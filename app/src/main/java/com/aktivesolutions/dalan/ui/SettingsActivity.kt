package com.aktivesolutions.dalan.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.preference.PreferenceCategory
import android.preference.PreferenceFragment
import android.view.MenuItem
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.aktivesolutions.dalan.R
import com.aktivesolutions.dalan.player.Codecs

class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_Dalan)
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
            findPreference("clear_cache").setOnPreferenceClickListener {
                ImageLoader.clearCaches()
                Toast.makeText(activity, R.string.pref_cache_cleared, Toast.LENGTH_SHORT).show()
                true
            }
            findPreference("about").setOnPreferenceClickListener { showAbout(); true }
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
