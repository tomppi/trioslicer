# CAD engine: known low-severity findings

Recorded from the CAD bug hunts (2026-10-09, rounds one and two) so the next hunt does not spend
its budget rediscovering them. Every one below was verified against the code, and most against the
device; none of them misleads the user on its own.

**Give this list to any agent hunting the CAD engine.** If a finding is below, it is known.

## Closed since round one

| # | what happened |
|---|---|
| L1 | **Fixed in round two.** The pick echoed its coordinates in *pixmap* pixels, supersampling factor and all, so a tap at (100, 100) answered x=200. The engine now echoes the point the caller asked about, and the app maps view pixels to frame pixels through the frame's real Fit transform. |
| L4 | **Refuted on the device.** With the grid and the edge overlay both on, a 25-point sweep off the part returned `kind: "none"` everywhere, and six taps placed exactly on drawn edge pixels each named the adjacent face. Neither overlay steals a pick. |

## Still open from round one

| # | where | what | why it stays low |
|---|---|---|---|
| L2 | `CadPreviewClient.readReply` | completeness is "last byte is }" plus "org.json parses", and org.json ignores trailing content | one command at a time under one lock, one reply per request: a robustness gap, not a demonstrated fault |
| L3 | `CadEngine.start`/`stop` | `start()` is guarded only by a volatile flag assigned 60 s later, so two overlapping starts both pass; the loser can destroy the winner's process | the only caller serialises it (`MainViewModel.cadEngineStarting`) |
| L5 | `CadScreen.kt`, `CadViewport.kt` | `CadViewport.onPickUsed` is declared and never invoked, and nothing else calls `clearPick()`, so re-tapping the same face at the same pixel stores an equal value and emits nothing | the user sees no overlay for that one re-pick |
| L6 | `docs/skills/cad-engine.md`, `fetch-cad-payload-android.sh` | the doc says replies are capped at 1 MiB (the CAD client's is 8 MiB), and the fetch script's comment names tag `v1.0.0` while it downloads `v1.1.1` | documentation only |

## Round two (verified, deliberately left alone)

| # | where | what |
|---|---|---|
| R1 | `cad_mcp_slim.py` `_pick_at` | a whole-shape hit answers `kind: "shape"`, which `CadPick.isSomething` does not know, so the strip says "Nothing under that tap"; an `index: null` is also printed with `%d` as `#null` |
| R2 | `CadScreen.kt` tap gestures | nothing is consumed below slop, so a stationary two-finger touch can arm a pick that then rides into the next message (UNCONFIRMED: needs a two-finger tap) |
| R3 | `CadViewerSettings.kt:109-123` | `save()` returns whether the write reached disk and its only caller ignores it, so a failed save applies for the session and is silently gone next launch |
| R4 | `CadPreviewClient.command` | any non-timeout `Throwable` re-sends the identical payload, including a reply lost after execution; for `view` that is a second application of a relative motion |
| R5 | `EnderSlicerApp.kt:1181` vs the CAD dropdown | the top bar is suppressed over the CAD screen, so "Viewer settings" and "CAD files" cannot be reached while it is open and `cadViewport?.applyViewerSettings` is always null: the settings sheet can never live-apply |
| R6 | `EnderSlicerApp.kt:867, 930` | nothing starts the engine for the CAD files screen, so Load there can only retry ECONNREFUSED for 120 s |
| R7 | `EnderSlicerApp.kt:1018-1029` | CAD's BackHandlers are composed before the paint and annotation ones, and the last enabled handler wins: a first Back can be eaten by a paint UI armed from the plate |
| R8 | `CadEngine.stopWatching/watchExports` | a stop/start inside the watcher's 500 ms sleep leaves a second poller for good (`watcher = null` without joining) |
| R9 | `CadEngine.kt:469-481` | Stop CAD engine SIGTERMs the child, so `cad_mcp_status.json` keeps `{"running": true, pid}`; `cad-engine.md` reads that file as liveness (`start()` deletes it before launching, so nothing believes it) |
| R10 | `cad_mcp_slim.py` `analyze` | `layer=0.2` is accepted and never read |
| R11 | `cad_mcp_slim.py` `section` | `out=` is refused unless it ends in `.svg` now, but an x or y section is still degenerate: Export2D projects onto XY, so a YZ section is one vertical line reported as success |
| R12 | `cad_mcp_slim.py` `import_file` | `replace=False` plus a name collision silently overwrites: `Scene.add` replaces by name |
| R13 | `cad_mcp_slim.py` exporters | `export_stl`'s `tolerance=0.1` is relative (build123d meshes `isRelative=True`) while the OBJ path's identical 0.1 is absolute millimetres |
| R14 | `cad_mcp_slim.py` `export_mesh` | an extensionless path with `format=` set is written, and the app's watcher routes by extension, so nothing ever picks it up |
| R15 | dead wiring | `CadEngine.onRender` is declared and never assigned, `EnderSlicerApp.cadRender` is never read, and `CadViewport.loadPart/importPart` are uncalled |
| R16 | `CadEngine.watchExports` | every `exports/*.png` is announced as "render ready", though only `viewport.png` is ever written there |
| R17 | `CadEngine.kt:362-366` | the CAD engine cannot start until the Klipper payload has been extracted once; on a fresh install "Model with CAD" answers "the app's Python is not extracted yet" and only the printer screen or a USB attach fixes it |
| R18 | `docs/skills/cad-engine.md` | calls the engine "inside the app process", lists SVG as importable, and calls the `execute_code` `scene` binding a dict. **Its first claim is resolved**: the CAD menu now has "Copy MCP token" (2026-10-10), so copying the CAD token no longer needs root, and it is the CAD token rather than Blender's |

## Not bugs, but worth knowing when reading the engine

- `view` silently substitutes `iso` for an unknown `orientation` and does not echo what it used,
  where `render` raises for an unknown `view`. A caller cannot tell "iso because I asked" from
  "iso because you asked for nonsense".
- `import_file` returns the shape name under `_summary`'s `"name"`, while
  `CadPreviewClient.importFile` reads `"shapes"`, so that documented return value is always null.
  Nothing acts on it today.
- `execute_code` runs in a **fresh namespace every call** (build123d, OCP and the scene helpers are
  pre-imported; `import build123d as b` does not survive into the next call). This is by design and
  has cost two sessions a false alarm each.
- A client that closes its socket *immediately* after writing can have the request dropped before
  the server ever reads it (traced: `recv` → 0 bytes/EOF). A client that stays connected - even for
  0.3 s - is dispatched normally, and a request already dispatched runs even if the client walks
  away. The app always waits for its reply, so it never sees the first case.
