package com.djcohen.offlinereader

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/** The audiobook player: a big cover, progress, large transport buttons, and a row of actions underneath. */
class AudioPlayerActivity : Activity() {
    private lateinit var lib: AudioLibrary
    private var book: Audiobook? = null
    private var service: AudioService? = null
    private val ui = Handler(Looper.getMainLooper())
    private var dragging = false
    private var chapters: List<Chapter> = emptyList()
    /** Opened from the library: start playing as soon as the player is ready (once). */
    private var autoplayPending = false

    private lateinit var part: TextView
    private lateinit var seek: SeekBar
    private lateinit var elapsed: TextView
    private lateinit var left: TextView
    private lateinit var playButton: ImageView
    private lateinit var backLabel: TextView
    private lateinit var forwardLabel: TextView
    private lateinit var prevButton: View
    private lateinit var nextButton: View
    private lateinit var speedText: TextView
    private lateinit var sleepLabel: TextView
    private lateinit var chaptersAction: View
    private lateinit var partsAction: View

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply { setColor(color); cornerRadius = radiusDp * resources.displayMetrics.density }
    private fun circle(color: Int) = GradientDrawable().apply { setColor(color); shape = GradientDrawable.OVAL }
    private fun pressable(normal: android.graphics.drawable.Drawable, pressed: android.graphics.drawable.Drawable) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), pressed)
        addState(intArrayOf(), normal)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as AudioService.LocalBinder).service
            service = s
            s.onChange = { refresh() }
            book?.let { s.open(it) }
            if (autoplayPending) {
                autoplayPending = false
                askForNotificationPermission()
                s.play()
            }
            refresh()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lib = AudioLibrary(this)
        Library(this).audioTab = true  // going back from an audiobook always lands on the Audiobooks tab
        book = intent.getStringExtra(EXTRA_ID)?.let { lib.book(it) }
        val b = book
        if (b == null) { finish(); return }
        autoplayPending = savedInstanceState == null && intent.getBooleanExtra(EXTRA_AUTOPLAY, false)
        chapters = lib.cachedChapters(b.id).orEmpty()
        build()
        if (lib.cachedChapters(b.id) == null) {
            // First time here: look for chapters inside the file, in the background.
            Thread {
                val found = lib.loadChapters(b)
                ui.post { chapters = found; chaptersAction.visibility = if (found.isNotEmpty()) View.VISIBLE else View.GONE; refresh() }
            }.start()
        }
    }

    private fun build() {
        val b = book ?: return
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(12))
        }

        column.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_arrow_back); setColorFilter(PAGE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = pressable(circle(Color.TRANSPARENT), circle(0x33FFFFFF))
            contentDescription = "Back to the library"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(52), dp(52)))

        // The cover takes whatever height is left, so nothing floats in empty space.
        val coverBox = FrameLayout(this)
        val cover = CoverView(this)
        val picture = lib.loadCover(b)
        cover.bind(b.title, "audio", picture)
        val aspect = picture?.let { (it.width.toFloat() / it.height).coerceIn(0.6f, 1f) } ?: 0.68f
        coverBox.addView(cover, FrameLayout.LayoutParams(dp(100), dp(100), Gravity.CENTER))
        coverBox.addOnLayoutChangeListener { _, l, t, r, bt, _, _, _, _ ->
            val w = r - l; val h = bt - t
            if (w <= 0 || h <= 0) return@addOnLayoutChangeListener
            val ch = minOf(h, (w / aspect).toInt())
            val cw = (ch * aspect).toInt()
            val lp = cover.layoutParams as FrameLayout.LayoutParams
            if (lp.width != cw || lp.height != ch) { lp.width = cw; lp.height = ch; cover.layoutParams = lp }
        }
        column.addView(coverBox, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(4); bottomMargin = dp(12) })

        column.addView(TextView(this).apply {
            text = b.title; textSize = 22f; setTextColor(PAGE); gravity = Gravity.CENTER; maxLines = 2
            ellipsize = TextUtils.TruncateAt.END; typeface = Typeface.create("serif", Typeface.BOLD)
        }, LinearLayout.LayoutParams(-1, -2))
        part = TextView(this).apply { textSize = 15f; setTextColor(PAGE_DIM); gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        column.addView(part, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        seek = SeekBar(this).apply {
            progressTintList = ColorStateList.valueOf(LEAF)
            progressBackgroundTintList = ColorStateList.valueOf(0x44FFF8E7)
            thumbTintList = ColorStateList.valueOf(LEAF)
            max = b.totalMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) showTimes(progress.toLong()) }
                override fun onStartTrackingTouch(s: SeekBar?) { dragging = true }
                override fun onStopTrackingTouch(s: SeekBar?) { dragging = false; service?.seekOverall(progress.toLong()) }
            })
        }
        column.addView(seek, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(10) })
        val times = LinearLayout(this)
        elapsed = TextView(this).apply { textSize = 14f; setTextColor(PAGE_DIM) }
        left = TextView(this).apply { textSize = 14f; setTextColor(PAGE_DIM); gravity = Gravity.END }
        times.addView(elapsed, LinearLayout.LayoutParams(0, -2, 1f))
        times.addView(left, LinearLayout.LayoutParams(0, -2, 1.6f))
        column.addView(times, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(6); marginEnd = dp(6) })

        // Transport: big, evenly spread buttons.
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        fun glyph(res: Int, desc: String, onClick: () -> Unit) = ImageView(this).apply {
            setImageResource(res); setColorFilter(PAGE); contentDescription = desc
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = pressable(circle(Color.TRANSPARENT), circle(0x33FFFFFF))
            setOnClickListener { onClick() }
        }
        fun skipButton(res: Int, desc: String, onClick: () -> Unit): Pair<FrameLayout, TextView> {
            val label = TextView(this).apply { textSize = 13f; setTextColor(PAGE); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD }
            val frame = FrameLayout(this).apply {
                addView(glyph(res, desc, onClick), FrameLayout.LayoutParams(-1, -1))
                addView(label, FrameLayout.LayoutParams(-1, -1).apply { topMargin = dp(4) })
            }
            return frame to label
        }
        val (backFrame, bl) = skipButton(R.drawable.ic_replay, "Skip back") { service?.skip(-lib.skipBackSec * 1000L) }
        val (fwdFrame, fl) = skipButton(R.drawable.ic_forward, "Skip forward") { service?.skip(lib.skipForwardSec * 1000L) }
        backLabel = bl; forwardLabel = fl
        playButton = ImageView(this).apply {
            setImageResource(R.drawable.ic_play); setColorFilter(FOREST)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            background = pressable(circle(LEAF), circle(LEAF_PRESSED))
            contentDescription = "Play or pause"
            setOnClickListener { togglePlay() }
        }
        // Previous / next chapter (or part). Hidden, but still taking up room so play stays centred, when there is neither.
        prevButton = glyph(R.drawable.ic_prev, "Previous chapter") { service?.previousTrack() }
        nextButton = glyph(R.drawable.ic_next, "Next chapter") { service?.nextTrack() }
        controls.addView(prevButton, LinearLayout.LayoutParams(0, dp(64), 1f))
        controls.addView(backFrame, LinearLayout.LayoutParams(0, dp(76), 1.25f))
        controls.addView(playButton, LinearLayout.LayoutParams(dp(92), dp(92)).apply { marginStart = dp(4); marginEnd = dp(4) })
        controls.addView(fwdFrame, LinearLayout.LayoutParams(0, dp(76), 1.25f))
        controls.addView(nextButton, LinearLayout.LayoutParams(0, dp(64), 1f))
        column.addView(controls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        // Actions: a row of big icon buttons with a label under each.
        val actions = LinearLayout(this)
        fun action(label: String, onClick: () -> Unit, iconView: View): Pair<LinearLayout, TextView> {
            val text = TextView(this).apply { this.text = label; textSize = 12f; setTextColor(PAGE_DIM); gravity = Gravity.CENTER; maxLines = 1 }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                background = pressable(rounded(Color.TRANSPARENT, 14), rounded(0x33FFF8E7, 14))
                setPadding(0, dp(8), 0, dp(6))
                addView(iconView, LinearLayout.LayoutParams(dp(34), dp(34)))
                addView(text, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
                setOnClickListener { onClick() }
            }
            return box to text
        }
        fun iconOf(res: Int) = ImageView(this).apply { setImageResource(res); setColorFilter(PAGE) }
        speedText = TextView(this).apply { textSize = 19f; setTextColor(PAGE); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD }
        val speedAction = action("Speed", { chooseSpeed() }, speedText).first
        val (sleepAction, sl) = action("Sleep", { chooseSleep() }, iconOf(R.drawable.ic_moon)); sleepLabel = sl
        val bookmarkAction = action("Bookmarks", { showBookmarks() }, iconOf(R.drawable.ic_bookmark)).first
        chaptersAction = action("Chapters", { showChapters() }, iconOf(R.drawable.ic_list)).first
        partsAction = action("Parts", { showTracks() }, iconOf(R.drawable.ic_parts)).first
        val settingsAction = action("Settings", { showSettings() }, iconOf(R.drawable.ic_settings)).first
        for (v in listOf(speedAction, sleepAction, bookmarkAction, chaptersAction, partsAction, settingsAction)) {
            actions.addView(v, LinearLayout.LayoutParams(0, -2, 1f))
        }
        column.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        partsAction.visibility = if (b.tracks.size > 1) View.VISIBLE else View.GONE
        chaptersAction.visibility = if (chapters.isNotEmpty()) View.VISIBLE else View.GONE

        val scroll = ScrollView(this).apply {
            setBackgroundColor(FOREST); isFillViewport = true
            addView(column, ViewGroup.LayoutParams(-1, -1))
        }
        setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        refresh()
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, AudioService::class.java), connection, Context.BIND_AUTO_CREATE)
        ui.post(poll)
    }

    override fun onStop() {
        ui.removeCallbacks(poll)
        service?.onChange = null
        service = null
        try { unbindService(connection) } catch (_: Throwable) { }
        super.onStop()
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!dragging) refresh()
            ui.postDelayed(this, 500)
        }
    }

    private fun togglePlay() {
        val s = service ?: return
        if (!s.isPlaying) askForNotificationPermission()
        s.toggle()
    }

    /** Needed for the notification with the lock-screen buttons; playback works either way. */
    private fun askForNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    // --- display ----------------------------------------------------------------------------

    private fun refresh() {
        val b = book ?: return
        val s = service?.takeIf { it.book?.id == b.id }
        val speed = s?.speed ?: lib.speed(b.id)
        val overall = s?.overallMs() ?: lib.overallMs(b)
        val index = s?.trackIndex ?: lib.position(b.id).first
        playButton.setImageResource(if (s?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play)
        backLabel.text = lib.skipBackSec.toString()
        forwardLabel.text = lib.skipForwardSec.toString()
        // A single file with no chapters has nothing for these to jump to, so they disappear (like the chapter list).
        val sections = if (chapters.isNotEmpty() || b.tracks.size > 1) View.VISIBLE else View.INVISIBLE
        prevButton.visibility = sections
        nextButton.visibility = sections
        speedText.text = formatSpeed(speed) + "x"
        val chapter = chapters.indexOfLast { it.ms <= overall }
        part.text = when {
            chapters.isNotEmpty() && chapter >= 0 ->
                if (AudioLibrary.titlesAreNumbered(chapters)) chapters[chapter].title
                else "Chapter ${chapter + 1} of ${chapters.size}  ·  ${chapters[chapter].title}"
            b.tracks.size > 1 -> "Part ${index + 1} of ${b.tracks.size}  ·  ${b.tracks.getOrNull(index)?.name?.let { AudioLibrary.stripExt(it) } ?: ""}"
            else -> ""
        }
        part.visibility = if (part.text.isEmpty()) View.GONE else View.VISIBLE
        sleepLabel.text = s?.sleepLabel() ?: "Sleep"
        if (!dragging) seek.progress = overall.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        showTimes(overall)
    }

    private fun showTimes(overall: Long) {
        val b = book ?: return
        val speed = service?.speed ?: lib.speed(b.id)
        elapsed.text = AudioLibrary.clock(overall)
        val remaining = (b.totalMs - overall).coerceAtLeast(0)
        left.text = if (lib.adjustedRemaining && speed != 1f) "${AudioLibrary.short((remaining / speed).toLong())} left at ${formatSpeed(speed)}x"
        else "${AudioLibrary.short(remaining)} left"
    }

    private fun formatSpeed(v: Float): String = if (v == v.toInt().toFloat()) v.toInt().toString() else "%.2f".format(v).trimEnd('0').trimEnd('.')

    // --- building blocks for dialogs --------------------------------------------------------

    private fun chip(label: String, selected: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 16f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (selected) FOREST else PAGE)
        background = rounded(if (selected) LEAF else 0x22FFF8E7, 22)
        setOnClickListener { onClick() }
    }

    /** Chips laid out [columns] to a row. */
    private fun chipGrid(columns: Int, chips: List<View>): GridLayout {
        val grid = GridLayout(this).apply { columnCount = columns }
        for (c in chips) {
            grid.addView(c, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                width = 0; height = dp(46); setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        return grid
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text; textSize = 14f; setTextColor(PAGE_DIM); setPadding(dp(4), dp(14), 0, dp(4))
    }

    private fun roundButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 26f; gravity = Gravity.CENTER; setTextColor(FOREST); typeface = Typeface.DEFAULT_BOLD
        background = pressable(circle(LEAF), circle(LEAF_PRESSED))
        setOnClickListener { onClick() }
    }

    // --- dialogs ----------------------------------------------------------------------------

    private fun chooseSpeed() {
        val s = service ?: return
        var dialog: AlertDialog? = null
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), 0) }
        val value = TextView(this).apply { textSize = 34f; setTextColor(PAGE); gravity = Gravity.CENTER; typeface = Typeface.create("serif", Typeface.BOLD) }
        fun show() { value.text = formatSpeed(s.speed) + "x" }
        fun nudge(by: Float) { s.setSpeed(Math.round((s.speed + by) * 100) / 100f); show(); refresh() }
        show()
        val stepper = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        stepper.addView(roundButton("−") { nudge(-0.05f) }, LinearLayout.LayoutParams(dp(56), dp(56)))
        stepper.addView(value, LinearLayout.LayoutParams(0, -2, 1f))
        stepper.addView(roundButton("+") { nudge(0.05f) }, LinearLayout.LayoutParams(dp(56), dp(56)))
        form.addView(stepper, LinearLayout.LayoutParams(-1, -2))
        form.addView(heading("Quick choices"))
        form.addView(chipGrid(3, lib.speedPresets.map { p ->
            chip(formatSpeed(p) + "x", p == s.speed) { s.setSpeed(p); refresh(); dialog?.dismiss() }
        }))
        dialog = AlertDialog.Builder(this).setTitle("Playback speed").setView(form).setPositiveButton("Done", null).showLeaf()
    }

    private fun chooseSleep() {
        val s = service ?: return
        var dialog: AlertDialog? = null
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), 0) }
        form.addView(chipGrid(3, intArrayOf(10, 15, 30, 45, 60, 90).map { m ->
            chip("$m min", false) { s.sleepIn(m); refresh(); dialog?.dismiss() }
        }))
        val endLabel = if (chapters.isNotEmpty()) "End of this chapter" else if ((book?.tracks?.size ?: 1) > 1) "End of this part" else "End of the audiobook"
        form.addView(chipGrid(1, listOf(chip(endLabel, false) { s.sleepAtEndOfSection(); refresh(); dialog?.dismiss() })))
        if (s.sleepLabel() != null) form.addView(chipGrid(1, listOf(chip("Turn the timer off", false) { s.cancelSleep(); refresh(); dialog?.dismiss() })))
        dialog = AlertDialog.Builder(this).setTitle("Sleep timer").setView(form).setNegativeButton("Cancel", null).showLeaf()
    }

    private fun showChapters() {
        val b = book ?: return
        val now = service?.takeIf { it.book?.id == b.id }?.overallMs() ?: lib.overallMs(b)
        val current = chapters.indexOfLast { it.ms <= now }
        val numbered = AudioLibrary.titlesAreNumbered(chapters)
        val names = chapters.mapIndexed { i, c ->
            (if (i == current) "▶  " else "") + (if (numbered) "" else "${i + 1}. ") + "${c.title}   (${AudioLibrary.clock(c.ms)})"
        }
        val dialog = AlertDialog.Builder(this).setTitle("Chapters")
            .setItems(names.toTypedArray()) { _, which -> service?.seekOverall(chapters[which].ms) }
            .setNegativeButton("Close", null).showLeaf()
        if (current > 0) dialog.listView.setSelection((current - 2).coerceAtLeast(0))
    }

    private fun showTracks() {
        val b = book ?: return
        val now = service?.trackIndex ?: lib.position(b.id).first
        val names = b.tracks.mapIndexed { i, t -> (if (i == now) "▶  " else "") + "${i + 1}. " + AudioLibrary.stripExt(t.name) + "   (${AudioLibrary.short(t.durationMs)})" }
        AlertDialog.Builder(this).setTitle("Parts")
            .setItems(names.toTypedArray()) { _, which -> service?.seekTo(which, 0) }
            .setNegativeButton("Close", null).showLeaf()
    }

    private fun showBookmarks() {
        val b = book ?: return
        val marks = lib.bookmarks(b.id)
        val labels = listOf("＋  Bookmark this spot") + marks.map { m ->
            AudioLibrary.clock(b.offsetOf(m.track) + m.ms) + (if (m.note.isNotEmpty()) "   " + m.note else "")
        }
        val dialog = AlertDialog.Builder(this).setTitle("Bookmarks")
            .setItems(labels.toTypedArray(), null)
            .setNegativeButton("Close", null).showLeaf()
        dialog.listView.setOnItemClickListener { _, _, pos, _ ->
            dialog.dismiss()
            if (pos == 0) {
                // One tap saves it; a note can be added afterwards by holding the bookmark.
                val s = service ?: return@setOnItemClickListener
                lib.addBookmark(b.id, s.trackIndex, s.positionMs(), "")
                toast("Bookmarked at ${AudioLibrary.clock(s.overallMs())}")
            } else marks[pos - 1].let { service?.seekTo(it.track, it.ms) }
        }
        dialog.listView.setOnItemLongClickListener { _, _, pos, _ ->
            if (pos > 0) {
                dialog.dismiss()
                val m = marks[pos - 1]
                AlertDialog.Builder(this).setTitle("Bookmark")
                    .setItems(arrayOf("Add or change note", "Delete")) { _, which ->
                        if (which == 1) lib.removeBookmark(b.id, m)
                        else editNote(m.note) { note -> lib.removeBookmark(b.id, m); lib.addBookmark(b.id, m.track, m.ms, note) }
                    }
                    .setNegativeButton("Cancel", null).showLeaf()
            }
            true
        }
        if (marks.isNotEmpty()) toast("Tap to jump. Hold for a note or to delete.")
    }

    private fun editNote(initial: String, onDone: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial); setSelection(text.length); setSingleLine(); setTextColor(PAGE)
            backgroundTintList = ColorStateList.valueOf(LEAF)
        }
        AlertDialog.Builder(this).setTitle("Note")
            .setView(LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input, LinearLayout.LayoutParams(-1, -2)) })
            .setPositiveButton("Save") { _, _ -> onDone(input.text.toString().trim()) }
            .setNegativeButton("Cancel", null).showLeaf()
    }

    private fun showSettings() {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(0), dp(16), 0) }

        fun picker(options: List<Int>, unit: String, current: () -> Int, set: (Int) -> Unit): GridLayout {
            val grid = GridLayout(this).apply { columnCount = 3 }
            fun fill() {
                grid.removeAllViews()
                for (o in options) grid.addView(chip("$o $unit", o == current()) { set(o); fill(); refresh() }, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                    width = 0; height = dp(46); setMargins(dp(3), dp(3), dp(3), dp(3))
                })
            }
            fill()
            return grid
        }
        form.addView(heading("Skip back"))
        form.addView(picker(listOf(5, 10, 15, 30, 45, 60), "s", { lib.skipBackSec }) { lib.skipBackSec = it })
        form.addView(heading("Skip forward"))
        form.addView(picker(listOf(10, 15, 30, 45, 60, 90), "s", { lib.skipForwardSec }) { lib.skipForwardSec = it })

        form.addView(heading("Speed choices (tap to add or remove)"))
        val speedGrid = GridLayout(this).apply { columnCount = 4 }
        fun fillSpeeds() {
            speedGrid.removeAllViews()
            val on = lib.speedPresets.toSet()
            for (sp in ALL_SPEEDS) speedGrid.addView(chip(formatSpeed(sp) + "x", sp in on) {
                val next = if (sp in on) on - sp else on + sp
                if (next.isNotEmpty()) lib.speedPresets = next.sorted()
                fillSpeeds()
            }.apply { textSize = 14f }, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                width = 0; height = dp(44); setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        fillSpeeds()
        form.addView(speedGrid)

        form.addView(heading("Time left"))
        val adjusted = LinearLayout(this)
        fun fillAdjusted() {
            adjusted.removeAllViews()
            adjusted.addView(chip("Normal", !lib.adjustedRemaining) { lib.adjustedRemaining = false; fillAdjusted(); refresh() },
                LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            adjusted.addView(chip("At current speed", lib.adjustedRemaining) { lib.adjustedRemaining = true; fillAdjusted(); refresh() },
                LinearLayout.LayoutParams(0, dp(46), 1.6f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        }
        fillAdjusted()
        form.addView(adjusted)

        AlertDialog.Builder(this).setTitle("Audiobook settings")
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Done", null).showLeaf()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_ID = "audiobook_id"
        const val EXTRA_AUTOPLAY = "autoplay"
        private val ALL_SPEEDS = listOf(0.6f, 0.7f, 0.8f, 0.9f, 1f, 1.1f, 1.2f, 1.25f, 1.3f, 1.4f, 1.5f, 1.6f, 1.75f, 1.8f, 2f, 2.25f, 2.5f, 3f)
        private const val FOREST = 0xFF1E3A2A.toInt()
        private const val LEAF = 0xFF6CC070.toInt()
        private const val LEAF_PRESSED = 0xFF55A85A.toInt()
        private const val PAGE = 0xFFFFF8E7.toInt()
        private const val PAGE_DIM = 0xFFB9D1BF.toInt()
    }
}
