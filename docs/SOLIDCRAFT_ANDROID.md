# SolidCraft: the live CAD engine

TrioSlicer's main plate can show a **CAD document** instead of a mesh. The document is a
SolidCraft 0.22.1 design held by a SolidCraft engine process; the agent editing it is the author,
and the plate is a live view of the engine's document. The human keeps every plate function he
had - move, rotate, scale, lay flat, split, snap fit, paint, Slice, Export, the layer and path
views - because those act on a **derived mesh**, not on the document.

This file records what is packaged, how the pieces talk, why the view is incremental, and what
is deliberately not in it.

## What is packaged

| part | what it is |
|---|---|
| app/src/main/jniLibs/arm64-v8a/libsolidcraft_exec.so | SolidCraft 0.22.1's solidcraft-cli, cross-compiled for arm64-v8a with --no-default-features, stripped. It is a PIE executable staged under a .so name so Android unpacks it into the app's native library directory; libocct_exec.so is the same pattern. 63 MB. |
| scripts/build-solidcraft-engine-android.sh | the cargo cross-build (NDK r28c, linker aarch64-linux-android24-clang), the strip, the staging, and --package for the release tarball. |
| scripts/fetch-solidcraft-engine-android.sh | the SHA-256-pinned download of that tarball as a release asset (tag solidcraft-engine-v0.22.1), or a local tree through SOLIDCRAFT_ENGINE_DIR. |

Rebuilding from source:

    scripts/build-solidcraft-engine-android.sh --package
    # prints the tarball's sha256 and the release command
    gh release create solidcraft-engine-v0.22.1 .build/solidcraft-engine/solidcraft-engine-arm64-v0.22.1.tar.gz

The digest is pinned in the fetch script; **re-pin it deliberately**, the way the OCCT and CAD
payload digests are pinned. "gradlew :app:verifySolidCraftEngineExecutable" checks that a staged
binary is an ELF engine of a plausible size, and is not wired into assemble because the release
asset is published separately from the source commit (CI has no Rust cross toolchain).

## The pieces

    agent (MCP client)                    app process                       child process
    -----------------                     -----------                       -------------
    MCP over TCP 127.0.0.1:9878  --->  SolidCraftBridge  --->  SolidCraftSession  --->  solidcraft-cli mcp
        (token)                          |  (forwards, reports changes)         |  (stdio MCP)
                                         v                                      |
                                  MainViewModel  <---- DocumentDelta -----------+
                                         |
                                         v
                                  derived mesh ---> ModelSurfaceView (the existing renderer)

- **solidcraft/SolidCraftEngine.kt** owns the child process. It starts it as
  libsolidcraft_exec.so mcp --headless --allow-path <files/solidcraft/workspace>, points
  TMPDIR, HOME and SOLIDCRAFT_RECOVERY_DIR into the app's private directories, drains its
  stderr into logcat, and stops it when the app finishes. The engine's own file sandbox is the
  one allowed directory: designs, opened files, body tessellations and exports are all inside it.
- **solidcraft/McpClient.kt** is the stdio MCP client: one reader thread, a pending map keyed
  by JSON-RPC id, per-call timeouts, and named failures (McpTimeoutException,
  McpRemoteException, McpTransportException). A timed-out call reports that the design may
  still have changed, the same honesty the engine's own protocol asks for.
- **solidcraft/SolidCraftSession.kt** is the typed tool surface the app uses itself (export a
  body, save the design, open a file, inspect bodies) - not the agent's path.
- **solidcraft/SolidCraftBridge.kt** is SolidCraft's agent door: the same token boundary the
  Blender (9876) and Python CAD (9877) engines use, on 9878, refusing the engine's screenshot
  tool because this app's viewport is the only renderer. It binds 127.0.0.1 like the other two
  engines; the network side is the app's forwarder (below), not this socket. Every tools/call
  that may have changed the design is turned into a DocumentDelta for the live view.
- **agent/AgentBridgeProxy.kt** is the network side of all three agent engines. It listens on
  the device's own network address at 9876, 9877 and 9878 - the engines' loopback ports, kept
  identical - and forwards each connection's bytes both ways to 127.0.0.1 at that port. A
  specific network address and 127.0.0.1 can both hold the same port, which is why the numbers
  do not change. It is transport only: each engine still checks its token on every message, the
  forwarder checks nothing, and it never logs message content.
- **agent/AgentBridges.kt** owns the CAD agent sheet's **Reachable on the network** switch: the
  stored choice, the three targets, the watcher that follows the device's address (so a Wi-Fi
  change rebinds instead of leaving a stale line), and the per-engine status rows
  ("app bridge up" / "engine not running"). Turning the switch off closes every listener
  before it returns.
- **agent/BridgeNetwork.kt** holds the stored preference and the IPv4 address rule behind the
  connect lines, and it is the one place the app enumerates the device's interfaces
  (java.net.NetworkInterface only - no WifiManager, no connectivity service, no interface names).
- **solidcraft/CadDocument.kt** holds the document record, the per-body tessellation composer,
  the workspace path rules and the Slice-export handoff.
  the workspace path rules and the Slice-export handoff.

## The live view

The document's geometry is the engine's. The plate draws a **derived mesh**, one tessellated mesh
per body (CadBodyMeshes), and the picture is kept live by patching only what an edit named:

1. the agent calls execute/batch over the bridge; the engine answers with
   created_bodies / changed_bodies / removed_bodies (verified shapes: a box reports
   created_bodies: ["Body1"], a fillet changed_bodies: ["Body1"]);
2. the bridge turns that into a DocumentDelta (undo, redo, a parameter rebuild, an opened file
   say no bodies and become a full re-read - still of the same scene, body by body);
3. the app exports **only the named bodies** with file.export {bodies: [name]} - one batch call,
   one STL file per body - parses them and patches the composer;
4. the composed mesh replaces the plate object's sourceMesh in place: same object id, same name,
   same camera. The plate object is updated, never reloaded;
5. the human's plate edits are re-applied: the placement transform is carried over relative to
   the old mesh (a part that grows keeps its turn and its place; it never recentres), painted
   triangles are kept while the triangle count matches and are otherwise **named as cleared** in
   the status;
6. a plate that has been **split** (or a snap pair applied) is no longer the document's object:
   the app marks the document stale, says which shape the plate is showing, refuses to Slice it,
   and offers **Rebuild plate from the document** in the agent sheet.

No debounce: each edit repaints as soon as it is reported. A second edit that arrives while one
is being applied is merged into the next repaint rather than dropped. Timings are measured in
MainViewModel ("cad live: call->export ... export->mesh ... mesh->state ... total ...") and the
first frame after the state change is logged as "cad frame". The walk in .build/walk-114/
carries the measured numbers.

The camera and gestures are the existing ModelSurfaceView's: orbit, pinch, two-finger pan and
double-tap reset are untouched, and a repaint never calls resetCamera because the object keeps
its identity and name. The engine never touches the camera and its screenshot/render tools are
never used.

## The plate functions on a CAD document

| plate function | on a live CAD document | how |
|---|---|---|
| orbit / zoom / pan / double-tap reset | yes | the same ModelSurfaceView gestures |
| move by finger, position, rotate, scale, gizmo | yes | stored on the derived mesh's PlateObject.placement, re-applied on every document change |
| lay flat, drop to plate, reset/centre | yes | same placement path |
| apply imported Cura transform | yes | same placement path |
| split at a plane | yes | split the derived mesh; the halves are marked as derived objects, and a later document change marks the plate stale instead of pasting over them |
| snap fit (all styles, cuts, gizmo) | yes | the existing snap pipeline on the derived halves; same stale rule on a document change |
| paint supports / clear | yes | triangle paint on the derived mesh; kept while the triangle count matches |
| multi-object: remove, arrange, settings | yes | the document object is one plate object among the plate's objects |
| Slice | yes | a print-quality STL is exported from the current document only when it changed since the last slice; an unchanged document reuses the recorded export, and the status says which |
| Export G-code | yes | the slice of the derived mesh, as today |
| Export STEP / STL / 3MF | yes | the agent sheet writes the document, unchanged |
| Model / Layers / Path view switch | yes | the slice of the derived mesh, as today |
| sketch / feature / parameter editing | no, by design | the agent is the only author; the app has no UI that edits the document |

## The agent

All three of the app's agent engines speak newline-delimited JSON-RPC 2.0 on loopback, each
with its own bearer token: Blender MCP on 9876, the Python CAD engine on 9877, and SolidCraft
on 9878. The token is sent as a top-level "token" field on every message (or inside params).
The CAD agent sheet's row **Copy** is the hand-over: it puts one ready-to-paste line on the
clipboard - `tcp://<this device's address>:<port> token=<that engine's token>` - after the
app's biometric prompt, so connecting an agent is one copy and one paste. The line and the
clipboard handling are in `agent/AgentBridgeHandoff.kt` (AgentBridgeCopy, AgentBridgeTokens).

The CAD agent sheet owns how those ports are reached from outside the phone:

| switch | listeners | how another device connects |
|---|---|---|
| **Reachable on the network** off (default) | 127.0.0.1 only, in each engine | nothing off the phone reaches them; an adb forward or a tailnet serve rule publishes a port by hand |
| **Reachable on the network** on | the app's forwarder listens on this device's network address at 9876, 9877 and 9878, forwarding to 127.0.0.1 at the same ports | the sheet shows one row per engine; its **Copy** puts `tcp://<this device's address>:<port> token=<token>` on the clipboard in one line, and any device that can route to that address connects with that line |

Each line has its own state under it. **app bridge up** means the listener answers and the
engine's loopback port does too; **engine not running** means the listener is up but nothing
answers behind it (start the engine - that is a dead target, not a dead network); **off - this
device only** is the switch-off state. While the listeners are being moved to a new address
the sheet says **address changed - rebinding**. A row whose engine has never started has no
token file yet, so it says **start the engine first - its token is created when it starts** and
its Copy is disabled: nothing puts a line without a token on the clipboard.

The row Copy is gated like the token-only copies always were: the token is read after the
fingerprint (or device-credential) prompt, the clip is marked sensitive so Android does not
preview it, and the toast names the engine, never the token. The token is never rendered in
the sheet - `AgentBridgeLine` carries only the name, URL, state and sentence - and the
forwarder logs no content. The sheet's standalone **Copy MCP token** is kept only as the
fallback for when no published row can offer the one-line form (the switch is off, or there is
no network address to publish on); while a row is copying address + token together it is hidden
rather than being a second, weaker path to the same credential.

The address is not configured anywhere and is not a device name: the app enumerates its own
IPv4 interfaces and picks, in order, an address inside the tailnet range 100.64.0.0/10, else a
private LAN address (10/8, 172.16/12, 192.168/16) on a real interface (the mobile data
interface is only used when no Wi-Fi or Ethernet address exists), else nothing - and then the
sheet shows a named sentence saying which ranges it looked for instead of a dead line. The
rule is in `BridgeAccess.select`, and `BridgeNetworkTest` pins it without a network.

The switch is stored in the app's own preferences file; [AgentBridges.ensureStarted] puts the
listeners back at app start, a watcher re-reads the device address every few seconds, and a
flip of the switch binds or closes them immediately. No listener survives the switch being
turned off, and no engine process restarts for either direction.

**The forwarder is not a gate.** Each engine's token file and per-message check are exactly
what they were on loopback; the forwarder adds no authentication of its own, keeps no state
between connections and never logs the bytes it copies. What else gates the network reach is
what the network itself provides - a tailnet's ACL, or the LAN's own boundary - and the sheet
says so next to the lines.

The engine is the upstream solidcraft-cli; its tool list, parameter docs and command catalog
are the upstream ones (docs/mcp.md in the SolidCraft repository). Two rules are ours:

- **screenshot is refused** by the bridge: the plate is the viewport, and the engine's camera
  is not the user's.
- Files are confined to files/solidcraft/workspace by --allow-path.

## Known limitations

- **The published listeners are plain TCP.** Each engine's token authorizes every message;
  nothing encrypts the stream and nothing is signed. On loopback that is the same boundary the
  other engines have always had; with the network switch on, anyone who can reach an address
  and has that engine's token can drive that engine, which is exactly what the sheet says. The
  forwarder logs no message content, so a token does not become a log line. Use a tailnet or a
  LAN you trust.
- One live CAD document per plate. Opening another from the app needs the current one closed;
  the agent can open files inside its own session.
- A document change after a split or a snap joint marks the plate stale (with a named reason and a
  Rebuild action) rather than re-slicing the new shape into the old layout.
- Display and print tessellations are the same STL export: SolidCraft 0.22.1's file.export has no
  tessellation-quality parameter. "Display" and "print" name *when* the tessellation is taken, not
  two different qualities.
- The engine is arm64-v8a only; on other ABIs the status says the binary is not packaged.
