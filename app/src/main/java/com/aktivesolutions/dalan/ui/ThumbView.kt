package com.aktivesolutions.dalan.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView

/** ImageView that is always 16:9 (or [ratio]) for the width it is given. */
class ThumbView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ImageView(context, attrs) {
    var ratio: Float = 9f / 16f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * ratio).toInt())
    }
}
