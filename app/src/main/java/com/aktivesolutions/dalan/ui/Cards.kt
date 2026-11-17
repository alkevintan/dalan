package com.aktivesolutions.dalan.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.aktivesolutions.dalan.R
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem
import java.util.Locale

internal fun formatDuration(ms: Long): String {
    if (ms <= 0) return ""
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

class CardHolder(v: View) : RecyclerView.ViewHolder(v) {
    val thumb: ImageView = v.findViewById(R.id.thumb)
    val badge: TextView = v.findViewById(R.id.badge)
    val title: TextView = v.findViewById(R.id.title)
    val subtitle: TextView = v.findViewById(R.id.subtitle)
}

/**
 * Cards for videos / channels / playlists. [cardWidthPx] > 0 gives a fixed width (horizontal rows),
 * 0 means "fill the grid cell".
 */
class CardAdapter(
    private val cardWidthPx: Int = 0,
    private val onClick: (MediaItem) -> Unit,
) : RecyclerView.Adapter<CardHolder>() {

    private val items = ArrayList<MediaItem>()

    fun setItems(newItems: List<MediaItem>) {
        items.clear(); items.addAll(newItems); notifyDataSetChanged()
    }

    /**
     * Replaces the content; when [newItems] merely extends the current list (the usual partial-feed update)
     * only the new tail is inserted so the list does not jump.
     */
    fun update(newItems: List<MediaItem>) {
        val n = items.size
        if (n > 0 && newItems.size >= n && newItems[0] === items[0] && newItems[n - 1] === items[n - 1]) {
            append(newItems.subList(n, newItems.size))
        } else setItems(newItems)
    }

    fun append(more: List<MediaItem>) {
        if (more.isEmpty()) return
        val start = items.size
        items.addAll(more)
        notifyItemRangeInserted(start, more.size)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_card, parent, false)
        if (cardWidthPx > 0) v.layoutParams = RecyclerView.LayoutParams(cardWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
        else v.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return CardHolder(v)
    }

    override fun onBindViewHolder(h: CardHolder, position: Int) {
        val item = items[position]
        h.title.text = item.title ?: ""
        val sub = item.secondTitle?.toString().takeUnless { it.isNullOrBlank() } ?: item.author ?: ""
        h.subtitle.text = sub
        val badge = when {
            item.isLive -> "LIVE"
            !item.badgeText.isNullOrBlank() -> item.badgeText
            else -> formatDuration(item.durationMs)
        }
        h.badge.text = badge
        h.badge.visibility = if (badge.isNullOrEmpty()) View.GONE else View.VISIBLE
        val w = if (cardWidthPx > 0) cardWidthPx else 256
        ImageLoader.load(h.thumb, item.cardImageUrl, w, w * 9 / 16)
        h.itemView.setOnClickListener { onClick(item) }
    }

    override fun onViewRecycled(h: CardHolder) { ImageLoader.cancel(h.thumb) }
}

internal fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density + 0.5f).toInt()
