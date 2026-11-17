package com.aktivesolutions.dalan.player

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo

enum class SourceKind { UNPLAYABLE, DASH_FORMATS, SABR, DASH_URL, HLS_URL, URL_LIST, EMPTY }

/**
 * Picks the kind of media source to build, in the same order as SmartTube's
 * VideoLoaderController.processFormatInfo(): unplayable, DASH formats, SABR, live DASH url,
 * live HLS url, progressive url list, empty (future live stream).
 */
object SourceOrder {
    fun choose(f: MediaItemFormatInfo): SourceKind {
        val acceptAdaptive = !(f.isLive && f.startTimeMs == 0L)
        val acceptDashLive = f.isLive && f.containsDashUrl()
        return when {
            f.isUnplayable -> SourceKind.UNPLAYABLE
            acceptAdaptive && f.containsDashFormats() -> SourceKind.DASH_FORMATS
            acceptAdaptive && f.containsSabrFormats() -> SourceKind.SABR
            acceptDashLive -> SourceKind.DASH_URL
            f.isLive && f.containsHlsUrl() -> SourceKind.HLS_URL
            f.containsUrlFormats() -> SourceKind.URL_LIST
            else -> SourceKind.EMPTY
        }
    }
}
