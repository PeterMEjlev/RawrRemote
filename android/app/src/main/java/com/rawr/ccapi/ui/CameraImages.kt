package com.rawr.ccapi.ui

import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.rawr.ccapi.CameraSession
import com.rawr.ccapi.net.CcapiEndpoints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Loads CCAPI previews and caches them so the grid scrolls smoothly.
 *
 * The grid loads in two stages for speed: the tiny embedded `?kind=thumbnail`
 * (a few KB, near-instant) shows immediately, then the sharper `?kind=display`
 * image quietly replaces it. This is why the grid now feels fast — it no longer
 * blocks on a full display-size download per cell before showing anything.
 *
 * Caches:
 *  - raw JPEG bytes per (kind, path), so the grid's sharp image and the
 *    full-screen view share a single network fetch of the display JPEG, and
 *  - decoded bitmaps: a small one for thumbnails and a larger one for the
 *    sharpened grid image.
 */
object CameraImageLoader {

    private val rawCache = object : LruCache<String, ByteArray>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }
    private val thumbCache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }
    private val gridCache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    // Two separate lanes so the small, fast thumbnails are NEVER stuck waiting
    // behind the big, slow display downloads. The visible cells' thumbnails get
    // their own permits and load promptly; the sharper images trickle in on a
    // narrower lane.
    private val thumbLimiter = Semaphore(4)
    private val sharpLimiter = Semaphore(2)

    // Bounds concurrent bitmap decodes. The network lanes indirectly bound
    // decodes on first load, but on the cache-hit path (scrolling back, or a
    // pinch that changed the request width so bitmap caches miss while the raw
    // bytes hit) every visible cell would otherwise decode at once, saturating
    // the CPU and starving the UI thread mid-scroll.
    private val decodeLimiter = Semaphore(2)

    private suspend fun fetchBytes(path: String, kind: String): ByteArray? {
        val key = "$kind:$path"
        rawCache.get(key)?.let { return it }
        if (CameraSession.client == null) return null
        val limiter = if (kind == CcapiEndpoints.KIND_DISPLAY) sharpLimiter else thumbLimiter
        return limiter.withPermit {
            // Another cell may have fetched the same image while we waited.
            rawCache.get(key)?.let { return@withPermit it }
            val client = CameraSession.client ?: return@withPermit null
            withContext(Dispatchers.IO) {
                try {
                    val (bytes, _) = client.getImage(path, kind)
                    if (bytes.isEmpty()) null else bytes.also { rawCache.put(key, it) }
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    /** Already-decoded sharp grid image at [reqWidth], if cached. */
    fun cachedGrid(path: String, reqWidth: Int): ImageBitmap? = gridCache.get("$reqWidth:$path")

    /** Tiny, fast embedded thumbnail for a grid cell; cached. */
    suspend fun loadThumb(path: String): ImageBitmap? {
        thumbCache.get(path)?.let { return it }
        val bytes = fetchBytes(path, CcapiEndpoints.KIND_THUMBNAIL) ?: return null
        return decodeLimiter.withPermit {
            withContext(Dispatchers.Default) {
                decode(bytes, reqWidth = 320)?.also { thumbCache.put(path, it) }
            }
        }
    }

    /**
     * Sharper display image, decoded only as large as the cell actually needs
     * ([reqWidth] shrinks as the grid gains columns). Smaller bitmaps mean far
     * less GPU work per frame, which keeps scrolling smooth at higher densities.
     */
    suspend fun loadGridSharp(path: String, reqWidth: Int): ImageBitmap? {
        val key = "$reqWidth:$path"
        gridCache.get(key)?.let { return it }
        val bytes = fetchBytes(path, CcapiEndpoints.KIND_DISPLAY) ?: return null
        return decodeLimiter.withPermit {
            withContext(Dispatchers.Default) {
                decode(bytes, reqWidth)?.also { gridCache.put(key, it) }
            }
        }
    }

    /** Larger bitmap for the full-screen view; reuses the cached display bytes. */
    suspend fun loadFull(path: String): ImageBitmap? {
        val bytes = fetchBytes(path, CcapiEndpoints.KIND_DISPLAY) ?: return null
        return decodeLimiter.withPermit {
            withContext(Dispatchers.Default) { decode(bytes, reqWidth = 1600) }
        }
    }

    // Decode camera-preview JPEG bytes; shared with the local viewer, including
    // the manual EXIF-orientation fix (see ImageDecode.kt).
    private fun decode(bytes: ByteArray, reqWidth: Int): ImageBitmap? =
        decodeSampledImage(bytes, reqWidth)
}

// Decode-width buckets for the sharp grid image. Quantizing the requested width
// keeps a pinch or rotation that nudges the cell size from changing every
// visible cell's produceState key (and gridCache key) at once — which would
// restart them all and trigger a simultaneous re-fetch/re-decode storm.
private val WIDTH_BUCKETS = intArrayOf(192, 256, 320, 448, 640, 896, 1024)

private fun quantizeCellWidth(px: Int): Int =
    WIDTH_BUCKETS.firstOrNull { it >= px } ?: WIDTH_BUCKETS.last()

@Composable
fun GridThumb(path: String, cellWidthPx: Int, modifier: Modifier = Modifier) {
    // Decode the sharp image to (the bucket just above) the cell's on-screen
    // pixel width, so we never decode or upload a bitmap much bigger than the
    // cell draws: more columns = smaller cells = smaller bitmaps = less GPU
    // work per frame, while nearby column counts share one cached decode.
    val sharpWidth = quantizeCellWidth(cellWidthPx)
    val image by produceState<ImageBitmap?>(initialValue = null, key1 = path, key2 = sharpWidth) {
        // If the sharp image is already cached, use it straight away; otherwise
        // show the fast embedded thumbnail first, then upgrade to the sharp one.
        val cachedSharp = CameraImageLoader.cachedGrid(path, sharpWidth)
        if (cachedSharp != null) {
            value = cachedSharp
        } else {
            // Tiny debounce only to drop cells blown past in a fast fling; short
            // enough that thumbnails of cells the user is looking at appear fast.
            delay(40)
            value = CameraImageLoader.loadThumb(path)
            // Upgrade to the sharp image only after the cell has clearly settled,
            // and on its own narrow lane, so it never delays other thumbnails.
            delay(400)
            CameraImageLoader.loadGridSharp(path, sharpWidth)?.let { value = it }
        }
    }
    val img = image
    if (img != null) {
        Image(bitmap = img, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    } else {
        // Static placeholder, deliberately NOT a spinner: with several columns,
        // dozens of cells load at once and an indeterminate spinner per cell
        // means dozens of infinite animations invalidating every frame — a
        // major source of scroll jank at higher grid densities.
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

@Composable
fun FullImage(path: String, modifier: Modifier = Modifier) {
    val image by produceState<ImageBitmap?>(initialValue = null, key1 = path) {
        value = CameraImageLoader.loadFull(path)
    }
    val img = image
    if (img != null) {
        Image(bitmap = img, contentDescription = null, contentScale = ContentScale.Fit, modifier = modifier)
    } else {
        Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}
