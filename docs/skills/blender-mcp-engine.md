---
name: blender-mcp-engine
description: Drive the Blender MCP socket engine embedded in the enderslicercura Android app (JSON protocol on 127.0.0.1:9876 via adb forward), including the STL export + hot-load handoff into the slicer UI.
whenToUse: When asked to modify, reshape, deepen or otherwise edit an existing model on the device - this is the only modelling environment there is, and the GPU box is not a substitute. Also when generating 3D models through the phone's embedded Blender engine, writing STL handoff files, debugging MCP command responses, or checking that an exported model reached the app.
---

# Blender MCP Engine (enderslicercura embedded)

**This is the device's modelling environment, and the only one.** Anything that *changes* an existing model - reshaping a profile, deepening a dish, adding or subtracting material, fixing a wall - belongs here, in bpy, over the MCP socket.

The pipeline in [image-to-3d-model](../image-to-3d-model/SKILL.md) does exactly one thing: turn a photograph into a mesh on the GPU box. It is not a modelling tool. Reaching for it to edit a model means reimplementing, badly and with whatever Python happens to be installed there, mesh surgery the embedded Blender already does properly - and the box's `/usr/bin/blender` is broken besides.

The app bundle (package `com.tomppi.enderslicercura`) runs Blender 3.6 **inside the app process** via `libblender_exec.so` (in-process wrapper, `mainBlenderInitial` on a detached thread), with a slim MCP addon serving on **localhost:9876**. Blender stdout/stderr appear in logcat as `I app_process64` when the `wrap.com.tomppi.enderslicercura` property is set to `logwrapper` (capture with `adb logcat -d -s app_process64`).

## 1. Connect

- **Device: `<phone-tailscale-ip>:5555` - the tailnet address, and the one to use.** It works from
  anywhere the tailnet is up, which the LAN address does not: that one needs the phone and the
  host on the same WiFi, so it disappears the moment either moves. Connect once with
  `adb connect <phone-tailscale-ip>:5555`, then address it as `adb -s <phone-tailscale-ip>:5555 ...`.
  (`<phone-lan-ip>:5555` still works on the LAN and is a fine fallback when the tailnet is down.)
  Root shell via `su -c`. Verified: phone-host, shell context `u:r:shell:s0`.
- **Never drive the device's UI.** No `input swipe`, `input tap`, `input keyevent`, no `screencap`. The phone is the user's; it is not an observation port. Looking at your model is section 4, and it happens inside Blender.
- **Do not invent directories inside the app's private storage.** Two directories exist for handoff - `files/blender/imports/` (model into the engine) and `files/blender/exports/` (engine out to the app) - and nothing else belongs there. An agent once made up `files/blender/incoming/` with `su`, copied a 67 MB mesh into it and left it behind: a root-owned directory, in a place the app cannot write, that no code had ever heard of. If you need somewhere to put a file first, that is what the drop box is for.
- **Drop box: anything you push to the device goes in `/sdcard/Download/dsh-agent/`, never the Download root.** Models stay there - it is the copy the user can find; probe scripts and screenshots are scaffolding and come back out.
- Forward (required): `adb -s <phone-tailscale-ip>:5555 forward tcp:9876 tcp:9876`. The MCP socket MUST bind `localhost` (the addon's default; app passes `BLENDER_MCP_HOST` via the C++ wrapper). **Do NOT bind the Tailscale/CGNAT IP** (100.64.0.0/10): Tailscale Android does not deliver inbound TCP to app sockets (SYN times out / ports RST from tailscaled's userspace stack; verified 2026-09-09), so a tailnet-bound socket breaks the adb-forward loopback path and is unreachable anyway.
- The engine lives only while the app process runs. Relaunch: `adb shell am start -n com.tomppi.enderslicercura/com.tomppi.enderslicer.MainActivity`. First launch after an install can hit a transient wrapper/zygote race ("start timeout", signal 9) — just launch again.
- Quick liveness: `adb shell su -c 'ss -tlnp | grep 9876'` (owner pid should be the app).

**Exposure - turn the listener off when you are done.** This section needs adbd on TCP 5555, and
the phone binds that port on **every** interface (`*:5555`, verified on the device), so it is
reachable from the LAN and the tailnet, not only from the host you connect from. adb authorizes a
host by its adb key, not by a password the phone asks for per connection: any host the phone has
trusted - or that gets trusted while 5555 is open - has a shell as `u:r:shell:s0`, and every
`su -c` command below then reads the app's private storage, the MCP token included. The MCP
socket is no more private than the adb forward that publishes it. Close it after a session with
`adb usb`, or turn **Settings ▸ Developer options ▸ Wireless debugging** off.

## 2. Protocol

Plain TCP, one JSON object per request. Either a single write with no terminator (what the app does) or newline-delimited requests are accepted; two requests in one write no longer hang the connection.

**Every request needs the engine's token**, which the app generates on first run and the engine reads at startup:

```bash
adb -s <phone-tailscale-ip>:5555 shell cat /data/user/0/com.tomppi.enderslicercura/files/blender/scripts/startup/blender_mcp_token.txt
```

This reads the app's private file over the exposed 5555 shell, so anyone who reaches that port
(see the exposure note in section 1) can read the token the same way. There is no `su` binary to
call on this build: with rooted debugging enabled adbd already runs as root (`adb shell id` reports
`uid=0(root)`, and `adb root` answers "adbd is already running as root"), so a plain `cat` reads
it. A non-root shell fails with permission denied, which then looks exactly like a wrong token —
every request answers `unauthorized` — so check what you read, not just that the command exited.

An engine started by hand (`blender -b --python start_blender_mcp.py`) has no token file and **refuses to serve**, and the app likewise refuses to start the addon when it could not write one: without a token there is nothing to authorize a request against, so a tokenless server would let any co-installed app run Python as this app's uid. To use a hand-run engine, write `blender_mcp_token.txt` beside the script and send that token with every request.

```text
→ {"type": "ping", "params": {}, "token": "<token>"}
← {"status": "success", "result": {"pong": true}}

→ {"type": "execute_code", "params": {"code": "..."}, "token": "<token>"}
← {"status": "success", "result": {"executed": true, "result": "<captured stdout>"}}

→ {"type": "ping", "params": {}}
← {"status": "error", "message": "unauthorized: send the token from blender_mcp_token.txt"}
```

There is no token-free command, `ping` included: the server checks `_authorized()` before it
dispatches, and answers `unauthorized` when the field is missing. A tokenless ping is a failed
ping, not a liveness check.

While one command is running nothing else can: a request that arrives while another has been running for more than ten seconds is answered `{"status": "error", "message": "engine busy in another command for Ns"}` instead of waiting out the client's own timeout.

**A reply is capped at 1 MiB.** A reply is a JSON control message, not a payload - the pixels and the STL bytes come back through files - so the app's engine client refuses a reply that grows past a megabyte instead of buffering whatever the peer sends. The command then fails outright, with:

```text
Engine reply exceeded the 1048576 byte limit; the engine is not answering with a control message
```

That is the app's client, not the socket, but the practical rule is the same either way: printing a whole mesh dump is a **failed command, not a slow one**. Keep `print()` to a summary and write anything large to a file in `exports/`.

Commands (`params` are keyword args, so `{"type":"execute_code","code":...}` without `params` FAILS with "missing required positional argument"):

| type | params | notes |
|---|---|---|
| `ping` | – | liveness; touches no bpy data. **Needs the token too** — without it the answer is `unauthorized`, not `pong` |
| `execute_code` | `code` | namespace: `bpy`, `mathutils`, `json`, `os`; stdout captured via `redirect_stdout` |
| `export_stl` | `filepath` | writes ALL scene meshes as binary STL via native writer (no `bpy.ops`); returns `{filepath, bytes, triangles}` |
| `get_scene_info` | – | name, object_count, objects[{name,type,location,dimensions}] |
| `get_object_info` | `name` | + vertices/polygons for MESH |
| `get_world_state_snapshot` | – | object names |
| `get_addon_info` | – | name, version, headless_ready |
| `shutdown` | – | drains the queue and closes its listening socket, so nothing answers on 9876; the engine then **parks** inside the app process, thread and loaded scene intact (the app survives - it used to exit and take the whole process with it). The next start request, or `touch blender_mcp_restart.txt` next to the addon, serves again. Stopping frees the socket but not the memory: **only ending the app process releases the engine** |

Errors: `{"status": "error", "message": "<exc>"}`. A command run in `blender -b` (headless) is executed on the MCP addon's **main-thread driver loop** (`start_blender_mcp.py` calls `_server.run_headless()`); `bpy.data` access is safe there. New objects persist in the scene between commands; use `bpy.data.objects` to find them within `execute_code`.

## 3. STL handoff (hot-load into the slicer UI)

1. Export to the handoff dir: `/data/user/0/com.tomppi.enderslicercura/files/blender/exports/<name>.stl` — either via `export_stl` or inside `execute_code`:
   ```python
   import os, blender_mcp_slim as bm
   base  = "/data/user/0/com.tomppi.enderslicercura/files/blender/exports"
   final = base + "/model.stl"
   n = bm._mesh_to_binary_stl(bpy.context.active_object.data, final + ".part")
   os.replace(final + ".part", final)   # the rename is what publishes it
   print(n, "tris")
   ```

   **Export through a temporary name and rename into place.** A `.part` suffix is invisible to the poller, so nothing is published until the rename - which is atomic. Writing straight to `.stl` instead exposes the file while it is still being written, and a 67 MB mesh took seconds to write.
   (helper `_export_scene_stl(path)` exports every scene mesh; header is `enderslicercura MCP STL`, little-endian binary STL.)
2. The app **polls** the exports dir every 500 ms (authoritative; FileObserver kept as accelerator) and imports any new revision, deduped by `path|size|mtime` signature.

   It refuses anything that is not a **whole** STL, because a binary STL declares its own length: it reads the triangle count from the header and requires `84 + 50 x triangles == filesize`, with an ASCII export required to end in `endsolid`. So a half-written file can no longer be imported.

   That guard is a safety net, not a licence to write carelessly. Growing a file in place still costs a probe on every poll and, before the guard existed, dispatched **eleven revisions of one export - seven of them truncated mid-triangle - and left 486 MB of staged copies** in `files/models/`. Write atomically (step 1).
3. On dispatch the app stages a private copy `files/models/blender-<nanoTime>.stl` and swaps it into the UI ("Imported … from the Blender engine"). **Always export a fresh unique/canonical filename per generation** — size+mtime signature means a rewrite of the same path only re-fires if mtime changes.
4. Verify: `adb shell su -c 'ls -la /data/user/0/com.tomppi.enderslicercura/files/models/'` — a new `blender-*.stl` proves the full chain.

## 4. Look at your model - required, and do it freely

**Looking at your model is a required part of this job, not a nicety and not something to ration.** This works in the engine - see the Cycles recipe below - and a view costs well under a second. Modification is iterative: change the mesh, look at it, decide, change it again. An agent that edits blind produces confident, plausible, wrong geometry - the sawtooth, the wall with no vertices in it. Render as often as you need, from as many angles as you need, and do not hesitate over the cost.

### Looking at the model: WORKBENCH first, CYCLES when you need light

**Both engines now render in the engine, on the device.** The GPU path was
repaired in this engine build (see `native/blender/patches/README.md`), so the
old "never set a GPU engine" rule is gone.

| engine | resolution | time |
|---|---|---|
| **WORKBENCH** | 128x128 | **~10 ms** (34 ms on the very first render, compiling shaders) |
| **WORKBENCH** | 256x256 | **~22 ms** |
| CYCLES CPU | 128x128 @ 8 | ~50 ms |
| CYCLES CPU | 512x512 @ 32 | ~2.5 s |
| EEVEE | 128x128 @ 64 | ~2.3 s (mostly first-run shader compilation) |

All three engines render now. EEVEE works but is not a preview engine: it
accumulates TAA samples on the GPU and its first render pays a ~2 s shader
compile. Use it only when you specifically want EEVEE's look.

**Use WORKBENCH for the routine look-at-it loop.** It is a solid-shading
rasteriser: no lights needed, no noise, no sampling, and at ~10 ms a view you can
afford to render from ten angles to check a shape. It shows geometry and nothing
else, which is usually exactly what you want to judge.

```python
scene.render.engine = 'BLENDER_WORKBENCH'
# Optional, and worth it: cavity + outline make edges and shallow steps read.
scene.display.shading.light = 'STUDIO'
scene.display.shading.show_cavity = True
scene.display.shading.cavity_type = 'BOTH'
scene.display.shading.show_object_outline = True
scene.render.resolution_x = scene.render.resolution_y = 256
bpy.ops.render.render(write_still=True)
```

**Use CYCLES when materials, lighting or shadow shape matter** - a lit render
shows form that flat shading hides, and it is the second opinion on whether a
surface really is smooth. It costs ~5x more per view.

```python
scene.render.engine = 'CYCLES'
scene.cycles.device = 'CPU'
scene.cycles.samples = 16
scene.cycles.use_denoising = False
```

**The first GPU render of a process costs about 34 ms instead of 10** while the
Workbench shaders compile. That is not a hang, and it happens once.

#### Render big enough to see what you are looking at

A render is only evidence if the feature you are judging covers enough pixels.
Work out the scale before you trust the picture:

```python
import math
d = (cam.matrix_world.translation - target).length
frame_mm = 2 * d * math.tan(cam.data.angle / 2)        # width the frame covers
mm_per_px = frame_mm / scene.render.resolution_x
px_per_line = 0.4 / mm_per_px                          # 0.4 mm is one nozzle line
```

At the app's default framing - about 2.9x the model's radius - a 512 px render
puts **2.3 pixels on a 0.4 mm nozzle line**. Anything at nozzle scale is a smear
at that size, which is how a real defect stays invisible: it is not that the
render is small, it is that the feature is smaller than the render can resolve.

- **For detail work render at 1024, not 512.** Workbench is a rasteriser on an
  Adreno 740: 512 costs ~56 ms and 1024 about four times that. It is worth it.
- **Frame the region, not the part.** Nobody inspects a 60 mm boat by rendering
  60 mm of it. The camera is shared, so when the user zooms into something they
  are pointing at it - read `camera.json`, keep their `target` and `distanceMm`,
  and your render is of the thing they are looking at.
- **Turn cavity on for surface detail.** `scene.display.shading.show_cavity = True`
  with `cavity_type = 'BOTH'` makes shallow steps and ridges read; a flat studio
  shading hides exactly the defects worth finding.

**And remember a sawtooth is geometry, not an image.** A render rasterises, and
anti-aliasing (this build runs 8 samples) both stops the renderer inventing
staircase edges that are not there and softens real ones. Measure before you
conclude: vertex spacing along the suspect edge, the distribution of edge
lengths, the normal discontinuity between neighbouring faces. Let the picture
confirm what the numbers found - it cannot find it for you, and at the wrong
scale it will show you a defect that is not there, or hide one that is.

Note that this engine build is patched; a stock epai build of the same sources
still dies here. The skill and the engine go together - if renders start killing
the app again, the engine binary is not the patched one.

```python
import bpy
from mathutils import Vector

scene = bpy.context.scene
obj = <the mesh object you want to look at>

for o in list(bpy.data.objects):
    if o.type in {'CAMERA', 'LIGHT'}:
        bpy.data.objects.remove(o, do_unlink=True)

cam = bpy.data.objects.new('look', bpy.data.cameras.new('look'))
scene.collection.objects.link(cam)
scene.camera = cam
bb = [obj.matrix_world @ Vector(c) for c in obj.bound_box]
centre = sum(bb, Vector()) / 8.0
radius = max((v - centre).length for v in bb)
cam.location = centre + Vector((radius * 1.6, -radius * 1.6, radius * 1.3))
cam.rotation_euler = (centre - cam.location).to_track_quat('-Z', 'Y').to_euler()

light = bpy.data.objects.new('sun', bpy.data.lights.new('sun', type='SUN'))
light.data.energy = 3.0
scene.collection.objects.link(light)
light.rotation_euler = (0.9, 0.2, 0.6)

scene.render.engine = 'CYCLES'        # or WORKBENCH for a ~10 ms geometry check
scene.cycles.device = 'CPU'
scene.cycles.samples = 16
scene.cycles.use_denoising = False
scene.render.resolution_x = scene.render.resolution_y = 512
scene.render.image_settings.file_format = 'PNG'
scene.render.filepath = "/data/data/com.tomppi.enderslicercura/files/blender/exports/look-iso.png"
bpy.ops.render.render(write_still=True)
```

Move `cam.location` around the bounding box for front / side / top / iso / a low tilt at a wall, and re-render - a view costs well under a second at 256. **Look as often as you like; this is the required part of the job.** The PNG lands in `exports/`, where the poller ignores it because it only watches `.stl`; read it back and look at it.

Two PC-side renderers also exist as a second opinion - `render_numpy.py` (fast silhouette, no shading) and `render_stl.py` under full Blender - but they are no longer the only way to see, and nothing needs exporting for a routine check.

Write the PNG into `files/blender/exports/`. The poller only watches `.stl`, so an image there is inert - it will not be imported. Pull it back and read it.

**Verify with both eyes.** Renders show shape; bpy measurements show numbers, and the numbers are what catch the failure a render hides:

| what to measure | why |
|---|---|
| ring-band vertex counts by z | a wall with no vertices between two heights cannot be deformed, however it renders |
| z extents of down-facing vs up-facing faces | is the base actually flat, and on the plate |
| non-manifold and degenerate edges | the checks that matter before delivery |

## 5. The tools are already there - check before you hand-roll

**Seventeen bundled addons are enabled at engine startup.** They used to be off,
and that produced a specific and expensive failure: an agent would reach for a
tool it knew perfectly well - LoopTools, F2, Bool Tool - get
`AttributeError: 'Mesh' object has no attribute 'looptools_bridge'` because a
disabled addon's operators are never registered, read that as "this build does
not have it", and hand-roll the equivalent in `bmesh`. Which works, slowly, and
reinvents something with a decade of edge cases already handled.

The operators are live now. **Prefer them to writing your own.**

| the job | use | not |
|---|---|---|
| patch a hole | `bpy.ops.mesh.f2()` (a face from a vertex and its neighbours), `bpy.ops.mesh.fill()`, LoopTools **Bridge** | stitching `bmesh` faces by hand |
| tidy a distorted patch | LoopTools **Relax**, **Flatten**, **Circle**, **GStretch** | smoothing vertices yourself |
| cut one solid out of another | Bool Tool (`object_boolean_tools`), Carver, or a Boolean modifier | reimplementing CSG |
| make a bolt, nut, gear, pipe, spring | `add_mesh_BoltFactory`, `add_mesh_extra_objects` | building primitives from coordinates |
| intersect, extend or align precisely | tinyCAD (`mesh_tiny_cad`): **XAll**, **V2E**, **E2V** | computing intersections by hand |
| check it is printable | `bpy.ops.mesh.print3d_check_all()` - solid, intersections, degenerate, thin, overhangs, sharp | counting boundary edges yourself |
| light a Cycles render | Tri-lighting (`lighting_tri_lights`) | guessing three light positions |
| trace a photograph | `io_import_images_as_planes`; CAD profiles via `io_import_dxf` | eyeballing proportions |

```python
import addon_utils, bpy
print('looptools:', addon_utils.check('mesh_looptools')[1])
print('operators:', [o for o in dir(bpy.ops.mesh) if 'print3d' in o or 'f2' in o])
```

If an operator you expect is missing, check whether the addon is on before
concluding the engine cannot do it:

```python
import addon_utils
addon_utils.enable('mesh_looptools', default_set=False, persistent=False)
```

Enabled: `mesh_looptools`, `mesh_f2`, `mesh_inset`, `mesh_tools`,
`mesh_tiny_cad`, `mesh_snap_utilities_line`, `mesh_auto_mirror`, `mesh_tissue`,
`object_boolean_tools`, `object_carver`, `add_mesh_extra_objects`,
`add_mesh_BoltFactory`, `add_curve_extra_objects`, `curve_tools`,
`lighting_tri_lights`, `io_import_images_as_planes`, `io_import_dxf` - plus
`object_print3d_utils` and `measureit`. Blender bundles 104 and enables 11 by
default; the rest are off because they want UI panels, which this engine has none
of. The operators do not care.

## 6. Debugging

- App-side tags: `BlenderEngine` (resources materialization, watch/poll lines, export dispatch), `BlenderBridge` (`started=true port=9876`).
- Engine-side: `adb logcat -d -v threadtime | grep app_process64` (works only when the wrap property is set; note the wrap wrapper occasionally causes a one-shot start race — relaunch to clear).
- Structure checks (root shell): `ls -la /data/user/0/com.tomppi.enderslicercura/files/blender/` — `python/lib/python3.11/` must exist (stdlib), plus `scripts/`, `exports/`, and `.resources-version` marker. Bumping `RESOURCES_VERSION` in `BlenderEngine.kt` forces re-extraction on next launch.
- `files/blender/exports/` has the handoff files; `files/models/` has staged imported copies.
- **adb goes over the tailnet: `adb connect <phone-tailscale-ip>:5555`** - see section 1. The LAN
  address `<phone-lan-ip>:5555` is the fallback and needs both machines on the same WiFi.
  Inbound TCP *to app ports* still does not traverse the tailnet, so the engine's MCP socket
  stays on loopback plus `adb forward`; adb itself is what rides the tailnet.
- The app drives the engine itself over loopback for the modelling preview, so no forward and no adb is needed for that path. Use adb only for inspection.

### When the engine dies mid-command

The socket just goes quiet - no reply, no error - and the app is back a few seconds later, because the foreground service restarts it. To find out what killed it, capture the engine's own output *before* the fatal call; the report written afterwards is useless:

```python
import os, faulthandler
home = os.environ['HOME']
fd = os.open(os.path.join(home, 'gpu-crash.log'), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
os.dup2(fd, 1); os.dup2(fd, 2)   # catches Blender's C-level output, which bypasses Python
faulthandler.enable()            # catches the segfault and dumps every thread
```

Read it back with `su -c 'cat /data/user/0/com.tomppi.enderslicercura/files/blender-home/gpu-crash.log'`.

**For a C-level backtrace you need a tombstone, and two things get in the way.**
Blender installs its own SIGSEGV handler which writes `cache/blender.crash.txt` -
always with an **empty** backtrace on this port, because its unwinder does not
work under Android - and then exits, so debuggerd never sees the signal and no
tombstone is written. `blender_exec.cpp` therefore passes
`--disable-crash-handler`, which hands the fault to the platform. Then read:

```sh
T=$(ls -t /data/tombstones/tombstone_*[0-9] | head -1)
sed -n '/^backtrace:/,/^$/p' "$T"
```

Symbols matter: build with `llvm-strip --strip-debug` (125 MB, all 238,773
symbols kept) rather than `--strip-unneeded` (96 MB, none). debuggerd prints
demangled C++ frames when they are present, which is what turned "the app dies on
any GPU render" into a named function in one step.

Do **not** enable `faulthandler` when you are chasing a tombstone: it handles the
signal itself and the process never reaches debuggerd.

`bpy.app` still reports `SystemError: GPU API is not available in background
mode` for the Python `gpu` module, and `render.opengl()` still refuses. That is a
Python-level guard, not the render path: `bpy.ops.render.render()` with
`BLENDER_WORKBENCH` now works.

## 7. The modelling session (model from scratch)

When the user picks **Model from scratch** in the app's Blender menu they get a
full-screen view of the model, a chat window, an exit button, and a **camera
they share with you**. The engine starts on its default scene, so a first visit
has nothing to show until you export the default cube to `files/blender/exports/`
under a fresh unique filename.

### The shared camera

`files/blender/camera.json` - beside `imports/` and `exports/` in the engine's
own directory tree - is the handover. **Read it before every render** and place
the render camera from it. Skipping this is the failure that matters: you render
a view the user is not looking at, and the two of you drift apart inside a
single turn.

```json
{
  "yawDeg": 0.0, "pitchDeg": 0.0, "distanceMm": 210.5, "fovDeg": 42.0,
  "target": [0.0, 0.0, 0.0],
  "eye":    [0.0, -210.5, 130.5],
  "up":     [0.0, 0.0, 1.0],
  "owner":  "agent",
  "rev":    7
}
```

`eye` is the camera position **relative to `target`**, already resolved into the
model's own frame; `up` is the matching up vector. Place the camera with them
directly:

```python
from math import radians
from mathutils import Matrix, Vector

loc = Vector(target) + Vector(eye)
fwd = (Vector(target) - loc).normalized()        # a Blender camera looks along -Z
up = Vector(up_vec).normalized()
right = fwd.cross(up).normalized()
up2 = right.cross(fwd)
cam.matrix_world = (
    Matrix.Translation(loc) @ Matrix((right, up2, -fwd)).transposed().to_4x4()
)
cam.data.angle = radians(fovDeg)
```

Do **not** use `to_track_quat('-Z', 'Y')` here. The shared up vector is not global
Y, so the roll - and therefore the image - would not match what the user sees.

**`width` and `height` are the size the user is looking at the model at.** Render at
exactly those and your picture is theirs, pixel for pixel - not merely the same
camera, the same image. Use them rather than a size of your own choosing:

```python
scene.render.resolution_x = spec["width"]
scene.render.resolution_y = spec["height"]
```

They follow the view, so they are not a constant: they change with the device,
the orientation, and whether the chat is expanded. Read them, do not remember
them. Zero means the field was not published - pick your own size then.

**For detail work, go larger than the user's view, never smaller.** These are the
pixels on their screen; a feature finer than they can see is one they cannot
discuss with you.

**`target` and `eye` are in the model's own coordinates** - the same coordinates as
the STL the engine exported. They are *not* printer-bed coordinates. The app draws
the model sitting on the print bed, but that placement is a display concern and
does not exist in the engine's scene: the engine's default cube is centred on the
**origin**, not on the bed. Substituting a bed centre for `target` points the
camera at empty space and every render comes back blank.

**Use the camera as you find it.** It already frames the model. Move `yawDeg` /
`pitchDeg` to look from another side if you need to, and leave `target` and
`distanceMm` alone unless you have actually measured the mesh and know why they
are wrong.

`yawDeg` is the azimuth around the model's vertical axis and `pitchDeg` is the
**elevation above its horizon**, so `pitchDeg: 0` is level with the model and
`90` is directly overhead. Keep pitch inside -89..89: at exactly +/-90 the view
direction is parallel to the up vector and the camera matrix is degenerate.

**A blank or empty render means the camera is not on the model.** Check before you
reply: a blank render reported as success is worse than no render at all.

### The app can replace the scene underneath you

The user can load a model into this engine at any moment - that is what
**Upload model to Blender** does, and it deletes every mesh already there. This
is not hypothetical: an agent's close-up render failed with `'NoneType' object
has no attribute 'data'` because the `Cube` it was working on had been replaced
mid-turn.

`files/blender/scene.json` says when that last happened:

```json
{"rev": 1789310186499, "source": "app-import", "file": "current.stl", "note": "imported 1 mesh(es)"}
```

**Read it at the start of every turn, and re-read the scene before acting on an
object you remember from a previous turn.** `rev` is a millisecond timestamp; if
it is newer than the one you last saw, everything you knew about the scene's
objects is void. Never assume last turn's cube, mesh or selection still exists -
look, then act.

### Ownership: whose camera it is

`owner` is `agent` or `user`, and it is the whole protocol:

- **`agent`** - the camera is yours. Move it freely: render, look, decide, move again.
- **`user`** - the user has paused the camera to study something, usually a
  defect they want you to see. **Do not move the camera while it says `user`.**
  Render from the camera as it stands if you need to see what they are pointing at.

The app writes the file whenever the user orbits or zooms, and hands ownership
back to you the moment they send a message - you never have to claim it. To move
the camera yourself, write the file back in the same shape with `owner: "agent"`
and `rev` incremented, keeping `target` unless you actually mean to move what
the view is centred on.

The user cannot move the camera at all while you are working: the app locks it
until your turn ends. So a long silent turn is also a user who cannot look
around - render, export, and reply rather than working for minutes in silence.
