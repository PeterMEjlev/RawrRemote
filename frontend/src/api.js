// Thin wrapper around the backend API. All calls go through the Vite proxy
// (/api -> http://localhost:8000).

async function request(path, options = {}) {
  const resp = await fetch(`/api${path}`, {
    headers: { "Content-Type": "application/json" },
    ...options,
  });
  let body = null;
  try {
    body = await resp.json();
  } catch {
    body = null;
  }
  if (!resp.ok) {
    const detail = (body && body.detail) || `HTTP ${resp.status}`;
    throw new Error(detail);
  }
  return body;
}

export const api = {
  connect: (payload) =>
    request("/connect", { method: "POST", body: JSON.stringify(payload) }),
  disconnect: () => request("/disconnect", { method: "POST" }),
  storages: () => request("/storages"),
  folder: (path) => request(`/folder?path=${encodeURIComponent(path)}`),
  rawFiles: (folder, page = 1) =>
    request(`/raw-files?folder=${encodeURIComponent(folder)}&page=${page}`),
  thumbnailUrl: (path) => `/api/thumbnail?path=${encodeURIComponent(path)}`,
  startDownload: (destination, files) =>
    request("/download", {
      method: "POST",
      body: JSON.stringify({ destination, files }),
    }),
  downloadStatus: (jobId) => request(`/download/${jobId}`),
  cancelDownload: (jobId) =>
    request(`/download/${jobId}/cancel`, { method: "POST" }),
};
