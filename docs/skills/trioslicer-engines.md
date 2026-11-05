---
name: trioslicer-engines
description: Drive the three modelling engines embedded in the TrioSlicer app (SolidCraft live CAD document, build123d/OCP CAD, Blender 3.6) through the one unified MCP server on 127.0.0.1:9878 - the connection line, the engine selector, and the rules each engine works under.
whenToUse: When asked to model, edit or inspect something through the phone's engines and no deeper engine-specific skill is needed yet, or when deciding WHICH engine the task belongs to. The selector picks the engine; solidcraft-engine, cad-engine and blender-mcp-engine carry the deep per-engine knowledge and link back here for the connection.
---

# TrioSlicer modelling engines - one MCP server

The app drives three modelling engines and hands all of them to an agent through **one MCP
server**. One line connects you; the **selector** decides what you are talking to.

## 1. Connect

    tcp://<phone-tailscale-ip>:9878 token=<the token the app printed>

Newline-delimited JSON-RPC 2.0 (MCP), every message carrying the token at the top level or
inside params. Reach the port one of these ways:

- **Tailnet, no adb, no cable (the current daily driver).** The phone runs TailSocks root mode
  (node phone-host, <phone-tailscale-ip>); the daemon owns a real kernel tailscale0, so the app's
  **Reachable on the network** switch publishes the loopback listener directly. The CAD agent
  sheet prints the whole line - one tap, one paste. Verified against the app's bridges
  (2026-10-26).
- **adb forward** (any phone): adb -s <phone-tailscale-ip>:5555 forward tcp:9878 tcp:9878, then
  tcp://127.0.0.1:9878 token=... . Relaunch the app if the process died:
  adb shell am start -n com.tomppi.enderslicercura/com.tomppi.enderslicer.MainActivity.

**The token without root.** It lives in app-private storage (files/agent/engines_mcp_token.txt)
and no shell reads that without root. The app has it: the CAD agent sheet's **Copy** row for
Modelling engines puts the whole tcp:// line on the clipboard in one paste; the SolidCraft and
CAD menus' **Copy MCP token** copy just the token. Ask the user for one of those rather than
reaching for su.

**Exposure.** Reachable on the network is a promise to the user's tailnet - turn it off when
you are done (the sheet's switch stops every published listener; the engines keep running).
An adb-based session needs adbd on TCP 5555, which binds every interface: close it with
adb usb when the session ends.

## 2. The selector

- **engine_select** - arguments {"engine": "solidcraft" | "cad" | "blender"}. The answer names
  the engine, how to reach its tools, the rules that govern it, and whether it is running.
  tools/list then shows only that engine's tools, and tools/list_changed tells you it moved.
- **engine_status** - the selected engine and each engine's readiness.

**SolidCraft is the default**: connect and the live CAD document is already yours. The tools
keep the engine's own names whatever is selected - switching does not rename anything.

## 3. Which engine

| task | engine |
|---|---|
| "build a bracket / enclosure / adapter, exact dimensions", edit a design on the plate | **solidcraft** (the live CAD document; deep knowledge in [solidcraft-engine](solidcraft-engine.md)) |
| "make a 40 mm bracket with a 6 mm boss", exact geometry off the plate, STEP/sections/analysis | **cad** (build123d on OCCT; [cad-engine](cad-engine.md)) |
| "make this scanned model thinner", mesh surgery, reshaping a model | **blender** (bpy; [blender-mcp-engine](blender-mcp-engine.md)) |
| "turn this photo into a model" | image-to-3d-model, then CAD to correct dimensions |

## 4. The engines

**solidcraft** - the live CAD document the user's plate draws. The engine owns its tool list
(sketch, extrude, fillet, boolean, patterns, measure, section, curve tools, parameters) and a
tool it adds appears without an app change. Rules: the plate repaints as your edits land - what
you build is what the user sees, immediately. One author at a time: while the in-app agent
works, calls are refused with -32010 and the message names who is driving - wait and retry.
There is no screenshot tool: the plate is the viewport and the user's camera is theirs - ask
them to look. Exports are the document's own files (STEP/STL/3MF); converting the document to
plate models is the user's press, never yours.

**cad** - exact geometry: build123d on OpenCASCADE. execute_code runs Python with build123d,
OCP and the scene helpers pre-imported (each call is a fresh namespace). Named tools beside it:
get_scene_info, get_object_info, get_metrics, export_step, export_stl, export_mesh, describe_at,
view, analyze, section, import_file, clearance, viewer_settings, render, get_addon_info.
Exported meshes appear on the plate as their own objects. render draws what the engine sees for
reports; the plate is the live view. Import units matter - say them. Before measuring, verifying
or looping over geometry, read [occt-engine-rules](occt-engine-rules.md) - it is
mandatory for anything longer than a few seconds and carries the measured costs, the
cheap-first recipes and the boolean rules.

**blender** - the embedded Blender 3.6 scene. execute_code runs bpy; the scene stays alive
between calls - edit it, do not rebuild from scratch. Beside it: get_scene_info,
get_object_info, get_world_state_snapshot, export_stl, get_addon_info. export_stl writes one
binary STL of the whole scene and it hot-loads onto the plate as its own object; give each
export a new file name. Do not slice here - the app's own slicers do that.

## 5. Rules that hold for all three

- The phone's plate is the user's viewport. Renders exist for reports; the user's camera is
  theirs. Ask them to look rather than reaching for a picture you cannot have.
- Files you export land in the app's own folders and appear on the plate; the app tells the
  user what arrived. Never name an arbitrary phone path.
- The engines answer slowly when the scene is heavy (a tools/call may run for minutes). After
  a timeout the call may still have run: inspect the result before sending it again.
- The CAD (build123d/OCP) engine runs one command at a time on one thread, and a long command
  blocks every caller. Before measuring or verifying geometry, read occt-engine-rules: never
  loop point-in-solid, estimate every loop, verify every boolean changed the volume, and never
  resend a timed-out CAD command.
- An engine that is "not started" must be started in the app first - the selector's answer
  says so, and the app's menus own engine lifecycle.

## 6. Example turn

    -> {"jsonrpc":"2.0","id":1,"method":"tools/call","token":"<token>",
        "params":{"name":"engine_select","arguments":{"engine":"cad"}}}
    <- {"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"Selected engine: CAD engine. ..."}]}}
    -> {"jsonrpc":"2.0","id":2,"method":"tools/call","token":"<token>",
        "params":{"name":"execute_code","arguments":{"code":"box = Box(20,20,20)"}}}
    <- {"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"..."}]}}
