# CAD Engine (TrioSlicer / enderslicercura embedded)

**This is the app's parametric CAD environment.** Where the Blender engine edits *meshes*
(reshaping a profile, deepening a dish, mesh surgery), this one builds **exact analytic
geometry** — solids with real surfaces, cylinders that are round rather than faceted, STEP
files that carry design intent. Reach for this when the thing being made is a *part*: a
bracket, an enclosure, an adapter, anything with dimensions that must be right.

It is the app's own build123d + OCP, running on the device. It is not a wrapper around a
desktop CAD package, and FreeCAD is not involved — FreeCAD's kernel *is* OpenCASCADE, and
this engine drives that same kernel through the official Python bindings.

Rule of thumb:

| task | engine |
|---|---|
| "make a 40 mm bracket with a 6 mm boss and a 3 mm hole" | **CAD** — dimensions, exact geometry |
| "make this scanned model thinner" / "fix this wall" | **Blender** — mesh editing |
| "turn this photo into a model" | `image-to-3d-model` — then CAD to correct dimensions |

## 1. Connect

The engine is a plain TCP socket on **localhost:9877** inside the app process. It is **not**
Blender's port (9876) — the two can run at once and each has its own.

### Getting the token without root

**The token is in app-private storage, and `adb shell` cannot read it** - even a shell on a
phone where adbd runs as root is a different uid from the app:

```
drwx------  /data/user/0/com.tomppi.enderslicercura   0700, app-only
-rw-------  .../files/cad/cad_mcp_token.txt            0600, app-only
```

**Shizuku does not change this.** It grants *shell* privilege, uid 2000, and shell is refused
by those permissions exactly as a plain adb shell is. There is no privilege level between
shell and root that reads another app's private directory.

**The app has the answer: *Copy MCP token* in the CAD menu**, beside *Model with CAD*. It puts
the token on the clipboard; paste it to the agent and nothing needs root at all. **Ask the user
for it before reaching for `su`** - it is one tap, and it is the difference between a workflow
that needs a rooted phone and one that works on any phone with wireless debugging.

The order that works:

1. The user opens the CAD menu and taps **Copy MCP token**, then pastes it to you.
2. `adb -s <phone> forward tcp:9877 tcp:9877` - **this never needed root**; it asks adbd to
   open a listener and tunnel it, which is an ordinary adb feature.
3. Send commands with the pasted token.

Root is still the fallback if the user cannot reach the phone, and on the dev phone, where
nobody is using it. It is not the first move.

### Reach the engine on the phone the user is actually using

**This is the mistake to avoid, and it has already cost one session an afternoon.** There are
two devices:

| device | serial | whose |
|---|---|---|
| **the user's phone** | `<phone-tailscale-ip>:5555` (tailnet) | **theirs — this is the target when they ask from the app** |
| a dev phone | `<dev-phone-serial>` (USB) | scratch hardware for testing |

**When the user asks for something from the app, the engine is on THEIR phone**, because that
is where the app they typed into is running. Starting the engine on the dev phone and looking
there produces "connection refused" on a socket that is listening perfectly well somewhere
else, and nothing in the error says which device it meant.

The dev phone is for trying things out without touching their device. It is not a mirror: the
two have separate app installs, separate engines and separate tokens.

```bash
# Their phone - the one to use when they asked from the app.
adb -s <phone-tailscale-ip>:5555 forward tcp:9877 tcp:9877
```

**A local port can only be forwarded to one device at a time.** If `adb forward --list` shows
`<dev-phone-serial> tcp:9877`, the dev phone holds it and `127.0.0.1:9877` will reach the *dev* phone
whatever engine you are thinking about. Remove it first:

```bash
adb forward --remove tcp:9877
adb -s <phone-tailscale-ip>:5555 forward tcp:9877 tcp:9877
adb forward --list                     # confirm which device holds it
```

**Every request needs the token**, and the two devices have **different tokens**. Read it from
the device you are actually talking to:

```bash
adb -s <phone-tailscale-ip>:5555 shell cat \
  /data/user/0/com.tomppi.enderslicercura/files/cad/cad_mcp_token.txt
```

Their phone needs no `su`: adbd already runs as root there, and `su` does not exist on that
build — a command wrapped in `su -c` fails with `su: inaccessible or not found`.

A tokenless server **refuses to serve** — it records `"no MCP token loaded; refusing to
serve"` in its status file and never opens the port. There is no token-free command,
`ping` included.

Liveness without geometry:

```bash
# Their phone, unchanged - no su on that build.
adb -s <phone-tailscale-ip>:5555 shell 'ss -tln | grep 9877'
adb -s <phone-tailscale-ip>:5555 shell 'cat /data/user/0/com.tomppi.enderslicercura/files/cad/cad_mcp_status.json'
```

**No status file at all means the engine never got as far as serving** - the process died
before it could write one. That is a different failure from a status file saying
`"running": false`, which is the engine reporting its own refusal. Check the app's own log
with `adb logcat -d | grep -i CadEngine` before assuming the socket is the problem.

The status file is written atomically and reports:
```json
{"running": true, "port": 9877, "pid": 30099, "auth": true}
```

## 2. Protocol

Plain TCP, one JSON object per request, newline-delimited or as a single write. Same
envelope as the Blender engine:

```text
→ {"type": "ping", "params": {}, "token": "<token>"}
← {"status": "success", "result": {"pong": true}}

→ {"type": "ping", "params": {}}
← {"status": "error", "message": "unauthorized: send the token from cad_mcp_token.txt"}
```

- **`params` are keyword arguments.** Omitting `params` on a command that takes arguments
  fails with "missing required positional argument".
- **One command at a time.** A request arriving while another has run for more than ten
  seconds is answered `{"status": "error", "message": "engine busy in another command for
  Ns"}` rather than waiting out your timeout.
- **A reply is capped at 1 MiB** by the app's client. A reply is a control message, not a
  payload — the STEP and STL bytes come back through files. Printing a whole mesh is a
  **failed command, not a slow one**. Keep `print()` to a summary.

### Commands

| type | params | notes |
|---|---|---|
| `ping` | – | liveness; touches no geometry. Needs the token |
| `shutdown` | – | replies, then releases the socket. The engine parks; it does not exit |
| `execute_code` | `code` | the workhorse. namespace: `build123d`, `OCP`, `json`, `math`, `os`, and the scene helpers |
| `get_scene_info` | – | every shape with volume, bounding box and solid count |
| `get_object_info` | `name` | one shape, plus solid/face/edge/vertex counts |
| `get_metrics` | `name` (optional) | volume, area, bounding box, centroid. No name = all |
| `export_step` | `filepath`, `name` (optional) | atomic; no name = whole scene |
| `export_stl` | `filepath`, `name` (optional), `tolerance` | atomic; no name = whole scene |
| `import_file` | `filepath`, `name` (optional), `unit` (STL only) | STEP, STP, STPZ, BREP, STL, SVG |
| `clearance` | `cloud`, `name` or `x`,`y`,`z` | distance from the part to a scanned environment |
| `viewer_settings` | `tessellation`, `background`, `grid`, `grid_step_mm`, `axes`, `projection`, `edges`, `antialiasing` | the viewport's appearance, not the model |
| `view` | `turn_yaw`, `turn_pitch` (degrees), `pan_dx`, `pan_dy`, `zoom`, `reset`, `orientation`, `select_x/y` | the screen's camera: turn it, or pick a face in it |
| `render` | `filepath`, `name` (optional), `view`, `width`, `height`, `shaded` | writes a PNG; the user's view of the model |
| `get_addon_info` | – | engine version and kernel state |

## 2a. Units - one unit is one millimetre

`build123d` is unitless, and this engine treats **one unit as one millimetre**. That is
also what every slicer it hands geometry to assumes, so the number you write is the
number of millimetres in the part. A request in centimetres has to be converted, and
that conversion is the whole trap:

| the user asks for | write | the part is |
|---|---|---|
| 20 x 20 x 20 cm | `Box(200, 200, 200)` | 20 cm - correct |
| 20 x 20 x 20 cm | `Box(20, 20, 20)` | 2 cm - **ten times too small** |
| a 40 mm bracket | `Box(40, ...)` | 40 mm - correct |

**A wrong size is invisible in the viewport.** The camera fits whatever is in the scene,
so a 2 cm cube fills the frame exactly as convincingly as a 20 cm one - there is no grid
and no scale reference. The first place a unit error becomes visible is the slicer's
**Size** field, after the part has been exported.

So convert, and then **state the size in millimetres in your reply**: "20 x 20 x 20 cm,
built as 200 mm a side". A number in the reply is what lets the user catch a unit slip in
the chat rather than on the build plate.

## 3. The scene

Blender's engine gets persistence free from `bpy.data`. There is no equivalent here, so the
engine owns a dict of **named shapes** — the handle you use between commands.

Inside `execute_code`:

| helper | purpose |
|---|---|
| `add(name, shape)` | put a shape in the scene |
| `get(name)` | fetch one back |
| `remove(name)` | drop one |
| `shapes()` | list the names |
| `scene` | the underlying dict, if you want it directly |

`build123d`'s common names are pre-imported — `Box`, `Cylinder`, `Sphere`, `Cone`,
`Torus`, `Plane`, `Pos`, `Location`, `Axis`, `fillet`, `chamfer`, `extrude`, `revolve`,
`loft`, `sweep`, `export_step`, `export_stl`, `import_step`, `import_brep` — so a script
can start modelling immediately. For anything else, `from build123d import ...`.

**Objects persist between commands.** A shape added in one call is there in the next.

## 4. Worked examples

A part, in one call:

```json
{"type": "execute_code", "params": {"code":
  "part = Box(40, 20, 5) - Pos(0,0,0) * Cylinder(3, 20)
add('plate', part)
print(part.volume)"},
 "token": "<token>"}
```

Fillet only the vertical edges — where build123d earns its place over raw OCP:

```python
part = fillet(part.edges().filter_by(Axis.Z), radius=2)
add("plate", part)
```

Import a STEP, modify it, export it:

```python
from build123d import import_step
part = import_step("/path/in.step")
add("part", part)
print("imported, volume", part.volume)
```

Read a number back rather than guessing — always cheap:

```python
print("volume", round(part.volume, 4), "bbox", part.bounding_box().size)
```

## 4a. Importing an existing model

`import_file` brings a file into the scene under a name, after which it is an ordinary
shape — addressable, measurable, and modifiable like anything modelled here.

```json
{"type": "import_file", "params": {"filepath": "/path/part.step", "name": "housing"}}
```

**STEP is the one worth importing.** It carries analytic geometry, so a hole comes back as a
cylinder rather than a ring of triangles and can be re-dimensioned. Verified: a STEP
round-trip preserves volume exactly (delta 0.000000), and a shape imported that way can be
cut, filleted and re-exported.

| format | what you get |
|---|---|
| STEP / STP / STPZ | **exact solid** — the only format that survives editing |
| BREP | exact solid, OCCT's native format |
| SVG | 2D curves, for `import_svg_as_buildline_code` |
| STL | **a surface, not a solid** — see below |

## 4b. The camera is a turntable

`view` turns the camera the way the rest of the app does: **two angles**, not a free rotation.

```json
{"type": "view", "params": {"turn_yaw": -35.0, "turn_pitch": 20.0}}
```

`turn_yaw` is an azimuth around the world's vertical axis and `turn_pitch` an elevation above
the horizontal plane, both in **degrees**, both relative to where the camera already is. Pitch is
clamped to ±89 degrees and the up vector is world Z, always - so a roll is *impossible* rather
than corrected, which is the rule the plate's own viewer follows (`ModellingCamera.upVector`, and
a pitch clamped the same way in `ModellingPreview`). The app's one-finger drag sends
`dx * 0.35` and `dy * 0.35` degrees, the same rate that viewer uses, so a centimetre of finger
means the same thing in both places.

This replaced an arcball (`StartRotation`/`Rotation`), which rolls as it turns: a diagonal drag
left a part lying on its side, which is what was reported. Four other mechanisms were tried
against the same problem and each failed for its own reason, all measured on the device:

| call | result |
|---|---|
| `Rotate(0, 0, a)` about the view axis | rolled correctly, but only from an iso view |
| `Twist` / `SetTwist` | work from an iso view; build a screen basis from world Z/Y/X and mangle the camera from anywhere else |
| camera up vector via `SetCamera` | accepted and ignored - the renderer honours eye and centre, not up |
| rebuilt camera (eye + centre + up) | changed the projection's scale too; the part came back a sixth of its size |

`SetProj` is the one call this driver has always honoured - it is what the view presets use - and
the source is explicit that it sets the camera's *direction* and nothing else, so the distance and
the centre survive and turning a part cannot move or resize it.

**Verified** with a world-vertical bar, whose edges stay vertical in the picture for any turn:
iso, front and a 40-degree turn all measured a lean of **0.0 to 0.5 px**; the arcball leaned it by
tens of pixels. The app path was checked separately by swiping the dev phone's screen: a 200 px
horizontal swipe turned the model (22% of the frame changed) and it stayed upright.

## 4c. The viewport's appearance

`viewer_settings` sets how the scene is drawn, and answers with what the viewer reports back.
Every value is optional, and the answer carries the viewer's own state - which is the only way to
trust any of it, because **this driver accepts calls it does not honour**:

```json
{"type": "viewer_settings", "params": {"background": "light", "projection": "orthographic"}}
```

Three things were measured on the device rather than assumed, and each changed the code:

- **`V3d_View.SetGrid` / `SetGridActivity` segfault the engine.** Not ignored - the process died,
  reproducibly. The grid is drawn as ordinary edges instead: a real thing of a real size in the
  scene, displayed but never added to SCENE, so it cannot reach an export.
- **MSAA is ignored** - the frame is byte-identical with `NbMsaaSamples` at 0 and at 4.
  Anti-aliasing supersamples in `_write_view` instead, and the app asks for it only on the frame
  that settles, never mid-drag.
- **`Camera()` returns a copy**, so setting the projection type on it does nothing until it is
  handed back with `SetCamera`. Tessellation is honoured by meshing the shape *before* AIS sees it:
  the drawer's deviation coefficient, like `SetDrawEdges`, changes nothing on this GLES path.

A frame with the cube the engine seeds on startup, at 640x640:

| setting | pixels changed |
|---|---|
| background | 292720 |
| grid | 138122 |
| axes | 199 |
| projection | 106731 |
| edges | 7895 |
| anti-aliasing | 42449 (470 distinct colours off, ~2700 on) |
| tessellation coarse / very fine | 54979 / 24873 |
| view preset | 148141 |

## 4b. Measuring against a scanned environment

A capture of a real place arrives as a point cloud — `environment.npy`, an Nx3 float array in
millimetres, written beside the mesh by the capture pipeline. `clearance` answers the question a
jig turns on: how far is my part from the real thing?

```json
{"type": "clearance", "params": {"cloud": "/…/env/environment.npy", "name": "bracket"}}
{"type": "clearance", "params": {"cloud": "/…/env/environment.npy", "x": 0, "y": 0, "z": 150}}
```

Give `name` and it measures a shape in the scene — vertices, edge midpoints, and a grid over every
face, because a corner can be millimetres clear while the middle of a face is touching. Give
`x`, `y`, `z` and it measures a single point. The answer carries both ends of the closest pair:

```json
{"shapes": "bracket", "distance_mm": 47.46, "from": [686.6, -765.3, 52.5],
 "to": [686.6, -765.3, 5.0], "probes": 140, "cloud_points": 295915}
```

**Measure against the cloud, not the mesh in the scene.** The imported mesh is decimated for
display; the cloud is the reconstruction. The tree over it is built once per file and kept, so the
first call costs about a second on 300k points and later ones about 20 ms.

Verified against known offsets: from a point on the reconstructed floor, ±50 mm and ±100 mm read
back as 47.2/47.5 and 97.2/97.3 mm — the shortfall is the point's own distance to the nearest
sample, not an error in the query.

### STL imports as a surface, not a solid

An STL carries triangles and no topology, so there is no "inside" to measure. `import_file`
on an STL reports **`volume: 0.0`**, and that is correct rather than a failure.

You can still use it: measure with `part.area`, cut it, or wrap it with
`build123d.Solid.make_solid(...)` when the mesh is watertight. But do not expect a boolean on
a raw imported STL to behave like one on a solid — and say so to the user rather than
reporting a mysterious zero volume.

## 5. Handoff into the app

Export to the app's handoff directory and the app picks it up:

```
/data/user/0/com.tomppi.enderslicercura/files/cad/exports/<name>.step
/data/user/0/com.tomppi.enderslicercura/files/cad/exports/<name>.stl
```

**Export writes through a `.part` name and renames into place.** The rename is what
publishes the file — a half-written STEP is not a file the app can use. This is automatic in
`export_step` / `export_stl`; if you write a file yourself, do the same.

Use a fresh name per generation. A rewrite of the same path only re-fires if the timestamp
changes.

## 5a. Rendering — the user's eyes

The engine has a GL viewer and no window. It renders offscreen and writes a PNG, and the app
shows the newest one as the view. **Render whenever the user should see the result** — after
a shape change, before reporting a part done, or when asked what it looks like.

```json
{"type": "render", "params": {"filepath": ".../files/cad/exports/iso.png", "view": "iso"}}
```

| argument | default | notes |
|---|---|---|
| `view` | `iso` | `iso`, `top`, `front`, `right`, `left`, `back`, `bottom` |
| `width`, `height` | 640 | capped at 2048 |
| `shaded` | true | false draws wireframe only |
| `name` | whole scene | render one shape |

**Which view to pick.** `iso` shows a part as a part — use it by default, and after any
change. The orthographic views are for checking *where something is*: a hole's position, a
boss's diameter, whether two features line up. Render both when the question is "is this
right" rather than "what does it look like".

**Shaded renders carry edges**, drawn as a wireframe overlay rather than by OCCT's own edge
setting (`SetDrawEdges` has no effect on this GLES path — the renders come out
byte-identical with and without it). Without the overlay a boss on a plate is invisible from
directly above, which is exactly the view used to check a feature's position.

**Renders cost seconds, not milliseconds** — the viewer is built once and kept, but each
render re-displays the scene. Do not render in a loop.

## 5b. 2D — drawings and flat parts

Two different jobs with two different APIs. Both are build123d, so both run through
`execute_code` like anything else. Everything below was run on a device, not written from
memory.

### Flat parts: cut files

For a plate with holes in it — something a laser or a CNC would cut — export the 2D profile.
Real files with real units, not pictures:

```python
from build123d import *
from build123d.exporters import ExportSVG, ExportDXF

part = Box(80, 50, 3) - [Pos(x, 0) * Cylinder(3, 6) for x in (-25, 0, 25)]
top = part.faces().sort_by(Axis.Z)[-1]          # the profile to cut

svg = ExportSVG(unit=Unit.MM)
svg.add_shape(top)
svg.write("plate.svg")

dxf = ExportDXF(unit=Unit.MM)
dxf.add_shape(top)
dxf.write("plate.dxf")
```

Measured on the device: 583 bytes of SVG, 16,099 of DXF.

**The exporters take no shape in the constructor.** `ExportSVG(top)` does not mean "export
top" — the argument lands on `unit` and it fails with `Invalid unit. Supported units are mm,
cm, in.`, which says nothing about shapes and sends you looking in the wrong place. Geometry
goes in through `add_shape`.

**`ExportDXF` colour wants a `ColorIndex`, not an int.** `add_layer(color=1)` raises
`'int' object has no attribute 'value'`; use `ColorIndex.RED`.

### Technical drawings: hidden lines

A drawing is a projection that keeps the hidden edges as a separate set, which is what makes
it readable — a hole through a part shows as dashed lines instead of vanishing. `Drawing`
wraps `HLRBRep_Algo` and `HLRAlgo_Projector`:

```python
from build123d import *
from build123d.exporters import ExportSVG, Drawing, LineType

part = Box(60, 40, 20) - Cylinder(5, 30)
d = Drawing(part, look_at=(0, -100, 0), look_up=(0, 0, 1))     # front
visible, hidden = d.visible_lines, d.hidden_lines

svg = ExportSVG(unit=Unit.MM)
svg.add_layer("visible", line_weight=0.5)
svg.add_layer("hidden", line_weight=0.25, line_type=LineType.ISO_DASH)
for edge in visible: svg.add_shape(edge, layer="visible")
for edge in hidden:  svg.add_shape(edge, layer="hidden")
svg.write("front.svg")
```

Measured on the device: that box with a hole gives 1 visible and 2 hidden lines for the front,
and the same for the top, in 2,152 bytes. A plausible line count is the check that it worked —
a drawing that comes out empty means the view is wrong, not that there is nothing there.

**`Drawing` is in `build123d.exporters`.** It is not re-exported at the top level, so
`from build123d import Drawing` fails with `name 'Drawing' is not defined`.

**The attributes are `visible_lines` and `hidden_lines`** — not `visible`/`hidden`, and there
is no `lines()`.

**A top view needs `look_up=(0, 1, 0)`.** With the default the projection direction and the up
vector are parallel and OCCT raises `gp_Dir() - input vector has zero norm`, which does not
mention the view you asked for.

### The user cannot see an SVG

**Android has no SVG preview.** An SVG sent to the phone opens as a blank page or nothing at
all, and the same goes for DXF. Render a PNG beside every drawing and send both — the SVG is
the deliverable, the PNG is how it gets looked at. Use `render` (section 5a) on the same part.

## 6. Look at your model — required, and cheap

**Looking at the result is part of the job, not a nicety.** CAD fails quietly: a fillet that
silently did nothing, a boolean that left a zero-thickness wall, a hole in the wrong place.
An agent that models blind produces confident, plausible, wrong geometry.

Two ways, both cheap:

1. **Numbers first.** `get_metrics` returns volume, area, bounding box and centroid. Most
   mistakes show up here — a volume that did not change after a cut means the cut missed; a
   bounding box shorter than expected means an operation was dropped. Check after every
   destructive step.
2. **Then look at it.** Export an STL and let the app display it. That catches what numbers
   cannot — an inverted normal, a feature on the wrong face, a wall that is not there.

Do not ration this. Both are fast, and a wrong part costs far more than the check.

## 7. Troubleshooting

| symptom | cause |
|---|---|
| `unauthorized` on everything, `ping` included | token missing or wrong. Wrong token and unreadable file look identical — check what you actually read |
| connection refused | engine not running. The app must be started; the engine lives only while the app process does |
| `engine busy in another command for Ns` | a previous command is still running. Wait, or find what hung |
| `Unknown command type` | typo, or a command this engine does not have |
| `no MCP token loaded; refusing to serve` in the status file | the app did not write the token before starting the engine |
| `'''OCP...''' object has no attribute ...'''` | a binding the official bindings do not expose. Report it — this engine has needed hand-written bindings before (`BRepTools.Clean_s`, `XCAFDoc_DocumentTool.SetLengthUnit_s`, `STEPCAFControl_Writer.Transfer`, `StlAPI_Writer.Write`) |

## 8. What this engine is not

- **Not a slicer.** It makes the model; the slicer engines make G-code.
- **Not the Blender engine.** No `bpy`, no scene objects, no materials, no rendering. If the
  task is mesh surgery, that is Blender's job.
- **Not FreeCAD.** No document tree, no workbenches, no GUI.

## 9. What the OCCT layer underneath can do

build123d is a comfortable wrapper over part of OCCT. `execute_code` can call **all** of it -
316 toolkits are bound and build123d imports 84 - so when a job looks impossible in build123d,
drop to OCP before concluding it cannot be done.

**Verified working on a device**, in the engine's own environment:

| capability | how |
|---|---|
| **IGES import and export, exactly** | `IGESControl_Reader` / `IGESControl_Writer` |
| **The repair pipeline** | `ShapeProcessAPI_ApplySequence("ToFix").PrepareShape(shape)` |
| **Variable-radius fillets** | `BRepFilletAPI_MakeFillet.Add(radius, edge)` per edge - build123d takes one radius |
| **Boolean validity** | `BOPAlgo_ArgumentAnalyzer`: shapes in, `Perform()`, read `HasFaulty` |
| **Shape-to-shape distance** | `BRepExtrema_DistShapeShape` + `PerformDist()` |
| **Sweeps with a real section** | `BRepOffsetAPI_MakePipeShell` - the section must be a wire or face, not a solid |
| **Point in solid, sections** | `BRepClass3d_SolidClassifier`, `BRepAlgoAPI_Section` |
| **Highlighting faces in a render** | the `render` command's `highlight_faces` / `highlight_overhang` |

**`render` with a highlight** is how you point at something instead of describing it:

```json
{"type": "render", "params": {"filepath": ".../support.png", "view": "front",
                              "highlight_overhang": 45.0}}
```

That draws every face steeper than 45 degrees in red and reports which indices they were, so
"this needs support" becomes a picture. Pass `highlight_faces: [0, 1]` to mark specific ones.
An index the shape does not have is skipped rather than raising - the model may have changed
since you counted faces.

### Two things that are not there yet

**glTF export does not work, and `export_mesh` says so.** OCCT's `RWGltf_CafWriter`
returns False and writes nothing on this platform - the binding is now present, the writer
itself fails. `export_mesh` raises "the exporter reported failure" rather than reporting a
file that was never created. **Use BREP, STL, STEP or OBJ.**

**`build123d.Text` returns an empty compound.** The glyph pipeline underneath it *works* -
`StdPrs_BRepTextBuilder.Perform` with a `StdPrs_BRepFont` returns real geometry, and this
was verified by rendering an H and measuring the result - so the gap is in build123d's own
wrapping, not in the bindings. Constructing `FontManager()` first is required: the bundled
fonts are registered lazily, and until then `FindFont("singleline")` returns None.

### Exporting a mesh yourself

`Poly_Triangulation` has `Node`, `Triangle`, `Normal`, `UVNode` and (now)
`MapNodeArray`; `BRep_Tool.Triangulation_s` reads a face's triangulation. `export_mesh`
writes OBJ through those directly - `build123d.export_obj` needs
`Poly_Triangulation.ComputeNormals`, which is not bound.

**Read the face location with the triangulation.** A face's triangles are stored in that
face's own frame; skipping `TopLoc_Location.Transformation()` puts them where the part is
not. And reverse the winding on a face whose orientation is `TopAbs_REVERSED`, or a viewer
that trusts the winding lights it inside out.

### If you need to bind something yourself

**pywrap drops `Perform`.** The method that actually runs an OCCT algorithm is missing from
several classes - `ShapeFix_Shape`, `BRepExtrema_DistShapeShape` and `StdPrs_BRepTextBuilder`
all lost theirs - which leaves a class inert from Python even though every accessor around it
is bound. **Check for `Perform` before assuming a class cannot do something.**

Adding a binding is a patch to `/root/occt-port/OCP/OCP/<Toolkit>.cpp` plus a rebuild, and
**one rebuild covers every patch**, so enumerate them all first. Do not fix them one
`AttributeError` at a time: diff the toolkit header in
`/root/occt-port/build/OCCT-7_9_3/src/<Toolkit>/` against the `.def` names already in the
binding file. That is how two missing triangulation methods were found together, after three
rebuilds spent finding text bindings one at a time.

