package com.aktivesolutions.dalan.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.widget.TextView
import com.aktivesolutions.dalan.R
import com.aktivesolutions.dalan.data.Settings

/** Light / dark theme selection (Settings > Theme). "System" follows the night bit on Android 10+ and is dark below. */
object Themes {
    fun isDark(context: Context): Boolean = when (Settings.get(context).themeMode) {
        "dark" -> true
        "light" -> false
        else -> if (Build.VERSION.SDK_INT >= 29)
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        else true
    }

    fun themeRes(dark: Boolean, player: Boolean): Int = when {
        player && dark -> R.style.Theme_Dalan_Player_Dark
        player -> R.style.Theme_Dalan_Player_Light
        dark -> R.style.Theme_Dalan_Dark
        else -> R.style.Theme_Dalan_Light
    }
}

/**
 * Base class for every screen: applies the chosen theme before the window is created and recreates the
 * activity on return (onResume) when the Theme setting, or the system night mode, no longer matches.
 */
abstract class ThemedActivity : Activity() {
    protected var appliedDark = true
    protected open val playerTheme: Boolean get() = false
    /** True once recreate() has been requested: callers should stop touching the UI. */
    protected var recreating = false
        private set

    /** Re-themes in place (return true) instead of recreating the activity. */
    protected open fun restyle(): Boolean = false

    /** Applies a changed theme setting / system night mode: restyle in place if supported, else recreate. */
    fun checkTheme() {
        val dark = Themes.isDark(this)
        if (dark == appliedDark || recreating) return
        if (restyle()) { appliedDark = dark; return }
        recreating = true
        recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        appliedDark = Themes.isDark(this)
        setTheme(Themes.themeRes(appliedDark, playerTheme))
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        checkTheme()
    }
}

/** Resolves a colour theme attribute (R.attr.dalan*, android.R.attr.*) of this context's theme. */
fun Context.themeColor(attr: Int): Int {
    // obtainStyledAttributes handles colour-state-list attrs (android.R.attr.textColorPrimary) too: it returns their default colour.
    val a = theme.obtainStyledAttributes(intArrayOf(attr))
    try { return a.getColor(0, 0) } finally { a.recycle() }
}

/** Resource id of the theme's borderless ripple (for icon buttons). */
fun Context.borderlessRippleRes(): Int {
    val tv = TypedValue()
    theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
    return tv.resourceId
}

/** A vector glyph tinted with [color] (own mutable copy). */
fun Context.glyph(res: Int, color: Int): Drawable {
    val d = resources.getDrawable(res, theme).mutate()
    d.setTint(color)
    return d
}

/** Puts a 24dp glyph left of the text of a Button / TextView. */
fun TextView.setStartGlyph(res: Int, color: Int = currentTextColor) {
    val d = context.glyph(res, color)
    val s = dp(context, 24)
    d.setBounds(0, 0, s, s)
    setCompoundDrawablesRelative(d, null, null, null)
    compoundDrawablePadding = dp(context, 8)
}
