package com.aktivesolutions.dalan.ui

import android.content.Context
import android.content.res.Configuration
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aktivesolutions.dalan.R
import com.aktivesolutions.dalan.data.Repo
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem
import io.reactivex.disposables.Disposable

/**
 * Shared feed widget: a grid (4 columns landscape / 3 portrait) with infinite scroll through
 * `continueGroup`, or a vertical list of titled horizontal rows, plus loading/error/empty states.
 */
class FeedView(context: Context) : FrameLayout(context) {
    private val recycler = RecyclerView(context)
    private val skeletonHolder = FrameLayout(context) // opaque, so a stale list behind it never shows through
    private var skeleton: PulseLayout? = null
    private var skeletonKey = ""
    private val moreSpinner = ProgressBar(context)
    private val message = TextView(context)
    private val retry = Button(context)
    private val errorBox = LinearLayout(context)

    private val cardClick: (MediaItem) -> Unit = { Nav.open(context, it) }
    private val gridAdapter = CardAdapter(0, cardClick)
    private val grid = GridLayoutManager(context, spanCount())
    private val list = LinearLayoutManager(context)
    private val sectionsAdapter = SectionsAdapter(context, cardClick)

    private var lastGroup: MediaGroup? = null
    private var loadingMore = false
    private var loadFailed = false // last loadMore failed: retried on the next user scroll
    private var endReached = false
    private var hasContent = false
    private var moreAction: Disposable? = null
    private var onRetry: (() -> Unit)? = null
    private var flat = true

    /** Called with true when the skeleton is shown and false when content, an error or the empty state replaces it. */
    var onLoadingChanged: ((Boolean) -> Unit)? = null

    init {
        recycler.setHasFixedSize(false)
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                // SETTLING == fling in progress: don't start new downloads until it stops.
                ImageLoader.setPaused(newState == RecyclerView.SCROLL_STATE_SETTLING)
            }

            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0) loadFailed = false // the user scrolled: allow a retry after a failure
                maybeLoadMore()
            }
        })
        addView(recycler, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        skeletonHolder.setBackgroundColor(context.themeColor(R.attr.dalanBg))
        skeletonHolder.visibility = View.GONE
        skeletonHolder.isClickable = true // swallow touches so nothing underneath can be tapped while loading
        skeletonHolder.contentDescription = context.getString(R.string.loading)
        addView(skeletonHolder, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        moreSpinner.visibility = View.GONE
        addView(moreSpinner, LayoutParams(dp(context, 32), dp(context, 32), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(context, 8) })

        message.gravity = Gravity.CENTER
        message.setTextColor(context.themeColor(R.attr.dalanTextPrimary))
        retry.setText(R.string.retry)
        retry.setStartGlyph(R.drawable.ic_refresh)
        retry.setOnClickListener { onRetry?.invoke() }
        errorBox.orientation = LinearLayout.VERTICAL
        errorBox.gravity = Gravity.CENTER_HORIZONTAL
        errorBox.addView(message)
        errorBox.addView(retry)
        addView(errorBox, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        showLoading()
    }

    private fun spanCount() =
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 4 else 3

    /** Call from Activity.onConfigurationChanged. */
    fun updateSpan() {
        grid.spanCount = spanCount(); sectionsAdapter.notifyDataSetChanged()
        if (skeletonHolder.visibility == View.VISIBLE) showSkeleton(skeletonKey.startsWith("s"))
    }

    /** Skeleton placeholders shaped like the content to come: titled rows ([sections]) or a grid. */
    fun showLoading(sections: Boolean = false) {
        hasContent = false
        moreAction?.dispose(); loadingMore = false
        errorBox.visibility = View.GONE
        moreSpinner.visibility = View.GONE
        recycler.visibility = View.INVISIBLE // a stale list must not stay live (touch / accessibility) under the skeleton
        showSkeleton(sections)
    }

    private fun showSkeleton(sections: Boolean) {
        val key = if (sections) "s${resources.displayMetrics.widthPixels}" else "g${spanCount()}"
        if (key != skeletonKey || skeleton == null) {
            skeleton?.let { skeletonHolder.removeView(it) } // detaching stops its animator
            val content = if (sections) Skeleton.sections(context) else Skeleton.grid(context, spanCount())
            skeleton = Skeleton.pulse(context, content).also { skeletonHolder.addView(it) }
            skeletonKey = key
        }
        val wasHidden = skeletonHolder.visibility != View.VISIBLE
        skeletonHolder.visibility = View.VISIBLE
        if (wasHidden) onLoadingChanged?.invoke(true)
    }

    private fun hideSkeleton() {
        if (skeletonHolder.visibility == View.GONE) return
        skeletonHolder.visibility = View.GONE // PulseLayout stops its animator when it is no longer shown
        onLoadingChanged?.invoke(false)
    }

    fun showError(text: String?, retryAction: () -> Unit) {
        hideSkeleton()
        message.text = if (text.isNullOrBlank()) context.getString(R.string.error_generic) else text
        retry.visibility = View.VISIBLE
        onRetry = retryAction
        errorBox.visibility = View.VISIBLE
    }

    fun showEmpty() {
        hideSkeleton()
        message.setText(R.string.empty)
        retry.visibility = View.GONE
        errorBox.visibility = View.VISIBLE
    }

    fun scrollToTop() { recycler.scrollToPosition(0) }

    /**
     * @param sections true to show one titled row per group when there are several groups;
     *                 false to flatten everything into one grid.
     */
    fun showGroups(groups: List<MediaGroup>, sections: Boolean) {
        val nonEmpty = groups.filter { !it.mediaItems.isNullOrEmpty() }
        if (nonEmpty.isEmpty()) { if (!hasContent) showEmpty(); return }
        if (!hasContent) { moreAction?.dispose(); loadingMore = false; moreSpinner.visibility = View.GONE; loadFailed = false; endReached = false }
        hasContent = true
        recycler.visibility = View.VISIBLE
        hideSkeleton()
        errorBox.visibility = View.GONE
        if (sections) {
            // Titled rows (one per group). Partial updates only add / refresh rows: no new LayoutManager or adapter.
            if (flat || recycler.adapter !== sectionsAdapter) {
                flat = false
                recycler.layoutManager = list
                recycler.adapter = sectionsAdapter
            }
            sectionsAdapter.update(nonEmpty)
        } else {
            if (!flat || recycler.adapter !== gridAdapter) {
                flat = true
                recycler.layoutManager = grid
                grid.spanCount = spanCount()
                recycler.adapter = gridAdapter
            }
            gridAdapter.update(nonEmpty.flatMap { it.mediaItems ?: emptyList() })
            // Continue the main results group (the first one that can page), not just whichever came last.
            lastGroup = nonEmpty.firstOrNull { it.nextPageKey != null } ?: nonEmpty.first()
            recycler.post { maybeLoadMore() } // the first page may not fill the screen: no scroll event would come
        }
    }

    private fun maybeLoadMore() {
        if (flat && !loadingMore && !loadFailed && !endReached &&
            grid.findLastVisibleItemPosition() >= gridAdapter.itemCount - 8) loadMore()
    }

    private fun loadMore() {
        val g = lastGroup ?: return
        if (g.nextPageKey == null) { endReached = true; return }
        loadingMore = true
        moreSpinner.visibility = View.VISIBLE
        var got = false
        moreAction = Repo.continueGroup(g).subscribe({ next ->
            got = true
            val items = next.mediaItems
            if (!items.isNullOrEmpty()) {
                gridAdapter.append(items)
                lastGroup = next
            } else {
                endReached = true // end of feed
            }
            loadingMore = false
            moreSpinner.visibility = View.GONE
            recycler.post { maybeLoadMore() }
        }, {
            loadingMore = false
            moreSpinner.visibility = View.GONE
            loadFailed = true // keep lastGroup: scrolling again retries
        }, {
            if (!got) { loadingMore = false; moreSpinner.visibility = View.GONE; endReached = true }
        })
    }

    fun dispose() { moreAction?.dispose(); skeleton?.let { skeletonHolder.removeView(it) } }
}

/** Vertical list of rows: section title + horizontal card row. */
class SectionsAdapter(private val context: Context, private val onClick: (MediaItem) -> Unit) :
    RecyclerView.Adapter<SectionsAdapter.RowHolder>() {

    private val groups = ArrayList<MediaGroup>()
    private val pool = RecyclerView.RecycledViewPool()
    private val cardWidth = dp(context, 220)

    /** Incremental: rows that already exist are kept, new rows are inserted at the end. */
    fun update(g: List<MediaGroup>) {
        val n = groups.size
        val prefixSame = g.size >= n && (0 until n).all { g[it] === groups[it] }
        if (!prefixSame) {
            groups.clear(); groups.addAll(g); notifyDataSetChanged()
            return
        }
        if (g.size > n) {
            groups.addAll(g.subList(n, g.size))
            notifyItemRangeInserted(n, g.size - n)
        }
    }

    class RowHolder(v: LinearLayout, val title: TextView, val row: RecyclerView, val adapter: CardAdapter) : RecyclerView.ViewHolder(v)

    override fun getItemCount() = groups.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL
        root.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val title = TextView(context)
        title.setTextColor(context.themeColor(R.attr.dalanTextPrimary))
        title.textSize = 16f
        title.setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 2))
        val row = RecyclerView(context)
        row.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        row.setRecycledViewPool(pool)
        row.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                ImageLoader.setPaused(newState == RecyclerView.SCROLL_STATE_SETTLING)
            }
        })
        val adapter = CardAdapter(cardWidth, onClick)
        row.adapter = adapter
        root.addView(title)
        root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return RowHolder(root, title, row, adapter)
    }

    override fun onBindViewHolder(h: RowHolder, position: Int) {
        val g = groups[position]
        h.title.text = g.title ?: ""
        h.title.visibility = if (g.title.isNullOrBlank()) View.GONE else View.VISIBLE
        h.adapter.setItems(g.mediaItems ?: emptyList())
    }
}
