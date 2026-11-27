package com.djcohen.offlinereader

/** Where taps on the page go. Each preset maps a tap position (fractions of width and height) to a zone. */
object TapZones {
    val names = listOf("Thirds", "Wide sides", "Tap anywhere", "L-shape", "Left-handed")

    /** [rtl] flips left and right (manga). */
    fun zoneAt(preset: Int, fx: Float, fy: Float, rtl: Boolean = false): PageView.Zone {
        val z = when (preset) {
            1 -> if (fx < 0.4f) PageView.Zone.PREV else if (fx > 0.6f) PageView.Zone.NEXT else PageView.Zone.MENU
            // top strip opens the menu, left third goes back, everything else goes forward
            2 -> if (fy < 0.15f) PageView.Zone.MENU else if (fx < 0.3f) PageView.Zone.PREV else PageView.Zone.NEXT
            // left column and top strip go back, the centre is the menu, right column and bottom strip go forward
            3 -> if (fx < 0.3f || fy < 0.2f) PageView.Zone.PREV else if (fx > 0.7f || fy > 0.8f) PageView.Zone.NEXT else PageView.Zone.MENU
            4 -> if (fx < 0.3f) PageView.Zone.NEXT else if (fx > 0.7f) PageView.Zone.PREV else PageView.Zone.MENU
            else -> if (fx < 0.3f) PageView.Zone.PREV else if (fx > 0.7f) PageView.Zone.NEXT else PageView.Zone.MENU
        }
        if (!rtl) return z
        return when (z) { PageView.Zone.PREV -> PageView.Zone.NEXT; PageView.Zone.NEXT -> PageView.Zone.PREV; else -> z }
    }

    private const val BACK = 0xFF3B82F6.toInt()
    private const val FWD = 0xFF6CC070.toInt()
    private const val MENU = 0xFF9A9A9A.toInt()

    /** A little phone drawn with the preset's zones: blue goes back, green goes forward, grey opens the menu. */
    private class Preview(context: android.content.Context, val preset: Int, var chosen: Boolean) : android.view.View(context) {
        private val d = resources.displayMetrics.density
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: android.graphics.Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val cols = 20; val rows = 30
            val clip = android.graphics.Path().apply { addRoundRect(0f, 0f, w, h, 8 * d, 8 * d, android.graphics.Path.Direction.CW) }
            c.save(); c.clipPath(clip)
            for (y in 0 until rows) for (x in 0 until cols) {
                paint.color = when (zoneAt(preset, (x + 0.5f) / cols, (y + 0.5f) / rows)) {
                    PageView.Zone.PREV -> BACK; PageView.Zone.NEXT -> FWD; else -> MENU
                }
                c.drawRect(x * w / cols, y * h / rows, (x + 1) * w / cols + 1, (y + 1) * h / rows + 1, paint)
            }
            c.restore()
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = (if (chosen) 3 else 1) * d
            paint.color = if (chosen) 0xFFFFFFFF.toInt() else 0x66000000
            c.drawRoundRect(paint.strokeWidth / 2, paint.strokeWidth / 2, w - paint.strokeWidth / 2, h - paint.strokeWidth / 2, 8 * d, 8 * d, paint)
            paint.style = android.graphics.Paint.Style.FILL
        }
    }

    /** Small zone pictures to tap, three to a row; [onPick] gets the preset number. */
    fun picker(context: android.content.Context, current: Int, onPick: (Int) -> Unit): android.view.View {
        val d = context.resources.displayMetrics.density
        val row = android.widget.LinearLayout(context).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        val previews = ArrayList<Preview>()
        names.forEachIndexed { i, n ->
            val p = Preview(context, i, i == current)
            previews += p
            p.setOnClickListener { previews.forEach { it.chosen = false; it.invalidate() }; p.chosen = true; p.invalidate(); onPick(i) }
            val cell = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER_HORIZONTAL
                setPadding(0, (2 * d).toInt(), 0, (2 * d).toInt())
                addView(p, android.widget.LinearLayout.LayoutParams((40 * d).toInt(), (62 * d).toInt()))
                addView(android.widget.TextView(context).apply {
                    // Two short lines instead of a hyphen, so no name splits or has to shrink.
                    text = (if (n == "Left-handed") "Left\nhanded" else n.replace(' ', '\n')).replace('-', '\u2011'); gravity = android.view.Gravity.CENTER
                    textSize = 11f; minLines = 2; maxLines = 2; hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
                    breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
                    setPadding((2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt(), 0)
                }, android.widget.LinearLayout.LayoutParams(-1, -2))
            }
            row.addView(cell, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
        }
        val legend = android.widget.TextView(context).apply {
            val words = listOf("Blue" to BACK, "back" to null, "Green" to FWD, "forward" to null, "Grey" to MENU, "menu" to null)
            val sb = android.text.SpannableStringBuilder()
            var k = 0
            while (k < words.size) {
                val (color, _) = words[k]
                val start = sb.length
                sb.append(color)
                sb.setSpan(android.text.style.ForegroundColorSpan(words[k].second!!), start, sb.length, 0)
                sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, sb.length, 0)
                sb.append(": ").append(words[k + 1].first)
                if (k + 2 < words.size) sb.append("      ")
                k += 2
            }
            text = sb; textSize = 12f; gravity = android.view.Gravity.CENTER; setPadding(0, (4 * d).toInt(), 0, 0)
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
        }
        return android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(row, android.widget.LinearLayout.LayoutParams(-1, -2))
            addView(legend, android.widget.LinearLayout.LayoutParams(-1, -2))
        }
    }
}
