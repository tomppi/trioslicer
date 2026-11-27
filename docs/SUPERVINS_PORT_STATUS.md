# SuperVINS port status

_Updated 2026-11-26 — EasyScanner inside TrioSlicer switched from OpenVINS MSCKF to SuperVINS._

## Implemented in `:easyscanapp` / `:app`

### Backend (SuperVINS / VINS-Fusion Ceres sliding-window estimator)
- Replaced `libopenvins_jni.so` (OpenVINS MSCKF + Boost) with `libsupervins_jni.so` (`supervins_core` + Ceres + Eigen + OpenCV + DBoW3).
- Loads the phone's existing calibrated `files/openvins-config/kalibr_imucam_chain.yaml` (`T_cam_imu` inverted to `body_T_cam0 = T_imu_cam`, calibrated `radtan` intrinsics/distortion, `timeshift_cam_imu`) and `kalibr_imu_chain.yaml` (continuous Allan noise converted to VINS-Fusion `IntegrationBase` discrete parameters), plus app-owned `supervins_config.yaml`.
- Online time-offset optimization (`estimate_td: 1`) is now backed by real normalized feature velocities `(vx, vy)` fed into `ProjectionTwoFrameOneCamFactor`.
- Separate IMU (`imu_mtx`) and frame (`frame_mtx`) locking so 480 Hz IMU ingestion never stalls behind Ceres optimization; frames wait up to 18 ms for IMU bracketing of `ts + td` without queuing stale backlogs.
- Fixed upstream `Estimator` issues: `clearParameters()` on restart, `all_image_frame` `pre_integration` cleanup in `clearState()` and `slideWindow()`, `sum_g` zero-init in `initialStructure()`, empty-buffer guards in `getIMUInterval()`, NaN state guard in `failureDetection()`, and `predictPtsInNextFrame()` output wired back to the tracker.

### Front-end (`SuperVinsFrontend`)
- SuperPoint NPU (`superpoint_single.tflite`, 250 NMS-4 candidate pool) filtered by `min_dist` (default 20 px) into LightGlue NPU (`lightglue.tflite`, 100-keypoint budget).
- LightGlue VIO matcher enabled by default (threshold 0.15; loop matcher stays 0.20); KLT fallback off by default (`klt_fallback.txt`), used automatically only if QNN HTP LightGlue is unavailable or explicitly turned off in Settings.
- SuperVINS 3D landmark prediction gating from `Estimator::predictPtsInNextFrame()`.
- Seeded PyrLK sub-pixel refinement (preserving LightGlue matches cleanly if PyrLK rejects) + adaptive F-RANSAC (3.0 px base, 2.5–4.0 px adaptive).
- Laser-stripe band exclusion and calibrated Brown-Conrady `radtan` undistortion.
- Settings -> VIO tuning (`flood.txt`) aligned to SuperVINS defaults (250 pool / 20 px spacing / 100 tracks / 0.15 threshold / `reacq=0`).

### Live-mesh & DriftFix loop-closure fixes
- Fixed double depth scaling in `AdaptiveDepthMesh.metricPointAt()` (`baseDepthM` is already scaled in metres).
- Fixed 640×480 SuperPoint keypoint → full-resolution `DepthCamera` pixel scaling in `PatchMap.visualPose()`.
- Fixed stale-frame feature binding in `LiveMeshController`: features are now snapshotted at patch capture time alongside `rgb518` and `rawPose`.
- Fixed `PatchMap.optimizeAndRebuild()` to update `p.laserFrames` poses and `submapToRawVio` after pose-graph solve, and switched coverage/laser queries to use corrected `p.pose`.
- Fixed `DriftFix.pnpInliers()` projection matrix to use `W^T` (`world -> camera`) instead of `W` (`camera -> world`).
