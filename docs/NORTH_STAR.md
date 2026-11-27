# North Star — Handheld Hybrid Scanner

_Saved 2026-11-21; revised 2026-11-22 after the DA3 single-frame result._

## The decision that changed the plan

The geometry product is **not** a photoreal Gaussian splat. It is:

- a **coarse depth surface from one photo at a time** (Depth Anything 3 on the NPU),
- refined by the **laser**, which is the metric geometry authority,
- stitched across views only when there is real overlap.

Multi-frame DA3 fusion with one global/per-frame depth scale was tried and rejected:
the camera was mostly tilting, so the relative depth scale varied per view and the
frames turned into differently sized copies. The useful result is the
**single-frame depth map**; the later views add coverage, not a second global
reconstruction.

Cross3DGS is integrated and can run 4,000 iterations end-to-end on the phone, but
its output on the current poses/checkpoint is not usable (PSNR ~7 dB, collapsed
splat). It is **not on the geometry critical path**. Keep the integration; do not
invest more in splat training until the depth + laser geometry works.

## Where we are right now (2026-11-22)

**Position: Phase 2c — single-frame DA3 coarse surface + laser refinement.**

### Done

- [x] **Phase 1 — one capture session.** `TrackerRecorder` writes `frames.csv`,
      `imu.csv`, `laser/fNNNNN.f32` and the keyframes into one EasyScanner session;
      `HybridSession` / `EasyScanImport` import it into a splat scan with
      `hybrid/hybrid.json`.
- [x] **DA3 on the phone.** The old standalone `splatproto` implementation and
      its 73 MB HTP V73 model were recovered and ported into the current app:
      `da3_depth_fp16.bin`, `NpuMasker` QNN staging, `Da3Dense`.
- [x] **Single-frame depth works.** `DA3 1 frame` produces a coherent depth
      surface (67,081 points on scan `20261122-092927`), with the laser line
      visible on it. This is the desired primitive.
- [x] **Laser points are metric 3D.** `laser/*.f32` is camera-frame millimetres
      from laser-plane triangulation: it supplies x, y **and z**, not just an
      image feature.
- [x] **Cross3DGS integration.** Native Vulkan trainer, JNI bridge, COLMAP
      binary conversion, laser checkpoint and training callback are in place.
      Not currently producing usable output.

### Open

- [ ] **Single-frame depth → coarse mesh.** Triangulate the 518×518 depth grid
      with the frame intrinsics, export/render a mesh (not only a point cloud).
- [ ] **Laser refinement in the same frame.** Project the laser points onto the
      single-frame surface and locally displace/add detail; the laser is already
      in the same camera frame.
- [ ] **Live single-frame preview.** Run DA3 every N frames, place the patch with
      the recorded VIO pose, update the live view and accumulate laser lines. The
      first live view is a throwaway GPU point-splat display, not a mesh.
- [ ] **Patch stitching.** Register overlapping single-frame patches using:
      markers with known geometry (full 6DoF ground truth), VIO initial pose,
      laser ICP refinement, diopter as weak validation.
- [ ] **Moving-phone test.** The current tests are tilt-only. A moving capture
      is required before locking the patch-stitching thresholds.
- [ ] **Phase 0 validation.** The trusted calibration profile still has to be
      loaded and validated on a real object.

## Geometry model

### Single-frame patch

One DA3 depth map is a local surface in camera coordinates:

1. DA3 predicts a relative depth map for one image.
2. Scale it with the laser points from that same frame.
3. Back-project with the frame intrinsics.
4. Triangulate the depth grid into a coarse mesh.
5. The laser line refines the surface where it crosses it.

No pose or multi-frame fusion is needed for one patch.

### Live display — throwaway point splats

The live viewfinder is deliberately not a mesh. It renders the latest DA3 depth
sample plus the laser as **screen-space point sprites**:

- DA3 depth grid is sampled into camera-frame points; the current VIO pose puts
  them in world space.
- each point is a depth-scaled round sprite, coloured from the image:
  `sprite_px = spacing_m × focal_px / depth_m`, clamped.
- laser points are drawn in a distinct colour, slightly larger, updated every
  frame.
- the depth buffer is replaced when a new DA3 patch is ready; it is not kept for
  export.

This is display only. It has no topology, no export, and it is not the final
surface. Its job is to show coverage and where the laser is hitting while the
map is being captured. The same buffers can later seed the real TSDF/mesh path.

### VIO is the primary patch transform

OpenVINS already tracks image features and fuses them with the IMU into a
camera pose. That pose is the stitched transform we need; we do not need a
separate feature-matching stage or a full COLMAP reconstruction for live work.

A keyframe records `vioPose` in `scan.json` (world -> camera, row-major) only
when OpenVINS has a pose for that image timestamp. Live Mesh uses the same rule:
submit the current image once the IMU covers its offset-adjusted timestamp,
wait at most 8 ms for late samples, and do not place depth or laser data using
a pose from another frame. Board pose remains independent. `VioJni` logs
skipped unbracketed camera updates with the reason. When more than 20 tracks are shared by the current image and the filter’s previous image, motion at or above the visual stillness threshold vetoes a zero-velocity update even if the IMU alone looks still.

DA3 should therefore:

1. prefer the recorded VIO pose,
2. fall back to COLMAP `images.txt` only when no VIO pose exists,
3. transform each single-frame depth patch into the VIO world with that pose,
4. scale the patch with its laser line,
5. register overlapping patches against the existing map.

The raw OpenVINS features are not exposed to Java as landmarks; the pose is the
useful output and it already incorporates those tracks. Exposing the landmarks
is only needed if VIO drift has to be corrected directly, and markers/laser ICP
are the ground-truth correction in this design.

### Patch stitching

A new depth patch is captured when the view leaves the current coverage:

- translation > roughly `0.05 × median_laser_depth`, or
- rotation > roughly 10–15°, or
- laser coverage of the current patch drops below about 70%.

Then:

```text
new patch
  → initial pose from VIO
  → refine against overlapping markers/laser
  → if overlap and residual are good: fuse into the map
  → else (Live Mesh): keep the last registered mesh; retry from another viewpoint
  → else (offline reconstruction): keep a separate submap
```

Live Mesh shows and exports only registered patches. A rejection does not replace
the active TSDF, advance the patch count, or recenter the viewer. Move back toward
overlap or use **+ Patch** to retry manually. The log names the scale source as
`laser`, `overlap`, or `focus_distance`.

**Never force all frames into one global scale.** That is what produced the
stretched copies.

### Roles of each source

| Source | Contribution | Depth role |
|---|---|---|
| Laser triangulation | metric camera-frame 3D points | **direct x/y/z**; scale authority |
| Markers with known geometry | full 6-DoF pose via PnP | **z included**, if marker geometry is known |
| VIO / IMU | relative 6-DoF motion | relative z; initial guess, drifts |
| DA3 | per-pixel relative depth | coarse z after laser scaling |
| Diopter / focus distance | scalar focus-plane distance | weak prior, validation, keyframe trigger |

Markers are not merely x/y. A fiducial board with known geometry gives the full
camera pose including z. A single line of laser points gives metric 3D because
the laser plane is calibrated. Diopter alone cannot supply 6-DoF and must not be
the primary stitch source.

### Motion mode

Compute from the captured VIO poses:

```text
translationSpan = robust max pairwise camera-centre distance
medianDepth     = median laser depth in the camera frame
parallax        = translationSpan / medianDepth
```

- `parallax < ~0.04` → rotation-only / tilting: one patch and a global laser scale.
- `parallax >= ~0.04` → moving: new patches with per-patch laser scale, stitched by
  markers + VIO + laser ICP.

Record the measured values and the chosen mode in `report.json`.

## Recommended sequence

### Phase 2c — single-frame geometry (current)

- single-frame DA3 depth, laser-scaled
- VIO pose as the patch transform; COLMAP only as fallback
- coarse mesh from the depth grid
- laser overlay/refinement in the same camera frame
- offline export and viewer test

### Phase 2d — live throwaway viewfinder (current)

- GPU point-splat preview over the camera
- patches placed by VIO pose, not by waiting for SfM
- one DA3 patch kept and reprojected while tilting
- laser line accumulated/updated per frame
- no mesh, no export; dispose freely

### Phase 3 — patch map

- keyframe selection by coverage
- marker + VIO + laser registration
- local submaps, overlap-based fusion
- moving-phone test, threshold tuning

### Phase 4 — real live mesh

- persistent QNN DA3 session (not `qnn-net-run` per batch)
- replace the throwaway splats with GPU TSDF + marching-cubes mesh
- laser detail accumulation in the high-resolution TSDF
- UI shows the mode: tilting / moving, patch count, coverage

### Phase 5 — appearance and product

- optional Gaussian splat / textured mesh from the same session, after geometry
- calibration validation, guided flow, export
- performance and thermal limits

## What to deliberately not do

- Do not return to per-frame global DA3 scaling across all frames.
- Do not train Cross3DGS on the current poses/checkpoint until the geometry path
  proves the frames are consistent.
- Do not use diopter alone for 6-DoF stitching.
- Do not force low-overlap patches into one map; keep submaps and stitch later.
- Do not cap or subsample laser data to make the mesh easier; the laser is the
  accuracy authority.
- Do not claim the moving-phone path works until a moving capture has been tested.

## Inventory — do not repeat this work

- EasyScanner hybrid recorder and session schema.
- `HybridSession` / `EasyScanImport` import path.
- Kalibr camera calibration port and camchain YAML writer.
- DA3 HTP model recovery and `Da3Dense` NPU path.
- Cross3DGS native/JNI/COLMAP integration.
- Laser `LASER_CAM` layer and `laser/*.f32`.

## The single next step

**Switch DA3 to the recorded VIO pose and verify single-frame patch placement.**

Then add the throwaway GPU point-splat live viewfinder over the camera preview.
It displays the latest DA3 single-frame depth points and the laser overlay while
the scan is being mapped; it is not a mesh and is not exported. Once it is stable,
move the same data into the real GPU TSDF/mesh path.
