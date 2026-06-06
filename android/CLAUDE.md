# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A native **Kotlin / Jetpack Compose** Android app with three pages, switched via a
bottom navigation bar (`MainShell` in `MainActivity.kt`):

1. **Import** — browses a Canon EOS camera's card over **CCAPI** (Canon Camera
   Control API) and downloads `.CR3` RAW files to a user-picked folder. There is
   **no backend**; the phone talks to the camera directly over HTTP. The Python
   `CanonCcapiClient` from the sibling web MVP was ported to Kotlin/OkHttp and
   runs on-device (`net/CcapiClient.kt`).
2. **Control** — remote shooting over CCAPI (`ui/ControlScreen.kt` +
   `ControlViewModel.kt`): a polled **live-view** finder, a **shutter** press,
   **touch focus** (tap the finder → set AF frame + drive AF), and **ISO /
   shutter / aperture** controls (read each setting's value + options, PUT a new
   value). Requires the camera connected on the Import tab (shares
   `CameraSession.client`).
3. **View** — an offline gallery of the `.CR3` files already on the phone
   (`local/` package + `ui/LocalViewerScreen.kt`). The OS gallery can't decode
   CR3, so this exists to find them and **share them to Lightroom** via the system
   share sheet. Fully independent of the camera connection.

`applicationId` / namespace: `com.rawr.ccapi`. minSdk 26, target/compile SDK 35, JVM 17.

## Build & run

Toolchain: Gradle 8.11.1, AGP 8.7.3, Kotlin 2.1.0. Requires Android SDK 35 and a
`local.properties` with `sdk.dir` (already present locally; not committed).

- Build debug APK: `./gradlew :app:assembleDebug`
- Install to a connected device: `./gradlew installDebug`

**The Gradle wrapper jar/scripts are not checked in** — only `gradle/wrapper/gradle-wrapper.properties`
exists. If `./gradlew` is missing, either open the project in Android Studio (it
provisions the wrapper), or run `gradle wrapper` once to generate it.

**Use a physical phone, not an emulator.** The app pins itself to the camera's Wi-Fi
access point, which can't be exercised on an emulator. There are also **no unit or
instrumented tests** in this project (`src/test` and `src/androidTest` do not exist).

## Architecture

Five layers under `app/src/main/java/com/rawr/ccapi/`, plus two process-wide singletons
that outlive any screen because work (downloads, the connected client) must survive the
UI going away.

**`net/` — camera communication**
- `CcapiClient.kt` — the OkHttp client. Browsing + streaming download, a typed
  `CcapiException` hierarchy (offline / timeout / auth / unsupported-endpoint / partial /
  cancelled) that the UI maps to clean messages, and Digest-then-Basic auth dispatch.
- `CcapiEndpoints.kt` — **the single source of truth for every CCAPI URL/path/param.**
  If Canon changes a path or you need a different version, change it *here*.
- `CameraNetwork.kt` — acquires and pins the app to the camera's internet-less Wi-Fi.

**`download/` — background downloads**
- `DownloadController` (object) — process-wide `StateFlow<DownloadUiState>` the UI
  observes, plus an `AtomicBoolean cancelFlag` and the pending `DownloadRequestData`.
- `DownloadService` — a foreground service (type `dataSync`) that streams the queued
  batch to the user's folder via the Storage Access Framework, reports progress back
  through `DownloadController`, handles filename collisions, and deletes partial files.
- `DownloadModels.kt` — `FileTask` / `FileProgress` / `JobStatus` state types.

**`local/` — offline RAW viewer (the "View" page)**
- `LocalRawStore.kt` — walks shared storage for `.CR3`/`.CR2` files (`LocalRawPhoto`),
  reading each one's orientation + rating concurrently during the scan.
- `LocalRawAccess.kt` — the broad file-read permission: "All files access"
  (MANAGE_EXTERNAL_STORAGE) on API 30+, legacy READ_EXTERNAL_STORAGE on 26–29.
- `Cr3Metadata.kt` — pulls orientation + star rating out of a CR3's `CMT1`
  EXIF/TIFF block (with an XMP rating fallback) from a small header read. The OS
  can't read these from a `.CR3`; orientation fixes sideways portraits, rating
  drives the grid filter + star badges.
- `RawPreviewLoader.kt` — renders thumbnails by extracting the JPEG preview baked
  into each CR3 (scans a bounded prefix for the largest `FF D8 … FF D9` segment),
  applying `LocalRawPhoto.orientation`, with the same cache/lane pattern as
  `CameraImageLoader`.

**`ui/` — Jetpack Compose**
- `MainViewModel.kt` — `AndroidViewModel` holding all browse/select/download state as
  Compose `mutableStateOf` / `SnapshotStateList` / `SnapshotStateMap` (not LiveData).
  Orchestrates connect → browse → select → download.
- `MainActivity.kt` — most of the Compose UI in one file: the `MainShell` bottom-nav
  host, the CCAPI screens (connect, breadcrumb browser, photo grid, full-screen
  preview, downloads/filter bottom sheets), and the hand-built nav/action icons.
- `LocalViewerViewModel.kt` / `LocalViewerScreen.kt` — the "View" page: permission
  gate, thumbnail grid, full-screen preview, and `shareToLightroom` (an `ACTION_SEND`
  via `FileProvider`; authority `${applicationId}.fileprovider`, roots in
  `res/xml/file_paths.xml`).
- `CameraImages.kt` — `CameraImageLoader` + the `GridThumb`/`FullImage` composables.
- `ImageDecode.kt` — `decodeSampledImage`, the downsample-+-EXIF-orientation decode
  shared by `CameraImageLoader` and `RawPreviewLoader`.

**`CameraSession.kt` (object)** — holds the one connected `CcapiClient` for the process.
The ViewModel sets it on connect; `DownloadService` reads it. MVP = one camera at a time.

### Cross-cutting concepts you must understand before editing

1. **Network binding is load-bearing.** The phone joins the camera's access point, which
   has *no internet*, so Android refuses to route the app's traffic over it by default.
   `CameraNetwork.bindToCameraWifi` acquires the Wi-Fi via `requestNetwork` (keeping the
   request alive for the session) and pins the whole process with
   `bindProcessToNetwork`. **Per-socket binding (`Network.bindSocket` / passing the
   `Network` to `CcapiClient`'s `socketFactory`) is deliberately avoided** — it throws
   EPERM on some devices. `CcapiClient` accepts a `Network` param but the normal connect
   path passes `null` on purpose; don't "fix" this. An always-on VPN or local-VPN
   ad-blocker breaks camera access entirely and the user must disable it.

2. **Endpoint discovery vs. fallbacks.** `GET /ccapi` returns a capability document listing
   every endpoint the connected camera supports, grouped by API version. `CcapiClient`
   indexes these by version-independent suffix (e.g. `deviceinformation`, `contents`),
   keeping the highest version seen, and only falls back to the hard-coded lists in
   `CcapiEndpoints` when discovery isn't available. Keep those fallback lists
   newest-version-first.

3. **Two-stage, lane-separated image loading** (`CameraImageLoader`). Grid cells show the
   tiny `?kind=thumbnail` JPEG first (its own `Semaphore(4)` lane), then upgrade to the
   sharper `?kind=display` image (a narrower `Semaphore(2)` lane) so big downloads never
   block fast thumbnails. Raw bytes and decoded bitmaps are kept in size-bounded
   `LruCache`s; the display bytes are shared between the grid's sharp image and the
   full-screen view. EXIF orientation is applied manually because `BitmapFactory` ignores it.

4. **Download data flow:** `MainViewModel.startDownload()` → `DownloadController.start()`
   (sets state to RUNNING, stores the request) → `startForegroundService` →
   `DownloadService.runBatch()` reads `CameraSession.client` + `DownloadController.pending`,
   streams each file through `CcapiClient.download(...)`, and pushes per-file progress back
   via `DownloadController.updateFile()`. The UI is purely a `collectAsState()` observer.

5. **Atomic browse-level updates.** `postResolvedFolders` resolves every folder's item
   count concurrently *before* publishing, then commits the whole level inside a single
   `Snapshot.withMutableSnapshot { ... }` so the grid never flashes a half-updated state
   (empty folders are filtered out before they appear).

## Conventions & rules for editing

- **Kotlin official style** (`kotlin.code.style=official`); non-transitive R class is on.
  Match the existing terse, heavily-commented style — comments here explain *why*
  (device quirks, ordering constraints), not *what*.
- **Put all CCAPI URLs, query params, and `kind=` constants in `CcapiEndpoints.kt`.**
  Don't inline endpoint strings elsewhere.
- **Metadata and thumbnail/preview fetches are best-effort** and must never fail a
  listing — catch and degrade (see `listRawFiles`'s per-file `try`, `CameraImageLoader`
  returning `null`). Preserve this when adding features.
- **Cleartext HTTP to the camera is intentional**, allowed globally via
  `res/xml/network_security_config.xml`. This is required because CCAPI is plain HTTP on
  a private network.
- Icons are hand-built `ImageVector`s (bottom of `MainActivity.kt`) to avoid pulling in
  the heavy `material-icons-extended` dependency — add new glyphs the same way rather
  than adding that dep.
- The UI is **dark-theme only and immersive fullscreen**; Dialogs/BottomSheets live in
  their own windows, so they call `KeepImmersive()` to re-hide the system bars.
- Adding a dependency means editing `app/build.gradle.kts`; the foreground-service and
  network permissions in `AndroidManifest.xml` are already declared for the current scope.
- New `versionCode`/`versionName` live in `app/build.gradle.kts` (`defaultConfig`).
