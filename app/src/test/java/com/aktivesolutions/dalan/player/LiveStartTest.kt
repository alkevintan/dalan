package com.aktivesolutions.dalan.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveStartTest {
    @Test fun dashFormatsEdge() =
        // now - start = 100_000, minus 3 * 5000
        assertEquals(85_000L, LiveStart.startPositionMs(SourceKind.DASH_FORMATS, 1_000_000, 7, 5_000_000, 1_100_000))

    @Test fun sabrEdgeIncludesStartSegmentOffset() =
        // 100_000 + 10 * 5000 = 150_000, minus 15_000
        assertEquals(135_000L, LiveStart.startPositionMs(SourceKind.SABR, 1_000_000, 10, 5_000_000, 1_100_000))

    @Test fun clampsToZero() =
        assertEquals(0L, LiveStart.startPositionMs(SourceKind.DASH_FORMATS, 1_000_000, 0, 5_000_000, 1_005_000))

    @Test fun segmentDurationFallback() =
        assertEquals(85_000L, LiveStart.startPositionMs(SourceKind.DASH_FORMATS, 0, 0, 0, 100_000))

    @Test fun shortSegmentsUseBackoffFloor() =
        // 1 s segments: 3 * 1000 < 15_000 floor
        assertEquals(85_000L, LiveStart.startPositionMs(SourceKind.DASH_FORMATS, 1_000_000, 7, 1_000_000, 1_100_000))

    @Test fun longSegmentsBackOffThreeSegments() =
        // 10 s segments: 3 * 10_000 = 30_000 > floor
        assertEquals(70_000L, LiveStart.startPositionMs(SourceKind.DASH_FORMATS, 1_000_000, 7, 10_000_000, 1_100_000))

    @Test fun nullForDynamicManifests() {
        assertNull(LiveStart.startPositionMs(SourceKind.DASH_URL, 1, 0, 5_000_000, 100))
        assertNull(LiveStart.startPositionMs(SourceKind.HLS_URL, 1, 0, 5_000_000, 100))
    }

    @Test fun otherKindsZero() =
        assertEquals(0L, LiveStart.startPositionMs(SourceKind.URL_LIST, 1, 0, 5_000_000, 100))
}
