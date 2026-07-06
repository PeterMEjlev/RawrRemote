package com.rawr.ccapi.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared, process-wide download state. The UI observes [state]; the
 * [DownloadService] reads [pending] and reports progress back through here.
 *
 * Kept as an object because the work outlives any one screen and a foreground
 * service drives it. Single batch at a time (MVP).
 */
object DownloadController {

    private val _state = MutableStateFlow(DownloadUiState())
    val state: StateFlow<DownloadUiState> = _state.asStateFlow()

    @Volatile
    var pending: DownloadRequestData? = null
        private set

    /** Set by the service per-job; checked inside the streaming loop. */
    val cancelFlag = AtomicBoolean(false)

    val isRunning: Boolean get() = _state.value.status == JobStatus.RUNNING

    /** Queue a batch and start the foreground service. */
    fun start(context: Context, request: DownloadRequestData) {
        if (isRunning) return
        cancelFlag.set(false)
        pending = request
        _state.value = DownloadUiState(
            status = JobStatus.RUNNING,
            destinationLabel = request.destinationLabel,
            files = request.files.map {
                FileProgress(name = it.name, folder = it.folder, size = it.size)
            },
        )
        ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
    }

    fun cancel() {
        cancelFlag.set(true)
    }

    // -- internal: called by the service ----------------------------------

    internal fun updateFile(index: Int, transform: (FileProgress) -> FileProgress) {
        // update {} is atomic (compare-and-set), so concurrent progress pushes
        // can't overwrite each other the way a get-copy-set could.
        _state.update { current ->
            if (index !in current.files.indices) return@update current
            current.copy(files = current.files.toMutableList().also { it[index] = transform(it[index]) })
        }
    }

    internal fun finish(status: JobStatus) {
        _state.update { it.copy(status = status) }
        pending = null
    }
}
