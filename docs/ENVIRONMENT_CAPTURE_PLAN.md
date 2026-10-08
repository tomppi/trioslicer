# Capturing the workshop: an environment for the CAD agent to model in

A plan, not a description of anything that exists yet. Written after reading the model's own
repository, its licence and its output format, and against what this project already has.

## The idea

The CAD engine is good at parts and blind to context: it knows a bracket and not the rail the
bracket has to clamp. Give it the *surroundings* - a reconstruction of the real thing, at a known
scale - and the conversation changes from "make me a 20 mm wide clip" to "make me something that
fits this, here".

The route: a phone video of the job, with one object of known size in frame, reconstructed on the
GPU box into a point cloud, scaled and squared up, handed to the phone as a mesh the CAD engine can
show, measure against and model inside.

    phone video (with a scale marker)              <- the capture
        |
        v
    GPU box: HunyuanWorld-Mirror -> point cloud + cameras + depth + normals
        |
        v
    scale from the marker, rotate to a CAD frame, mesh, decimate
        |
        v
    environment.stl + environment.json  ->  adb push into the phone's export dir
        |
        v
    CAD engine imports it as `env`; the agent models the jig against it
        |
        v
    jig.stl -> plate -> slice -> print                  <- the part

## What the model is, exactly

[HunyuanWorld-Mirror](https://github.com/Tencent-Hunyuan/HunyuanWorld-Mirror) (Tencent, ICML 2026)
is a feed-forward reconstruction model: one forward pass over N images or a video gives camera
poses, intrinsics, per-view depth, surface normals, a **world-frame point cloud** and 3D Gaussians.
No per-scene optimisation, so a capture is seconds of GPU rather than the minutes-to-hours
photogrammetry costs.

Facts that matter here, from the repository itself:

| | |
|---|---|
| weights | `model.safetensors`, 5.05 GB (~2.5B parameters at bf16) |
| input | a video or a directory of images, `target_size=518`, `fps` for frame extraction |
| output | `pts3d` [S,H,W,3] in world coordinates with confidence, per-view depth and normals, camera poses (OpenCV convention), intrinsics |
| environment | python 3.10, CUDA 12.4, torch 2.4, `open3d`, `pycolmap`, `gsplat`, `viser` - a **new venv**, the box's existing one is Hunyuan3D's |
| GPU | to be measured on the box (M0). 5 GB of weights plus attention over all patches |

**There is no mesh in the output.** The point cloud is the geometry; a surface comes from meshing
it (Poisson or a TSDF, open3d does both). That step is ours, and it is where most of the shape
fidelity can be lost or kept.

## The three hard parts

### 1. Scale, which the model does not give you

`pts3d` is in an arbitrary world frame - nothing in the output says how many millimetres a unit is.
A reference of known size in the capture is therefore mandatory, not optional.

Best: a **printed AprilTag / ArUco marker taped to the job**, size known exactly. It can be found
in the 2D frames deterministically, so its corners can be lifted into 3D through the predicted
point map (or the depth + intrinsics) and the scale factor comes out of a rigid fit rather than an
eyeball. A ruler works too, and is friendlier to explain to someone standing at a bench; the agent
would measure its 100 mm span in the cloud and divide. The marker is repeatable, the ruler is
improvised - both beat "I'll guess".

### 2. Accuracy, which will not be what a fit needs

The linear rail is one example of the job; the shape of the problem is *any part that has to fit
something real*. Where a jig touches that something, it needs tenths of a millimetre. A phone video
reconstruction will be around a millimetre at best, and worse on shiny, dark or repetitive surfaces
- which is what rails, extrusions and dowel holes are. This is the fact that should shape the whole
design:

- **The environment is context, not a datum.** It answers "where is the thing, which way does it
  run, what is in the way, how much room is there".
- **Fits come from the user's calipers**, or from a close-range capture at a known scale. The
  agent must be told which dimensions it may take from the cloud and which it must ask for.
- Every delivery should carry an **accuracy note** alongside the geometry, so the agent has the
  number in front of it rather than an assumption.

Get this wrong and the failure is quiet: the agent models 20.0 mm from a cloud that says
19.6-20.4 and the printed part either rattles or will not go on.

### 3. Steel on the phone

A raw cloud is millions of points; the CAD engine must stay responsive while the agent models. Two
budgets, deliberately different:

- **On the phone**: a decimated mesh (target ≤250k triangles) for display and picking, imported
  through the path that already exists - `import_file` takes STL today.
- **On the harness**: the full-resolution cloud, for measurement queries the agent asks for
  ("how far is this face from the environment?"). `scipy.spatial.cKDTree` in the CAD payload can
  answer that; making the agent measure from the render would be asking it to read a photograph.

## Open decisions

1. **Licence.** Not open source: one custom agreement, the Tencent HunyuanWorld-Mirror Community
   License, whose first line says the agreement "does not apply in the European Union, United
   Kingdom and South Korea" and whose grant is "for the Territory only", Territory being the world
   minus those three. The clause is identical in Hunyuan3D-2, which the image-to-3D pipeline
   already runs, so the environment pipeline adds nothing new in kind.

   **What "excluded" means, precisely.** It is a carve-out from the *grant*, not from the
   *restrictions*: a licence is the thing that gives permission, so outside the Territory there is
   no permission rather than unlimited permission. "The agreement does not apply to me" is not a
   defence for using the work - it means there is no licence at all, and the default is copyright.
   Nothing enforces this against a hobbyist running a model on their own bench, and the weights are
   not gated (no click-through, so nobody has accepted anything either); but that is a
   risk-tolerance judgement, not a permission, and only Tencent can grant rights in the EU.

   Worth separating the two exposures: **the app does not ship Hunyuan** and never has (the
   repository mentions it only in docs), so nothing distributed is affected. What is affected is
   running it on the box. The options:
   - keep it as a local bench tool, as the image pipeline does today - no redistribution, no
     hosting, and the outputs are explicitly not Model Derivatives;
   - photogrammetry (COLMAP + OpenMVS: BSD-3 + AGPL-3), same scale-marker step, weaker on
     textureless and shiny surfaces;
   - a permissive metric-geometry model - **licences to be checked one by one**, not assumed.
2. **Scale reference**: printed marker (recommended) or ruler.
3. **Capture**: video (`fps=1`, recommended - one pass around the job) or stills.
4. **The accuracy contract**: confirm the split above, or decide to invest in close-range captures
   for tighter fits.
5. **Wake the box** for M0: the wake is the reliable half of that skill, the resume is not.

## M0: measured on the box (done)

The box is an **RTX 3060 Ti with 8192 MiB**, 16 cores, 15 GB RAM, 173 GB free - and the model is
2.5B parameters, 4.8 GB of weights. That is the whole story of this milestone: it runs, at a size
the card can hold, and the ceiling is the card.

Its own examples, its own settings, `target_size` as the dial:

| scene | views | target_size | result | wall | peak VRAM |
|---|---|---|---|---|---|
| Workspace | 4 | 518 (default) | **OOM** | 18 s | 7101 MiB |
| Workspace | 4 | 448 | ok | 15 s | 7825 MiB |
| Workspace | 4 | 336 | ok | 29 s | 7547 MiB |
| Snow | 6 | 336 | ok | 15 s | 7285 MiB |
| Valley | 11 | 336 | **OOM** | 13 s | 7781 MiB |

So on this card: **4-6 views at 336-448 px, in about fifteen seconds**, model load included.
Eleven views does not fit at any size tried, and the default 518 px does not fit at all - the
attention is over all patches of all views, so both dials cost memory, and views cost it fastest.

What one run produces (`Workspace`, 4 views, 336):

    pts_from_pointmap.ply   220,992 points      the geometry, in an arbitrary world frame
    gaussians.ply            15 MB              for splat rendering
    depth/ normal/           per-view maps
    sparse/0/                a COLMAP reconstruction
    rendered_rgb.mp4         a tour of the reconstruction

Those point counts are the good news: 221k points at 336 px, 387k at 448 - hectares less than the
million-point clouds this was feared to produce, and comfortable to mesh, decimate and deliver.

Three things follow for the pipeline:

1. **A capture must be small.** Either 4-6 chosen stills, or a video whose frames are sampled down
   to that - which is fine, because the point cloud does not need every frame; it needs the views
   to be far enough apart to be worth having.
2. **Full quality needs more card.** 518 px and 11+ views is where this model wants to be, and it
   is not reachable on 8 GB. A rented GPU, or a card with 24 GB, is the difference between "the
   free demo quality" and "the paper's quality" - worth knowing before the accuracy discussion.
3. **The scale problem is exactly as advertised**: the Workspace reconstruction's extent came out
   0.79 x 0.60 x 1.00 - arbitrary units, no millimetres anywhere in it.

Environment: `/home/tomppix/worldmirror` on the box - `venv` (python 3.10.22, torch 2.4.0+cu124,
open3d 0.18.0, pycolmap 3.10.0, gsplat 1.5.3+pt24cu124), the repo clone, and `out/<scene>-<size>/`
for each run. Weights are in the shared HF cache.

## M1: the scale, the up, and the mesh (tool ready, verified against ground truth)

`env_to_cad.py` on the box (`/home/tomppix/worldmirror/`) — a point cloud in, a CAD mesh and a
JSON in:

    env_to_cad.py --points out/Workspace-336/Workspace/pts_from_pointmap.ply \
                  --out env/workspace \
                  --reference-mm 100 --reference-p1=1.2,0.4,0.9 --reference-p2=1.9,1.1,0.88

The two reference points are the ends of the known-size thing, in the cloud's own coordinates, and
the `=` matters: a coordinate that starts with a minus looks like an option to argparse.

What it does, in order: scale from the reference; drop statistical **and** radius outliers (both,
because they fail differently — a sparse population of its own survives the statistical pass);
find the bench as the biggest plane and put it on z=0, refining the fit by total least squares
because RANSAC's answer comes from three points and a tenth of a degree tips a 400 mm bench
several millimetres; Poisson-mesh it, crop to the cloud, decimate to a phone-sized budget; write
`environment.stl`, `environment.ply` (the aligned cloud, unmeshed) and `environment.json`.

**It was verified against a scene whose true size we chose** — `test_env_to_cad.py`, which builds a
400 x 300 mm bench, a 100 x 60 x 20 mm block and a 100 mm reference bar in true millimetres,
converts them to an arbitrary unit (1 unit = 0.371 mm), tilts the lot, sprinkles 300 outliers
through it, and then checks what comes back:

| | truth | recovered | |
|---|---|---|---|
| scale, from the 100 mm reference | 0.371 mm/unit | 0.371000 | 0.00 % |
| extent | 400 x 300 x 20 mm | 398.83 x 299.07 x 21.35 | 1.35 mm |
| bench on z=0 | 0 | -0.75 mm | the 0.25 mm noise floor |

Three things that test taught, each of which was wrong first:

- **Two bounds are reported, not one.** A reconstruction always carries a few points a few
  millimetres off a surface — too close for any distance filter to call them outliers — and eight
  such points stretched the answer by 4.8 mm. `bounds_mm` is the honest extreme; `bounds_mm_robust`
  (0.1st to 99.9th percentile) is the size of the place, and 0.1 % rather than 0.5 % because a
  percentile cut eats into the ends of a bench by that fraction of its span.
- **The plane fit has to be refined.** RANSAC alone left the bench tipped by a fraction of a
  degree, which is invisible and worth millimetres across a bench; the smallest principal
  direction of the inliers fixes it.
- **Up is all a plane can give.** The in-plane orientation is left as the reconstruction had it;
  a scene with a roll in it comes back rolled, by design, and the tool says so rather than
  guessing a heading from a point cloud.

Still open on M1: a **real capture** — 4-6 images of something with a known-size reference in
frame — to see what the *reconstruction's* error actually is, as opposed to the tool's arithmetic.

## M2: the environment in the app (verified on a device)

Driven end to end on the development phone with a real WorldMirror reconstruction (the repo's
`Workspace` example, 4 views at 448 px), scaled by a declared 600 mm reference and meshed to
250k triangles:

    files/cad/env/workspace-env.stl     12.5 MB, delivered with adb and chowned to the app
    import_file  unit=mm name=env       -> success in 0.2 s
    bbox                                343.6 x 430.4 x 206.5 mm   (the JSON said 334.8 x 434.2 x 202.2)
    view 1080x610                       -> 0.17-0.28 s a frame, against 0.09-0.16 for a simple part

The screen shows the room: floor, two walls, the desk. The agent asked the engine for the bounding
box over the same socket in 0.02 s. That is "see it and measure it" - the geometry arrives at the
right size, in the frame the CAD engine works in, and can be queried without a render.

Five things this run found, four of which are now written into the tool or the plan:

1. **The up-detection was wrong, and only real data could show it.** The tool decided which way was
   up from the sign of the fitted normal's z in the *raw* frame - which means nothing, because a
   reconstruction's axes are arbitrary. The synthetic test passed only because its tilt happened to
   keep z up; the real cloud came back **hanging upside down below its own bench** (z from -204 to
   +1.76 mm). Up is now decided by where the points are: the bench has things standing on it, so it
   is the side the bulk of the cloud is on (z from -2.25 to +204.28 afterwards). The ground-truth
   test still passes, at the same 1.35 mm.
2. **Environments must not be dropped in `files/cad/exports`.** The app watches that directory and
   `onExport` imports every `.stl` it sees onto the plate - so an environment left there becomes
   the *printable model*. They go to `files/cad/env/` instead, which nothing watches.
3. **A differently-sized shape needs a view reset.** `import_file` does not fit the camera; the
   first render of the environment was a 6.5 KB picture of a corner of it, because the camera still
   belonged to the previous part. The app's own import path calls `reset()`; an agent driving the
   socket has to ask.
4. **Poisson makes a blobby closed shell.** Fine as context, wrong as a datum - the same conclusion
   the accuracy section reached from the other direction, now with a picture.
5. **250k triangles cost about double a frame.** Usable, and the dial is the decimation budget.

Still open on M2: a **measurement** facility. Today the agent can ask for a bounding box and pick
points; it cannot ask "how far is this face from the environment?", which is the question a jig
design actually turns on. The full-resolution cloud is kept beside the mesh (`environment.ply`) for
exactly that, and answering it wants a small command in the engine - a KD-tree built once and
queried per call - rather than the agent reading a render.

## M1 and M2 with a real capture: the projector on its tripod

Six photos of a projector on a tripod in a bedroom, taken on the user's phone (3060 x 4080), plus
three close-ups of the reference - a 20 mm printed cube standing on the projector's shelf.

**What worked, on the first real capture:**

- 4 wide views reconstruct in **26 s** at 336 px, 7827 MiB peak. 6 views OOMs, and 4 views at 448
  OOMs - the capture is **portrait**, so `target_size` lands on the short side and the frame
  carries about 1.8x the pixels of a landscape example at the same setting. Landscape examples are
  not a guide to a phone's portrait photos.
- The room comes back with the floor as its largest plane, **flat to 5.7 mm** across a nominal
  4.5 x 3.5 m - which is the accuracy figure that matters for context, and it is what the plan
  predicted from the other direction.
- Aligned, meshed to 250k triangles, delivered, imported into the CAD engine in 0.16 s, rendered,
  and queried: the whole chain on real photographs.

**What did not work: the 20 mm cube cannot set the scale.**

The room reconstructs at **~8 mm point spacing** (median nearest-neighbour over 388k points), so a
20 mm cube is two and a half samples wide in the wide views. Adding a close-up helps - the cube
reaches ~35 px - but measuring it four independent ways gives four answers:

| measurement | implied scale |
|---|---|
| top face height above the shelf, region A | 835 mm/unit |
| top face height above the shelf, region B | 477 mm/unit |
| top face side 1 | 1628 mm/unit |
| top face side 2 | 1247 mm/unit |

A factor of **3.4** between the extremes, and the two "shelf" regions differ in normal by 15
degrees: at this resolution the model's depth near a glossy dark surface is noisy by more than the
size of the thing being measured. The cube is not a bad idea; it is a bad *size* for a capture
that has to hold a whole room.

**What to do instead.** Put something with a known dimension that is *large in the frame* next to
the job: a tape measure or a metre rule laid on the floor (about 125 px at 8 mm spacing, so the
ends are good to ~1 %), or an A4 sheet (297 mm, 37 px, ~3 %). The reference wants to be roughly a
tenth of the scene's width, not a hundredth.

**And the accuracy contract holds as written**: at 5.7 mm of floor flatness and a scale good to a
few percent, the environment is context. Fits still come from calipers.

## Delivering to a phone that has no root (a gap in M2)

The development phone has `su`, so an environment reaches `files/cad/env/` with an adb push and the
pipeline looked finished. **The user's phone has neither root nor `su`** - `adb shell id` reports
`uid=2000(shell)` - so nothing outside shared storage can be written by an agent, and the app's
private directories are out of reach. Files for that phone go to the drop box,
`/sdcard/Download/dsh-agent/`, which is where its copy of the room environment now sits.

That is not enough to finish the job, and finding out why cost one experiment:

- The CAD engine **cannot read a model from shared storage**. `import_file` on
  `/sdcard/Download/dsh-agent/room-environment.stl` reports `success` with a **zero bounding box**:
  OCCT's STL reader opens nothing and raises nothing. The path looks fine and the answer looks fine,
  which is the worst shape a failure can have.
- It is not ownership. The drop-box file is `root:everybody 0660`; `chmod 644` does not even take,
  because `/sdcard` is FUSE, and the import answers the same either way. This is scoped storage:
  the app has no direct filesystem access there, which is exactly why its own importer goes through
  the system file picker.

**So the missing piece for a non-rooted phone is an in-app import**: a CAD-menu entry that opens the
system file picker and copies what the user chooses into `files/cad/env/`, the way the plate
already receives models through `files/cad/exports/`. Until that exists, delivering to the user's
phone needs them to move the file themselves, and the environment path is a development-tool path.

## Milestones

| | what | done when |
|---|---|---|
| **M0** | Box recon and the model's own examples | **done** - the table above |
| **M1** | A real capture of the rail with the marker, scaled and squared up on the harness | a 100 mm reference measures 100 mm in the delivered mesh, and the error is written down |
| **M2** | Delivered to the phone, visible in the CAD viewport, agent measures a known dimension | the agent reports a dimension it did not get from the user, and it is right within the stated tolerance |
| **M3** | The jig, end to end | a printed part that fits the rail |

M0 is one GPU session and answers the two questions that could change the plan - does the box have
the memory, and what does a capture actually cost in time.
