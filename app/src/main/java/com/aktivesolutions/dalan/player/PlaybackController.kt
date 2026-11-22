package com.aktivesolutions.dalan.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.aktivesolutions.dalan.data.Filters
import com.aktivesolutions.dalan.data.Repo
import com.aktivesolutions.dalan.data.ResumeStore
import com.aktivesolutions.dalan.data.Settings
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlaybackException
import com.google.android.exoplayer2.ParserException
import com.google.android.exoplayer2.upstream.HttpDataSource
import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.TrackGroupArray
import com.google.android.exoplayer2.source.sabr.parser.exceptions.SabrBackoffError
import com.google.android.exoplayer2.source.sabr.parser.exceptions.SabrPoTokenError
import com.google.android.exoplayer2.source.sabr.parser.exceptions.SabrReloadPlayerError
import com.google.android.exoplayer2.trackselection.TrackSelectionArray
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata
import com.liskovsoft.mediaserviceinterfaces.data.MediaSubtitle
import io.reactivex.disposables.Disposable

/**
 * Owns the playback logic for the one [SimpleExoPlayer] that lives in the PlaybackService:
 * resolve the format info, build the media source, apply track/caption policy, recover from
 * errors, persist the resume position and autoplay the next video. Main thread only.
 */
class PlaybackController(
    private val context: Context,
    val player: SimpleExoPlayer,
    val selector: DalanTrackSelector,
) : Player.EventListener {

    interface Listener {
        fun onVideoOpened(videoId: String)
        fun onMetadata(metadata: MediaItemMetadata)
        /** [retryable] true: the UI should offer a Retry button. */
        fun onError(message: String, retryable: Boolean)
        fun onErrorCleared()
        fun onTracksChanged()
        /** The metadata request failed: the UI should stop showing a loading skeleton. */
        fun onMetadataFailed() {}
    }

    var listener: Listener? = null

    /** True when the metadata request for the current video failed. */
    var metadataFailed = false
        private set

    /** Service-side hook: called when the video title / author / thumbnail become known. */
    var metadataObserver: (() -> Unit)? = null

    val settings = Settings.get(context)
    private val resume = ResumeStore(context)
    private val builder = MediaSourceBuilder(context)
    private val recovery = ErrorRecovery(3)
    private val handler = Handler(Looper.getMainLooper())

    var videoId: String? = null
        private set
    var metadata: MediaItemMetadata? = null
        private set
    var formatInfo: MediaItemFormatInfo? = null
        private set
    var lastError: String? = null
        private set
    var speed: Float = 1f
        private set
    var chosenCaption: MediaSubtitle? = null
        private set

    private var formatAction: Disposable? = null
    private var metaAction: Disposable? = null
    private var sessionMaxHeight: Int? = null // lowered after renderer/OOM errors
    private var currentKind: SourceKind? = null

    private var retryRunnable: Runnable? = null
    private var captionsFailed = false // per video: a caption caused a failure, don't add it again
    private var tickCount = 0
    private var ticking = false

    /** Runs once a second, only while playing: recovery health check + position save every 5 s. */
    private val playTick = object : Runnable {
        override fun run() {
            if (!isPlaying()) { ticking = false; return }
            recovery.onTick(player.playbackState == Player.STATE_READY, player.currentPosition)
            if (++tickCount % (SAVE_INTERVAL_MS / TICK_MS).toInt() == 0) savePosition()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun isPlaying() = player.playWhenReady &&
        (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)

    private fun updateTick() {
        if (isPlaying()) {
            if (!ticking) { ticking = true; handler.postDelayed(playTick, TICK_MS) }
        } else {
            ticking = false
            handler.removeCallbacks(playTick)
        }
    }

    private fun cancelRetry() {
        retryRunnable?.let { handler.removeCallbacks(it) }
        retryRunnable = null
    }

    init {
        player.addListener(this)
    }

    // ---------------------------------------------------------------- opening

    /** Opens [id]. If it's already the current video this does nothing, unless [force]. */
    fun open(id: String, startSeconds: Int = 0, force: Boolean = false) {
        if (!force && id == videoId && player.playbackState != Player.STATE_IDLE) return
        savePosition()
        if (id != videoId) captionsFailed = false
        videoId = id
        metadata = null
        metadataFailed = false
        formatInfo = null
        lastError = null
        sessionMaxHeight = null
        selector.chosenHeight = null
        chosenCaption = null
        recovery.reset(id)
        listener?.onVideoOpened(id)
        listener?.onErrorCleared()
        val startMs = if (startSeconds > 0) startSeconds * 1000L else resume.resumeFor(id)
        load(id, startMs)
        metaAction?.dispose()
        metaAction = Repo.metadata(id).subscribe({ m ->
            if (id == videoId) { metadata = m; listener?.onMetadata(m); metadataObserver?.invoke() }
        }, {
            Log.w(TAG, "metadata failed", it)
            if (id == videoId) { metadataFailed = true; listener?.onMetadataFailed() }
        })
    }

    /** Re-resolves the current video (after an error) and continues at [positionMs]. */
    private fun load(id: String, startMs: Long) {
        cancelRetry()
        formatAction?.dispose() // never more than one formatInfo request in flight
        player.stop(true)
        applyPolicySettings()
        formatAction = Repo.formatInfo(id).subscribe({ f ->
            if (id == videoId) onFormatInfo(f, startMs)
        }, { e ->
            if (id == videoId) onFailure(FailureKind.FORMAT_FETCH, e.message ?: e.javaClass.simpleName, null, startMs)
        })
    }

    private fun onFormatInfo(f: MediaItemFormatInfo, startMs: Long) {
        formatInfo = f
        metadataObserver?.invoke()
        val built = try {
            builder.build(f)
        } catch (e: Exception) {
            onFailure(FailureKind.UNEXPECTED, "Can't build media source: ${e.message}", null, startMs)
            return
        }
        currentKind = built.kind
        var source: MediaSource? = built.source
        when (built.kind) {
            SourceKind.UNPLAYABLE -> { fail(f.playabilityReason ?: "This video can't be played", retryable = false); return }
            SourceKind.EMPTY -> { fail("No playable stream (maybe a not-yet-started live stream).", retryable = true); return }
            else -> {}
        }
        // Captions
        if (captionsFailed) chosenCaption = null
        chosenCaption = chosenCaption ?: defaultCaption(f)
        val cap = chosenCaption
        selector.captionsEnabled = cap != null && !f.isLive
        if (cap != null && !f.isLive && source != null) {
            source = builder.withCaption(source, cap, durationUs(f))
        }
        start(source!!, startMs)
    }

    private fun start(source: MediaSource, startMs: Long) {
        if (startMs > 0) player.seekTo(startMs) else player.seekTo(0)
        player.prepare(source, startMs <= 0, true)
        player.playWhenReady = true
        updateTick()
    }

    private fun durationUs(f: MediaItemFormatInfo): Long {
        val s = f.lengthSeconds?.toLongOrNull() ?: 0
        return if (s > 0) s * 1_000_000 else C.TIME_UNSET
    }

    private fun applyPolicySettings() {
        val hw = Codecs.hasHardwareVp9
        val cap = minOf(settings.maxQuality, sessionMaxHeight ?: Int.MAX_VALUE)
        selector.settings = PolicySettings(cap, settings.allow60fps, settings.preferVp9 && hw, hw)
    }

    // ---------------------------------------------------------------- user controls

    fun availableHeights(): List<Int> = selector.availableHeights
    fun chosenHeight(): Int? = selector.chosenHeight
    fun currentHeight(): Int = selector.selectedHeight

    /** null = Auto. */
    fun setQuality(height: Int?) {
        selector.chosenHeight = height
        selector.reselect()
    }

    fun setSpeed(s: Float) {
        speed = s
        player.setPlaybackParameters(PlaybackParameters(s))
    }

    fun captionTracks(): List<MediaSubtitle> = formatInfo?.subtitles?.filter { it.baseUrl != null } ?: emptyList()

    /** null turns captions off. Remembers the language for next time. */
    fun setCaption(sub: MediaSubtitle?) {
        if (sub != null) captionsFailed = false // the user explicitly asked for it
        chosenCaption = sub
        settings.captionLang = sub?.languageCode ?: "off"
        val f = formatInfo ?: return
        if (f.isLive) return
        val pos = player.currentPosition
        val built = try { builder.build(f) } catch (e: Exception) { return }
        var source = built.source ?: return
        selector.captionsEnabled = sub != null
        if (sub != null) source = builder.withCaption(source, sub, durationUs(f))
        player.seekTo(pos)
        player.prepare(source, false, false)
    }

    private fun defaultCaption(f: MediaItemFormatInfo): MediaSubtitle? {
        if (captionsFailed) return null
        val lang = settings.resolvedCaptionLang() ?: return null
        return f.subtitles?.firstOrNull { it.baseUrl != null && it.languageCode?.substringBefore('-').equals(lang, true) }
    }

    /** Disables/enables the video renderer (the activity is hidden: keep only audio). */
    fun setVideoEnabled(enabled: Boolean) {
        for (i in 0 until player.rendererCount) {
            if (player.getRendererType(i) == C.TRACK_TYPE_VIDEO) {
                selector.setParameters(selector.buildUponParameters().setRendererDisabled(i, !enabled))
            }
        }
    }

    /** The user pressed Retry. */
    fun retry() {
        val id = videoId ?: return
        lastError = null
        listener?.onErrorCleared()
        cancelRetry()
        recovery.reset(id)
        load(id, positionForRecovery())
    }

    // ---------------------------------------------------------------- Player.EventListener

    override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) onEnded()
        updateTick()
        if (!playWhenReady) savePosition()
    }

    override fun onTracksChanged(trackGroups: TrackGroupArray, trackSelections: TrackSelectionArray) {
        listener?.onTracksChanged()
    }

    override fun onPlayerError(error: ExoPlaybackException) {
        val chain = generateSequence<Throwable>(error) { it.cause }.toList()
        val reload = chain.firstOrNull { it is SabrReloadPlayerError } as? SabrReloadPlayerError
        val kind = when {
            reload != null -> FailureKind.SABR_RELOAD
            chain.any { it is SabrBackoffError } -> FailureKind.SABR_BACKOFF
            chain.any { it is SabrPoTokenError } -> FailureKind.SABR_POTOKEN
            error.type == ExoPlaybackException.TYPE_OUT_OF_MEMORY -> FailureKind.OUT_OF_MEMORY
            error.type == ExoPlaybackException.TYPE_SOURCE -> FailureKind.SOURCE
            error.type == ExoPlaybackException.TYPE_RENDERER -> when (player.getRendererType(error.rendererIndex)) {
                C.TRACK_TYPE_VIDEO -> FailureKind.VIDEO_RENDERER
                C.TRACK_TYPE_AUDIO -> FailureKind.AUDIO_RENDERER
                else -> FailureKind.OTHER_RENDERER
            }
            else -> FailureKind.UNEXPECTED
        }
        // A subtitle problem must not kill playback: drop the caption and carry on.
        val subtitleError = error.type == ExoPlaybackException.TYPE_RENDERER &&
            player.getRendererType(error.rendererIndex) == C.TRACK_TYPE_TEXT
        val cap = chosenCaption
        val captionUrlFailed = cap != null && chain.any {
            (it is HttpDataSource.HttpDataSourceException && it.dataSpec.uri.toString() == cap.baseUrl) || it is ParserException
        }
        // A caption that was active when a second source failure happened is the prime suspect.
        val captionSuspect = cap != null && error.type == ExoPlaybackException.TYPE_SOURCE && recovery.attempts >= 1
        if (subtitleError || captionUrlFailed || captionSuspect ||
            (error.type == ExoPlaybackException.TYPE_UNEXPECTED && error.unexpectedException is NullPointerException && cap != null)) {
            chosenCaption = null
            captionsFailed = true
            selector.captionsEnabled = false
        }
        val msg = (error.cause?.message ?: error.message ?: error.javaClass.simpleName)
        Log.w(TAG, "player error kind=$kind", error)
        onFailure(kind, msg, reload?.reloadPlaybackToken, positionForRecovery())
    }

    // ---------------------------------------------------------------- recovery

    private fun positionForRecovery(): Long =
        if (currentKind == SourceKind.DASH_URL || currentKind == SourceKind.HLS_URL || formatInfo?.isLive == true) 0 else player.currentPosition.coerceAtLeast(0)

    private fun onFailure(kind: FailureKind, message: String?, token: String?, posMs: Long) {
        val id = videoId ?: return
        when (val r = recovery.onFailure(kind, message, token, posMs)) {
            is Recovery.GiveUp -> fail(r.message, retryable = true)
            is Recovery.Retry -> {
                if (r.reloadToken != null) Repo.reloadPlayerResponse(id, r.reloadToken)
                if (r.switchClient) Repo.switchNextClient()
                if (r.lowerQuality) {
                    sessionMaxHeight = 480
                    selector.chosenHeight = null
                }
                cancelRetry()
                val run = Runnable { retryRunnable = null; if (id == videoId) load(id, posMs) }
                retryRunnable = run
                handler.postDelayed(run, RETRY_DELAY_MS)
            }
        }
    }

    private fun fail(message: String, retryable: Boolean) {
        lastError = message
        player.stop()
        listener?.onError(message, retryable)
    }

    // ---------------------------------------------------------------- end / autoplay / position

    private fun onEnded() {
        val id = videoId ?: return
        resume.save(id, 0, 0) // finished: forget the position
        if (!settings.autoplayNext) return
        val next = nextVideoId() ?: return
        open(next)
    }

    /** Videos the user dismissed ("Not interested"): autoplay never picks them this session. */
    private val skipped = HashSet<String>()

    fun skipForAutoplay(videoId: String) { skipped.add(videoId) }

    private fun nextVideoId(): String? {
        val m = metadata ?: return null
        val suggestions = m.suggestions?.asSequence()?.flatMap { it.mediaItems?.asSequence() ?: emptySequence() }
            ?.filter { !it.videoId.isNullOrEmpty() }?.toList() ?: emptyList()
        // Autoplay never lands on a video the content filters hide. m.nextVideo carries no author / channel id, so
        // also test the suggestion entry for the same video, which does.
        val next = m.nextVideo
        val nextId = next?.videoId
        if (next != null && !nextId.isNullOrEmpty() && nextId !in skipped && !Filters.isHidden(next) &&
            suggestions.none { it.videoId == nextId && Filters.isHidden(it) }) return nextId
        return suggestions.firstOrNull { it.videoId != videoId && it.videoId !in skipped && !Filters.isHidden(it) }?.videoId
    }

    fun savePosition() {
        val id = videoId ?: return
        if (formatInfo?.isLive == true) return
        val dur = player.duration
        val pos = player.currentPosition
        if (dur == C.TIME_UNSET || dur <= 0 || player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) return
        resume.save(id, pos, dur)
    }

    fun release() {
        savePosition()
        handler.removeCallbacksAndMessages(null)
        formatAction?.dispose(); metaAction?.dispose()
        player.removeListener(this)
        builder.release()
        listener = null
        metadataObserver = null
    }

    private companion object {
        const val TAG = "PlaybackController"
        const val SAVE_INTERVAL_MS = 5_000L
        const val TICK_MS = 1_000L
        const val RETRY_DELAY_MS = 800L
    }
}
