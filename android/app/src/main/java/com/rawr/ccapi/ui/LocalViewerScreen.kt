@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.rawr.ccapi.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.rawr.ccapi.local.LocalRawAccess
import com.rawr.ccapi.local.LocalRawPhoto
import com.rawr.ccapi.local.RawPreviewLoader
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

// FileProvider authority (see AndroidManifest.xml) + the MIME we advertise for a
// shared .CR3. The data is Canon raw; "image/..." makes image editors such as
// Lightroom (which accept image/*) offer themselves in the share sheet.
private const val FILEPROVIDER_SUFFIX = ".fileprovider"
private const val RAW_MIME = "image/x-canon-cr3"
private val LocalDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())

@Composable
fun LocalViewerScreen(vm: LocalViewerViewModel) {
    val context = LocalContext.current
    var showFilter by remember { mutableStateOf(false) }

    // Returning from the "All files access" Settings screen (API 30+) or the
    // legacy runtime dialog both just re-check the permission and auto-scan.
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { vm.refreshAccess() }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { vm.refreshAccess() }

    LaunchedEffect(Unit) { vm.refreshAccess() }

    Scaffold(topBar = { LocalTopBar(vm, onFilter = { showFilter = true }) }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                !vm.hasAccess -> AccessGate {
                    val legacy = LocalRawAccess.legacyPermission
                    if (legacy != null) {
                        permissionLauncher.launch(legacy)
                    } else {
                        settingsLauncher.launch(LocalRawAccess.allFilesSettingsIntent(context))
                    }
                }
                vm.scanning && vm.total == 0 -> ScanningMessage()
                vm.hasScanned && vm.total == 0 ->
                    CenteredMessage("No RAW (.CR3) files found on this phone.", "Rescan") { vm.rescan() }
                vm.visiblePhotos.isEmpty() ->
                    CenteredMessage("No photos match the filter.", "Clear filters") { vm.clearFilters() }
                else -> LocalGrid(vm)
            }
        }
    }

    if (showFilter) LocalFilterSheet(vm) { showFilter = false }

    // The full-screen preview is rendered by MainShell (above the bottom nav),
    // not here, so it covers the whole screen.
}

@Composable
private fun LocalTopBar(vm: LocalViewerViewModel, onFilter: () -> Unit) {
    TopAppBar(
        title = {
            Text(
                if (vm.total == 0) "RAW Photos" else "RAW Photos · ${vm.total}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        actions = {
            if (vm.scanning) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp).padding(end = 4.dp))
            } else if (vm.hasAccess) {
                // Filter + sort are only meaningful once there are photos.
                if (vm.total > 0) {
                    IconButton(onClick = onFilter) {
                        Icon(
                            FilterListIcon,
                            contentDescription = "Filter",
                            tint = if (vm.isFiltering) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    LocalSortMenu(vm)
                }
                IconButton(onClick = { vm.rescan() }) {
                    Icon(RefreshIcon, contentDescription = "Rescan", tint = MaterialTheme.colorScheme.onSurface)
                }
            }
        },
    )
}

@Composable
private fun LocalSortMenu(vm: LocalViewerViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(SortIcon, contentDescription = "Sort", tint = MaterialTheme.colorScheme.onSurface)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LocalViewerViewModel.SortKey.entries.forEach { key ->
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

// --- filter sheet ----------------------------------------------------------

@Composable
private fun LocalFilterSheet(vm: LocalViewerViewModel, onDismiss: () -> Unit) {
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
                "Show photos with these ratings. Nothing selected shows everything. " +
                    "Ratings are read from each RAW.",
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

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${vm.visiblePhotos.size} of ${vm.total} shown",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).align(Alignment.CenterVertically),
                )
                Button(onClick = onDismiss) { Text("Done") }
            }
        }
    }
}

// --- permission gate -------------------------------------------------------

@Composable
private fun AccessGate(onGrant: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("View RAW photos", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Your phone's gallery can't open .CR3 files, so this needs permission " +
                    "to read files across storage. It finds your RAW photos and lets you " +
                    "share them to Lightroom.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onGrant) { Text("Grant access") }
        }
    }
}

@Composable
private fun ScanningMessage() {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator()
            Text("Scanning for RAW files…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// --- grid ------------------------------------------------------------------

@Composable
private fun LocalGrid(vm: LocalViewerViewModel) {
    val context = LocalContext.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Default density scales with screen width (~135dp/cell ⇒ 3 columns on a
        // phone, more on a tablet); pinch-to-zoom still overrides it.
        val autoColumns = (maxWidth.value / 135f).roundToInt().coerceIn(1, 10)
        LaunchedEffect(autoColumns) { vm.applyAutoColumns(autoColumns) }
        val photos = vm.visiblePhotos
        LazyVerticalGrid(
            columns = GridCells.Fixed(vm.gridColumns),
            modifier = Modifier.fillMaxSize().pinchToZoomColumns(
                current = { vm.gridColumns },
                onChange = { vm.setGridColumnCount(it) },
            ),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            photos.forEachIndexed { index, photo ->
                val dateKey = photoDateKey(photo)
                if (index == 0 || dateKey != photoDateKey(photos[index - 1])) {
                    item(
                        key = "date:$dateKey:$index",
                        span = { GridItemSpan(maxLineSpan) },
                        contentType = "date-divider",
                    ) {
                        LocalDateDivider(photoDateLabel(photo))
                    }
                }
                item(key = photo.path, contentType = "raw") {
                    LocalPhotoCell(
                        photo = photo,
                        columns = vm.gridColumns,
                        onOpen = { vm.previewPhoto = photo },
                        onShare = { shareToLightroom(context, photo) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalDateDivider(label: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HorizontalDivider(Modifier.weight(1f))
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.weight(1f))
    }
}

private fun photoDateKey(photo: LocalRawPhoto): String {
    val time = photo.captureTime ?: photo.lastModified
    if (time <= 0L) return "unknown"
    return Instant.ofEpochMilli(time)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .toString()
}

private fun photoDateLabel(photo: LocalRawPhoto): String {
    val time = photo.captureTime ?: photo.lastModified
    if (time <= 0L) return "Unknown date"
    return LocalDateFormatter.format(
        Instant.ofEpochMilli(time)
            .atZone(ZoneId.systemDefault())
            .toLocalDate(),
    )
}

@Composable
private fun LocalPhotoCell(
    photo: LocalRawPhoto,
    columns: Int,
    onOpen: () -> Unit,
    onShare: () -> Unit,
) {
    // Match the Download page selection badge: 2 columns = full size, smaller
    // thumbnails shrink the overlay control down to half size.
    val badgeScale = (2f / columns).coerceIn(0.5f, 1f)
    Card(
        modifier = Modifier.clickable(onClick = onOpen),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(3f / 2f)) {
                LocalThumb(photo, columns, modifier = Modifier.fillMaxSize())
                // Quick share without opening the full preview.
                IconButton(
                    onClick = onShare,
                    modifier = Modifier.align(Alignment.TopEnd)
                        .graphicsLayer {
                            scaleX = badgeScale
                            scaleY = badgeScale
                            transformOrigin = TransformOrigin(1f, 0f)
                        }
                        .padding(4.dp)
                        .clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)),
                ) {
                    Icon(ShareIcon, contentDescription = "Share", tint = Color.White)
                }
                // Star rating badge (only when the RAW carries one).
                photo.rating.takeIf { it > 0 }?.let { stars ->
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
                photo.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun LocalThumb(photo: LocalRawPhoto, columns: Int, modifier: Modifier = Modifier) {
    val reqWidth = when {
        columns <= 2 -> 560
        columns <= 4 -> 360
        columns <= 6 -> 280
        else -> 220
    }
    val initial = remember(photo.path, reqWidth) { RawPreviewLoader.cachedThumb(photo, reqWidth) }
    var image by remember(photo.path, reqWidth) { mutableStateOf(initial) }
    var done by remember(photo.path, reqWidth) { mutableStateOf(initial != null) }
    LaunchedEffect(photo.path, reqWidth) {
        if (image == null) {
            delay(90) // drop cells flung past before doing disk work
            image = RawPreviewLoader.loadThumb(photo, reqWidth)
        }
        done = true
    }
    val img = image
    when {
        img != null -> Image(bitmap = img, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
        !done -> RawPlaceholder(modifier)
        else -> RawPlaceholder(modifier) // no embedded preview found
    }
}

@Composable
private fun RawPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text("RAW", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// --- full-screen preview ---------------------------------------------------

@Composable
internal fun LocalPreviewOverlay(vm: LocalViewerViewModel, photos: List<LocalRawPhoto>, startIndex: Int) {
    if (photos.isEmpty()) return
    val context = LocalContext.current
    // Full-screen overlay inside the activity's own edge-to-edge window — NOT a
    // Dialog. Dismiss on system back, like a dialog would.
    BackHandler { vm.previewPhoto = null }

    // Horizontal swipe pages between photos (the sorted/filtered list).
    val pagerState = rememberPagerState(initialPage = startIndex.coerceIn(0, photos.lastIndex)) { photos.size }
    val current = photos.getOrNull(pagerState.currentPage) ?: photos.first()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1, // pre-load neighbours so swipes feel instant
        ) { page ->
            ZoomablePhotoPage(photo = photos[page], onDismiss = { vm.previewPhoto = null })
        }

        Row(
            Modifier.fillMaxWidth().align(Alignment.TopCenter)
                .background(Color.Black.copy(alpha = 0.5f))
                .statusBarsPadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    current.name, color = Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "${pagerState.currentPage + 1} / ${photos.size}",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            TextButton(onClick = { vm.previewPhoto = null }) { Text("Close") }
        }

        Button(
            onClick = { shareToLightroom(context, current) },
            modifier = Modifier.align(Alignment.BottomCenter)
                .navigationBarsPadding().padding(horizontal = 24.dp, vertical = 28.dp),
        ) {
            Icon(ShareIcon, contentDescription = null)
            Text("  Share to Lightroom")
        }
    }
}

/**
 * One swipeable, zoomable page in the preview pager. Pinch to zoom + pan; swipe
 * down (when not zoomed) to dismiss. Horizontal drags at 1× are deliberately
 * NOT consumed, so they bubble up to the HorizontalPager to change photos; once
 * zoomed, drags are consumed here (panning the image) so the pager stays put.
 */
@Composable
private fun ZoomablePhotoPage(photo: LocalRawPhoto, onDismiss: () -> Unit) {
    var scale by remember(photo.path) { mutableStateOf(1f) }
    var offset by remember(photo.path) { mutableStateOf(Offset.Zero) }
    var dismissDrag by remember(photo.path) { mutableStateOf(0f) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { boxSize = it }
            .pointerInput(photo.path) {
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
                            // Only react to (and consume) clearly-vertical drags,
                            // leaving horizontal swipes for the pager.
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
            .pointerInput(photo.path) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) { scale = 1f; offset = Offset.Zero } else { scale = 2.5f }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        FullLocalImage(
            photo,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y + dismissDrag
            },
        )
    }
}

@Composable
private fun FullLocalImage(photo: LocalRawPhoto, modifier: Modifier = Modifier) {
    val image by produceState<ImageBitmap?>(initialValue = null, key1 = photo.path) {
        value = RawPreviewLoader.loadFull(photo)
    }
    val img = image
    if (img != null) {
        Image(bitmap = img, contentDescription = null, contentScale = ContentScale.Fit, modifier = modifier)
    } else {
        Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}

// --- sharing ---------------------------------------------------------------

/**
 * Hand the RAW to another app (Lightroom, Drive, …) via the system share sheet.
 * The file is exposed through our [FileProvider] as a content:// URI with a
 * temporary read grant; a `file://` URI would throw FileUriExposedException.
 */
private fun shareToLightroom(context: Context, photo: LocalRawPhoto) {
    val uri = try {
        FileProvider.getUriForFile(context, "${context.packageName}$FILEPROVIDER_SUFFIX", photo.file)
    } catch (e: Exception) {
        return // file outside the provider's configured roots; nothing to share
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = RAW_MIME
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share RAW to…"))
}

// --- inline icons (same hand-built approach as MainActivity) ---------------

/** Material "share" glyph: three nodes joined by two lines. */
private val ShareIcon: ImageVector = ImageVector.Builder(
    name = "Share",
    defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f,
).path(fill = SolidColor(Color.White)) {
    moveTo(18f, 16.08f)
    curveToRelative(-0.76f, 0f, -1.44f, 0.3f, -1.96f, 0.77f)
    lineTo(8.91f, 12.7f)
    curveToRelative(0.05f, -0.23f, 0.09f, -0.46f, 0.09f, -0.7f)
    reflectiveCurveToRelative(-0.04f, -0.47f, -0.09f, -0.7f)
    lineToRelative(7.05f, -4.11f)
    curveToRelative(0.54f, 0.5f, 1.25f, 0.81f, 2.04f, 0.81f)
    curveToRelative(1.66f, 0f, 3f, -1.34f, 3f, -3f)
    reflectiveCurveToRelative(-1.34f, -3f, -3f, -3f)
    reflectiveCurveToRelative(-3f, 1.34f, -3f, 3f)
    curveToRelative(0f, 0.24f, 0.04f, 0.47f, 0.09f, 0.7f)
    lineTo(9.16f, 9.81f)
    curveTo(8.63f, 9.31f, 7.92f, 9f, 7.13f, 9f)
    curveToRelative(-1.66f, 0f, -3f, 1.34f, -3f, 3f)
    reflectiveCurveToRelative(1.34f, 3f, 3f, 3f)
    curveToRelative(0.79f, 0f, 1.5f, -0.31f, 2.03f, -0.81f)
    lineToRelative(7.12f, 4.16f)
    curveToRelative(-0.05f, 0.21f, -0.08f, 0.43f, -0.08f, 0.65f)
    curveToRelative(0f, 1.61f, 1.31f, 2.92f, 2.92f, 2.92f)
    reflectiveCurveToRelative(2.92f, -1.31f, 2.92f, -2.92f)
    reflectiveCurveToRelative(-1.31f, -2.92f, -2.92f, -2.92f)
    close()
}.build()

/** Material "refresh" glyph: a circular arrow. */
private val RefreshIcon: ImageVector = ImageVector.Builder(
    name = "Refresh",
    defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f,
).path(fill = SolidColor(Color.White)) {
    moveTo(17.65f, 6.35f)
    curveTo(16.2f, 4.9f, 14.21f, 4f, 12f, 4f)
    curveToRelative(-4.42f, 0f, -7.99f, 3.58f, -7.99f, 8f)
    reflectiveCurveToRelative(3.57f, 8f, 7.99f, 8f)
    curveToRelative(3.73f, 0f, 6.84f, -2.55f, 7.73f, -6f)
    horizontalLineToRelative(-2.08f)
    curveToRelative(-0.82f, 2.33f, -3.04f, 4f, -5.65f, 4f)
    curveToRelative(-3.31f, 0f, -6f, -2.69f, -6f, -6f)
    reflectiveCurveToRelative(2.69f, -6f, 6f, -6f)
    curveToRelative(1.66f, 0f, 3.14f, 0.69f, 4.22f, 1.78f)
    lineTo(13f, 11f)
    horizontalLineToRelative(7f)
    verticalLineTo(4f)
    lineToRelative(-2.35f, 2.35f)
    close()
}.build()
