# Canon CCAPI RAW Downloader (MVP)

A minimal desktop app for browsing a Canon EOS R5's memory card over the
**Canon Camera Control API (CCAPI)** and downloading selected `.CR3` RAW files
to a local folder.

**Scope (intentionally small):** browse storage, list/filter RAW files, and
download them. There is **no** live view, **no** shooting, and **no** camera
settings control.

- **Backend:** Python + FastAPI (`backend/`)
- **Frontend:** React + Vite (`frontend/`)
- **Transport:** plain HTTP to the camera's CCAPI

---

## How it works

1. You enter the camera host/IP, port (default `8080`), and optional
   credentials, then click **Connect**.
2. The backend calls `GET /ccapi` and `GET /ccapi/ver100/deviceinformation` to
   verify the connection and read the model/firmware.
3. CCAPI advertises every endpoint it supports in the `/ccapi` root document.
   The client uses that to discover the correct API version for `contents`,
   `deviceinformation`, etc., and only falls back to the constants in
   [`backend/app/ccapi_endpoints.py`](backend/app/ccapi_endpoints.py) if needed.
4. You browse storage **lazily** — folders are only read when expanded, and
   files are listed **one page at a time** (the whole card is never loaded at
   once).
5. You select `.CR3` files, set a destination folder, and click **Download
   selected**. The backend **streams** each file to disk in 1 MB chunks (never
   buffering a whole RAW in memory), reports per-file progress, and supports
   cancellation.

---

## Prerequisites

- Python 3.10+
- Node.js 18+
- A Canon camera with CCAPI enabled (see below), reachable on your network.

---

## 1. Enable CCAPI on the camera

CCAPI is not public by default — you must enable it once via the camera menu.

1. Make sure your camera firmware supports CCAPI (EOS R5 does). Canon
   distributes the CCAPI activation/firmware and the official API reference via
   the [Canon Developers / CCAPI program](https://developers.canon.com/) — you
   may need to register to get the activation file and the reference PDF.
2. On the camera, connect to Wi‑Fi (or wired LAN) and join the same network as
   your computer. Note the camera's IP address (e.g. `192.168.0.179`).
3. In the camera menu, enable the **API / CCAPI** function and set/confirm the
   port (default `8080`). If you enable login authentication, note the username
   and password.
4. Keep the camera awake (disable aggressive auto power-off) while downloading.

> The official CCAPI reference is the **source of truth** for endpoint names.
> If Canon changes a path or you target a different firmware, adjust the
> constants in [`backend/app/ccapi_endpoints.py`](backend/app/ccapi_endpoints.py)
> only — nothing else hard-codes URLs.

---

## 2. Run the backend

```powershell
cd backend
python -m venv .venv
.venv\Scripts\Activate.ps1        # Windows PowerShell
# source .venv/bin/activate       # macOS/Linux
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8000
```

The API is now at `http://localhost:8000` (interactive docs at
`http://localhost:8000/docs`).

## 3. Run the frontend

In a second terminal:

```powershell
cd frontend
npm install
npm run dev
```

Open the printed URL (default `http://localhost:5173`). The dev server proxies
`/api/*` to the backend on port 8000.

---

## 4. Using the app

1. Enter the camera IP (e.g. `192.168.0.179`), port `8080`, optional
   username/password, and click **Connect**.
2. In **Camera storage**, click a card (e.g. `sd`) to expand its folders (e.g.
   `100CANON`), then click a folder to list its `.CR3` files.
3. Tick the files you want. Use the header checkbox to select the whole page.
4. Enter a **local destination folder** — an absolute path on the machine
   running the backend (e.g. `C:\Users\you\Pictures\CanonRAW`). The folder must
   already exist.
5. Click **Download selected**. Watch per-file progress; click **Cancel** to
   stop. Filename collisions are auto-resolved (`IMG_0001_1.CR3`).

---

## 5. Test CCAPI directly with curl

Useful for confirming the camera is reachable before involving the app. Replace
the IP/port and version (`ver110` vs `ver100`, etc.) to match your camera — the
`/ccapi` root tells you which versions exist.

```bash
# 1. Root document — lists every supported endpoint, grouped by version
curl http://192.168.0.179:8080/ccapi

# 2. Device information (model, firmware, serial)
curl http://192.168.0.179:8080/ccapi/ver100/deviceinformation

# 3. Storage content roots (e.g. /ccapi/ver110/contents/sd)
curl http://192.168.0.179:8080/ccapi/ver110/contents

# 4. Directories on a card
curl http://192.168.0.179:8080/ccapi/ver110/contents/sd

# 5. Page count + total items in a directory
curl "http://192.168.0.179:8080/ccapi/ver110/contents/sd/100CANON?kind=number"

# 6. One page of file paths
curl "http://192.168.0.179:8080/ccapi/ver110/contents/sd/100CANON?type=all&page=1"

# 7. Metadata for one file (size, last modified)
curl "http://192.168.0.179:8080/ccapi/ver110/contents/sd/100CANON/IMG_0001.CR3?kind=info"

# 8. Download a full-resolution file to disk (streamed)
curl -o IMG_0001.CR3 \
  "http://192.168.0.179:8080/ccapi/ver110/contents/sd/100CANON/IMG_0001.CR3"
```

If the camera requires login, add `--digest -u user:pass` (or `-u user:pass`
for Basic) to each request.

---

## Error handling

The backend maps client failures to clear HTTP statuses, surfaced in the UI:

| Situation              | Behaviour                                                  |
| ---------------------- | ---------------------------------------------------------- |
| Camera offline / no route | 502 "Cannot reach camera …"                             |
| Timeout                | 504 "…did not respond in time"                             |
| Auth failure (401/403) | 401 "Authentication failed" (tries Digest, then Basic)     |
| Unsupported endpoint   | 501 (also: version auto-discovery from `/ccapi`)           |
| Partial download       | `.part` temp file is deleted; file marked **error**        |
| Filename collision      | Auto-renamed to `name_1.CR3`, `name_2.CR3`, …             |
| Cancellation           | In-flight `.part` removed; remaining files marked cancelled |

---

## Project layout

```
backend/
  app/
    ccapi_endpoints.py   # all CCAPI URL/param constants (single source to tweak)
    ccapi_client.py      # CanonCcapiClient + error types + streaming download
    downloads.py         # background batch download jobs (progress + cancel)
    main.py              # FastAPI routes
  requirements.txt
frontend/
  src/
    api.js               # backend API wrapper
    App.jsx              # connect / browse / select / download UI
    styles.css
```

## Notes & limitations (MVP)

- Single camera, single process; connection/download state is in memory.
- Destination is typed as an absolute path (browsers can't expose real
  filesystem paths). The backend validates the folder exists before starting.
- File size/date come from per-file `?kind=info` calls, fetched lazily per page.
