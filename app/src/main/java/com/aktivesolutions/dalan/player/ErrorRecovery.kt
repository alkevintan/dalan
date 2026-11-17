package com.aktivesolutions.dalan.player

/** What to do about a playback or format-resolution failure. */
sealed class Recovery {
    /** Re-resolve and resume at the same position. */
    data class Retry(
        val switchClient: Boolean,
        val reloadToken: String? = null,
        val lowerQuality: Boolean = false,
        val message: String? = null,
    ) : Recovery()

    data class GiveUp(val message: String) : Recovery()
}

/** The classes of failure we distinguish. Decoupled from ExoPlayer so the policy is testable. */
enum class FailureKind { SOURCE, VIDEO_RENDERER, AUDIO_RENDERER, OTHER_RENDERER, OUT_OF_MEMORY, SABR_RELOAD, SABR_BACKOFF, SABR_POTOKEN, FORMAT_FETCH, UNEXPECTED }

/**
 * Port of the important parts of SmartTube's ErrorFixerController: after a source / 403-type error
 * switch to the next client and re-fetch; SABR reload requests re-fetch with the token. At most
 * [maxAttempts] automatic attempts per video, then the error is shown with a Retry button.
 */
class ErrorRecovery(private val maxAttempts: Int = 3, private val healthyMs: Long = 15_000L) {
    private var videoId: String? = null
    var attempts = 0
        private set
    private var failurePosMs = 0L
    private var streakStartMs = -1L

    fun reset(newVideoId: String?) { videoId = newVideoId; attempts = 0; failurePosMs = 0; streakStartMs = -1 }

    /**
     * Call about once a second. The attempt counter is only cleared after [healthyMs] of continuous
     * playback past the position of the last failure (a READY blip after a retry proves nothing).
     * @param playing READY and playWhenReady
     */
    fun onTick(playing: Boolean, positionMs: Long) {
        if (attempts == 0) return
        if (!playing) { streakStartMs = -1; return }
        if (streakStartMs < 0 || positionMs < streakStartMs) streakStartMs = positionMs
        if (positionMs - maxOf(streakStartMs, failurePosMs) >= healthyMs) {
            attempts = 0
            streakStartMs = -1
        }
    }

    fun onFailure(kind: FailureKind, message: String?, reloadToken: String? = null, positionMs: Long = 0): Recovery {
        failurePosMs = positionMs
        streakStartMs = -1
        if (attempts >= maxAttempts) {
            return Recovery.GiveUp(message ?: "Playback failed")
        }
        attempts++
        return when (kind) {
            FailureKind.SABR_RELOAD -> Recovery.Retry(switchClient = attempts > 1, reloadToken = reloadToken, message = message)
            FailureKind.SABR_BACKOFF, FailureKind.FORMAT_FETCH -> Recovery.Retry(switchClient = attempts > 1, message = message)
            FailureKind.SOURCE, FailureKind.SABR_POTOKEN, FailureKind.UNEXPECTED -> Recovery.Retry(switchClient = true, message = message)
            FailureKind.VIDEO_RENDERER, FailureKind.OUT_OF_MEMORY -> Recovery.Retry(switchClient = false, lowerQuality = true, message = message)
            FailureKind.AUDIO_RENDERER, FailureKind.OTHER_RENDERER -> Recovery.Retry(switchClient = false, message = message)
        }
    }
}
