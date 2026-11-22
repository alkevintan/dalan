package com.aktivesolutions.dalan.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.preference.Preference
import android.preference.PreferenceCategory
import android.preference.PreferenceFragment
import android.view.MenuItem
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.aktivesolutions.dalan.R
import com.aktivesolutions.dalan.data.Filters
import com.aktivesolutions.dalan.data.Repo
import com.aktivesolutions.dalan.player.Codecs

class SettingsActivity : ThemedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.settings)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            fragmentManager.beginTransaction().replace(android.R.id.content, Frag()).commit()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    @Suppress("DEPRECATION")
    class Frag : PreferenceFragment() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            addPreferencesFromResource(R.xml.preferences)
            if (!Codecs.hasHardwareVp9) {
                val vp9 = findPreference("prefer_vp9")
                (findPreference("cat_playback") as? PreferenceCategory)?.removePreference(vp9)
            }
            findPreference("theme").setOnPreferenceChangeListener { _, _ ->
                // The new value is saved after this returns; restyle this screen once it has been.
                activity.window.decorView.post { (activity as? ThemedActivity)?.checkTheme() }
                true
            }
            findPreference("clear_cache").setOnPreferenceClickListener {
                ImageLoader.clearCaches()
                Toast.makeText(activity, R.string.pref_cache_cleared, Toast.LENGTH_SHORT).show()
                true
            }
            findPreference("account").setOnPreferenceClickListener {
                if (Repo.isSignedIn()) confirmSignOut() else startActivity(Intent(activity, SignInActivity::class.java))
                true
            }
            findPreference("filters").setOnPreferenceClickListener { startActivity(Intent(activity, FiltersActivity::class.java)); true }
            findPreference("about").setOnPreferenceClickListener { showAbout(); true }
        }

        private var signOutAction: io.reactivex.disposables.Disposable? = null

        override fun onDestroy() {
            signOutAction?.dispose()
            super.onDestroy()
        }

        override fun onResume() {
            super.onResume()
            refreshAccount()
            refreshFilters()
        }

        private fun refreshFilters() {
            val p: Preference = findPreference("filters") ?: return
            p.summary = resources.getQuantityString(R.plurals.pref_filters_summary, Filters.activeCount(), Filters.activeCount())
        }

        private fun refreshAccount() {
            val p: Preference = findPreference("account") ?: return
            val acc = if (Repo.isSignedIn()) Repo.account() else null
            if (acc != null || Repo.isSignedIn()) {
                val name = acc?.name?.takeIf { it.isNotBlank() } ?: acc?.email?.takeIf { it.isNotBlank() } ?: ""
                p.title = if (name.isNotEmpty()) getString(R.string.pref_signed_in_as, name) else getString(R.string.signin_done_generic)
                p.summary = acc?.email?.takeIf { it.isNotBlank() && it != name } ?: getString(R.string.pref_sign_out)
            } else {
                p.setTitle(R.string.pref_sign_in)
                p.setSummary(R.string.pref_sign_in_sum)
            }
        }

        private fun confirmSignOut() {
            AlertDialog.Builder(activity)
                .setTitle(R.string.pref_sign_out_title)
                .setMessage(R.string.pref_sign_out_msg)
                .setPositiveButton(R.string.pref_sign_out) { _, _ ->
                    signOutAction?.dispose()
                    signOutAction = Repo.signOut().subscribe({ refreshAccount() }, { refreshAccount() })
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        private fun showAbout() {
            val sb = StringBuilder()
            fun append(title: String, path: String) {
                try {
                    val t = activity.assets.open(path).bufferedReader().use { it.readText() }
                    sb.append("\n\n==== ").append(title).append(" ====\n\n").append(t)
                } catch (_: Exception) { }
            }
            append("Dalan (LICENSE)", "LICENSE")
            append("THIRD_PARTY.md", "THIRD_PARTY.md")
            // Every bundled licence text (assets/licenses/*): MIT texts, EPL-1.0, Apache-2.0, commons-io, ...
            val names = try { activity.assets.list("licenses")?.sorted() ?: emptyList() } catch (_: Exception) { emptyList() }
            for (n in names) append(n, "licenses/$n")
            val text = if (sb.isEmpty()) "Dalan is MIT. See THIRD_PARTY.md in the source tree." else sb.toString().trimStart()
            val tv = TextView(activity)
            tv.text = text
            tv.textSize = 12f
            tv.setPadding(dp(activity, 16), dp(activity, 8), dp(activity, 16), dp(activity, 8))
            val sv = ScrollView(activity)
            sv.addView(tv)
            AlertDialog.Builder(activity).setTitle(R.string.about).setView(sv).setPositiveButton(android.R.string.ok, null).show()
        }
    }
}
