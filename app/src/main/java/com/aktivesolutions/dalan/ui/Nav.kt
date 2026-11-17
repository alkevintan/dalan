package com.aktivesolutions.dalan.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.aktivesolutions.dalan.R
import com.aktivesolutions.dalan.data.Repo
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem

object Nav {
    fun open(context: Context, item: MediaItem) {
        when (item.type) {
            MediaItem.TYPE_CHANNEL -> {
                val i = Intent(context, ChannelActivity::class.java)
                    .putExtra(ChannelActivity.EXTRA_CHANNEL_ID, item.channelId)
                    .putExtra(ChannelActivity.EXTRA_NAME, item.title)
                    .putExtra(ChannelActivity.EXTRA_AVATAR, item.cardImageUrl)
                context.startActivity(i)
            }
            MediaItem.TYPE_PLAYLIST -> {
                Repo.pendingItem = item
                context.startActivity(
                    Intent(context, ChannelActivity::class.java)
                        .putExtra(ChannelActivity.EXTRA_NAME, item.title)
                        .putExtra(ChannelActivity.EXTRA_PLAYLIST, true)
                )
            }
            else -> {
                val id = item.videoId
                if (id.isNullOrEmpty()) {
                    Toast.makeText(context, R.string.unsupported_item, Toast.LENGTH_SHORT).show()
                } else {
                    openVideo(context, id)
                }
            }
        }
    }

    fun openVideo(context: Context, videoId: String, startSeconds: Int = 0) {
        context.startActivity(
            Intent(context, PlayerActivity::class.java)
                // Reuse the running player (singleTop) instead of stacking a second one on the same service.
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(PlayerActivity.EXTRA_VIDEO_ID, videoId)
                .putExtra(PlayerActivity.EXTRA_START_SECONDS, startSeconds)
        )
    }
}
