# Rawr Remote — Android (Canon CCAPI RAW downloader)

A native **Kotlin / Jetpack Compose** Android app that browses a Canon EOS R5's
card over **CCAPI** and downloads selected `.CR3` files to a folder you pick on
the phone. Same scope as the web MVP: **no live view, shooting, or settings** —
just browse storage and download RAW.

The phone talks to the camera directly; **there is no Python backend** on
Android. The `CanonCcapiClient` logic was ported to Kotlin/OkHttp and runs on
the device.

## How the phone reaches the camera

This build assumes **the phone joins the camera's Wi‑Fi access point**. That
network has no internet, so Android would otherwise route the app's requests
over cellular and they'd never reach the camera. On connect, the app pins
itself to the camera's Wi‑Fi with `ConnectivityManager.bindProcessToNetwork`
(see [`net/CameraNetwork.kt`](app/src/main/java/com/rawr/ccapi/net/CameraNetwork.kt)).
Cleartext HTTP to the camera is allowed via
[`res/xml/network_security_config.xml`](app/src/main/res/xml/network_security_config.xml).

## Requirements

- Android Studio (Ladybug or newer) with Android SDK 35.
- A device/emulator on Android 8.0 (API 26)+ — use a **physical phone**, since
  the Wi‑Fi binding to the camera AP can't be exercised on an emulator.
- The camera with CCAPI enabled (see the root project README for how to enable
  CCAPI and test it with curl).

## Build & run

1. Open the `android/` folder in Android Studio (open the directory containing
   this README). Let Gradle sync — it downloads the Gradle wrapper and
   dependencies. *(From the CLI: `./gradlew :app:assembleDebug`. If the wrapper
   jar is missing because this was cloned without it, run `gradle wrapper`
   once, or just use Android Studio which provisions it.)*
2. Connect a phone with USB debugging and click **Run**, or
   `./gradlew installDebug`.

## Using the app

1. On the **phone's** Wi‑Fi settings, connect to the camera's access point
   (confirm "stay connected, no internet"). **Turn off any VPN / ad‑blocker /
   "block connections without VPN"** — those prevent the phone from reaching
   the camera entirely.
2. In the app, enter the camera IP (in camera access‑point mode this is shown
   on the camera, typically `192.168.1.2`), port `8080`, optional
   username/password, then tap **Connect**. The app acquires the camera Wi‑Fi,
   calls `/ccapi` + `deviceinformation`, and shows the model.
3. Browse: tap a storage (e.g. `sd`) → a folder (e.g. `100CANON`). Photos appear
   as a **grid of large `display`-resolution previews**, loaded one page at a
   time. Pull down to refresh.
4. **Tap a photo** to view it full‑screen; tap the **checkbox** on a tile to
   select/deselect. Selection persists across pages and folders and is shown in
   the bottom bar.
5. Tap **Choose folder** (bottom bar) to pick a destination via the system
   folder picker (Storage Access Framework), then tap **Download**.
6. A foreground notification plus the in‑app downloads sheet track progress;
   downloads keep running if you leave the app. **Cancel** stops them.
   Collisions become `IMG_0001_1.CR3`.

The UI is dark‑themed.

## Project layout

```
app/src/main/java/com/rawr/ccapi/
  CameraSession.kt              # holds the connected client (process-wide)
  net/
    CcapiEndpoints.kt           # all CCAPI URL/param constants (single source to tweak)
    CcapiClient.kt              # OkHttp client, models, errors, streaming download
    CameraNetwork.kt            # binds the app to the camera's Wi-Fi
  download/
    DownloadModels.kt           # task / progress / job state types
    DownloadController.kt       # shared job state (StateFlow) + start/cancel
    DownloadService.kt          # foreground service: SAF streaming, progress, collision
  ui/
    MainViewModel.kt            # connect / browse / select / download orchestration
    MainActivity.kt             # Compose UI: connect screen, photo grid, preview, downloads
    CameraImages.kt             # cached ?kind=display previews (grid + full-screen)
app/src/main/res/xml/network_security_config.xml   # allow cleartext to camera
app/src/main/AndroidManifest.xml                   # permissions + foreground service
```

## Error handling

`CcapiClient` raises typed errors mapped to clear UI messages: camera offline,
timeout, auth failure (tries Digest then Basic), unsupported endpoint (also via
version auto-discovery from `/ccapi`), partial download (the partial file is
deleted), and filename collision (auto-renamed). Thumbnail/metadata failures are
best-effort and never break a listing.

## Notes & limits (MVP)

- One camera, one download batch at a time; state is in memory.
- If your camera doesn't expose `?kind=number`, paging falls back to a single
  page. Endpoint paths/versions are all adjustable in `net/CcapiEndpoints.kt`.
- The `android/` project is standalone; the original `backend/` + `frontend/`
  web app still works unchanged.
