// Service worker: the only place that talks to the local compile server.
// Content scripts can't fetch 127.0.0.1 from https://leetcode.com without
// private-network-access prompts; the extension origin can.

const DEFAULT_PORT = 47321;

async function getPort() {
  const { port } = await chrome.storage.local.get({ port: DEFAULT_PORT });
  return port;
}

async function compile(code) {
  const port = await getPort();
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 8000);
  try {
    const res = await fetch(`http://127.0.0.1:${port}/compile`, {
      method: 'POST',
      headers: { 'Content-Type': 'text/plain; charset=utf-8' },
      body: code,
      signal: ctrl.signal,
    });
    if (!res.ok) return { ok: false, error: `server HTTP ${res.status}` };
    return await res.json();
  } catch (e) {
    return { ok: false, offline: true, error: String(e && e.message || e) };
  } finally {
    clearTimeout(timer);
  }
}

async function complete(code, offset) {
  const port = await getPort();
  try {
    const res = await fetch(`http://127.0.0.1:${port}/complete`, {
      method: 'POST',
      headers: { 'Content-Type': 'text/plain; charset=utf-8', 'X-Lc-Offset': String(offset | 0) },
      body: code,
      signal: AbortSignal.timeout(3000),
    });
    if (!res.ok) return { ok: false, error: `server HTTP ${res.status}` };
    return await res.json();
  } catch (e) {
    return { ok: false, offline: true, error: String(e && e.message || e) };
  }
}

async function health() {
  const port = await getPort();
  try {
    const res = await fetch(`http://127.0.0.1:${port}/health`, { signal: AbortSignal.timeout(2000) });
    return await res.json();
  } catch (e) {
    return { ok: false, offline: true, error: String(e && e.message || e) };
  }
}

chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  if (!msg || typeof msg !== 'object') return;
  if (msg.type === 'compile') {
    compile(msg.code).then(sendResponse);
    return true; // async
  }
  if (msg.type === 'complete') {
    complete(msg.code, msg.offset).then(sendResponse);
    return true;
  }
  if (msg.type === 'health') {
    health().then(sendResponse);
    return true;
  }
});
