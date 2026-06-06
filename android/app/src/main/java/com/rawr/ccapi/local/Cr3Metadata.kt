package com.rawr.ccapi.local

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.RandomAccessFile
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Reads the bits of a Canon CR3's metadata we need without a full RAW decoder:
 * capture **orientation** and **star rating**. Both are standard EXIF tags in
 * the CR3's `CMT1` box (its TIFF/EXIF IFD0) — which the OS can't read from a
 * `.CR3`, hence parsing by hand.
 *
 * `CMT1` sits in `moov` near the very start of the file (before the embedded
 * thumbnail), so a small header read finds it. Rating also has an XMP fallback
 * because not every body writes the EXIF Rating tag.
 */
internal object Cr3Metadata {

    data class Meta(val orientation: Int, val rating: Int, val captureTime: Long?)

    private val NONE = Meta(ExifInterface.ORIENTATION_NORMAL, 0, null)

    // CMT1 sits in moov right at the start (before the embedded thumbnail), so a
    // small header read finds it; kept small to keep the scan's I/O light.
    private const val HEADER_BYTES = 128 * 1024
    private val CMT1 = byteArrayOf(0x43, 0x4D, 0x54, 0x31) // "CMT1"

    private const val TAG_ORIENTATION = 0x0112
    private const val TAG_DATETIME = 0x0132
    private const val TAG_EXIF_IFD_POINTER = 0x8769
    private const val TAG_DATETIME_ORIGINAL = 0x9003
    private const val TAG_DATETIME_DIGITIZED = 0x9004
    private const val TIFF_TYPE_ASCII = 2

    private val ExifDateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.US)
    private const val TAG_RATING = 0x4746 // standard EXIF "Rating" (0–5), in IFD0

    /** Read [file]'s orientation + rating from its header, defaulting gracefully. */
    fun read(file: File): Meta {
        val cap = minOf(file.length(), HEADER_BYTES.toLong()).toInt()
        if (cap <= 8) return NONE
        val buf = ByteArray(cap)
        try {
            RandomAccessFile(file, "r").use { it.readFully(buf, 0, cap) }
        } catch (e: Exception) {
            return NONE
        }
        val cmt1 = indexOf(buf, CMT1, cap, 0)
        val exif = if (cmt1 >= 0) parseIfd0(buf, cmt1 + CMT1.size, cap) else NONE
        // Fall back to an XMP rating only when EXIF didn't carry one.
        val rating = if (exif.rating > 0) exif.rating else xmpRating(buf, cap)
        return Meta(exif.orientation, rating, exif.captureTime)
    }

    /** EXIF orientation, rating, and capture time from the TIFF block starting at [off]. */
    private fun parseIfd0(buf: ByteArray, off: Int, cap: Int): Meta {
        if (off + 8 > cap) return NONE
        val little = buf[off] == 'I'.code.toByte() && buf[off + 1] == 'I'.code.toByte()
        val big = buf[off] == 'M'.code.toByte() && buf[off + 1] == 'M'.code.toByte()
        if (!little && !big) return NONE

        fun u16(p: Int): Int =
            if (little) (buf[p].toInt() and 0xFF) or ((buf[p + 1].toInt() and 0xFF) shl 8)
            else ((buf[p].toInt() and 0xFF) shl 8) or (buf[p + 1].toInt() and 0xFF)
        fun u32(p: Int): Int =
            if (little) {
                (buf[p].toInt() and 0xFF) or ((buf[p + 1].toInt() and 0xFF) shl 8) or
                    ((buf[p + 2].toInt() and 0xFF) shl 16) or ((buf[p + 3].toInt() and 0xFF) shl 24)
            } else {
                ((buf[p].toInt() and 0xFF) shl 24) or ((buf[p + 1].toInt() and 0xFF) shl 16) or
                    ((buf[p + 2].toInt() and 0xFF) shl 8) or (buf[p + 3].toInt() and 0xFF)
            }

        fun asciiValue(entry: Int): String? {
            val type = u16(entry + 2)
            val byteCount = u32(entry + 4)
            if (type != TIFF_TYPE_ASCII || byteCount <= 0) return null
            val valueOffset = if (byteCount <= 4) entry + 8 else off + u32(entry + 8)
            if (valueOffset < off || valueOffset >= cap) return null
            val len = minOf(byteCount, cap - valueOffset)
            return String(buf, valueOffset, len, Charsets.US_ASCII).trim('\u0000', ' ')
        }

        fun walkIfd(ifd: Int, visit: (tag: Int, entry: Int) -> Unit) {
            if (ifd < off || ifd + 2 > cap) return
            val count = u16(ifd)
            var entry = ifd + 2
            var i = 0
            while (i < count && entry + 12 <= cap) {
                visit(u16(entry), entry)
                entry += 12
                i++
            }
        }

        val ifd0 = off + u32(off + 4) // IFD0 offset is relative to the TIFF start
        if (ifd0 < off || ifd0 + 2 > cap) return NONE
        var orientation = ExifInterface.ORIENTATION_NORMAL
        var rating = 0
        var exifIfd = -1
        var captureDate: String? = null
        var fallbackDate: String? = null

        walkIfd(ifd0) { tag, entry ->
            when (tag) {
                TAG_ORIENTATION -> u16(entry + 8).let { if (it in 1..8) orientation = it }
                TAG_RATING -> u16(entry + 8).let { if (it in 0..5) rating = it }
                TAG_DATETIME -> fallbackDate = asciiValue(entry)
                TAG_EXIF_IFD_POINTER -> {
                    val relative = u32(entry + 8)
                    if (relative > 0) exifIfd = off + relative
                }
            }
        }

        walkIfd(exifIfd) { tag, entry ->
            when (tag) {
                TAG_DATETIME_ORIGINAL -> captureDate = asciiValue(entry)
                TAG_DATETIME_DIGITIZED -> if (captureDate == null) captureDate = asciiValue(entry)
            }
        }

        return Meta(orientation, rating, parseExifCaptureTime(captureDate ?: fallbackDate))
    }

    private fun parseExifCaptureTime(value: String?): Long? {
        val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val normalized = if (trimmed.length >= 19) trimmed.take(19) else trimmed
        return runCatching {
            LocalDateTime.parse(normalized, ExifDateFormatter)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }.getOrNull()
    }

    /** Best-effort `xmp:Rating` from an XMP packet within the header, or 0. */
    private fun xmpRating(buf: ByteArray, cap: Int): Int {
        val key = "xmp:Rating".toByteArray(Charsets.US_ASCII)
        var from = 0
        while (true) {
            val idx = indexOf(buf, key, cap, from)
            if (idx < 0) return 0
            val after = idx + key.size
            // Skip "xmp:RatingPercent".
            if (after >= cap || buf[after] == 'P'.code.toByte()) { from = after + 1; continue }
            // The value follows as ="N" (attribute) or >N (element).
            var p = after
            val limit = minOf(cap, after + 8)
            while (p < limit) {
                val c = buf[p].toInt()
                if (c >= '0'.code && c <= '5'.code) return c - '0'.code
                p++
            }
            from = after + 1
        }
    }

    /** First index of [pat] within `buf[from, cap)`, or -1. */
    private fun indexOf(buf: ByteArray, pat: ByteArray, cap: Int, from: Int): Int {
        val last = cap - pat.size
        var i = from
        while (i <= last) {
            var k = 0
            while (k < pat.size && buf[i + k] == pat[k]) k++
            if (k == pat.size) return i
            i++
        }
        return -1
    }
}
