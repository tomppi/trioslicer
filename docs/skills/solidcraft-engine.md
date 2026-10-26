# SolidCraft engine (TrioSlicer / enderslicercura, on the plate)

**This is the app's live CAD document.** The main plate can show a SolidCraft 0.22.1 design
rather than a mesh; you are its author, and every geometry change you make appears on the user's
plate as it happens. The user is looking at the same viewport the app uses for imported STLs,
with the same orbit/zoom/pan/double-tap gestures, and he keeps every plate function (move,
rotate, scale, split, snap fit, support paint, Slice, Export) on a mesh derived from your
document.

Rule of thumb:

| task | engine |
|---|---|
| "build a bracket / enclosure / adapter, exact dimensions" | **this engine** (SolidCraft on the plate) |
| "make this scanned model thinner" / "fix this wall" | blender-mcp-engine (mesh editing) |
| "turn this photo into a model" | image-to-3d-model, then CAD to correct dimensions |

The older **cad-engine** skill drives a different engine (build123d on OCP, its own viewport and
its own MCP token on 9877). Do not mix them up: this one holds the document the PLATE draws.

## 1. Connect

The engine is a child process of the app, speaking MCP over its own stdio; the app exposes that
session on **port 9878** on the phone, behind a bearer token. How another device reaches it is
the **Reachable on the network** switch in the CAD agent sheet:

- **Off (the default)** - the bridge listens on 127.0.0.1 only, and nothing off the phone reaches
  it. A development machine can still reach it through `adb forward tcp:9878 tcp:9878`.
- **On** - the app republishes **all three agent engines** on this device's network address at
  their own loopback ports: Blender MCP 9876, the Python CAD engine 9877, and SolidCraft 9878.
  The sheet lists one row per engine with its **connect line** and a Copy button; SolidCraft's
  is `tcp://<this device's address>:9878`. The address is chosen from the phone's own interfaces
  (a tailnet address first, then a private LAN one), so it is not a device name and not a
  setting. **That line is the connection** - no adb, no serve rule, no forward, and no device
  serial anywhere in the loop, which matters because a serial is exactly how an agent ends up
  poking the wrong phone.

Each row also says what is behind the line: **app bridge up** means the listener and the engine
both answer; **engine not running** means the address is published but nothing is listening
behind it - ask the user to open TrioSlicer and press Connect agent. A dead engine must never
be read as a dead network: the line is good, the engine is not started.

If the sheet says there is no network address to show, the phone has neither a tailnet nor a
private LAN IPv4 address: ask the user to join Wi-Fi or the tailnet. Do not ask them to run a
publish rule first - the switch already did the publishing.

A phone on an older build, or a set-up where the user keeps the switch off, can still be
reached by publishing its loopback port from that phone (TailSocks in root mode works):

    tailscale serve --bg --tcp 9878 127.0.0.1:9878
    # -> tcp://<phone>.<tailnet>.ts.net:9878

1. **Probe first.** Connect to the line the user gave you (or 127.0.0.1:9878 through a forward on
   a development phone) and send an initialize frame with no token. Three answers, three meanings:
   - `{"error":{"code":-32001,"message":"unauthorized: send the token ..."}}` - the bridge is up and
     reachable. Good; add the token.
   - connection reset straight after accept - the app's forwarder is answering, but the SolidCraft
     engine is not running: the CAD agent is not connected. Ask the user to open TrioSlicer, CAD
     agent, Connect agent. The engine is a child of the app and dies with it; say that, do not
     retry in a loop.
   - timeout or connection refused - the network reach is not published: the switch is off, or
     the phone is not reachable at that address (Wi-Fi or tailnet down).
2. **Token.** The user taps **Copy MCP token** in the CAD agent sheet (the clipboard gesture is
   biometric-gated on purpose; the app is the only place a token can be handed over on a phone
   without root). It is one tap. Each engine has its own token: Blender's and the Python CAD
   engine's are copied from their own menus, SolidCraft's from the CAD agent sheet. Treat every
   one as a bearer credential: not in logs, not in chat history. The app's forwarder copies
   bytes and logs nothing, so the token does not become an app log line.
3. Speak newline-delimited JSON-RPC 2.0 on that socket. Every message carries the token as a
   top-level "token" field (or inside params):

    {"jsonrpc":"2.0","id":1,"method":"initialize","token":"<token>","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"agent","version":"0"}}}
    {"jsonrpc":"2.0","method":"notifications/initialized","token":"<token>","params":{}}
    {"jsonrpc":"2.0","id":2,"method":"tools/list","token":"<token>","params":{}}
    {"jsonrpc":"2.0","id":3,"method":"tools/call","token":"<token>","params":{"name":"execute","arguments":{"command":"solid.box","params":{"length":40,"width":30,"height":20}}}}

The tool surface is upstream SolidCraft's (list_commands, describe_command, execute, batch,
inspect_design, measure, body_topology, set_parameter, export, undo, redo, new_design, open,
save, ...). The app refuses the engine's screenshot tool: the plate is the viewport, and the
engine's own GPU renderer is never used.

## 2. The live view

Every execute/batch that changes geometry is followed by the app exporting only the bodies the
engine named (created_bodies / changed_bodies / removed_bodies) and patching them into the
plate. There is no debounce and nothing to trigger:

- do not ask the user to refresh, re-import, or export an STL to see the change;
- do not use screenshot or render tools; the plate is the picture;
- undo, redo, a parameter edit (set_parameter / parameters.change), new_design and open have no
  body report, so the app re-reads the whole document body by body - still the same scene;
- if the user has split the derived mesh, or applied a snap joint, a document change marks the
  plate stale and the app says so; the user presses Rebuild plate in the agent sheet. Do not
  loop edits trying to force it; say one sentence about the split being stale.

## 3. Coordinates and placement

The engine's own coordinates are the plate's: where the body sits in the design is where it
prints. The app adds no centring of its own, so:

- build the part on the bed: positive Z, and somewhere inside the machine's X/Y (ask the user's
  printer settings, or keep it near the origin and small);
- if the part must move on the bed, move it in the document (transforms/assemblies), not by
  asking the user to drag it - though the user can drag/rotate/scale the derived mesh if he
  wants to.

## 4. Saving and Slice

- The app saves the design itself into its workspace after edits; do not overwrite the app's
  design path unless you are asked to.
- Slice is the user's button: pressing it exports a fresh print STL from the current document
  (or reuses the last export while the document is unchanged) and hands it to the slicer
  engines.
- Export STEP/STL/3MF is the user's other button; both are in the CAD agent sheet.

## 5. Rules that are not the upstream engine's

- Files are confined to the app's workspace directory passed as --allow-path; an open/export
  path outside it is refused.
- The token is a bearer credential for the engine session: keep it out of logs and chat
  transcripts.
- The app and the engine are both on the phone; there is no desktop SolidCraft involved.
