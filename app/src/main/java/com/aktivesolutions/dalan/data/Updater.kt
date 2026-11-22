package com.aktivesolutions.dalan.data

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import com.aktivesolutions.dalan.BuildConfig
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A GitHub release that has an installable `.apk` asset. [version] is the tag without the leading `v`. */
data class Release(
    val version: String,
    val name: String,
    val notes: String,
    val pageUrl: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
)

/** In-app updates from GitHub Releases: check, download, verify. Installing is in ui/UpdateUi. */
object Updater {
    private const val API = "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    }

    /** Numeric parts of "v0.1.10-beta+x" -> [0, 1, 10]. Suffixes after `-` or `+` are ignored. */
    fun parseVersion(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
            .split('.').map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    /** Negative, zero or positive like compareTo; missing trailing parts count as 0 (0.2 == 0.2.0). */
    fun compareVersions(a: String, b: String): Int {
        val x = parseVersion(a)
        val y = parseVersion(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    /** Parses the `releases/latest` JSON; null for drafts, prereleases, or releases without an `.apk` asset. */
    fun parseRelease(json: String): Release? {
        val o = JSONObject(json)
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        val tag = o.optString("tag_name").removePrefix("v").removePrefix("V")
        if (tag.isEmpty()) return null
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            val url = a.optString("browser_download_url")
            if (name.endsWith(".apk", ignoreCase = true) && url.isNotEmpty()) {
                return Release(
                    version = tag,
                    name = o.optString("name").ifBlank { tag },
                    notes = o.optString("body").trim(),
                    pageUrl = o.optString("html_url"),
                    apkName = name,
                    apkUrl = url,
                    apkSize = a.optLong("size", -1),
                )
            }
        }
        return null
    }

    /** Emits the latest release if it is newer than this build, otherwise null. Runs on io, delivered on the main thread. */
    fun check(): Single<Release?> = Single.fromCallable<Release?> {
        val req = Request.Builder().url(API)
            .header("User-Agent", "Dalan-Updater")
            .header("Accept", "application/vnd.github+json")
            .build()
        val body = client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code()}")
            r.body()!!.string()
        }
        parseRelease(body)?.takeIf { compareVersions(it.version, BuildConfig.VERSION_NAME) > 0 }
    }.subscribeOn(Schedulers.io()).observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())

    fun updatesDir(context: Context): File = File(context.externalCacheDir ?: context.cacheDir, "updates")

    /**
     * Downloads the APK into the updates dir (older files there are deleted first) and checks its length against
     * the asset size. Disposing the Single cancels the HTTP call. [onProgress] is called on the io thread, 0..100.
     */
    fun download(context: Context, r: Release, onProgress: (Int) -> Unit): Single<File> {
        val dir = updatesDir(context)
        return Single.create<File> { emitter ->
            dir.deleteRecursively()
            dir.mkdirs()
            val out = File(dir, "dalan-${r.version}.apk")
            val call: Call = client.newCall(Request.Builder().url(r.apkUrl).header("User-Agent", "Dalan-Updater").build())
            emitter.setCancellable { call.cancel() }
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code()}")
                    val body = resp.body()!!
                    val total = if (r.apkSize > 0) r.apkSize else body.contentLength()
                    var done = 0L
                    var last = -1
                    body.byteStream().use { ins ->
                        out.outputStream().use { os ->
                            val buf = ByteArray(32 * 1024)
                            while (true) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                os.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    val pct = (done * 100 / total).toInt().coerceAtMost(100)
                                    if (pct != last) { last = pct; onProgress(pct) }
                                }
                            }
                        }
                    }
                    if (r.apkSize > 0 && done != r.apkSize) throw IOException("size mismatch: $done != ${r.apkSize}")
                }
                if (!emitter.isDisposed) emitter.onSuccess(out)
            } catch (e: Throwable) {
                out.delete()
                if (!emitter.isDisposed) emitter.onError(e)
            }
        }.subscribeOn(Schedulers.io()).observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
    }

    enum class Verdict { OK, NOT_OURS, NOT_NEWER, BAD_SIGNATURE }

    /**
     * Checks the downloaded APK is Dalan, newer than the installed build, and signed with the installed certificate(s).
     * Parses and verifies the whole APK: call it off the main thread.
     */
    @Suppress("DEPRECATION")
    fun verify(context: Context, apk: File): Verdict {
        val pm = context.packageManager
        val modern = Build.VERSION.SDK_INT >= 28
        // API 28's getPackageArchiveInfo only collects certificates when GET_SIGNATURES is set, so pass both there.
        val flags = if (modern) PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: return Verdict.NOT_OURS
        if (archive.packageName != context.packageName) return Verdict.NOT_OURS
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        if (code <= BuildConfig.VERSION_CODE) return Verdict.NOT_NEWER
        val installed = pm.getPackageInfo(context.packageName, flags)
        val ok = if (modern) {
            val a = archive.signingInfo
            val i = installed.signingInfo
            when {
                a == null || i == null -> false
                // Multiple signers: no rotation, the sets must match exactly (as the platform requires).
                a.hasMultipleSigners() || i.hasMultipleSigners() ->
                    a.apkContentsSigners.toSet() == i.apkContentsSigners.toSet()
                // One signer: the update's lineage (rotated keys included) must contain the installed signer.
                else -> i.apkContentsSigners.firstOrNull()?.let { it in a.signingCertificateHistory } ?: false
            }
        } else {
            val a = archive.signatures?.toSet().orEmpty()
            a.isNotEmpty() && a == installed.signatures?.toSet().orEmpty()
        }
        return if (ok) Verdict.OK else Verdict.BAD_SIGNATURE
    }
}
