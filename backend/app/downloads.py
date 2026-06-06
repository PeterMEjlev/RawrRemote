"""Download job manager.

Runs a batch download in a background thread, tracks per-file progress, and
supports cancellation. State is kept in memory (single-process MVP) and polled
by the frontend.
"""

from __future__ import annotations

import os
import threading
import time
import uuid
from dataclasses import dataclass, field
from typing import Optional

from .ccapi_client import CanonCcapiClient, CcapiError


@dataclass
class FileProgress:
    name: str
    url: str
    folder: str
    size: Optional[int] = None
    downloaded: int = 0
    status: str = "pending"   # pending | downloading | done | error | cancelled
    error: Optional[str] = None
    saved_path: Optional[str] = None


@dataclass
class DownloadJob:
    id: str
    destination: str
    files: list[FileProgress] = field(default_factory=list)
    status: str = "running"   # running | done | cancelled | error
    started_at: float = field(default_factory=time.time)
    finished_at: Optional[float] = None
    _cancel: threading.Event = field(default_factory=threading.Event)

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "destination": self.destination,
            "status": self.status,
            "startedAt": self.started_at,
            "finishedAt": self.finished_at,
            "files": [
                {
                    "name": f.name,
                    "folder": f.folder,
                    "size": f.size,
                    "downloaded": f.downloaded,
                    "status": f.status,
                    "error": f.error,
                    "savedPath": f.saved_path,
                }
                for f in self.files
            ],
        }


class DownloadManager:
    def __init__(self):
        self._jobs: dict[str, DownloadJob] = {}
        self._lock = threading.Lock()

    def start(self, client: CanonCcapiClient, destination: str, files: list[dict]) -> DownloadJob:
        """Begin a batch download. `files` items: {name, url, folder, size}."""
        if not os.path.isdir(destination):
            raise ValueError(f"Destination folder does not exist: {destination}")

        job = DownloadJob(
            id=uuid.uuid4().hex,
            destination=destination,
            files=[
                FileProgress(
                    name=f["name"], url=f["url"],
                    folder=f.get("folder", ""), size=f.get("size"),
                )
                for f in files
            ],
        )
        with self._lock:
            self._jobs[job.id] = job

        thread = threading.Thread(target=self._run, args=(client, job), daemon=True)
        thread.start()
        return job

    def _run(self, client: CanonCcapiClient, job: DownloadJob) -> None:
        for fp in job.files:
            if job._cancel.is_set():
                fp.status = "cancelled"
                continue

            fp.status = "downloading"
            dest = os.path.join(job.destination, fp.name)

            def on_progress(done: int, _total, _fp=fp):
                _fp.downloaded = done

            try:
                saved = client.download_file(
                    fp.url, dest,
                    progress_callback=on_progress,
                    cancel_check=job._cancel.is_set,
                )
                fp.saved_path = saved
                fp.status = "done"
            except CcapiError as exc:
                if job._cancel.is_set():
                    fp.status = "cancelled"
                else:
                    fp.status = "error"
                    fp.error = str(exc)
            except Exception as exc:  # noqa: BLE001 - never let the worker die silently
                fp.status = "error"
                fp.error = str(exc)

        if job._cancel.is_set():
            job.status = "cancelled"
        elif any(f.status == "error" for f in job.files):
            job.status = "error"
        else:
            job.status = "done"
        job.finished_at = time.time()

    def get(self, job_id: str) -> Optional[DownloadJob]:
        with self._lock:
            return self._jobs.get(job_id)

    def cancel(self, job_id: str) -> bool:
        job = self.get(job_id)
        if not job:
            return False
        job._cancel.set()
        return True
