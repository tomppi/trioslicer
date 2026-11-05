---
name: occt-engine-rules
description: Operating rules for driving TrioSlicer's OCCT/build123d CAD engine through the unified MCP door without hanging it - measured command costs, cheap-first measurement recipes, time/cancel reality, boolean pitfalls, and the evidence order to verify a part.
whenToUse: Before the first execute_code on the CAD engine, and whenever the CAD task needs measurement, verification, sectioning, rendering, threads, or any compute longer than a few seconds.
---

# OCCT engine rules (CAD / build123d)

The phone's CAD engine is real OpenCASCADE (OCP 7.9.3 + build123d 0.12.0) running
single-threaded inside the app process. It is fast for modelling and agonizingly slow for
brute-force sampling, and a command that runs too long cannot be interrupted - it keeps the
engine busy until it finishes or the process is killed. These rules exist because one agent
spent 60,000 point-in-solid classifications (~67 minutes on this phone) to learn what four
cheap measurement calls answer in under a second, and the app's 300 s call timeout threw the
result away anyway.

Read this before the first `execute_code` of a CAD task.

## 0. Where the engine is

- Unified MCP door: `tcp://<phone-tailscale-ip>:9878 token=<token>` (or `adb forward
  tcp:9878` → `tcp://127.0.0.1:9878`). Run `engine_select {"engine":"cad"}` first; its
  answer names the engine, how to reach its tools and its rules, and `tools/list` then shows
  the CAD catalog. The engine's own socket (9877) is the app's private channel - never drive
  it directly.
- The in-app agent sees the same catalog; the tool names below are valid on both lanes.
- `ping` (MCP method) is answered by the server instantly; it says the door is alive, not
  that the engine is free.

## 1. Time, threads and the command contract

- **One command at a time, on one thread.** The engine deliberately serializes commands
  (OCP is not documented thread-safe). A command uses one core; extra cores do nothing for
  it. Android moves the app's cgroup under it: on the dev phone in the foreground the engine
  sat in `/top-app` with `Cpus_allowed_list: 0-7`; on the user's phone during a backgrounded
  run the same engine was in `/background` with `Cpus_allowed_list: 0-2` (the ~2 GHz little
  cores). Long work is therefore faster with the app in the foreground - it is still one
  thread.
- **Slow is fine; unbounded and unannounced is not.** A ten-minute CAD job is a legitimate
  thing to run. What must never happen is a command whose cost was not estimated, whose
  progress nobody can see, and which blocks every other use of the engine until it ends.
- **Today's hard limit: 300 s per tool call.** The app answers a longer call with an engine
  timeout even though the engine keeps running it; when it finally finishes, nobody receives
  the result. So: keep one command under ~4 minutes today, or split the work into steps.
  (If the user asks for a longer single run, say that the app's 300 s gate will discard it.)
- **No cancel.** There is no way to stop a running command from outside. The only recovery
  is restarting the engine from the app's CAD menu (which kills the engine process). Treat
  every long command as unkillable once sent.
- **A second caller waits.** Through the MCP door, every tool call goes through the app's one
  engine connection: a call issued while another is running waits for it (measured: a trivial
  `execute_code` waited 10 s behind a 12 s command and then ran). The wait is not bounded by
  the 300 s tool timeout, so a queued call can hang for minutes.
- **Other engine clients get "busy".** The app's preview/viewport clients connect to the
  engine's own socket. A non-ping request that arrives while a command has already been
  running for >10 s is answered immediately with
  `{"status":"error","message":"engine busy in another command for Ns"}`. `ping` is exempt
  and waits in the queue instead. So during a long command the app's own previews report
  busy - harmless, but it is not something you can fix by retrying.
- **`engine_status` is not "idle".** It answers in ~10 ms from cached readiness and says only
  whether each engine is *running*. A busy engine is "running".
- **A timeout is not a failure you may retry.** The command may still be executing. Do not
  resend it; report the state and let the user decide to restart the engine.

## 2. What things cost (measured on this phone)

Shape used for the expensive row: a 10 x 10 x 5 mm plate with four real M2x0.4 threaded
through-holes (82 faces: 56 cylinders, 12 B-spline helical groove surfaces, 14 planes;
volume 441.555 mm3). Cheap row: a 40 x 20 x 6 mm bracket with four M4 holes and R4 corners
(14 faces, volume 4499.69 mm3).

| operation | threaded plate | bracket | notes |
|---|---|---|---|
| `execute_code` overhead (trivial) | 12-28 ms | 12-28 ms | fresh namespace per call |
| `get_metrics` | 0.45 s | 0.024 s | volume/area/bbox; cost scales with B-rep complexity |
| `get_object_info` | 0.16 s | - | topology counts |
| `describe_at` | 0.04-0.12 s | - | nearest sub-shape, a pick helper (see 4f) |
| `viewer_settings` | 0.013 s | - | appearance only |
| `view` (first frame) | 1.14 s | - | 640x640 PNG |
| `render` (warm) | 0.085 s | 0.085 s | 900x700 PNG ≈ 17 KB |
| `export_step` | 0.15 s | - | 1.9 MB STEP |
| `export_stl` | 1.18 s | 0.051 s | 3.1 MB / 128 KB STL |
| `import_file` (STEP in) | 0.48 s | - | 1.9 MB STEP |
| `section` (one plane, SVG) | **8.5 s** | 0.032 s | sections are where threads hurt |
| `analyze` (wall/overhang) | **14.9 s** | 0.31 s | sampling cost scales the same way |
| face-type census in code | 0.048 s | - | walk all faces, count `GeomAbs_*` types |
| sample 4,800 points on B-spline faces | 0.016-0.07 s | - | ~3-15 us per surface evaluation |
| 60 local dz/dtheta pitch samples | 0.013 s | - | see 4c |
| `BRepIntCurveSurface_Inter` ray | 21 ms/ray | - | 19.7 ms/ray on a groove-face compound |
| `build123d.is_inside` | **67 ms/point** | - | never loop this |
| reused `BRepClass3d_SolidClassifier` | **30 ms/point** | - | still never loop this |
| build: box + bore | 0.02 s | - | |
| build: bracket (builder mode, fillets, holes) | - | 0.106 s | |
| build: swept coil (12.5 turns, circle profile) | 0.06-0.1 s | - | |
| boolean: subtract the coil | 0.4-2.3 s | - | depends on overlap quality (see 6) |

The rule of thumb: **geometry operations cost tens of milliseconds to seconds; point
classification costs tens of milliseconds per point; surface evaluation costs microseconds per
point.** A 60,000-point probe is 30-67 minutes. The same facts from topology + surface
evaluation are under one second.

## 3. Estimate before you loop

Before any loop over geometry, write down:

1. the unit cost (from the table above, or measure 10 iterations first),
2. the iteration count,
3. the product.

If the product is more than a few seconds, look for a cheaper formulation (section 4) or
split it. If it is more than ~4 minutes, it will be discarded by today's 300 s gate - chunk
it, or tell the user what it will take and why.

Print progress inside long commands (`print("step %d/%d" % (i, n))`) every few percent:
it is the only visibility the user and you get, and it tells you which iteration hung if the
call times out.

Do not send 60,000 `is_inside` calls. It is 67 minutes of one core and it answers the same
thing as a 20-line surface check.

## 4. Measurement recipes - cheap to expensive

**Order matters: use the first recipe that can answer the question.** Every recipe below was
run against the threaded plate and returned in the stated time.

### a. Topology census - is it real geometry?

```python
from OCP.TopAbs import TopAbs_FACE
from OCP.TopExp import TopExp_Explorer
from OCP.TopoDS import TopoDS as TD
from OCP.BRepAdaptor import BRepAdaptor_Surface
from OCP.GeomAbs import GeomAbs_Cylinder, GeomAbs_BSplineSurface, GeomAbs_Plane
types = {}
exp = TopExp_Explorer(get("part").wrapped, TopAbs_FACE)
while exp.More():
    gt = str(BRepAdaptor_Surface(TD.Face(exp.Current())).GetType())
    types[gt] = types.get(gt, 0) + 1
    exp.Next()
print(types)   # e.g. 56 cylinders, 12 B-spline surfaces, 14 planes
```

Real M2 threads are **12 B-spline surfaces (3 per hole, full height) plus 14 cylinders per
hole**; cosmetic threads produce none of the B-splines. This is the cheapest possible
"real threads?" proof.

### b. Surface sampling - diameters and depth

```python
from OCP.BRepTools import BRepTools
from OCP.BRepAdaptor import BRepAdaptor_Surface
from OCP.TopExp import TopExp_Explorer
from OCP.TopAbs import TopAbs_FACE
from OCP.TopoDS import TopoDS as TD
import math
part = get("part"); w = part.wrapped
holes = [(-2.5, -2.5), (2.5, -2.5), (-2.5, 2.5), (2.5, 2.5)]   # feature centres
def nearest(x, y):
    return min(holes, key=lambda h: math.hypot(x - h[0], y - h[1]))
lo = hi = None
exp = TopExp_Explorer(w, TopAbs_FACE)
while exp.More():
    ad = BRepAdaptor_Surface(TD.Face(exp.Current()))
    if ad.GetType() == GeomAbs_BSplineSurface:
        umin, umax, vmin, vmax = BRepTools.UVBounds_s(TD.Face(exp.Current()))
        for i in range(10):
            u = umin + (umax - umin) * i / 9.0
            for j in range(200):
                v = vmin + (vmax - vmin) * j / 199.0
                p = ad.Value(u, v); h = nearest(p.X(), p.Y())
                r = math.hypot(p.X() - h[0], p.Y() - h[1])
                lo = r if lo is None else min(lo, r)
                hi = r if hi is None else max(hi, r)
    exp.Next()
print("radial range %.4f .. %.4f  ->  Dmax %.3f" % (lo, hi, hi * 2))
# threaded plate: lo 0.7833 (M2 minor, D 1.567) hi 1.0200 (Dmax 2.040)
```

Notes: `BRepTools.UVBounds_s(face)` gives the **trimmed** parameter box - sampling the
adapter's First/LastUParameter samples the untrimmed surface and can wander off the face.
Group samples by nearest feature centre when several identical features exist.

### c. Pitch from one helix surface - two points per sample

A helical groove surface's parameterization carries the helix: sample two nearby points and
read `dz` and `dtheta`:

```python
p0 = ad.Value(u, v0); p1 = ad.Value(u, v0 + 1e-4)
dth = math.atan2(p1.Y() - cy, p1.X() - cx) - math.atan2(p0.Y() - cy, p0.X() - cx)
while dth > math.pi:  dth -= 2 * math.pi
while dth < -math.pi: dth += 2 * math.pi
pitch = 2 * math.pi * abs((p1.Z() - p0.Z()) / dth)
```

Measured across 60 samples on all 12 groove faces: **median 0.39997 mm** (0.39995-0.40002),
total 0.013 s. Do not try to unwrap theta over a multi-turn surface with coarse samples - the
step can exceed pi and the unwrap silently collapses; use local derivatives or
`BRepTools.UVBounds` (the surface's v-range is the angular sweep: `turns = dv / 2pi`,
`pitch = dz_range / turns` gave 0.3992 mm and is the quick sanity check).

### d. Sections - only when you need a 2D profile

`section` costs 8.5 s on a threaded plate and 0.03 s on a simple bracket; two sections of the
same threaded plate are 16 s. Use it to make a profile for the user or for a drawing, not as
a quick check. If you do compare two sections for pitch, compare rounded radius/angle
multisets with a tolerance - exact vertex lists differ between boolean runs even when the
geometry is congruent.

### e. Point-in-solid - only for a handful of points

```python
from OCP.BRepClass3d import BRepClass3d_SolidClassifier
from OCP.gp import gp_Pnt
cls = BRepClass3d_SolidClassifier(get("part").wrapped)   # build once, reuse
cls.Perform(gp_Pnt(x, y, z), 1e-6)
inside = cls.State() == TopAbs_IN
```

30 ms/point (67 ms via `part.is_inside`). Legitimate for <~20 points or one probe the user
named. Never for a grid, a scan, or a "sample the model" loop.

### f. `describe_at` - a pick helper, not a measurement

`describe_at` reports the exact B-rep sub-shape *nearest* a point (`{"kind":"face|edge|vertex",
"index", "distance_mm"}`), plus normal/area for a face. It is how "the user tapped here" becomes
"face 12". It is not a point-in-solid test and its answer is not a material report: points in
free space come back as the nearest vertex/edge with a distance, not as "nothing there".
Use it to resolve a tap, not to measure.

### g. `get_metrics` / `get_object_info` - first numbers, any time

Volume, area, bbox, centroid, topology counts. Cheap on simple parts (24 ms), 0.16-0.45 s on
the threaded plate. Good enough for "did the boolean change the volume" and "is it the size
the user asked for".

## 5. The verify-a-part workflow

Work down this list and stop as soon as the claim is proven:

1. **Requirements as numbers** - write the target dimensions/features before modelling.
2. **Topology census** (4a): do the expected feature surfaces exist? (threads = B-spline
   helical faces; holes = cylinders at expected radii; fillets = toroidal/cylindrical).
3. **`get_metrics`** (2g): volume/bbox against the analytic target. A 0.1% volume check
   catches most boolean mistakes.
4. **Surface sampling** (4b/4c): the dimension that only the geometry can tell you (thread
   depth, thread pitch, groove radius).
5. **Renders** only to show the user or to check appearance: `render` is the agent's eye
   (the plate is the user's). First frame ~1.1 s, warm frames ~0.09 s.
6. **Sections** only when a 2D profile is the deliverable (8.5 s on threaded parts).
7. **`analyze`** is a printability report for the user (0.3 s bracket, 14.9 s threaded
   plate), not a modelling check.

Report what is verified, how, and what is still a claim. A boolean that returned is not a
boolean that worked (section 6).

## 6. Booleans fail silently - verify every one

Measured while cutting a helical groove into a plate with a 0.7835 mm bore:

| cutter placement | result |
|---|---|
| helix r=0.86 / 0.88 / 0.90 (cutter crosses the bore wall near-tangentially) | **no-op**: volume and face count unchanged, 7 faces |
| helix r=0.9015 (cutter exactly tangent to the bore) | partial: dv 0.068 mm3, 24 faces, 10 B-spline surfaces |
| helix r=0.91 / 0.93 / 0.95 (cutter clear of the bore; fully in material) | dv ~1.57-1.63 mm3, 9 faces, 1 B-spline |
| helix r=0.84 (cutter crosses the bore with a large overlap) | dv 1.1723 mm3, 17 faces, 3 B-splines |

OCCT/build123d can return the input shape unchanged when a boolean fails, and no exception is
raised. So after every cut/fuse/intersect:

```python
before_vol, before_faces = part.volume, len(part.faces())
part = part - cutter
changed = abs(part.volume - before_vol) > 1e-6 or len(part.faces()) != before_faces
print("cut changed:", changed, "vol", round(before_vol, 3), "->", round(part.volume, 3),
      "faces", before_faces, "->", len(part.faces()))
if not changed:
    raise RuntimeError("the cut did not change the shape - fix the toolkit overlap, do not proceed")
```

Rule of thumb from the same measurements: keep boolean tool bodies **clearly** overlapping
(no tangency, no shared faces with the target's existing surfaces). When a boolean silently
no-ops, change the overlap by 0.05-0.1 mm and try again; do not increase sample counts or
timers.

## 7. Building and editing

- **Everything is code.** `execute_code` runs arbitrary Python in a fresh namespace; build123d,
  OCP and the scene helpers are pre-imported. `import time, math, ...` anything else you need.
- **The scene persists, the namespace does not.** `add(name, shape)` puts a shape in the
  scene; `get(name)`, `remove(name)`; `shapes` is a **method** - call `shapes()` to list names.
  In a later call, `get("part")` returns the build123d shape again. Use names.
- **The namespace pre-imports exactly**: `Box, Cylinder, Sphere, Cone, Torus, Plane, Pos,
  Location, Axis, fillet, chamfer, extrude, revolve, loft, sweep, export_step, export_stl,
  import_step, import_brep, build123d, OCP, json, math, os, scene, add, get, remove, shapes`.
  Anything else is `build123d.<name>` or an import.
- **Builder mode works and is fast**: `with build123d.BuildPart() as bp: ...` - a 40 x 20 x 6
  bracket with R4 fillets and four holes builds in 0.106 s.
- **A helical sweep coil is cheap to build** (12.5 turns, circle profile: 0.06-0.1 s); the
  boolean that cuts it costs 0.4-2.3 s depending on overlap quality.
- **Threads**: sweep a profile along `build123d.Helix(pitch=..., height=..., radius=...)`
  (`helix @ 0`, `helix % 0` give the start point and tangent for the section plane) and
  subtract. Keep the cutter clear of the bore void boundary (table in section 6). Verify with
  4a/4b/4c: B-spline helical faces present, radial max = major/2, local pitch = nominal.
- **Fillet after cuts, not before**, and re-check volume/faces after each boolean.

## 8. Output, error and print rules

- `execute_code` returns `{"executed": true, "result": "<your prints>"}`. **Nothing else is
  returned** - the last expression is not echoed. Print what you need.
- An exception returns an `isError` tool result whose text is the **exception message only**
  (no traceback), and prints made before the exception are lost. So: wrap risky stages in
  try/except and print progress/results as you go; on failure, print the exception message
  with context.
- Keep prints to summaries - a few KB. Printing a 20,000-character dump works, but it is
  wasted context for the agent that reads it.
- MCP-level failures are different from command failures: a timeout or an unreachable engine
  is a JSON-RPC error; a command that ran and raised is an `isError` result with the message.

## 9. When something goes wrong

- **"engine busy in another command for Ns"**: another command is still running (>10 s).
  Do not retry immediately; wait, or tell the user and restart the engine from the CAD menu.
- **A tool call timed out**: the engine may still be executing it (no cancel). Do not resend.
  Check `engine_status` (says "running" for a busy engine - expected), report the state, and
  let the user decide whether to restart the engine and lose that compute.
- **A boolean no-op'd**: see section 6 - adjust the overlap; do not paper over it.
- **The engine looks dead but the app works**: the engine process can be killed and restarted
  from the CAD menu. Killing loses the in-engine scene; anything exported is on disk.
- **Never restart the engine yourself from a tool call** - it is the user's session.

## 10. Handing work back to the user

- `export_stl` / `export_mesh` put a mesh on the plate as its own object (the app polls the
  export directory). `export_step` keeps design intent for a file. Exports never change the
  document.
- `render`/view PNGs are for you to look at; the plate is what the user sees. Renders cost
  ~1 s cold, ~0.1 s warm.
- Leave the scene tidy: remove probe shapes you added, keep the named part(s) the user asked
  for, and say what was left in the scene.

## 11. Quick reference

| question | tool/recipe | cost |
|---|---|---|
| is the part the right size? | `get_metrics` | 0.02-0.45 s |
| does the feature exist (real threads)? | face-type census (4a) | 0.05 s |
| what is the thread depth/diameter? | surface sampling (4b) | 0.07 s |
| what is the pitch? | local dz/dtheta (4c) | 0.013 s |
| did the boolean change anything? | volume + face count | ms |
| where is this point on the part? | `describe_at` (4f) | 0.1 s |
| is this point inside? | classifier, a few points only (4e) | 30 ms/point |
| 2D profile for the user | `section` | 0.03-8.5 s |
| show the part | `render` | 1.1 s cold, 0.09 s warm |
| will it print? | `analyze` | 0.3-15 s |
| give the user a mesh | `export_stl` | 0.05-1.2 s |
| give the user a STEP | `export_step` | 0.15 s |
| "just look at it" for a long time | do not; restructure per section 3 | - |

---

## Measured on

- Dev phone M2007J3SG (<dev-phone-serial>, Android 12), TrioSlicer WIP build, app foreground (cgroup
  `/top-app`, all 8 cores allowed) unless noted. The background-cpuset observation is from
  the user's phone-host (Android 13) while the app was backgrounded.
- Engine: "MCP for CAD (Slim)" [0,1]; OCP 7.9.3; build123d 0.12.0; Python 3.11.
- Shape A: 10 x 10 x 5 plate, 4 x M2x0.4 through threads, 82 faces, volume 441.55533 mm3,
  bbox 10 x 10 x 5, imported from STEP in 0.48 s.
- Shape B: 40 x 20 x 6 bracket, R4 corners, 4 x 3.4 mm holes, 14 faces, volume 4499.692 mm3.
- Unified MCP door on 127.0.0.1:9878 via `adb forward`; engine selection `cad`.
- All timings are wall-clock tool-call times (client-side), rounded to the shown precision;
  the same order of magnitude holds on the user's phone.
