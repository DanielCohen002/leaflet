package com.djcohen.offlinereader

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.util.LruCache
import android.widget.GridView
import android.net.Uri
import android.provider.DocumentsContract
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.text.TextUtils
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.widget.ImageView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class LibraryActivity : Activity() {
    private lateinit var library: Library
    private lateinit var list: ListView
    private lateinit var empty: View
    private lateinit var grid: GridView
    private lateinit var toggle: ImageView
    private var gridCols = 3
    private var gridCellW = 0
    private val covers = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var entries: List<Entry> = emptyList()
    private lateinit var audioLib: AudioLibrary
    private var audioBooks: List<Audiobook> = emptyList()
    private lateinit var audioList: ListView
    private lateinit var audioGrid: GridView
    private lateinit var audioEmpty: View
    private lateinit var tabBooks: TextView
    private lateinit var tabAudio: TextView
    private lateinit var addButton: Button

    private val adapter = object : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val e = entries[position]
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@LibraryActivity).apply {
                // A cream "page" card with a leaf-green tab on the left.
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), rounded(PAGE_PRESSED, 18))
                    addState(intArrayOf(), rounded(PAGE, 18))
                }
                setPadding(dp(10), dp(10), dp(16), dp(10))
                addView(CoverView(context), LinearLayout.LayoutParams(dp(52), dp(78)).apply { marginEnd = dp(14) })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context).apply {
                        textSize = 17f; maxLines = 2; setTextColor(INK)
                        typeface = Typeface.create("serif", Typeface.BOLD)
                    })
                    addView(TextView(context).apply {
                        textSize = 13f; setTextColor(MUTED); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            (row.getChildAt(0) as CoverView).bind(e.title, e.ext, coverFor(e))
            val col = row.getChildAt(1) as LinearLayout
            (col.getChildAt(0) as TextView).text = e.title
            val (chapter, _) = library.position(e.id)
            val (_, offset) = library.position(e.id)
            val resume = when {
                ImageBooks.isImageFormat(e.ext) -> if (offset > 0) "  ·  continue from page ${offset + 1}" else ""
                chapter > 0 -> "  ·  continue from ${library.positionTitle(e.id) ?: "section ${chapter + 1}"}"
                else -> ""
            }
            (col.getChildAt(1) as TextView).text = e.ext.uppercase() + resume
            return row
        }
    }

    /** Grid of covers: a cover picture (or a leaf-style placeholder) with the title underneath. */
    private val gridAdapter = object : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val e = entries[position]
            val cell = (convertView as? LinearLayout) ?: LinearLayout(this@LibraryActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(CoverView(context), LinearLayout.LayoutParams(gridCellW, (gridCellW * 1.5f).toInt()))
                addView(TextView(context).apply {
                    textSize = 12.5f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setTextColor(PAGE)
                    typeface = Typeface.create("serif", Typeface.NORMAL)
                    setPadding(dp(2), dp(7), dp(2), 0)
                })
            }
            (cell.getChildAt(0) as CoverView).bind(e.title, e.ext, coverFor(e))
            (cell.getChildAt(1) as TextView).text = e.title
            return cell
        }
    }

    /** One line of the audiobook shelf: the way up, a folder, or an audiobook. */
    private sealed class AudioRow {
        object Up : AudioRow()
        class Folder(val name: String, val count: Int) : AudioRow()
        class Book(val book: Audiobook) : AudioRow()
    }

    private var audioPath = ""
    private var audioRows: List<AudioRow> = emptyList()

    private fun upLabel() = "Audiobooks" + audioPath.split('/').filter { it.isNotEmpty() }.joinToString("") { "  ›  $it" }

    /** The audiobook shelf: folders first (open them to look through), then the audiobooks in this folder. */
    private val audioAdapter = object : BaseAdapter() {
        override fun getCount() = audioRows.size
        override fun getItem(position: Int) = audioRows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 3
        override fun getItemViewType(position: Int) = when (audioRows[position]) { is AudioRow.Up -> 0; is AudioRow.Folder -> 1; is AudioRow.Book -> 2 }

        private fun card(context: android.content.Context) = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), rounded(PAGE_PRESSED, 18))
                addState(intArrayOf(), rounded(PAGE, 18))
            }
        }

        private fun texts(context: android.content.Context) = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                textSize = 17f; maxLines = 2; setTextColor(INK)
                typeface = Typeface.create("serif", Typeface.BOLD)
            })
            addView(TextView(context).apply {
                textSize = 13f; setTextColor(MUTED); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            })
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val ctx = this@LibraryActivity
            when (val row = audioRows[position]) {
                is AudioRow.Up -> {
                    val v = (convertView as? LinearLayout) ?: LinearLayout(ctx).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        background = StateListDrawable().apply {
                            addState(intArrayOf(android.R.attr.state_pressed), rounded(0x33FFF8E7, 18))
                            addState(intArrayOf(), rounded(0x1AFFF8E7, 18))
                        }
                        setPadding(dp(14), dp(12), dp(16), dp(12))
                        addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_arrow_back); setColorFilter(PAGE) }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(12) })
                        addView(TextView(ctx).apply {
                            textSize = 15f; setTextColor(PAGE); maxLines = 2; ellipsize = TextUtils.TruncateAt.START
                        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    }
                    (v.getChildAt(1) as TextView).text = upLabel()
                    return v
                }
                is AudioRow.Folder -> {
                    val v = (convertView as? LinearLayout) ?: card(ctx).apply {
                        setPadding(dp(10), dp(10), dp(12), dp(10))
                        addView(FrameLayout(ctx).apply {
                            background = rounded(FOREST, 14)
                            addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_folder); setColorFilter(LEAF) }, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
                        }, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(14) })
                        addView(texts(ctx), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_chevron); setColorFilter(MUTED) }, LinearLayout.LayoutParams(dp(28), dp(28)))
                    }
                    val col = v.getChildAt(1) as LinearLayout
                    (col.getChildAt(0) as TextView).text = row.name
                    (col.getChildAt(1) as TextView).text = "${row.count} audiobook${if (row.count == 1) "" else "s"}"
                    val on = ("dir:" + fullPath(row.name)) in selected
                    styleCard(v, on)
                    (v.getChildAt(2) as ImageView).apply { setImageResource(if (on) R.drawable.ic_check else R.drawable.ic_chevron); setColorFilter(if (on) FOREST else MUTED) }
                    return v
                }
                is AudioRow.Book -> {
                    val b = row.book
                    val v = (convertView as? LinearLayout) ?: card(ctx).apply {
                        setPadding(dp(10), dp(10), dp(16), dp(10))
                        addView(CoverView(ctx), LinearLayout.LayoutParams(dp(52), dp(78)).apply { marginEnd = dp(14) })
                        addView(texts(ctx), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_check); setColorFilter(FOREST); visibility = View.GONE }, LinearLayout.LayoutParams(dp(28), dp(28)))
                    }
                    val on = b.id in selected
                    styleCard(v, on)
                    v.getChildAt(2).visibility = if (on) View.VISIBLE else View.GONE
                    (v.getChildAt(0) as CoverView).bind(b.title, "audio", audioCover(b))
                    val col = v.getChildAt(1) as LinearLayout
                    (col.getChildAt(0) as TextView).text = b.title
                    val done = audioLib.overallMs(b)
                    val parts = if (b.tracks.size > 1) "${b.tracks.size} parts  ·  " else ""
                    (col.getChildAt(1) as TextView).text = parts + when {
                        done <= 0 -> AudioLibrary.short(b.totalMs)
                        b.totalMs - done < 5000 -> "Finished"
                        else -> "${AudioLibrary.short(b.totalMs - done)} left of ${AudioLibrary.short(b.totalMs)}"
                    }
                    return v
                }
            }
        }
    }

    // --- selecting several audiobooks (hold one to start) ------------------------------------

    private val selected = LinkedHashSet<String>()
    private lateinit var selectionBar: LinearLayout
    private lateinit var selectionCount: TextView
    private lateinit var renameButton: TextView

    private fun fullPath(name: String) = if (audioPath.isEmpty()) name else "$audioPath/$name"

    private fun rowKey(r: AudioRow): String? = when (r) {
        is AudioRow.Book -> r.book.id
        is AudioRow.Folder -> "dir:" + fullPath(r.name)
        else -> null
    }

    private fun toggleSelect(r: AudioRow) {
        val k = rowKey(r) ?: return
        if (!selected.add(k)) selected.remove(k)
        updateSelection()
    }

    private fun clearSelection() {
        if (selected.isEmpty()) return
        selected.clear()
        updateSelection()
    }

    private fun updateSelection() {
        selectionBar.visibility = if (selected.isNotEmpty() && library.audioTab) View.VISIBLE else View.GONE
        selectionCount.text = "${selected.size} selected"
        renameButton.visibility = if (selected.size == 1 && !selected.first().startsWith("dir:")) View.VISIBLE else View.GONE
        audioAdapter.notifyDataSetChanged()
        audioGridAdapter.notifyDataSetChanged()
    }

    private fun selectAllHere() {
        audioRows.forEach { r -> rowKey(r)?.let { selected.add(it) } }
        updateSelection()
    }

    /** The audiobooks the selection stands for: the ones picked, plus everything inside any folder picked. */
    private fun selectedBooks(): List<Audiobook> {
        val dirs = selected.filter { it.startsWith("dir:") }.map { it.removePrefix("dir:") }
        return audioLib.books().filter { b -> b.id in selected || dirs.any { b.folder == it || b.folder.startsWith("$it/") } }
    }

    private fun confirmRemoveSelected() {
        val list = selectedBooks()
        if (list.isEmpty()) { clearSelection(); return }
        val names = list.take(6).joinToString("\n") { "• " + it.title } + if (list.size > 6) "\n…and ${list.size - 6} more" else ""
        AlertDialog.Builder(this)
            .setTitle("Remove ${list.size} audiobook${if (list.size == 1) "" else "s"}?")
            .setMessage(names + "\n\nThe audio files on your phone are not touched. Your place and bookmarks are forgotten.")
            .setPositiveButton("Remove") { _, _ ->
                list.forEach { audioLib.remove(it); audioCovers.remove(it.id) }
                selected.clear()
                refresh()
                updateSelection()
            }
            .setNegativeButton("Cancel", null)
            .showLeaf()
    }

    private fun styleCard(v: View, on: Boolean) {
        v.background = if (on) rounded(SELECTED, 18) else StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), rounded(PAGE_PRESSED, 18))
            addState(intArrayOf(), rounded(PAGE, 18))
        }
    }

    private fun selectionButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 15f; setTextColor(PAGE); gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(14), 0, dp(14), 0)
        setOnClickListener { onClick() }
    }

    /** The audiobook shelf as a grid: square tiles for folders and audiobooks, with the title underneath. */
    private val audioGridAdapter = object : BaseAdapter() {
        override fun getCount() = audioRows.size
        override fun getItem(position: Int) = audioRows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 3
        override fun getItemViewType(position: Int) = when (audioRows[position]) { is AudioRow.Up -> 0; is AudioRow.Folder -> 1; is AudioRow.Book -> 2 }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val ctx = this@LibraryActivity
            val row = audioRows[position]
            val type = getItemViewType(position)
            val cell = (convertView as? LinearLayout) ?: LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val art = FrameLayout(ctx)
                if (type == 2) art.addView(CoverView(ctx), FrameLayout.LayoutParams(-1, -1))
                else {
                    art.background = rounded(if (type == 0) 0x33FFF8E7 else PAGE, 10)
                    art.addView(ImageView(ctx).apply {
                        setImageResource(if (type == 0) R.drawable.ic_arrow_back else R.drawable.ic_folder)
                        setColorFilter(if (type == 0) PAGE else 0xFF3E8E45.toInt())
                    }, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))
                }
                art.addView(View(ctx).apply { background = rounded(0x886CC070.toInt(), 10); visibility = View.GONE }, FrameLayout.LayoutParams(-1, -1))
                art.addView(ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_check); setColorFilter(FOREST)
                    background = GradientDrawable().apply { setColor(LEAF); shape = GradientDrawable.OVAL }
                    setPadding(dp(4), dp(4), dp(4), dp(4)); visibility = View.GONE
                }, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP or Gravity.END).apply { topMargin = dp(6); marginEnd = dp(6) })
                addView(art, LinearLayout.LayoutParams(gridCellW, gridCellW))
                addView(TextView(ctx).apply {
                    textSize = 12.5f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setTextColor(PAGE)
                    typeface = Typeface.create("serif", Typeface.NORMAL)
                    setPadding(dp(2), dp(7), dp(2), 0)
                })
            }
            val art = cell.getChildAt(0) as FrameLayout
            val label = cell.getChildAt(1) as TextView
            val key = rowKey(row)
            val on = key != null && key in selected
            art.getChildAt(1).visibility = if (on) View.VISIBLE else View.GONE
            art.getChildAt(2).visibility = if (on) View.VISIBLE else View.GONE
            when (row) {
                is AudioRow.Up -> label.text = "Back"
                is AudioRow.Folder -> label.text = row.name + "  ·  " + row.count
                is AudioRow.Book -> {
                    (art.getChildAt(0) as CoverView).bind(row.book.title, "audio", audioCover(row.book))
                    label.text = row.book.title
                }
            }
            return cell
        }
    }

    private fun audioToTop() { audioList.setSelection(0); audioGrid.setSelection(0) }

    private fun rebuildAudioRows() {
        var shelf = audioLib.shelf(audioPath)
        // The folder you were in is gone (everything in it was removed): step out.
        while (audioPath.isNotEmpty() && shelf.folders.isEmpty() && shelf.books.isEmpty()) {
            audioPath = audioPath.substringBeforeLast('/', "")
            shelf = audioLib.shelf(audioPath)
        }
        val rows = ArrayList<AudioRow>()
        if (audioPath.isNotEmpty()) rows += AudioRow.Up
        shelf.folders.forEach { rows += AudioRow.Folder(it.first, it.second) }
        shelf.books.forEach { rows += AudioRow.Book(it) }
        audioRows = rows
    }

    private fun openAudioRow(row: AudioRow) {
        when (row) {
            is AudioRow.Up -> { audioPath = audioPath.substringBeforeLast('/', ""); refresh(); audioToTop() }
            is AudioRow.Folder -> { audioPath = if (audioPath.isEmpty()) row.name else audioPath + "/" + row.name; refresh(); audioToTop() }
            is AudioRow.Book -> openAudiobook(row.book)
        }
    }

    // The system back button steps out of an audiobook folder before leaving the app.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (selected.isNotEmpty()) clearSelection()
        else if (library.audioTab && audioPath.isNotEmpty()) { audioPath = audioPath.substringBeforeLast('/', ""); refresh() }
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private val audioCovers = HashMap<String, Bitmap?>()
    private fun audioCover(b: Audiobook): Bitmap? = audioCovers.getOrPut(b.id) { audioLib.loadCover(b) }

    private fun coverFor(e: Entry): Bitmap? {
        covers.get(e.id)?.let { return it }
        val bmp = library.loadCover(e) ?: return null
        covers.put(e.id, bmp)
        return bmp
    }

    /** Show the list or the grid (or the welcome message if there are no books), and set the toggle's icon. */
    private fun applyMode() {
        val audioTabNow = library.audioTab
        val useGrid = if (audioTabNow) library.audioGridMode else library.gridMode
        toggle.setImageResource(if (useGrid) R.drawable.ic_view_list else R.drawable.ic_view_grid)
        toggle.contentDescription = if (useGrid) "Show as list" else "Show as grid of covers"
        val audio = library.audioTab
        val hasBooks = entries.isNotEmpty()
        val hasAudio = audioBooks.isNotEmpty()
        tabBooks.setTextColor(if (audio) PAGE_DIM else PAGE); tabBooks.background = if (audio) null else rounded(0x33FFF8E7, 18)
        tabAudio.setTextColor(if (audio) PAGE else PAGE_DIM); tabAudio.background = if (audio) rounded(0x33FFF8E7, 18) else null
        addButton.text = if (audio) "Add audio" else "Add books"
        empty.visibility = if (!audio && !hasBooks) View.VISIBLE else View.GONE
        list.visibility = if (!audio && hasBooks && !useGrid) View.VISIBLE else View.GONE
        grid.visibility = if (!audio && hasBooks && useGrid) View.VISIBLE else View.GONE
        audioGrid.visibility = if (audio && hasAudio && useGrid) View.VISIBLE else View.GONE
        audioEmpty.visibility = if (audio && !hasAudio) View.VISIBLE else View.GONE
        audioList.visibility = if (audio && hasAudio && !useGrid) View.VISIBLE else View.GONE
    }

    private var makingCovers = false

    /** Covers are made once per book, in the background, and cached on the phone. */
    private fun fillMissingCovers() {
        if (makingCovers) return
        if (entries.none { library.needsCover(it) }) return
        makingCovers = true
        io.execute {
            while (true) {
                val todo = library.entries().filter { library.needsCover(it) }
                if (todo.isEmpty()) break
                for (e in todo) {
                    library.makeCover(e)
                    ui.post { gridAdapter.notifyDataSetChanged(); adapter.notifyDataSetChanged() }
                }
            }
            ui.post { makingCovers = false }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = radiusDp * resources.displayMetrics.density
    }

    /** The Leaflet mark (half leaf, half page), drawn on the forest background. */
    private fun logo() = ImageView(this).apply {
        setImageResource(R.drawable.ic_launcher_foreground)
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = Library(this)
        audioLib = AudioLibrary(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(FOREST) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(16), dp(8))
        }
        header.addView(logo(), LinearLayout.LayoutParams(dp(64), dp(64)))
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { null }
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = "Leaflet"; textSize = 30f; setTextColor(PAGE)
                typeface = Typeface.create("serif", Typeface.BOLD)
            })
            if (version != null) addView(TextView(context).apply {
                text = "version $version · GPL-3.0"; textSize = 12f; setTextColor(0xFFB9D1BF.toInt())
            })
            addView(TextView(context).apply {
                text = "Check for updates"; textSize = 12f; setTextColor(LEAF)
                paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                setPadding(0, dp(4), dp(16), dp(4))
                setOnClickListener { UpdateChecker.check(this@LibraryActivity) }  // UPDATE-CHECK
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        toggle = ImageView(this).apply {
            setColorFilter(PAGE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(11), dp(11), dp(11), dp(11))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), rounded(0x33FFFFFF, 22))
                addState(intArrayOf(), rounded(0x00000000, 22))
            }
            setOnClickListener {
                if (library.audioTab) library.audioGridMode = !library.audioGridMode else library.gridMode = !library.gridMode
                applyMode()
            }
        }
        header.addView(toggle, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(8) })
        addButton = Button(this).apply {
            text = "Add books"; isAllCaps = false; textSize = 15f; setTextColor(FOREST)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), rounded(LEAF_PRESSED, 24))
                addState(intArrayOf(), rounded(LEAF, 24))
            }
            stateListAnimator = null
            setPadding(dp(20), 0, dp(20), 0)
            setOnClickListener { if (library.audioTab) showAudioAddMenu() else showAddMenu() }
        }
        header.addView(addButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
        root.addView(header)

        // Two shelves: Books and Audiobooks.
        fun tab(label: String, audio: Boolean) = TextView(this).apply {
            text = label; textSize = 15f; gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setOnClickListener { selected.clear(); library.audioTab = audio; library.shelfAudio = audio; applyMode(); updateSelection() }
        }
        tabBooks = tab("Books", false); tabAudio = tab("Audiobooks", true)
        val tabs = LinearLayout(this).apply { setPadding(dp(16), 0, dp(16), dp(4)) }
        tabs.addView(tabBooks, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(6) })
        tabs.addView(tabAudio, LinearLayout.LayoutParams(0, dp(36), 1f))
        root.addView(tabs)
        selectionBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            background = rounded(0x33FFF8E7, 18)
            visibility = View.GONE
        }
        selectionBar.addView(selectionButton("✕") { clearSelection() })
        selectionCount = TextView(this).apply { textSize = 15f; setTextColor(PAGE) }
        selectionBar.addView(selectionCount, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        renameButton = selectionButton("Rename") {
            val one = selectedBooks().firstOrNull()
            clearSelection()
            one?.let { showAudioOptions(it) }
        }
        selectionBar.addView(renameButton)
        selectionBar.addView(selectionButton("All") { selectAllHere() })
        selectionBar.addView(selectionButton("Remove") { confirmRemoveSelected() }.apply { setTextColor(0xFFFFB4A8.toInt()) })
        root.addView(selectionBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { marginStart = dp(16); marginEnd = dp(16); bottomMargin = dp(4) })

        list = ListView(this).apply {
            divider = ColorDrawable(Color.TRANSPARENT)
            dividerHeight = dp(10)
            selector = ColorDrawable(Color.TRANSPARENT)
            setPadding(dp(16), dp(8), dp(16), dp(16))
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(64))
            addView(logo(), LinearLayout.LayoutParams(dp(150), dp(150)))
            addView(TextView(context).apply {
                text = "No books yet.\nTap “Add books” and choose EPUB, MOBI, FB2, PDF, CBZ comics, TXT, HTML or Markdown files from your phone.\n\nLeaflet only goes online if you tap Check for updates."  // UPDATE-CHECK
                textSize = 15f; gravity = Gravity.CENTER; setTextColor(PAGE); alpha = 0.8f
            })
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> openBook(entries[pos]) }
        list.setOnItemLongClickListener { _, _, pos, _ -> showOptions(entries[pos]); true }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Grid of covers: as many columns as fit (at least two), each cell a 2:3 cover with the title underneath.
        val usable = resources.displayMetrics.widthPixels - dp(32)
        gridCols = maxOf(2, usable / dp(116))
        gridCellW = (usable - (gridCols - 1) * dp(12)) / gridCols
        grid = GridView(this).apply {
            numColumns = gridCols
            columnWidth = gridCellW
            horizontalSpacing = dp(12)
            verticalSpacing = dp(18)
            selector = ColorDrawable(Color.TRANSPARENT)
            setPadding(dp(16), dp(8), dp(16), dp(16))
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            adapter = gridAdapter
            setOnItemClickListener { _, _, pos, _ -> openBook(entries[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> showOptions(entries[pos]); true }
            visibility = View.GONE
        }
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(empty, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        audioList = ListView(this).apply {
            divider = ColorDrawable(Color.TRANSPARENT)
            dividerHeight = dp(10)
            selector = ColorDrawable(Color.TRANSPARENT)
            setPadding(dp(16), dp(8), dp(16), dp(16))
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            adapter = audioAdapter
            setOnItemClickListener { _, _, pos, _ -> if (selected.isNotEmpty()) toggleSelect(audioRows[pos]) else openAudioRow(audioRows[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> toggleSelect(audioRows[pos]); true }
            visibility = View.GONE
        }
        root.addView(audioList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        audioGrid = GridView(this).apply {
            numColumns = gridCols
            columnWidth = gridCellW
            horizontalSpacing = dp(12)
            verticalSpacing = dp(18)
            selector = ColorDrawable(Color.TRANSPARENT)
            setPadding(dp(16), dp(8), dp(16), dp(16))
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            adapter = audioGridAdapter
            setOnItemClickListener { _, _, pos, _ -> if (selected.isNotEmpty()) toggleSelect(audioRows[pos]) else openAudioRow(audioRows[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> toggleSelect(audioRows[pos]); true }
            visibility = View.GONE
        }
        root.addView(audioGrid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        audioEmpty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(64))
            addView(logo(), LinearLayout.LayoutParams(dp(150), dp(150)))
            addView(TextView(context).apply {
                text = "No audiobooks yet.\nTap “Add audio” and choose an M4B or M4A file, the MP3 files of one audiobook, or a folder with audiobooks in it.\n\nLeaflet remembers your place in each one."
                textSize = 15f; gravity = Gravity.CENTER; setTextColor(PAGE); alpha = 0.8f
            })
            visibility = View.GONE
        }
        root.addView(audioEmpty, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val refreshBooks = {
            if (library.syncFolder == null) toast("No synced folder yet. Use Add books and choose a folder to sync.") else syncFolder(quiet = false)
        }
        for (v in listOf(list, grid, empty)) pullToRefresh(v, refreshBooks)
        for (v in listOf(audioList, audioGrid, audioEmpty)) pullToRefresh(v) { syncAudio() }
        setContentView(root)

        root.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data?.let { importUris(listOf(it), openFirst = true) }

        // Opening the app (not coming back to it) goes straight to the book you were reading.
        if (savedInstanceState == null && !autoOpenDone && intent?.action != Intent.ACTION_VIEW && !library.shelfAudio) {
            autoOpenDone = true
            // Backing out of the reopened book lands on the Audiobooks shelf, like backing out of an audiobook.
            library.lastOpened()?.let { library.audioTab = true; openBook(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        if (library.syncFolder != null && System.currentTimeMillis() - lastSync > 60_000) syncFolder(quiet = true)
    }

    private fun refresh() {
        entries = library.entries()
        audioBooks = audioLib.books()
        rebuildAudioRows()
        audioAdapter.notifyDataSetChanged()
        audioGridAdapter.notifyDataSetChanged()
        adapter.notifyDataSetChanged()
        gridAdapter.notifyDataSetChanged()
        applyMode()
        fillMissingTitles()
        fillMissingCovers()
    }

    private var fillingTitles = false

    /** Books saved before chapter names were remembered: look the name up once, in the background. */
    private fun fillMissingTitles() {
        if (fillingTitles) return
        val missing = entries.filter {
            !ImageBooks.isImageFormat(it.ext) && library.position(it.id).first > 0 && library.positionTitle(it.id) == null
        }
        if (missing.isEmpty()) return
        fillingTitles = true
        io.execute {
            for (e in missing) {
                try {
                    val book = BookLoader.open(library.file(e), e.ext)
                    book.chapterTitles.getOrNull(library.position(e.id).first)?.let { library.setPositionTitle(e.id, it) }
                    book.close()
                } catch (_: Throwable) {
                    // leave it as "section N"
                }
            }
            ui.post { fillingTitles = false; adapter.notifyDataSetChanged() }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    /** "Add books": pick files, or choose a folder (like Books) whose books Leaflet keeps in sync. */
    private fun showAddMenu() {
        val folder = library.syncFolder
        val choices = ArrayList<Choice>()
        choices += Choice("Pick files", "Choose books, PDFs or comics from your phone") { pickFiles() }
        if (folder == null) {
            choices += Choice("Sync a folder", "Pick a folder like Books and Leaflet keeps it in step") { pickFolder() }
        } else {
            val name = FolderSync.label(folder)
            choices += Choice("Sync “$name” now", "Add new books and drop the ones you deleted there") { syncFolder(quiet = false) }
            choices += Choice("Choose a different folder", "Replace “$name” as the synced folder") { pickFolder() }
            choices += Choice("Stop syncing “$name”", "Books already added stay in Leaflet") {
                library.syncFolder = null
                toast("Stopped syncing. Books already added stay in Leaflet.")
            }
        }
        showChoices("Add books", choices)
    }

    private fun pickFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            // Open the picker at the Books folder if there is one; the user just taps "Use this folder".
            putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Books"),
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_FOLDER)
    }

    private var syncing = false
    private var lastSync = 0L

    /**
     * Keep Leaflet in step with the synced folder: add books that are new there, follow files that were moved, and remove
     * books whose file is gone from the folder. Books added by hand are never removed this way.
     */
    private fun syncFolder(quiet: Boolean) {
        val tree = library.syncFolder?.let { Uri.parse(it) } ?: return
        if (syncing) return
        syncing = true
        lastSync = System.currentTimeMillis()
        if (!quiet) {
            // A sync you ask for brings back everything that is in the folder but not in Leaflet, even books you removed.
            // (The automatic check on opening the library still leaves removed books alone.)
            library.clearIgnored()
            toast("Looking for books…")
        }
        io.execute {
            var added = 0
            var lostAccess = false
            var gone: List<Entry> = emptyList()
            var syncedBefore = 0
            val failed = ArrayList<String>()
            try {
                val docs = FolderSync.scan(this, tree)          // throws if the folder can't be listed completely
                val present = docs.map { it.uri.toString() }.toHashSet()
                val known = library.entries()
                syncedBefore = known.count { it.src.isNotEmpty() }
                val have = known.map { it.src }.filter { it.isNotEmpty() }.toHashSet()
                // Books whose file is no longer where it was. A file moved to another sub-folder shows up as one of these
                // plus a "new" file, so new files are matched against them first.
                val orphans = known.filter { it.src.isNotEmpty() && it.src !in present }.toMutableList()
                val claimed = HashSet<String>()
                for (d in docs) {
                    val key = d.uri.toString()
                    if (key in have || library.isIgnored(key)) continue
                    val ext = FolderSync.storedExt(d.name.substringAfterLast('.', "").lowercase())
                    // Same kind of file and size as a book whose own file vanished? It was moved: follow it.
                    val moved = orphans.firstOrNull { it.ext == ext && library.storedSize(it) == d.size }
                    if (moved != null) { orphans.remove(moved); library.setSource(moved, key); continue }
                    // Same as a book added by hand? Just remember where it came from.
                    val twin = known.firstOrNull {
                        it.src.isEmpty() && it.id !in claimed && it.ext == ext && library.storedSize(it) == d.size
                    }
                    if (twin != null) { claimed += twin.id; library.setSource(twin, key); continue }
                    try {
                        library.import(d.uri, key)
                        added++
                        ui.post { refresh() }
                    } catch (t: Throwable) {
                        failed += d.name
                        library.ignore(key)   // do not retry a broken or locked file every time
                    }
                }
                // Whatever is still orphaned was deleted from the folder. An empty listing is not trusted: the storage
                // may simply be unavailable, and that must never wipe the library.
                if (docs.isNotEmpty()) gone = orphans.toList()
            } catch (e: SecurityException) {
                lostAccess = true
            } catch (t: Throwable) {
                // The scan did not finish cleanly: keep whatever was added and remove nothing.
                gone = emptyList()
            }
            // Removing many books at once is more likely a glitch than a clean-up, so that case asks first.
            val needsConfirm = gone.size >= 4 && gone.size * 2 > syncedBefore
            ui.post {
                syncing = false
                if (lostAccess) {
                    library.syncFolder = null
                    toast("Leaflet can no longer read that folder. Choose it again from Add books.")
                }
                var removed = 0
                if (gone.isNotEmpty()) {
                    if (needsConfirm) confirmRemoveGone(gone)
                    else { gone.forEach { library.remove(it, ignoreSource = false) }; removed = gone.size }
                }
                refresh()
                val parts = ArrayList<String>()
                if (added > 0) parts += "added $added"
                if (removed > 0) parts += "removed $removed that left the folder"
                if (failed.isNotEmpty()) parts += "${failed.size} could not be read"
                if (parts.isNotEmpty()) toast(parts.joinToString(", ").replaceFirstChar { it.uppercase() })
                else if (!quiet && !lostAccess && gone.isEmpty()) toast("Already up to date")
            }
        }
    }

    private fun confirmRemoveGone(gone: List<Entry>) {
        val list = gone.take(6).joinToString("\n") { "• " + it.title } + if (gone.size > 6) "\n…and ${gone.size - 6} more" else ""
        AlertDialog.Builder(this)
            .setTitle("Remove ${gone.size} books?")
            .setMessage("These are no longer in your synced folder:\n\n$list\n\nRemoving them also forgets your reading positions.")
            .setPositiveButton("Remove") { _, _ ->
                gone.forEach { library.remove(it, ignoreSource = false) }
                refresh()
                toast("Removed ${gone.size} books")
            }
            .setNegativeButton("Keep them") { _, _ ->
                // They become ordinary books added by hand, so the sync stops asking about them.
                gone.forEach { library.setSource(it, "") }
                toast("They stay in Leaflet")
            }
            .showLeaf()
    }

    private fun pickFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_AUDIO_FILES && resultCode == RESULT_OK && data != null) {
            val uris = mutableListOf<Uri>()
            data.data?.let { uris += it }
            data.clipData?.let { c -> for (k in 0 until c.itemCount) uris += c.getItemAt(k).uri }
            for (u in uris) try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
            importAudio { listOf(audioLib.importFiles(uris.distinct())) }
            return
        }
        if (requestCode == REQ_AUDIO_FOLDER && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
            audioLib.syncFolder = uri.toString()
            syncAudio()
            return
        }
        if (requestCode == REQ_FOLDER && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            library.syncFolder = uri.toString()
            syncFolder(quiet = false)
            return
        }
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        data.data?.let { uris += it }
        data.clipData?.let { c -> for (k in 0 until c.itemCount) uris += c.getItemAt(k).uri }
        importUris(uris.distinct(), openFirst = false)
    }

    private fun importUris(uris: List<Uri>, openFirst: Boolean) {
        Toast.makeText(this, "Importing…", Toast.LENGTH_SHORT).show()
        io.execute {
            val errors = mutableListOf<String>()
            var first: Entry? = null
            for (u in uris) {
                try {
                    val e = library.import(u)
                    if (first == null) first = e
                } catch (t: Throwable) {
                    errors += t.message ?: "Import failed"
                }
            }
            ui.post {
                refresh()
                if (errors.isNotEmpty()) Toast.makeText(this, errors.joinToString("\n"), Toast.LENGTH_LONG).show()
                if (openFirst) first?.let { openBook(it) }
            }
        }
    }

    private fun showAudioAddMenu() {
        val folderName = audioLib.syncFolder?.let { FolderSync.label(it) }
        showChoices("Add audio", listOfNotNull(
            if (folderName == null) null else Choice("Check “$folderName” now", "Add new audio and drop what you deleted there") { syncAudio() },
            Choice("Pick audio files", "M4B, M4A, MP3 and more. Choose all the files of ONE audiobook.") {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    // Some phones label .m4a/.m4b files "application/octet-stream" or "video/mp4", so those are offered too.
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "application/octet-stream", "video/mp4", "application/ogg"))
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
                @Suppress("DEPRECATION")
                startActivityForResult(i, REQ_AUDIO_FILES)
            },
            Choice(if (audioLib.syncFolder == null) "Choose a folder" else "Choose a different folder", "Every audio file in it becomes an audiobook, kept in its folders. Pull down on the shelf to check it for changes.") {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
                @Suppress("DEPRECATION")
                startActivityForResult(i, REQ_AUDIO_FOLDER)
            },
        ))
    }

    /** Add audiobooks in the background (reading every file's length takes a moment), then show the shelf. */
    private fun importAudio(work: () -> List<Audiobook>) {
        toast("Adding audiobooks…")
        io.execute {
            var count = 0
            var error: String? = null
            try { count = work().size } catch (t: Throwable) { error = t.message ?: "Could not add audio" }
            ui.post {
                refresh()
                toast(error ?: if (count == 0) "Nothing new to add" else "Added $count audiobook${if (count == 1) "" else "s"}")
            }
        }
    }

    private var syncingAudio = false

    /** Keep the audiobook shelf in step with its folder: add new files, follow moved ones, drop deleted ones. */
    private fun syncAudio() {
        val tree = audioLib.syncFolder?.let { Uri.parse(it) }
        if (tree == null) { toast("No audiobook folder yet. Use Add audio and choose a folder."); return }
        if (syncingAudio) return
        syncingAudio = true
        toast("Looking for audiobooks…")
        io.execute {
            var result: AudioLibrary.SyncResult? = null
            var lostAccess = false
            var failed = false
            try { result = audioLib.sync(tree) }
            catch (e: SecurityException) { lostAccess = true }
            catch (t: Throwable) { failed = true }
            ui.post {
                syncingAudio = false
                if (lostAccess) {
                    audioLib.syncFolder = null
                    toast("Leaflet can no longer read that folder. Choose it again from Add audio.")
                    refresh(); return@post
                }
                if (failed || result == null) { toast("Could not read the folder. Nothing was changed."); refresh(); return@post }
                val gone = result.gone
                var removed = 0
                // Removing many at once is more likely a glitch than a clean-up, so that case asks first.
                if (gone.isNotEmpty()) {
                    if (gone.size >= 4 && gone.size * 2 > result.syncedBefore) confirmRemoveGoneAudio(gone)
                    else { gone.forEach { audioLib.remove(it); audioCovers.remove(it.id) }; removed = gone.size }
                }
                refresh()
                val parts = ArrayList<String>()
                if (result.added > 0) parts += "added ${result.added}"
                if (result.moved > 0) parts += "${result.moved} moved"
                if (removed > 0) parts += "removed $removed that left the folder"
                val message = if (parts.isEmpty()) (if (gone.isEmpty()) "Already up to date" else "") else parts.joinToString(", ").replaceFirstChar { it.uppercase() }
                if (message.isNotEmpty()) toast(message)
            }
        }
    }

    private fun confirmRemoveGoneAudio(gone: List<Audiobook>) {
        val names = gone.take(6).joinToString("\n") { "• " + it.title } + if (gone.size > 6) "\n…and ${gone.size - 6} more" else ""
        AlertDialog.Builder(this)
            .setTitle("Remove ${gone.size} audiobooks?")
            .setMessage("These are no longer in your audiobook folder:\n\n$names\n\nRemoving them also forgets your place and bookmarks.")
            .setPositiveButton("Remove") { _, _ ->
                gone.forEach { audioLib.remove(it); audioCovers.remove(it.id) }
                refresh()
                toast("Removed ${gone.size} audiobooks")
            }
            .setNegativeButton("Keep them") { _, _ ->
                gone.forEach { audioLib.detach(it) }
                toast("They stay in Leaflet")
            }
            .showLeaf()
    }

    /**
     * Pull down at the top of [v] and let go to run [onRefresh]. A tick is felt when the pull is far enough. The list still
     * scrolls normally: this only watches the touches.
     */
    private fun pullToRefresh(v: View, onRefresh: () -> Unit) {
        var startY = 0f
        var tracking = false
        var armed = false
        val threshold = dp(80)
        v.isClickable = true
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { startY = e.rawY; tracking = !view.canScrollVertically(-1); armed = false }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (view.canScrollVertically(-1)) { tracking = false }
                    else if (!tracking) { tracking = true; startY = e.rawY }
                    else {
                        val pulled = e.rawY - startY
                        if (!armed && pulled > threshold) { armed = true; view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }
                        else if (armed && pulled < threshold * 0.6f) armed = false
                    }
                }
                android.view.MotionEvent.ACTION_UP -> { if (armed && tracking) onRefresh(); armed = false; tracking = false }
                android.view.MotionEvent.ACTION_CANCEL -> { armed = false; tracking = false }
            }
            false
        }
    }

    private fun openAudiobook(b: Audiobook) {
        startActivity(Intent(this, AudioPlayerActivity::class.java).putExtra(AudioPlayerActivity.EXTRA_ID, b.id).putExtra(AudioPlayerActivity.EXTRA_AUTOPLAY, true))
    }

    private fun showAudioOptions(b: Audiobook) {
        AlertDialog.Builder(this)
            .setTitle(b.title)
            .setItems(arrayOf("Rename", "Remove")) { _, which ->
                if (which == 0) {
                    val input = android.widget.EditText(this).apply { setText(b.title); setSelection(text.length); isSingleLine = false; maxLines = 3 }
                    val pad = dp(20)
                    AlertDialog.Builder(this).setTitle("Rename")
                        .setView(LinearLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input, LinearLayout.LayoutParams(-1, -2)) })
                        .setPositiveButton("Save") { _, _ -> audioLib.rename(b, input.text.toString()); refresh() }
                        .setNegativeButton("Cancel", null).showLeaf()
                } else {
                    AlertDialog.Builder(this).setTitle("Remove audiobook?")
                        .setMessage(b.title + "\n\nThe audio files on your phone are not touched. Your place and bookmarks are forgotten.")
                        .setPositiveButton("Remove") { _, _ -> audioLib.remove(b); audioCovers.remove(b.id); refresh() }
                        .setNegativeButton("Cancel", null).showLeaf()
                }
            }
            .setNegativeButton("Cancel", null)
            .showLeaf()
    }

    private fun openBook(e: Entry) {
        val target = if (ImageBooks.isImageFormat(e.ext)) ImageReaderActivity::class.java else ReaderActivity::class.java
        startActivity(Intent(this, target).putExtra(ReaderActivity.EXTRA_ID, e.id))
    }

    private fun showOptions(e: Entry) {
        AlertDialog.Builder(this)
            .setTitle(e.title)
            .setItems(arrayOf("Rename", "Remove")) { _, which -> if (which == 0) promptRename(e) else confirmDelete(e) }
            .setNegativeButton("Cancel", null)
            .showLeaf()
    }

    private fun promptRename(e: Entry) {
        val input = android.widget.EditText(this).apply {
            setText(e.title); setSelection(text.length); isSingleLine = false; maxLines = 3
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        AlertDialog.Builder(this)
            .setTitle("Rename")
            .setView(LinearLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input, LinearLayout.LayoutParams(-1, -2)) })
            .setPositiveButton("Save") { _, _ -> library.rename(e, input.text.toString()); refresh() }
            .setNegativeButton("Cancel", null)
            .showLeaf()
    }

    private fun confirmDelete(e: Entry) {
        AlertDialog.Builder(this)
            .setTitle("Remove book?")
            .setMessage(e.title + if (e.src.isNotEmpty()) "\n\nThe file in your folder is not touched. Add books > Sync now brings it back." else "")
            .setPositiveButton("Remove") { _, _ -> library.remove(e); refresh() }
            .setNegativeButton("Cancel", null)
            .showLeaf()
    }

    companion object {
        private var autoOpenDone = false
        private const val REQ_PICK = 1
        private const val REQ_FOLDER = 2
        private const val REQ_AUDIO_FILES = 3
        private const val REQ_AUDIO_FOLDER = 4
        private const val PAGE_DIM = 0xFFB9D1BF.toInt()
        private const val SELECTED = 0xFFBFE3BC.toInt()
        // Leaflet palette (matches the icon).
        private const val FOREST = 0xFF1E3A2A.toInt()
        private const val LEAF = 0xFF6CC070.toInt()
        private const val LEAF_PRESSED = 0xFF55A85A.toInt()
        private const val PAGE = 0xFFFFF8E7.toInt()
        private const val PAGE_PRESSED = 0xFFEADFC0.toInt()
        private const val INK = 0xFF22301F.toInt()
        private const val MUTED = 0xFF5E7F61.toInt()
    }
}
