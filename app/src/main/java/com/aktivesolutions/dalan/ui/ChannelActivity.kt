package com.aktivesolutions.dalan.ui

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.aktivesolutions.dalan.R
import com.aktivesolutions.dalan.data.Repo
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import io.reactivex.Observable
import io.reactivex.disposables.Disposable

/** Channel header (avatar + name) and uploads grid. Also used for playlists (EXTRA_PLAYLIST). */
class ChannelActivity : Activity() {
    private lateinit var feed: FeedView
    private var channelId: String? = null
    private var isPlaylist = false
    private var action: Disposable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_Dalan)
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
        val avatar = ImageView(this)
        avatar.setBackgroundColor(Color.DKGRAY)
        avatar.scaleType = ImageView.ScaleType.CENTER_CROP
        header.addView(avatar, LinearLayout.LayoutParams(dp(this, 56), dp(this, 56)))
        val nameView = TextView(this)
        nameView.text = name
        nameView.textSize = 20f
        nameView.setTextColor(Color.WHITE)
        nameView.setPadding(dp(this, 12), 0, 0, 0)
        header.addView(nameView)
        val avatarUrl = intent.getStringExtra(EXTRA_AVATAR)
        if (avatarUrl.isNullOrEmpty()) avatar.visibility = android.view.View.GONE
        else ImageLoader.load(avatar, avatarUrl, dp(this, 56), dp(this, 56))

        feed = FeedView(this)
        root.addView(header)
        root.addView(feed, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
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
                { g -> got = true; feed.showGroups(listOf(g), sections = false) },
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

    override fun onDestroy() { action?.dispose(); feed.dispose(); super.onDestroy() }

    companion object {
        const val EXTRA_CHANNEL_ID = "channel_id"
        const val EXTRA_NAME = "name"
        const val EXTRA_AVATAR = "avatar"
        const val EXTRA_PLAYLIST = "playlist"
    }
}
