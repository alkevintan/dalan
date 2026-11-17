package com.aktivesolutions.dalan.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
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
class PlayerActivity : Activity(), PlaybackController.Listener {

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
    private lateinit var ccBtn: Button
    private lateinit var speedBtn: Button
    private lateinit var fullscreenBtn: Button
    private lateinit var infoList: RecyclerView
    private val infoAdapter = InfoAdapter()

    private var fullscreen = true
    private var controlsVisible = true
    private var dragging = false
    private var pendingIntent: Intent? = null
    private var started = false

    private val hideControls = Runnable { setControlsVisible(false) }
    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
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
            if (started) c.setVideoEnabled(true)
            else if (!c.settings.backgroundPlay) s.player.playWhenReady = false
            else c.setVideoEnabled(false)
            updatePlayState()
            pendingIntent?.let { handleIntent(it) }
            pendingIntent = null
            c.metadata?.let { showMetadata(it) }
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_Dalan_Player)
        buildUi()
        applyLayout()
        pendingIntent = intent
        val i = Intent(this, PlaybackService::class.java)
        startService(i)
        bound = bindService(i, connection, Context.BIND_AUTO_CREATE)
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
            infoAdapter.reset()
            c.open(parsed.videoId, parsed.startSeconds)
        } else if (c.videoId == null) {
            // Nothing to show (e.g. notification tapped after the service died, or a bad link).
            Toast.makeText(this, R.string.empty, Toast.LENGTH_SHORT).show()
            finish()
        }
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
        val c = controller
        // With a second PlayerActivity already visible (Player -> Channel -> video) leave the player alone.
        if (c != null && visibleCount <= 0) {
            if (!c.settings.backgroundPlay) service?.player?.playWhenReady = false
            else c.setVideoEnabled(false) // audio only while hidden: saves CPU on this tablet
        }
        c?.savePosition()
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing && visibleCount <= 0) {
            val p = service?.player
            val playing = p != null && p.playWhenReady &&
                (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING)
            val bg = controller?.settings?.backgroundPlay ?: false
            // Leaving the player while paused / ended / with background play off: release service, player and session.
            if (!playing || !bg) startService(Intent(this, PlaybackService::class.java).setAction(PlaybackService.ACTION_CLOSE))
        }
        handler.removeCallbacksAndMessages(null)
        controller?.let { if (it.listener === this) it.listener = null }
        service?.player?.removeListener(playerListener)
        playerView.player = null
        if (bound) { unbindService(connection); bound = false }
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            // The user used the fullscreen button in portrait; let rotation work normally again.
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        applyLayout()
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
        playerView.setOnClickListener { toggleControls() }

        spinner = ProgressBar(this)
        playerFrame.addView(spinner, FrameLayout.LayoutParams(dp(this, 48), dp(this, 48), Gravity.CENTER))
        statusText = TextView(this).apply {
            setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(dp(context, 24), 0, dp(context, 24), 0); visibility = View.GONE
        }
        retryButton = Button(this).apply { setText(R.string.retry); visibility = View.GONE; setOnClickListener { controller?.retry() } }
        val statusBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        statusBox.addView(statusText); statusBox.addView(retryButton)
        playerFrame.addView(statusBox, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        controls = LinearLayout(this)
        controls.orientation = LinearLayout.VERTICAL
        controls.setBackgroundColor(resources.getColor(R.color.controls_scrim))
        controls.setPadding(dp(this, 8), dp(this, 4), dp(this, 8), dp(this, 4))
        val seekRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        seek = SeekBar(this)
        timeText = TextView(this).apply { setTextColor(Color.WHITE); textSize = 12f; setPadding(dp(context, 8), 0, 0, 0) }
        seekRow.addView(seek, LinearLayout.LayoutParams(0, -2, 1f))
        seekRow.addView(timeText)
        val buttons = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val back10 = textButton("-10") { seekBy(-10_000) }
        playPause = ImageButton(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setImageResource(android.R.drawable.ic_media_pause)
            setOnClickListener { togglePlay() }
            contentDescription = getString(R.string.play_pause)
        }
        val fwd10 = textButton("+10") { seekBy(10_000) }
        qualityBtn = textButton(getString(R.string.quality)) { showQualityDialog() }
        ccBtn = textButton("CC") { showCaptionDialog() }
        speedBtn = textButton("1x") { showSpeedDialog() }
        fullscreenBtn = textButton("[ ]") { toggleFullscreen() }
        buttons.addView(back10)
        buttons.addView(playPause, LinearLayout.LayoutParams(dp(this, 56), dp(this, 44)))
        buttons.addView(fwd10)
        buttons.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        buttons.addView(qualityBtn); buttons.addView(ccBtn); buttons.addView(speedBtn); buttons.addView(fullscreenBtn)
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
        text = label; setTextColor(Color.WHITE); textSize = 13f; minWidth = 0; minimumWidth = 0
        setPadding(dp(context, 10), 0, dp(context, 10), 0)
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
        fullscreenBtn.text = if (landscape && fullscreen) "[x]" else "[ ]"
        root.requestLayout()
    }

    @Suppress("DEPRECATION")
    private fun applyImmersive(on: Boolean) {
        window.decorView.systemUiVisibility = if (on) {
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        } else View.SYSTEM_UI_FLAG_VISIBLE
    }

    private fun toggleFullscreen() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (!landscape) {
            fullscreen = true
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
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

    private fun updatePlayState() {
        val p = service?.player ?: return
        val playing = p.playWhenReady && p.playbackState != Player.STATE_ENDED && p.playbackState != Player.STATE_IDLE
        playPause.setImageResource(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
        spinner.visibility = if (p.playbackState == Player.STATE_BUFFERING ||
            (p.playbackState == Player.STATE_IDLE && controller?.lastError == null && controller?.videoId != null)) View.VISIBLE else View.GONE
        playerFrame.keepScreenOn = playing // keep the screen on while playing
        if (playing) scheduleHide() else { handler.removeCallbacks(hideControls); if (!controlsVisible) setControlsVisible(true) }
        speedBtn.text = String.format(Locale.US, "%sx", trimSpeed(controller?.speed ?: 1f))
        updateQualityLabel()
    }

    private fun updateQualityLabel() {
        val c = controller ?: return
        val h = c.chosenHeight()
        qualityBtn.text = if (h != null) "${h}p" else if (c.currentHeight() > 0) "${getString(R.string.auto)} ${c.currentHeight()}p" else getString(R.string.quality)
        ccBtn.alpha = if (c.chosenCaption != null) 1f else 0.5f
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
        val labels = ArrayList<String>().apply { add(getString(R.string.auto)); heights.forEach { add("${it}p") } }
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
        val labels = speeds.map { "${trimSpeed(it)}x" }.toTypedArray()
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
        setControlsVisible(true)
    }

    override fun onErrorCleared() {
        statusText.visibility = View.GONE; retryButton.visibility = View.GONE
    }

    override fun onTracksChanged() = updateQualityLabel()

    // ------------------------------------------------------------------ info + related list

    private inner class InfoAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var meta: MediaItemMetadata? = null
        private var related: List<MediaItem> = emptyList()
        private var descExpanded = false

        fun reset() { meta = null; related = emptyList(); descExpanded = false; notifyDataSetChanged() }

        fun setMetadata(m: MediaItemMetadata) {
            meta = m
            related = m.suggestions.orEmpty().flatMap { it.mediaItems.orEmpty() }.filter { !it.videoId.isNullOrEmpty() }
            notifyDataSetChanged()
        }

        override fun getItemCount() = 1 + related.size
        override fun getItemViewType(position: Int) = if (position == 0) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == 0) HeaderHolder(buildHeader(parent.context))
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
            val title = TextView(ctx).apply { setTextColor(Color.WHITE); textSize = 18f }
            val channel = Button(ctx, null, android.R.attr.borderlessButtonStyle).apply {
                setTextColor(0xFF90CAF9.toInt()); gravity = Gravity.START or Gravity.CENTER_VERTICAL; isAllCaps = false; setPadding(0, 0, 0, 0)
            }
            val stats = TextView(ctx).apply { setTextColor(resources.getColor(R.color.text_secondary)); textSize = 12f }
            val desc = TextView(ctx).apply { setTextColor(Color.LTGRAY); textSize = 13f; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(ctx, 8), 0, 0) }
            val more = Button(ctx, null, android.R.attr.borderlessButtonStyle).apply { isAllCaps = false; gravity = Gravity.START or Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 0) }
            val label = TextView(ctx).apply { setText(R.string.related); setTextColor(Color.WHITE); textSize = 15f; setPadding(0, dp(ctx, 8), 0, dp(ctx, 4)) }
            box.addView(title); box.addView(channel, LinearLayout.LayoutParams(-2, dp(ctx, 36))); box.addView(stats); box.addView(desc); box.addView(more, LinearLayout.LayoutParams(-2, dp(ctx, 36))); box.addView(label)
            return HeaderViews(box, title, channel, stats, desc, more, label)
        }
    }

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
