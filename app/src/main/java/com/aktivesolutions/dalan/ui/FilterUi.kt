package com.aktivesolutions.dalan.ui

import android.app.AlertDialog
import android.content.Context
import android.widget.Toast
import com.aktivesolutions.dalan.R
import com.aktivesolutions.dalan.data.Filters
import com.aktivesolutions.dalan.data.Repo
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem

/** The long-press menu on a video card, shared by the feeds and the player's related list. */
object FilterUi {
    /**
     * @param onChanged called after a filter was added, so the caller can re-filter what it shows
     * @param onRemoved called when the item should disappear from the list for this session ("Not interested")
     */
    fun showItemMenu(
        context: Context,
        item: MediaItem,
        onChanged: () -> Unit,
        onRemoved: (MediaItem) -> Unit,
        filtersApply: Boolean = true, // false on screens that never filter (History): offering "hide" there would do nothing visible
    ) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        val channelId = item.channelId
        if (filtersApply && !channelId.isNullOrEmpty()) {
            val author = item.author?.takeIf { it.isNotBlank() } ?: item.title.orEmpty()
            labels.add(context.getString(R.string.filter_hide_channel, author))
            actions.add {
                val rule = Filters.blockChannel(channelId, author)
                val msg = if (Filters.enabled) R.string.filter_channel_hidden else R.string.filter_channel_hidden_paused
                Toast.makeText(context, context.getString(msg, rule), Toast.LENGTH_SHORT).show()
                onChanged()
            }
        }
        if (filtersApply) {
            labels.add(context.getString(R.string.filter_hide_like))
            // Commas would split the title into separate (far too broad) terms.
            actions.add { FilterEditDialog.show(context, null, item.title.orEmpty().replace(',', ' '), onChanged) }
        }
        val token = item.feedbackToken
        if (!token.isNullOrEmpty() && Repo.isSignedIn()) {
            labels.add(context.getString(R.string.filter_not_interested))
            actions.add { Repo.notInterested(token); onRemoved(item) }
        }
        if (labels.isEmpty()) return
        AlertDialog.Builder(context)
            .setTitle(item.title.orEmpty())
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }
}
