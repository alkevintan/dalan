package com.aktivesolutions.dalan

import com.liskovsoft.youtubeapi.service.YouTubeServiceManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Instrumented smoke test: run by hand on the device (needs network).
 * Resolves a long-lived public video through MediaServiceCore and checks it yields media.
 * DalanApp.onCreate() has already initialised GlobalPreferences when this runs.
 *
 *   ./gradlew :app:connectedDebugAndroidTest   (or install the test APK and run with `am instrument`)
 */
class ResolverSmokeTest {
    // "Me at the zoo" (first YouTube video): public and stable.
    private val videoId = "jNQXAC9IVRw"

    @Test fun formatInfoContainsMedia() {
        val info = YouTubeServiceManager.instance().mediaItemService.getFormatInfo(videoId)
        assertNotNull(info)
        assertFalse("unplayable: ${info.playabilityReason}", info.isUnplayable)
        assertTrue("no media in formatInfo", info.containsMedia())
    }
}
