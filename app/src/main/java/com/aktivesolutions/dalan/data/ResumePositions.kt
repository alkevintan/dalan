package com.aktivesolutions.dalan.data

/**
 * Pure LRU map of videoId -> (positionMs, durationMs), capped at [cap] entries.
 * Serialises to a compact string so it can live in a single SharedPreferences value.
 */
class ResumePositions(private val cap: Int = 300) {
    data class Entry(val positionMs: Long, val durationMs: Long)

    private val map = LinkedHashMap<String, Entry>() // insertion order == recency (oldest first)

    val size: Int get() = map.size

    fun get(videoId: String): Entry? = map[videoId]

    /** Position to resume from, or 0 if nothing is stored or fewer than [minRemainingMs] remain. */
    fun resumeFor(videoId: String, minRemainingMs: Long = 30_000): Long {
        val e = map[videoId] ?: return 0
        if (e.durationMs > 0 && e.durationMs - e.positionMs < minRemainingMs) return 0
        return e.positionMs
    }

    /** Stores a position; removes the entry if the video is (nearly) finished or at the very start. */
    fun put(videoId: String, positionMs: Long, durationMs: Long, minRemainingMs: Long = 30_000) {
        map.remove(videoId)
        val nearEnd = durationMs > 0 && durationMs - positionMs < minRemainingMs
        if (positionMs < 5_000 || nearEnd) return
        map[videoId] = Entry(positionMs, durationMs)
        while (map.size > cap) {
            val eldest = map.entries.iterator()
            eldest.next()
            eldest.remove()
        }
    }

    fun remove(videoId: String) { map.remove(videoId) }

    fun keysOldestFirst(): List<String> = map.keys.toList()

    fun serialize(): String = map.entries.joinToString(",") { "${it.key}:${it.value.positionMs}:${it.value.durationMs}" }

    companion object {
        fun parse(s: String?, cap: Int = 300): ResumePositions {
            val r = ResumePositions(cap)
            if (s.isNullOrEmpty()) return r
            for (part in s.split(',')) {
                val f = part.split(':')
                if (f.size != 3) continue
                val p = f[1].toLongOrNull() ?: continue
                val d = f[2].toLongOrNull() ?: continue
                r.map[f[0]] = Entry(p, d)
                if (r.map.size > cap) {
                    val it = r.map.entries.iterator(); it.next(); it.remove()
                }
            }
            return r
        }
    }
}
