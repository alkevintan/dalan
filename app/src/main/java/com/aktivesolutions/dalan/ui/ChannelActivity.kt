package com.aktivesolutions.dalan.ui

import android.app.Activity
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.aktivesolutions.dalan.R
import com.aktivesolutions.dalan.data.Repo
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import io.reactivex.Observable
import io.reactivex.disposables.Disposable

/** Channel header (avatar + name) and uploads grid. Also used for playlists (EXTRA_PLAYLIST). */
class ChannelActivity : ThemedActivity() {
    private lateinit var feed: FeedView
    private lateinit var mini: MiniPlayer
    private var channelId: String? = null
    private var isPlaylist = false
    private var action: Disposable? = null
    private var headerHolder: PulseLayout? = null
    private var headerBar: android.view.View? = null
    private var pulseRowView: LinearLayout? = null
    private var nameView: TextView? = null

    /** No name was passed in: use the title of the loaded feed, if it has one, else leave the name out. */
    private fun fallbackName(g: MediaGroup?) {
        val nv = nameView ?: return
        if (!nv.text.isNullOrBlank()) return
        val t = g?.title?.takeIf { it.isNotBlank() } ?: return
        nv.text = t; nv.visibility = android.view.View.VISIBLE
        title = t
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        channelId = intent.getStringExtra(EXTRA_CHANNEL_ID)
        isPlaylist = intent.getBooleanExtra(EXTRA_PLAYLIST, false)
        val name = intent.getStringExtra(EXTRA_NAME) ?: ""
        title = name
        actionBar?.setDisplayHomeAsUpEnabled(true)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val header = LinearLayout(this)
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(this, 12), dp(this, 8), dp(this, 12), dp(this, 8))
        // Avatar: a skeleton-coloured circle that pulses until the content has loaded, then the image fills it.
        val avatarUrl = intent.getStringExtra(EXTRA_AVATAR)
        val hasAvatar = !avatarUrl.isNullOrEmpty()
        val avatar = ImageView(this)
        avatar.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(themeColor(R.attr.dalanSkeleton)) }
        avatar.outlineProvider = ViewOutlineProvider.BACKGROUND
        avatar.clipToOutline = true
        avatar.scaleType = ImageView.ScaleType.CENTER_CROP
        val pulseRow = LinearLayout(this)
        pulseRow.gravity = Gravity.CENTER_VERTICAL
        if (hasAvatar) {
            pulseRow.addView(avatar, LinearLayout.LayoutParams(dp(this, 56), dp(this, 56)))
            ImageLoader.load(avatar, avatarUrl, dp(this, 56), dp(this, 56))
        }
        // Name placeholder only when the caller did not pass a name.
        val nameBar = if (name.isBlank()) Skeleton.bar(this, dp(this, 160), 20).also {
            (it.layoutParams as LinearLayout.LayoutParams).leftMargin = dp(this, 12)
            pulseRow.addView(it)
        } else null
        val headerPulse = PulseLayout(this)
        headerPulse.addView(pulseRow)
        if (hasAvatar || nameBar != null) header.addView(headerPulse)
        val nameView = TextView(this)
        nameView.text = name
        nameView.textSize = 20f
        nameView.setTextColor(themeColor(R.attr.dalanTextPrimary))
        nameView.setPadding(dp(this, 12), 0, 0, 0)
        nameView.visibility = if (name.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
        header.addView(nameView)
        this.nameView = nameView
        headerHolder = headerPulse
        headerBar = nameBar
        pulseRowView = pulseRow

        feed = FeedView(this)
        feed.onLoadingChanged = { loading ->
            val bar = headerBar
            if (loading) {
                // Retry: pulse again, and bring the name placeholder back if there is still no name.
                headerHolder?.pulsing = true
                if (bar != null && nameView?.text.isNullOrBlank() && bar.parent == null) pulseRowView?.addView(bar)
            } else {
                headerHolder?.pulsing = false
                if (bar != null) pulseRowView?.removeView(bar)
            }
        }
        root.addView(header)
        root.addView(feed, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        mini = MiniPlayer(this)
        root.addView(mini.view)
        setContentView(root)
        load()
    }

    private fun load() {
        action?.dispose()
        feed.showLoading()
        if (isPlaylist) {
            val item = Repo.pendingItem
            if (item == null) { feed.showEmpty(); return }
            var got = false
            action = Repo.group(item).subscribe(
                { g -> got = true; fallbackName(g); feed.showGroups(listOf(g), sections = false) },
                { e -> feed.showError(e.message) { load() } },
                { if (!got) feed.showEmpty() },
            )
            return
        }
        val id = channelId
        if (id == null) { feed.showEmpty(); return }
        var shown = false
        var last: List<MediaGroup> = emptyList()
        val source: Observable<List<MediaGroup>> = Repo.channel(id)
        action = source.subscribe({ groups ->
            last = groups
            if (!shown) {
                val uploads = groups.firstOrNull { it.type == MediaGroup.TYPE_CHANNEL_UPLOADS && !it.mediaItems.isNullOrEmpty() }
                if (uploads != null) {
                    shown = true
                    fallbackName(uploads)
                    feed.showGroups(listOf(uploads), sections = false)
                    action?.dispose()
                }
            }
        }, { e ->
            if (!shown) feed.showError(e.message) { load() }
        }, {
            // Finished without a dedicated uploads group: show the first non-empty one.
            if (!shown) {
                shown = true
                val first = last.firstOrNull { !it.mediaItems.isNullOrEmpty() }
                fallbackName(first)
                if (first != null) feed.showGroups(listOf(first), sections = false) else feed.showEmpty()
            }
        })
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig); feed.updateSpan()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    override fun onStart() { super.onStart(); mini.onStart() }

    override fun onStop() { mini.onStop(); super.onStop() }

    override fun onDestroy() { action?.dispose(); feed.dispose(); super.onDestroy() }

    companion object {
        const val EXTRA_CHANNEL_ID = "channel_id"
        const val EXTRA_NAME = "name"
        const val EXTRA_AVATAR = "avatar"
        const val EXTRA_PLAYLIST = "playlist"
    }
}
