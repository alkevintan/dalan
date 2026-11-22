package com.aktivesolutions.dalan.data

import com.liskovsoft.googlecommon.common.locale.LocaleManager

/** Applies the Region preference to the vendored parser, which reads the country from [LocaleManager] on every request. */
object Region {
    /** Bumped when the effective region changes: screens compare it in onResume to know when to reload their feeds. */
    @Volatile var version: Int = 0
        private set

    @Volatile private var applied: String? = null

    /** [code] is an ISO 3166 country code, or empty for the system country. */
    @Synchronized
    fun apply(code: String) {
        if (code.isEmpty()) {
            LocaleManager.unhold() // the next instance() re-derives language and country from the system locale
            LocaleManager.instance()
        } else {
            LocaleManager.instance().country = code
        }
        if (applied != null && applied != code) version++
        applied = code
    }

    /** Re-sets the chosen country after something rebuilt [LocaleManager] (ServiceManager.invalidateCache() calls unhold()). */
    @Synchronized
    fun reapply() {
        applied?.takeIf { it.isNotEmpty() }?.let { LocaleManager.instance().country = it }
    }
}
