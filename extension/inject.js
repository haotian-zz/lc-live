// MAIN-world script: hooks LeetCode's Monaco editor, ships Java buffers to the
// local compile server (via bridge.js -> background.js) and paints javac
// diagnostics back as Monaco markers + inline "error lens" text.
(() => {
  if (window.__lcLiveInstalled) return;
  window.__lcLiveInstalled = true;

  const TAG = 'lc-live';
  const OWNER = 'lc-live';
  const DEBOUNCE_MS = 400;
  const OFFLINE_RETRY_MS = 5000;

  // ---------------------------------------------------------------- styles
  const css = `
    .lc-live-after::after {
      opacity: .75; font-style: italic; padding-left: 1.5em;
      pointer-events: none; user-select: none; -webkit-user-select: none; white-space: pre;
    }
    .lc-live-after-error::after { color: #f14c4c; }
    .lc-live-after-warning::after { color: #cca700; }
    .lc-live-line-error { background: rgba(241, 76, 76, .07); }
    .lc-live-pill {
      position: absolute; right: 14px; bottom: 10px; z-index: 50;
      font: 11px/1 -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
      padding: 5px 9px; border-radius: 999px; cursor: pointer; user-select: none;
      background: rgba(30,30,30,.85); color: #ccc; border: 1px solid rgba(255,255,255,.12);
      box-shadow: 0 1px 4px rgba(0,0,0,.3); transition: background .15s, color .15s; max-width: 60%;
      white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
    }
    .lc-live-pill[data-state="ok"] { color: #4ec9b0; }
    .lc-live-pill[data-state="error"] { color: #f14c4c; }
    .lc-live-pill[data-state="busy"] { color: #9cdcfe; }
    .lc-live-pill[data-state="offline"] { color: #cca700; }
    .lc-live-pill[data-state="disabled"] { color: #888; }
  `;
  const style = document.createElement('style');
  style.textContent = css;
  document.documentElement.appendChild(style);

  // ---------------------------------------------------------------- bridge
  let nextId = 1;
  const pending = new Map(); // id -> {resolve, timer}
  let bridgeReady = false;

  function request(type, code, extra = {}, timeoutMs = 10000) {
    return new Promise((resolve) => {
      const id = nextId++;
      const timer = setTimeout(() => {
        pending.delete(id);
        resolve({ ok: false, offline: true, error: bridgeReady ? 'timeout' : 'extension bridge not loaded' });
      }, timeoutMs);
      pending.set(id, { resolve, timer });
      window.postMessage({ tag: TAG, dir: 'to-ext', id, type, code, ...extra }, '*');
    });
  }

  window.addEventListener('message', (ev) => {
    if (ev.source !== window) return;
    const d = ev.data;
    if (!d || d.tag !== TAG || d.dir !== 'to-page') return;
    if (d.type === 'bridge-ready') { bridgeReady = true; return; }
    const p = pending.get(d.id);
    if (!p) return;
    clearTimeout(p.timer);
    pending.delete(d.id);
    p.resolve(d.payload || { ok: false, error: 'empty response' });
  });

  // ---------------------------------------------------------------- state
  let enabled = true;
  let offline = false;
  let offlineTimer = null;
  const tracked = new Map(); // model -> state
  let pill = null;
  let pillHost = null;

  function setPill(state, text) {
    if (!pill) return;
    pill.dataset.state = state;
    pill.textContent = text;
    pill.title = state === 'offline'
      ? 'lc-live: compile server not reachable. Start it with: java server/LcLiveServer.java  (click to retry)'
      : 'lc-live Java live compile  (click to enable/disable)';
  }

  // LeetCode ships Monaco with quickSuggestions off, so nothing pops up while typing an identifier.
  // Turn it on (with a short delay) for editors showing a tracked Java model; '.' is already a trigger char.
  function tuneEditor(monaco, ed) {
    if (ed.__lcLiveTuned) return;
    ed.__lcLiveTuned = true;
    const wbs = monaco.editor.EditorOptions && monaco.editor.EditorOptions.wordBasedSuggestions;
    const wordBasedOff = wbs && typeof wbs.defaultValue === 'boolean' ? false : 'off';
    try {
      ed.updateOptions({
        quickSuggestions: { other: true, comments: false, strings: false },
        quickSuggestionsDelay: 30,
        suggestOnTriggerCharacters: true,
        wordBasedSuggestions: wordBasedOff,
        suggest: { showStatusBar: false, preview: false, snippetsPreventQuickSuggestions: false },
      });
    } catch (e) { /* older Monaco without some options; defaults are fine */ }
  }

  function ensurePill(monaco, model) {
    const ed = monaco.editor.getEditors().find((e) => e.getModel() === model);
    if (!ed) return;
    tuneEditor(monaco, ed);
    const host = ed.getDomNode() && ed.getDomNode().parentElement;
    if (!host) return;
    if (pill && pillHost === host && host.contains(pill)) return;
    if (pill) pill.remove();
    pill = document.createElement('div');
    pill.className = 'lc-live-pill';
    pill.addEventListener('click', () => {
      if (offline) { offline = false; recompileAll(); return; }
      enabled = !enabled;
      if (!enabled) {
        for (const [m] of tracked) clearDiagnostics(monaco, m);
        setPill('disabled', 'lc-live off');
      } else {
        recompileAll();
      }
    });
    const cs = getComputedStyle(host);
    if (cs.position === 'static') host.style.position = 'relative';
    host.appendChild(pill);
    pillHost = host;
    setPill('busy', 'lc-live …');
  }

  // ---------------------------------------------------------------- compile
  // "cannot find symbol\n  symbol:   variable foo\n  location: ..." -> "cannot find symbol: variable foo"
  function firstLine(s) {
    const lines = String(s).split('\n').map((l) => l.trim()).filter(Boolean);
    let out = lines[0] || '';
    const sym = lines.find((l) => /^symbol:/.test(l));
    if (sym) out += ': ' + sym.replace(/^symbol:\s*/, '');
    return out;
  }

  function clearDiagnostics(monaco, model) {
    const st = tracked.get(model);
    if (model.isDisposed()) return;
    monaco.editor.setModelMarkers(model, OWNER, []);
    if (st) {
      st.decorations = model.deltaDecorations(st.decorations, []);
      if (st.styleEl) st.styleEl.textContent = '';
    }
  }

  function cssString(s) {
    return '"' + String(s).replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\n/g, '\\a ') + '"';
  }

  function applyDiagnostics(monaco, model, diags) {
    if (model.isDisposed()) return;
    const st = tracked.get(model);
    const lineCount = model.getLineCount();
    const clamp = (ln) => Math.min(Math.max(1, ln), lineCount);

    const markers = diags.map((d) => {
      const sl = clamp(d.line), el = clamp(d.endLine || d.line);
      const maxEnd = model.getLineMaxColumn(el);
      let sc = Math.max(1, d.col), ec = Math.max(sc + 1, d.endCol || sc + 1);
      if (sc > model.getLineMaxColumn(sl)) sc = Math.max(1, model.getLineMaxColumn(sl) - 1);
      if (el === sl && ec > maxEnd) ec = Math.max(sc + 1, maxEnd);
      return {
        severity: d.severity === 'warning' ? monaco.MarkerSeverity.Warning : monaco.MarkerSeverity.Error,
        message: d.message,
        source: 'javac',
        startLineNumber: sl, startColumn: sc, endLineNumber: el, endColumn: ec,
      };
    });
    monaco.editor.setModelMarkers(model, OWNER, markers);

    // One inline message per line (the first diagnostic on that line), error-lens style.
    // The text is drawn by a CSS ::after pseudo-element, so it is not part of the DOM text:
    // it can't be selected, copied, or landed on by the cursor.
    const seen = new Set();
    const decos = [];
    const rules = [];
    for (const m of markers) {
      if (seen.has(m.startLineNumber)) continue;
      seen.add(m.startLineNumber);
      const isErr = m.severity === monaco.MarkerSeverity.Error;
      const idx = rules.length;
      const cls = `lc-live-m${model.id.replace(/[^\w-]/g, '')}-${idx}`;
      rules.push(`.${cls}::after{content:${cssString('  ⟵ ' + firstLine(m.message))}}`);
      const endCol = model.getLineMaxColumn(m.startLineNumber);
      decos.push({
        range: new monaco.Range(m.startLineNumber, 1, m.startLineNumber, endCol),
        options: {
          isWholeLine: true,
          className: isErr ? 'lc-live-line-error' : undefined,
          afterContentClassName: `lc-live-after ${isErr ? 'lc-live-after-error' : 'lc-live-after-warning'} ${cls}`,
        },
      });
    }
    if (st) {
      st.decorations = model.deltaDecorations(st.decorations, decos);
      if (!st.styleEl) { st.styleEl = document.createElement('style'); document.documentElement.appendChild(st.styleEl); }
      st.styleEl.textContent = rules.join('\n');
    }
  }

  async function compileModel(monaco, model) {
    const st = tracked.get(model);
    if (!st || !enabled || model.isDisposed()) return;
    if (st.inflight) { st.dirty = true; return; }
    st.inflight = true;
    st.dirty = false;
    const version = model.getVersionId();
    const code = model.getValue();
    ensurePill(monaco, model);
    setPill('busy', 'javac …');

    const res = await request('compile', code);

    st.inflight = false;
    if (model.isDisposed()) return;

    if (!res || res.offline) {
      offline = true;
      setPill('offline', 'lc-live: server offline');
      clearDiagnostics(monaco, model);
      if (!offlineTimer) {
        offlineTimer = setTimeout(() => { offlineTimer = null; offline = false; recompileAll(); }, OFFLINE_RETRY_MS);
      }
      return;
    }
    offline = false;

    if (model.getVersionId() !== version || st.dirty) {
      // Buffer moved on while we were compiling; results would be misaligned. Recompile.
      schedule(monaco, model, 0);
      return;
    }
    if (res.error && !res.diagnostics) {
      setPill('offline', 'lc-live: ' + res.error);
      return;
    }
    const diags = res.diagnostics || [];
    applyDiagnostics(monaco, model, diags);
    const errs = diags.filter((d) => d.severity === 'error').length;
    const warns = diags.length - errs;
    if (errs === 0 && warns === 0) setPill('ok', `✓ Java OK · ${res.ms}ms`);
    else if (errs === 0) setPill('ok', `✓ Java OK · ${warns} warning${warns > 1 ? 's' : ''}`);
    else setPill('error', `✗ ${errs} error${errs > 1 ? 's' : ''}` + (warns ? `, ${warns} warn` : ''));
  }

  function schedule(monaco, model, delay = DEBOUNCE_MS) {
    const st = tracked.get(model);
    if (!st) return;
    clearTimeout(st.timer);
    st.timer = setTimeout(() => compileModel(monaco, model), delay);
  }

  function recompileAll() {
    for (const [model, st] of tracked) schedule(st.monaco, model, 0);
  }

  // ---------------------------------------------------------------- tracking
  function isJava(model) { return model.getLanguageId && model.getLanguageId() === 'java'; }

  function attach(monaco, model) {
    if (tracked.has(model) || model.isDisposed()) return;
    const st = { monaco, timer: null, inflight: false, dirty: false, decorations: [], subs: [] };
    tracked.set(model, st);
    st.subs.push(model.onDidChangeContent(() => schedule(monaco, model)));
    st.subs.push(model.onWillDispose(() => detach(monaco, model)));
    schedule(monaco, model, 150);
  }

  function detach(monaco, model) {
    const st = tracked.get(model);
    if (!st) return;
    clearTimeout(st.timer);
    st.subs.forEach((s) => { try { s.dispose(); } catch (_) {} });
    if (!model.isDisposed()) clearDiagnostics(monaco, model);
    if (st.styleEl) st.styleEl.remove();
    tracked.delete(model);
  }

  function watchModel(monaco, model) {
    if (isJava(model)) attach(monaco, model);
    model.onDidChangeLanguage(() => {
      if (isJava(model)) attach(monaco, model);
      else {
        detach(monaco, model);
        if (pill) setPill('disabled', 'lc-live: Java only');
      }
    });
  }

  // ---------------------------------------------------------------- completion
  const KIND_MAP = (K) => ({
    class: K.Class, interface: K.Interface, enum: K.Enum, record: K.Struct, method: K.Method, constructor: K.Constructor,
    field: K.Field, constant: K.EnumMember, variable: K.Variable, keyword: K.Keyword, package: K.Module,
  });

  function registerCompletion(monaco) {
    const kinds = KIND_MAP(monaco.languages.CompletionItemKind);
    monaco.languages.registerCompletionItemProvider('java', {
      triggerCharacters: ['.'],
      async provideCompletionItems(model, position, _context, token) {
        if (!enabled || offline || !tracked.has(model)) return { suggestions: [] };
        const offset = model.getOffsetAt(position);
        const res = await request('complete', model.getValue(), { offset }, 3000);
        if (token.isCancellationRequested || !res || !res.ok || !Array.isArray(res.items)) return { suggestions: [] };
        const word = model.getWordUntilPosition(position);
        const range = new monaco.Range(position.lineNumber, word.startColumn, position.lineNumber, word.endColumn);
        const suggestions = res.items.map((it) => ({
          label: it.label,
          kind: kinds[it.kind] ?? monaco.languages.CompletionItemKind.Text,
          detail: it.detail || undefined,
          documentation: it.doc || undefined,
          insertText: it.insert || it.label,
          insertTextRules: it.snippet ? monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet : undefined,
          sortText: it.sort || it.label,
          filterText: it.label,
          range,
        }));
        return { suggestions, incomplete: !!res.incomplete };
      },
    });
  }

  function hook(monaco) {
    registerCompletion(monaco);
    monaco.editor.getModels().forEach((m) => watchModel(monaco, m));
    monaco.editor.onDidCreateModel((m) => watchModel(monaco, m));
    // Editors can be (re)created when LeetCode re-renders the pane; make sure the pill follows.
    if (monaco.editor.onDidCreateEditor) {
      monaco.editor.onDidCreateEditor((ed) => {
        setTimeout(() => { const m = ed.getModel(); if (m && tracked.has(m)) { tuneEditor(monaco, ed); ensurePill(monaco, m); } }, 300);
      });
    }
  }

  // Troubleshooting handle: window.__lcLive.status() in DevTools.
  window.__lcLive = { status: () => ({ enabled, offline, bridgeReady, models: tracked.size, pending: pending.size }) };

  // Monaco is loaded lazily by LeetCode; poll until it shows up.
  const start = Date.now();
  const poll = setInterval(() => {
    const monaco = window.monaco;
    if (monaco && monaco.editor && typeof monaco.editor.getModels === 'function') {
      clearInterval(poll);
      hook(monaco);
    } else if (Date.now() - start > 10 * 60 * 1000) {
      clearInterval(poll);
    }
  }, 500);
})();
