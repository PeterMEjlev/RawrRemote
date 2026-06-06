"""FastAPI backend for the Canon CCAPI RAW downloader MVP.

Single-camera, single-process. The connected client is held in module state.
"""

from __future__ import annotations

from typing import Optional

from fastapi import FastAPI, HTTPException, Response
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

from .ccapi_client import (
    AuthError,
    CameraOfflineError,
    CanonCcapiClient,
    CcapiError,
    CcapiHttpError,
    CcapiTimeoutError,
    UnsupportedEndpointError,
)
from .downloads import DownloadManager

app = FastAPI(title="Canon CCAPI RAW Downloader", version="0.1.0")

# Dev convenience: the Vite frontend runs on a different port.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# --- module state (MVP: one camera at a time) -----------------------------
_client: Optional[CanonCcapiClient] = None
_downloads = DownloadManager()


def _require_client() -> CanonCcapiClient:
    if _client is None:
        raise HTTPException(status_code=409, detail="Not connected to a camera. Call /api/connect first.")
    return _client


def _handle_ccapi(exc: CcapiError) -> HTTPException:
    """Map a client error to an HTTP response the frontend can show."""
    if isinstance(exc, CameraOfflineError):
        return HTTPException(status_code=502, detail=str(exc))
    if isinstance(exc, CcapiTimeoutError):
        return HTTPException(status_code=504, detail=str(exc))
    if isinstance(exc, AuthError):
        return HTTPException(status_code=401, detail=str(exc))
    if isinstance(exc, UnsupportedEndpointError):
        return HTTPException(status_code=501, detail=str(exc))
    if isinstance(exc, CcapiHttpError):
        return HTTPException(status_code=502, detail=str(exc))
    return HTTPException(status_code=500, detail=str(exc))


# --- request models -------------------------------------------------------

class ConnectRequest(BaseModel):
    host: str
    port: int = 8080
    username: Optional[str] = None
    password: Optional[str] = None
    scheme: str = "http"


class DownloadRequest(BaseModel):
    destination: str
    files: list[dict]   # {name, url, folder, size}


# --- routes ---------------------------------------------------------------

@app.post("/api/connect")
def connect(req: ConnectRequest):
    """Connect to the camera: verify /ccapi and read device information."""
    global _client
    client = CanonCcapiClient(
        host=req.host, port=req.port,
        username=req.username, password=req.password,
        scheme=req.scheme,
    )
    try:
        root = client.get_root()
        device = client.get_device_information()
    except CcapiError as exc:
        raise _handle_ccapi(exc)

    _client = client
    return {
        "connected": True,
        "deviceInformation": device,
        "apiVersions": [k for k in root.keys()],
    }


@app.get("/api/status")
def status():
    return {"connected": _client is not None}


@app.post("/api/disconnect")
def disconnect():
    global _client
    _client = None
    return {"connected": False}


@app.get("/api/storages")
def storages():
    client = _require_client()
    try:
        return {"storages": client.list_storage()}
    except CcapiError as exc:
        raise _handle_ccapi(exc)


@app.get("/api/folder")
def folder(path: str):
    """List immediate child paths of a storage root or directory (lazy browse)."""
    client = _require_client()
    try:
        return {"path": path, "children": client.list_folder(path)}
    except CcapiError as exc:
        raise _handle_ccapi(exc)


@app.get("/api/raw-files")
def raw_files(folder: str, page: int = 1):
    """List one page of .CR3 files (with size/date) in a directory."""
    client = _require_client()
    try:
        result = client.list_raw_files(folder, page=page)
    except CcapiError as exc:
        raise _handle_ccapi(exc)
    return {
        "page": result.page,
        "pageCount": result.page_count,
        "totalContents": result.total_contents,
        "hasMore": result.has_more,
        "files": [
            {"name": f.name, "path": f.path, "url": f.url,
             "folder": f.folder, "size": f.size, "modified": f.modified}
            for f in result.files
        ],
    }


@app.get("/api/thumbnail")
def thumbnail(path: str):
    """Proxy the embedded preview JPEG for a content path (keeps camera auth server-side)."""
    client = _require_client()
    try:
        data, content_type = client.get_thumbnail(path)
    except CcapiError as exc:
        raise _handle_ccapi(exc)
    return Response(content=data, media_type=content_type,
                    headers={"Cache-Control": "max-age=3600"})


@app.post("/api/download")
def start_download(req: DownloadRequest):
    client = _require_client()
    if not req.files:
        raise HTTPException(status_code=400, detail="No files selected.")
    try:
        job = _downloads.start(client, req.destination, req.files)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    return {"jobId": job.id, "job": job.to_dict()}


@app.get("/api/download/{job_id}")
def download_status(job_id: str):
    job = _downloads.get(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="Unknown job id.")
    return job.to_dict()


@app.post("/api/download/{job_id}/cancel")
def cancel_download(job_id: str):
    if not _downloads.cancel(job_id):
        raise HTTPException(status_code=404, detail="Unknown job id.")
    return {"cancelled": True}
