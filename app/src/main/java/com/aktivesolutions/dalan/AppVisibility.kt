package com.aktivesolutions.dalan

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.aktivesolutions.dalan.data.Settings
import com.aktivesolutions.dalan.player.PlaybackService
import com.google.android.exoplayer2.Player

/**
 * App-wide activity counting (main thread). Pauses when the whole app leaves the foreground with background
 * play off, and closes the playback session when the last activity is finished unless audio should go on.
 * Moving between two Dalan activities starts the next before stopping the previous, so the count never
 * touches 0 in between; configuration changes are ignored.
 */
object AppVisibility : Application.ActivityLifecycleCallbacks {
    private var started = 0
    private var created = 0

    fun register(app: Application) = app.registerActivityLifecycleCallbacks(this)

    override fun onActivityCreated(a: Activity, b: Bundle?) { created++ }
    override fun onActivityStarted(a: Activity) { started++ }

    override fun onActivityStopped(a: Activity) {
        started--
        if (started > 0 || a.isChangingConfigurations) return
        val s = PlaybackService.instance ?: return
        if (!Settings.get(a).backgroundPlay) s.player.playWhenReady = false
    }

    override fun onActivityDestroyed(a: Activity) {
        created--
        if (created > 0 || a.isChangingConfigurations || !a.isFinishing) return
        val s = PlaybackService.instance ?: return
        val p = s.player
        val playing = p.playWhenReady && (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING)
        if (!playing || !Settings.get(a).backgroundPlay) s.close()
    }

    override fun onActivityResumed(a: Activity) {}
    override fun onActivityPaused(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
}
