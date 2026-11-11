# Splat capture, object masking and mesh extraction

How a phone video becomes a Gaussian-splat model, then an object-only mesh that
Blender (the app's embedded engine, or the desktop app) can load for
post-processing.

Reference run: the **reindeer test capture** - a 3D-printed deer on a dining
table, filmed with a Galaxy Z Fold5.

## Input used in the reference run

| | |
|---|---|
| video | `FOLD5RAW_1794412817943.mp4` - 3840x2160, 60 fps, 39.7 s, portrait |
| frame extraction | ffmpeg, **2 fps** -> **79 images**, 1600x2844 |
| undistorted dataset | 1511x2686 PINHOLE, **79/79 registered** by COLMAP |
| COLMAP result | 17,907 points, 77,826 observations, mean reprojection error **1.14 px** |

So: **79 images** are used for every model and mesh below.

## Hardware

- GPU: NVIDIA RTX 3060 Ti, 8 GB VRAM (minimum for the runs here; 12-16 GB
  recommended for larger objects or 4K frames)
- CPU/RAM: 16 threads / 15 GB (mesh extraction is CPU-bound and small)
- Disk: ~40 GB free (Docker images alone are ~27 GB)
- OS: Nobara Linux 44 (Fedora family), kernel 7.2.9, NVIDIA driver 595.104.02

## Software stack

| software | version | role | licence |
|---|---|---|---|
| ffmpeg | 8.1.2 | frames from video | LGPL/GPL build |
| COLMAP | `colmap/colmap:latest` (3.12 dev, CUDA build) | SfM: features, matching, mapping, undistortion | new BSD |
| LichtFeld Studio | commit `1fbd7a2`, built in `lichtfeld-toolchain:cu128` (CUDA 12.8.61, GCC 14, CMake/Ninja, vcpkg) | 3DGS training, SOG/PLY export, format conversion | GPL-3.0 |
| Python | 3.11.17 (uv-managed venv) | object masking, depth, mesh | - |
| PyTorch | 2.5.1+cu121 | SAM2 and DA3 | BSD-3 |
| SAM2 | `facebookresearch/sam2` @ `2b90b9f`, `sam2.1_hiera_small.pt` | object tracking / masks over the video | Apache-2.0 |
| Depth Anything 3 | `ByteDance-Seed/Depth-Anything-3` @ `3d835ec`, `DA3-SMALL` | pose-conditioned dense depth | Apache-2.0 (small checkpoint) |
| Open3D | 0.20.0 | point-cloud normals, Poisson reconstruction, decimation | MIT |
| trimesh | 5.2.0 | mesh checks | MIT |
| Aholo viewer | `@manycore/aholo-viewer` 1.13.0 | in-app .sog viewer | MIT |
| Blender | app-embedded engine (or local Blender) | post-processing the mesh | GPL-2.0+ |

Docker setup: Docker CE + `nvidia-container-toolkit`
(`nvidia-ctk runtime configure --runtime=docker`); images:

```
lichtfeld-toolchain:cu128   16.9 GB   (build environment for LichtFeld)
colmap/colmap:latest         9.36 GB   (SfM, GPU SIFT)
nvidia/cuda:12.8.0-devel-ubuntu24.04   14.6 GB (base of the toolchain)
```

Notes on the toolchain build: the upstream LichtFeld Dockerfile adds
`ppa:ubuntu-toolchain-r/test`; on networks that cannot reach Launchpad this
fails. Ubuntu 24.04 (noble) already ships `gcc-14`/`g++-14`/`gfortran-14`, so
those lines were removed from `docker/Dockerfile` before building. The build is
in-container: `cmake --preset linux-release` (Ninja, release-only vcpkg
triplets) then `cmake --build --preset linux-release -j N`; the binary lands at
`build-linux-release/LichtFeld-Studio`.

## Pipeline

### 1. Frames

```bash
ffmpeg -hide_banner -loglevel error -i video.mp4 \\
  -vf "fps=2,scale=-2:2400" -q:v 2 images/%05d.jpg
```

### 2. SfM (COLMAP, GPU)

```bash
docker run --rm --gpus all -v "$WORK:/data" colmap/colmap:latest \\
  colmap feature_extractor --database_path /data/database.db \\
  --image_path /data/images --ImageReader.single_camera 1
docker run --rm --gpus all -v "$WORK:/data" colmap/colmap:latest \\
  colmap exhaustive_matcher --database_path /data/database.db
docker run --rm --gpus all -v "$WORK:/data" colmap/colmap:latest \\
  colmap mapper --database_path /data/database.db \\
  --image_path /data/images --output_path /data/sparse
# undistorted PINHOLE dataset for training
docker run --rm -v "$WORK:/data" colmap/colmap:latest \\
  colmap image_undistorter --image_path /data/images \\
  --input_path /data/sparse/0 --output_path /data/dataset_u --output_type COLMAP
```

### 3. Train the splat (LichtFeld, headless)

```bash
docker run --rm --gpus all \\
  -v /home/tomppix/LichtFeld-Studio:/home/tomppix/projects/LichtFeld-Studio \\
  -v "$WORK:/home/tomppix/lf-work/work" \\
  -e NVIDIA_DRIVER_CAPABILITIES=all -e LFS_NO_CRASH_HANDLER=1 \\
  -w /home/tomppix/projects/LichtFeld-Studio/build-linux-release \\
  lichtfeld-toolchain:cu128 ./LichtFeld-Studio \\
  --headless -d "/home/tomppix/lf-work/work/dataset_u" \\
  -o "/home/tomppix/lf-work/work/out" \\
  -r 2 --iter 15000 --strategy mcmc --export sog,ply
```

(The repo path must be mounted at its **original** path: the binary's RPATH
points into the build tree.) On the reference run: 3,000 iterations at
800x1422 took 32 s; 15,000 at 1511x2686 took ~8 min; 1M splats / SOG export a
few seconds.

### 4. Object-only reconstruction (noise removal)

1. **SAM2 masks.** Prompt the object on frame 0 (body + head + antler points,
   negative point on the table) and propagate through all frames (79 frames,
   ~15 s). Masks are cleaned to their largest connected component.
2. **DA3 depth.** Run DA3-SMALL with the COLMAP poses and intrinsics as input
   (`extrinsics`, `intrinsics` args) - 1.4 s forward, depth aligned to COLMAP
   scale. Measured against the COLMAP point cloud: median depth ratio **0.982**
   (IQR 0.947-1.006).
3. **Fuse into a dense object-only point cloud.** Unproject masked pixels with
   COLMAP intrinsics/poses, voxel-merge (0.01 units, seen in >=2 frames) ->
   **170,363 points** instead of COLMAP's 17,907 scene points.
4. Write those as `points3D.bin` in the existing COLMAP model, mask the images
   (object over black) and train the same way. This gives an object-only splat:
   1M splats, 30k-iteration run used for the deliverable mesh.

### 5. Mesh extraction (splat -> STL/OBJ)

`splat_to_mesh` script (on the box at `/home/tomppix/lf-work/object/mesh_from_splat.py`):

- read the splat PLY (positions, `f_dc` colours, opacity logits, scale);
- keep the strongest 99 % by opacity, drop the largest 0.5 % scales and the
  0.2-99.8 percentile outliers per axis;
- voxel-downsample to ~220k points, estimate and orient normals (Open3D);
- **Poisson surface reconstruction** (depth 10, scale 1.1), trim the lowest 6 %
  density; Taubin smooth (8 iterations); decimate to 180k triangles;
- export `reindeer-splat.stl`, `.obj`, `.ply` plus `mesh-info.json`.

Result on the 30k / 1M-splat object model: **97,336 vertices / 180,000
triangles**, extent 0.98 x 1.81 x 1.09 (COLMAP units, no metric scale), not
watertight (expected for Poisson after density trimming). The 3k/10k models
give the same topology at clearly lower surface detail.

Delivered files (also pushed to the phone's `/sdcard/Download/dsh-agent/`):

| file | content |
|---|---|
| `reindeer-splat.stl` | mesh geometry for Blender |
| `reindeer-splat.obj` | mesh with vertex colours |
| `reindeer-splat.ply` | same mesh, with vertex colours |
| `reindeer-object-hq.sog` | the 30k splat for the in-app viewer |
| `mesh-info.json` | counts, extent, watertight flags |

## Outputs

| artefact | where (box) |
|---|---|
| dataset / undistorted images | `/home/tomppix/lf-work/dataset_u` |
| baseline splat | `/home/tomppix/lf-work/dataset_u/output` |
| object-only dataset | `/home/tomppix/lf-work/object/dataset-dense` |
| object-only 30k model | `/home/tomppix/lf-work/object/out7` |
| mesh (STL/OBJ/PLY) | `/home/tomppix/lf-work/object/mesh` |
| viewer copies | `.sog`, imported into the phone's Splat viewer |

## Blender notes

- Import `reindeer-splat.stl` (or `.obj` for vertex colours). Units are
  **arbitrary** (COLMAP units): scale to the intended physical size; there is no
  metric scale in this capture.
- The mesh is **not watertight**; use Remesh (Voxel or Quadriflow) or Sculpt >
  Smooth, then a Remesh modifier to fuse antlers and close holes. A Shrinkwrap
  back onto the splat's point cloud (exported as PLY) is a good first pass.
- Thin features (antler tips) are the weakest part - they come from the splat's
  density and the 2 fps/decimated capture; smoothing them too far loses them.

## Limitations

- Splat geometry is approximate; this is a visual reconstruction, not a
  metrology-grade scan. Expect several tenths of a millimetre of noise on a
  ~10 cm object, and more at thin features.
- No metric scale (COLMAP is scale-free). For real dimensions, include a
  ruler/Aruco marker in the capture or use the ARCore pose scale.
- Monocular DA3 depth improves the initial point cloud but does not replace the
  image-based optimisation.
- 8 GB VRAM limits the object-only model to roughly 1M splats at 1511x2686;
  more needs a bigger GPU or smaller crops.

## Licences of the deliverables

The mesh itself is the user's capture. The software above keeps its own
licence: LichtFeld Studio is GPL-3.0 (run as a separate service, not bundled
into the app), SAM2 and DA3-small are Apache-2.0, Aholo viewer is MIT,
Open3D is MIT, COLMAP is new BSD.
