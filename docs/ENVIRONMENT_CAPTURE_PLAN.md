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

### 2. Accuracy, which will not be what a jig needs

A jig that clips onto a rail needs tenths of a millimetre where it touches. A phone video
reconstruction will be around a millimetre at best, and worse on shiny, dark or repetitive
surfaces - which is what rails, extrusions and dowel holes are. This is the fact that should shape
the whole design:

- **The environment is context, not a datum.** It answers "where is the rail, which way does it
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

1. **Licence.** HunyuanWorld-Mirror's `Territory` is "the worldwide territory, excluding the
   territory of the European Union, United Kingdom and South Korea" - a grant "for the Territory
   only". The same clause is in Hunyuan3D-2, which the existing image-to-3D pipeline already runs,
   so this is not new exposure, but it does apply. Options, all defensible:
   - proceed as-is, unchanged from today's practice;
   - a photogrammetry route (COLMAP + OpenMVS: BSD + AGPL) with the same scale-marker step;
   - a metric-geometry route (monocular metric depth + fusion) - EU-safe licences exist and I can
     shortlist and check them if you want this path.
2. **Scale reference**: printed marker (recommended) or ruler.
3. **Capture**: video (`fps=1`, recommended - one pass around the job) or stills.
4. **The accuracy contract**: confirm the split above, or decide to invest in close-range captures
   for tighter fits.
5. **Wake the box** for M0: the wake is the reliable half of that skill, the resume is not.

## Milestones

| | what | done when |
|---|---|---|
| **M0** | Box recon: wake it, `nvidia-smi`, clone, new venv, run the repo's own example | VRAM, runtime and output files are known numbers, not guesses |
| **M1** | A real capture of the rail with the marker, scaled and squared up on the harness | a 100 mm reference measures 100 mm in the delivered mesh, and the error is written down |
| **M2** | Delivered to the phone, visible in the CAD viewport, agent measures a known dimension | the agent reports a dimension it did not get from the user, and it is right within the stated tolerance |
| **M3** | The jig, end to end | a printed part that fits the rail |

M0 is one GPU session and answers the two questions that could change the plan - does the box have
the memory, and what does a capture actually cost in time.
