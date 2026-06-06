package com.rawr.ccapi.local

import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import com.rawr.ccapi.ui.decodeSampledImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * Renders thumbnails/previews for local Canon RAW files by pulling embedded
 * JPEGs from the CR3 container.
 *
 * The grid intentionally uses the first embedded JPEG it can find, which is
 * usually Canon's small THMB image. Fullscreen still uses the largest embedded
 * JPEG preview for better quality.
 */
object RawPreviewLoader {

    private val gridJpegCache = object : LruCache<String, ByteArray>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    private val previewJpegCache = object : LruCache<String, ByteArray>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    private val thumbCache = object : LruCache<String, ImageBitmap>(128 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    private val ioLimiter = Semaphore(2)
    private val decodeLimiter = Semaphore(2)

    private const val GRID_SCAN_BYTES = 2 * 1024 * 1024
    private const val PREVIEW_SCAN_BYTES = 8 * 1024 * 1024

    private val EMPTY = ByteArray(0)

    fun cachedThumb(photo: LocalRawPhoto, reqWidth: Int): ImageBitmap? =
        thumbCache.get(thumbKey(photo, reqWidth))

    suspend fun loadThumb(photo: LocalRawPhoto, reqWidth: Int): ImageBitmap? {
        thumbCache.get(thumbKey(photo, reqWidth))?.let { return it }
        val bytes = gridBytes(photo) ?: return null
        return decodeLimiter.withPermit {
            withContext(Dispatchers.Default) {
                decodeSampledImage(bytes, reqWidth, photo.orientation)
                    ?.also { thumbCache.put(thumbKey(photo, reqWidth), it) }
            }
        }
    }

    suspend fun loadFull(photo: LocalRawPhoto, reqWidth: Int = 1600): ImageBitmap? {
        val bytes = previewBytes(photo) ?: return null
        return decodeLimiter.withPermit {
            withContext(Dispatchers.Default) {
                decodeSampledImage(bytes, reqWidth, photo.orientation)
            }
        }
    }

    private suspend fun gridBytes(photo: LocalRawPhoto): ByteArray? {
        val key = jpegKey(photo)
        gridJpegCache.get(key)?.let { return it.ifEmptySentinel() }
        return ioLimiter.withPermit {
            gridJpegCache.get(key)?.let { return@withPermit it.ifEmptySentinel() }
            val extracted = withContext(Dispatchers.IO) {
                runCatching {
                    extractFirstJpeg(photo.file, GRID_SCAN_BYTES)
                        ?: extractLargestJpeg(photo.file, PREVIEW_SCAN_BYTES)
                }.getOrNull()
            }
            gridJpegCache.put(key, extracted ?: EMPTY)
            extracted
        }
    }

    private suspend fun previewBytes(photo: LocalRawPhoto): ByteArray? {
        val key = jpegKey(photo)
        previewJpegCache.get(key)?.let { return it.ifEmptySentinel() }
        return ioLimiter.withPermit {
            previewJpegCache.get(key)?.let { return@withPermit it.ifEmptySentinel() }
            val extracted = withContext(Dispatchers.IO) {
                runCatching { extractLargestJpeg(photo.file, PREVIEW_SCAN_BYTES) }.getOrNull()
            }
            previewJpegCache.put(key, extracted ?: EMPTY)
            extracted
        }
    }

    private fun ByteArray.ifEmptySentinel(): ByteArray? = if (this === EMPTY || isEmpty()) null else this

    private fun extractFirstJpeg(file: File, maxBytes: Int): ByteArray? {
        val cap = minOf(file.length(), maxBytes.toLong()).toInt()
        if (cap <= 4) return null
        val buf = ByteArray(cap)
        RandomAccessFile(file, "r").use { raf -> raf.readFully(buf, 0, cap) }

        val ff = 0xFF.toByte()
        val d8 = 0xD8.toByte()
        val d9 = 0xD9.toByte()
        var i = 0
        while (i < cap - 3) {
            if (buf[i] == ff && buf[i + 1] == d8 && buf[i + 2] == ff) {
                var j = i + 2
                while (j < cap - 1) {
                    if (buf[j] == ff && buf[j + 1] == d9) return buf.copyOfRange(i, j + 2)
                    j++
                }
                return null
            }
            i++
        }
        return null
    }

    private fun extractLargestJpeg(file: File, maxBytes: Int): ByteArray? {
        val cap = minOf(file.length(), maxBytes.toLong()).toInt()
        if (cap <= 4) return null
        val buf = ByteArray(cap)
        RandomAccessFile(file, "r").use { raf -> raf.readFully(buf, 0, cap) }

        val ff = 0xFF.toByte()
        val d8 = 0xD8.toByte()
        val d9 = 0xD9.toByte()
        var best: ByteArray? = null
        var i = 0
        while (i < cap - 3) {
            if (buf[i] == ff && buf[i + 1] == d8 && buf[i + 2] == ff) {
                var j = i + 2
                var end = -1
                while (j < cap - 1) {
                    if (buf[j] == ff && buf[j + 1] == d9) {
                        end = j + 2
                        break
                    }
                    j++
                }
                if (end < 0) break
                if (best == null || end - i > best!!.size) best = buf.copyOfRange(i, end)
                i = end
            } else {
                i++
            }
        }
        return best
    }

    private fun jpegKey(p: LocalRawPhoto) = "${p.path}:${p.lastModified}"
    private fun thumbKey(p: LocalRawPhoto, reqWidth: Int) = "$reqWidth:${p.path}:${p.lastModified}"
}
