package com.djcohen.offlinereader

import android.content.Context
import android.net.Uri
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Reads the chapter list out of an M4B / M4A / MP4 file. Android's own metadata reader does not expose chapters, so the
 * file's atoms are read directly. Two layouts are understood: the Nero "chpl" list, and a QuickTime chapter text track.
 */
object Mp4Chapters {
    /** [startMs] is counted from the start of the file. */
    class Found(val startMs: Long, val title: String)

    private class Box(val type: String, val start: Long, val end: Long)

    fun read(context: Context, uri: Uri): List<Found> {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val ch = FileInputStream(pfd.fileDescriptor).channel
                parse(ch)
            } ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun parse(ch: FileChannel): List<Found> {
        val moov = boxes(ch, 0, ch.size()).firstOrNull { it.type == "moov" } ?: return emptyList()
        val kids = boxes(ch, moov.start, moov.end)

        // 1. Nero chapter list: moov / udta / chpl
        kids.filter { it.type == "udta" }.forEach { udta ->
            boxes(ch, udta.start, udta.end).firstOrNull { it.type == "chpl" }?.let { chpl ->
                val list = parseChpl(payload(ch, chpl))
                if (list.isNotEmpty()) return list
            }
        }

        // 2. QuickTime chapter track: an audio track whose "tref/chap" points at a text track.
        val traks = kids.filter { it.type == "trak" }.map { it to boxes(ch, it.start, it.end) }
        var chapterTrackId = -1L
        for ((_, tk) in traks) {
            val tref = tk.firstOrNull { it.type == "tref" } ?: continue
            val chap = boxes(ch, tref.start, tref.end).firstOrNull { it.type == "chap" } ?: continue
            val p = payload(ch, chap)
            if (p.size >= 4) { chapterTrackId = u32(p, 0); break }
        }
        if (chapterTrackId < 0) return emptyList()
        for ((_, tk) in traks) {
            val tkhd = tk.firstOrNull { it.type == "tkhd" } ?: continue
            val p = payload(ch, tkhd)
            val id = if (p.isNotEmpty() && p[0].toInt() == 1) u32(p, 20) else u32(p, 12)
            if (id == chapterTrackId) return parseTextTrack(ch, tk)
        }
        return emptyList()
    }

    private fun parseChpl(p: ByteArray): List<Found> {
        if (p.size < 5) return emptyList()
        var i = 4                              // version + flags
        if (p[0].toInt() == 1) i += 4          // reserved
        if (i >= p.size) return emptyList()
        val count = p[i++].toInt() and 0xFF
        val out = ArrayList<Found>()
        repeat(count) {
            if (i + 9 > p.size) return@repeat
            val start = u64(p, i) / 10_000      // 100-nanosecond units to milliseconds
            i += 8
            val len = p[i++].toInt() and 0xFF
            if (i + len > p.size) return@repeat
            out += Found(start, String(p, i, len, Charsets.UTF_8).trim())
            i += len
        }
        return out.sortedBy { it.startMs }
    }

    private fun parseTextTrack(ch: FileChannel, trak: List<Box>): List<Found> {
        val mdia = trak.firstOrNull { it.type == "mdia" } ?: return emptyList()
        val mdiaKids = boxes(ch, mdia.start, mdia.end)
        val mdhd = mdiaKids.firstOrNull { it.type == "mdhd" }?.let { payload(ch, it) } ?: return emptyList()
        val timescale = (if (mdhd.isNotEmpty() && mdhd[0].toInt() == 1) u32(mdhd, 20) else u32(mdhd, 12)).takeIf { it > 0 } ?: return emptyList()
        val minf = mdiaKids.firstOrNull { it.type == "minf" } ?: return emptyList()
        val stbl = boxes(ch, minf.start, minf.end).firstOrNull { it.type == "stbl" } ?: return emptyList()
        val t = boxes(ch, stbl.start, stbl.end).associateBy { it.type }

        val stts = payload(ch, t["stts"] ?: return emptyList())
        val stsc = payload(ch, t["stsc"] ?: return emptyList())
        val stsz = payload(ch, t["stsz"] ?: return emptyList())
        val chunkBox = t["stco"] ?: t["co64"] ?: return emptyList()
        val chunks = payload(ch, chunkBox)
        val wide = chunkBox.type == "co64"

        // Start time of every sample.
        val starts = ArrayList<Long>()
        var time = 0L
        for (e in 0 until u32(stts, 4).toInt()) {
            val count = u32(stts, 8 + e * 8); val delta = u32(stts, 12 + e * 8)
            for (k in 0 until count) { starts += time; time += delta }
            if (starts.size > 100_000) return emptyList()
        }
        // Size of every sample.
        val fixed = u32(stsz, 4)
        val total = u32(stsz, 8).toInt()
        val sizes = LongArray(total) { if (fixed > 0) fixed else u32(stsz, 12 + it * 4) }
        // Where every sample is: walk the chunks.
        val chunkCount = u32(chunks, 4).toInt()
        val stscCount = u32(stsc, 4).toInt()
        val offsets = LongArray(total)
        var sample = 0
        for (c in 0 until chunkCount) {
            var per = 0L
            for (e in 0 until stscCount) if (u32(stsc, 8 + e * 12) <= c + 1) per = u32(stsc, 12 + e * 12)
            var pos = if (wide) u64(chunks, 8 + c * 8) else u32(chunks, 8 + c * 4)
            for (k in 0 until per) {
                if (sample >= total) break
                offsets[sample] = pos
                pos += sizes[sample]
                sample++
            }
        }

        val out = ArrayList<Found>()
        for (s in 0 until minOf(total, starts.size)) {
            if (sizes[s] < 2 || sizes[s] > 4096) continue
            val raw = bytesAt(ch, offsets[s], sizes[s].toInt())
            val len = ((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF)
            if (len + 2 > raw.size) continue
            val utf16 = len >= 2 && ((raw[2].toInt() and 0xFF) == 0xFE && (raw[3].toInt() and 0xFF) == 0xFF)
            val title = if (utf16) String(raw, 4, len - 2, Charsets.UTF_16BE) else String(raw, 2, len, Charsets.UTF_8)
            out += Found(starts[s] * 1000 / timescale, title.trim())
        }
        return out
    }

    // --- reading atoms ----------------------------------------------------------------------

    private fun boxes(ch: FileChannel, from: Long, to: Long): List<Box> {
        val out = ArrayList<Box>()
        var pos = from
        while (pos + 8 <= to) {
            val h = bytesAt(ch, pos, minOf(16L, to - pos).toInt())
            var size = u32(h, 0)
            val type = String(h, 4, 4, Charsets.ISO_8859_1)
            var header = 8L
            if (size == 1L && h.size >= 16) { size = u64(h, 8); header = 16 } else if (size == 0L) size = to - pos
            if (size < header) break
            out += Box(type, pos + header, minOf(pos + size, to))
            pos += size
        }
        return out
    }

    private fun payload(ch: FileChannel, b: Box): ByteArray {
        val n = b.end - b.start
        if (n < 0 || n > 16L * 1024 * 1024) return ByteArray(0)
        return bytesAt(ch, b.start, n.toInt())
    }

    private fun bytesAt(ch: FileChannel, pos: Long, n: Int): ByteArray {
        val buf = ByteBuffer.allocate(n)
        var at = pos
        while (buf.hasRemaining()) {
            val r = ch.read(buf, at)
            if (r < 0) break
            at += r
        }
        return buf.array().copyOf(buf.position())
    }

    private fun u32(b: ByteArray, i: Int): Long {
        if (i < 0 || i + 4 > b.size) return 0
        return ((b[i].toLong() and 0xFF) shl 24) or ((b[i + 1].toLong() and 0xFF) shl 16) or
            ((b[i + 2].toLong() and 0xFF) shl 8) or (b[i + 3].toLong() and 0xFF)
    }

    private fun u64(b: ByteArray, i: Int): Long = (u32(b, i) shl 32) or u32(b, i + 4)
}
