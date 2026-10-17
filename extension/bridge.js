// Isolated-world relay: page (inject.js, MAIN world)  <-- postMessage -->  here  <-- runtime -->  background.js
(() => {
  const TAG = 'lc-live';
  window.addEventListener('message', (ev) => {
    if (ev.source !== window) return;
    const d = ev.data;
    if (!d || d.tag !== TAG || d.dir !== 'to-ext') return;
    chrome.runtime.sendMessage({ type: d.type, code: d.code, offset: d.offset }, (resp) => {
      const err = chrome.runtime.lastError;
      const payload = err ? { ok: false, offline: true, error: err.message } : resp;
      window.postMessage({ tag: TAG, dir: 'to-page', id: d.id, type: d.type, payload }, '*');
    });
  });
  // Tell the page the bridge is alive (inject.js may have loaded first).
  window.postMessage({ tag: TAG, dir: 'to-page', type: 'bridge-ready' }, '*');
})();
