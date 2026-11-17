package com.aktivesolutions.dalan.data

import android.content.Context

/** SharedPreferences-backed [ResumePositions] (videoId -> positionMs, 300 entries, LRU). */
class ResumeStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("resume", Context.MODE_PRIVATE)
    private val positions = ResumePositions.parse(prefs.getString(KEY, null))

    @Synchronized fun resumeFor(videoId: String): Long = positions.resumeFor(videoId)

    @Synchronized fun save(videoId: String, positionMs: Long, durationMs: Long) {
        positions.put(videoId, positionMs, durationMs)
        prefs.edit().putString(KEY, positions.serialize()).apply()
    }

    private companion object { const val KEY = "positions" }
}
