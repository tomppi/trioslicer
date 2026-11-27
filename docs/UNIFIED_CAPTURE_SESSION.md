# Unified hybrid capture session — format

Status: Phase 1 design, 2026-11-21.

This freezes the on-disk contract between EasyScan (capture), the calibration app,
and splatproto (consumer). It extends the existing EasyScanner photo session; it
does **not** introduce a second capture flow.

## What one session directory contains

```
<session>/
  session.json          existing orbiter.native.photo_session.v1
  photos.jsonl          existing: one row per still
  photos/left_0001.jpg  existing: posed keyframes
  frames.csv            analysis frames: board pose, VIO pose, laser counts
  imu.csv               raw accel/gyro samples
  corners.csv           board corner observations (development)
  laser/f00042.f32      camera-frame laser points for analysis frame 42
  laser.ply             optional: board-frame laser cloud written on finish
  scan.ply              optional: board-frame scan cloud written on finish
  hybrid.json           NEW: manifest that binds the streams together
```

The stream files already exist. `hybrid.json` is the only new required file; it
must be written when the session opens (after the photo session exists) and never
rewritten with different geometry.

## hybrid.json

```json
{
  "schema": "easyscanner.hybrid_session.v1",
  "session_id": "20261121-173000",
  "created_utc": "2026-11-21T15:30:00Z",
  "app": { "easyscan": "<versionName>", "core": "<core build>" },
  "frame": {
    "name": "board",
    "origin": "board centre",
    "axes": "+x along board width, +y up the printed face, +z out of the printed face",
    "units": "mm"
  },
  "analysis": {
    "frames": "frames.csv",
    "imu": "imu.csv",
    "corners": "corners.csv",
    "width": 640,
    "height": 480,
    "timebase": "sensor_boottime_ns",
    "board_pose_column": "pose16",
    "vio_pose_column": "vio_pose16",
    "board_pose_convention": "board->camera (camera_point = R * board_point + t)",
    "vio_pose_convention": "world->camera (row-major, metres)"
  },
  "keyframes": {
    "jsonl": "photos.jsonl",
    "dir": "photos",
    "pose_frame": "board",
    "pose_convention": "board->camera",
    "timestamp_field": "sensor_timestamp_ns",
    "laser_field": "laser_on"
  },
  "laser": {
    "dir": "laser",
    "filename": "f%05d.f32",
    "index": "frames.csv frame column",
    "layout": "float32 x,y,z per point",
    "frame": "analysis camera",
    "units": "mm"
  },
  "calibration": {
    "camera_model": "camera_model.json or live_intrinsics.json",
    "imucam_chain": "kalibr_imucam_chain.yaml",
    "laser_plane": "laser_plane.json",
    "profile_id": "<sha256 or document timestamp>"
  },
  "mapping": {
    "still_to_analysis": "nearest frames.csv row by |sensor_timestamp_ns - ts_ns| <= 50 ms",
    "analysis_to_laser": "laser/f{frame:05d}.f32; zero points means no laser in that frame",
    "laser_to_board": "apply the same row's pose16: p_board = R^T (p_cam - t); do not use vio_pose16 for this",
    "laser_to_vio": "apply inverted vio_pose16 only when the session is marker-free (Phase 4)"
  }
}
```

## Rules

1. **One capture flow.** A hybrid session is opened by EasyScan. splatproto consumes
   it; it does not create a second session for the same capture.
2. **Timestamps are the join key.** Every still carries `sensor_timestamp_ns`;
   every analysis row carries `ts_ns`. The importer must record the chosen row and
   the time gap, not silently drop a still.
3. **Pose frames are not interchangeable.** `pose16` is board->camera;
   `vio_pose16` is VIO-world->camera. Laser points are camera-frame. A consumer
   must name which world frame its cloud is in.
4. **Board frame is the current authority.** Phase 1/2 laser clouds are written in
   the board frame using `pose16`. VIO is carried alongside, and becomes the
   authority only after the marker-free phase.
5. **Calibration is part of the session.** The manifest records which
   `kalibr_imucam_chain.yaml`/intrinsics produced the VIO poses and the profile id,
   so a later consumer never has to guess.
6. **No silent resampling.** If a still has no analysis row within the tolerance, the
   importer keeps the still without laser and reports the count/max gap. The same
   applies to missing laser files and empty laser frames.

## First consumer: EasyScanImport

Phase 1 changes to `EasyScanImport`:

- read `hybrid.json`, then `frames.csv` and `photos.jsonl`;
- for each still, pick the nearest analysis row by timestamp;
- copy/link the chosen frame's `laser/fNNNNN.f32` into the splat scan (or store the
  path in `scan.json` for later);
- keep both poses: the board pose for the laser cloud, the VIO pose as an optional
  pose lane;
- write the chosen frame index, time gap and laser point count per image into
  `scan.json` (or a `hybrid.json` copy in the scan) so reconstruction can use it.

Acceptance for the first consumer: a real session imports with every still mapped
or explicitly counted as unmapped, and the laser points reproject onto the laser
stripe in the still when transformed with `pose16`.

## Open verification points

- Units of `laser_cam` (mm assumed from the engine's board/PLY units) — confirm on
  one capture against a known board distance.
- Whether `frames.csv` `pose16` and `photos.jsonl` `pose` are exactly the same
  board->camera convention at the still; both come from the same core, but verify
  before fusing.
- `laser.ply`/ `scan.ply` are optional outputs; the authoritative Phase 1 source
  is the per-frame `laser/*.f32`, so consumers must not require the PLY.
