package com.rawr.ccapi.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rawr.ccapi.local.LocalRawAccess
import com.rawr.ccapi.local.LocalRawPhoto
import com.rawr.ccapi.local.LocalRawStore
import kotlinx.coroutines.launch

/** Upper bound on grid columns (pinch-to-zoom + width-derived default). */
private const val MAX_GRID_COLUMNS = 10

/**
 * State for the local RAW viewer ("View" tab): broad-file-access permission,
 * the list of `.CR3` files found on the phone, plus sort + rating filter
 * (mirroring the CCAPI grid). Fully independent of the camera connection.
 */
class LocalViewerViewModel(app: Application) : AndroidViewModel(app) {

    var hasAccess by mutableStateOf(LocalRawAccess.isGranted(app))
        private set
    var scanning by mutableStateOf(false)
        private set
    var hasScanned by mutableStateOf(false)
        private set

    private val photos = mutableStateListOf<LocalRawPhoto>()

    // Full-screen preview target (null = grid).
    var previewPhoto by mutableStateOf<LocalRawPhoto?>(null)

    var gridColumns by mutableStateOf(3)
        private set
    // Once the user pinch-adjusts, stop applying the width-derived default.
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

    // -- sorting (mirrors MainViewModel.SortKey) --------------------------
    enum class SortKey(val label: String) { NAME("Name"), DATE("Date"), SIZE("Size") }

    var sortKey by mutableStateOf(SortKey.DATE)
        private set
    var sortAscending by mutableStateOf(false) // newest first by default
        private set

    fun setSort(key: SortKey) { sortKey = key }
    fun toggleSortDirection() { sortAscending = !sortAscending }

    // -- filtering --------------------------------------------------------
    // Set of star ratings to show (0 = unrated). Empty = show everything.
    var ratingFilter by mutableStateOf<Set<Int>>(emptySet())
        private set

    val isFiltering: Boolean get() = ratingFilter.isNotEmpty()

    fun toggleRatingFilter(rating: Int) {
        ratingFilter = if (rating in ratingFilter) ratingFilter - rating else ratingFilter + rating
    }

    fun clearFilters() { ratingFilter = emptySet() }

    val total: Int get() = photos.size

    /** Files after the active rating filter, then the active sort. */
    val visiblePhotos: List<LocalRawPhoto>
        get() {
            val filtered = photos.filter { ratingFilter.isEmpty() || it.rating in ratingFilter }
            val comparator: Comparator<LocalRawPhoto> = when (sortKey) {
                SortKey.NAME -> compareBy { it.name.lowercase() }
                SortKey.DATE -> compareBy { it.captureTime ?: it.lastModified }
                SortKey.SIZE -> compareBy { it.size }
            }
            val sorted = filtered.sortedWith(comparator)
            return if (sortAscending) sorted else sorted.reversed()
        }

    /**
     * Re-check the permission (e.g. after returning from the Settings screen)
     * and kick off an initial scan once it's granted. Safe to call repeatedly.
     */
    fun refreshAccess() {
        hasAccess = LocalRawAccess.isGranted(getApplication())
        if (hasAccess && !hasScanned && !scanning) rescan()
    }

    fun rescan() {
        if (scanning) return
        if (!LocalRawAccess.isGranted(getApplication())) {
            hasAccess = false
            return
        }
        hasAccess = true
        scanning = true
        viewModelScope.launch {
            try {
                val found = LocalRawStore.scan(getApplication())
                photos.clear()
                photos.addAll(found)
            } finally {
                scanning = false
                hasScanned = true
            }
        }
    }
}
