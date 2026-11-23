package com.aktivesolutions.dalan.data

import com.liskovsoft.googlecommon.common.locale.LocaleManager
import java.text.Collator
import java.util.Locale

/**
 * Applies the content-language preference to the vendored parser, which reads the language from [LocaleManager] on every request (the override outlives unhold()).
 * YouTube ignores the client-sent country (it follows the IP), but the language does steer which content is returned.
 */
object ContentLanguage {
    /** Common YouTube `hl` values offered in Settings. */
    val TAGS = listOf(
        "en", "en-GB", "es", "es-419", "pt", "pt-PT", "fr", "de", "it", "nl", "pl", "ru", "uk", "tr",
        "ar", "hi", "id", "ms", "fil", "vi", "th", "ja", "ko", "zh-CN", "zh-TW", "zh-HK", "sv", "da",
        "no", "fi", "cs", "el", "he", "hu", "ro", "bn", "ta", "te", "ur", "fa",
    )

    /** Bumped when the effective language changes: screens compare it in onResume to know when to reload their feeds. */
    @Volatile var version: Int = 0
        private set

    @Volatile private var applied: String? = null

    /** [tag] is a BCP-47 language tag, or empty for the system language. */
    @Synchronized
    fun apply(tag: String) {
        LocaleManager.setLanguageOverride(tag.ifEmpty { null }) // survives LocaleManager.unhold(); null = system language
        if (applied != null && applied != tag) version++
        applied = tag
    }

    /** Tag to its own-language display name (capitalised, tag as fallback), sorted by that name. */
    fun labelled(tags: List<String> = TAGS): List<Pair<String, String>> {
        val collator = Collator.getInstance()
        return tags
            .map { tag ->
                val l = Locale.forLanguageTag(tag)
                val name = l.getDisplayName(l).replaceFirstChar { it.uppercase(l) }
                tag to name.ifBlank { tag }
            }
            .sortedWith { a, b -> collator.compare(a.second, b.second) }
    }
}
