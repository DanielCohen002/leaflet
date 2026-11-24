package com.djcohen.offlinereader

import android.content.Context
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Shader
import android.view.animation.AccelerateDecelerateInterpolator
import android.os.Handler
import android.os.Looper
import android.view.ViewConfiguration
import java.text.BreakIterator
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/** Draws one page of a chapter. Pagination is done by slicing a StaticLayout into screen-height runs of lines. */
class PageView(context: Context) : View(context) {
    enum class Zone { PREV, MENU, NEXT }

    companion object {
        const val STYLE_NONE = 0
        const val STYLE_SLIDE = 1
        const val STYLE_CURL = 2
        const val STYLE_FLIP = 3
    }

    var onZone: ((Zone) -> Unit)? = null
    /** Called with the href of a link the reader tapped. */
    var onLink: ((String) -> Unit)? = null
    var footerText: String = ""
    /** If set, gives the footer title for the page whose first character is at the given offset (overrides [footerText]). */
    var footerProvider: ((Int) -> String)? = null
    /** Character offsets where a new page must begin (chapter starts inside the text). */
    private var breaks = IntArray(0)

    private val density = resources.displayMetrics.density
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 16 * density }

    private var text: CharSequence = ""
    private var layout: StaticLayout? = null
    private var pageStartLines = IntArray(0)
    private var page = 0
    private var fg = 0xFFFFFFFF.toInt()
    private var bg = 0xFF000000.toInt()
    private var spacingMult = 1.4f
    private var insetL = 0; private var insetT = 0; private var insetR = 0; private var insetB = 0
    private var pendingOffset = 0
    private var raw: CharSequence = ""     // the chapter as loaded; [text] is this plus paragraph indents
    private var indentPx = 0
    private var justify = false

    // --- continuous scroll mode (instead of turning pages) -------------------------------------
    private var scrollMode = false
    private var scrollPx = 0
    private val scroller = android.widget.OverScroller(context)
    private var scrolling = false
    private var lastY = 0f
    private var velocity: android.view.VelocityTracker? = null

    private fun maxScroll() = ((layout?.height ?: 0) - contentHeight).coerceAtLeast(0)

    /** Layout y that sits at the top of the text area. */
    private fun topY(): Int {
        val l = layout ?: return 0
        return if (scrollMode) scrollPx else l.getLineTop(pageStartLines[page])
    }

    private fun firstLine(): Int {
        val l = layout!!
        return if (scrollMode) l.getLineForVertical(scrollPx) else pageStartLines[page]
    }

    private fun lastLine(): Int {
        val l = layout!!
        return if (scrollMode) l.getLineForVertical(scrollPx + contentHeight).coerceAtMost(l.lineCount - 1)
        else (if (page + 1 < pageStartLines.size) pageStartLines[page + 1] else l.lineCount) - 1
    }

    /** Switch between paged and continuous-scroll reading, keeping the current position. */
    fun setScrollMode(enabled: Boolean) {
        if (enabled == scrollMode) return
        val keep = currentOffset
        scroller.forceFinished(true)
        scrollMode = enabled
        relayout(keep)
        invalidate()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollPx = scroller.currY.coerceIn(0, maxScroll())
            postInvalidateOnAnimation()
            invalidate()
        }
    }

    val pageCount get() = pageStartLines.size
    val pageIndex get() = page
    val contentWidth get() = (width - insetL - insetR - 2 * margin).coerceAtLeast(100)
    val contentHeight get() = (height - insetT - insetB - vMargin - footerHeight).coerceAtLeast(100)
    private val margin get() = (22 * density).toInt()
    private val vMargin get() = (14 * density).toInt()
    private val footerHeight get() = (footerPaint.textSize + 6 * density).toInt()

    /** Character offset at the top of the current page, used to save and restore the reading position. */
    val currentOffset: Int
        get() {
            val l = layout ?: return 0
            if (scrollMode) return l.getLineStart(l.getLineForVertical(scrollPx))
            return if (pageStartLines.isEmpty()) 0 else l.getLineStart(pageStartLines[page])
        }

    // --- selection -------------------------------------------------------------------------

    /** Called with the selected text, or null when the selection is cleared. */
    var onSelection: ((String?) -> Unit)? = null

    private var selStart = -1
    private var selEnd = -1
    private val selPath = Path()
    private val selPaint = Paint().apply { color = 0x66FFB300; style = Paint.Style.FILL }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFA000.toInt() }
    private val handleRadius get() = 9 * density
    private val handler = Handler(Looper.getMainLooper())
    private var longPressFired = false
    private var dragging = 0            // 0 none, 1 start handle, 2 end handle
    private var dragMinEnd = -1
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    val hasSelection get() = selStart >= 0

    /** Vertical screen position of the selection start, used to keep the action bar out of the way. */
    fun selectionY(): Float = if (selStart >= 0 && layout != null) handlePoint(selStart, false)[1] else 0f

    fun selectedText(): String? =
        if (selStart in 0 until selEnd && selEnd <= text.length) text.subSequence(selStart, selEnd).toString().replace('￼', ' ').trim().ifEmpty { null } else null

    fun clearSelection() {
        if (selStart < 0) return
        selStart = -1; selEnd = -1; dragging = 0
        onSelection?.invoke(null)
        invalidate()
    }

    private fun pageRange(): IntRange? {
        val l = layout ?: return null
        if (pageStartLines.isEmpty() || l.lineCount == 0) return null
        return l.getLineStart(firstLine())..l.getLineEnd(lastLine())
    }

    /** Character offset under screen point (x, y), restricted to the current page. */
    private fun offsetAt(x: Float, y: Float): Int {
        val l = layout ?: return 0
        val first = firstLine()
        val last = lastLine()
        val ly = y - (insetT + vMargin) + topY()
        val line = l.getLineForVertical(ly.toInt()).coerceIn(first, last)
        return l.getOffsetForHorizontal(line, x - (insetL + margin)).coerceIn(pageRange()!!)
    }

    private fun selectWordAt(x: Float, y: Float): Boolean {
        val range = pageRange() ?: return false
        if (text.isEmpty()) return false
        val o = offsetAt(x, y).coerceIn(range.first, (range.last - 1).coerceAtLeast(range.first))
        val bi = BreakIterator.getWordInstance()
        val str = text.toString()
        bi.setText(str)
        var start = if (bi.isBoundary(o)) o else bi.preceding(o)
        var end = bi.following(start).let { if (it == BreakIterator.DONE) str.length else it }
        // Landed on a space or punctuation: snap to the word just before it, else the one after.
        if (str.substring(start, end.coerceAtMost(str.length)).none { it.isLetterOrDigit() }) {
            val ps = bi.preceding(start)
            if (ps != BreakIterator.DONE && str.substring(ps, start).any { it.isLetterOrDigit() }) {
                end = start; start = ps
            } else if (end < str.length) {
                val ne = bi.following(end)
                if (ne != BreakIterator.DONE) { start = end; end = ne }
            }
        }
        val word = str.substring(start, end.coerceAtMost(str.length))
        if (word.none { it.isLetterOrDigit() }) return false
        selStart = start.coerceAtLeast(range.first); selEnd = end.coerceAtMost(range.last)
        dragMinEnd = selEnd
        return true
    }

    private fun handlePoint(offset: Int, isEnd: Boolean): FloatArray {
        val l = layout!!
        var line = l.getLineForOffset(offset)
        var x = l.getPrimaryHorizontal(offset)
        if (isEnd && line > l.getLineForOffset(selStart) && l.getLineStart(line) == offset) {
            line -= 1; x = l.getLineRight(line)
        }
        val y = l.getLineBaseline(line) + paint.descent().toInt() - topY() + insetT + vMargin
        return floatArrayOf(x + insetL + margin, y.toFloat())
    }

    private val longPress = Runnable {
        if (selectWordAt(downX, downY)) {
            longPressFired = true
            dragging = 2
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            onSelection?.invoke(selectedText())
            invalidate()
        }
    }

    fun setInsets(l: Int, t: Int, r: Int, b: Int) {
        if (l == insetL && t == insetT && r == insetR && b == insetB) return
        insetL = l; insetT = t; insetR = r; insetB = b
        relayout(currentOffset)
    }

    fun setStyle(typeface: Typeface, sizeSp: Int, spacing: Float, fgColor: Int, bgColor: Int, indentEm: Float = 1.2f, justified: Boolean = false) {
        justify = justified
        clearSelection()
        val keep = currentOffset
        paint.typeface = typeface
        paint.textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, sizeSp.toFloat(), resources.displayMetrics)
        paint.color = fgColor
        paint.linkColor = fgColor
        indentPx = (indentEm * paint.textSize).toInt()
        footerPaint.color = (fgColor and 0x00FFFFFF) or 0xBB000000.toInt()
        footerPaint.typeface = typeface
        footerPaint.textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        fg = fgColor; bg = bgColor; spacingMult = spacing
        relayout(keep)
        invalidate()
    }

    /** Show [content], opening at the page containing [offset]; a negative offset opens on the last page. */
    fun setContent(content: Spanned, offset: Int, pageBreaks: List<Int> = emptyList()) {
        clearSelection()
        breaks = pageBreaks.sorted().toIntArray()
        raw = content
        text = content
        relayout(offset)
        invalidate()
    }

    private fun relayout(offset: Int) {
        cancelTurn()
        clearSelection()
        if (width == 0 || height == 0) { pendingOffset = offset; return }
        text = withIndent(raw)
        val len = text.length
        layout = StaticLayout.Builder.obtain(text, 0, len, paint, contentWidth)
            .setLineSpacing(0f, spacingMult).setIncludePad(false)
            .setJustificationMode(if (justify) Layout.JUSTIFICATION_MODE_INTER_WORD else Layout.JUSTIFICATION_MODE_NONE).build()
        val l = layout!!
        if (scrollMode) {
            pageStartLines = intArrayOf(0)
            page = 0
            scrollPx = if (offset < 0) maxScroll()
            else l.getLineTop(l.getLineForOffset(offset.coerceAtMost(len))).coerceIn(0, maxScroll())
            return
        }
        val starts = ArrayList<Int>()
        val avail = contentHeight
        var line = 0
        while (line < l.lineCount) {
            starts += line
            val top = l.getLineTop(line)
            var end = line
            while (end + 1 < l.lineCount && l.getLineBottom(end + 1) - top <= avail && !startsChapter(l, end + 1)) end++
            line = end + 1
        }
        if (starts.isEmpty()) starts += 0
        pageStartLines = starts.toIntArray()
        page = if (offset < 0) pageStartLines.size - 1 else pageForOffset(offset)
    }

    /** True if a chapter starts on [line], so that line has to open a new page. */
    private fun startsChapter(l: StaticLayout, line: Int): Boolean {
        if (breaks.isEmpty()) return false
        val a = l.getLineStart(line); val b = l.getLineEnd(line)
        return breaks.any { it in a until b }
    }

    /** Show the page (or scroll position) containing character [offset]; used when following a link or going back. */
    fun jumpToOffset(offset: Int) {
        clearSelection()
        val l = layout ?: return
        val o = offset.coerceIn(0, text.length)
        if (scrollMode) {
            scroller.forceFinished(true)
            scrollPx = l.getLineTop(l.getLineForOffset(o)).coerceIn(0, maxScroll())
        } else {
            page = pageForOffset(o)
        }
        invalidate()
    }

    /** The href of the link under the screen point (x, y), or null if the point isn't on link text. */
    private fun linkAt(x: Float, y: Float): String? {
        val l = layout ?: return null
        val spanned = text as? Spanned ?: return null
        if (pageStartLines.isEmpty() || l.lineCount == 0) return null
        val ly = y - (insetT + vMargin) + topY()
        val line = l.getLineForVertical(ly.toInt())
        if (line < firstLine() || line > lastLine()) return null
        val lx = x - (insetL + margin)
        if (lx < l.getLineLeft(line) - slop || lx > l.getLineRight(line) + slop) return null
        val offset = l.getOffsetForHorizontal(line, lx)
        for (o in intArrayOf(offset, offset - 1)) {
            if (o < 0 || o >= spanned.length) continue
            val spans = spanned.getSpans(o, o + 1, android.text.style.URLSpan::class.java)
            if (spans.isNotEmpty()) return spans[0].url
        }
        return null
    }

    /** Indent the first line of every prose paragraph, including short ones such as lines of dialogue. */
    private fun withIndent(src: CharSequence): CharSequence {
        if (indentPx <= 0 || src.isEmpty()) return src
        val sb = android.text.SpannableStringBuilder(src)
        var i = 0
        while (i < sb.length) {
            var j = sb.indexOf('\n', i)
            if (j < 0) j = sb.length
            if (j > i && isProse(sb, i, j)) {
                sb.setSpan(android.text.style.LeadingMarginSpan.Standard(indentPx, 0), i, minOf(j + 1, sb.length), Spanned.SPAN_PARAGRAPH)
            }
            i = j + 1
        }
        return sb
    }

    /** False for things that should not be indented: headings, centered lines, lists, quotes, scene breaks, pictures. */
    private fun isProse(sb: android.text.SpannableStringBuilder, start: Int, end: Int): Boolean {
        var letters = 0
        for (k in start until end) {
            if (sb[k].isLetter()) { letters++; if (letters >= 2) break }
        }
        if (letters < 2) return false                                   // "* * *", a picture, a page number
        if (sb.getSpans(start, end, android.text.style.RelativeSizeSpan::class.java).any { it.sizeChange > 1.05f }) return false
        if (sb.getSpans(start, end, android.text.style.AlignmentSpan::class.java).any { it.alignment != Layout.Alignment.ALIGN_NORMAL }) return false
        if (sb.getSpans(start, end, android.text.style.BulletSpan::class.java).isNotEmpty()) return false
        if (sb.getSpans(start, end, android.text.style.QuoteSpan::class.java).isNotEmpty()) return false
        // A short line that doesn't end like a sentence is a title or label (plain-text books have no heading markup).
        if (end - start < 40 && sb[end - 1] !in ".!?,;:”\"’'…)—-") return false
        return true
    }

    private fun pageForOffset(offset: Int): Int {
        val l = layout ?: return 0
        var p = 0
        for (i in pageStartLines.indices) {
            if (l.getLineStart(pageStartLines[i]) <= offset) p = i else break
        }
        return p
    }

    /** In scroll mode this scrolls by most of a screen; returns false when already at the end. */
    fun nextPage(): Boolean {
        clearSelection()
        if (scrollMode) return scrollBy(+1)
        if (turnRunning) return true
        if (page + 1 >= pageCount) return false
        if (prepareTurn(true)) { startTurn(true); return true }
        page++; invalidate(); return true
    }

    fun prevPage(): Boolean {
        clearSelection()
        if (scrollMode) return scrollBy(-1)
        if (turnRunning) return true
        if (page <= 0) return false
        if (prepareTurn(false)) { startTurn(false); return true }
        page--; invalidate(); return true
    }

    // --- page-turn animation (Slide, Curl, Flip) --------------------------------------------
    // The current page and its neighbour are drawn to bitmaps; the animation only moves those bitmaps around.

    /** 0 none, 1 slide, 2 corner curl, 3 flip. */
    var turnStyle = STYLE_SLIDE
        set(v) { field = v; cancelTurn() }

    /** Right-to-left reading (manga): taps, swipes and the page-turn animation are mirrored. */
    var rtl = false
        set(v) { field = v; cancelTurn() }

    private fun mirrored(b: Bitmap): Bitmap {
        val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { preScale(-1f, 1f) }, false)
        b.recycle()
        return r
    }

    private var bmpCur: Bitmap? = null
    private var bmpOther: Bitmap? = null
    private var turnFwd = true
    private var turnProg = 0f
    private val curlP = floatArrayOf(0f, 0f)
    private var turnAnim: ValueAnimator? = null
    private var turnRunning = false
    private var dragTurn = false
    private val camera = Camera()
    private val camMatrix = Matrix()
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shadePaint = Paint()
    private val flapStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private fun animationsOn() = ValueAnimator.areAnimatorsEnabled()

    private fun renderPage(p: Int): Bitmap {
        val saved = page
        page = p
        val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawPage(Canvas(b), false)
        page = saved
        return b
    }

    private fun prepareTurn(forward: Boolean): Boolean {
        if (turnStyle == STYLE_NONE || scrollMode || width == 0 || height == 0 || layout == null || !animationsOn()) return false
        val other = if (forward) page + 1 else page - 1
        if (other !in 0 until pageCount) return false
        clearSelection()
        cancelTurn()
        bmpCur = renderPage(page).let { if (rtl) mirrored(it) else it }
        bmpOther = renderPage(other).let { if (rtl) mirrored(it) else it }
        turnFwd = forward
        turnProg = 0f
        setCurl(corner())
        return true
    }

    private fun corner() = floatArrayOf(width.toFloat(), height.toFloat())
    private fun curlEnd() = floatArrayOf(-Math.hypot(width.toDouble(), height.toDouble()).toFloat() * 0.95f, height * 0.5f)
    private fun setCurl(p: FloatArray) { curlP[0] = p[0]; curlP[1] = p[1] }

    private fun freeTurn() {
        bmpCur?.recycle(); bmpOther?.recycle()
        bmpCur = null; bmpOther = null
    }

    fun cancelTurn() {
        turnAnim?.removeAllListeners(); turnAnim?.cancel(); turnAnim = null
        turnRunning = false; dragTurn = false
        if (bmpCur != null) { freeTurn(); invalidate() }
    }

    private fun startTurn(forward: Boolean) {
        if (turnStyle == STYLE_CURL) {
            if (forward) runTurn(true, 0f, 1f, corner(), curlEnd(), true, 360)
            else { setCurl(curlEnd()); runTurn(true, 0f, 1f, curlEnd(), corner(), true, 360) }
        } else runTurn(true, 0f, 1f, null, null, false, 210)
    }

    /** Animates slide/flip progress from [p0] to [p1], or the curl corner from [s] to [e]; [commit] turns the page at the end. */
    private fun runTurn(commit: Boolean, p0: Float, p1: Float, s: FloatArray?, e: FloatArray?, arc: Boolean, duration: Long) {
        turnRunning = true
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = duration
        a.interpolator = if (turnStyle == STYLE_CURL) TimeInterpolator { t -> (t + AccelerateDecelerateInterpolator().getInterpolation(t)) / 2f }
        else AccelerateDecelerateInterpolator()
        a.addUpdateListener {
            val f = it.animatedValue as Float
            if (s != null && e != null) {
                curlP[0] = s[0] + (e[0] - s[0]) * f
                curlP[1] = s[1] + (e[1] - s[1]) * f - (if (arc) 0.1f * height * Math.sin(Math.PI * f).toFloat() else 0f)
            } else turnProg = p0 + (p1 - p0) * f
            invalidate()
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (turnAnim !== a) return
                turnAnim = null; turnRunning = false
                if (commit) page = (page + if (turnFwd) 1 else -1).coerceIn(0, pageCount - 1)
                freeTurn()
                invalidate()
            }
        })
        turnAnim = a
        a.start()
    }

    /** Follows the finger while a slide or curl is dragged. Returns true while it owns the gesture. */
    private fun turnDragMove(e: MotionEvent): Boolean {
        if (turnStyle != STYLE_SLIDE && turnStyle != STYLE_CURL) return false
        if (scrollMode || longPressFired || hasSelection || turnRunning) return false
        val dx = (if (rtl) -1 else 1) * (e.x - downX)     // in right-to-left mode everything is computed mirrored
        val dy = e.y - downY
        if (!dragTurn) {
            if (abs(dx) <= slop || abs(dx) < abs(dy) * 1.5f) return false
            val fwd = dx < 0
            if (turnStyle == STYLE_CURL && !fwd) return false
            if (!prepareTurn(fwd)) return false
            dragTurn = true
            handler.removeCallbacks(longPress)
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        if (turnStyle == STYLE_SLIDE) {
            turnProg = ((if (turnFwd) -dx else dx) / width).coerceIn(0f, 1f)
        } else {
            curlP[0] = (width + dx).coerceIn(-width.toFloat(), width - 2f)
            curlP[1] = (height + dy).coerceIn(height * 0.15f, height - 2f)
        }
        invalidate()
        return true
    }

    private fun finishTurnDrag() {
        dragTurn = false
        if (turnStyle == STYLE_SLIDE) {
            val commit = turnProg > 0.25f
            val left = if (commit) 1f - turnProg else turnProg
            runTurn(commit, turnProg, if (commit) 1f else 0f, null, null, false, (210 * left).toLong().coerceAtLeast(90))
        } else {
            val commit = curlP[0] < width * 0.5f
            runTurn(commit, 0f, 1f, curlP.copyOf(), if (commit) curlEnd() else corner(), false, 360)
        }
    }

    private fun drawTurn(c: Canvas) {
        c.save()
        if (rtl) c.scale(-1f, 1f, width / 2f, 0f)
        drawTurnInner(c)
        c.restore()
    }

    private fun drawTurnInner(c: Canvas) {
        val cur = bmpCur ?: return
        val other = bmpOther ?: return
        val w = width.toFloat(); val h = height.toFloat()
        when (turnStyle) {
            STYLE_FLIP -> {
                val under = if (turnFwd) other else cur
                val flipper = if (turnFwd) cur else other
                val k = if (turnFwd) turnProg else 1f - turnProg
                c.drawBitmap(under, 0f, 0f, null)
                if (k < 0.995f) {
                    camera.save()
                    camera.setLocation(0f, 0f, -(w * 1.4f / 72f))
                    camera.rotateY(-90f * k)
                    camera.getMatrix(camMatrix)
                    camera.restore()
                    camMatrix.preTranslate(0f, -h / 2f)
                    camMatrix.postTranslate(0f, h / 2f)
                    val b = 1f - 0.5f * k
                    bmpPaint.colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                        b, 0f, 0f, 0f, 0f,  0f, b, 0f, 0f, 0f,  0f, 0f, b, 0f, 0f,  0f, 0f, 0f, 1f, 0f)))
                    c.drawBitmap(flipper, camMatrix, bmpPaint)
                    bmpPaint.colorFilter = null
                }
            }
            STYLE_CURL -> drawCurl(c, cur, other, w, h)
            else -> {
                val off = turnProg * w
                if (turnFwd) { c.drawBitmap(cur, -off, 0f, null); c.drawBitmap(other, w - off, 0f, null) }
                else { c.drawBitmap(cur, off, 0f, null); c.drawBitmap(other, -w + off, 0f, null) }
            }
        }
    }

    /** Corner curl: the corner (w,h) is dragged to P; the fold is the perpendicular bisector of the two, and the part of the page past it is reflected to draw the back. */
    private fun drawCurl(c: Canvas, cur: Bitmap, other: Bitmap, w: Float, h: Float) {
        val top = if (turnFwd) cur else other
        val under = if (turnFwd) other else cur
        c.drawBitmap(under, 0f, 0f, null)
        val dx = w - curlP[0]; val dy = h - curlP[1]
        val len = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (len < 2f) { c.drawBitmap(top, 0f, 0f, null); return }
        val nx = dx / len; val ny = dy / len
        val mx = (w + curlP[0]) / 2f; val my = (h + curlP[1]) / 2f
        val d = mx * nx + my * ny
        val rect = floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h)
        val pSide = clipPoly(rect, nx, ny, d, false)
        val cSide = clipPoly(rect, nx, ny, d, true)
        val shade = 48 * density
        if (cSide.size >= 6) {                       // shadow cast on the page underneath
            c.save(); c.clipPath(polyPath(cSide))
            shadePaint.shader = LinearGradient(mx, my, mx + nx * shade, my + ny * shade, 0x66000000, 0, Shader.TileMode.CLAMP)
            c.drawPaint(shadePaint); c.restore()
        }
        if (pSide.size >= 6) {
            c.save(); c.clipPath(polyPath(pSide)); c.drawBitmap(top, 0f, 0f, null); c.restore()
        }
        if (cSide.size >= 6) {                       // the back of the page: blank paper with fold shading
            val flap = FloatArray(cSide.size)
            var i = 0
            while (i < cSide.size) {
                val k = 2f * (cSide[i] * nx + cSide[i + 1] * ny - d)
                flap[i] = cSide[i] - k * nx; flap[i + 1] = cSide[i + 1] - k * ny
                i += 2
            }
            val fp = polyPath(flap)
            flapStroke.strokeWidth = 12 * density; flapStroke.color = 0x14000000; c.drawPath(fp, flapStroke)
            flapStroke.strokeWidth = 6 * density; flapStroke.color = 0x22000000; c.drawPath(fp, flapStroke)
            c.save(); c.clipPath(fp)
            c.drawColor(bg)
            shadePaint.shader = LinearGradient(mx, my, mx - nx * 64 * density, my - ny * 64 * density, 0x66000000, 0, Shader.TileMode.CLAMP)
            c.drawPaint(shadePaint)
            c.restore()
        }
        shadePaint.shader = null
    }

    private fun polyPath(p: FloatArray): Path {
        val path = Path()
        path.moveTo(p[0], p[1])
        var i = 2
        while (i < p.size) { path.lineTo(p[i], p[i + 1]); i += 2 }
        path.close()
        return path
    }

    /** Clips polygon [poly] (x,y pairs) to one side of the line n.p = d. */
    private fun clipPoly(poly: FloatArray, nx: Float, ny: Float, d: Float, positive: Boolean): FloatArray {
        val out = ArrayList<Float>()
        val n = poly.size / 2
        for (i in 0 until n) {
            val ax = poly[i * 2]; val ay = poly[i * 2 + 1]
            val bx = poly[((i + 1) % n) * 2]; val by = poly[((i + 1) % n) * 2 + 1]
            val sa = ax * nx + ay * ny - d; val sb = bx * nx + by * ny - d
            val ia = if (positive) sa >= 0 else sa <= 0
            val ib = if (positive) sb >= 0 else sb <= 0
            if (ia) { out.add(ax); out.add(ay) }
            if (ia != ib) { val t = sa / (sa - sb); out.add(ax + (bx - ax) * t); out.add(ay + (by - ay) * t) }
        }
        return out.toFloatArray()
    }

    private fun scrollBy(direction: Int): Boolean {
        val max = maxScroll()
        if (direction > 0 && scrollPx >= max - 2) return false
        if (direction < 0 && scrollPx <= 0) return false
        val target = (scrollPx + direction * (contentHeight * 0.9f).toInt()).coerceIn(0, max)
        scroller.forceFinished(true)
        scroller.startScroll(0, scrollPx, 0, target - scrollPx, 250)
        postInvalidateOnAnimation()
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        relayout(if (pageStartLines.isEmpty()) pendingOffset else currentOffset)
    }

    override fun onDraw(canvas: Canvas) {
        if (bmpCur != null) drawTurn(canvas) else drawPage(canvas, true)
    }

    private fun drawPage(canvas: Canvas, withSelection: Boolean) {
        canvas.drawColor(bg)
        val l = layout ?: return
        if (pageStartLines.isEmpty() || l.lineCount == 0) return
        val top = topY()
        val bottom = if (scrollMode) top + contentHeight else l.getLineBottom(lastLine())
        canvas.save()
        val originX = (insetL + margin).toFloat()
        val originY = (insetT + vMargin).toFloat()
        canvas.translate(originX, originY - top)
        canvas.clipRect(0f, top.toFloat(), contentWidth.toFloat(), bottom.toFloat())
        if (withSelection && selStart in 0 until selEnd) {
            l.getSelectionPath(selStart, selEnd, selPath)
            canvas.drawPath(selPath, selPaint)
        }
        l.draw(canvas)
        canvas.restore()
        if (withSelection && selStart in 0 until selEnd) {
            val a = handlePoint(selStart, false)
            val b = handlePoint(selEnd, true)
            canvas.drawCircle(a[0], a[1] + handleRadius, handleRadius, handlePaint)
            canvas.drawCircle(b[0], b[1] + handleRadius, handleRadius, handlePaint)
        }

        val y = height - insetB - 6 * density
        val x0 = (insetL + margin).toFloat()
        val x1 = (width - insetR - margin).toFloat()
        val count = if (scrollMode) "${(scrollPx * 100L / maxScroll().coerceAtLeast(1)).toInt()}%" else "${page + 1} / $pageCount"
        footerPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(count, x1, y, footerPaint)
        val room = (x1 - x0) - footerPaint.measureText(count) - 16 * density
        val title = footerProvider?.invoke(if (pageStartLines.isEmpty() || scrollMode) currentOffset else l.getLineStart(firstLine())) ?: footerText
        if (title.isNotEmpty() && room > 40 * density) {
            footerPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(TextUtils.ellipsize(title, footerPaint, room, TextUtils.TruncateAt.END).toString(), x0, y, footerPaint)
        }
    }

    private var downX = 0f
    private var downY = 0f

    private fun nearHandle(x: Float, y: Float): Int {
        if (selStart !in 0 until selEnd) return 0
        val reach = 32 * density
        val a = handlePoint(selStart, false)
        val b = handlePoint(selEnd, true)
        val da = Math.hypot((x - a[0]).toDouble(), (y - a[1] - handleRadius).toDouble())
        val db = Math.hypot((x - b[0]).toDouble(), (y - b[1] - handleRadius).toDouble())
        return if (db <= reach && db <= da) 2 else if (da <= reach) 1 else 0
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (turnRunning) return true
                downX = e.x; downY = e.y; longPressFired = false
                scrolling = false
                if (scrollMode) {
                    scroller.forceFinished(true)
                    velocity?.recycle()
                    velocity = android.view.VelocityTracker.obtain().also { it.addMovement(e) }
                }
                dragging = nearHandle(e.x, e.y)
                dragMinEnd = -1
                if (dragging == 0) handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging != 0) {
                    // Aim a little above the finger so the handle stays visible.
                    val o = offsetAt(e.x, e.y - handleRadius * 2)
                    if (dragging == 2) selEnd = maxOf(o, selStart + 1, dragMinEnd)
                    else selStart = minOf(o, selEnd - 1)
                    onSelection?.invoke(selectedText())
                    invalidate()
                } else if (scrollMode && !longPressFired) {
                    velocity?.addMovement(e)
                    if (!scrolling && abs(e.y - downY) > slop && abs(e.y - downY) > abs(e.x - downX)) {
                        scrolling = true
                        handler.removeCallbacks(longPress)
                        clearSelection()
                        lastY = e.y
                    }
                    if (scrolling) {
                        scrollPx = (scrollPx + (lastY - e.y).toInt()).coerceIn(0, maxScroll())
                        lastY = e.y
                        invalidate()
                    }
                } else if (turnDragMove(e)) {
                    // a slide or curl is following the finger
                } else if (abs(e.x - downX) > slop || abs(e.y - downY) > slop) {
                    handler.removeCallbacks(longPress)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress); dragging = 0; scrolling = false
                if (dragTurn) cancelTurn()
                velocity?.recycle(); velocity = null
                return true
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                if (dragTurn) { finishTurnDrag(); return true }
                val wasDragging = dragging != 0
                dragging = 0
                if (scrolling) {
                    velocity?.let {
                        it.addMovement(e)
                        it.computeCurrentVelocity(1000)
                        scroller.fling(0, scrollPx, 0, -it.yVelocity.toInt(), 0, 0, 0, maxScroll())
                        it.recycle()
                    }
                    velocity = null
                    scrolling = false
                    postInvalidateOnAnimation()
                    return true
                }
                velocity?.recycle(); velocity = null
                if (longPressFired || wasDragging) return true
                if (hasSelection) { clearSelection(); return true }   // a tap just dismisses the selection
                val dx = e.x - downX
                val dy = e.y - downY
                // A tap on a link follows the link, before any page-turn zone gets a say.
                if (abs(dx) < 20 * density && abs(dy) < 20 * density) {
                    val href = linkAt(e.x, e.y)
                    if (href != null) { onLink?.invoke(href); return true }
                }
                if (!scrollMode && abs(dx) > 70 * density && abs(dx) > abs(dy) * 1.5f) {
                    onZone?.invoke(if ((dx < 0) != rtl) Zone.NEXT else Zone.PREV)
                } else if (abs(dx) < 20 * density && abs(dy) < 20 * density) {
                    val f = e.x / width
                    onZone?.invoke(if (f < 0.3f) (if (rtl) Zone.NEXT else Zone.PREV) else if (f > 0.7f) (if (rtl) Zone.PREV else Zone.NEXT) else Zone.MENU)
                }
                return true
            }
        }
        return true
    }
}
