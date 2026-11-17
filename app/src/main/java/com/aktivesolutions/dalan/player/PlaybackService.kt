package com.aktivesolutions.dalan.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.aktivesolutions.dalan.R
import com.aktivesolutions.dalan.ui.PlayerActivity
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.ExoPlayerFactory
import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.Timeline
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.C

/**
 * Foreground service that owns the [SimpleExoPlayer] (so audio keeps playing in the background),
 * a framework MediaSession and a MediaStyle notification. The PlayerActivity binds to it and
 * attaches its PlayerView to [player].
 */
class PlaybackService : Service() {

    inner class LocalBinder : Binder() {
        val service: PlaybackService get() = this@PlaybackService
    }

    private val binder = LocalBinder()
    lateinit var player: SimpleExoPlayer
        private set
    lateinit var controller: PlaybackController
        private set
    private lateinit var session: MediaSession
    private var inForeground = false

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) player.playWhenReady = false
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var sessionTick = false
    private var lastMetaKey: String? = null

    private val playerListener = object : Player.EventListener {
        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) = refresh()
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = refresh()
        override fun onPositionDiscontinuity(reason: Int) = updateSession()
        override fun onSeekProcessed() = updateSession()
        override fun onTimelineChanged(timeline: Timeline, manifest: Any?, reason: Int) {
            updateMetadata(); updateSession()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!isActivelyPlaying()) { sessionTick = false; return }
            updateSession()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val selector = DalanTrackSelector()
        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        // Modest buffers keep memory low on a 1.4 GB device.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 30_000, 2_500, 5_000)
            .createDefaultLoadControl()
        player = ExoPlayerFactory.newSimpleInstance(this, renderers, selector, loadControl)
        player.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.CONTENT_TYPE_MOVIE).build(),
            /* handleAudioFocus = */ true
        )
        controller = PlaybackController(this, player, selector)
        player.addListener(playerListener)
        controller.metadataObserver = { updateMetadata(); refresh() }

        session = MediaSession(this, "Dalan")
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { player.playWhenReady = true }
            override fun onPause() { player.playWhenReady = false }
            override fun onSeekTo(pos: Long) { player.seekTo(pos) }
            override fun onStop() { closeService() }
        })
        // The session becomes active when playback starts (see refresh()).
        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> player.playWhenReady = !player.playWhenReady
            ACTION_CLOSE -> closeService()
        }
        return START_NOT_STICKY
    }

    private fun closeService() {
        handler.removeCallbacks(tick); sessionTick = false
        player.stop(true)
        player.playWhenReady = false
        releaseLocks()
        session.isActive = false
        stopForeground(true)
        inForeground = false
        stopSelf()
    }

    private fun isActivelyPlaying(): Boolean =
        player.playWhenReady && (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)

    /**
     * Stay a foreground service while the user wants playback (playWhenReady) even through IDLE
     * (stop / re-prepare during error recovery), otherwise the notification flickers and the
     * process loses its priority for a moment. Errors and the end of the video drop it.
     */
    private fun wantForeground(): Boolean =
        player.playWhenReady && controller.lastError == null && player.playbackState != Player.STATE_ENDED &&
            controller.videoId != null

    private fun updateSession() {
        val ready = player.playbackState == Player.STATE_READY
        val state = when {
            player.playbackState == Player.STATE_BUFFERING && player.playWhenReady -> PlaybackState.STATE_BUFFERING
            isActivelyPlaying() -> PlaybackState.STATE_PLAYING
            player.playbackState == Player.STATE_IDLE -> if (wantForeground()) PlaybackState.STATE_BUFFERING else PlaybackState.STATE_STOPPED
            else -> PlaybackState.STATE_PAUSED
        }
        val speed = if (ready && player.playWhenReady) player.playbackParameters.speed else 0f
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP)
                .setState(state, player.currentPosition.coerceAtLeast(0), speed)
                .setBufferedPosition(player.bufferedPosition.coerceAtLeast(0))
                .build()
        )
    }

    private fun updateMetadata() {
        val id = controller.videoId ?: return
        val title = controller.metadata?.title ?: controller.formatInfo?.title ?: getString(R.string.app_name)
        val artist = controller.metadata?.author ?: controller.formatInfo?.author
        val dur = player.duration
        val key = "$id|$title|$artist|$dur"
        if (key == lastMetaKey) return
        lastMetaKey = key
        val mb = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist ?: "")
            .putString(MediaMetadata.METADATA_KEY_ART_URI, "https://i.ytimg.com/vi/$id/hqdefault.jpg")
        if (dur != C.TIME_UNSET && dur > 0) mb.putLong(MediaMetadata.METADATA_KEY_DURATION, dur)
        session.setMetadata(mb.build())
    }

    @Suppress("DEPRECATION")
    private fun updateLocks() {
        if (isActivelyPlaying()) {
            if (wakeLock == null) {
                val pm = applicationContext.getSystemService(POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Dalan:playback").apply { setReferenceCounted(false) }
            }
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager
                wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Dalan:playback")?.apply { setReferenceCounted(false) }
            }
            try { if (wakeLock?.isHeld == false) wakeLock?.acquire() } catch (_: Exception) {}
            try { if (wifiLock?.isHeld == false) wifiLock?.acquire() } catch (_: Exception) {}
        } else releaseLocks()
    }

    private fun releaseLocks() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
    }

    private fun refresh() {
        val playing = isActivelyPlaying()
        if (playing && !session.isActive) session.isActive = true
        updateLocks()
        updateMetadata()
        updateSession()
        if (playing && !sessionTick) { sessionTick = true; handler.postDelayed(tick, 1000) }
        if (!session.isActive && !inForeground) return
        val n = buildNotification()
        if (wantForeground() || playing) {
            startForeground(NOTIF_ID, n) // also re-posts the notification when already in the foreground
            inForeground = true
        } else {
            if (inForeground) { stopForeground(false); inForeground = false }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, n)
        }
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        val playing = isActivelyPlaying()
        val title = controller.metadata?.title ?: controller.formatInfo?.title ?: getString(R.string.app_name)
        val artist = controller.metadata?.author ?: controller.formatInfo?.author
        val open = PendingIntent.getActivity(this, 0, Intent(this, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT)
        b.setSmallIcon(R.drawable.ic_stat_play)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(open)
            .setDeleteIntent(servicePending(ACTION_CLOSE, 3))
            .setOngoing(playing)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                getString(R.string.play_pause), servicePending(ACTION_TOGGLE, 1)
            )
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.close), servicePending(ACTION_CLOSE, 2))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1))
        return b.build()
    }

    private fun servicePending(action: String, code: Int): PendingIntent =
        PendingIntent.getService(this, code, Intent(this, PlaybackService::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT)

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        releaseLocks()
        try { unregisterReceiver(noisyReceiver) } catch (_: Exception) {}
        controller.release()
        player.removeListener(playerListener)
        session.isActive = false
        session.release()
        player.release()
        super.onDestroy()
    }

    companion object {
        const val ACTION_TOGGLE = "com.aktivesolutions.dalan.TOGGLE"
        const val ACTION_CLOSE = "com.aktivesolutions.dalan.CLOSE"
        private const val CHANNEL_ID = "playback"
        private const val NOTIF_ID = 1
    }
}
