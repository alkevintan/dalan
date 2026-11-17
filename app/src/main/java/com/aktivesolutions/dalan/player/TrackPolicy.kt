package com.aktivesolutions.dalan.player

enum class VideoCodec { AVC, VP9, AV1, OTHER }

/** A video track as the policy sees it (decoupled from ExoPlayer so it can be unit-tested). */
data class VideoTrack(
    val group: Int,
    val index: Int,
    val codec: VideoCodec,
    val height: Int,
    val fps: Float,
    val bitrate: Int,
    /** RendererCapabilities FORMAT_HANDLED. */
    val supported: Boolean = true,
    /** RendererCapabilities FORMAT_EXCEEDS_CAPABILITIES (a decoder exists but may not keep up). */
    val exceeds: Boolean = false,
)

data class AudioTrack(
    val group: Int,
    val index: Int,
    val isAac: Boolean,
    val isOpus: Boolean,
    val bitrate: Int,
    val supported: Boolean = true,
    /** The YouTube itag (Format.id), 0 if unknown. */
    val itag: Int = 0,
    val channelCount: Int = 2,
    val isDrc: Boolean = false,
    /** Language label as built by MediaServiceCore, e.g. "English (original)"; null for single-track videos. */
    val language: String? = null,
    /** Original / default audio track (or the video has only one language). */
    val isOriginal: Boolean = true,
    /** Audio description or auto-dubbed track: only used if nothing else exists. */
    val isDescriptive: Boolean = false,
)

data class PolicySettings(
    val maxHeight: Int = 720,
    val allow60fps: Boolean = false,
    val preferVp9: Boolean = false,
    val hardwareVp9: Boolean = false,
) {
    val maxFps: Float get() = if (allow60fps) 60.5f else 30.5f
}

/** Which tracks of which group to play. More than one track in a group means adaptive. */
data class Pick(val group: Int, val tracks: List<Int>) {
    val isAdaptive: Boolean get() = tracks.size > 1
}

/**
 * Track selection rules for the SM-T550 (hardware H.264/VP8 only, VP9 software-only, no AV1).
 *
 * Video is chosen from tiers, taking the first non-empty one:
 *  1. HANDLED H.264 within the height cap and the fps cap (adaptive inside one group)
 *  2. HANDLED H.264 within the height cap at any fps, highest first
 *  3. HANDLED H.264 above the cap, lowest first
 *  4. hardware VP9 under the same rules (1-3; only when a hardware decoder exists)
 *  5. HANDLED software VP9 up to 480p, preferring <= 30 fps
 *  6. H.264 that EXCEEDS_CAPABILITIES, lowest first
 *  7. (last resort, not in the review list) software VP9 above 480p / VP9 exceeding capabilities, lowest first
 * AV1 is never picked. A height <= 0 counts as the lowest height.
 */
object TrackPolicy {
    private const val SOFTWARE_VP9_MAX_HEIGHT = 480

    /** Surround itags (SmartTube TrackSelectorUtil.is51Audio also treats bitrate > 300000 as 5.1). */
    private val SURROUND_ITAGS = setOf(256, 258, 327, 328, 380, 338)
    private const val SURROUND_BITRATE = 300_000
    private val AUDIO_ITAG_ORDER = listOf(140, 139, 251, 250)

    fun codecOf(codecs: String?, mimeType: String?): VideoCodec {
        val c = (codecs ?: "").lowercase()
        val m = (mimeType ?: "").lowercase()
        return when {
            c.startsWith("avc") || c.contains("avc1") || m == "video/avc" -> VideoCodec.AVC
            c.startsWith("vp9") || c.startsWith("vp09") || c.contains("vp9") || c.contains("vp09") || m == "video/x-vnd.on2.vp9" -> VideoCodec.VP9
            c.startsWith("av01") || c.contains("av01") || m == "video/av01" -> VideoCodec.AV1
            else -> VideoCodec.OTHER
        }
    }

    private fun h(t: VideoTrack) = if (t.height > 0) t.height else 0
    private fun fpsOk(t: VideoTrack, s: PolicySettings) = t.fps <= 0f || t.fps <= s.maxFps
    private fun isH264(t: VideoTrack) = t.codec == VideoCodec.AVC || t.codec == VideoCodec.OTHER

    /** Tracks the quality dialog may offer / an explicit choice may use (never AV1). */
    private fun selectable(tracks: List<VideoTrack>, s: PolicySettings): List<VideoTrack> {
        val handled = tracks.filter {
            it.supported && it.codec != VideoCodec.AV1 &&
                (it.codec != VideoCodec.VP9 || s.hardwareVp9 || h(it) <= SOFTWARE_VP9_MAX_HEIGHT)
        }
        val pool = handled.ifEmpty { tracks.filter { it.exceeds && isH264(it) } }
            .ifEmpty { tracks.filter { it.supported && it.codec == VideoCodec.VP9 } }
        val strict = pool.filter { fpsOk(it, s) }
        return strict.ifEmpty { pool }
    }

    /** Distinct heights the quality dialog can offer, highest first. */
    fun availableHeights(tracks: List<VideoTrack>, s: PolicySettings): List<Int> =
        selectable(tracks, s).map { it.height }.filter { it > 0 }.distinct().sortedDescending()

    private val highestFirst = Comparator<VideoTrack> { a, b ->
        var c = h(b).compareTo(h(a))
        if (c == 0) c = b.fps.compareTo(a.fps)
        if (c == 0) c = b.bitrate.compareTo(a.bitrate)
        c
    }
    /** Tier 2: highest height, but the lowest fps among those (they are all above the fps cap). */
    private val highestLowFps = Comparator<VideoTrack> { a, b ->
        var c = h(b).compareTo(h(a))
        if (c == 0) c = a.fps.compareTo(b.fps)
        if (c == 0) c = b.bitrate.compareTo(a.bitrate)
        c
    }
    private val lowestFirst = Comparator<VideoTrack> { a, b ->
        var c = h(a).compareTo(h(b))
        if (c == 0) c = a.fps.compareTo(b.fps)
        if (c == 0) c = b.bitrate.compareTo(a.bitrate)
        c
    }

    /** One result of the tier walk: the pick and the tier that produced it (1..7). */
    data class VideoChoice(val pick: Pick, val tier: Int)

    private fun adaptive(set: List<VideoTrack>, top: VideoTrack): Pick {
        val same = set.filter { it.group == top.group && it.codec == top.codec && h(it) in 1..h(top) }
            .sortedBy { it.height }.map { it.index }
        return Pick(top.group, if (same.size >= 2) same else listOf(top.index))
    }

    /** Tiers 1-3 for one codec family (H.264 or hardware VP9). */
    private fun family(handled: List<VideoTrack>, s: PolicySettings, baseTier: Int): VideoChoice? {
        val cap = s.maxHeight
        val within = handled.filter { h(it) <= cap }
        val t1 = within.filter { fpsOk(it, s) }
        if (t1.isNotEmpty()) return VideoChoice(adaptive(t1, t1.sortedWith(highestFirst).first()), baseTier)
        if (within.isNotEmpty()) return VideoChoice(adaptive(within, within.sortedWith(highestLowFps).first()), baseTier + 1)
        val above = handled.filter { h(it) > cap }
        if (above.isNotEmpty()) {
            val t = above.sortedWith(lowestFirst).first()
            return VideoChoice(Pick(t.group, listOf(t.index)), baseTier + 2)
        }
        return null
    }

    fun chooseVideo(tracks: List<VideoTrack>, s: PolicySettings): VideoChoice? {
        val ok = tracks.filter { it.codec != VideoCodec.AV1 }
        val handled = ok.filter { it.supported }
        val h264 = handled.filter { isH264(it) }
        val hwVp9 = if (s.hardwareVp9) handled.filter { it.codec == VideoCodec.VP9 } else emptyList()

        val first = if (s.preferVp9 && hwVp9.isNotEmpty()) family(hwVp9, s, 1) else null
        first?.let { return it }
        family(h264, s, 1)?.let { return it }
        family(hwVp9, s, 4)?.let { return it }

        val sw = handled.filter { it.codec == VideoCodec.VP9 && h(it) <= SOFTWARE_VP9_MAX_HEIGHT }
        if (sw.isNotEmpty()) {
            val pool = sw.filter { it.fps <= 0f || it.fps <= 30.5f }.ifEmpty { sw }
            val t = pool.sortedWith(highestFirst).first()
            return VideoChoice(Pick(t.group, listOf(t.index)), 5)
        }
        ok.filter { it.exceeds && isH264(it) }.sortedWith(lowestFirst).firstOrNull()
            ?.let { return VideoChoice(Pick(it.group, listOf(it.index)), 6) }
        (handled.filter { it.codec == VideoCodec.VP9 } + ok.filter { it.exceeds && it.codec == VideoCodec.VP9 })
            .sortedWith(lowestFirst).firstOrNull()?.let { return VideoChoice(Pick(it.group, listOf(it.index)), 7) }
        return null
    }

    /**
     * Picks the video track(s).
     * @param chosenHeight null for Auto (tiers above); otherwise the user's explicit choice (the cap is ignored).
     */
    fun pickVideo(tracks: List<VideoTrack>, s: PolicySettings, chosenHeight: Int? = null): Pick? {
        if (tracks.isEmpty()) return null
        if (chosenHeight == null) return chooseVideo(tracks, s)?.pick
        val pool = selectable(tracks, s)
        if (pool.isEmpty()) return null
        val ranked = Comparator<VideoTrack> { a, b ->
            var c = h(b).compareTo(h(a))
            if (c == 0) c = codecRank(a.codec, s).compareTo(codecRank(b.codec, s))
            if (c == 0) c = b.fps.compareTo(a.fps)
            if (c == 0) c = b.bitrate.compareTo(a.bitrate)
            c
        }
        val best = pool.filter { it.height == chosenHeight }.sortedWith(ranked).firstOrNull()
            ?: pool.filter { it.height in 1..chosenHeight }.sortedWith(ranked).firstOrNull()
            ?: pool.sortedWith(ranked).last() // everything is taller: take the lowest
        return Pick(best.group, listOf(best.index))
    }

    fun is51Audio(t: AudioTrack) = t.itag in SURROUND_ITAGS || t.bitrate > SURROUND_BITRATE || t.channelCount > 2

    private fun audioRank(t: AudioTrack): Int {
        val i = AUDIO_ITAG_ORDER.indexOf(t.itag)
        return if (i >= 0) i else AUDIO_ITAG_ORDER.size + (if (t.isAac) 0 else if (t.isOpus) 1 else 2)
    }

    /**
     * @param systemLanguage English display name of the system language ("German"), used to rank
     *                       dubbed tracks when there is no original/default track.
     */
    fun pickAudio(tracks: List<AudioTrack>, systemLanguage: String? = null): Pick? {
        val playable = tracks.filter { it.supported }.ifEmpty { tracks }
        if (playable.isEmpty()) return null
        // No surround unless nothing else exists.
        val stereo = playable.filter { !is51Audio(it) }.ifEmpty { playable }
        // Language group: original/default first, then the system language, then the rest, lowest group index first.
        fun langRank(g: List<AudioTrack>): Int {
            val t = g.first()
            return when {
                g.any { it.isDescriptive } && g.all { it.isDescriptive } -> 3
                t.isOriginal -> 0
                systemLanguage != null && t.language?.startsWith(systemLanguage, ignoreCase = true) == true -> 1
                else -> 2
            }
        }
        val groups = stereo.groupBy { it.group }.toSortedMap().values.toList()
        val group = groups.minWithOrNull(compareBy<List<AudioTrack>> { langRank(it) }.thenBy { it.first().group })!!
        // Prefer non-DRC, then 140 > 139 > 251 > 250, then bitrate.
        val best = group.sortedWith(
            compareBy<AudioTrack> { it.isDrc }.thenBy { audioRank(it) }.thenByDescending { it.bitrate }
        ).first()
        return Pick(best.group, listOf(best.index))
    }

    private fun codecRank(c: VideoCodec, s: PolicySettings): Int = when (c) {
        VideoCodec.AVC -> if (s.preferVp9 && s.hardwareVp9) 1 else 0
        VideoCodec.VP9 -> if (s.preferVp9 && s.hardwareVp9) 0 else 1
        VideoCodec.OTHER -> 2
        VideoCodec.AV1 -> 3
    }

    fun describeVideo(tracks: List<VideoTrack>): String = tracks.joinToString("\n") {
        "  v g${it.group}/${it.index} ${it.codec} ${it.height}p ${it.fps}fps ${it.bitrate}bps " +
            (if (it.supported) "HANDLED" else if (it.exceeds) "EXCEEDS" else "UNSUPPORTED")
    }

    fun describeAudio(tracks: List<AudioTrack>): String = tracks.joinToString("\n") {
        "  a g${it.group}/${it.index} itag=${it.itag} ${it.bitrate}bps ch=${it.channelCount} drc=${it.isDrc} " +
            "lang=${it.language} orig=${it.isOriginal} ${if (it.supported) "HANDLED" else "UNSUPPORTED"}"
    }
}
