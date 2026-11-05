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

## 0. Before you measure or verify — read the rules

The engine is one OpenCASCADE process, one command at a time, one thread. It is fast for
building and slow for brute force, and a command that overruns cannot be interrupted. Before
any measurement, verification or loop over geometry, load
[occt-engine-rules](occt-engine-rules.md) — it carries the measured costs and the
cheap-first recipes. The rules that matter even if you read nothing else:

- **One command at a time.** A long command blocks every other call, the app's own previews
  included; a second tool call waits for it, sometimes for minutes. `engine_status` says
  "running", not "free".
- **Slow is fine; unestimated is not.** A ten-minute job is legitimate when it is doing needed
  work. Estimate unit cost x count before any loop and print progress every few percent. The
  app's call timeout is 300 s: a longer single call is answered as a timeout while the engine
  keeps running it, and the result is discarded — chunk the work below ~4 minutes, or tell the
  user what it will take.
- **Never loop point-in-solid.** `is_inside` is 67 ms per point on a threaded part (30 ms with
  a reused `BRepClass3d_SolidClassifier`); 60,000 points is ~67 minutes and a wedged engine.
  The same facts come from a face census (0.05 s), B-spline surface sampling for
  diameters/depth (0.07 s) and a local dz/dtheta for pitch (0.013 s) — recipes in
  occt-engine-rules.
- **Verify every boolean.** OCCT can silently return the input unchanged when a cut fails:
  compare volume and face count before/after and treat "unchanged" as a failure, not a result.
- **`describe_at` is a pick helper, not a point-in-solid test.** It names the nearest sub-shape
  and distance; use `get_metrics` for volume/bbox and surface sampling for feature sizes.
- **`execute_code` returns prints only** (fresh namespace each call; the scene persists).
  Prints are lost when the code raises, and the error is the exception message without a
  traceback — print progress and expected values as you go.
- **There is no cancel.** Restarting the engine from the app's CAD menu is the only recovery;
  never resend a timed-out command.

Cost highlights on a 10 x 10 x 5 plate with four real M2x0.4 threads (82 faces):
`get_metrics` 0.45 s, `section` 8.5 s, `analyze` 14.9 s, `export_stl` 1.18 s — versus
0.024 s, 0.03 s, 0.31 s and 0.05 s on a simple bracket. `render` is the agent's eye
(1.1 s first frame, 0.09 s warm); the plate is the user's.

## 1. Connect

The app hands all three modelling engines to external agents through **one MCP server** -
see [trioslicer-engines](trioslicer-engines.md) for the whole picture. For this
engine the two facts are: connect once, and the selector points you at CAD.

    tcp://<phone-tailscale-ip>:9878 token=<the token the app printed>

Newline-delimited JSON-RPC 2.0 (MCP), every message carrying the token at the top level or
inside params. Run engine_select {"engine": "cad"} first - its answer names the tools and
their rules - then call the commands in this document as tools/call tools, by their own names
(execute_code, get_scene_info, export_stl, analyze, section, ...). The arguments are this
document's parameters.

**Reaching the port.** The CAD agent sheet's **Reachable on the network** switch publishes the
loopback listener on the phone's tailnet address (TailSocks root mode, node phone-host) and its
**Copy** row puts the whole tcp:// line on the clipboard in one paste. Without a tailnet, adb
forward tcp:9878 tcp:9878 and use tcp://127.0.0.1:9878. The token is app-private storage
(files/agent/engines_mcp_token.txt); **Copy MCP token** in the CAD menu copies just the value -
ask the user rather than reaching for su (no privilege level between shell and root reads
another app's private directory; Shizuku does not change that).

**The engine's own socket (9877) is the app's private channel now** - the type/token/params
wire in section 2 is what the app speaks to the engine; you speak MCP to 9878. The wire
section stays as the background that explains the answers' shape.

**Lifecycle.** The engine starts from the app's OCCT / CAD menu. An engine that answers "not
started" in engine_status must be started there first.


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

### Let the user turn the part, not just look at it

The CAD screen opens on the **model**, not the render, whenever the engine has exported an
STL. That view is the same one the plate uses: drag to rotate, pinch to zoom. The render is
still there behind a **Turn it / Picture** button.

**So export an STL when the shape matters and the user will want to inspect it:**

```json
{"type": "export_stl", "params": {"filepath": ".../exports/part.stl", "name": "part"}}
```

A render answers "what does it look like from here"; a mesh answers "what does the other side
look like", and the user can only ask the second question if they have the mesh. Export both
when the part is the point of the turn - a render to show what you mean, and an STL so they can
check you meant it.

This costs nothing when it is not wanted: no STL, no model view, and the screen falls back to
whatever render exists.

### When the user points at a surface

The user can tap **Pick a surface** in the CAD screen and tap the model. You then receive a
message whose preamble names a point:

```text
[The user picked a surface at x=12.50 y=-3.00 z=0.00 in the model. Call describe_at with those
coordinates to find which face, edge or vertex that is before acting - do not guess from the
picture.]

fillet that edge
```

**Call `describe_at` before acting.** It answers against the exact geometry, not the mesh
triangles the viewer picked:

```json
{"type": "describe_at", "params": {"x": 12.5, "y": -3.0, "z": 0.0, "name": "part"}}
```

```json
{"kind": "face", "index": 5, "normal": [1.0, 0.0, -0.0], "area_mm2": 160.0,
 "distance_mm": 0.0, "centre": [15.0, 0.0, 0.0]}
```

The **index** is what the other commands take - `highlight_faces` for a render, or the face
list for a fillet. The **normal** says which way it faces, which decides whether it is a floor,
a wall or an overhang. The **area** distinguishes the face they meant from one that looks the
same from that angle.

**If `distance_mm` is not near zero, stop and say so.** It means the click landed somewhere
other than the model - a stale view, a mis-tap, or a model that has changed since it was drawn.
Naming the nearest face of a part that no longer exists is worse than asking.

Picking is a one-shot: the coordinates ride with that one message and are then cleared, so a
later message without a preamble has no pick attached.

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
