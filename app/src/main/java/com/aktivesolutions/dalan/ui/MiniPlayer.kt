package com.aktivesolutions.dalan.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.aktivesolutions.dalan.R
import com.aktivesolutions.dalan.player.PlaybackService
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.Player

/**
 * "Now playing" bar shown at the bottom of browse screens while a Dalan session exists. It only binds to a
 * running [PlaybackService] (flags 0, never BIND_AUTO_CREATE) and shows a thumbnail, not live video.
 * Call [onStart] / [onStop] from the host activity.
 */
class MiniPlayer(private val activity: Activity) {
    val view: View
    private val thumb = ImageView(activity)
    private val titleView = TextView(activity)
    private val authorView = TextView(activity)
    private val playPause = ImageButton(activity)
    private val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal)
    private val handler = Handler(Looper.getMainLooper())

    private var service: PlaybackService? = null
    private var bound = false
    private var shownId: String? = null
    private val observer: () -> Unit = { update() }

    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 1000)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val s = (binder as PlaybackService.LocalBinder).service
            service = s
            s.addStateObserver(observer)
            update()
        }

        override fun onServiceDisconnected(name: ComponentName) { detach() }
    }

    init {
        val ctx: Context = activity
        val bar = FrameLayout(ctx)
        bar.setBackgroundColor(ctx.themeColor(R.attr.dalanSurface))
        bar.isClickable = true
        bar.setOnClickListener {
            ctx.startActivity(Intent(ctx, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }

        val row = LinearLayout(ctx)
        row.gravity = Gravity.CENTER_VERTICAL
        thumb.setBackgroundColor(ctx.themeColor(R.attr.dalanThumbBg))
        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        row.addView(thumb, LinearLayout.LayoutParams(dp(ctx, 114), dp(ctx, 64)))

        val texts = LinearLayout(ctx)
        texts.orientation = LinearLayout.VERTICAL
        texts.setPadding(dp(ctx, 12), 0, dp(ctx, 8), 0)
        titleView.setTextColor(ctx.themeColor(R.attr.dalanTextPrimary)); titleView.textSize = 14f
        titleView.maxLines = 1; titleView.ellipsize = TextUtils.TruncateAt.END
        authorView.setTextColor(ctx.themeColor(R.attr.dalanTextSecondary)); authorView.textSize = 12f
        authorView.maxLines = 1; authorView.ellipsize = TextUtils.TruncateAt.END
        texts.addView(titleView); texts.addView(authorView)
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val iconColor = ctx.themeColor(R.attr.dalanTextPrimary)
        fun button(icon: Int, desc: Int, onClick: () -> Unit) = playPauseLike(ctx, icon, ctx.getString(desc), iconColor, onClick)
        playPause.apply {
            setImageResource(R.drawable.ic_play)
            imageTintList = ColorStateList.valueOf(iconColor)
            setBackgroundResource(ctx.borderlessRippleRes())
            contentDescription = ctx.getString(R.string.play_pause)
            setOnClickListener { togglePlay() }
        }
        row.addView(playPause, LinearLayout.LayoutParams(dp(ctx, 48), dp(ctx, 64)))
        row.addView(
            button(R.drawable.ic_close, R.string.close) { close() },
            LinearLayout.LayoutParams(dp(ctx, 48), dp(ctx, 64))
        )
        bar.addView(row, FrameLayout.LayoutParams(-1, dp(ctx, 64)))

        progress.max = 1000
        progress.progressTintList = ColorStateList.valueOf(ctx.themeColor(R.attr.dalanAccent))
        progress.progressBackgroundTintList = ColorStateList.valueOf(ctx.themeColor(R.attr.dalanSkeleton))
        bar.addView(progress, FrameLayout.LayoutParams(-1, dp(ctx, 2), Gravity.TOP))

        bar.visibility = View.GONE
        view = bar
        view.layoutParams = ViewGroup.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun playPauseLike(ctx: Context, icon: Int, desc: String, tint: Int, onClick: () -> Unit) = ImageButton(ctx).apply {
        setImageResource(icon); imageTintList = ColorStateList.valueOf(tint)
        setBackgroundResource(ctx.borderlessRippleRes()); contentDescription = desc
        setOnClickListener { onClick() }
    }

    fun onStart() {
        if (bound) return
        bound = activity.bindService(Intent(activity, PlaybackService::class.java), connection, 0)
        update()
    }

    fun onStop() {
        handler.removeCallbacks(ticker)
        detach()
        if (bound) { try { activity.unbindService(connection) } catch (_: Exception) {}; bound = false }
    }

    private fun detach() {
        service?.removeStateObserver(observer)
        service = null
        handler.removeCallbacks(ticker)
        view.visibility = View.GONE
    }

    private fun togglePlay() {
        val p = service?.player ?: return
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
        p.playWhenReady = !p.playWhenReady
    }

    private fun close() {
        // Direct call, not startService(ACTION_CLOSE): closes synchronously, so a video opened right after can't
        // be overtaken by a still-queued close, and it never creates the service just to close it.
        service?.close()
        view.visibility = View.GONE
        handler.removeCallbacks(ticker)
    }

    private fun update() {
        val s = service
        val id = s?.controller?.videoId
        if (s == null || s.closed || id == null) {
            handler.removeCallbacks(ticker)
            view.visibility = View.GONE
            shownId = null
            return
        }
        val c = s.controller
        titleView.text = c.metadata?.title ?: c.formatInfo?.title ?: activity.getString(R.string.now_playing)
        authorView.text = c.metadata?.author ?: c.formatInfo?.author ?: ""
        if (id != shownId) {
            shownId = id
            ImageLoader.load(thumb, "https://i.ytimg.com/vi/$id/mqdefault.jpg", dp(activity, 114), dp(activity, 64))
        }
        val playing = s.player.playWhenReady && s.player.playbackState != Player.STATE_ENDED
        playPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        if (view.visibility != View.VISIBLE) {
            view.visibility = View.VISIBLE
            handler.removeCallbacks(ticker)
            handler.post(ticker)
        }
        updateProgress()
    }

    private fun updateProgress() {
        val p = service?.player ?: return
        val dur = p.duration
        progress.progress = if (dur != C.TIME_UNSET && dur > 0) (p.currentPosition * 1000 / dur).toInt().coerceIn(0, 1000) else 0
    }
}
