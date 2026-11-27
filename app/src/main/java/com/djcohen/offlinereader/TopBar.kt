package com.djcohen.offlinereader

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** The bar shown at the top of a reader when the middle of the page is tapped: back arrow (same as the audiobook player) plus the book title. */
class TopBar(context: Context, onBack: () -> Unit) : LinearLayout(context) {
    private val label = TextView(context).apply {
        setTextColor(Color.WHITE); textSize = 16f
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
    }

    var text: CharSequence
        get() = label.text
        set(v) { label.text = v }

    private val bookmarkButton = ImageView(context)

    /** Adds a bookmark button at the right end of the bar. */
    fun setBookmarkAction(action: () -> Unit) {
        val d = resources.displayMetrics.density
        bookmarkButton.apply {
            setImageResource(R.drawable.ic_bookmark)
            setColorFilter(Color.WHITE)
            contentDescription = "Bookmarks"
            background = RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), null, GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
            isClickable = true
            setOnClickListener { action() }
            setPadding((10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
        }
        if (bookmarkButton.parent == null) addView(bookmarkButton, LayoutParams((48 * d).toInt(), (48 * d).toInt()))
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(0xE6202020.toInt())
        val d = resources.displayMetrics.density
        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_arrow_back)
            setColorFilter(Color.WHITE)
            contentDescription = "Back to the library"
            background = RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), null, GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
            isClickable = true
            setOnClickListener { onBack() }
            setPadding((10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
        }
        addView(icon, LayoutParams((48 * d).toInt(), (48 * d).toInt()))
        addView(label, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = (10 * d).toInt() })
    }
}
