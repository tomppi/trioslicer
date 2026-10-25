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
- **solidcraft/SolidCraftBridge.kt** is the agent's door: the same token boundary the Blender
  (9876) and Python CAD (9877) engines use, on 9878, bound to loopback, refusing the engine's
  screenshot tool because this app's viewport is the only renderer. Every tools/call that
  may have changed the design is turned into a DocumentDelta for the live view.
- **solidcraft/CadDocument.kt** holds the document record, the per-body tessellation composer,
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

The agent's MCP client connects to 127.0.0.1:9878 with the token from
**Copy SolidCraft MCP token** (the app's biometric-gated clipboard action). On the dev machine
that is reached through adb forward tcp:9878 tcp:9878; the token is sent as a top-level
"token" field on every JSON-RPC message (or inside params). The app's own status badge shows
whether the engine is up; the agent's edits appear on the plate as they happen.

The engine is the upstream solidcraft-cli; its tool list, parameter docs and command catalog
are the upstream ones (docs/mcp.md in the SolidCraft repository). Two rules are ours:

- **screenshot is refused** by the bridge: the plate is the viewport, and the engine's camera
  is not the user's.
- Files are confined to files/solidcraft/workspace by --allow-path.

## Known limitations

- One live CAD document per plate. Opening another from the app needs the current one closed;
  the agent can open files inside its own session.
- A document change after a split or a snap joint marks the plate stale (with a named reason and a
  Rebuild action) rather than re-slicing the new shape into the old layout.
- Display and print tessellations are the same STL export: SolidCraft 0.22.1's file.export has no
  tessellation-quality parameter. "Display" and "print" name *when* the tessellation is taken, not
  two different qualities.
- The engine is arm64-v8a only; on other ABIs the status says the binary is not packaged.
