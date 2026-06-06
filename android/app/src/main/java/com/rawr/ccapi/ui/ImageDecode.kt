package com.rawr.ccapi.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream

/**
 * JPEG decoding shared by the CCAPI grid ([CameraImageLoader]) and the local
 * RAW viewer ([com.rawr.ccapi.local.RawPreviewLoader]). Both decode JPEG bytes
 * downsampled to roughly the on-screen size and must honour EXIF orientation by
 * hand, because `BitmapFactory` ignores it (portrait shots otherwise decode
 * sideways).
 *
 * Camera previews carry their orientation in their own EXIF, so the default
 * reads it from [bytes]. A `.CR3`'s embedded preview does *not* — its
 * orientation lives in the RAW's metadata — so `RawPreviewLoader` extracts that
 * and passes [orientation] explicitly.
 */
internal fun decodeSampledImage(
    bytes: ByteArray,
    reqWidth: Int,
    orientation: Int = orientationFromExif(bytes),
): ImageBitmap? = decodeSampledImageWithSize(bytes, reqWidth, orientation)?.image

internal data class DecodedImage(
    val image: ImageBitmap,
    val width: Int,
    val height: Int,
)

internal fun decodeSampledImageWithSize(
    bytes: ByteArray,
    reqWidth: Int,
    orientation: Int = orientationFromExif(bytes),
): DecodedImage? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

    // Upright frames need no pixel rotation, so we decode them straight into a
    // GPU-resident HARDWARE bitmap: it skips the per-draw texture upload and keeps
    // the pixels off the Java heap (far less GC churn while scrolling). Rotated
    // frames must stay software because the orientation Matrix below can't operate
    // on a hardware bitmap.
    val isUpright = orientation == ExifInterface.ORIENTATION_NORMAL ||
        orientation == ExifInterface.ORIENTATION_UNDEFINED
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sampleSize(bounds.outWidth, reqWidth)
        if (isUpright) inPreferredConfig = Bitmap.Config.HARDWARE
    }
    var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    if (bitmap == null && isUpright) {
        // Rare: a decoder rejected HARDWARE config — retry as a software bitmap.
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888
        bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
    val decoded = bitmap ?: return null
    val oriented = if (isUpright) decoded else applyOrientation(decoded, orientation)
    val (width, height) = orientedSourceSize(bounds.outWidth, bounds.outHeight, orientation)
        ?: (oriented.width to oriented.height)
    return DecodedImage(oriented.asImageBitmap(), width, height)
}

/** EXIF orientation embedded in [bytes], or NORMAL if absent / unreadable. */
private fun orientationFromExif(bytes: ByteArray): Int = try {
    ExifInterface(ByteArrayInputStream(bytes))
        .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
} catch (e: Exception) {
    ExifInterface.ORIENTATION_NORMAL
}

/** Rotate/flip [bitmap] to match a standard EXIF [orientation] (1–8). */
private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
        else -> return bitmap // ORIENTATION_NORMAL / undefined: nothing to do
    }
    return try {
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            .also { if (it != bitmap) bitmap.recycle() }
    } catch (e: Exception) {
        bitmap
    }
}

private fun orientedSourceSize(width: Int, height: Int, orientation: Int): Pair<Int, Int>? {
    if (width <= 0 || height <= 0) return null
    return when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90,
        ExifInterface.ORIENTATION_ROTATE_270,
        ExifInterface.ORIENTATION_TRANSPOSE,
        ExifInterface.ORIENTATION_TRANSVERSE -> height to width
        else -> width to height
    }
}

private fun sampleSize(srcWidth: Int, reqWidth: Int): Int {
    if (srcWidth <= 0 || reqWidth <= 0) return 1
    var sample = 1
    while (srcWidth / (sample * 2) >= reqWidth) sample *= 2
    return sample
}
