package com.aktivesolutions.dalan.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.aktivesolutions.dalan.R

/**
 * Container for skeleton placeholders. One ValueAnimator per container pulses the container's alpha
 * (1.0 <-> 0.5, 900 ms) on a hardware layer, so the cost is one texture blend however many blocks it holds.
 * It runs only while the container is attached and shown, and stops (alpha back to 1) otherwise.
 */
class PulseLayout(context: Context) : FrameLayout(context) {
    private var animator: ValueAnimator? = null

    /** Set to false once the real content has arrived to stop the pulse for good. */
    var pulsing = true
        set(v) { field = v; sync() }

    private fun sync() {
        if (pulsing && isAttachedToWindow && isShown && windowVisibility == View.VISIBLE) start() else stop()
    }

    private fun start() {
        if (animator != null) return
        // API 26+ honours "remove animations" / battery saver; older versions have no way to ask.
        if (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()) { alpha = 1f; return }
        setLayerType(LAYER_TYPE_HARDWARE, null)
        animator = ValueAnimator.ofFloat(1f, 0.5f).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { alpha = it.animatedValue as Float }
            start()
        }
    }

    private fun stop() {
        animator?.cancel()
        animator = null
        alpha = 1f
        setLayerType(LAYER_TYPE_NONE, null)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); sync() }
    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); sync() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); sync() }
}

/** Placeholder block: [frac] of the width it is offered (when its layout width is MATCH_PARENT), rounded. */
class SkeletonBar(context: Context, private val frac: Float = 1f, radiusDp: Int = 3) : View(context) {
    init {
        background = GradientDrawable().apply {
            setColor(context.themeColor(R.attr.dalanSkeleton))
            cornerRadius = dp(context, radiusDp).toFloat()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(if (frac < 1f) (w * frac).toInt() else w, h)
    }
}

/** Builders for skeleton layouts that mirror the real item layouts (same paddings / aspect ratios). */
object Skeleton {
    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun bar(ctx: Context, widthPx: Int, heightDp: Int, frac: Float = 1f, topDp: Int = 0, radiusDp: Int = 3): View =
        SkeletonBar(ctx, frac, radiusDp).also {
            it.layoutParams = LinearLayout.LayoutParams(widthPx, dp(ctx, heightDp)).apply { topMargin = dp(ctx, topDp) }
        }

    /** A 16:9 block (no rounding, like the real thumbnail). */
    private fun thumb(ctx: Context): View = ThumbView(ctx).apply {
        setBackgroundColor(ctx.themeColor(R.attr.dalanSkeleton))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
    }

    /** Mirrors item_card.xml: padding 6dp, 16:9 thumbnail, two title lines, one subtitle line. */
    fun card(ctx: Context, lp: ViewGroup.LayoutParams): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(ctx, 6), dp(ctx, 6), dp(ctx, 6), dp(ctx, 6))
        layoutParams = lp
        addView(thumb(ctx))
        addView(bar(ctx, MATCH, 12, 1f, 8))
        addView(bar(ctx, MATCH, 12, 0.6f, 5))
        addView(bar(ctx, MATCH, 10, 0.4f, 6))
    }

    /** Sections mode: [rows] x (title bar + horizontal row of cards 220dp wide, like SectionsAdapter). */
    fun sections(ctx: Context, rows: Int = 3): View {
        val cardW = dp(ctx, 220)
        val count = ctx.resources.displayMetrics.widthPixels / cardW + 1
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            repeat(rows) {
                addView(bar(ctx, dp(ctx, 140), 16, 1f, 12).also { (it.layoutParams as LinearLayout.LayoutParams).leftMargin = dp(ctx, 10) })
                val row = LinearLayout(ctx)
                repeat(count) { row.addView(card(ctx, LinearLayout.LayoutParams(cardW, WRAP))) }
                addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(ctx, 4) })
            }
        }
    }

    /** Flat mode: a [cols]-column grid of cards (same cell padding as the RecyclerView grid). */
    fun grid(ctx: Context, cols: Int, rows: Int = 4): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        repeat(rows) {
            val row = LinearLayout(ctx)
            repeat(cols) { row.addView(card(ctx, LinearLayout.LayoutParams(0, WRAP, 1f))) }
            addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        }
    }

    /** Mirrors item_related.xml: 160dp thumbnail, three text lines to the right. */
    fun related(ctx: Context): View = LinearLayout(ctx).apply {
        setPadding(dp(ctx, 6), dp(ctx, 6), dp(ctx, 6), dp(ctx, 6))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        addView(thumb(ctx), LinearLayout.LayoutParams(dp(ctx, 160), WRAP))
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 8), dp(ctx, 2), 0, 0)
            addView(bar(ctx, MATCH, 12, 1f))
            addView(bar(ctx, MATCH, 12, 0.7f, 6))
            addView(bar(ctx, MATCH, 10, 0.4f, 10))
        }
        addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
    }

    /** Player info panel: title (2 lines), channel, stats, then related rows. */
    fun playerInfo(ctx: Context, relatedCount: Int = 6): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(MATCH, WRAP)
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 12), dp(ctx, 14), dp(ctx, 12), dp(ctx, 8))
            addView(bar(ctx, MATCH, 16, 1f))
            addView(bar(ctx, MATCH, 16, 0.65f, 8))
            addView(bar(ctx, dp(ctx, 120), 12, 1f, 20))
            addView(bar(ctx, dp(ctx, 180), 10, 1f, 14))
            addView(bar(ctx, dp(ctx, 90), 14, 1f, 22))
        }
        addView(head)
        repeat(relatedCount) { addView(related(ctx)) }
    }

    /** Wraps [content] in a [PulseLayout]. */
    fun pulse(ctx: Context, content: View): PulseLayout = PulseLayout(ctx).apply {
        addView(content, FrameLayout.LayoutParams(MATCH, WRAP))
    }
}
