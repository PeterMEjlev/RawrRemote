# TODO — improvement backlog

Prioritized list of known issues and improvements. Each item explains **what's wrong**, **why it matters**, and **how to implement the fix**, with file references. Completed items are removed from this file.

---

## 1. [PARTIAL · Android · Perf] Folder listing: render the page before its metadata arrives

> **Status: stage 1 implemented (2026-07-06).** `listRawFiles` is now `suspend` and fetches `?kind=info` with 4-way bounded concurrency (`INFO_CONCURRENCY`). Stage 2 below and the `folderPageCount` session cache remain open.

**Where:** `CcapiClient.listRawFiles` ([CcapiClient.kt](android/app/src/main/java/com/rawr/ccapi/net/CcapiClient.kt)), consumed by `MainViewModel.loadLevel`.

**Stage 2 (perceived-instant listing):** split listing from enrichment. Add `listRawFilePaths(folder, page)` that returns names/paths only (2–3 requests total); have `loadLevel` call `postFiles(...)` with that immediately so the grid renders and thumbnails start loading, then launch a background enrichment job that updates each `files[i] = files[i].copy(size = …, modified = …, rating = …)` as infos arrive (SnapshotStateList updates recompose only the touched cells). Guard with a generation counter so navigating away cancels stale enrichment. Note the sort-by-date implication: unenriched items sort as unknown, so either keep camera order until enrichment completes (track an `enriching` flag) or accept items reordering as metadata lands.

Also: cache the `folderPageCount` result per folder path for the session (it's re-requested on every page append), invalidating on `refresh()`.

The Python client has the same serial loop — see item 4.

---

## 2. [LOW · Android · Robustness] Flip to disconnected when browsing hits a dead camera

**Where:** `ui/MainViewModel.kt` (`loadLevel`, `connect`).

Wi-Fi loss is now detected via `CameraNetwork.onCameraNetworkLost`, but a camera that powers off while its Wi-Fi stays up (or an AP that lingers) still only sets `browseError` — the screen keeps claiming to be connected while every action times out.

**Fix:** add a single `handleCameraError(e: CcapiException)` helper in `MainViewModel` that both `connect` and `loadLevel` route through; when the exception is a `CameraOfflineException`, also set `connected = false`, clear `CameraSession`, and surface a reconnect message instead of just the browse error.

---

## 3. [LOW · Android · Quality] Add unit tests for the pure parsing logic

**Where:** none exist (`src/test` absent). The riskiest logic is all JVM-testable without a device.

**Fix:**
1. In `android/app/build.gradle.kts` add:
   ```kotlin
   testImplementation("junit:junit:4.13.2")
   testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
   testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
   ```
   (Requires network access for the first dependency resolution — the recent builds ran `--offline`.)
2. Create `android/app/src/test/java/com/rawr/ccapi/` with tests for:
   - `Cr3Metadata.read` — build small synthetic buffers (a `CMT1` marker + minimal little/big-endian TIFF IFD with orientation/rating/date tags; an XMP fallback case; a truncated buffer case).
   - `CcapiClient` endpoint discovery + `listRawFiles` — spin up `MockWebServer`, serve a fake `/ccapi` root and contents pages, assert version selection, extension filtering, rating normalization (int, "off", "3" as string).
   - `imageQualityLabel` / `getImageQuality` — both the list-form and axis-form `ability` shapes.
   - `DownloadService.uniqueName` — now a pure function over a name set; test collision suffixing directly.
3. Run with `./gradlew :app:testDebugUnitTest`.

---

## 4. [LOW · Web MVP] Backend/frontend parity fixes

The web MVP is secondary, but if it stays maintained:

- **Serial metadata fetch** — `backend/app/ccapi_client.py` `list_raw_files` has the same one-`?kind=info`-per-file serial loop the Android client used to. Parallelize with `concurrent.futures.ThreadPoolExecutor(max_workers=6)` mapping `get_file_info` over the page (requests.Session is not thread-safe for auth digest state — create one session per worker or guard with a lock).
- **Download job registry never pruned** — `_downloads` in `backend/app/main.py` keeps every finished job forever. Add a cleanup in `DownloadManager.start` that drops jobs finished more than an hour ago.
- **Frontend forgets the host** — `ConnectPanel` hard-codes `192.168.0.179` (infrastructure-mode IP; AP mode is `192.168.1.2`). Persist the last-used host/port in `localStorage` and prefill.
- **CORS is wide open** — `allow_origins=["*"]` in `main.py`; restrict to `http://localhost:5173` since only the Vite dev server calls it.

---

## Smaller ideas (unscheduled)

- **Live-view pipelining** (`ControlViewModel.start`): fetch of frame N+1 could overlap decode of frame N (two-stage channel) to get closer to the 30 fps cap on slow links.
- **Parallelize `getMoreSettings`** (`CcapiClient.kt`): ~20 sequential GETs delay the Control page's "More" sheet; same bounded-`async` pattern as `listRawFiles` now uses.
- **Drawn star glyphs**: the `★` rating badges and filter chips are font glyphs (not emoji); replacing them with a hand-built `ImageVector` star would complete the drawn-icon consistency.
