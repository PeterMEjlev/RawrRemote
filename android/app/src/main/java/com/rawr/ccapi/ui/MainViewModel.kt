package com.rawr.ccapi.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rawr.ccapi.CameraSession
import com.rawr.ccapi.download.DownloadController
import com.rawr.ccapi.download.DownloadRequestData
import com.rawr.ccapi.download.FileTask
import com.rawr.ccapi.net.CameraNetwork
import com.rawr.ccapi.net.CcapiClient
import com.rawr.ccapi.net.CcapiException
import com.rawr.ccapi.net.RawFile
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** One step in the breadcrumb trail: a label and the contents path it points to. */
data class Crumb(val label: String, val path: String?)

/** Upper bound on grid columns (pinch-to-zoom + width-derived default). */
private const val MAX_GRID_COLUMNS = 10

class MainViewModel(app: Application) : AndroidViewModel(app) {

    // -- connection form --------------------------------------------------
    var host by mutableStateOf("192.168.1.2")
    var port by mutableStateOf("8080")
    var username by mutableStateOf("")
    var password by mutableStateOf("")

    var connecting by mutableStateOf(false)
        private set
    var connected by mutableStateOf(false)
        private set
    var deviceName by mutableStateOf<String?>(null)
        private set
    var connectionError by mutableStateOf<String?>(null)
        private set

    // -- browsing ---------------------------------------------------------
    val breadcrumbs = mutableStateListOf(Crumb("Storage", null))
    val folders = mutableStateListOf<String>()       // navigable sub-folders at this level
    val files = mutableStateListOf<RawFile>()         // .CR3 files at this level
    var isFileView by mutableStateOf(false)
        private set
    var browsing by mutableStateOf(false)
        private set
    var browseError by mutableStateOf<String?>(null)
        private set
    // Sum of all RAW files across the camera, resolved once at root level.
    var totalCameraImages by mutableStateOf<Int?>(null)
        private set

    var page by mutableStateOf(1)
        private set
    var pageCount by mutableStateOf(1)
        private set
    var totalContents by mutableStateOf(0)
        private set
    val hasMore: Boolean get() = page < pageCount

    // Item count per folder path (?kind=number). Absent = still loading,
    // -1 = unavailable. Used for the count + share bar in the folder list.
    val folderCounts: SnapshotStateMap<String, Int> = mutableStateMapOf()

    // -- selection (keyed by download url, persists across pages/folders) --
    val selected: SnapshotStateMap<String, FileTask> = mutableStateMapOf()

    // -- full-screen preview (null = closed) --
    var previewFile by mutableStateOf<RawFile?>(null)

    // -- grid density (pinch-to-zoom changes the column count) -------------
    var gridColumns by mutableStateOf(2)
        private set
    // Once the user pinch-adjusts, stop applying the width-derived default
    // (e.g. on rotation) so their choice sticks.
    private var columnsManual = false

    /** Pinch-to-zoom column change (user-driven). */
    fun setGridColumnCount(count: Int) {
        gridColumns = count.coerceIn(1, MAX_GRID_COLUMNS)
        columnsManual = true
    }

    /** Width-derived default density; ignored once the user has pinched. */
    fun applyAutoColumns(count: Int) {
        if (!columnsManual) gridColumns = count.coerceIn(1, MAX_GRID_COLUMNS)
    }

    // -- filtering (applies to the loaded page in the photo grid) ----------
    // Set of star ratings to show (0 = unrated). Empty = show everything.
    var ratingFilter by mutableStateOf<Set<Int>>(emptySet())
        private set
    // When true, only show photos already selected for download.
    var selectedOnly by mutableStateOf(false)

    val isFiltering: Boolean get() = ratingFilter.isNotEmpty() || selectedOnly

    // -- sorting ----------------------------------------------------------
    enum class SortKey(val label: String) { NAME("Name"), DATE("Capture date"), SIZE("Size") }

    var sortKey by mutableStateOf(SortKey.DATE)
        private set
    var sortAscending by mutableStateOf(true)
        private set

    fun setSort(key: SortKey) { sortKey = key }
    fun toggleSortDirection() { sortAscending = !sortAscending }

    /** Files visible after applying the active filters, then the active sort. */
    val visibleFiles: List<RawFile>
        get() {
            val filtered = files.filter { f ->
                (ratingFilter.isEmpty() || (f.rating ?: 0) in ratingFilter) &&
                    (!selectedOnly || selected.containsKey(f.url))
            }
            val comparator: Comparator<RawFile> = when (sortKey) {
                SortKey.NAME -> compareBy { it.name.lowercase() }
                SortKey.DATE -> compareBy { parseModified(it.modified) }
                SortKey.SIZE -> compareBy { it.size ?: -1L }
            }
            val sorted = filtered.sortedWith(comparator)
            return if (sortAscending) sorted else sorted.reversed()
        }

    /** Parse CCAPI's RFC-1123 lastmodifieddate to epoch seconds; unknown sorts first. */
    private fun parseModified(s: String?): Long {
        if (s.isNullOrBlank()) return Long.MIN_VALUE
        return try {
            ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond()
        } catch (e: Exception) {
            Long.MIN_VALUE
        }
    }

    fun toggleRatingFilter(rating: Int) {
        ratingFilter = if (rating in ratingFilter) ratingFilter - rating else ratingFilter + rating
    }

    fun clearFilters() {
        ratingFilter = emptySet()
        selectedOnly = false
    }

    // -- destination ------------------------------------------------------
    var destinationUri by mutableStateOf<Uri?>(null)
        private set
    var destinationLabel by mutableStateOf("")
        private set

    private val client: CcapiClient? get() = CameraSession.client

    init {
        // Auto-connect once when the app launches, using the default settings.
        // If the camera isn't reachable yet, the user can retry from the screen.
        connect()
    }

    // -- connect ----------------------------------------------------------

    fun connect() {
        if (connecting) return
        connecting = true
        connectionError = null
        viewModelScope.launch {
            try {
                // Pin the app to the camera's (internet-less) Wi-Fi first.
                CameraNetwork.bindToCameraWifi(getApplication())

                // Note: we deliberately do NOT pass the network to the client
                // (no per-socket socketFactory bind). Routing is handled by the
                // process-wide bindProcessToNetwork in CameraNetwork, because
                // per-socket Network.bindSocket throws EPERM on some devices.
                val c = CcapiClient(
                    host = host.trim(),
                    port = port.trim().toIntOrNull() ?: 8080,
                    username = username.trim().ifEmpty { null },
                    password = password.ifEmpty { null },
                )
                val name = withContext(Dispatchers.IO) {
                    c.getRoot() // verifies /ccapi + primes endpoint discovery
                    val info = c.getDeviceInformation()
                    info["productname"] ?: "camera"
                }
                CameraSession.client = c
                CameraSession.deviceName = name
                deviceName = name
                connected = true
                navigateToRoot()
            } catch (e: CcapiException) {
                connectionError = e.message
                connected = false
            } catch (e: Exception) {
                connectionError = e.message ?: "Unexpected error"
                connected = false
            } finally {
                connecting = false
            }
        }
    }

    fun disconnect() {
        CameraSession.clear()
        CameraNetwork.unbind(getApplication())
        connected = false
        deviceName = null
        totalCameraImages = null
        folders.clear()
        files.clear()
        selected.clear()
        breadcrumbs.clear()
        breadcrumbs.add(Crumb("Storage", null))
    }

    // -- browsing ---------------------------------------------------------

    private fun navigateToRoot() {
        breadcrumbs.clear()
        breadcrumbs.add(Crumb("Storage", null))
        // On bootup, skip straight past trivial single-folder levels (e.g.
        // Storage → DCIM → 100CANON) so the user lands on the photos.
        loadLevel(null, autoDescend = true)
    }

    /** Enter a child path, pushing a breadcrumb. */
    fun open(path: String) {
        val label = path.trimEnd('/').substringAfterLast('/')
        breadcrumbs.add(Crumb(label, path))
        loadLevel(path)
    }

    /** Jump back to a breadcrumb (truncates the trail). */
    fun goToCrumb(index: Int) {
        if (index !in breadcrumbs.indices) return
        val target = breadcrumbs[index]
        while (breadcrumbs.size > index + 1) breadcrumbs.removeAt(breadcrumbs.size - 1)
        loadLevel(target.path)
    }

    /**
     * Load a level. When [autoDescend] is set (bootup only), keep descending
     * while a level holds exactly one sub-folder and no photos — pushing a
     * breadcrumb each step — so deep, single-child storage trees open straight
     * to the photos. Manual navigation never auto-descends.
     */
    private fun loadLevel(path: String?, requestedPage: Int = 1, autoDescend: Boolean = false) {
        browsing = true
        browseError = null
        viewModelScope.launch {
            try {
                val c = client ?: throw CcapiException("Not connected")
                var currentPath = path
                var pageToLoad = requestedPage
                while (true) {
                    val p = currentPath
                    withContext(Dispatchers.IO) {
                        if (p == null) {
                            // Root: storage containers (always shown, never dropped).
                            val storages = c.listStorage()
                            postResolvedFolders(c, storages, dropEmpty = false)
                        } else {
                            // Decide: directory of files, or container of sub-folders?
                            val children = c.listFolder(p)
                            val looksLikeFiles = children.any { it.substringAfterLast('/').contains('.') }
                            if (looksLikeFiles) {
                                val result = c.listRawFiles(p, pageToLoad)
                                postFiles(result.files, result.page, result.pageCount, result.totalContents)
                            } else {
                                postResolvedFolders(c, children, dropEmpty = true)
                            }
                        }
                    }
                    // A folder view with a single sub-folder is just a passthrough.
                    if (autoDescend && !isFileView && folders.size == 1) {
                        val child = folders[0]
                        breadcrumbs.add(Crumb(child.trimEnd('/').substringAfterLast('/'), child))
                        currentPath = child
                        pageToLoad = 1
                        continue
                    }
                    break
                }
            } catch (e: CcapiException) {
                browseError = e.message
            } catch (e: Exception) {
                browseError = e.message ?: "Failed to list folder"
            } finally {
                browsing = false
            }
        }
    }

    /**
     * Resolve every folder's item count concurrently, then publish the level in
     * a single atomic snapshot. Resolving up front means:
     *  - empty sub-folders are filtered out *before* anything is shown, so they
     *    never flash into view and then vanish, and
     *  - intermediate folders (sub-folders only, 0 direct files) get a concrete
     *    "no count" marker instead of being left in a perpetual loading state.
     *
     * @param dropEmpty omit folders with no files and no sub-folders (used
     *   inside the card). Storage roots pass false so they are always shown.
     */
    private suspend fun postResolvedFolders(c: CcapiClient, paths: List<String>, dropEmpty: Boolean) {
        // count: file count to display (>0), or -1 = "no count / intermediate".
        data class Resolved(val path: String, val count: Int, val keep: Boolean)

        val resolved = coroutineScope {
            paths.map { p ->
                async(Dispatchers.IO) {
                    val n = try { c.folderPageCount(p).second } catch (e: Exception) { -1 }
                    when {
                        n > 0 -> Resolved(p, count = n, keep = true)
                        // Storages are always shown — no need to probe children.
                        !dropEmpty -> Resolved(p, count = -1, keep = true)
                        // 0 files inside the card: keep only if it has sub-folders.
                        else -> {
                            val hasChildren = try { c.listFolder(p).isNotEmpty() } catch (e: Exception) { true }
                            Resolved(p, count = -1, keep = hasChildren)
                        }
                    }
                }
            }.awaitAll()
        }
        val visible = resolved.filter { it.keep }

        // Sum concrete counts (>0) to get the camera-wide total image count.
        val knownTotal = visible.sumOf { if (it.count > 0) it.count else 0 }
        if (knownTotal > 0) totalCameraImages = knownTotal

        // One atomic snapshot so the UI never observes a half-updated level.
        Snapshot.withMutableSnapshot {
            isFileView = false
            files.clear()
            page = 1; pageCount = 1; totalContents = 0
            folderCounts.clear()
            visible.forEach { folderCounts[it.path] = it.count }
            folders.clear()
            folders.addAll(visible.map { it.path })
        }
    }

    private fun postFiles(list: List<RawFile>, p: Int, pc: Int, total: Int) {
        isFileView = true
        files.clear()
        files.addAll(list)
        folders.clear()
        page = p; pageCount = pc; totalContents = total
    }

    /** Reload the current level (pull-to-refresh / retry). */
    fun refresh() {
        loadLevel(breadcrumbs.last().path, page)
    }

    fun nextPage() {
        if (hasMore) loadLevel(breadcrumbs.last().path, page + 1)
    }

    fun prevPage() {
        if (page > 1) loadLevel(breadcrumbs.last().path, page - 1)
    }

    // -- selection --------------------------------------------------------

    fun toggle(file: RawFile) {
        if (selected.containsKey(file.url)) selected.remove(file.url)
        else selected[file.url] = file.toTask()
    }

    fun toggleAllOnPage(check: Boolean) {
        files.forEach { f ->
            if (check) selected[f.url] = f.toTask() else selected.remove(f.url)
        }
    }

    fun clearSelection() = selected.clear()

    // -- destination & download ------------------------------------------

    fun setDestination(uri: Uri, label: String) {
        destinationUri = uri
        destinationLabel = label
    }

    fun startDownload() {
        val dest = destinationUri ?: return
        if (selected.isEmpty()) return
        DownloadController.start(
            getApplication(),
            DownloadRequestData(
                destinationTree = dest,
                destinationLabel = destinationLabel,
                files = selected.values.toList(),
            ),
        )
    }

    fun cancelDownload() = DownloadController.cancel()

    private fun RawFile.toTask() = FileTask(name = name, url = url, folder = folder, size = size)
}
