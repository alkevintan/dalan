package com.aktivesolutions.dalan.player

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

class SourceOrderTest {
    /** Fake MediaItemFormatInfo: unspecified booleans are false, numbers 0, everything else null. */
    private fun fake(vararg values: Pair<String, Any?>): MediaItemFormatInfo {
        val map = values.toMap()
        return Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(MediaItemFormatInfo::class.java)
        ) { _, method, _ ->
            if (map.containsKey(method.name)) map[method.name]
            else when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Long.TYPE -> 0L
                java.lang.Integer.TYPE -> 0
                java.lang.Float.TYPE -> 0f
                else -> null
            }
        } as MediaItemFormatInfo
    }

    @Test fun unplayableWins() =
        assertEquals(SourceKind.UNPLAYABLE, SourceOrder.choose(fake("isUnplayable" to true, "containsDashFormats" to true)))

    @Test fun dashFormatsBeforeSabr() =
        assertEquals(SourceKind.DASH_FORMATS, SourceOrder.choose(fake("containsDashFormats" to true, "containsSabrFormats" to true)))

    @Test fun sabrWhenNoDash() =
        assertEquals(SourceKind.SABR, SourceOrder.choose(fake("containsSabrFormats" to true, "containsUrlFormats" to true)))

    @Test fun liveWithoutStartTimeUsesDashUrl() =
        assertEquals(
            SourceKind.DASH_URL,
            SourceOrder.choose(fake("isLive" to true, "getStartTimeMs" to 0L, "containsDashFormats" to true, "containsDashUrl" to true, "containsHlsUrl" to true))
        )

    @Test fun liveWithStartTimeUsesDashFormats() =
        assertEquals(
            SourceKind.DASH_FORMATS,
            SourceOrder.choose(fake("isLive" to true, "getStartTimeMs" to 1_000L, "containsDashFormats" to true, "containsDashUrl" to true))
        )

    @Test fun liveHls() =
        assertEquals(SourceKind.HLS_URL, SourceOrder.choose(fake("isLive" to true, "containsHlsUrl" to true)))

    @Test fun nonLiveHlsIsNotUsed() =
        assertEquals(SourceKind.URL_LIST, SourceOrder.choose(fake("containsHlsUrl" to true, "containsUrlFormats" to true)))

    @Test fun urlList() =
        assertEquals(SourceKind.URL_LIST, SourceOrder.choose(fake("containsUrlFormats" to true)))

    @Test fun empty() = assertEquals(SourceKind.EMPTY, SourceOrder.choose(fake()))
}
