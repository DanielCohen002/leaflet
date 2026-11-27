package com.djcohen.offlinereader

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One audio file of an audiobook. [uri] points at the file where it already is: audiobooks are big, so they are not copied. */
data class Track(val uri: String, val name: String, val durationMs: Long)

/** [folder] is where it sits on the shelf, like "Dungeon Crawler Carl" or "Series/Part 1"; empty means the top level. */
/** [synced] is true for audiobooks that came from the synced folder; only those are removed when their file leaves it. */
data class Audiobook(val id: String, val title: String, val tracks: List<Track>, val folder: String = "", val synced: Boolean = false) {
    val totalMs: Long get() = tracks.sumOf { it.durationMs }
    /** Where track [index] starts, counted from the start of the whole audiobook. */
    fun offsetOf(index: Int): Long = tracks.take(index).sumOf { it.durationMs }
}

/** A chapter inside an audiobook; [ms] is counted from the start of the whole audiobook. */
data class Chapter(val ms: Long, val title: String)

data class Bookmark(val track: Int, val ms: Long, val note: String, val created: Long)

/**
 * Audiobooks live in their own store, separate from the e-books. An audiobook is one or more audio files played in order
 * (a single M4B, or a folder of MP3s). Positions, speed and bookmarks are remembered for each one.
 */
class AudioLibrary(private val context: Context) {
    private val sp = context.getSharedPreferences("audio", Context.MODE_PRIVATE)
    private val coverDir = File(context.filesDir, "covers").apply { mkdirs() }

    // --- settings ---------------------------------------------------------------------------

    var skipBackSec: Int
        get() = sp.getInt("skip_back", 15)
        set(v) = sp.edit().putInt("skip_back", v.coerceIn(1, 600)).apply()

    var skipForwardSec: Int
        get() = sp.getInt("skip_fwd", 30)
        set(v) = sp.edit().putInt("skip_fwd", v.coerceIn(1, 600)).apply()

    /** Seconds stepped back when playback resumes after an interruption (a notification, call or another app). */
    var interruptRewindSec: Int
        get() = sp.getInt("rewind_interrupt", 5)
        set(v) = sp.edit().putInt("rewind_interrupt", v.coerceIn(0, 120)).apply()

    /** Seconds stepped back when an audiobook is reopened after the app was fully closed. */
    var reopenRewindSec: Int
        get() = sp.getInt("rewind_reopen", 30)
        set(v) = sp.edit().putInt("rewind_reopen", v.coerceIn(0, 300)).apply()

    /** Show the time left divided by the playback speed (1 h left at 1.5x reads 40 min). */
    var adjustedRemaining: Boolean
        get() = sp.getBoolean("adjusted", true)
        set(v) = sp.edit().putBoolean("adjusted", v).apply()

    // --- the shelf --------------------------------------------------------------------------

    /** The folder (a tree address from the system picker) whose audiobooks are kept in step, or null. */
    var syncFolder: String?
        get() = sp.getString("sync_folder", null)
        set(v) { if (v == null) sp.edit().remove("sync_folder").apply() else sp.edit().putString("sync_folder", v).apply() }

    /** Most recently played first, then the rest A to Z. */
    fun books(): List<Audiobook> {
        val arr = JSONArray(sp.getString("index", "[]"))
        val all = (0 until arr.length()).map { parseBook(arr.getJSONObject(it)) }
        val collator = java.text.Collator.getInstance().apply { strength = java.text.Collator.PRIMARY }
        return all.sortedWith { a, b ->
            val ta = sp.getLong("played_${a.id}", 0L)
            val tb = sp.getLong("played_${b.id}", 0L)
            when {
                ta > 0 && tb > 0 && ta != tb -> tb.compareTo(ta)
                ta > 0 && tb == 0L -> -1
                tb > 0 && ta == 0L -> 1
                else -> collator.compare(a.title, b.title)
            }
        }
    }

    /** What to show for the shelf folder [path]: its sub-folders (with how many audiobooks each holds) and its audiobooks. */
    class Shelf(val folders: List<Pair<String, Int>>, val books: List<Audiobook>, val folderPlayed: Map<String, Long> = emptyMap())

    /** When this audiobook was last played (0 if never). */
    fun lastPlayed(id: String): Long = sp.getLong("played_$id", 0L)

    fun shelf(path: String): Shelf {
        val all = books()
        val prefix = if (path.isEmpty()) "" else "$path/"
        val counts = LinkedHashMap<String, Int>()
        val folderPlayed = HashMap<String, Long>()
        for (b in all) {
            if (b.folder.isEmpty() || !b.folder.startsWith(prefix) || b.folder == path) continue
            val child = b.folder.removePrefix(prefix).substringBefore('/')
            counts[child] = (counts[child] ?: 0) + 1
            folderPlayed[child] = maxOf(folderPlayed[child] ?: 0L, lastPlayed(b.id))   // a folder counts as played when anything in it was
        }
        val here = all.filter { it.folder == path }
        // A series folder reads in series order when every title says which book it is ("Book 2"), otherwise A to Z.
        val numbers = here.map { SERIES_NUMBER.find(it.title)?.groupValues?.get(1)?.toIntOrNull() }
        val ordered = if (here.size > 1 && numbers.all { it != null }) here.sortedBy { b -> SERIES_NUMBER.find(b.title)!!.groupValues[1].toInt() }
        else here.sortedWith { a, b -> naturalCompare(a.title, b.title) }
        return Shelf(counts.entries.sortedWith { a, b -> naturalCompare(a.key, b.key) }.map { it.key to it.value }, ordered, folderPlayed)
    }

    fun book(id: String): Audiobook? = books().firstOrNull { it.id == id }

    private fun parseBook(o: JSONObject): Audiobook {
        val t = o.getJSONArray("tracks")
        return Audiobook(o.getString("id"), o.getString("title"), (0 until t.length()).map {
            val x = t.getJSONObject(it)
            Track(x.getString("uri"), x.getString("name"), x.getLong("dur"))
        }, o.optString("folder", ""), o.optBoolean("synced", false))
    }

    private fun save(list: List<Audiobook>) {
        val arr = JSONArray()
        list.forEach { b ->
            val tracks = JSONArray()
            b.tracks.forEach { tracks.put(JSONObject().put("uri", it.uri).put("name", it.name).put("dur", it.durationMs)) }
            arr.put(JSONObject().put("id", b.id).put("title", b.title).put("tracks", tracks).put("folder", b.folder).put("synced", b.synced))
        }
        sp.edit().putString("index", arr.toString()).apply()
    }

    fun rename(b: Audiobook, title: String) {
        val t = title.trim()
        if (t.isEmpty()) return
        save(books().map { if (it.id == b.id) it.copy(title = t) else it })
    }

    fun remove(b: Audiobook) {
        val others = books().filter { it.id != b.id }
        val stillUsed = others.flatMap { o -> o.tracks.map { it.uri } }.toHashSet()
        for (t in b.tracks) if (t.uri !in stillUsed) {
            try { context.contentResolver.releasePersistableUriPermission(Uri.parse(t.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) { }
        }
        coverFile(b.id).delete()
        save(others)
        sp.edit().remove("pos_${b.id}").remove("speed_${b.id}").remove("played_${b.id}").remove("marks_${b.id}").remove("chap_${b.id}").apply()
    }

    // --- position, speed, bookmarks ---------------------------------------------------------

    /** Track index and milliseconds into that track. */
    fun position(id: String): Pair<Int, Long> {
        val parts = (sp.getString("pos_$id", null) ?: return 0 to 0L).split(':')
        return (parts.getOrNull(0)?.toIntOrNull() ?: 0) to (parts.getOrNull(1)?.toLongOrNull() ?: 0L)
    }

    fun savePosition(id: String, track: Int, ms: Long) {
        sp.edit().putString("pos_$id", "$track:$ms").putLong("played_$id", System.currentTimeMillis()).apply()
    }

    /** How far into the whole audiobook the saved position is. */
    fun overallMs(b: Audiobook): Long {
        val (t, ms) = position(b.id)
        return b.offsetOf(t.coerceIn(0, (b.tracks.size - 1).coerceAtLeast(0))) + ms
    }

    fun speed(id: String) = sp.getFloat("speed_$id", 1f)
    fun setSpeed(id: String, v: Float) = sp.edit().putFloat("speed_$id", v).apply()

    fun bookmarks(id: String): List<Bookmark> {
        val arr = JSONArray(sp.getString("marks_$id", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Bookmark(o.getInt("t"), o.getLong("ms"), o.optString("note", ""), o.optLong("at", 0L))
        }
    }

    private fun saveMarks(id: String, list: List<Bookmark>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("t", it.track).put("ms", it.ms).put("note", it.note).put("at", it.created)) }
        sp.edit().putString("marks_$id", arr.toString()).apply()
    }

    fun addBookmark(id: String, track: Int, ms: Long, note: String) {
        saveMarks(id, (bookmarks(id) + Bookmark(track, ms, note.trim(), System.currentTimeMillis())).sortedWith(compareBy({ it.track }, { it.ms })))
    }

    fun removeBookmark(id: String, mark: Bookmark) {
        saveMarks(id, bookmarks(id).filter { it != mark })
    }

    // --- chapters ---------------------------------------------------------------------------

    /** Chapters already looked up for [id], or null if that has not happened yet (an empty list means "none"). */
    fun cachedChapters(id: String): List<Chapter>? {
        val arr = JSONArray(sp.getString("chap_$id", null) ?: return null)
        return (0 until arr.length()).map { Chapter(arr.getJSONObject(it).getLong("ms"), arr.getJSONObject(it).getString("t")) }
    }

    /** Find the chapters inside the audio files (M4B, M4A, MP4) once and remember them. Slow: call off the UI thread. */
    fun loadChapters(b: Audiobook): List<Chapter> {
        cachedChapters(b.id)?.let { return it }
        val all = ArrayList<Chapter>()
        b.tracks.forEachIndexed { i, t ->
            if (t.name.substringAfterLast('.', "").lowercase() !in MP4_EXTENSIONS) return@forEachIndexed
            val base = b.offsetOf(i)
            val found = Mp4Chapters.read(context, Uri.parse(t.uri))
            found.forEachIndexed { n, f -> all += Chapter(base + f.startMs, f.title.ifEmpty { "Chapter ${n + 1}" }) }
        }
        all.sortBy { it.ms }
        val arr = JSONArray()
        all.forEach { arr.put(JSONObject().put("ms", it.ms).put("t", it.title)) }
        sp.edit().putString("chap_${b.id}", arr.toString()).apply()
        return all
    }

    // --- covers -----------------------------------------------------------------------------

    private fun coverFile(id: String) = File(coverDir, "a_$id.jpg")

    fun loadCover(b: Audiobook): Bitmap? = coverFile(b.id).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    private fun makeCover(id: String, firstTrack: Uri) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, firstTrack)
            val bytes = r.embeddedPicture ?: return
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
            val scale = minOf(600f / bmp.width, 600f / bmp.height, 1f)
            val small = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true) else bmp
            coverFile(id).outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        } catch (_: Throwable) {
        } finally {
            try { r.release() } catch (_: Throwable) { }
        }
    }

    // --- importing --------------------------------------------------------------------------

    private class Probed(val track: Track, val album: String?, val title: String?)

    /** Read one audio file's length and tags, or null if it is not playable audio. Slow: call off the UI thread. */
    private fun probe(uri: Uri, name: String): Probed? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            if (dur <= 0 || r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == null) return null
            return Probed(
                Track(uri.toString(), name, dur),
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim()?.takeIf { it.isNotEmpty() },
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()?.takeIf { it.isNotEmpty() },
            )
        } catch (_: Throwable) {
            return null
        } finally {
            try { r.release() } catch (_: Throwable) { }
        }
    }

    private fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0)?.let { n -> return n }
        }
        return uri.lastPathSegment ?: "audio"
    }

    /** Add the picked files as ONE audiobook, played in file-name order. */
    fun importFiles(uris: List<Uri>): Audiobook {
        val named = uris.map { it to displayName(it) }.sortedWith { a, b -> naturalCompare(a.second, b.second) }
        val probed = named.mapNotNull { (u, n) -> probe(u, n) }
        if (probed.isEmpty()) throw IllegalArgumentException("Those files are not audio Leaflet can play")
        val title = probed.first().album
            ?: (if (probed.size == 1) probed.first().title else null)
            ?: stripExt(probed.first().track.name)
        return add(title, probed)
    }

    private class Found(val uri: Uri, val name: String, val path: String)

    /** [gone] are synced audiobooks whose file is no longer in the folder; they are NOT removed yet, the caller decides. */
    class SyncResult(val added: Int, val moved: Int, val gone: List<Audiobook>, val syncedBefore: Int)

    /** Every audio file under [tree] with the sub-folder it sits in. Throws if the folder cannot be listed completely. */
    private fun scan(tree: Uri): List<Found> {
        val out = ArrayList<Found>()
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        fun walk(docId: String, path: String, depth: Int) {
            if (depth > 8) return
            val dirs = ArrayList<Pair<String, String>>()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            context.contentResolver.query(children, columns, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    if (name.startsWith(".")) continue
                    val mime = c.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) dirs += id to name
                    else if (name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS || mime.startsWith("audio/")) {
                        out += Found(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, path)
                    }
                }
            } ?: throw IllegalStateException("The folder could not be listed")
            for ((id, name) in dirs.sortedWith { x, y -> naturalCompare(x.second, y.second) }) {
                walk(id, if (path.isEmpty()) name else "$path/$name", depth + 1)
            }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), "", 0)
        return out
    }

    /**
     * Bring the shelf in step with [tree]: every audio file in it is its own audiobook, kept in its folders. New files are
     * added, files that moved are followed (keeping your place), and audiobooks whose file is gone are reported in
     * [SyncResult.gone]. Audiobooks you added by picking files are never touched. Slow: call off the UI thread.
     */
    fun sync(tree: Uri): SyncResult {
        val found = scan(tree)
        val present = found.associate { it.uri.toString() to it.path }
        val before = books()
        val updated = HashMap<String, Audiobook>()
        // A single-file audiobook whose file is in the folder belongs to the sync, and sits where the file sits.
        for (b in before) {
            val path = if (b.tracks.size == 1) present[b.tracks[0].uri] else null
            if (path != null && (!b.synced || b.folder != path)) updated[b.id] = b.copy(synced = true, folder = path)
        }
        val known = before.flatMap { b -> b.tracks.map { it.uri } }.toHashSet()
        val orphans = before.filter { it.synced && it.tracks.size == 1 && it.tracks[0].uri !in present }.toMutableList()
        var added = 0
        var moved = 0
        for (f in found.sortedWith { x, y -> naturalCompare(x.path + "/" + x.name, y.path + "/" + y.name) }) {
            if (f.uri.toString() in known) continue
            val p = probe(f.uri, f.name) ?: continue
            // The same file under a new folder or name of folder: follow it instead of adding a second copy.
            val twin = orphans.firstOrNull { it.tracks[0].name.equals(f.name, ignoreCase = true) && Math.abs(it.tracks[0].durationMs - p.track.durationMs) < 1500 }
            if (twin != null) {
                orphans.remove(twin)
                updated[twin.id] = twin.copy(tracks = listOf(p.track), folder = f.path)
                moved++
            } else {
                add(stripExt(f.name), listOf(p), f.path, synced = true)
                added++
            }
        }
        if (updated.isNotEmpty()) save(books().map { updated[it.id] ?: it })
        // An empty listing is not trusted: the storage may simply be unavailable, and that must never wipe the shelf.
        return SyncResult(added, moved, if (found.isEmpty()) emptyList() else orphans.toList(), before.count { it.synced })
    }

    /** Keep [b] on the shelf for good: it no longer follows the synced folder. */
    fun detach(b: Audiobook) {
        save(books().map { if (it.id == b.id) it.copy(synced = false) else it })
    }

    private fun add(title: String, probed: List<Probed>, folder: String = "", synced: Boolean = false): Audiobook {
        books().firstOrNull { it.tracks.firstOrNull()?.uri == probed.first().track.uri }?.let { return it }
        val book = Audiobook(UUID.randomUUID().toString(), title, probed.map { it.track }, folder, synced)
        save(books() + book)
        makeCover(book.id, Uri.parse(book.tracks.first().uri))
        return book
    }

    companion object {
        private val SERIES_NUMBER = Regex("""(?:book|vol(?:ume)?\.?|part|#)\s*(\d+)""", RegexOption.IGNORE_CASE)
        private val NUMBER_WORDS = Regex(
            """\b(zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|[ivxlc]{1,6})\b|\d""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * True when the chapter names already carry their own numbers ("Chapter 14", "Chapter Fourteen"). Then the list shows
         * just the names, because a number of our own in front would disagree with theirs ("2. Chapter One").
         */
        fun titlesAreNumbered(chapters: List<Chapter>): Boolean =
            chapters.isNotEmpty() && chapters.count { NUMBER_WORDS.containsMatchIn(it.title) } * 2 >= chapters.size

        private val MP4_EXTENSIONS = setOf("m4b", "m4a", "mp4", "m4v")
        val AUDIO_EXTENSIONS =setOf("mp3", "m4a", "m4b", "aac", "ogg", "oga", "opus", "flac", "wav", "mka")

        fun stripExt(name: String) = name.substringBeforeLast('.').replace('_', ' ').trim().ifEmpty { name }

        /** "Chapter 2" sorts before "Chapter 10". */
        fun naturalCompare(a: String, b: String): Int {
            val x = a.lowercase(); val y = b.lowercase()
            var i = 0; var j = 0
            while (i < x.length && j < y.length) {
                if (x[i].isDigit() && y[j].isDigit()) {
                    var ie = i; while (ie < x.length && x[ie].isDigit()) ie++
                    var je = j; while (je < y.length && y[je].isDigit()) je++
                    val nx = x.substring(i, ie).trimStart('0'); val ny = y.substring(j, je).trimStart('0')
                    if (nx.length != ny.length) return nx.length - ny.length
                    val c = nx.compareTo(ny)
                    if (c != 0) return c
                    i = ie; j = je
                } else {
                    if (x[i] != y[j]) return x[i].compareTo(y[j])
                    i++; j++
                }
            }
            return (x.length - i) - (y.length - j)
        }

        /** 3725000 -> "1:02:05", 125000 -> "2:05". */
        fun clock(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
        }

        /** 3725000 -> "1h 2m", 125000 -> "2m". */
        fun short(ms: Long): String {
            val m = ((ms.coerceAtLeast(0) + 30_000) / 60_000)
            return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
        }
    }
}
