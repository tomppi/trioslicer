# The embedded ScanRuler engine (1.15.0)

The full ScanRuler workspace from 1.14.0 is untouched: **More -> Experimental -> ScanRuler** still
opens `ScanRulerActivity` over WebViewAssetLoader at
`https://appassets.androidplatform.net/assets/scanruler/index.html`. This document is the *embedded*
mode inside the plate workspace and the engine behind it. There are no new vendored assets: the
page is the bundle committed in 1.14.0 (engine commit `b3aad8a0`, verifier
`scripts/verify-scanruler-assets.py`).

## Serving: a loopback origin, not the AssetLoader origin

The page decides whether it may skip the `http://127.0.0.1:<port>/scanruler-mcp` hello fetch and
pair with a WebSocket by `location.hostname` (`onThisComputer()` in upstream
`src/commands/bridge.ts`). On the AssetLoader origin the hostname is
`appassets.androidplatform.net`, so the page would attempt an http request the WebView's mixed
content policy blocks. The embedded session therefore serves the same verified asset tree from
`http://127.0.0.1:<pagePort>/assets/scanruler/...`:

- `ScanRulerAssetServer` - loopback-only HTTP/1.1 GET server for the `assets/scanruler` tree,
  with `.wasm` as `application/wasm` (required by `WebAssembly.instantiateStreaming`),
  `.js`/`.css`/`.json`/`.woff2`/`.svg` MIME types, no-store caching, and no path outside the
  pinned tree. Preferred port **9881**; an occupied port falls back to an ephemeral one (then
  the page's localStorage origin is new, logged and visible in `state.pagePort`).
- `/__scanruler_bootstrap` - a same-origin bootstrap page. It writes the page's agent prefs
  (`scanruler.agentdataok`, `scanruler.agentlink`, `scanruler.agentport`,
  `scanruler.agenttoken`) so the page's Settings -> Agents link is on and the data warning is
  accepted without UI, then redirects to
  `/assets/scanruler/index.html#agent=<wsPort>:<token>`. The fragment is the page's own
  `takePairingLink`; it sets the prefs itself and strips the fragment.

The WebView's mixed content mode stays `MIXED_CONTENT_NEVER_ALLOW`: everything the page loads is
the same loopback http origin, and the WebSocket is `ws://` from an http page, not mixed content.
The app's network security config already permits cleartext (OctoPrint), so no manifest change was
needed.

## The page link

`ScanRulerPageLink` is a hand-written RFC 6455 WebSocket server on `127.0.0.1:<wsPort>`
(preferred **9880**), so no Node and no third-party WebSocket dependency are added. It accepts
exactly one page, with the per-session pairing token, and implements the page's link protocol:

- page `{hello}` (pageId, since, version, commands, plugins, viewport, token) -> server
  `{welcome: {server: "scanruler-mcp", version: "1.15.0"}}`; a wrong token, a second page, or no
  hello within the timeout is answered with `{refused}` and a named close code
  (4001/4003/4008/4009).
- server `{id, method: "run"|"list", name, input, job}`; page `{id, result}` or
  `{id, error: {code, message}}`.
- page `{event: "progress", id, text}` lines are forwarded to the in-flight command's progress
  callback; `{event: "visibility"}` is accepted and ignored.
- Files travel as JSON plus the next binary frame, exactly like upstream `framing.ts`:
  `"binary": "input.bytes"` announces `{size}` and the next frame is the bytes; the same for
  results. `ScanRulerFrames` mirrors the page's encoder/decoder, including its unsafe-path keys.

Commands run one at a time (`runLock`); a page that closes fails every waiting command by name.

## The session API (UI lane)

`ScanRulerEmbeddedSession` (package `com.tomppi.enderslicer.scanruler.engine`) is the whole
engine. One instance lives for the plate workspace; Plate <-> ScanRuler is `setVisible`, never a
reload.

```kotlin
class ScanRulerEmbeddedSession(context: Context, scope: CoroutineScope, log: (String) -> Unit = {}) : Closeable {
    val state: StateFlow<ScanRulerEmbeddedState>
    val webView: WebView                       // created by start(); read only when phase != IDLE
    suspend fun start()                        // servers + WebView + page load
    suspend fun awaitReady(timeoutMillis: Long = 25_000): Boolean
    fun setVisible(visible: Boolean)           // hide/show only; page stays alive
    suspend fun showEmbeddedWorkspace(): Boolean   // reverse workspace + chrome adapter
    suspend fun setEmbeddedMode(enabled: Boolean)
    suspend fun loadScan(file: File): JSONObject
    suspend fun loadScan(bytes: ByteArray, name: String = "scan.stl"): JSONObject
    suspend fun command(name: String, input: JSONObject = JSONObject()): JSONObject
    fun toolDefinitions(): JSONArray
    val agentHost: AgentToolHost
    val agentInstructions: String
    fun stop(); override fun close()
}
enum class ScanRulerPhase { IDLE, PREPARING, READY, FAILED }
data class ScanRulerEmbeddedState(
    val phase: ScanRulerPhase, val pageUrl: String?, val pagePort: Int, val websocketPort: Int,
    val paired: Boolean, val toolsReady: Boolean, val toolCount: Int,
    val embedded: Boolean, val message: String?,
)
```

`state.phase == READY` means the page said hello and its command catalogue is parsed
(`paired && toolsReady`). `FAILED` carries a user-facing `message`; `awaitReady` returns false on
timeout and leaves the phase alone. `loadScan` sends the bytes through `scan.open` and returns
its result when the page answers; the page's registry reports `busy` while its CAD kernel
(OCCT, loaded when the reverse workspace opens) is still starting, so `loadScan` retries
`busy` with a bounded backoff and shows "Wait for ScanRuler's CAD kernel..." meanwhile. Other
failures are `ScanRulerCommandException(code, message)` or `ScanRulerLinkException`.
`start()` creates the WebView before publishing `PREPARING`, so the UI can host it with
`AndroidView(factory = { session.webView })` when `phase != IDLE`.

The session also binds the MCP facade on `127.0.0.1:9879` (`ScanRulerMcpServer`), joined to
`AgentBridges.TARGETS`; see `docs/MCP_TOKEN_SECURITY.md` section 10 for the token and protocol.

## Embedded chrome adapter

`ScanRulerChromeAdapter` is injected only in embedded mode. It is a style element plus a
`<html>` class, never page edits, and the whole script is wrapped in try/catch: if upstream
renames a selector, one rule stops matching and the page keeps working (the full UI may be
visible), never a broken screen.

Hidden: the top bar, mode/workspace switcher, view bar, status strip (`.strip`),
connector/recovery bars, start pane and file UI, settings/modals, stage hints/warnings/toasts,
split captions, and every side-panel group except the notes group.

Kept: the 3D viewport (`.stage`, `.viewslot`, `.viewport`), the notes group
(`aside.panel > .group.notes`: the Note key, the pin text box, the note rows and their resolve
controls), the viewport note pins and their labels (`.note-label`), and the note card/dialog
that a pin opens. The notes group is moved to a compact fixed overlay at the lower-left of the
viewport so it does not cover the part. `showEmbeddedWorkspace()` first switches the page to
its `reverse` workspace (the only one that renders the notes panel), then applies the adapter.

**The page is loaded by an unattached WebView.** Until the plate composes its AndroidView, this
Chromium resolves the page's own `100dvh` / percentage height chain to 0 (`innerHeight` is
already correct at 658x824, the units are not), which collapses `.app` to height 0 and leaves
the stage's canvas 1 px tall. The adapter therefore does not rely on viewport units for height:
`#root` is `position: fixed; inset: 0` (its size comes from the viewport), `.app` takes 100% of
it, and the notes overlay's `max-height` is fixed px, not `vh`. Width is forced to 100% because
the desktop page has a 640 px floor. After this the canvas measures the full WebView size.

**Commands can change the workspace.** `element.fit` shows the measure workspace, which does
not render the notes panel. While embedded, the session re-asserts the reverse workspace after
every command it runs and after every MCP tool call (asynchronously, as a follow-up), so the
Note key/pin box/note rows return while the element or measurement the command made stays in the
viewport and its labels.

## Files

- **Into the page.** Plate mesh bytes via `loadScan` (the app writes a binary STL to its cache and
  calls `scan.open`); the user's own file picker in the standalone screen; an external agent's
  synthetic bytes only as inline base64. No phone path is read by the facade.
- **Out of the page.** Agent-facing exports from MCP tool calls are written by the app as its own
  MediaStore entries under `Download/ScanRuler/` (no storage permission) and returned as a
  content URI (`ScanRulerFileExports`). The in-app agent can ask the page to export; the MCP
  result names the content URI.
- The 9879 token lives in `files/scanruler/`, excluded from both backup lists like the other
  engines' tokens.

## In-app agent seam

When the ScanRuler view is active, the app's in-app agent must use
`session.toolDefinitions()` for its catalogue, `session.agentHost` as its `AgentToolHost`, and
append `session.agentInstructions` to the runbook. The host maps the underscore tool names back
to the page's dotted commands and returns one-way `AgentToolHost.Result` values. The catalogue is
the page's own: the 72 core commands plus the reverse plugin's `reverse.*` commands when the page
registers them. Notes are read with `reverse_note_list`, answered/closed with
`reverse_note_resolve`, and the agent's own questions pinned with `reverse_note_add`.