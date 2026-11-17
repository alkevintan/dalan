package com.aktivesolutions.dalan.util

/** A YouTube video reference extracted from a URL or shared text. */
data class ParsedVideo(val videoId: String, val startSeconds: Int = 0)

/** Pure-Kotlin YouTube URL parser (no android.net.Uri, so it is unit-testable on the JVM). */
object VideoUrlParser {
    private val ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val URL_IN_TEXT = Regex("""(?i)(?:https?://)?(?:[a-z0-9-]+\.)?(?:youtube\.com|youtu\.be)/[^\s<>"']*""")
    private val HOST_PATH = Regex("""(?i)^(?:https?://)?([^/?#\s]+)(/[^?#\s]*)?(?:\?([^#\s]*))?(?:#(\S*))?$""")
    private val TIME_HMS = Regex("""^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s?)?$""", RegexOption.IGNORE_CASE)

    /** Parses a single URL. Returns null if it isn't a recognised YouTube video URL. */
    fun parse(url: String?): ParsedVideo? {
        if (url.isNullOrBlank()) return null
        val m = HOST_PATH.matchEntire(url.trim()) ?: return null
        val host = m.groupValues[1].lowercase().substringBefore(':')
        val path = m.groupValues[2]
        val query = parseQuery(m.groupValues[3])
        val fragment = parseQuery(m.groupValues[4])

        val id: String? = when {
            host == "youtu.be" -> path.trim('/').substringBefore('/')
            host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com") -> {
                val segs = path.split('/').filter { it.isNotEmpty() }
                when {
                    segs.isEmpty() -> null
                    segs[0] == "watch" -> query["v"]
                    segs[0] in setOf("shorts", "embed", "live", "v", "e") && segs.size >= 2 -> segs[1]
                    else -> null
                }
            }
            else -> null
        }
        if (id == null || !ID.matches(id)) return null
        val t = query["t"] ?: query["start"] ?: query["time_continue"] ?: fragment["t"]
        return ParsedVideo(id, parseTimestamp(t))
    }

    /** Finds the first YouTube URL in free text (e.g. a share intent) and parses it. */
    fun parseFromText(text: String?): ParsedVideo? {
        if (text.isNullOrBlank()) return null
        parse(text)?.let { return it }
        for (match in URL_IN_TEXT.findAll(text)) {
            parse(match.value.trimEnd('.', ',', ')', ';'))?.let { return it }
        }
        return null
    }

    /** Accepts "90", "90s", "1m30s", "1h2m3s". Returns 0 for anything else. */
    fun parseTimestamp(t: String?): Int {
        if (t.isNullOrBlank()) return 0
        val m = TIME_HMS.matchEntire(t.trim()) ?: return 0
        if (m.groupValues.drop(1).all { it.isEmpty() }) return 0
        val h = m.groupValues[1].toLongOrNull() ?: 0
        val mi = m.groupValues[2].toLongOrNull() ?: 0
        val s = m.groupValues[3].toLongOrNull() ?: 0
        val total = h * 3600 + mi * 60 + s
        return if (total in 0..Int.MAX_VALUE) total.toInt() else 0
    }

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        for (part in q.split('&')) {
            if (part.isEmpty()) continue
            val k = part.substringBefore('=')
            val v = part.substringAfter('=', "")
            out.putIfAbsent(k, v)
        }
        return out
    }
}
