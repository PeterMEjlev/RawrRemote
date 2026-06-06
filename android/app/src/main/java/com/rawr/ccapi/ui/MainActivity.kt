@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.rawr.ccapi.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.rawr.ccapi.download.DownloadController
import com.rawr.ccapi.download.DownloadUiState
import com.rawr.ccapi.download.FileStatus
import com.rawr.ccapi.download.JobStatus
import com.rawr.ccapi.net.RawFile
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()
    private val controlVm: ControlViewModel by viewModels()
    private val localVm: LocalViewerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveFullscreen()
        setContent { RawrTheme { MainShell(vm, controlVm, localVm) } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Draw edge-to-edge and hide the status + navigation bars; they reappear
     *  transiently when the user swipes in from an edge. */
    private fun enableImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Re-hide the bars after dialogs/permission prompts steal focus.
        if (hasFocus) enableImmersiveFullscreen()
    }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }
}

@Composable
private fun RawrTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(color = MaterialTheme.colorScheme.background) { content() }
    }
}

@Composable
private fun AppRoot(vm: MainViewModel) {
    if (vm.connected) BrowseScreen(vm) else ConnectScreen(vm)
}

// --- bottom-nav shell ------------------------------------------------------

private enum class Tab(val label: String, val icon: ImageVector) {
    IMPORT("Download", CameraTabIcon),    // connect + download
    CONTROL("Control", ControlTabIcon), // live view + shutter
    VIEW("View", GalleryTabIcon),       // local .CR3 viewer + share to Lightroom
}

/**
 * Hosts the two top-level pages and the bottom navigation that switches between
 * them. The active page is laid out above the bar in a Column (rather than a
 * Scaffold bottomBar) so each page keeps its own internal Scaffold/insets
 * untouched. The "View" page is independent of the camera connection.
 */
@Composable
private fun MainShell(vm: MainViewModel, controlVm: ControlViewModel, localVm: LocalViewerViewModel) {
    var selected by rememberSaveable { mutableStateOf(Tab.IMPORT.ordinal) }
    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val landscape = maxWidth > maxHeight
            if (landscape) {
                Row(Modifier.fillMaxSize()) {
                    NavigationRail {
                        Tab.entries.forEach { tab ->
                            NavigationRailItem(
                                selected = selected == tab.ordinal,
                                onClick = { selected = tab.ordinal },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        MainPage(Tab.entries[selected], vm, controlVm, localVm)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        MainPage(Tab.entries[selected], vm, controlVm, localVm)
                    }
                    NavigationBar {
                        Tab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = selected == tab.ordinal,
                                onClick = { selected = tab.ordinal },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            }
        }
        // Full-screen photo previews render here, above the pages AND the bottom
        // nav, as overlays inside the activity's edge-to-edge window — so they
        // fill the whole screen and their bottom button never clips off the edge.
        vm.previewFile?.let { file ->
            val files = vm.visibleFiles
            val startIndex = files.indexOfFirst { it.url == file.url }
            PreviewOverlay(vm, if (startIndex >= 0) files else listOf(file), startIndex.coerceAtLeast(0))
        }
        localVm.previewPhoto?.let { photo ->
            val photos = localVm.visiblePhotos
            LocalPreviewOverlay(localVm, photos, photos.indexOf(photo))
        }
    }
}

@Composable
private fun MainPage(tab: Tab, vm: MainViewModel, controlVm: ControlViewModel, localVm: LocalViewerViewModel) {
    when (tab) {
        Tab.IMPORT -> AppRoot(vm)
        Tab.CONTROL -> ControlScreen(controlVm, connected = vm.connected)
        Tab.VIEW -> LocalViewerScreen(localVm)
    }
}

// --- connect screen --------------------------------------------------------

@Composable
private fun ConnectScreen(vm: MainViewModel) {
    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        // Cap the form width so it stays a tidy centred card on tablets.
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp),
        ) {
            Text("Rawr Remote", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Join the camera's Wi-Fi, then connect. Make sure any VPN / ad-blocker is off.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = vm.host, onValueChange = { vm.host = it },
                    label = { Text("Camera host / IP") }, singleLine = true,
                    modifier = Modifier.weight(2f),
                )
                OutlinedTextField(
                    value = vm.port, onValueChange = { vm.port = it },
                    label = { Text("Port") }, singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Button(
                onClick = { vm.connect() },
                enabled = !vm.connecting && vm.host.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (vm.connecting) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Connecting…")
                } else {
                    Text("Connect")
                }
            }
            vm.connectionError?.let {
                Text("Connection failed: $it", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

// --- browse screen ---------------------------------------------------------

@Composable
private fun BrowseScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val job by DownloadController.state.collectAsState()
    var showDownloads by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            vm.setDestination(uri, uri.lastPathSegment ?: uri.toString())
        }
    }

    // Open the downloads sheet automatically when a batch starts.
    LaunchedEffect(job.status) { if (job.status == JobStatus.RUNNING) showDownloads = true }

    Scaffold(
        topBar = { BrowseTopBar(vm, onFilter = { showFilter = true }) },
        bottomBar = {
            SelectionBar(
                vm, job,
                onPickFolder = { pickFolder.launch(null) },
                onShowDownloads = { showDownloads = true },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Breadcrumbs(vm)
            PullToRefreshBox(
                isRefreshing = vm.browsing,
                onRefresh = { vm.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    vm.browseError != null -> CenteredMessage(vm.browseError!!, "Retry") { vm.refresh() }
                    vm.isFileView && vm.files.isEmpty() && !vm.browsing ->
                        CenteredMessage("No .CR3 files here.")
                    vm.isFileView && vm.visibleFiles.isEmpty() && !vm.browsing ->
                        CenteredMessage("No photos match the filter.", "Clear filters") { vm.clearFilters() }
                    !vm.isFileView && vm.folders.isEmpty() && !vm.browsing ->
                        CenteredMessage("This folder is empty.")
                    vm.isFileView -> PhotoGrid(vm)
                    else -> FolderList(vm)
                }
            }
        }
    }

    if (showDownloads && job.files.isNotEmpty()) {
        DownloadsSheet(vm, job) { showDownloads = false }
    }
    if (showFilter) {
        FilterSheet(vm) { showFilter = false }
    }
    // The full-screen preview is rendered by MainShell (above the bottom nav),
    // not here, so it covers the whole screen.
}

@Composable
private fun BrowseTopBar(vm: MainViewModel, onFilter: () -> Unit) {
    TopAppBar(
        title = { Text(vm.deviceName ?: "Camera", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        actions = {
            vm.totalCameraImages?.let { total ->
                Text(
                    "$total images",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            // Filter + sort are only meaningful in the photo grid.
            if (vm.isFileView) {
                IconButton(onClick = onFilter) {
                    Icon(
                        FilterListIcon,
                        contentDescription = "Filter",
                        tint = if (vm.isFiltering) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
                SortMenu(vm)
            }
        },
    )
}

@Composable
private fun SortMenu(vm: MainViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(SortIcon, contentDescription = "Sort", tint = MaterialTheme.colorScheme.onSurface)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MainViewModel.SortKey.entries.forEach { key ->
                DropdownMenuItem(
                    text = { Text(key.label) },
                    onClick = { vm.setSort(key); expanded = false },
                    leadingIcon = {
                        if (vm.sortKey == key) Icon(Icons.Default.Check, contentDescription = null)
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(if (vm.sortAscending) "Ascending" else "Descending") },
                onClick = { vm.toggleSortDirection() }, // keep open so the choice is visible
                leadingIcon = {
                    Icon(
                        if (vm.sortAscending) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                    )
                },
            )
        }
    }
}

@Composable
private fun Breadcrumbs(vm: MainViewModel) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        vm.breadcrumbs.forEachIndexed { i, crumb ->
            TextButton(onClick = { vm.goToCrumb(i) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                Text(crumb.label, maxLines = 1)
            }
            if (i < vm.breadcrumbs.lastIndex) Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// --- folder list -----------------------------------------------------------

@Composable
private fun FolderList(vm: MainViewModel) {
    // Total across loaded folders, for each folder's share-of-card bar.
    val total = vm.folderCounts.values.filter { it >= 0 }.sum()
    LazyColumn(Modifier.fillMaxSize()) {
        items(vm.folders, key = { it }) { path ->
            FolderRow(
                name = path.trimEnd('/').substringAfterLast('/'),
                count = vm.folderCounts[path],
                total = total,
                onClick = { vm.open(path) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun FolderRow(name: String, count: Int?, total: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("📁", modifier = Modifier.padding(end = 12.dp))
        Text(
            name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        when {
            count == null ->
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            count >= 0 -> {
                val frac = (if (total > 0) count.toFloat() / total else 0f).coerceIn(0f, 1f)
                Text(formatCount(count), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier.width(56.dp).height(6.dp).clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        Modifier.fillMaxHeight().fillMaxWidth(frac)
                            .clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.primary),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "${(frac * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(36.dp),
                )
            }
            // count < 0: count unavailable — show nothing.
        }
    }
}

private fun formatCount(n: Int): String = String.format(Locale.getDefault(), "%,d", n)

// --- photo grid ------------------------------------------------------------

@Composable
private fun PhotoGrid(vm: MainViewModel) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Default density scales with screen width (~200dp/cell ⇒ 2 columns on a
        // phone, more on a tablet); pinch-to-zoom still overrides it.
        val autoColumns = (maxWidth.value / 200f).roundToInt().coerceIn(1, 10)
        LaunchedEffect(autoColumns) { vm.applyAutoColumns(autoColumns) }
        // The cell's real on-screen pixel width (grid width minus the 8dp content
        // padding each side and the 8dp gaps between columns). Thumbnails decode
        // to this size, so they never end up larger than what's actually drawn.
        val columns = vm.gridColumns
        val cellWidthPx = with(LocalDensity.current) {
            ((maxWidth - 16.dp - 8.dp * (columns - 1)) / columns).toPx()
        }.roundToInt().coerceAtLeast(1)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize().pinchToZoomColumns(
                current = { vm.gridColumns },
                onChange = { vm.setGridColumnCount(it) },
            ),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            gridItems(vm.visibleFiles, key = { it.url }, contentType = { "photo" }) { f -> PhotoCell(vm, f, cellWidthPx) }
            if (vm.pageCount > 1) {
                item(span = { GridItemSpan(maxLineSpan) }) { Pager(vm) }
            }
        }
    }
}

/**
 * Two-finger pinch to change the grid column count, without stealing one-finger
 * vertical scrolling from the grid: we only consume events while ≥2 pointers are
 * down. Spreading apart (zoom > 1) means bigger thumbnails → fewer columns.
 */
internal fun Modifier.pinchToZoomColumns(
    current: () -> Int,
    onChange: (Int) -> Unit,
): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var accum = 1f
        do {
            val event = awaitPointerEvent()
            if (event.changes.size >= 2) {
                accum *= event.calculateZoom()
                when {
                    accum > 1.18f -> { onChange(current() - 1); accum = 1f }
                    accum < 0.85f -> { onChange(current() + 1); accum = 1f }
                }
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}

@Composable
private fun PhotoCell(vm: MainViewModel, f: RawFile, cellWidthPx: Int) {
    val checked = vm.selected.containsKey(f.url)
    val border = if (checked) {
        BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }
    // Shrink the selection badge as the grid zooms out (more, smaller cells).
    // 2 columns = full size; never smaller than half.
    val badgeScale = (2f / vm.gridColumns).coerceIn(0.5f, 1f)
    Card(
        modifier = Modifier.clickable { vm.previewFile = f },
        border = border,
        shape = RoundedCornerShape(10.dp),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(3f / 2f)) {
                GridThumb(f.path, cellWidthPx = cellWidthPx, modifier = Modifier.fillMaxSize())
                // Selection badge. The Checkbox consumes its own taps, so
                // tapping it toggles selection while tapping the rest of the
                // cell opens the full-screen preview.
                Box(
                    Modifier.align(Alignment.TopEnd)
                        .graphicsLayer {
                            scaleX = badgeScale
                            scaleY = badgeScale
                            transformOrigin = TransformOrigin(1f, 0f) // pin to top-right
                        }
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.35f)),
                ) {
                    Checkbox(checked = checked, onCheckedChange = { vm.toggle(f) })
                }
                // Star rating badge (only when the camera reports one).
                f.rating?.takeIf { it > 0 }?.let { stars ->
                    Text(
                        "★$stars",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.45f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Text(
                f.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun Pager(vm: MainViewModel) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = { vm.prevPage() }, enabled = vm.page > 1) { Text("← Prev") }
        Text("Page ${vm.page} / ${vm.pageCount}")
        OutlinedButton(onClick = { vm.nextPage() }, enabled = vm.hasMore) { Text("Next →") }
    }
}

// --- bottom selection bar --------------------------------------------------

@Composable
private fun SelectionBar(
    vm: MainViewModel,
    job: DownloadUiState,
    onPickFolder: () -> Unit,
    onShowDownloads: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (job.files.isNotEmpty()) {
                val done = job.files.count { it.status == FileStatus.DONE }
                val total = job.files.size
                Row(
                    Modifier.fillMaxWidth().clickable { onShowDownloads() },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (job.status == JobStatus.RUNNING) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    }
                    Text(
                        when (job.status) {
                            JobStatus.RUNNING -> "Downloading $done/$total — tap for details"
                            JobStatus.DONE -> "Downloaded $done/$total — tap for details"
                            JobStatus.ERROR -> "Finished with errors — tap for details"
                            JobStatus.CANCELLED -> "Cancelled — tap for details"
                            else -> ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPickFolder) {
                    Text(if (vm.destinationUri != null) "📁 ${vm.destinationLabel}" else "Choose folder", maxLines = 1)
                }
                Spacer(Modifier.weight(1f))
                if (vm.selected.isNotEmpty()) {
                    TextButton(
                        onClick = { vm.clearSelection() },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text("Clear")
                    }
                }
                Text("${vm.selected.size} selected", style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = { vm.startDownload() },
                    enabled = vm.destinationUri != null && vm.selected.isNotEmpty() && job.status != JobStatus.RUNNING,
                ) {
                    Text("Download")
                }
            }
        }
    }
}

// --- downloads sheet -------------------------------------------------------

@Composable
private fun DownloadsSheet(vm: MainViewModel, job: DownloadUiState, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        KeepImmersive()
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Downloads", style = MaterialTheme.typography.titleLarge)
            Text("${job.status} → ${job.destinationLabel}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            job.files.forEach { f ->
                val pct = if (f.size != null && f.size > 0) (f.downloaded * 100 / f.size).toInt() else null
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(f.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium)
                        Text(f.status.name.lowercase(Locale.ROOT), style = MaterialTheme.typography.labelSmall)
                    }
                    if (f.status == FileStatus.DOWNLOADING) {
                        if (pct != null) {
                            LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        Text(
                            formatSize(f.downloaded) + (f.size?.let { " / ${formatSize(it)} ($pct%)" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    f.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (job.status == JobStatus.RUNNING) {
                    OutlinedButton(onClick = { vm.cancelDownload() }) { Text("Cancel") }
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}

// --- filter sheet ----------------------------------------------------------

@Composable
private fun FilterSheet(vm: MainViewModel, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        KeepImmersive()
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filter photos", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (vm.isFiltering) {
                    TextButton(onClick = { vm.clearFilters() }) { Text("Clear all") }
                }
            }

            Text("Rating", style = MaterialTheme.typography.titleSmall)
            Text(
                "Show photos with these ratings. Nothing selected shows everything.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (r in 0..5) {
                    FilterChip(
                        selected = r in vm.ratingFilter,
                        onClick = { vm.toggleRatingFilter(r) },
                        label = { Text(if (r == 0) "Unrated" else "★".repeat(r)) },
                    )
                }
            }

            HorizontalDivider()

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Selected only", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Show only photos already selected for download.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = vm.selectedOnly, onCheckedChange = { vm.selectedOnly = it })
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${vm.visibleFiles.size} of ${vm.files.size} shown",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).align(Alignment.CenterVertically),
                )
                Button(onClick = onDismiss) { Text("Done") }
            }
        }
    }
}

// --- full-screen preview ---------------------------------------------------

@Composable
private fun PreviewOverlay(vm: MainViewModel, f: RawFile) {
    val checked = vm.selected.containsKey(f.url)
    // Rendered as a full-screen overlay inside the activity's own edge-to-edge,
    // immersive window — NOT a Dialog. A Dialog gets a separate window sized to
    // wrap/centre its content, which left the photo vertically offset and clipped
    // the bottom button off-screen. Dismiss on system back, like a dialog would.
    BackHandler { vm.previewFile = null }

    // Pinch-to-zoom + pan, and swipe-down-to-dismiss; reset per photo.
    var scale by remember(f.path) { mutableStateOf(1f) }
    var offset by remember(f.path) { mutableStateOf(Offset.Zero) }
    var dismissDrag by remember(f.path) { mutableStateOf(0f) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    // Fade the backdrop as the photo is dragged down toward dismissal.
    val backdropAlpha = (1f - (dismissDrag / 900f)).coerceIn(0.4f, 1f)

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = backdropAlpha))) {
        Box(
            Modifier.fillMaxSize()
                .onSizeChanged { boxSize = it }
                .pointerInput(f.path) {
                    val dismissThreshold = 140.dp.toPx()
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var zoomed = false
                        var armed = false
                        var totalDy = 0f
                        do {
                            val event = awaitPointerEvent()
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (event.changes.size >= 2 || scale > 1f) {
                                // Zoom / pan an already-zoomed (or pinching) image.
                                zoomed = true
                                val newScale = (scale * zoom).coerceIn(1f, 5f)
                                val maxX = (boxSize.width * (newScale - 1f)) / 2f
                                val maxY = (boxSize.height * (newScale - 1f)) / 2f
                                offset = if (newScale > 1f) {
                                    Offset(
                                        (offset.x + pan.x).coerceIn(-maxX, maxX),
                                        (offset.y + pan.y).coerceIn(-maxY, maxY),
                                    )
                                } else {
                                    Offset.Zero
                                }
                                scale = newScale
                                event.changes.forEach { it.consume() }
                            } else {
                                // Not zoomed: track a downward drag to dismiss.
                                // The touch-slop guard leaves taps/double-taps alone.
                                totalDy += pan.y
                                if (!armed && totalDy > slop) armed = true
                                if (armed) {
                                    dismissDrag = (totalDy - slop).coerceAtLeast(0f)
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        } while (event.changes.any { it.pressed })

                        // Gesture ended: close if dragged far enough, else snap back.
                        if (!zoomed && armed && dismissDrag > dismissThreshold) {
                            vm.previewFile = null
                        } else {
                            dismissDrag = 0f
                        }
                    }
                }
                .pointerInput(f.path) {
                    detectTapGestures(onDoubleTap = {
                        if (scale > 1f) { scale = 1f; offset = Offset.Zero } else { scale = 2.5f }
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            FullImage(
                f.path,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y + dismissDrag
                },
            )
        }

        Row(
            Modifier.fillMaxWidth().align(Alignment.TopCenter)
                .background(Color.Black.copy(alpha = 0.5f))
                // Keep the title/close clear of the status bar / display cutout.
                .statusBarsPadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(f.name, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { vm.previewFile = null }) { Text("Close") }
        }

        Button(
            onClick = { vm.toggle(f) },
            // navigationBarsPadding tracks the bar if it transiently reappears;
            // the base padding keeps the button clear of the screen edge.
            modifier = Modifier.align(Alignment.BottomCenter)
                .navigationBarsPadding().padding(horizontal = 24.dp, vertical = 28.dp),
        ) {
            Text(if (checked) "✓ Selected — tap to deselect" else "Select for download")
        }
    }
}

@Composable
private fun PreviewOverlay(vm: MainViewModel, files: List<RawFile>, startIndex: Int) {
    if (files.isEmpty()) return
    BackHandler { vm.previewFile = null }

    val pagerState = rememberPagerState(initialPage = startIndex.coerceIn(0, files.lastIndex)) { files.size }
    val current = files.getOrNull(pagerState.currentPage) ?: files.first()
    val checked = vm.selected.containsKey(current.url)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
        ) { page ->
            ZoomableImportPhotoPage(file = files[page], onDismiss = { vm.previewFile = null })
        }

        Row(
            Modifier.fillMaxWidth().align(Alignment.TopCenter)
                .background(Color.Black.copy(alpha = 0.5f))
                .statusBarsPadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    current.name,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "${pagerState.currentPage + 1} / ${files.size}",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            TextButton(onClick = { vm.previewFile = null }) { Text("Close") }
        }

        Button(
            onClick = { vm.toggle(current) },
            modifier = Modifier.align(Alignment.BottomCenter)
                .navigationBarsPadding().padding(horizontal = 24.dp, vertical = 28.dp),
        ) {
            Text(if (checked) "âœ“ Selected â€” tap to deselect" else "Select for download")
        }
    }
}

@Composable
private fun ZoomableImportPhotoPage(file: RawFile, onDismiss: () -> Unit) {
    var scale by remember(file.path) { mutableStateOf(1f) }
    var offset by remember(file.path) { mutableStateOf(Offset.Zero) }
    var dismissDrag by remember(file.path) { mutableStateOf(0f) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { boxSize = it }
            .pointerInput(file.path) {
                val dismissThreshold = 140.dp.toPx()
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var zoomed = false
                    var armed = false
                    var totalDy = 0f
                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (event.changes.size >= 2 || scale > 1f) {
                            zoomed = true
                            val newScale = (scale * zoom).coerceIn(1f, 5f)
                            val maxX = (boxSize.width * (newScale - 1f)) / 2f
                            val maxY = (boxSize.height * (newScale - 1f)) / 2f
                            offset = if (newScale > 1f) {
                                Offset(
                                    (offset.x + pan.x).coerceIn(-maxX, maxX),
                                    (offset.y + pan.y).coerceIn(-maxY, maxY),
                                )
                            } else {
                                Offset.Zero
                            }
                            scale = newScale
                            event.changes.forEach { it.consume() }
                        } else {
                            totalDy += pan.y
                            if (!armed && totalDy > slop) armed = true
                            if (armed) {
                                dismissDrag = (totalDy - slop).coerceAtLeast(0f)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (!zoomed && armed && dismissDrag > dismissThreshold) {
                        onDismiss()
                    } else {
                        dismissDrag = 0f
                    }
                }
            }
            .pointerInput(file.path) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) { scale = 1f; offset = Offset.Zero } else { scale = 2.5f }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        FullImage(
            file.path,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y + dismissDrag
            },
        )
    }
}

// --- helpers ---------------------------------------------------------------

@Composable
internal fun CenteredMessage(message: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (actionLabel != null && onAction != null) {
                OutlinedButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

private fun formatSize(bytes: Long?): String {
    if (bytes == null) return "—"
    val units = arrayOf("B", "KB", "MB", "GB")
    var n = bytes.toDouble()
    var i = 0
    while (n >= 1024 && i < units.size - 1) { n /= 1024; i++ }
    return if (i == 0) "$bytes B" else String.format(Locale.ROOT, "%.1f %s", n, units[i])
}

/**
 * Keep the app's immersive fullscreen while a Dialog / BottomSheet is showing.
 * Those live in their own window, so the activity's bar-hiding doesn't reach
 * them and the status + navigation bars otherwise reappear over the sheet.
 */
@Composable
internal fun KeepImmersive() {
    val view = LocalView.current
    LaunchedEffect(view) {
        ViewCompat.getWindowInsetsController(view)?.apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

// --- inline icons (avoids pulling in the heavy material-icons-extended dep) --

/** Material "filter_list" glyph: three centered, decreasing bars. */
internal val FilterListIcon: ImageVector = ImageVector.Builder(
    name = "FilterList",
    defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f,
).path(fill = SolidColor(Color.White)) {
    moveTo(3f, 6f); lineTo(21f, 6f); lineTo(21f, 8f); lineTo(3f, 8f); close()
    moveTo(6f, 11f); lineTo(18f, 11f); lineTo(18f, 13f); lineTo(6f, 13f); close()
    moveTo(10f, 16f); lineTo(14f, 16f); lineTo(14f, 18f); lineTo(10f, 18f); close()
}.build()

/** Material "sort" glyph: three left-aligned, decreasing bars. */
internal val SortIcon: ImageVector = ImageVector.Builder(
    name = "Sort",
    defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f,
).path(fill = SolidColor(Color.White)) {
    moveTo(3f, 6f); lineTo(21f, 6f); lineTo(21f, 8f); lineTo(3f, 8f); close()
    moveTo(3f, 11f); lineTo(15f, 11f); lineTo(15f, 13f); lineTo(3f, 13f); close()
    moveTo(3f, 16f); lineTo(9f, 16f); lineTo(9f, 18f); lineTo(3f, 18f); close()
}.build()

/** Material "tune" glyph: three slider tracks with offset knobs. Used for the
 *  Control page's "More settings" button. */
internal val TuneIcon: ImageVector = ImageVector.Builder(
    name = "Tune",
    defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f,
).path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
    // Three horizontal tracks.
    moveTo(3f, 6.2f); lineTo(21f, 6.2f); lineTo(21f, 7.8f); lineTo(3f, 7.8f); close()
    moveTo(3f, 11.2f); lineTo(21f, 11.2f); lineTo(21f, 12.8f); lineTo(3f, 12.8f); close()
    moveTo(3f, 16.2f); lineTo(21f, 16.2f); lineTo(21f, 17.8f); lineTo(3f, 17.8f); close()
    // Knobs (cut out of the tracks), one per row at staggered x positions.
    moveTo(9f, 7f)
    arcToRelative(2f, 2f, 0f, false, true, -4f, 0f)
    arcToRelative(2f, 2f, 0f, false, true, 4f, 0f); close()
    moveTo(19f, 12f)
    arcToRelative(2f, 2f, 0f, false, true, -4f, 0f)
    arcToRelative(2f, 2f, 0f, false, true, 4f, 0f); close()
    moveTo(12f, 17f)
    arcToRelative(2f, 2f, 0f, false, true, -4f, 0f)
    arcToRelative(2f, 2f, 0f, false, true, 4f, 0f); close()
}.build()

/** Camera glyph for the CCAPI tab: a body with a viewfinder bump and a lens
 *  cut out (even-odd) so the lens reads as a ring. */
private val CameraTabIcon: ImageVector = ImageVector.Builder(
    name = "Camera",
    defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f,
).path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
    // Body with centered viewfinder bump.
    moveTo(3f, 8f); lineTo(8f, 8f); lineTo(9.3f, 6f); lineTo(14.7f, 6f)
    lineTo(16f, 8f); lineTo(21f, 8f); lineTo(21f, 19f); lineTo(3f, 19f); close()
    // Lens (cut out).
    moveTo(15f, 13.5f)
    arcToRelative(3f, 3f, 0f, false, true, -6f, 0f)
    arcToRelative(3f, 3f, 0f, false, true, 6f, 0f)
    close()
}.build()

/** Shutter glyph for the Control tab: an outer ring + a centre dot (even-odd). */
private val ControlTabIcon: ImageVector = ImageVector.Builder(
    name = "Control",
    defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f,
).path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
    // Outer circle r=9 (filled).
    moveTo(21f, 12f)
    arcToRelative(9f, 9f, 0f, false, true, -18f, 0f)
    arcToRelative(9f, 9f, 0f, false, true, 18f, 0f)
    close()
    // Ring hole r=6.5 (cut out).
    moveTo(18.5f, 12f)
    arcToRelative(6.5f, 6.5f, 0f, false, true, -13f, 0f)
    arcToRelative(6.5f, 6.5f, 0f, false, true, 13f, 0f)
    close()
    // Centre dot r=3.5 (filled again — odd nesting).
    moveTo(15.5f, 12f)
    arcToRelative(3.5f, 3.5f, 0f, false, true, -7f, 0f)
    arcToRelative(3.5f, 3.5f, 0f, false, true, 7f, 0f)
    close()
}.build()

/** Gallery glyph for the View tab: a framed picture (hollow frame, even-odd)
 *  containing a small sun and mountain. */
private val GalleryTabIcon: ImageVector = ImageVector.Builder(
    name = "Gallery",
    defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f,
).path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
    // Outer frame.
    moveTo(3f, 5f); lineTo(21f, 5f); lineTo(21f, 19f); lineTo(3f, 19f); close()
    // Inner window (cut out).
    moveTo(5f, 7f); lineTo(19f, 7f); lineTo(19f, 17f); lineTo(5f, 17f); close()
    // Mountain (solid again — odd nesting inside the window).
    moveTo(6f, 16.5f); lineTo(10f, 11f); lineTo(13f, 14.5f); lineTo(15f, 12f); lineTo(18f, 16.5f); close()
    // Sun.
    moveTo(9f, 9.5f)
    arcToRelative(1.3f, 1.3f, 0f, false, true, -2.6f, 0f)
    arcToRelative(1.3f, 1.3f, 0f, false, true, 2.6f, 0f)
    close()
}.build()
