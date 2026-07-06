# TODO — improvement backlog

Prioritized list of known issues and improvements. Each item explains **what's wrong**, **why it matters**, and **how to implement the fix**, with file references.

---

## 1. [DONE · Android · Perf] Grid scrolling is janky with more than 1 column (known issue)

> **Status: implemented (2026-07-06).** All five sub-fixes applied (1e to both `MainViewModel` and `LocalViewerViewModel`). Compiles clean; verify scroll feel on-device with a multi-column grid.

**Where:** `android/app/src/main/java/com/rawr/ccapi/ui/CameraImages.kt`, `MainActivity.kt` (`PhotoGrid` / `PhotoCell`), `ImageDecode.kt`.

More columns means more cells per screenful, so every per-cell cost multiplies. Apply these sub-fixes in order of expected impact:

### 1a. Replace the animated spinner placeholder with a static placeholder

Every loading cell currently shows a `CircularProgressIndicator` (`GridThumb`, CameraImages.kt ~line 144). An indeterminate spinner runs an **infinite animation that invalidates and redraws every frame**. During a fling on a 4+ column grid, dozens of cells are simultaneously in the loading state, which means dozens of concurrent infinite animations on the UI thread — a classic Compose jank source.

**Fix:** reuse the pattern already used on the View tab (`RawPlaceholder` in `LocalViewerScreen.kt`): a plain `Box` with `MaterialTheme.colorScheme.surfaceVariant` background (optionally a static "…" or nothing at all). No animation, zero per-frame cost.

### 1b. Bound decode concurrency with a semaphore

`CameraImageLoader.loadThumb`/`loadGridSharp` decode on `Dispatchers.Default` with **no limit**. The network lanes (`Semaphore(4)`/`Semaphore(2)`) indirectly bound decodes on first load, but on the *cache-hit path* (scrolling back up, or after a pinch changed `reqWidth` so bitmap caches miss but `rawCache` byte caches hit) `fetchBytes` returns instantly and **every visible cell decodes at once**, saturating all CPU cores and starving the UI thread.

**Fix:** add a `private val decodeLimiter = Semaphore(2)` to `CameraImageLoader` and wrap both decode call sites in `decodeLimiter.withPermit { withContext(Dispatchers.Default) { decode(...) } }` — exactly the pattern `RawPreviewLoader` already uses (`local/RawPreviewLoader.kt` lines 35–36).

### 1c. Quantize the sharp-image width into buckets

`PhotoGrid` computes `cellWidthPx` exactly from the grid width, and `GridThumb`'s `produceState` uses it as a key (`key2 = sharpWidth`). Any width change — pinch to a new column count, rotation, window resize — changes the key for **every cell simultaneously**, restarting all their coroutines and forcing a full re-fetch/re-decode storm (the `gridCache` is keyed by exact width, so everything misses).

**Fix:** round the requested width up to a small set of buckets before it reaches `GridThumb`, e.g. in `PhotoGrid`:

```kotlin
val buckets = intArrayOf(192, 256, 320, 448, 640, 896, 1024)
val sharpWidth = buckets.first { it >= cellWidthPx.coerceAtMost(1024) }
```

Cells then keep their keys across small width changes, and the `gridCache` gets reused across column counts that fall in the same bucket. Decoding up to one bucket larger than the cell is visually free (it's downscaled at draw time) and much cheaper than a re-decode storm.

### 1d. Keep rotated (portrait) bitmaps off the Java heap

`decodeSampledImageWithSize` (ImageDecode.kt) decodes upright images straight into `Bitmap.Config.HARDWARE` (GPU-resident, no per-draw upload), but rotated images stay **software** after `applyOrientation`, so portrait shots pay a texture upload on first draw and add GC churn. Folders full of portrait shots jank more.

**Fix:** after `applyOrientation`, convert the result: `oriented.copy(Bitmap.Config.HARDWARE, false)?.also { oriented.recycle() } ?: oriented` (guard with try/catch and keep the software bitmap on failure; `copy` to HARDWARE requires API 26+, which matches minSdk 26).

### 1e. Cache the filtered/sorted list instead of recomputing per read

`MainViewModel.visibleFiles` is a computed `get()` that filters, sorts, and (for descending) copies the list on **every read**. It's read by `PhotoGrid`'s content lambda, `MainShell`, and `FilterSheet`, so unrelated recompositions re-run the sort.

**Fix:** replace the getter with a cached derivation, e.g. hold the inputs in `derivedStateOf`:

```kotlin
val visibleFiles by derivedStateOf {
    val filtered = files.filter { ... }
    val cmp = when (sortKey) { ... }
    filtered.sortedWith(if (sortAscending) cmp else cmp.reversed())
}
```

Using `cmp.reversed()` also removes the extra `reversed()` list copy.

### Verification note

Profile in a **release build** (`./gradlew :app:assembleRelease` with R8) or at minimum with a profileable debug build — debug Compose has heavy overhead and exaggerates jank. Confirm with the Layout Inspector recomposition counts or `adb shell dumpsys gfxinfo com.rawr.ccapi`.

---

## 2. [DONE · Android · Bug] Corrupted (mojibake) text in the full-screen preview select button

> **Status: implemented (2026-07-06).** String replaced with `"Selected — tap to deselect"`; `.editorconfig` added at repo root pinning UTF-8.

**Where:** [MainActivity.kt:958](android/app/src/main/java/com/rawr/ccapi/ui/MainActivity.kt#L958)

The pager-based preview's button renders literally as `âœ“ Selected â€” tap to deselect` — UTF-8 bytes for "✓" and "—" that were re-encoded through Windows-1252 at some point. This is user-visible on every photo preview.

**Fix:**
1. Replace the string with plain text (also drops the glyph, per the no-symbols-in-controls design direction): `Text(if (checked) "Selected — tap to deselect" else "Select for download")` — make sure the em dash is a real `—` and the file is saved as UTF-8.
2. Prevent recurrence: add a repo-root `.editorconfig` with `charset = utf-8` so every editor writes UTF-8.

---

## 3. [PARTIAL · Android · Perf] Folder listing fetches per-file metadata sequentially

> **Status: stage 1 implemented (2026-07-06).** `listRawFiles` is now `suspend` and fetches `?kind=info` with 4-way bounded concurrency (`INFO_CONCURRENCY`). Stage 2 (render the page immediately, enrich in the background) and the `folderPageCount` session cache remain open.

**Where:** `CcapiClient.listRawFiles` ([CcapiClient.kt:317-352](android/app/src/main/java/com/rawr/ccapi/net/CcapiClient.kt#L317-L352)), consumed by `MainViewModel.loadLevel`.

For each page, `listRawFiles` does one `?kind=info` request **per file, serially**, before returning anything. A 100-item CCAPI page at ~40–80 ms per request means **4–8 seconds of blank "browsing" spinner** before the grid appears. This dominates perceived slowness far more than rendering does.

**Fix in two stages:**

**Stage 1 (small diff, ~5x faster):** make `listRawFiles` a `suspend fun` and fetch the infos concurrently with bounded parallelism:

```kotlin
val sem = Semaphore(4)
val enriched = coroutineScope {
    files.map { f ->
        async(Dispatchers.IO) {
            sem.withPermit {
                try { val info = getFileInfo(f.path); f.copy(size = ..., modified = ..., rating = parseRating(info)) }
                catch (e: CcapiException) { f }
            }
        }
    }.awaitAll()
}
```

The camera already tolerates this concurrency — the thumbnail loader runs 4+2 parallel requests.

**Stage 2 (perceived-instant listing):** split listing from enrichment. Add `listRawFilePaths(folder, page)` that returns names/paths only (2–3 requests total); have `loadLevel` call `postFiles(...)` with that immediately so the grid renders and thumbnails start loading, then launch a background enrichment job that updates each `files[i] = files[i].copy(size = …, modified = …, rating = …)` as infos arrive (SnapshotStateList updates recompose only the touched cells). Guard with a generation counter so navigating away cancels stale enrichment. Note the sort-by-date implication: unenriched items sort as unknown, so either keep camera order until enrichment completes (track an `enriching` flag) or accept items reordering as metadata lands.

Also: cache the `folderPageCount` result per folder path for the session (it's re-requested on every page turn), invalidating on `refresh()`.

The Python client has the same serial loop — see item 12.

---

## 4. [MEDIUM · Android · Robustness] Camera Wi-Fi loss leaves the app wedged on a dead network binding

**Where:** `net/CameraNetwork.kt`, `ui/MainViewModel.kt`.

The `NetworkCallback` only handles `onAvailable`/`onUnavailable`. If the camera powers off or the phone drops its AP, **`onLost` is never handled**: `bindProcessToNetwork` keeps pointing at the dead network (black-holing all app traffic, including any home-Wi-Fi retry), `isBound` stays true, and the UI still says connected — every action just times out.

**Fix:**
1. In `CameraNetwork`, add to the callback:
   ```kotlin
   override fun onLost(network: Network) {
       if (network == boundNetwork) {
           cm.bindProcessToNetwork(null)
           boundNetwork = null
           isBound = false
           onNetworkLost?.invoke()   // or expose a MutableStateFlow<Boolean>
       }
   }
   ```
   Expose the event as a `StateFlow`/callback the ViewModel can observe.
2. In `MainViewModel`, observe it: set `connected = false`, `connectionError = "Lost the camera's Wi-Fi. Rejoin it and reconnect."`, and `CameraSession.clear()`.
3. Also flip `connected` when a `CameraOfflineException` surfaces from browsing (currently only `browseError` is set, and the screen still looks connected) — a single `handleCameraError(e)` helper in the ViewModel that both `connect` and `loadLevel` route through.
4. Bonus UX: in `connect()`, if `bindToCameraWifi(...)` returns `false`, fail fast with "Phone isn't connected to a Wi-Fi network" instead of proceeding into a 10-second HTTP timeout.

---

## 5. [MEDIUM · Android · UX] Persist connection settings, destination folder, and grid preferences

**Where:** `MainViewModel` (host/port/username/destination/columns/sort are all reset every process start).

The user re-types nothing today only because defaults are hard-coded (`192.168.1.2`), but the picked download folder — the thing SAF makes annoying to re-pick — is forgotten every launch even though `takePersistableUriPermission` is already taken in `BrowseScreen`.

**Fix with Jetpack DataStore:**
1. Add `implementation("androidx.datastore:datastore-preferences:1.1.1")` to `android/app/build.gradle.kts`.
2. Create `data/SettingsStore.kt`: `val Context.dataStore by preferencesDataStore("settings")` with keys `HOST`, `PORT`, `USERNAME`, `DEST_URI`, `DEST_LABEL`, `GRID_COLUMNS`, `SORT_KEY`, `SORT_ASC`. Expose `suspend fun load(): Settings` and `suspend fun save(...)` helpers.
3. In `MainViewModel.init`, read the stored values **before** the auto-`connect()` (launch a coroutine: restore fields, then connect). Save on: successful connect (host/port/username), `setDestination`, `setGridColumnCount`, `setSort`/`toggleSortDirection`.
4. When restoring the destination URI, validate it's still usable:
   ```kotlin
   val ok = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
       && DocumentFile.fromTreeUri(context, uri)?.canWrite() == true
   ```
   Clear it if not.
5. Don't persist the password in plain preferences; either skip it or use `androidx.security:security-crypto`'s `EncryptedSharedPreferences`.

---

## 6. [MEDIUM · Android · Perf] Faster, sturdier batch downloads

**Where:** `download/DownloadService.kt`, `download/DownloadController.kt`, `MainActivity.kt` (`DownloadsSheet`).

Three independent fixes:

### 6a. Collision check is O(files x folder-size)

`uniqueName` calls `tree.findFile(name)` per file (twice+ in the loop for suffixed names). Each `findFile` on a SAF `DocumentFile` **queries and iterates the whole directory** through the documents provider. For a 300-file batch into a folder that already holds thousands of RAWs this adds minutes.

**Fix:** list the folder **once** at batch start and keep the names in memory:

```kotlin
val existing = tree.listFiles().mapNotNullTo(HashSet()) { it.name }
fun uniqueName(name: String): String { /* probe against `existing` */ }
// after choosing: existing.add(chosenName)
```

### 6b. Hold a Wi-Fi lock (and partial wakelock) during the batch

With the screen off, Wi-Fi power-save can throttle throughput hard even under a foreground service. In `onStartCommand`, acquire and release in the `finally`:

```kotlin
val wifi = (getSystemService(WIFI_SERVICE) as WifiManager)
    .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "rawr:download")
val wake = (getSystemService(POWER_SERVICE) as PowerManager)
    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rawr:download")
```

(`WIFI_MODE_FULL_LOW_LATENCY` requires API 29; fall back to `WIFI_MODE_FULL_HIGH_PERF` below that.)

### 6c. Throttle progress emissions and lazy-render the sheet

`onProgress` fires per 1 MB chunk; each call copies the **entire** `files` list into a new `DownloadUiState` and emits, and `DownloadsSheet` renders all rows in a plain `Column` (not lazy). A 45 MB CR3 = 45 full-list copies + recompositions; a 300-file batch sheet lays out 300 rows each time.

**Fix:** in `DownloadService`, only push `updateFile` when ≥250 ms elapsed since the last push for that file (always push the final state); convert `DownloadsSheet`'s file list to a `LazyColumn(Modifier.heightIn(max = 480.dp))`. While in `DownloadController`, switch `updateFile`/`finish` to `_state.update { ... }` for atomicity.

---

## 7. [DONE · Android · Cleanup] Remove the dead single-file preview overlay; freeze the pager's list

> **Status: implemented (2026-07-06).** Dead overload deleted (~110 lines); `previewFile` setter is now private behind `openPreview`/`closePreview`, and the pager renders from the frozen `previewList`. The View tab's `LocalPreviewOverlay` was left as-is (its list can't change while the overlay is open).

**Where:** `MainActivity.kt`.

- The single-file `PreviewOverlay(vm, f: RawFile)` overload ([MainActivity.kt:801-910](android/app/src/main/java/com/rawr/ccapi/ui/MainActivity.kt#L801-L910)) is **dead code** — `MainShell` only calls the list/pager overload. Its gesture logic is duplicated by `ZoomableImportPhotoPage`. Delete it (~110 lines).
- The pager overlay is built from `vm.visibleFiles`, which is **live**: with the "Selected only" filter on, deselecting the current photo removes it from the list mid-swipe and every page shifts. Fix by snapshotting the list when the preview opens — add to `MainViewModel`:
  ```kotlin
  var previewList: List<RawFile> = emptyList(); private set
  fun openPreview(f: RawFile) { previewList = visibleFiles; previewFile = f }
  ```
  have `PhotoCell` call `vm.openPreview(f)`, and `MainShell` render from `vm.previewList`. (Same consideration applies to `LocalPreviewOverlay` on the View tab.)

---

## 8. [MEDIUM · Android · Perf/UX] Cache decoded full-screen previews

**Where:** `CameraImages.kt` (`loadFull`), used by the preview pager.

`loadFull` caches the display-JPEG **bytes** but decodes a fresh ~1600 px bitmap on **every** call. The pager preloads neighbours (`beyondViewportPageCount = 1`), so each swipe decodes 1–2 images, and swiping back re-decodes what was just shown.

**Fix:** add a small decoded-bitmap cache next to the others:

```kotlin
private val fullCache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}
```

Check it at the top of `loadFull`, put after decode. ~48 MB holds roughly 4–5 1600-px frames — enough for the pager's window. Optional follow-up: when zoomed past ~2x, re-decode the cached bytes at a larger `reqWidth` for sharper zooms.

---

## 9. [MEDIUM · Android · UX] Auto-load the next page while scrolling (infinite scroll)

**Where:** `MainActivity.kt` (`PhotoGrid`, `Pager`), `MainViewModel`.

Paging through a shoot with Prev/Next buttons is clunky, and sorting currently applies **within one page only**, which quietly lies (sort by date only sorts the loaded 100).

**Fix:**
1. Add `postFiles(..., append = true)` in `MainViewModel` so `nextPage()` appends to `files` instead of replacing (track the highest loaded page; keep `refresh()` clearing).
2. In `PhotoGrid`, hoist a `rememberLazyGridState()` and trigger loading near the end:
   ```kotlin
   LaunchedEffect(gridState) {
       snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
           .collect { last ->
               if (last != null && last >= vm.visibleFiles.size - columns * 4 && vm.hasMore && !vm.browsing) vm.nextPage()
           }
   }
   ```
3. Replace the Prev/Next `Pager` row with a small "Loading more…" footer item while a page is in flight.
4. Sorting then applies across everything loaded so far, which matches user expectations much better. (Pairs well with item 3's fast listing.)

---

## 10. [LOW · Android · Design] Replace emoji glyphs in controls with drawn icons

**Where:** `FolderRow` ([MainActivity.kt:466](android/app/src/main/java/com/rawr/ccapi/ui/MainActivity.kt#L466)) and the folder-picker button ([MainActivity.kt:673](android/app/src/main/java/com/rawr/ccapi/ui/MainActivity.kt#L673)) render a literal `📁` emoji.

The app's design direction is minimal/sleek with **no emoji in UI controls**, and every other glyph is a hand-built `ImageVector`. The emoji also renders in the system emoji font's colors, ignoring the theme.

**Fix:** add a `FolderIcon` ImageVector at the bottom of `MainActivity.kt` following the existing pattern (a simple tabbed-rectangle path), and use `Icon(FolderIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)` in both places. The `★` text badges are font glyphs rather than emoji — optionally replace with a drawn star for full consistency, but they're lower priority.

---

## 11. [DONE · Repo] Stop tracking build outputs and IDE state in git

> **Status: implemented (2026-07-06).** `.gitignore` updated; `android/.gradle`, `android/build`, `android/app/build`, `android/.idea`, and `android/local.properties` untracked via `git rm -r --cached` (1050 files, staged but not yet committed — files remain on disk). Optional history rewrite for the old APKs not done.

**Where:** the repo currently tracks `android/.gradle/**` (lock files, binary caches), `android/app/build/**` (including `app-debug.apk`), and `android/.idea/**`.

These churn on every build, bloat history (binary APKs), and cause pointless diffs/conflicts.

**Fix:**
1. Append to the root `.gitignore`:
   ```
   android/.gradle/
   android/build/
   android/app/build/
   android/.idea/
   android/local.properties
   ```
2. Untrack without deleting locally:
   ```
   git rm -r --cached android/.gradle android/app/build android/.idea
   git commit -m "Stop tracking Gradle caches, build outputs, and IDE state"
   ```
3. (Optional) If repo size matters, rewrite history with `git filter-repo` to drop the APKs — only worth it before the repo is shared.

---

## 12. [LOW · Android · Quality] Add unit tests for the pure parsing logic

**Where:** none exist (`src/test` absent). The riskiest logic is all JVM-testable without a device.

**Fix:**
1. In `android/app/build.gradle.kts` add:
   ```kotlin
   testImplementation("junit:junit:4.13.2")
   testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
   testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
   ```
2. Create `android/app/src/test/java/com/rawr/ccapi/` with tests for:
   - `Cr3Metadata.read` — build small synthetic buffers (a `CMT1` marker + minimal little/big-endian TIFF IFD with orientation/rating/date tags; an XMP fallback case; a truncated buffer case).
   - `CcapiClient` endpoint discovery + `listRawFiles` — spin up `MockWebServer`, serve a fake `/ccapi` root and contents pages, assert version selection, extension filtering, rating normalization (int, "off", "3" as string).
   - `imageQualityLabel` / `getImageQuality` — both the list-form and axis-form `ability` shapes.
   - The download `uniqueName` collision logic — extract it to a pure function (`fun uniqueName(existing: Set<String>, name: String)`) per item 6a, then test it directly.
3. Run with `./gradlew :app:testDebugUnitTest`.

---

## 13. [LOW · Web MVP] Backend/frontend parity fixes

The web MVP is secondary, but if it stays maintained:

- **Serial metadata fetch** — `backend/app/ccapi_client.py` `list_raw_files` has the same one-`?kind=info`-per-file serial loop as item 3. Parallelize with `concurrent.futures.ThreadPoolExecutor(max_workers=6)` mapping `get_file_info` over the page (requests.Session is not thread-safe for auth digest state — create one session per worker or guard with a lock).
- **Download job registry never pruned** — `_downloads` in `backend/app/main.py` keeps every finished job forever. Add a cleanup in `DownloadManager.start` that drops jobs finished more than an hour ago.
- **Frontend forgets the host** — `ConnectPanel` hard-codes `192.168.0.179` (infrastructure-mode IP; AP mode is `192.168.1.2`). Persist the last-used host/port in `localStorage` and prefill.
- **CORS is wide open** — `allow_origins=["*"]` in `main.py`; restrict to `http://localhost:5173` since only the Vite dev server calls it.

---

## Smaller ideas (unscheduled)

- **Live-view pipelining** (`ControlViewModel.start`): fetch of frame N+1 could overlap decode of frame N (two-stage channel) to get closer to the 30 fps cap on slow links.
- **Parallelize `getMoreSettings`** (`CcapiClient.kt`): ~20 sequential GETs delay the Control page's "More" sheet; same bounded-`async` pattern as item 3.
- **Import tab `.CR2` support**: `CcapiEndpoints.RAW_EXTENSIONS` only lists `.cr3`, but the View tab already handles `.cr2` — add it for older bodies.
- **`postFiles` atomicity** (`MainViewModel`): wrap in `Snapshot.withMutableSnapshot { }` like `postResolvedFolders`, so the grid never renders a half-committed page.
- **Double-tap zoom to the tapped point** (all zoomable previews): current double-tap zooms to center; compute the offset from the tap position for a more natural feel.
- **Download notification tap action**: add a `PendingIntent` opening `MainActivity` (downloads sheet) — the notification currently does nothing when tapped.
