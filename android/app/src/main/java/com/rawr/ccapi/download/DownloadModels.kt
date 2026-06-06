package com.rawr.ccapi.download

import android.net.Uri

/** A single file the user asked to download. */
data class FileTask(
    val name: String,
    val url: String,
    val folder: String,
    val size: Long?,
)

enum class FileStatus { PENDING, DOWNLOADING, DONE, ERROR, CANCELLED }

data class FileProgress(
    val name: String,
    val folder: String,
    val size: Long?,
    val downloaded: Long = 0,
    val status: FileStatus = FileStatus.PENDING,
    val error: String? = null,
    val savedName: String? = null,
)

enum class JobStatus { IDLE, RUNNING, DONE, CANCELLED, ERROR }

data class DownloadUiState(
    val status: JobStatus = JobStatus.IDLE,
    val destinationLabel: String = "",
    val files: List<FileProgress> = emptyList(),
)

/** Parameters handed from the ViewModel to the running [DownloadService]. */
data class DownloadRequestData(
    val destinationTree: Uri,
    val destinationLabel: String,
    val files: List<FileTask>,
)
