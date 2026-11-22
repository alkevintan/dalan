package com.aktivesolutions.dalan.data

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import java.util.Locale

/** Typed view over the default shared preferences (the same file the PreferenceActivity edits). */
class Settings(context: Context) {
    val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    val maxQuality: Int get() = prefs.getString("max_quality", "720")?.toIntOrNull() ?: 720
    val allow60fps: Boolean get() = prefs.getBoolean("allow_60fps", false)
    val preferVp9: Boolean get() = prefs.getBoolean("prefer_vp9", false)
    val backgroundPlay: Boolean get() = prefs.getBoolean("background_play", true)
    val autoplayNext: Boolean get() = prefs.getBoolean("autoplay_next", true)

    /** ISO 3166 country code for content (YouTube `gl`); empty means the system country. */
    val region: String get() = prefs.getString("region", "") ?: ""
    val autoUpdateCheck: Boolean get() = prefs.getBoolean("auto_update_check", true)

    /** "system" (default), "light" or "dark". */
    val themeMode: String get() = prefs.getString("theme", "system") ?: "system"

    /** "off", "system" or a BCP-47 language code. */
    var captionLang: String
        get() = prefs.getString("caption_lang", "off") ?: "off"
        set(v) { prefs.edit().putString("caption_lang", v).apply() }

    fun resolvedCaptionLang(): String? = when (val l = captionLang) {
        "off", "" -> null
        "system" -> Locale.getDefault().language
        else -> l
    }

    companion object {
        @Volatile private var instance: Settings? = null
        fun get(context: Context): Settings =
            instance ?: synchronized(this) { instance ?: Settings(context).also { instance = it } }
    }
}
