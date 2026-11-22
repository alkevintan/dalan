package com.aktivesolutions.dalan.data

import java.text.Normalizer
import java.util.Locale
import java.util.regex.Pattern

enum class Scope { TITLE, CHANNEL, BOTH }

/**
 * One user-defined filter: hides a video when ANY term matches its title and / or channel name (per [scope]),
 * or when its channel id is in [channelIds] (id -> display name).
 */
data class FilterRule(
    val id: String,
    val name: String,
    val terms: List<String>,
    val scope: Scope = Scope.BOTH,
    val channelIds: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val expiresAt: Long? = null, // epoch ms; null = never
) {
    fun isActive(nowMs: Long) = enabled && (expiresAt == null || expiresAt > nowMs)
}

/** Pure Kotlin (no android imports) so it runs in plain JVM unit tests. */
object TermParser {
    private val SPLIT = Regex("[\\n\\r,]+")

    /** Splits on newlines and commas, trims, drops blanks and case-insensitive duplicates. */
    fun parse(text: String): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (p in text.split(SPLIT)) {
            val t = p.trim()
            if (t.isEmpty()) continue
            if (seen.add(t.lowercase(Locale.ROOT))) out.add(t)
        }
        return out
    }
}

fun parseTerms(text: String): List<String> = TermParser.parse(text)

/**
 * Compiled form of a rule set at a point in time. Only enabled, unexpired rules take part. All terms of a scope
 * are compiled into ONE alternation pattern for the fast "does anything match" check; per-rule patterns are
 * used only to name the rule once that check hits. Build it again when the rules change or [nextExpiryMs] passes.
 */
class FilterMatcher(rules: List<FilterRule>, nowMs: Long) {
    private class Compiled(val rule: FilterRule, val title: Pattern?, val channel: Pattern?)

    private val compiled: List<Compiled>
    private val titlePattern: Pattern?
    private val channelPattern: Pattern?
    private val channelRules = HashMap<String, FilterRule>()

    /** Earliest expiry among the rules in use, or Long.MAX_VALUE when none expires. */
    val nextExpiryMs: Long

    init {
        val list = ArrayList<Compiled>()
        var next = Long.MAX_VALUE
        val titleParts = ArrayList<String>()
        val channelParts = ArrayList<String>()
        for (r in rules) {
            if (!r.isActive(nowMs)) continue
            r.expiresAt?.let { if (it < next) next = it }
            for (id in r.channelIds.keys) if (id.isNotEmpty()) channelRules.putIfAbsent(id, r)
            val regexes = r.terms.mapNotNull { termRegex(it) }
            if (regexes.isEmpty()) continue
            val joined = regexes.joinToString("|") { "(?:$it)" }
            val p = Pattern.compile(joined)
            val t = if (r.scope != Scope.CHANNEL) p else null
            val c = if (r.scope != Scope.TITLE) p else null
            if (t != null) titleParts.add(joined)
            if (c != null) channelParts.add(joined)
            list.add(Compiled(r, t, c))
        }
        compiled = list
        titlePattern = if (titleParts.isEmpty()) null else Pattern.compile(titleParts.joinToString("|") { "(?:$it)" })
        channelPattern = if (channelParts.isEmpty()) null else Pattern.compile(channelParts.joinToString("|") { "(?:$it)" })
        nextExpiryMs = next
    }

    fun isEmpty() = titlePattern == null && channelPattern == null && channelRules.isEmpty()

    /**
     * The first rule that hides this video, or null. [titleOnly] ignores channel blocks and channel-name matches
     * (used on a channel's own page, where every video would otherwise vanish).
     */
    fun hiddenBy(title: String?, channelName: String?, channelId: String?, titleOnly: Boolean = false): FilterRule? {
        if (isEmpty()) return null
        if (!titleOnly && !channelId.isNullOrEmpty()) channelRules[channelId]?.let { return it }
        val tp = titlePattern
        if (tp != null && !title.isNullOrEmpty()) {
            val h = normalize(title)
            if (tp.matcher(h).find()) return compiled.firstOrNull { it.title?.matcher(h)?.find() == true }?.rule
        }
        val cp = if (titleOnly) null else channelPattern
        if (cp != null && !channelName.isNullOrEmpty()) {
            val h = normalize(channelName)
            if (cp.matcher(h).find()) return compiled.firstOrNull { it.channel?.matcher(h)?.find() == true }?.rule
        }
        return null
    }

    fun isHidden(title: String?, channelName: String?, channelId: String?, titleOnly: Boolean = false) =
        hiddenBy(title, channelName, channelId, titleOnly) != null

    fun <T> countHidden(items: Iterable<T>, title: (T) -> String?, channelName: (T) -> String?, channelId: (T) -> String?): Int =
        if (isEmpty()) 0 else items.count { isHidden(title(it), channelName(it), channelId(it)) }

    companion object {
        private const val WORD = "[\\p{L}\\p{N}]"
        private val MARKS = Regex("\\p{M}+")
        private const val SEP = "[^\\p{L}\\p{N}]+"
        // A word: letters / digits, with an optional leading # and trailing + / # kept as literal parts ("c++", "c#", "#tag").
        private val TOKEN = Regex("#?[\\p{L}\\p{N}]+[+#]*")

        /** Lowercase, decomposed, combining marks stripped: "Pokémon" -> "pokemon". */
        fun normalize(s: String): String =
            MARKS.replace(Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD), "")

        private fun usesSpacelessScript(s: String): Boolean {
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                when (Character.UnicodeScript.of(cp)) {
                    Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
                    Character.UnicodeScript.THAI, Character.UnicodeScript.HANGUL -> return true
                    else -> {}
                }
                i += Character.charCount(cp)
            }
            return false
        }

        /**
         * Regex for one term, or null when it has no letters / digits. Whole words only (lookarounds on letters and
         * digits, so unicode works); a trailing `*` makes the last word a prefix; words of a phrase may be separated
         * by any run of non-letter/digit characters. `+` and `#` attached to a word stay literal ("c++" does not
         * match "vitamin c"). Terms in scripts written without spaces (Han, kana, Thai, Hangul) match as substrings.
         */
        internal fun termRegex(term: String): String? {
            val t = term.trim()
            val prefix = t.endsWith("*")
            val norm = normalize(t)
            val words = TOKEN.findAll(norm).map { it.value }.toList()
            if (words.isEmpty()) return null
            val body = words.joinToString(SEP) { Pattern.quote(it) }
            if (usesSpacelessScript(norm)) return body
            // The literal + / # already ends the word, so "c++" also hides "C++11".
            val open = prefix || words.last().let { it.endsWith("+") || it.endsWith("#") }
            return "(?<!$WORD)$body" + if (open) "" else "(?!$WORD)"
        }
    }
}
