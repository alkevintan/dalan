package com.aktivesolutions.dalan.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackPolicyTest {
    private fun v(group: Int, idx: Int, codec: VideoCodec, h: Int, fps: Float = 30f, br: Int = h * 1000) =
        VideoTrack(group, idx, codec, h, fps, br)

    // group 0 = mp4 (avc + av1), group 1 = webm (vp9), as built by DashManifestParser2
    private val tracks = listOf(
        v(0, 0, VideoCodec.AVC, 1080), v(0, 1, VideoCodec.AVC, 720), v(0, 2, VideoCodec.AVC, 480), v(0, 3, VideoCodec.AVC, 360),
        v(0, 4, VideoCodec.AV1, 1080), v(0, 5, VideoCodec.AV1, 720),
        v(0, 6, VideoCodec.AVC, 720, 60f), v(0, 7, VideoCodec.AVC, 1080, 60f),
        v(1, 0, VideoCodec.VP9, 1080), v(1, 1, VideoCodec.VP9, 720), v(1, 2, VideoCodec.VP9, 360),
    )

    @Test fun codecDetection() {
        assertEquals(VideoCodec.AVC, TrackPolicy.codecOf("avc1.64001F", "video/mp4"))
        assertEquals(VideoCodec.VP9, TrackPolicy.codecOf("vp9", "video/webm"))
        assertEquals(VideoCodec.VP9, TrackPolicy.codecOf("vp09.00.40.08", null))
        assertEquals(VideoCodec.AV1, TrackPolicy.codecOf("av01.0.05M.08", "video/mp4"))
        assertEquals(VideoCodec.OTHER, TrackPolicy.codecOf(null, null))
    }

    @Test fun prefersAvcAt720Default() {
        val p = TrackPolicy.pickVideo(tracks, PolicySettings())!!
        assertEquals(0, p.group)
        // adaptive set: only avc, <=720, 30 fps, never av1/vp9/60fps
        assertEquals(listOf(3, 2, 1), p.tracks)
    }

    @Test fun neverAv1() {
        val av1Only = listOf(v(0, 0, VideoCodec.AV1, 720), v(0, 1, VideoCodec.AVC, 360))
        val p = TrackPolicy.pickVideo(av1Only, PolicySettings(maxHeight = 720))!!
        assertEquals(listOf(1), p.tracks)
        assertFalse(TrackPolicy.availableHeights(av1Only, PolicySettings()).contains(720))
    }

    @Test fun respectsMaxHeight() {
        val p = TrackPolicy.pickVideo(tracks, PolicySettings(maxHeight = 480))!!
        assertEquals(listOf(3, 2), p.tracks)
    }

    @Test fun maxHeight1080IsAvcOnly() {
        val p = TrackPolicy.pickVideo(tracks, PolicySettings(maxHeight = 1080, hardwareVp9 = true))!!
        assertEquals(0, p.group)
        assertEquals(0, p.tracks.last()) // the 1080p avc track (highest)
    }

    @Test fun fpsCap30AndAllow60() {
        val capped = TrackPolicy.pickVideo(tracks, PolicySettings(maxHeight = 720))!!
        assertFalse(capped.tracks.contains(6))
        val allow60 = TrackPolicy.pickVideo(tracks, PolicySettings(maxHeight = 720, allow60fps = true))!!
        // 720p60 avc is the top choice now; adaptive set within the group includes it
        assertTrue(allow60.tracks.contains(6))
    }

    @Test fun softwareVp9OnlyUpTo480() {
        val vp9Only = listOf(v(1, 0, VideoCodec.VP9, 720), v(1, 1, VideoCodec.VP9, 480), v(1, 2, VideoCodec.VP9, 360))
        val p = TrackPolicy.pickVideo(vp9Only, PolicySettings(hardwareVp9 = false))
        assertEquals(Pick(1, listOf(1)), p) // tier 5: highest software VP9 <= 480p, single track
        assertEquals(5, TrackPolicy.chooseVideo(vp9Only, PolicySettings())!!.tier)
    }

    @Test fun vp9PreferredOnlyWithHardwareAndSetting() {
        val avc = v(0, 0, VideoCodec.AVC, 720)
        val vp9 = v(1, 0, VideoCodec.VP9, 720)
        val noHw = TrackPolicy.pickVideo(listOf(avc, vp9), PolicySettings(preferVp9 = true, hardwareVp9 = false))!!
        assertEquals(0, noHw.group)
        val hw = TrackPolicy.pickVideo(listOf(avc, vp9), PolicySettings(preferVp9 = true, hardwareVp9 = true))!!
        assertEquals(1, hw.group)
        val hwNotPreferred = TrackPolicy.pickVideo(listOf(avc, vp9), PolicySettings(preferVp9 = false, hardwareVp9 = true))!!
        assertEquals(0, hwNotPreferred.group)
    }

    @Test fun explicitHeightIgnoresCapButNotCodec() {
        val p = TrackPolicy.pickVideo(tracks, PolicySettings(maxHeight = 360), chosenHeight = 720)!!
        assertEquals(Pick(0, listOf(1)), p)
        val missing = TrackPolicy.pickVideo(tracks, PolicySettings(), chosenHeight = 540)!! // nearest lower
        assertEquals(Pick(0, listOf(2)), missing)
    }

    @Test fun availableHeights() {
        assertEquals(listOf(1080, 720, 480, 360), TrackPolicy.availableHeights(tracks, PolicySettings()))
    }

    @Test fun unsupportedTracksAreSkipped() {
        val t = listOf(v(0, 0, VideoCodec.AVC, 1080).copy(supported = false), v(0, 1, VideoCodec.AVC, 720), v(0, 2, VideoCodec.AVC, 480))
        val p = TrackPolicy.pickVideo(t, PolicySettings(maxHeight = 1080))!!
        assertFalse(p.tracks.contains(0))
    }

    @Test fun everythingTallerThanCapPicksLowest() {
        val t = listOf(v(0, 0, VideoCodec.AVC, 720), v(0, 1, VideoCodec.AVC, 480))
        val p = TrackPolicy.pickVideo(t, PolicySettings(maxHeight = 360))!!
        assertEquals(listOf(1), p.tracks)
    }

    @Test fun noTracks() = assertEquals(null, TrackPolicy.pickVideo(emptyList(), PolicySettings()))

    @Test fun audioPrefersAacThenOpus() {
        val a = listOf(
            AudioTrack(0, 0, isAac = true, isOpus = false, bitrate = 48_000),
            AudioTrack(0, 1, isAac = true, isOpus = false, bitrate = 128_000),
            AudioTrack(1, 0, isAac = false, isOpus = true, bitrate = 160_000),
        )
        assertEquals(Pick(0, listOf(1)), TrackPolicy.pickAudio(a))
        assertEquals(Pick(1, listOf(0)), TrackPolicy.pickAudio(a.filter { !it.isAac }))
        assertEquals(null, TrackPolicy.pickAudio(emptyList()))
    }

    // ---- tiers

    private fun tier(tracks: List<VideoTrack>, s: PolicySettings = PolicySettings()) = TrackPolicy.chooseVideo(tracks, s)!!

    @Test fun sixtyFpsOnlyVideoPicks720p60H264() {
        val t = listOf(v(0, 0, VideoCodec.AVC, 1080, 60f), v(0, 1, VideoCodec.AVC, 720, 60f), v(0, 2, VideoCodec.AVC, 480, 60f))
        val c = tier(t) // default: 720 cap, 30 fps cap
        assertEquals(2, c.tier)
        assertEquals(1, c.pick.tracks.last())
        assertEquals(listOf(2, 1), c.pick.tracks) // adaptive 480p60 -> 720p60, never 1080
    }

    @Test fun sixtyFpsOnlyFallsBackToLowerWhen720p60NotHandled() {
        val t = listOf(v(0, 0, VideoCodec.AVC, 1080, 60f), v(0, 1, VideoCodec.AVC, 720, 60f).copy(supported = false), v(0, 2, VideoCodec.AVC, 480, 60f))
        assertEquals(Pick(0, listOf(2)), tier(t).pick)
    }

    @Test fun tier3LowestAboveCapWhenNothingWithinCap() {
        val t = listOf(v(0, 0, VideoCodec.AVC, 1080), v(0, 1, VideoCodec.AVC, 900), v(0, 2, VideoCodec.AVC, 800))
        val c = tier(t, PolicySettings(maxHeight = 480))
        assertEquals(3, c.tier)
        assertEquals(Pick(0, listOf(2)), c.pick)
    }

    @Test fun h264BeatsVp9EvenIfOnlyAboveCap() {
        val t = listOf(v(0, 0, VideoCodec.AVC, 1080), v(1, 0, VideoCodec.VP9, 360))
        val c = tier(t, PolicySettings(maxHeight = 720, hardwareVp9 = true))
        assertEquals(3, c.tier)
        assertEquals(0, c.pick.group)
    }

    @Test fun tier4HardwareVp9() {
        val t = listOf(v(1, 0, VideoCodec.VP9, 720), v(1, 1, VideoCodec.VP9, 480), v(0, 0, VideoCodec.AVC, 720).copy(supported = false))
        val c = tier(t, PolicySettings(hardwareVp9 = true))
        assertEquals(4, c.tier)
        assertEquals(listOf(1, 0), c.pick.tracks)
    }

    @Test fun tier5SoftwareVp9PrefersThirtyFps() {
        val t = listOf(v(1, 0, VideoCodec.VP9, 480, 60f), v(1, 1, VideoCodec.VP9, 360, 30f), v(1, 2, VideoCodec.VP9, 720, 30f))
        val c = tier(t)
        assertEquals(5, c.tier)
        assertEquals(Pick(1, listOf(1)), c.pick)
        val only60 = listOf(v(1, 0, VideoCodec.VP9, 480, 60f), v(1, 1, VideoCodec.VP9, 360, 60f))
        assertEquals(Pick(1, listOf(0)), tier(only60).pick)
    }

    @Test fun tier6H264ExceedingCapabilitiesLowestFirst() {
        val t = listOf(
            v(0, 0, VideoCodec.AVC, 1080).copy(supported = false, exceeds = true),
            v(0, 1, VideoCodec.AVC, 720).copy(supported = false, exceeds = true),
            v(0, 2, VideoCodec.AVC, 360).copy(supported = false, exceeds = true),
            v(1, 0, VideoCodec.VP9, 720).copy(supported = false, exceeds = true),
        )
        val c = tier(t)
        assertEquals(6, c.tier)
        assertEquals(Pick(0, listOf(2)), c.pick)
    }

    @Test fun neverAv1EvenAsLastResort() {
        val t = listOf(v(0, 0, VideoCodec.AV1, 360), v(0, 1, VideoCodec.AV1, 720).copy(supported = false, exceeds = true))
        assertEquals(null, TrackPolicy.pickVideo(t, PolicySettings()))
    }

    @Test fun nonPositiveHeightIsLowest() {
        // tier 6: unknown height sorts as the lowest
        val t = listOf(
            v(0, 0, VideoCodec.AVC, 480).copy(supported = false, exceeds = true),
            v(0, 1, VideoCodec.AVC, -1).copy(supported = false, exceeds = true),
        )
        assertEquals(Pick(0, listOf(1)), tier(t).pick)
        // tier 1: unknown height is ranked below known ones
        val t1 = listOf(v(0, 0, VideoCodec.AVC, 0), v(0, 1, VideoCodec.AVC, 360))
        assertEquals(1, tier(t1).pick.tracks.last())
    }

    // ---- audio

    private fun a(group: Int, idx: Int, itag: Int, br: Int, ch: Int = 2, drc: Boolean = false, aac: Boolean = itag == 140 || itag == 139,
                  lang: String? = null, orig: Boolean = true) =
        AudioTrack(group, idx, isAac = aac, isOpus = !aac, bitrate = br, itag = itag, channelCount = ch, isDrc = drc, language = lang, isOriginal = orig)

    @Test fun audioExcludesSurround() {
        val t = listOf(a(0, 0, 380, 384_000, 6, aac = true), a(0, 1, 258, 256_000, 6, aac = true), a(0, 2, 140, 128_000), a(0, 3, 251, 140_000))
        assertEquals(Pick(0, listOf(2)), TrackPolicy.pickAudio(t))
        // bitrate > 300000 alone marks 5.1 (SmartTube is51Audio)
        assertEquals(Pick(0, listOf(1)), TrackPolicy.pickAudio(listOf(a(0, 0, 999, 320_000), a(0, 1, 251, 130_000))))
        // only surround available: better than nothing
        assertNotNull(TrackPolicy.pickAudio(listOf(a(0, 0, 380, 384_000, 6, aac = true))))
    }

    @Test fun audioPrefersNonDrcThenItagOrder() {
        val t = listOf(a(0, 0, 140, 128_000, drc = true), a(0, 1, 251, 140_000), a(0, 2, 250, 70_000), a(0, 3, 139, 48_000))
        assertEquals(Pick(0, listOf(3)), TrackPolicy.pickAudio(t)) // 139 non-DRC beats DRC 140
        val order = listOf(a(0, 0, 250, 70_000), a(0, 1, 251, 140_000), a(0, 2, 139, 48_000), a(0, 3, 140, 128_000))
        assertEquals(Pick(0, listOf(3)), TrackPolicy.pickAudio(order))
        assertEquals(Pick(0, listOf(2)), TrackPolicy.pickAudio(order.filter { it.itag != 140 }))
        assertEquals(Pick(0, listOf(1)), TrackPolicy.pickAudio(order.filter { it.itag == 250 || it.itag == 251 })) // 251 > 250
    }

    @Test fun audioLanguageOriginalFirstThenSystem() {
        val dubDe = a(0, 0, 140, 128_000, lang = "German (dubbed)", orig = false)
        val orig = a(1, 0, 140, 128_000, lang = "English (original)", orig = true)
        val dubFr = a(2, 0, 140, 128_000, lang = "French (dubbed)", orig = false)
        // original wins whatever the group order is
        assertEquals(Pick(1, listOf(0)), TrackPolicy.pickAudio(listOf(dubDe, orig, dubFr), "German"))
        // no original: system language, then lowest group
        assertEquals(Pick(2, listOf(0)), TrackPolicy.pickAudio(listOf(dubDe, dubFr), "French"))
        assertEquals(Pick(0, listOf(0)), TrackPolicy.pickAudio(listOf(dubDe, dubFr), "Spanish"))
        // audio-description tracks are the last resort
        val desc = a(0, 0, 140, 128_000, lang = "English (descriptive)", orig = false).copy(isDescriptive = true)
        assertEquals(Pick(1, listOf(0)), TrackPolicy.pickAudio(listOf(desc, dubFr.copy(group = 1)), "German"))
    }
}
