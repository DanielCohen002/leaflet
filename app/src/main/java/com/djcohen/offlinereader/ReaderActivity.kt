package com.djcohen.offlinereader

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class ReaderActivity : Activity() {
    private lateinit var library: Library
    private lateinit var prefs: Prefs
    private lateinit var entry: Entry
    private var book: Book? = null
    private lateinit var page: PageView
    private lateinit var topBar: TopBar
    private lateinit var bottomBar: LinearLayout
    private lateinit var selBar: LinearLayout
    private var selText: String? = null
    private var chapter = 0
    private var loading = true
    private var overlayVisible = false
    private lateinit var scrubber: SeekBar
    private lateinit var scrubLabel: TextView
    private var scrubbing = false
    private val SCRUB_MAX = 10000
    private lateinit var backChip: TextView
    private var backTarget: Pair<Int, Int>? = null      // (chapter, offset) to return to after following a link
    private var backToken = 0
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var barTop = 0
    private var barBottom = 0

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = Library(this)
        val id = intent.getStringExtra(EXTRA_ID)
        val found = library.entries().firstOrNull { it.id == id }
        if (found == null) { finish(); return }
        entry = found
        prefs = Prefs(this, entry.ext)
        library.audioTab = false   // going back from a book lands on the Books shelf

        val root = FrameLayout(this)
        page = PageView(this).apply {
            onLink = { href -> openLink(href) }
            onZone = { z ->
                when (z) {
                    PageView.Zone.NEXT -> turn(true)
                    PageView.Zone.PREV -> turn(false)
                    PageView.Zone.MENU -> setOverlay(!overlayVisible)
                }
            }
        }
        root.addView(page, FrameLayout.LayoutParams(-1, -1))

        topBar = TopBar(this) { finish() }.apply { visibility = View.GONE }
        topBar.setBookmarkAction { showBookmarks() }
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xE6202020.toInt()); visibility = View.GONE
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        // Scrubber: drag to jump around the book. The label shows the chapter name and how far through the book you would land.
        scrubLabel = TextView(this).apply { setTextColor(0xFFFFFFFF.toInt()); textSize = 14f; setPadding(dp(8), 0, dp(8), 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        scrubber = SeekBar(this).apply {
            max = 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) updateScrubLabel(p) }
                override fun onStartTrackingTouch(s: SeekBar?) { scrubbing = true }
                override fun onStopTrackingTouch(s: SeekBar?) { scrubbing = false; scrubTo(progress) }
            })
        }
        bottomBar.addView(scrubLabel, LinearLayout.LayoutParams(-1, -2))
        bottomBar.addView(scrubber, LinearLayout.LayoutParams(-1, -2))
        val buttonRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottomBar.addView(buttonRow, LinearLayout.LayoutParams(-1, -2))
        fun barButton(label: String, action: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; setOnClickListener { action() }
            buttonRow.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
        }
        barButton("‹") { skipChapter(-1) }
        barButton("Chapters") { showChapters() }
        barButton("Search") { showSearch() }
        barButton("Aa") { showSettings() }
        barButton("›") { skipChapter(1) }
        root.addView(bottomBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        backChip = TextView(this).apply {
            text = "\u21A9  Back to where you were"
            textSize = 14f; setTextColor(0xFF1E3A2A.toInt())
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding(dp(18), dp(11), dp(18), dp(11))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF6CC070.toInt()); cornerRadius = dp(24).toFloat()
            }
            visibility = View.GONE
            setOnClickListener { goBack() }
        }
        root.addView(backChip, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(72) })
        selBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xF0202020.toInt()); visibility = View.GONE
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        fun selButton(label: String, action: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; setOnClickListener { action() }
            selBar.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
        }
        selButton("Copy") { copySelection() }
        selButton("Look up") { lookUpSelection() }
        selButton("Done") { page.clearSelection() }
        root.addView(selBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        page.onSelection = { t ->
            selText = t
            if (t == null) selBar.visibility = View.GONE
            else {
                setOverlay(false)
                (selBar.layoutParams as FrameLayout.LayoutParams).gravity =
                    if (page.selectionY() > page.height / 2f) Gravity.TOP else Gravity.BOTTOM
                selBar.requestLayout()
                selBar.visibility = View.VISIBLE
            }
        }
        setContentView(root)

        root.setOnApplyWindowInsetsListener { _, insets ->
            val b = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            page.setInsets(b.left, b.top, b.right, b.bottom)
            barTop = b.top; barBottom = b.bottom
            selBar.setPadding(dp(8) + b.left, dp(8) + b.top, dp(8) + b.right, dp(8) + b.bottom)
            topBar.setPadding(dp(8) + b.left, dp(4) + b.top, dp(16) + b.right, dp(4))
            bottomBar.setPadding(dp(8) + b.left, dp(8), dp(8) + b.right, dp(8) + b.bottom)
            insets
        }

        applyStyle()
        hideSystemBars()

        io.execute {
            try {
                val b = BookLoader.open(library.file(entry), entry.ext)
                val (ch, off) = library.position(entry.id)
                ui.post {
                    book = b
                    topBar.text = entry.title
                    startLengths(b)
                    loadChapter(ch.coerceIn(0, b.chapterTitles.size - 1), off)
                }
            } catch (t: Throwable) {
                ui.post {
                    Toast.makeText(this, "Couldn't open book: ${t.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun applyStyle() {
        val t = Themes.all[prefs.theme.coerceIn(Themes.all.indices)]
        page.setStyle(Fonts.typeface(prefs.font), prefs.textSizeSp, prefs.spacing / 100f, t.fg, t.bg, prefs.indent / 10f, prefs.justify)
        page.setScrollMode(false)   // the scroll option was removed; any old saved choice is ignored
        page.turnStyle = prefs.turnStyle
        page.tapZones = prefs.tapZones
        window.decorView.setBackgroundColor(t.bg)
        if (prefs.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Light theme needs dark status icons if the bars are ever shown.
        window.insetsController?.setSystemBarsAppearance(
            if (prefs.theme >= 2) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
        )
    }

    private fun hideSystemBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun setOverlay(show: Boolean) {
        if (show == overlayVisible && (show == (topBar.visibility == View.VISIBLE))) return
        overlayVisible = show
        // The bars slide in from the top and bottom edges, and back out again.
        fun slide(bar: View, fromEdge: Float) {
            bar.animate().cancel()
            val away = if (bar.height > 0) fromEdge * bar.height else fromEdge * dp(90)
            if (show) {
                bar.visibility = View.VISIBLE
                bar.translationY = away
                bar.animate().translationY(0f).setDuration(160).start()
            } else if (bar.visibility == View.VISIBLE) {
                bar.animate().translationY(away).setDuration(140).withEndAction {
                    if (!overlayVisible) bar.visibility = View.GONE
                    bar.translationY = 0f
                }.start()
            }
        }
        if (show) syncScrubber()
        positionBack()
        slide(topBar, -1f)
        slide(bottomBar, 1f)
    }

    // --- bookmarks -------------------------------------------------------------------------

    private fun showBookmarks() {
        val b = book ?: return
        val list = library.bookmarks(entry.id)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val dialog = AlertDialog.Builder(this).setTitle("Bookmarks").setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Close", null).showLeaf()
        box.addView(Button(this).apply {
            text = "Add bookmark here"; isAllCaps = false
            setOnClickListener {
                val off = page.currentOffset
                val title = try { b.titleAt(chapter, off) } catch (t: Throwable) { b.chapterTitles.getOrElse(chapter) { "" } }
                val pct = (((0 until chapter).sumOf { lenOf(it) } + off) * 100 / totalLen(b.chapterTitles.size)).toInt()
                val snip = page.snippetAt(off).take(50)
                library.addBookmark(entry.id, Library.Bookmark(chapter, off, "$title · $pct%" + if (snip.isNotEmpty()) "\n$snip…" else "", System.currentTimeMillis()))
                dialog.dismiss()
                Toast.makeText(this@ReaderActivity, "Bookmark added", Toast.LENGTH_SHORT).show()
            }
        })
        if (list.isEmpty()) box.addView(TextView(this).apply { text = "No bookmarks yet."; alpha = 0.7f; setPadding(0, dp(12), 0, dp(4)) })
        list.forEachIndexed { i, bm ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
            row.addView(TextView(this).apply {
                text = bm.label; textSize = 14f
                setOnClickListener {
                    dialog.dismiss(); setOverlay(false)
                    val from = chapter to page.currentOffset
                    if (bm.chapter == chapter) page.jumpToOffset(bm.offset) else loadChapter(bm.chapter, bm.offset)
                    showBack(from)
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Button(this).apply {
                text = "✕"; isAllCaps = false; minWidth = 0; minimumWidth = 0
                setOnClickListener { library.removeBookmark(entry.id, i); dialog.dismiss(); showBookmarks() }
            })
            box.addView(row)
        }
    }

    // --- scrubber --------------------------------------------------------------------------

    /** Characters in each chapter file (-1 until known), worked out quietly in the background after the book opens. */
    private var lens = IntArray(0)

    private fun startLengths(b: Book) {
        val n = b.chapterTitles.size
        lens = IntArray(n) { -1 }
        fun step(c: Int) {
            if (c >= n || isFinishing || book !== b) return
            io.execute {
                val len = plainText.getOrPut(c) { try { b.load(c, 8, 8).toString() } catch (t: Throwable) { "" } }.length
                lens[c] = len
                // One chapter per task, so opening a chapter yourself never waits long in the queue.
                ui.post { step(c + 1) }
            }
        }
        ui.postDelayed({ step(0) }, 3000)
    }

    private fun lenOf(c: Int): Long {
        if (c == chapter && page.textLength > 1) return page.textLength.toLong()
        val l = lens.getOrElse(c) { -1 }
        if (l >= 0) return l.toLong()
        val known = lens.filter { it >= 0 }
        return if (known.isEmpty()) page.textLength.toLong().coerceAtLeast(1) else known.average().toLong().coerceAtLeast(1)
    }

    private fun totalLen(n: Int): Long = (0 until n).sumOf { lenOf(it) }.coerceAtLeast(1)

    private fun charsPerPage(): Double = (page.textLength.toDouble() / page.pageCount.coerceAtLeast(1)).coerceAtLeast(1.0)

    /** The scrubber runs smoothly through the whole book by character count, 10000 steps. */
    private fun syncScrubber() {
        val b = book ?: return
        val n = b.chapterTitles.size
        val before = (0 until chapter.coerceIn(0, n - 1)).sumOf { lenOf(it) }
        val pos = before + page.currentOffset
        scrubber.max = SCRUB_MAX
        scrubber.progress = (pos * SCRUB_MAX / totalLen(n)).toInt().coerceIn(0, SCRUB_MAX)
        updateScrubLabel(scrubber.progress)
    }

    /** Chapter file and offset in it for scrubber position [p]. */
    private fun locate(p: Int, n: Int): Pair<Int, Int> {
        var pos = p.toLong() * totalLen(n) / SCRUB_MAX
        for (c in 0 until n) {
            val len = lenOf(c)
            if (pos < len || c == n - 1) return c to pos.toInt().coerceIn(0, (len - 1).toInt().coerceAtLeast(0))
            pos -= len
        }
        return 0 to 0
    }

    private fun updateScrubLabel(p: Int) {
        val b = book ?: return
        val n = b.chapterTitles.size
        val (c, off) = locate(p, n)
        val total = totalLen(n)
        val cpp = charsPerPage()
        val pageNo = (p.toLong() * total / SCRUB_MAX / cpp).toLong() + 1
        val pages = (total / cpp).toLong().coerceAtLeast(1)
        val title = try { b.titleAt(c, off) } catch (t: Throwable) { b.chapterTitles[c] }
        scrubLabel.text = "Page ${pageNo.coerceAtMost(pages)} of about $pages  ·  ${p * 100 / SCRUB_MAX}%  ·  $title"
    }

    private fun scrubTo(p: Int) {
        val b = book ?: return
        if (loading) return
        val (c, off) = locate(p, b.chapterTitles.size)
        val from = chapter to page.currentOffset
        if (c == chapter) {
            if (kotlin.math.abs(off - page.currentOffset) < charsPerPage() / 2) return
            page.jumpToOffset(off)
        } else {
            loadChapter(c, off)
        }
        showBack(from)   // the "Back to where you were" button undoes the jump
    }

    // --- navigation ------------------------------------------------------------------------

    /** [offset] < 0 opens the chapter on its last page. */
    private fun loadChapter(index: Int, offset: Int, anchor: String? = null) {
        val b = book ?: return
        loading = true
        val w = page.contentWidth
        val h = (page.contentHeight * 0.9f).toInt()
        io.execute {
            val text = try {
                b.load(index, w, h)
            } catch (t: Throwable) {
                android.text.SpannableString("Couldn't load this section:\n${t.message}")
            }
            // A link to "#note3" lands on that spot, which is only known once the chapter has been built.
            val start = if (anchor != null) (b.anchorOffset(index, anchor) ?: offset) else offset
            ui.post {
                chapter = index
                page.footerProvider = { off -> b.titleAt(index, off) }
                page.setContent(text, start, b.chapterStarts(index))
                loading = false
                if (overlayVisible && !scrubbing) syncScrubber()
            }
        }
    }

    /** Follow a tapped link: jump within the book, remembering where we were so "Back" can return. */
    private fun openLink(href: String) {
        val b = book ?: return
        if (loading) return
        val target = b.resolveLink(chapter, href)
        when {
            target == null -> Toast.makeText(this, "Couldn't find where that link goes", Toast.LENGTH_SHORT).show()
            target.external -> Toast.makeText(this, "That link goes to a web page. Leaflet stays offline.", Toast.LENGTH_SHORT).show()
            else -> {
                val from = chapter to page.currentOffset
                if (target.chapter == chapter) {
                    val spot = target.anchor?.let { b.anchorOffset(chapter, it) } ?: 0
                    page.jumpToOffset(spot)
                } else {
                    loadChapter(target.chapter, 0, target.anchor)
                }
                showBack(from)
            }
        }
    }

    /** Keep the "Back to where you were" button clear of the menu bar (it is taller now that it holds the scrubber). */
    private fun positionBack() {
        if (!::backChip.isInitialized) return
        val lp = backChip.layoutParams as FrameLayout.LayoutParams
        val above = if (overlayVisible) (if (bottomBar.height > 0) bottomBar.height else dp(150)) + dp(12) else dp(72)
        if (lp.bottomMargin != above) { lp.bottomMargin = above; backChip.layoutParams = lp }
    }

    private fun showBack(from: Pair<Int, Int>) {
        backTarget = from
        positionBack()
        val token = ++backToken
        backChip.visibility = View.VISIBLE
        ui.postDelayed({ if (token == backToken) hideBack() }, 12000)   // fades away if you carry on reading
    }

    private fun hideBack() {
        backToken++
        backTarget = null
        if (::backChip.isInitialized) backChip.visibility = View.GONE
    }

    private fun goBack() {
        val (c, offset) = backTarget ?: return
        hideBack()
        if (c == chapter) page.jumpToOffset(offset) else loadChapter(c, offset)
    }

    private fun turn(forward: Boolean) {
        val b = book ?: return
        if (loading) return
        hideBack()
        if (overlayVisible) setOverlay(false)
        if (forward) {
            if (!page.nextPage()) {
                if (chapter + 1 < b.chapterTitles.size) loadChapter(chapter + 1, 0)
                else Toast.makeText(this, "End of book", Toast.LENGTH_SHORT).show()
            }
        } else {
            if (!page.prevPage() && chapter > 0) loadChapter(chapter - 1, -1)
        }
    }

    /** Index in the contents list of the entry the reader is in now. */
    private fun currentTocIndex(b: Book): Int {
        val toc = b.toc
        val here = page.currentOffset
        var best = 0
        for ((i, e) in toc.withIndex()) {
            if (e.chapter < chapter) best = i
            else if (e.chapter == chapter) {
                val at = if (e.anchor == null) 0 else b.anchorOffset(chapter, e.anchor) ?: continue
                if (at <= here) best = i
            }
        }
        return best
    }

    private fun skipChapter(delta: Int) {
        val b = book ?: return
        if (loading) return
        val toc = b.toc
        val e = toc.getOrNull(currentTocIndex(b) + delta) ?: return
        val from = chapter to page.currentOffset
        loadChapter(e.chapter, 0, e.anchor)
        showBack(from)
    }

    private fun showChapters() {
        val b = book ?: return
        AlertDialog.Builder(this)
            .setTitle("Chapters")
            .setSingleChoiceItems(b.toc.map { it.title }.toTypedArray(), currentTocIndex(b)) { d, which ->
                val e = b.toc[which]
                val from = chapter to page.currentOffset
                d.dismiss(); setOverlay(false); loadChapter(e.chapter, 0, e.anchor)
                showBack(from)
            }
            .setNegativeButton("Close", null)
            .showLeaf()
    }

    // --- search ----------------------------------------------------------------------------

    private class Hit(val chapter: Int, val offset: Int, val snippet: String)

    /** Plain text of each chapter, filled in as searches need it (offsets match the pages: pictures are one character). */
    private val plainText = HashMap<Int, String>()
    private var searching = 0

    private fun showSearch() {
        val b = book ?: return
        val input = android.widget.EditText(this).apply {
            hint = "Word or phrase"; setSingleLine(); setTextColor(0xFFFFF8E7.toInt()); setHintTextColor(0xFFB9D1BF.toInt())
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        val box = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input, -1, -2) }
        val dialog = AlertDialog.Builder(this).setTitle("Search this book").setView(box)
            .setPositiveButton("Search", null).setNegativeButton("Cancel", null).showLeaf()
        fun go() {
            val q = input.text.toString().trim()
            if (q.isEmpty()) return
            dialog.dismiss()
            runSearch(b, q)
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { go() }
        input.setOnEditorActionListener { _, _, _ -> go(); true }
        input.requestFocus()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    private fun runSearch(b: Book, query: String) {
        val token = ++searching
        val progress = AlertDialog.Builder(this).setMessage("Searching…").setNegativeButton("Cancel") { _, _ -> searching++ }.showLeaf()
        progress.setCanceledOnTouchOutside(false)
        val w = page.contentWidth
        io.execute {
            val hits = ArrayList<Hit>()
            val max = 300
            for (c in b.chapterTitles.indices) {
                if (token != searching) return@execute
                val text = plainText.getOrPut(c) {
                    try { b.load(c, 8, 8).toString() } catch (t: Throwable) { "" }
                }
                var from = 0
                while (hits.size < max) {
                    val i = text.indexOf(query, from, ignoreCase = true)
                    if (i < 0) break
                    val a = (i - 35).coerceAtLeast(0)
                    val e = (i + query.length + 55).coerceAtMost(text.length)
                    val snip = text.substring(a, e).replace(Regex("\\s+"), " ").trim()
                    hits += Hit(c, i, (if (a > 0) "… " else "") + snip + (if (e < text.length) " …" else ""))
                    from = i + query.length
                }
                if (hits.size >= max) break
            }
            ui.post {
                if (token != searching || isFinishing) return@post
                progress.dismiss()
                showHits(b, query, hits)
            }
        }
    }

    private fun showHits(b: Book, query: String, hits: List<Hit>) {
        if (hits.isEmpty()) {
            Toast.makeText(this, "No matches for “$query”", Toast.LENGTH_SHORT).show()
            return
        }
        val lines = hits.map { h -> "${b.chapterTitles[h.chapter]}\n${h.snippet}" }.toTypedArray()
        val title = if (hits.size >= 300) "300+ matches" else "${hits.size} match" + if (hits.size == 1) "" else "es"
        AlertDialog.Builder(this).setTitle(title)
            .setItems(lines) { _, which ->
                val h = hits[which]
                setOverlay(false)
                val from = chapter to page.currentOffset
                if (h.chapter == chapter) page.jumpToOffset(h.offset) else loadChapter(h.chapter, h.offset)
                showBack(from)
            }
            .setNegativeButton("Close", null).showLeaf()
    }

    // --- selection actions -----------------------------------------------------------------

    private fun copySelection() {
        val t = selText ?: return
        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
            .setPrimaryClip(android.content.ClipData.newPlainText("Selected text", t))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        page.clearSelection()
    }

    private val dictionary by lazy { Dictionary(applicationContext) }

    /** Offline WordNet lookup in a popup; falls back to any installed lookup app on request. */
    private fun lookUpSelection() {
        val t = selText ?: return
        io.execute {
            val result = try { dictionary.lookup(t) } catch (e: Throwable) { null }
            ui.post { if (!isFinishing) showDefinition(t, result) }
        }
    }

    private fun showDefinition(query: String, entries: List<DictEntry>?) {
        val posName = mapOf("n" to "noun", "v" to "verb", "a" to "adjective", "r" to "adverb")
        val sb = android.text.SpannableStringBuilder()
        fun span(s: Any, from: Int) = sb.setSpan(s, from, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (entries == null) sb.append("The dictionary couldn't be opened.")
        else if (entries.isEmpty()) sb.append("No entry for “").append(query.trim()).append("”.\n\nTry selecting a single word.")
        for (e in entries.orEmpty()) {
            if (sb.isNotEmpty()) sb.append("\n")
            var s = sb.length
            sb.append(e.word).append('\n')
            span(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), s)
            span(android.text.style.ForegroundColorSpan(0xFFFFF8E7.toInt()), s)
            span(android.text.style.RelativeSizeSpan(1.3f), s)
            for (pos in listOf("n", "v", "a", "r")) {
                val senses = e.senses.filter { it.pos == pos }
                if (senses.isEmpty()) continue
                s = sb.length
                sb.append(posName[pos]).append('\n')
                span(android.text.style.StyleSpan(android.graphics.Typeface.ITALIC), s)
                span(android.text.style.ForegroundColorSpan(0xFF6CC070.toInt()), s)
                for ((i, sense) in senses.take(8).withIndex()) {
                    sb.append("${i + 1}. ").append(sense.definition).append('\n')
                    sense.examples.firstOrNull()?.let { ex ->
                        s = sb.length
                        sb.append("    “").append(ex).append("”\n")
                        span(android.text.style.StyleSpan(android.graphics.Typeface.ITALIC), s)
                        span(android.text.style.ForegroundColorSpan(0xFFB9D1BF.toInt()), s)
                    }
                }
                if (senses.size > 8) sb.append("    … ${senses.size - 8} more\n")
            }
        }
        val text = TextView(this).apply {
            this.text = sb; textSize = 16f; setTextColor(0xFFFFF8E7.toInt())
            setPadding(dp(20), dp(8), dp(20), dp(8))
            setTextIsSelectable(true)
        }
        AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(text) })
            .setPositiveButton("Close", null)
            .showLeaf()
    }

    // --- settings --------------------------------------------------------------------------

    private fun indentText(tenthsOfEm: Int) = if (tenthsOfEm == 0) "none" else "${tenthsOfEm / 10f} em"

    private fun showSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(2), dp(14), dp(2)) }
        fun label(t: String) = TextView(this).apply { text = t; setPadding(0, dp(6), 0, dp(2)) }.also { box.addView(it) }

        label("Font")
        val spinner = Spinner(this)
        // Each font name is drawn in that font, so the list shows what you will get.
        spinner.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, Fonts.all.map { it.first }) {
            private fun styled(pos: Int, v: View): View = v.also { (it as? TextView)?.typeface = Fonts.typeface(pos) }
            override fun getView(pos: Int, convert: View?, parent: android.view.ViewGroup) = styled(pos, super.getView(pos, convert, parent))
            override fun getDropDownView(pos: Int, convert: View?, parent: android.view.ViewGroup) = styled(pos, super.getDropDownView(pos, convert, parent))
        }
        spinner.setSelection(prefs.font)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos != prefs.font) { prefs.font = pos; applyStyle() }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        box.addView(spinner)

        // The three text sliders only change the page when Apply is tapped.
        var pendingSize = prefs.textSizeSp
        var pendingSpacing = prefs.spacing
        var pendingIndent = prefs.indent
        var applyButton: Button? = null
        fun touched() { applyButton?.isEnabled = pendingSize != prefs.textSizeSp || pendingSpacing != prefs.spacing || pendingIndent != prefs.indent }
        val sizeLabel = label("Text size: ${prefs.textSizeSp}")
        box.addView(SeekBar(this).apply {
            max = 14; progress = (prefs.textSizeSp - 12).coerceIn(0, 14)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { sizeLabel.text = "Text size: ${p + 12}" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { pendingSize = progress + 12; touched() }
            })
        })

        val spaceLabel = label("Line spacing: ${prefs.spacing / 100f}")
        box.addView(SeekBar(this).apply {
            max = 12; progress = ((prefs.spacing - 100) / 5).coerceIn(0, 12)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { spaceLabel.text = "Line spacing: ${(100 + p * 5) / 100f}" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { pendingSpacing = 100 + progress * 5; touched() }
            })
        })

        val indentLabel = label("Paragraph indent: ${indentText(prefs.indent)}")
        box.addView(SeekBar(this).apply {
            max = 20; progress = prefs.indent.coerceIn(0, 20)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { indentLabel.text = "Paragraph indent: ${indentText(p)}" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { pendingIndent = progress; touched() }
            })
        })

        val applyBtn = Button(this).apply {
            text = "Apply"; isAllCaps = false; isEnabled = false
            setOnClickListener {
                prefs.textSizeSp = pendingSize; prefs.spacing = pendingSpacing; prefs.indent = pendingIndent
                applyStyle(); touched()
            }
        }
        applyButton = applyBtn
        box.addView(applyBtn, LinearLayout.LayoutParams(-1, -2))

        label("Colors")
        val swatchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val swatches = ArrayList<Pair<Int, TextView>>()
        fun paintSwatches() {
            for ((i, v) in swatches) {
                val t = Themes.all[i]
                v.background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(t.bg); cornerRadius = dp(10).toFloat()
                    setStroke(dp(if (i == prefs.theme) 3 else 1), if (i == prefs.theme) 0xFF3B82F6.toInt() else 0x55888888)
                }
            }
        }
        for (i in Themes.rowOrder) {
            val t = Themes.all[i]
            val v = TextView(this).apply {
                text = t.name; gravity = Gravity.CENTER; setTextColor(t.fg); textSize = 14f
                setPadding(0, dp(7), 0, dp(7))
                setOnClickListener { prefs.theme = i; applyStyle(); paintSwatches() }
            }
            swatches += i to v
            swatchRow.addView(v, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        paintSwatches()
        box.addView(swatchRow)

        val turnGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        label("Touch zones")
        box.addView(TapZones.picker(this, prefs.tapZones) { prefs.tapZones = it; applyStyle() })

        label("Page turn animation")
        listOf("None", "Slide", "Curl", "Flip").forEachIndexed { i, n ->
            turnGroup.addView(RadioButton(this).apply { text = n; id = 2000 + i })
        }
        turnGroup.check(2000 + prefs.turnStyle.coerceIn(0, 3))
        turnGroup.setOnCheckedChangeListener { _, checked -> prefs.turnStyle = checked - 2000; applyStyle() }
        box.addView(turnGroup)

        fun check(text: String, value: Boolean, set: (Boolean) -> Unit) = CheckBox(this).apply {
            this.text = text; isChecked = value; setOnCheckedChangeListener { _, c -> set(c) }
        }.also { box.addView(it) }
        val checks = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun check(text: String, value: Boolean, parent: LinearLayout, set: (Boolean) -> Unit) = CheckBox(this).apply {
            this.text = text; isChecked = value; setOnCheckedChangeListener { _, c -> set(c) }
        }.also { parent.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        check("Justify text", prefs.justify, checks) { prefs.justify = it; applyStyle() }
        check("Volume keys", prefs.volumeKeys, checks) { prefs.volumeKeys = it }
        box.addView(checks)
        val checks2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        check("Keep screen on", prefs.keepScreenOn, checks2) { prefs.keepScreenOn = it; applyStyle() }
        box.addView(checks2)

        AlertDialog.Builder(this).setTitle("Reading settings").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Done", null).showLeaf()
    }

    // --- hardware keys ---------------------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (prefs.volumeKeys && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            if (event.repeatCount == 0) {
                val next = keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                turn(next)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (prefs.volumeKeys && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) return true
        return super.onKeyUp(keyCode, event)
    }

    // --- lifecycle -------------------------------------------------------------------------

    private fun savePosition() {
        if (book != null && !loading) library.savePosition(entry.id, chapter, page.currentOffset, book?.chapterTitles?.getOrNull(chapter))
    }

    override fun onPause() {
        savePosition()
        super.onPause()
    }

    override fun onDestroy() {
        book?.close()
        io.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ID = "book_id"
    }
}
