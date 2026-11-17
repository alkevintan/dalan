package com.aktivesolutions.dalan.player

import android.util.Log
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlaybackException
import com.google.android.exoplayer2.RendererCapabilities
import com.google.android.exoplayer2.source.TrackGroupArray
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.trackselection.MappingTrackSelector
import com.google.android.exoplayer2.trackselection.TrackSelection

/**
 * DefaultTrackSelector whose video/audio/text choices come from [TrackPolicy]. Selection is
 * redone (via [invalidate]) when quality or caption settings change.
 */
class DalanTrackSelector : DefaultTrackSelector() {

    @Volatile var settings: PolicySettings = PolicySettings()
    /** null = Auto. */
    @Volatile var chosenHeight: Int? = null
    /** Whether the side-loaded caption track should be rendered. */
    @Volatile var captionsEnabled: Boolean = false

    /** Heights offered by the last selection (for the quality dialog), highest first. */
    @Volatile var availableHeights: List<Int> = emptyList()
        private set
    /** Height of the track(s) the policy picked last, 0 if unknown. */
    @Volatile var selectedHeight: Int = 0
        private set

    private val systemLanguageName: String? =
        java.util.Locale.getDefault().getDisplayLanguage(java.util.Locale.ENGLISH).takeIf { it.isNotBlank() }

    /** Re-runs track selection with the current [settings], [chosenHeight] and [captionsEnabled]. */
    fun reselect() = invalidate()

    @Throws(ExoPlaybackException::class)
    override fun selectAllTracks(
        mappedTrackInfo: MappingTrackSelector.MappedTrackInfo,
        rendererFormatSupports: Array<Array<IntArray>>,
        rendererMixedMimeTypeAdaptationSupports: IntArray,
        params: Parameters,
    ): Array<TrackSelection.Definition?> {
        val defs = super.selectAllTracks(mappedTrackInfo, rendererFormatSupports, rendererMixedMimeTypeAdaptationSupports, params)
        var videoDone = false
        var audioDone = false
        for (r in 0 until mappedTrackInfo.rendererCount) {
            val groups = mappedTrackInfo.getTrackGroups(r)
            when (mappedTrackInfo.getRendererType(r)) {
                C.TRACK_TYPE_VIDEO -> if (!videoDone && groups.length > 0) {
                    val tracks = videoTracks(groups, rendererFormatSupports[r])
                    availableHeights = TrackPolicy.availableHeights(tracks, settings)
                    val pick = TrackPolicy.pickVideo(tracks, settings, chosenHeight)
                    run {
                        val tier = if (chosenHeight == null) TrackPolicy.chooseVideo(tracks, settings)?.tier else null
                        Log.d(TAG, "video tracks (cap=${settings.maxHeight} fps60=${settings.allow60fps} hwVp9=${settings.hardwareVp9} chosen=$chosenHeight):\n" +
                            TrackPolicy.describeVideo(tracks) + "\n  -> pick=$pick tier=$tier")
                    }
                    if (pick != null) {
                        defs[r] = TrackSelection.Definition(groups.get(pick.group), pick.tracks.toIntArray(), C.SELECTION_REASON_UNKNOWN, null)
                        selectedHeight = groups.get(pick.group).getFormat(pick.tracks.last()).height
                        videoDone = true
                    }
                }
                C.TRACK_TYPE_AUDIO -> if (!audioDone && groups.length > 0) {
                    val aTracks = audioTracks(groups, rendererFormatSupports[r])
                    val pick = TrackPolicy.pickAudio(aTracks, systemLanguageName)
                    Log.d(TAG, "audio tracks:\n" + TrackPolicy.describeAudio(aTracks) + "\n  -> pick=$pick")
                    if (pick != null) {
                        defs[r] = TrackSelection.Definition(groups.get(pick.group), pick.tracks.toIntArray(), C.SELECTION_REASON_UNKNOWN, null)
                        audioDone = true
                    }
                }
                C.TRACK_TYPE_TEXT -> {
                    defs[r] = null
                    if (captionsEnabled) {
                        for (g in 0 until groups.length) {
                            val grp = groups.get(g)
                            for (t in 0 until grp.length) {
                                if (grp.getFormat(t).id == MediaSourceBuilder.CAPTION_FORMAT_ID) {
                                    defs[r] = TrackSelection.Definition(grp, intArrayOf(t), C.SELECTION_REASON_UNKNOWN, null)
                                }
                            }
                        }
                    }
                }
            }
        }
        return defs
    }

    private fun videoTracks(groups: TrackGroupArray, support: Array<IntArray>): List<VideoTrack> {
        val out = ArrayList<VideoTrack>()
        for (g in 0 until groups.length) {
            val grp = groups.get(g)
            for (t in 0 until grp.length) {
                val f = grp.getFormat(t)
                val level = support[g][t] and RendererCapabilities.FORMAT_SUPPORT_MASK
                out += VideoTrack(
                    g, t, TrackPolicy.codecOf(f.codecs, f.sampleMimeType),
                    f.height, f.frameRate.takeIf { it > 0f } ?: 0f, f.bitrate.coerceAtLeast(0),
                    supported = level == RendererCapabilities.FORMAT_HANDLED,
                    exceeds = level == RendererCapabilities.FORMAT_EXCEEDS_CAPABILITIES,
                )
            }
        }
        return out
    }

    private fun audioTracks(groups: TrackGroupArray, support: Array<IntArray>): List<AudioTrack> {
        val out = ArrayList<AudioTrack>()
        for (g in 0 until groups.length) {
            val grp = groups.get(g)
            for (t in 0 until grp.length) {
                val f = grp.getFormat(t)
                val codecs = (f.codecs ?: "").lowercase()
                val mime = (f.sampleMimeType ?: "").lowercase()
                val handled = (support[g][t] and RendererCapabilities.FORMAT_SUPPORT_MASK) == RendererCapabilities.FORMAT_HANDLED
                val lang = f.language?.lowercase()
                out += AudioTrack(
                    g, t,
                    isAac = codecs.startsWith("mp4a") || mime == "audio/mp4a-latm",
                    isOpus = codecs.contains("opus") || mime == "audio/opus",
                    bitrate = f.bitrate.coerceAtLeast(0), supported = handled,
                    itag = f.id?.toIntOrNull() ?: 0,
                    channelCount = if (f.channelCount > 0) f.channelCount else 2,
                    isDrc = f.isDrc || (f.id?.endsWith("drc") == true),
                    language = f.language,
                    isOriginal = lang.isNullOrEmpty() || lang == "und" || lang.contains("(original)"),
                    isDescriptive = lang?.contains("(descriptive)") == true || lang?.contains("(dubbed-auto)") == true,
                )
            }
        }
        return out
    }

    private companion object { const val TAG = "DalanTracks" }
}
