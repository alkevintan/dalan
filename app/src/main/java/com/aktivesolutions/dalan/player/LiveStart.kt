package com.aktivesolutions.dalan.player

/**
 * Start position for in-progress live streams.
 *
 * DASH_FORMATS and SABR build STATIC manifests covering a ~24h+ window, so player position 0 is the
 * start of that window, not the live edge. We have to seek to the edge ourselves. DASH_URL / HLS_URL
 * are dynamic manifests where ExoPlayer's default position already is the live edge.
 */
object LiveStart {
    // Floor: low-latency streams have 1-2 s segments; 3 of those is below the player's rebuffer
    // threshold, so we'd be requesting segments past the head within seconds.
    private const val MIN_BACKOFF_MS = 15_000L
    private const val BACKOFF_SEGMENTS = 3

    /** @return position in ms, or null to use the player's default position (live edge). */
    fun startPositionMs(
        kind: SourceKind,
        startTimeMs: Long,
        startSegmentNum: Int,
        segmentDurationUs: Int,
        nowMs: Long
    ): Long? {
        val segMs = if (segmentDurationUs > 0) segmentDurationUs / 1000L else 0L
        val backoff = maxOf(segMs * BACKOFF_SEGMENTS, MIN_BACKOFF_MS)
        val edge = when (kind) {
            SourceKind.DASH_FORMATS -> nowMs - startTimeMs
            // SabrManifestParser forces startSegmentNum = 0 for live, so position 0 = segment 0.
            SourceKind.SABR -> (nowMs - startTimeMs) + startSegmentNum.toLong() * segMs
            SourceKind.DASH_URL, SourceKind.HLS_URL -> return null
            else -> return 0L
        }
        return maxOf(0L, edge - backoff)
    }
}
