# lc-live

English | [中文](README.zh-CN.md)

**Live javac errors and IDE-style completion inside the LeetCode editor in Chrome**, powered by a compiler running on your own machine. No dependency on LeetCode's flaky built-in "Auto" assistant.

Currently supports **Java**.

```
LeetCode page (Monaco editor)
   │ inject.js reads the buffer, sends it 400 ms after you stop typing
   ▼
Chrome extension background.js  ──POST /compile──▶  local Java compile server (127.0.0.1:47321)
   ▲                                                 in-memory javac, ~15 ms per request
   └── diagnostics → setModelMarkers squiggles + end-of-line hints + status pill
```

## 1. Start the compile server (pick one)

### Option A: Docker (recommended, ships JDK 21 like LeetCode)

```bash
server/docker-up.sh      # = docker compose up -d --build
```

The container uses `restart: unless-stopped`, so it comes back whenever the Docker daemon starts. The port is published to `127.0.0.1:47321` only.
Whether the daemon itself starts at login is up to your Docker backend, e.g. for colima: `brew services start colima`.

Useful commands: `docker logs lc-live`, `docker compose stop`, `docker compose down`.

### Option B: local JDK (11+)

```bash
server/start.sh                  # run in the foreground
server/install-launchd.sh        # macOS LaunchAgent: starts at login, restarts if it dies (undo: server/uninstall-launchd.sh)
```

Point `JAVA_HOME` at a JDK 21 before running the script if you want parity with LeetCode.

Both options listen on 47321, so run only one. Verify with `curl http://127.0.0.1:47321/health`.

## 2. Install the Chrome extension

1. Open `chrome://extensions` and enable **Developer mode** (top right).
2. Click **Load unpacked** and select the `extension/` folder of this repo.
3. Open any problem on leetcode.com or leetcode.cn and pick Java. A small pill appears at the bottom right of the editor:
   - `✓ Java OK · 14ms`: compiles cleanly
   - `✗ 2 errors`: offending lines get a red squiggle plus an end-of-line hint; hover for the full message
   - `lc-live: server offline`: the compile server is unreachable; click to retry
   - Click the pill to toggle the feature on and off

Click the extension icon to see server status or change the port.

## Completion

Once installed, the editor gets IDE-style completion (the extension turns on `quickSuggestions`, which LeetCode ships disabled):

- Type a class-name prefix: `ArrayDe` → `ArrayDeque`; camel-hump abbreviations work too (`ADe`, `HM` → `HashMap`)
- After `.`, members with parameter and return types, generics substituted: `dq.pe` → `peek() : Integer`
- Static members: `Math.`, `Collections.`, `Integer.`
- Locals, parameters, fields and methods of the current class; `this.`, array `length`, results of chained calls
- Members of `ListNode` / `TreeNode` as defined in the problem's comment block
- Picking a method inserts `name(` + caret + `)`; parameterless methods insert `name()`
- Nothing triggers inside comments or strings

Completion and diagnostics share one server; each request takes 10–30 ms. End-of-line error text is drawn with a CSS pseudo-element, so it cannot be selected or copied and the caret never lands on it.

## Behaviour details

- Adds the imports LeetCode adds implicitly (`java.util.*`, `java.util.stream.*`, `java.math.*`, …); line numbers are mapped back correctly
- Extracts `ListNode` / `TreeNode` / `Node` / `NestedInteger` definitions from the problem's comment block and compiles them; falls back to built-in stubs if the comment was deleted
- Provides stubs for interactive-problem parents such as `GuessGame`, `VersionControl`, `MountainArray`, `Relation`
- Design problems (`class LRUCache`), multiple classes and `public class Solution` all compile
- Compile errors only: your code is never executed and never leaves the machine (the server listens on 127.0.0.1 only)

## Design document

Architecture, data flows, the completion algorithm and the key trade-offs are in [docs/DESIGN.md](docs/DESIGN.md) ([中文](docs/DESIGN.zh-CN.md)).

## Layout

```
server/LcLiveServer.java     zero-dependency compile server (single-file java launch)
server/start.sh              run in the foreground
server/install-launchd.sh    install the LaunchAgent
server/docker-up.sh          build the image and start the container
Dockerfile / compose.yml     container definition (JDK 21, restart: unless-stopped)
extension/manifest.json      MV3 extension
extension/inject.js          MAIN world: hooks Monaco, paints markers, completion provider
extension/bridge.js          ISOLATED world: page ↔ extension message relay
extension/background.js      service worker: talks to the local server
extension/popup.*            popup (server status / port)
docs/DESIGN.md               design document (English); docs/DESIGN.zh-CN.md (中文)
```

## Testing the server by hand

```bash
curl -s --data-binary @Solution.java http://127.0.0.1:47321/compile
# {"ok":false,"ms":16,"diagnostics":[{"line":9,"col":20,"endLine":9,"endCol":21,"severity":"error","message":"..."}]}

curl -s -H 'X-Lc-Offset: 103' --data-binary @Solution.java http://127.0.0.1:47321/complete   # caret at character offset 103
# {"ok":true,"ms":12,"incomplete":false,"items":[{"label":"peek","kind":"method","detail":"() : Integer",...}]}

# verbose completion logging
LC_LIVE_DEBUG=1 server/start.sh
```
