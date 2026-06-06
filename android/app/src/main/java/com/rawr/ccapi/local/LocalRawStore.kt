package com.rawr.ccapi.local

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/** One RAW file found on shared storage, with metadata parsed from its header. */
data class LocalRawPhoto(
    val path: String,
    val name: String,
    val size: Long,
    val lastModified: Long,
    val captureTime: Long? = null,
    val orientation: Int = ExifInterface.ORIENTATION_NORMAL,
    val rating: Int = 0,
) {
    val file: File get() = File(path)
}

/**
 * Finds Canon RAW files on the tablet/phone's shared storage and reads each
 * one's capture time, orientation, and rating.
 */
object LocalRawStore {

    // R5 writes .CR3; .CR2 covers older Canon bodies. Lowercase for comparison.
    private val rawExtensions = setOf("cr3", "cr2")
    private val hiddenSkipDirs = setOf(".thumbnails", ".trashed", ".trash", ".cache")

    /** Walk every shared storage root and return every RAW file, newest first. */
    suspend fun scan(context: Context): List<LocalRawPhoto> = withContext(Dispatchers.IO) {
        val filesByPath = LinkedHashMap<String, File>()

        storageRoots(context).forEach { root ->
            root.walkTopDown()
                .onEnter { dir ->
                    // Lightroom keeps downloaded originals under Android/data,
                    // so scan it too. Unreadable app-private dirs are skipped by
                    // onFail without aborting the whole walk.
                    dir.name.lowercase() !in hiddenSkipDirs
                }
                .onFail { _, _ -> /* unreadable dir: skip, never abort the whole scan */ }
                .filter { it.isFile && it.extension.lowercase() in rawExtensions }
                .forEach { filesByPath[it.normalizedPath] = it }
        }

        // Read each file's header metadata concurrently (bounded), then sort.
        val limiter = Semaphore(8)
        val photos = coroutineScope {
            filesByPath.values.map { f ->
                async {
                    limiter.withPermit {
                        val meta = Cr3Metadata.read(f)
                        LocalRawPhoto(
                            path = f.path,
                            name = f.name,
                            size = f.length(),
                            lastModified = f.lastModified(),
                            captureTime = meta.captureTime,
                            orientation = meta.orientation,
                            rating = meta.rating,
                        )
                    }
                }
            }.awaitAll()
        }

        photos.sortedByDescending { it.captureTime ?: it.lastModified }
    }

    private fun storageRoots(context: Context): List<File> {
        val roots = LinkedHashMap<String, File>()

        fun addRoot(file: File?) {
            if (file == null || !file.exists() || !file.isDirectory) return
            roots[file.normalizedPath] = file
        }

        addRoot(Environment.getExternalStorageDirectory())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val storageManager = context.getSystemService(StorageManager::class.java)
            storageManager?.storageVolumes?.forEach { addRoot(it.directory) }
        }

        context.getExternalFilesDirs(null).forEach { appDir ->
            addRoot(appDir?.sharedStorageRoot())
        }

        return roots.values.toList()
    }

    private fun File.sharedStorageRoot(): File? {
        val marker = "${File.separator}Android${File.separator}data${File.separator}"
        val idx = absolutePath.indexOf(marker)
        return if (idx > 0) File(absolutePath.substring(0, idx)) else null
    }

    private val File.normalizedPath: String
        get() = absolutePath.trimEnd(File.separatorChar)
}
