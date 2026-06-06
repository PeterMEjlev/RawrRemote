package com.rawr.ccapi.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import com.rawr.ccapi.CameraSession
import com.rawr.ccapi.net.CcapiException
import com.rawr.ccapi.net.DownloadCancelledException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that streams the queued batch to disk via the Storage
 * Access Framework, so large RAW downloads survive the screen turning off.
 *
 * It reads the job from [DownloadController.pending], reports progress back
 * through the controller, and stops itself when the batch finishes.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(NOTIFICATION_ID, buildNotification("Starting download…", 0, 0))
        scope.launch {
            try {
                runBatch()
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun runBatch() {
        val request = DownloadController.pending
        val client = CameraSession.client
        if (request == null || client == null) {
            DownloadController.finish(JobStatus.ERROR)
            return
        }

        val tree = DocumentFile.fromTreeUri(this, request.destinationTree)
        if (tree == null || !tree.canWrite()) {
            request.files.indices.forEach { i ->
                DownloadController.updateFile(i) {
                    it.copy(status = FileStatus.ERROR, error = "Destination folder is not writable")
                }
            }
            DownloadController.finish(JobStatus.ERROR)
            return
        }

        var lastNotify = 0L
        val total = request.files.size

        request.files.forEachIndexed { index, task ->
            if (DownloadController.cancelFlag.get()) {
                DownloadController.updateFile(index) { it.copy(status = FileStatus.CANCELLED) }
                return@forEachIndexed
            }

            DownloadController.updateFile(index) { it.copy(status = FileStatus.DOWNLOADING) }
            updateNotification("Downloading ${task.name}", index, total)

            val name = uniqueName(tree, task.name)
            val doc = tree.createFile("application/octet-stream", name)
            if (doc == null) {
                DownloadController.updateFile(index) {
                    it.copy(status = FileStatus.ERROR, error = "Could not create file in destination")
                }
                return@forEachIndexed
            }

            try {
                contentResolver.openOutputStream(doc.uri).use { out ->
                    if (out == null) throw CcapiException("Could not open destination for writing")
                    client.download(
                        fileUrl = task.url,
                        sink = out,
                        isCancelled = { DownloadController.cancelFlag.get() },
                        onProgress = { downloaded, _ ->
                            DownloadController.updateFile(index) { it.copy(downloaded = downloaded) }
                            val now = System.currentTimeMillis()
                            if (now - lastNotify > 500) {
                                lastNotify = now
                                updateNotification("Downloading ${task.name}", index, total)
                            }
                        },
                    )
                }
                DownloadController.updateFile(index) {
                    it.copy(status = FileStatus.DONE, savedName = doc.name ?: name)
                }
            } catch (e: DownloadCancelledException) {
                runCatching { doc.delete() }
                DownloadController.updateFile(index) { it.copy(status = FileStatus.CANCELLED) }
            } catch (e: Exception) {
                runCatching { doc.delete() } // never leave a partial file behind
                DownloadController.updateFile(index) {
                    it.copy(status = FileStatus.ERROR, error = e.message ?: "Download failed")
                }
            }
        }

        val files = DownloadController.state.value.files
        val finalStatus = when {
            files.any { it.status == FileStatus.ERROR } -> JobStatus.ERROR
            files.any { it.status == FileStatus.CANCELLED } -> JobStatus.CANCELLED
            else -> JobStatus.DONE
        }
        DownloadController.finish(finalStatus)
    }

    /** Resolve filename collisions in the destination: name -> name_1, name_2… */
    private fun uniqueName(tree: DocumentFile, name: String): String {
        if (tree.findFile(name) == null) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (tree.findFile("${base}_$i$ext") != null) i++
        return "${base}_$i$ext"
    }

    // -- notification ------------------------------------------------------

    private fun buildNotification(text: String, current: Int, total: Int): Notification {
        ensureChannel()
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (total > 0) "Downloading RAW ($current/$total)" else "Rawr Remote")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (total > 0) builder.setProgress(total, current, false)
        return builder.build()
    }

    private fun updateNotification(text: String, current: Int, total: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text, current, total))
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
    }

    private fun startForegroundCompat(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(id, notification)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 42
    }
}
