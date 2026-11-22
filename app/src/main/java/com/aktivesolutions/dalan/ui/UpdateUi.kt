package com.aktivesolutions.dalan.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import android.os.Build
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.aktivesolutions.dalan.R
import com.aktivesolutions.dalan.data.Release
import com.aktivesolutions.dalan.data.Settings
import com.aktivesolutions.dalan.data.Updater
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import java.io.File

/** Dialogs and install hand-off for in-app updates (the logic lives in [Updater]). */
object UpdateUi {
    private const val KEY_LAST_CHECK = "update_last_check"
    private const val KEY_SKIPPED = "update_skipped_version"
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** At most once per 24 h and only if enabled; silent on failure, and honours "Skip this version". */
    fun autoCheck(activity: Activity) {
        val s = Settings.get(activity)
        val now = System.currentTimeMillis()
        val last = s.prefs.getLong(KEY_LAST_CHECK, 0)
        if (!s.autoUpdateCheck || (now >= last && now - last < DAY_MS)) return // now < last: clock went back, check again
        Updater.check().subscribe({ r ->
            s.prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
            if (r != null && r.version != s.prefs.getString(KEY_SKIPPED, null) && alive(activity)) showAvailable(activity, r, manual = false)
        }, { })
    }

    private fun alive(a: Activity) = !a.isFinishing && !(Build.VERSION.SDK_INT >= 17 && a.isDestroyed)

    /** dismiss() on a dialog whose activity was destroyed (e.g. Settings rotated mid-download) throws. */
    private fun safeDismiss(d: android.app.Dialog) { try { if (d.isShowing) d.dismiss() } catch (_: IllegalArgumentException) { } }

    fun showAvailable(activity: Activity, r: Release, manual: Boolean) {
        val tv = TextView(activity).apply {
            text = r.notes.ifEmpty { activity.getString(R.string.update_no_notes) }
            textSize = 14f
            setPadding(dp(activity, 20), dp(activity, 8), dp(activity, 20), dp(activity, 8))
        }
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_title, r.version))
            .setView(ScrollView(activity).apply { addView(tv) })
            .setPositiveButton(R.string.update_install) { _, _ -> start(activity, r) }
            .setNeutralButton(R.string.update_later, null)
            .setNegativeButton(R.string.update_skip) { _, _ ->
                Settings.get(activity).prefs.edit().putString(KEY_SKIPPED, r.version).apply()
            }
            .show()
    }

    private fun start(activity: Activity, r: Release) {
        // canRequestPackageInstalls() is always false for targetSdk < 26; the system installer then asks for the
        // "install unknown apps" permission itself, so only pre-check it when targeting 26+.
        if (Build.VERSION.SDK_INT >= 26 && activity.applicationInfo.targetSdkVersion >= 26 &&
            !activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity)
                .setMessage(R.string.update_need_permission)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    try {
                        activity.startActivity(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                    } catch (_: ActivityNotFoundException) { }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        download(activity, r)
    }

    @Suppress("DEPRECATION")
    private fun download(activity: Activity, r: Release) {
        val dlg = ProgressDialog(activity).apply {
            setTitle(R.string.update_downloading)
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(true)
        }
        val app = activity.applicationContext
        val sub = Updater.download(activity, r) { p -> activity.runOnUiThread { dlg.progress = p } }
            .observeOn(Schedulers.io())
            .map { file -> file to Updater.verify(app, file) } // parses and checks the whole APK: keep it off the UI thread
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ (file, verdict) ->
                safeDismiss(dlg)
                if (alive(activity)) install(activity, r, file, verdict) else if (verdict != Updater.Verdict.OK) file.delete()
            }, { e ->
                safeDismiss(dlg)
                if (alive(activity) && e !is java.util.concurrent.CancellationException) Toast.makeText(activity, R.string.update_download_failed, Toast.LENGTH_LONG).show()
            })
        dlg.setOnCancelListener { sub.dispose() }
        dlg.show()
    }

    private fun install(activity: Activity, r: Release, file: File, verdict: Updater.Verdict) {
        if (verdict != Updater.Verdict.OK) {
            file.delete()
            val msg = when (verdict) {
                Updater.Verdict.NOT_OURS -> activity.getString(R.string.update_bad_package)
                Updater.Verdict.NOT_NEWER -> activity.getString(R.string.update_not_newer)
                else -> activity.getString(R.string.update_bad_signature, r.pageUrl)
            }
            AlertDialog.Builder(activity)
                .setMessage(msg)
                .setPositiveButton(R.string.update_open_page) { _, _ ->
                    try { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.pageUrl))) } catch (_: ActivityNotFoundException) { }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        try {
            // The pre-N installer only accepts file:// (the APK is in the world-readable external cache there).
            val uri = if (Build.VERSION.SDK_INT >= 24) FileProvider.getUriForFile(activity, "${activity.packageName}.updates", file)
                else Uri.fromFile(file)
            activity.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Toast.makeText(activity, R.string.update_install_failed, Toast.LENGTH_LONG).show()
        }
    }
}
