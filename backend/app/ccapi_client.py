"""Canon CCAPI HTTP client.

Talks to a Canon camera (e.g. EOS R5) over its Camera Control API. This MVP
only implements *reading* storage and *downloading* RAW files. No live view,
no shooting, no settings changes.
"""

from __future__ import annotations

import os
import posixpath
from dataclasses import dataclass, field
from typing import Callable, Iterable, Optional
from urllib.parse import urlparse

import requests
from requests.auth import HTTPBasicAuth, HTTPDigestAuth

from . import ccapi_endpoints as ep


# --- Errors ---------------------------------------------------------------
# One small exception hierarchy so the API layer can map failures to clean,
# user-facing messages (camera offline, auth failure, timeout, ...).

class CcapiError(Exception):
    """Base class for all CCAPI client errors."""


class CameraOfflineError(CcapiError):
    """Could not reach the camera (connection refused / DNS / network down)."""


class CcapiTimeoutError(CcapiError):
    """The camera did not respond within the timeout."""


class AuthError(CcapiError):
    """Authentication failed (401/403)."""


class UnsupportedEndpointError(CcapiError):
    """The camera/firmware does not expose a required endpoint (404)."""


class CcapiHttpError(CcapiError):
    """Any other non-success HTTP response."""

    def __init__(self, status_code: int, message: str):
        self.status_code = status_code
        super().__init__(message)


# --- Data models ----------------------------------------------------------

@dataclass
class RawFile:
    name: str
    path: str            # CCAPI content path, e.g. /ccapi/ver110/contents/sd/100CANON/IMG_0001.CR3
    url: str             # absolute download URL
    folder: str          # human-readable folder, e.g. sd/100CANON
    size: Optional[int] = None
    modified: Optional[str] = None


@dataclass
class FolderPage:
    files: list[RawFile] = field(default_factory=list)
    page: int = 1
    page_count: int = 1
    total_contents: int = 0

    @property
    def has_more(self) -> bool:
        return self.page < self.page_count


class CanonCcapiClient:
    """Minimal CCAPI client focused on browsing storage and downloading RAW.

    Parameters
    ----------
    host, port:
        Camera address. Port defaults to 8080.
    username, password:
        Optional CCAPI credentials. If omitted, requests are sent unauthenticated.
    scheme:
        "http" (default) or "https".
    timeout:
        Per-request timeout in seconds for metadata calls.
    """

    def __init__(
        self,
        host: str,
        port: int = 8080,
        username: Optional[str] = None,
        password: Optional[str] = None,
        scheme: str = "http",
        timeout: float = 10.0,
    ):
        self.base_url = f"{scheme}://{host}:{port}"
        self.timeout = timeout
        self.session = requests.Session()
        if username:
            # CCAPI may use Digest or Basic depending on firmware/config.
            # requests resolves Digest via the WWW-Authenticate challenge; we
            # attach Digest first and fall back to Basic transparently below.
            self._username = username
            self._password = password or ""
            self.session.auth = HTTPDigestAuth(self._username, self._password)
        else:
            self._username = None
            self._password = None

        # Endpoint paths resolved against the live root document.
        self._endpoints: dict[str, str] = {}

    # -- low level ---------------------------------------------------------

    def _abs(self, path_or_url: str) -> str:
        if path_or_url.startswith("http://") or path_or_url.startswith("https://"):
            return path_or_url
        if not path_or_url.startswith("/"):
            path_or_url = "/" + path_or_url
        return self.base_url + path_or_url

    def _request(self, method: str, path_or_url: str, *, stream: bool = False,
                 params: Optional[dict] = None, timeout: Optional[float] = None) -> requests.Response:
        url = self._abs(path_or_url)
        try:
            resp = self.session.request(
                method, url, params=params, stream=stream,
                timeout=timeout or self.timeout,
            )
        except requests.exceptions.ConnectTimeout as exc:
            raise CcapiTimeoutError(f"Connection to {self.base_url} timed out") from exc
        except requests.exceptions.ReadTimeout as exc:
            raise CcapiTimeoutError(f"Camera at {self.base_url} did not respond in time") from exc
        except requests.exceptions.ConnectionError as exc:
            raise CameraOfflineError(
                f"Cannot reach camera at {self.base_url}. Is it on, on the same "
                f"network, and is CCAPI enabled?"
            ) from exc
        except requests.exceptions.RequestException as exc:
            raise CcapiError(str(exc)) from exc

        # If Digest failed but the camera actually wants Basic, retry once.
        if resp.status_code == 401 and self._username and isinstance(self.session.auth, HTTPDigestAuth):
            self.session.auth = HTTPBasicAuth(self._username, self._password)
            return self._request(method, path_or_url, stream=stream, params=params, timeout=timeout)

        if resp.status_code in (401, 403):
            raise AuthError("Authentication failed. Check the username and password.")
        if resp.status_code == 404:
            raise UnsupportedEndpointError(f"Endpoint not found on camera: {url}")
        if not resp.ok:
            raise CcapiHttpError(resp.status_code, f"CCAPI returned HTTP {resp.status_code} for {url}")
        return resp

    def _get_json(self, path_or_url: str, params: Optional[dict] = None) -> dict:
        resp = self._request("GET", path_or_url, params=params)
        try:
            return resp.json()
        except ValueError as exc:
            raise CcapiError(f"Expected JSON from {path_or_url} but got: {resp.text[:200]}") from exc

    # -- discovery ---------------------------------------------------------

    def get_root(self) -> dict:
        """GET /ccapi — the API capability document. Also primes endpoint discovery."""
        root = self._get_json(ep.ROOT)
        self._index_endpoints(root)
        return root

    def _index_endpoints(self, root: dict) -> None:
        """Build a {suffix: full_path} map from the root document.

        The root groups endpoints by version (ver100, ver110, ...). We index by
        the version-independent suffix (everything after /ccapi/verXXX/) and
        keep the highest version seen, so callers ask for "contents" or
        "deviceinformation" without caring about the version number.
        """
        best: dict[str, tuple[str, str]] = {}
        if not isinstance(root, dict):
            return
        for version, entries in root.items():
            if not isinstance(entries, list):
                continue
            for entry in entries:
                path = entry.get("path") if isinstance(entry, dict) else None
                if not path:
                    continue
                # /ccapi/ver110/contents -> suffix "contents"
                parts = path.strip("/").split("/")
                if len(parts) < 3:
                    continue
                suffix = "/".join(parts[2:])
                prev = best.get(suffix)
                if prev is None or version > prev[0]:
                    best[suffix] = (version, path)
        self._endpoints = {suffix: path for suffix, (_, path) in best.items()}

    def _resolve(self, suffix: str, fallbacks: Iterable[str]) -> str:
        """Return the live path for an endpoint suffix, else the first fallback."""
        if suffix in self._endpoints:
            return self._endpoints[suffix]
        fallbacks = list(fallbacks)
        if not self._endpoints:
            # Discovery not run yet; try to run it once, ignore failures.
            try:
                self.get_root()
            except CcapiError:
                pass
            if suffix in self._endpoints:
                return self._endpoints[suffix]
        if not fallbacks:
            raise UnsupportedEndpointError(f"No known path for '{suffix}'")
        return fallbacks[0]

    # -- public read API ---------------------------------------------------

    def get_device_information(self) -> dict:
        """GET /ccapi/verXXX/deviceinformation — model name, serial, firmware."""
        path = self._resolve("deviceinformation", ep.DEVICE_INFORMATION)
        return self._get_json(path)

    def list_storage(self) -> list[str]:
        """List storage content roots, e.g. ['/ccapi/ver110/contents/sd', ...]."""
        contents = self._resolve("contents", ep.CONTENTS)
        data = self._get_json(contents)
        return list(data.get("path", []))

    def list_folder(self, path_or_url: str) -> list[str]:
        """List immediate children (sub-paths) of a contents path.

        Works for both storage roots (returns directories) and directories
        (returns file paths). Use list_raw_files() for paginated, filtered,
        metadata-rich file listings.
        """
        data = self._get_json(path_or_url)
        return list(data.get("path", []))

    def _folder_label(self, folder_path: str) -> str:
        """Turn a contents path into a short label like 'sd/100CANON'."""
        parts = folder_path.strip("/").split("/")
        # drop /ccapi/verXXX/contents prefix (first 3 segments)
        return "/".join(parts[3:]) if len(parts) > 3 else folder_path

    def folder_page_count(self, folder_path: str) -> tuple[int, int]:
        """Return (page_count, total_contents) for a directory.

        Uses ?kind=number. If the camera does not support it, assume one page.
        """
        try:
            data = self._get_json(folder_path, params={ep.PARAM_KIND: ep.KIND_NUMBER})
        except UnsupportedEndpointError:
            return (1, 0)
        page_count = int(data.get("pagenumber", 1) or 1)
        total = int(data.get("contentsnumber", 0) or 0)
        return (max(page_count, 1), total)

    def get_file_info(self, file_path_or_url: str) -> dict:
        """GET <file>?kind=info — size + last modified date for a single file."""
        return self._get_json(file_path_or_url, params={ep.PARAM_KIND: ep.KIND_INFO})

    def get_thumbnail(self, file_path_or_url: str) -> tuple[bytes, str]:
        """GET <file>?kind=thumbnail — the embedded preview JPEG.

        Returns (image_bytes, content_type). Thumbnails are small, so reading
        them fully into memory is fine (unlike full RAW downloads).
        """
        resp = self._request(
            "GET", file_path_or_url,
            params={ep.PARAM_KIND: ep.KIND_THUMBNAIL}, stream=True,
        )
        try:
            content_type = resp.headers.get("Content-Type", "image/jpeg")
            return resp.content, content_type
        finally:
            resp.close()

    def list_raw_files(
        self,
        folder: str,
        page: int = 1,
        extensions: Iterable[str] = ep.RAW_EXTENSIONS,
        with_info: bool = True,
    ) -> FolderPage:
        """List RAW files in one page of a directory.

        Lazy by design: only the requested page is fetched. The frontend pages
        through folders so the whole card is never loaded at once.

        Parameters
        ----------
        folder:
            A directory contents path, e.g. /ccapi/ver110/contents/sd/100CANON.
        page:
            1-based page number.
        extensions:
            Filename suffixes to keep (default: .cr3).
        with_info:
            When True, fetch size/modified date per file via ?kind=info.
        """
        exts = tuple(e.lower() for e in extensions)
        page_count, total = self.folder_page_count(folder)
        page = max(1, min(page, page_count))

        data = self._get_json(folder, params={ep.PARAM_TYPE: ep.TYPE_ALL, ep.PARAM_PAGE: page})
        paths = list(data.get("path", []))

        label = self._folder_label(folder)
        files: list[RawFile] = []
        for p in paths:
            name = posixpath.basename(urlparse(p).path)
            if not name.lower().endswith(exts):
                continue
            files.append(RawFile(name=name, path=p, url=self._abs(p), folder=label))

        if with_info:
            for f in files:
                try:
                    info = self.get_file_info(f.path)
                    f.size = info.get("filesize")
                    f.modified = info.get("lastmodifieddate")
                except CcapiError:
                    # Metadata is best-effort; never fail the whole listing.
                    pass

        return FolderPage(files=files, page=page, page_count=page_count, total_contents=total)

    # -- download ----------------------------------------------------------

    def download_file(
        self,
        file_url: str,
        destination_path: str,
        progress_callback: Optional[Callable[[int, Optional[int]], None]] = None,
        cancel_check: Optional[Callable[[], bool]] = None,
        chunk_size: int = 1024 * 1024,
    ) -> str:
        """Stream a file to disk without loading it fully into memory.

        Downloads to a temporary `.part` file, then atomically renames it on
        success. Resolves filename collisions by appending _1, _2, ... Honors a
        cancel_check callable (return True to abort) and reports bytes via
        progress_callback(bytes_done, total_bytes_or_None).

        Returns the final path written.
        """
        final_path = _resolve_collision(destination_path)
        tmp_path = final_path + ".part"
        os.makedirs(os.path.dirname(final_path) or ".", exist_ok=True)

        # No metadata timeout on the body: large RAW files take a while. We use
        # a connect/read timeout tuple so a dead socket still fails fast.
        resp = self._request("GET", file_url, stream=True, timeout=(self.timeout, 60))
        total = resp.headers.get("Content-Length")
        total_bytes = int(total) if total and total.isdigit() else None

        done = 0
        cancelled = False
        try:
            with open(tmp_path, "wb") as fh:
                for chunk in resp.iter_content(chunk_size=chunk_size):
                    if cancel_check and cancel_check():
                        cancelled = True
                        break
                    if not chunk:
                        continue
                    fh.write(chunk)
                    done += len(chunk)
                    if progress_callback:
                        progress_callback(done, total_bytes)
        except requests.exceptions.RequestException as exc:
            _safe_remove(tmp_path)
            raise CcapiError(f"Download failed for {file_url}: {exc}") from exc
        finally:
            resp.close()

        if cancelled:
            _safe_remove(tmp_path)
            raise CcapiError("Download cancelled")

        if total_bytes is not None and done != total_bytes:
            _safe_remove(tmp_path)
            raise CcapiError(
                f"Partial download for {file_url}: got {done} of {total_bytes} bytes"
            )

        os.replace(tmp_path, final_path)
        return final_path


# --- helpers --------------------------------------------------------------

def _resolve_collision(path: str) -> str:
    """If `path` exists, return path with _1, _2, ... inserted before the ext."""
    if not os.path.exists(path):
        return path
    root, ext = os.path.splitext(path)
    i = 1
    while True:
        candidate = f"{root}_{i}{ext}"
        if not os.path.exists(candidate):
            return candidate
        i += 1


def _safe_remove(path: str) -> None:
    try:
        if os.path.exists(path):
            os.remove(path)
    except OSError:
        pass
