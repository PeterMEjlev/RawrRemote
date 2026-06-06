import React, { useEffect, useState } from "react";
import { api } from "./api.js";

// ---- helpers --------------------------------------------------------------

function formatSize(bytes) {
  if (bytes == null) return "—";
  const units = ["B", "KB", "MB", "GB"];
  let n = bytes;
  let i = 0;
  while (n >= 1024 && i < units.length - 1) {
    n /= 1024;
    i++;
  }
  return `${n.toFixed(i === 0 ? 0 : 1)} ${units[i]}`;
}

function label(path) {
  // /ccapi/ver110/contents/sd/100CANON -> "100CANON"; storage root -> "sd"
  return path.replace(/\/+$/, "").split("/").pop();
}

// ---- connect panel --------------------------------------------------------

function ConnectPanel({ onConnected }) {
  const [host, setHost] = useState("192.168.0.179");
  const [port, setPort] = useState(8080);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [device, setDevice] = useState(null);

  async function connect() {
    setBusy(true);
    setError(null);
    try {
      const res = await api.connect({
        host,
        port: Number(port),
        username: username || null,
        password: password || null,
      });
      setDevice(res.deviceInformation);
      onConnected(res);
    } catch (e) {
      setDevice(null);
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="panel">
      <h1>Canon CCAPI RAW Downloader</h1>
      <div className="row">
        <div className="field">
          <label>Camera host / IP</label>
          <input type="text" value={host} onChange={(e) => setHost(e.target.value)} />
        </div>
        <div className="field">
          <label>Port</label>
          <input type="number" value={port} onChange={(e) => setPort(e.target.value)} style={{ width: 80 }} />
        </div>
        <div className="field">
          <label>Username (optional)</label>
          <input type="text" value={username} onChange={(e) => setUsername(e.target.value)} />
        </div>
        <div className="field">
          <label>Password (optional)</label>
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} />
        </div>
        <button className="primary" onClick={connect} disabled={busy || !host}>
          {busy ? "Connecting…" : "Connect"}
        </button>
      </div>

      {device && (
        <div className="status ok">
          Connected to <b>{device.productname || "camera"}</b>
          {device.firmwareversion ? ` (firmware ${device.firmwareversion})` : ""}
          {device.serialnumber ? ` · SN ${device.serialnumber}` : ""}
        </div>
      )}
      {error && <div className="status err">Connection failed: {error}</div>}
    </div>
  );
}

// ---- storage / folder tree ------------------------------------------------

function TreeNode({ path, isFolder, selectedFolder, onSelectFolder }) {
  const [expanded, setExpanded] = useState(false);
  const [children, setChildren] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(false);

  async function toggle() {
    // A storage root expands into folders; a folder is selected to list files.
    if (isFolder) {
      onSelectFolder(path);
      return;
    }
    if (!expanded && children == null) {
      setLoading(true);
      try {
        const res = await api.folder(path);
        setChildren(res.children);
      } catch (e) {
        setError(e.message);
      } finally {
        setLoading(false);
      }
    }
    setExpanded(!expanded);
  }

  return (
    <li>
      <button
        className={`node ${selectedFolder === path ? "active" : ""}`}
        onClick={toggle}
      >
        {isFolder ? "📁" : expanded ? "📂" : "💾"} {label(path)}
      </button>
      {loading && <span className="muted"> loading…</span>}
      {error && <span className="status err">{error}</span>}
      {expanded && children && (
        <ul className="tree">
          {children.length === 0 && <li className="muted">empty</li>}
          {children.map((c) => (
            <TreeNode
              key={c}
              path={c}
              isFolder={true}
              selectedFolder={selectedFolder}
              onSelectFolder={onSelectFolder}
            />
          ))}
        </ul>
      )}
    </li>
  );
}

function StorageTree({ selectedFolder, onSelectFolder }) {
  const [storages, setStorages] = useState([]);
  const [error, setError] = useState(null);

  useEffect(() => {
    api
      .storages()
      .then((res) => setStorages(res.storages))
      .catch((e) => setError(e.message));
  }, []);

  return (
    <div className="panel">
      <b>Camera storage</b>
      {error && <div className="status err">{error}</div>}
      <ul className="tree">
        {storages.map((s) => (
          <TreeNode
            key={s}
            path={s}
            isFolder={false}
            selectedFolder={selectedFolder}
            onSelectFolder={onSelectFolder}
          />
        ))}
      </ul>
    </div>
  );
}

// ---- file table -----------------------------------------------------------

function FileTable({ folder, selected, setSelected }) {
  const [page, setPage] = useState(1);
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  useEffect(() => {
    setPage(1);
  }, [folder]);

  useEffect(() => {
    if (!folder) return;
    setLoading(true);
    setError(null);
    api
      .rawFiles(folder, page)
      .then(setData)
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false));
  }, [folder, page]);

  if (!folder) return <div className="panel muted">Select a folder to list .CR3 files.</div>;

  const files = data?.files || [];

  function toggleFile(f) {
    setSelected((prev) => {
      const next = { ...prev };
      if (next[f.url]) delete next[f.url];
      else next[f.url] = f;
      return next;
    });
  }

  function toggleAll(checked) {
    setSelected((prev) => {
      const next = { ...prev };
      files.forEach((f) => {
        if (checked) next[f.url] = f;
        else delete next[f.url];
      });
      return next;
    });
  }

  const allChecked = files.length > 0 && files.every((f) => selected[f.url]);

  return (
    <div className="panel">
      <div className="row" style={{ justifyContent: "space-between" }}>
        <b>{label(folder)} — .CR3 files</b>
        {data && (
          <span className="muted">
            page {data.page} / {data.pageCount} · {data.totalContents} items in folder
          </span>
        )}
      </div>

      {loading && <div className="muted">Loading…</div>}
      {error && <div className="status err">{error}</div>}

      {!loading && !error && (
        <>
          <table>
            <thead>
              <tr>
                <th>
                  <input type="checkbox" checked={allChecked} onChange={(e) => toggleAll(e.target.checked)} />
                </th>
                <th>Preview</th>
                <th>Filename</th>
                <th>Size</th>
                <th>Modified</th>
                <th>Folder</th>
              </tr>
            </thead>
            <tbody>
              {files.length === 0 && (
                <tr>
                  <td colSpan={6} className="muted">No .CR3 files on this page.</td>
                </tr>
              )}
              {files.map((f) => (
                <tr key={f.url}>
                  <td>
                    <input type="checkbox" checked={!!selected[f.url]} onChange={() => toggleFile(f)} />
                  </td>
                  <td>
                    <img
                      className="thumb"
                      src={api.thumbnailUrl(f.path)}
                      alt={f.name}
                      loading="lazy"
                      onClick={() => toggleFile(f)}
                      onError={(e) => {
                        e.currentTarget.style.visibility = "hidden";
                      }}
                    />
                  </td>
                  <td>{f.name}</td>
                  <td>{formatSize(f.size)}</td>
                  <td>{f.modified || "—"}</td>
                  <td className="muted">{f.folder}</td>
                </tr>
              ))}
            </tbody>
          </table>

          {data && data.pageCount > 1 && (
            <div className="row" style={{ marginTop: 10 }}>
              <button disabled={page <= 1} onClick={() => setPage((p) => p - 1)}>
                ← Prev
              </button>
              <button disabled={!data.hasMore} onClick={() => setPage((p) => p + 1)}>
                Next →
              </button>
            </div>
          )}
        </>
      )}
    </div>
  );
}

// ---- download panel -------------------------------------------------------

function DownloadPanel({ selected, setSelected }) {
  const [destination, setDestination] = useState("");
  const [job, setJob] = useState(null);
  const [jobId, setJobId] = useState(null);
  const [error, setError] = useState(null);
  const [starting, setStarting] = useState(false);

  const selectedList = Object.values(selected);

  // Poll job status until it stops running.
  useEffect(() => {
    if (!jobId) return;
    let active = true;
    const tick = async () => {
      try {
        const j = await api.downloadStatus(jobId);
        if (!active) return;
        setJob(j);
        if (j.status === "running") setTimeout(tick, 600);
      } catch (e) {
        if (active) setError(e.message);
      }
    };
    tick();
    return () => {
      active = false;
    };
  }, [jobId]);

  async function start() {
    setError(null);
    setStarting(true);
    try {
      const payload = selectedList.map((f) => ({
        name: f.name,
        url: f.url,
        folder: f.folder,
        size: f.size,
      }));
      const res = await api.startDownload(destination, payload);
      setJob(res.job);
      setJobId(res.jobId);
    } catch (e) {
      setError(e.message);
    } finally {
      setStarting(false);
    }
  }

  async function cancel() {
    if (jobId) await api.cancelDownload(jobId).catch(() => {});
  }

  return (
    <div className="panel">
      <div className="row">
        <div className="field" style={{ flex: 1, minWidth: 320 }}>
          <label>Local destination folder (absolute path on the machine running the backend)</label>
          <input
            type="text"
            placeholder="C:\\Users\\you\\Pictures\\CanonRAW"
            value={destination}
            onChange={(e) => setDestination(e.target.value)}
          />
        </div>
        <button
          className="primary"
          disabled={starting || !destination || selectedList.length === 0}
          onClick={start}
        >
          Download selected ({selectedList.length})
        </button>
        {job && job.status === "running" && <button onClick={cancel}>Cancel</button>}
        {selectedList.length > 0 && <button onClick={() => setSelected({})}>Clear selection</button>}
      </div>

      {error && <div className="status err">{error}</div>}

      {job && (
        <div style={{ marginTop: 12 }}>
          <div className="muted">
            Job {job.id.slice(0, 8)} — status: <b>{job.status}</b> → {job.destination}
          </div>
          <table>
            <thead>
              <tr>
                <th>File</th>
                <th>Progress</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {job.files.map((f, i) => {
                const pct = f.size ? Math.min(100, Math.round((f.downloaded / f.size) * 100)) : 0;
                return (
                  <tr key={i}>
                    <td>{f.name}</td>
                    <td>
                      <span className="progressbar">
                        <span style={{ width: `${f.size ? pct : f.status === "done" ? 100 : 0}%` }} />
                      </span>{" "}
                      <span className="muted">
                        {formatSize(f.downloaded)}
                        {f.size ? ` / ${formatSize(f.size)} (${pct}%)` : ""}
                      </span>
                    </td>
                    <td>
                      <span className={`pill ${f.status}`}>{f.status}</span>
                      {f.error && <div className="status err">{f.error}</div>}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

// ---- root -----------------------------------------------------------------

export default function App() {
  const [connected, setConnected] = useState(false);
  const [selectedFolder, setSelectedFolder] = useState(null);
  const [selected, setSelected] = useState({}); // url -> file

  return (
    <div className="app">
      <ConnectPanel onConnected={() => setConnected(true)} />

      {connected && (
        <>
          <DownloadPanel selected={selected} setSelected={setSelected} />
          <div className="two-col">
            <StorageTree selectedFolder={selectedFolder} onSelectFolder={setSelectedFolder} />
            <FileTable folder={selectedFolder} selected={selected} setSelected={setSelected} />
          </div>
        </>
      )}
    </div>
  );
}
