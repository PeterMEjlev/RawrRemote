package com.rawr.ccapi.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import com.rawr.ccapi.CameraSession
import com.rawr.ccapi.net.CcapiException
import com.rawr.ccapi.net.DownloadCancelledException
import com.rawr.ccapi.ui.MainActivity
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

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(NOTIFICATION_ID, buildNotification("Starting download…", 0, 0))
        acquireLocks()
        scope.launch {
            try {
                runBatch()
            } finally {
                releaseLocks()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Hold Wi-Fi at full performance plus a partial wakelock for the batch.
     * Without these, screen-off Wi-Fi power saving can throttle transfers to a
     * crawl even under a foreground service. The wakelock carries a generous
     * timeout as a leak backstop; releaseLocks() in the finally is the real end.
     */
    private fun acquireLocks() {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wm.createWifiLock(mode, "rawr:download").apply {
            setReferenceCounted(false)
            acquire()
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rawr:download").apply {
            setReferenceCounted(false)
            acquire(4 * 60 * 60 * 1000L)
        }
    }

    private fun releaseLocks() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
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

        // One directory listing up front. DocumentFile.findFile() re-queries the
        // whole SAF directory on every call, which made collision checks
        // O(files x folder-size) — minutes for a big batch into a full folder.
        val existingNames = tree.listFiles().mapNotNullTo(HashSet()) { it.name }

        request.files.forEachIndexed { index, task ->
            if (DownloadController.cancelFlag.get()) {
                DownloadController.updateFile(index) { it.copy(status = FileStatus.CANCELLED) }
                return@forEachIndexed
            }

            DownloadController.updateFile(index) { it.copy(status = FileStatus.DOWNLOADING) }
            updateNotification("Downloading ${task.name}", index, total)

            val name = uniqueName(existingNames, task.name)
            val doc = tree.createFile("application/octet-stream", name)
            if (doc == null) {
                DownloadController.updateFile(index) {
                    it.copy(status = FileStatus.ERROR, error = "Could not create file in destination")
                }
                return@forEachIndexed
            }
            // The provider may still have adjusted the name (e.g. added an
            // extension); track what it actually created too.
            doc.name?.let { existingNames.add(it) }

            try {
                var bytes = 0L
                contentResolver.openOutputStream(doc.uri).use { out ->
                    if (out == null) throw CcapiException("Could not open destination for writing")
                    var lastPush = 0L
                    client.download(
                        fileUrl = task.url,
                        sink = out,
                        isCancelled = { DownloadController.cancelFlag.get() },
                        onProgress = { downloaded, _ ->
                            bytes = downloaded
                            val now = System.currentTimeMillis()
                            // Throttle state pushes: every 1 MB chunk copied the
                            // whole file list + recomposed the UI otherwise.
                            if (now - lastPush > 250) {
                                lastPush = now
                                DownloadController.updateFile(index) { it.copy(downloaded = downloaded) }
                            }
                            if (now - lastNotify > 500) {
                                lastNotify = now
                                updateNotification("Downloading ${task.name}", index, total)
                            }
                        },
                    )
                }
                DownloadController.updateFile(index) {
                    it.copy(status = FileStatus.DONE, downloaded = bytes, savedName = doc.name ?: name)
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

    /**
     * Resolve filename collisions against the in-memory [existing] name set:
     * name -> name_1, name_2… The chosen name is added to the set, reserving it
     * for the rest of the batch.
     */
    private fun uniqueName(existing: MutableSet<String>, name: String): String {
        if (name !in existing) {
            existing.add(name)
            return name
        }
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while ("${base}_$i$ext" in existing) i++
        return "${base}_$i$ext".also { existing.add(it) }
    }

    // -- notification ------------------------------------------------------

    private fun buildNotification(text: String, current: Int, total: Int): Notification {
        ensureChannel()
        // Tapping the notification brings the app back to the foreground.
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (total > 0) "Downloading RAW ($current/$total)" else "Rawr Remote")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(tap)
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
