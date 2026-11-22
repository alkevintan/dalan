package com.aktivesolutions.dalan.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.OrientationEventListener
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aktivesolutions.dalan.R
import com.aktivesolutions.dalan.data.Filters
import com.aktivesolutions.dalan.player.PlaybackController
import com.aktivesolutions.dalan.player.PlaybackService
import com.aktivesolutions.dalan.util.VideoUrlParser
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.ui.PlayerView
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata
import java.util.Locale

/**
 * Full player screen. The SimpleExoPlayer lives in [PlaybackService]; this Activity binds to it,
 * attaches its PlayerView and draws the controls plus the info / related list.
 * Handles rotation itself (configChanges) so the player is never recreated.
 */
class PlayerActivity : ThemedActivity(), PlaybackController.Listener {

    override val playerTheme: Boolean get() = true

    private var service: PlaybackService? = null
    private var controller: PlaybackController? = null
    private var bound = false
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var root: LinearLayout
    private lateinit var playerFrame: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var spinner: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var retryButton: Button
    private lateinit var controls: LinearLayout
    private lateinit var seek: SeekBar
    private lateinit var timeText: TextView
    private lateinit var playPause: ImageButton
    private lateinit var qualityBtn: Button
    private lateinit var ccBtn: ImageButton
    private lateinit var speedBtn: Button
    private lateinit var fullscreenBtn: ImageButton
    private lateinit var infoList: RecyclerView
    private val infoAdapter = InfoAdapter()

    private var fullscreen = true
    /** Landscape was forced by the fullscreen button from portrait (lock kept until the device is physically landscape). */
    private var forcedLandscape = false
    private var resumed = false
    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (!forcedLandscape || orientation == ORIENTATION_UNKNOWN) return
                // Degrees from the natural orientation: landscape is 90/270 on portrait-natural devices (this tablet), 0/180 otherwise.
                val d = (orientation + (if (naturalLandscape()) 90 else 0)) % 180
                val nearLandscape = Math.abs(d - 90) <= 20
                if (nearLandscape && autoRotateOn()) releaseForcedLandscape()
            }
        }
    }
    private var controlsVisible = true
    private var dragging = false
    private var pendingIntent: Intent? = null
    private var started = false
    private var restored = false

    private val hideControls = Runnable { setControlsVisible(false) }
    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            updateKeepScreenOn()
            handler.postDelayed(this, 500)
        }
    }

    private val playerListener = object : Player.EventListener {
        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) = updatePlayState()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val s = (binder as PlaybackService.LocalBinder).service
            service = s
            val c = s.controller
            controller = c
            attach(s)
            // Only show video while visible: a connect that completes after onStop must not re-enable it.
            c.setVideoEnabled(started)
            updatePlayState()
            // After a recreation (process restore) the service already plays something: don't replay the launch intent.
            pendingIntent?.let { if (!(restored && c.videoId != null)) handleIntent(it) }
            pendingIntent = null
            c.metadata?.let { showMetadata(it) }
            if (c.metadata == null && (c.metadataFailed || c.formatInfo != null)) infoAdapter.failed()
            c.lastError?.let { onError(it, true) }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null; controller = null
        }
    }

    /** (Re-)attaches this activity to the service's player. Another PlayerActivity may have taken it over. */
    private fun attach(s: PlaybackService) {
        val c = s.controller
        c.listener = this
        if (playerView.player !== s.player) playerView.player = null
        playerView.player = s.player
        s.player.removeListener(playerListener)
        s.player.addListener(playerListener)
    }

    // ------------------------------------------------------------------ lifecycle

    private var filtersSeen = Filters.version

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restored = savedInstanceState != null
        fullscreen = savedInstanceState?.getBoolean("fullscreen", true) ?: true
        forcedLandscape = savedInstanceState?.getBoolean("forcedLandscape", false) ?: false
        if (forcedLandscape) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        buildUi()
        applyLayout()
        pendingIntent = intent
        val i = Intent(this, PlaybackService::class.java)
        startService(i)
        bound = bindService(i, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("fullscreen", fullscreen)
        outState.putBoolean("forcedLandscape", forcedLandscape)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (controller != null) handleIntent(intent) else pendingIntent = intent
    }

    private fun handleIntent(intent: Intent) {
        val c = controller ?: return
        val extraId = intent.getStringExtra(EXTRA_VIDEO_ID)
        val parsed = when {
            extraId != null -> com.aktivesolutions.dalan.util.ParsedVideo(extraId, intent.getIntExtra(EXTRA_START_SECONDS, 0))
            intent.action == Intent.ACTION_VIEW -> VideoUrlParser.parse(intent.dataString)
            intent.action == Intent.ACTION_SEND -> VideoUrlParser.parseFromText(intent.getStringExtra(Intent.EXTRA_TEXT))
            else -> null
        }
        if (parsed != null) {
            // Re-start the service: onNewIntent reuses this activity without onCreate, and the session may have
            // been closed meanwhile (mini player / notification), which leaves it only bound (and marked closed).
            startService(Intent(this, PlaybackService::class.java))
            if (parsed.videoId != c.videoId) infoAdapter.reset()
            c.open(parsed.videoId, parsed.startSeconds)
        } else if (c.videoId == null) {
            // Nothing to show (e.g. notification tapped after the service died, or a bad link).
            Toast.makeText(this, R.string.empty, Toast.LENGTH_SHORT).show()
            service?.close() // don't leave an empty started service behind
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        updateOrientationListener()
        if (filtersSeen != Filters.version) { filtersSeen = Filters.version; infoAdapter.refilter() }
    }

    override fun onPause() {
        resumed = false
        updateOrientationListener()
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        started = true
        visibleCount++
        service?.let { attach(it) }
        controller?.setVideoEnabled(true)
        handler.post(ticker)
    }

    override fun onStop() {
        started = false
        visibleCount--
        handler.removeCallbacks(ticker)
        setKeepScreenOn(false)
        val c = controller
        // Audio only while no PlayerActivity is visible: saves CPU on weak devices. Pausing when the whole
        // app leaves the foreground (background play off) is handled app-wide by AppVisibility.
        if (c != null && visibleCount <= 0 && !isChangingConfigurations) c.setVideoEnabled(false)
        c?.savePosition()
        super.onStop()
    }

    override fun onDestroy() {
        orientationListener.disable()
        handler.removeCallbacksAndMessages(null)
        controller?.let { if (it.listener === this) it.listener = null }
        service?.player?.removeListener(playerListener)
        playerView.player = null
        if (bound) { unbindService(connection); bound = false }
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLayout()
        checkTheme() // uiMode is handled here (no recreation): only the info panel is re-themed
    }

    /** Theme setting / night mode changed: the video area stays black, so only the info panel and dialogs restyle. */
    override fun restyle(): Boolean {
        setTheme(Themes.themeRes(Themes.isDark(this), true))
        infoList.setBackgroundColor(themeColor(R.attr.dalanBg))
        infoList.adapter = infoAdapter // new adapter attach recycles the holders, so they are rebuilt with the new colours
        return true
    }

    // ------------------------------------------------------------------ UI construction

    private fun buildUi() {
        root = LinearLayout(this)
        root.setBackgroundColor(Color.BLACK)

        playerFrame = FrameLayout(this)
        playerFrame.setBackgroundColor(Color.BLACK)
        playerView = PlayerView(this)
        playerView.useController = false
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        playerFrame.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        // PlayerView ignores touches when useController=false, so the frame (its parent) handles taps.
        playerFrame.setOnClickListener { toggleControls() }

        spinner = ProgressBar(this)
        spinner.indeterminateTintList = ColorStateList.valueOf(resources.getColor(R.color.launcher_tan))
        playerFrame.addView(spinner, FrameLayout.LayoutParams(dp(this, 48), dp(this, 48), Gravity.CENTER))
        statusText = TextView(this).apply {
            setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(dp(context, 24), 0, dp(context, 24), 0); visibility = View.GONE
        }
        retryButton = Button(this).apply { setText(R.string.retry); setStartGlyph(R.drawable.ic_refresh); visibility = View.GONE; setOnClickListener { controller?.retry() } }
        val statusBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        statusBox.addView(statusText); statusBox.addView(retryButton)
        playerFrame.addView(statusBox, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        controls = LinearLayout(this)
        controls.isClickable = true // swallow taps on the bar so they don't toggle (hide) it
        controls.orientation = LinearLayout.VERTICAL
        controls.setBackgroundColor(resources.getColor(R.color.controls_scrim))
        controls.setPadding(dp(this, 8), dp(this, 4), dp(this, 8), dp(this, 4))
        val seekRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        seek = SeekBar(this)
        // Controls always sit on a dark scrim over the video, so they use fixed brand / white colours in both themes.
        seek.progressTintList = ColorStateList.valueOf(resources.getColor(R.color.launcher_tan))
        seek.thumbTintList = ColorStateList.valueOf(resources.getColor(R.color.launcher_tan))
        seek.secondaryProgressTintList = ColorStateList.valueOf(0x99FFFFFF.toInt())
        seek.progressBackgroundTintList = ColorStateList.valueOf(0x55FFFFFF)
        timeText = TextView(this).apply { setTextColor(Color.WHITE); textSize = 12f; setPadding(dp(context, 8), 0, 0, 0) }
        seekRow.addView(seek, LinearLayout.LayoutParams(0, -2, 1f))
        seekRow.addView(timeText)
        val buttons = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val back10 = iconButton(R.drawable.ic_replay_10, R.string.cd_replay_10) { seekBy(-10_000) }
        playPause = iconButton(R.drawable.ic_pause, R.string.play_pause) { togglePlay() }
        val fwd10 = iconButton(R.drawable.ic_forward_10, R.string.cd_forward_10) { seekBy(10_000) }
        qualityBtn = textButton("") { showQualityDialog() }
        qualityBtn.setStartGlyph(R.drawable.ic_hd, Color.WHITE)
        qualityBtn.contentDescription = getString(R.string.cd_quality)
        ccBtn = iconButton(R.drawable.ic_cc_off, R.string.cd_captions) { showCaptionDialog() }
        speedBtn = textButton("1×") { showSpeedDialog() }
        speedBtn.contentDescription = getString(R.string.cd_speed)
        fullscreenBtn = iconButton(R.drawable.ic_fullscreen, R.string.cd_fullscreen) { toggleFullscreen() }
        val box = LinearLayout.LayoutParams(dp(this, 48), dp(this, 48))
        buttons.addView(back10, box)
        buttons.addView(playPause, LinearLayout.LayoutParams(dp(this, 56), dp(this, 48)))
        buttons.addView(fwd10, LinearLayout.LayoutParams(box))
        buttons.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        buttons.addView(qualityBtn, LinearLayout.LayoutParams(-2, dp(this, 48))); buttons.addView(ccBtn, LinearLayout.LayoutParams(box))
        buttons.addView(speedBtn, LinearLayout.LayoutParams(-2, dp(this, 48)))
        buttons.addView(fullscreenBtn, LinearLayout.LayoutParams(box))
        controls.addView(seekRow); controls.addView(buttons)
        playerFrame.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) timeText.text = timeLabel(progress.toLong(), seek.max.toLong())
            }
            override fun onStartTrackingTouch(s: SeekBar) { dragging = true; handler.removeCallbacks(hideControls) }
            override fun onStopTrackingTouch(s: SeekBar) {
                dragging = false
                service?.player?.seekTo(s.progress.toLong())
                scheduleHide()
            }
        })

        infoList = RecyclerView(this)
        infoList.setBackgroundColor(themeColor(R.attr.dalanBg))
        infoList.layoutManager = LinearLayoutManager(this)
        infoList.adapter = infoAdapter
        infoList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                ImageLoader.setPaused(newState == RecyclerView.SCROLL_STATE_SETTLING)
            }
        })

        root.addView(playerFrame)
        root.addView(infoList)
        setContentView(root)
    }

    private fun textButton(label: String, onClick: () -> Unit) = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
        text = label; setTextColor(Color.WHITE); textSize = 14f; minWidth = 0; minimumWidth = 0
        setBackgroundResource(R.drawable.ripple_white)
        setPadding(dp(context, 10), 0, dp(context, 10), 0)
        setOnClickListener { onClick(); scheduleHide() }
    }

    /** 48dp borderless glyph button for the player controls (glyphs are white vectors). */
    private fun iconButton(icon: Int, desc: Int, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        setBackgroundResource(R.drawable.ripple_white)
        contentDescription = getString(desc)
        setOnClickListener { onClick(); scheduleHide() }
    }

    /** Arranges the player and the info pane for the current orientation / fullscreen state. */
    private fun applyLayout() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val dm = resources.displayMetrics
        if (landscape && fullscreen) {
            root.orientation = LinearLayout.HORIZONTAL
            playerFrame.layoutParams = LinearLayout.LayoutParams(-1, -1)
            infoList.visibility = View.GONE
            applyImmersive(true)
        } else if (landscape) {
            root.orientation = LinearLayout.HORIZONTAL
            playerFrame.layoutParams = LinearLayout.LayoutParams(0, -1, 65f)
            infoList.layoutParams = LinearLayout.LayoutParams(0, -1, 35f)
            infoList.visibility = View.VISIBLE
            applyImmersive(false)
        } else {
            root.orientation = LinearLayout.VERTICAL
            playerFrame.layoutParams = LinearLayout.LayoutParams(-1, dm.widthPixels * 9 / 16)
            infoList.layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            infoList.visibility = View.VISIBLE
            applyImmersive(false)
        }
        val exit = landscape && fullscreen
        fullscreenBtn.setImageResource(if (exit) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
        fullscreenBtn.contentDescription = getString(if (exit) R.string.cd_exit_fullscreen else R.string.cd_fullscreen)
        root.requestLayout()
    }

    @Suppress("DEPRECATION")
    private fun applyImmersive(on: Boolean) {
        window.decorView.systemUiVisibility = if (on) {
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        } else View.SYSTEM_UI_FLAG_VISIBLE
    }

    private fun autoRotateOn(): Boolean = try {
        Settings.System.getInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
    } catch (e: Exception) { false }

    @Suppress("DEPRECATION")
    private fun naturalLandscape(): Boolean {
        val rot = windowManager.defaultDisplay.rotation
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return landscape == (rot == android.view.Surface.ROTATION_0 || rot == android.view.Surface.ROTATION_180)
    }

    private fun releaseForcedLandscape() {
        forcedLandscape = false
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        updateOrientationListener()
    }

    private fun updateOrientationListener() {
        if (forcedLandscape && resumed && orientationListener.canDetectOrientation()) orientationListener.enable()
        else orientationListener.disable()
    }

    private fun toggleFullscreen() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (!landscape) {
            fullscreen = true
            forcedLandscape = true
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            updateOrientationListener()
        } else if (forcedLandscape) {
            // Exit: hand orientation back to the system (portrait under rotation lock); keep fullscreen as the landscape default.
            forcedLandscape = false
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            updateOrientationListener()
        } else {
            fullscreen = !fullscreen
            applyLayout()
        }
    }

    // ------------------------------------------------------------------ controls

    private fun toggleControls() = setControlsVisible(!controlsVisible)

    private fun setControlsVisible(v: Boolean) {
        controlsVisible = v
        controls.visibility = if (v) View.VISIBLE else View.GONE
        handler.removeCallbacks(hideControls)
        if (v) scheduleHide()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideControls)
        if (service?.player?.playWhenReady == true) handler.postDelayed(hideControls, 3500)
    }

    private fun togglePlay() {
        val p = service?.player ?: return
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
        p.playWhenReady = !p.playWhenReady
    }

    private fun seekBy(deltaMs: Long) {
        val p = service?.player ?: return
        val dur = p.duration
        var t = p.currentPosition + deltaMs
        if (dur != C.TIME_UNSET) t = t.coerceAtMost(dur)
        p.seekTo(t.coerceAtLeast(0))
    }

    private var keepOnApplied = false

    private fun setKeepScreenOn(on: Boolean) {
        if (on == keepOnApplied) return
        keepOnApplied = on
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Window-level keep-awake: stays on through IDLE/loading gaps while playWhenReady; off when paused, ended or errored. */
    private fun updateKeepScreenOn() {
        val p = service?.player
        setKeepScreenOn(p != null && p.playWhenReady && controller?.lastError == null &&
            p.playbackState != Player.STATE_ENDED)
    }

    private fun updatePlayState() {
        updateKeepScreenOn()
        val p = service?.player ?: return
        val playing = p.playWhenReady && p.playbackState != Player.STATE_ENDED && p.playbackState != Player.STATE_IDLE
        playPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        spinner.visibility = if (p.playbackState == Player.STATE_BUFFERING ||
            (p.playbackState == Player.STATE_IDLE && controller?.lastError == null && controller?.videoId != null)) View.VISIBLE else View.GONE
        if (playing) scheduleHide() else { handler.removeCallbacks(hideControls); if (!controlsVisible) setControlsVisible(true) }
        speedBtn.text = String.format(Locale.US, "%s×", trimSpeed(controller?.speed ?: 1f))
        updateQualityLabel()
    }

    private fun updateQualityLabel() {
        val c = controller ?: return
        val h = c.chosenHeight()
        val label = if (h != null) "${h}p" else if (c.currentHeight() > 0) "${getString(R.string.auto)}·${c.currentHeight()}p" else null
        qualityBtn.contentDescription = if (label != null) "${getString(R.string.cd_quality)}, $label" else getString(R.string.cd_quality)
        if (label != null) {
            qualityBtn.text = label
            qualityBtn.setCompoundDrawablesRelative(null, null, null, null)
        } else {
            qualityBtn.text = ""
            qualityBtn.setStartGlyph(R.drawable.ic_hd, Color.WHITE)
        }
        ccBtn.setImageResource(if (c.chosenCaption != null) R.drawable.ic_cc_on else R.drawable.ic_cc_off)
    }

    private fun updateProgress() {
        val p = service?.player ?: return
        if (dragging) return
        val dur = p.duration
        if (dur == C.TIME_UNSET || dur <= 0) {
            seek.max = 0; seek.progress = 0; seek.secondaryProgress = 0
            timeText.text = timeLabel(p.currentPosition, 0)
            return
        }
        seek.max = dur.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        seek.progress = p.currentPosition.coerceIn(0, dur).toInt()
        seek.secondaryProgress = p.bufferedPosition.coerceIn(0, dur).toInt() // buffered position
        timeText.text = timeLabel(p.currentPosition, dur)
    }

    private fun timeLabel(pos: Long, dur: Long) =
        if (dur > 0) "${formatDuration(pos).ifEmpty { "0:00" }} / ${formatDuration(dur)}" else formatDuration(pos).ifEmpty { "0:00" }

    private fun trimSpeed(s: Float) = if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString()

    // ------------------------------------------------------------------ dialogs

    private fun showQualityDialog() {
        val c = controller ?: return
        val heights = c.availableHeights()
        val labels = ArrayList<String>().apply { add(if (c.currentHeight() > 0) "${getString(R.string.auto)} (${c.currentHeight()}p)" else getString(R.string.auto)); heights.forEach { add("${it}p") } }
        val chosen = c.chosenHeight()
        val checked = if (chosen == null) 0 else (heights.indexOf(chosen) + 1).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle(R.string.quality)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { d, which ->
                c.setQuality(if (which == 0) null else heights[which - 1]); d.dismiss(); updateQualityLabel()
            }.show()
    }

    private fun showSpeedDialog() {
        val c = controller ?: return
        val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
        val labels = speeds.map { "${trimSpeed(it)}×" }.toTypedArray()
        AlertDialog.Builder(this).setTitle(R.string.speed)
            .setSingleChoiceItems(labels, speeds.toList().indexOf(c.speed).coerceAtLeast(0)) { d, which ->
                c.setSpeed(speeds[which]); d.dismiss(); updatePlayState()
            }.show()
    }

    private fun showCaptionDialog() {
        val c = controller ?: return
        val tracks = c.captionTracks()
        if (tracks.isEmpty()) { Toast.makeText(this, R.string.no_captions, Toast.LENGTH_SHORT).show(); return }
        val labels = ArrayList<String>().apply {
            add(getString(R.string.captions_off))
            tracks.forEach { add((it.name ?: it.languageCode ?: "?") + if (it.type == "asr") " (auto)" else "") }
        }
        val checked = c.chosenCaption?.let { cc -> tracks.indexOfFirst { it.vssId == cc.vssId } + 1 } ?: 0
        AlertDialog.Builder(this).setTitle(R.string.captions)
            .setSingleChoiceItems(labels.toTypedArray(), checked.coerceAtLeast(0)) { d, which ->
                c.setCaption(if (which == 0) null else tracks[which - 1]); d.dismiss(); updateQualityLabel()
            }.show()
    }

    // ------------------------------------------------------------------ PlaybackController.Listener

    override fun onVideoOpened(videoId: String) {
        // Keep the activity's intent in step with what is playing (autoplay-next, related taps), so re-handling it is harmless.
        setIntent(Intent(this, PlayerActivity::class.java).putExtra(EXTRA_VIDEO_ID, videoId))
        infoAdapter.reset()
        statusText.visibility = View.GONE; retryButton.visibility = View.GONE
        updatePlayState()
    }

    override fun onMetadata(metadata: MediaItemMetadata) = showMetadata(metadata)

    private fun showMetadata(m: MediaItemMetadata) {
        infoAdapter.setMetadata(m)
    }

    override fun onError(message: String, retryable: Boolean) {
        statusText.text = message
        statusText.visibility = View.VISIBLE
        retryButton.visibility = if (retryable) View.VISIBLE else View.GONE
        spinner.visibility = View.GONE
        infoAdapter.failed()
        setControlsVisible(true)
        updateKeepScreenOn()
    }

    override fun onErrorCleared() {
        statusText.visibility = View.GONE; retryButton.visibility = View.GONE
        updateKeepScreenOn()
    }

    override fun onTracksChanged() = updateQualityLabel()

    override fun onMetadataFailed() = infoAdapter.failed()

    // ------------------------------------------------------------------ info + related list

    private inner class InfoAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var meta: MediaItemMetadata? = null
        private var rawRelated: List<MediaItem> = emptyList()
        private var related: List<MediaItem> = emptyList()
        private val dismissed = ArrayList<MediaItem>()
        private var descExpanded = false

        /** True from opening a video until its metadata (or an error) arrives: the panel shows a skeleton. */
        private var loading = true

        fun reset() { meta = null; rawRelated = emptyList(); related = emptyList(); dismissed.clear(); descExpanded = false; loading = true; notifyDataSetChanged() }

        /** The video failed to load: stop pretending metadata is coming and show what is known. */
        fun failed() { if (loading) { loading = false; notifyDataSetChanged() } }

        fun setMetadata(m: MediaItemMetadata) {
            meta = m
            loading = false
            rawRelated = m.suggestions.orEmpty().flatMap { it.mediaItems.orEmpty() }.filter { !it.videoId.isNullOrEmpty() }
            refilter()
        }

        /** Hides related videos the content filters (or "Not interested") remove. */
        fun refilter() {
            related = Filters.filter(rawRelated).let { l -> if (dismissed.isEmpty()) l else l.filter { x -> dismissed.none { it === x } } }
            notifyDataSetChanged()
        }

        override fun getItemCount() = if (loading) 1 else 1 + related.size
        override fun getItemViewType(position: Int) = if (loading) 2 else if (position == 0) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == 2) SkeletonHolder(Skeleton.pulse(parent.context, Skeleton.playerInfo(parent.context)).also {
                it.layoutParams = RecyclerView.LayoutParams(-1, -2)
            })
            else if (viewType == 0) HeaderHolder(buildHeader(parent.context))
            else RelatedHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_related, parent, false).also {
                it.layoutParams = RecyclerView.LayoutParams(-1, -2)
            })

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            if (h is HeaderHolder) bindHeader(h) else if (h is RelatedHolder) {
                val item = related[position - 1]
                h.title.text = item.title
                h.sub.text = item.secondTitle?.toString().takeUnless { it.isNullOrBlank() } ?: item.author ?: ""
                val badge = if (item.isLive) "LIVE" else item.badgeText?.takeIf { it.isNotBlank() } ?: formatDuration(item.durationMs)
                h.badge.text = badge
                h.badge.visibility = if (badge.isEmpty()) View.GONE else View.VISIBLE
                ImageLoader.load(h.thumb, item.cardImageUrl, 160, 90)
                h.itemView.setOnClickListener { item.videoId?.let { id -> Nav.openVideo(this@PlayerActivity, id) } }
                h.itemView.setOnLongClickListener {
                    FilterUi.showItemMenu(this@PlayerActivity, item, { refilter() }, { dismissed.add(it); it.videoId?.let { v -> controller?.skipForAutoplay(v) }; refilter() })
                    true
                }
            }
        }

        override fun onViewRecycled(h: RecyclerView.ViewHolder) { if (h is RelatedHolder) ImageLoader.cancel(h.thumb) }

        private fun bindHeader(h: HeaderHolder) {
            val m = meta
            val c = controller
            h.title.text = m?.title ?: c?.formatInfo?.title ?: ""
            val author = m?.author ?: c?.formatInfo?.author ?: ""
            h.channel.text = author
            h.channel.isEnabled = !(m?.channelId ?: c?.formatInfo?.channelId).isNullOrEmpty()
            h.channel.setOnClickListener {
                val id = m?.channelId ?: c?.formatInfo?.channelId ?: return@setOnClickListener
                startActivity(
                    Intent(this@PlayerActivity, ChannelActivity::class.java)
                        .putExtra(ChannelActivity.EXTRA_CHANNEL_ID, id)
                        .putExtra(ChannelActivity.EXTRA_NAME, author)
                        .putExtra(ChannelActivity.EXTRA_AVATAR, m?.authorImageUrl)
                )
            }
            h.stats.text = listOfNotNull(m?.viewCount?.takeIf { it.isNotBlank() }, m?.publishedDate?.takeIf { it.isNotBlank() }).joinToString(" · ")
            val desc = m?.description ?: c?.formatInfo?.description ?: ""
            h.desc.text = desc
            h.desc.maxLines = if (descExpanded) Int.MAX_VALUE else 3
            h.more.text = getString(if (descExpanded) R.string.show_less else R.string.show_more)
            h.more.visibility = if (desc.length > 120) View.VISIBLE else View.GONE
            h.more.setOnClickListener { descExpanded = !descExpanded; notifyItemChanged(0) }
            h.relatedLabel.visibility = if (related.isEmpty()) View.GONE else View.VISIBLE
        }

        private fun buildHeader(ctx: Context): HeaderViews {
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 4))
                layoutParams = RecyclerView.LayoutParams(-1, -2)
            }
            val textPrimary = ctx.themeColor(R.attr.dalanTextPrimary)
            val title = TextView(ctx).apply { setTextColor(textPrimary); textSize = 18f }
            val channel = Button(ctx, null, android.R.attr.borderlessButtonStyle).apply {
                setTextColor(ctx.themeColor(R.attr.dalanLink)); gravity = Gravity.START or Gravity.CENTER_VERTICAL; isAllCaps = false; setPadding(0, 0, 0, 0)
            }
            val stats = TextView(ctx).apply { setTextColor(ctx.themeColor(R.attr.dalanTextSecondary)); textSize = 12f }
            val desc = TextView(ctx).apply { setTextColor((textPrimary and 0x00FFFFFF) or 0xDD000000.toInt()); textSize = 13f; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(ctx, 8), 0, 0) }
            val more = Button(ctx, null, android.R.attr.borderlessButtonStyle).apply { setTextColor(ctx.themeColor(R.attr.dalanAccent)); isAllCaps = false; gravity = Gravity.START or Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 0) }
            val label = TextView(ctx).apply { setText(R.string.related); setTextColor(textPrimary); textSize = 15f; setPadding(0, dp(ctx, 8), 0, dp(ctx, 4)) }
            box.addView(title); box.addView(channel, LinearLayout.LayoutParams(-2, dp(ctx, 36))); box.addView(stats); box.addView(desc); box.addView(more, LinearLayout.LayoutParams(-2, dp(ctx, 36))); box.addView(label)
            return HeaderViews(box, title, channel, stats, desc, more, label)
        }
    }

    private class SkeletonHolder(v: View) : RecyclerView.ViewHolder(v)

    private class HeaderViews(val root: LinearLayout, val title: TextView, val channel: Button, val stats: TextView, val desc: TextView, val more: Button, val relatedLabel: TextView)

    private class HeaderHolder(v: HeaderViews) : RecyclerView.ViewHolder(v.root) {
        val title = v.title; val channel = v.channel; val stats = v.stats; val desc = v.desc; val more = v.more; val relatedLabel = v.relatedLabel
    }

    private class RelatedHolder(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val badge: TextView = v.findViewById(R.id.badge)
        val title: TextView = v.findViewById(R.id.title)
        val sub: TextView = v.findViewById(R.id.subtitle)
    }

    companion object {
        const val EXTRA_VIDEO_ID = "video_id"
        const val EXTRA_START_SECONDS = "start_seconds"
        /** Number of PlayerActivity instances currently started (visible). */
        private var visibleCount = 0
    }
}
