# SuperVINS feature scope

_Compared against the local SuperVINS checkout (2026-11-25) and the current EasyScanner tree._

## Executive summary

SuperVINS is VINS-Fusion with a learned front-end:

~~~text
SuperPoint detector/descriptor
-> LightGlue matcher
-> F-RANSAC
-> VINS-Fusion sliding-window optimizer
-> DBoW3 + SuperPoint loop detection
-> LightGlue + PnP loop verification
-> g2o 4-DoF pose graph
~~~

EasyScanner is OpenVINS MSCKF with a TFLite/QNN front-end and an on-device TSDF live mesh. The useful parts of SuperVINS are front-end robustness, loop verification, and pose-graph correction. The VINS-Fusion backend is not a drop-in and is not required for the live-mesh problem.

## Feature matrix

| Area | SuperVINS component | EasyScanner status | Decision |
|---|---|---|---|
| Detector | SuperPoint, 256-D descriptors | TFLite 100-keypoint QNN | Have |
| Matcher | LightGlue ONNX, dynamic keypoints | TFLite 100-keypoint QNN | Have |
| Matching robustness | F-RANSAC, configurable 3 px | fixed 2 px normalized F-RANSAC | Want adaptive threshold |
| Match gate | VIO-predicted 2-D positions | rotation + velocity + SLAM-depth gates | Partial |
| Track fallback | none (pure learned matcher) | optional KLT completion (toggle) | Keep optional |
| Feature count | 150 max, 20 px spacing | 100 cap, 10 px spacing | Tune, do not copy |
| Preprocessing | 512 long side, grayscale | 640x480 RGB | Keep RGB (model + app) |
| Feature mask | image mask/fisheye mask | laser-stripe mask | Have |
| Front-end rate | 10 Hz tracking | 30 Hz | Keep 30 Hz for live |
| Predicted features | VINS feature-manager depth + next pose | partial (rotation/velocity/SLAM depth) | Want full feature depth only if needed |
| Initialization | SFM + visual-inertial alignment | OpenVINS static/dynamic init + timing gate | Keep OpenVINS |
| Backend | VINS-Fusion sliding window, Ceres | OpenVINS MSCKF EKF | Do not port |
| Feature depth manager | inverse-depth feature manager | only SLAM landmarks | Do not port |
| Keyframes/marginalization | VINS keyframe + marginalization | MSCKF clones | Do not port |
| Online time offset | estimate_td: 1 in optimization | flag exists but no visual Jacobian; fixed 14 ms | Do not rely on it |
| Online extrinsics | estimate_extrinsic: 1 | calibrated fixed extrinsic | Do not need |
| ZUPT | none | OpenVINS ZUPT (off) | Not needed |
| Failure detection | present but disabled | reinit paths | Do not copy |
| Loop detection | DBoW3 + SuperPoint | DBoW3 integrated in AR DriftFix | Have, extend to live mesh |
| Loop vocabulary | gitignored, trained offline | full-data 9,985-word DBoW3 vocabulary | Have |
| Loop verification | SuperPoint + LightGlue + PnP RANSAC | LightGlue + PnP in DriftFix | Have, extend to live mesh |
| Pose graph | g2o 4-DoF | Java SE(3) graph in DriftFix | Have |
| Loop-corrected trajectory | SuperVINS 2.0 dense trajectory | corrected PLY + splat | Want equivalent |
| Live mesh correction | not a SuperVINS feature | PatchMap TSDF retention + correction API | Build on it |
| Multi-threading | tracker + estimator | camera/NPU/IMU/rebuild threads | Have |
| Camera models | camodocal MEI/pinhole/equidistant | app calibration (pinhole/radtan) | Do not need |
| Stereo/multi-camera | supported | mono | Do not need |
| ROS/RViz/launch | required | Android app | Do not need |
| Docker/CUDA/ONNX Runtime | desktop dev | TFLite/QNN | Do not need |
| Ceres/g2o host | backend/pose graph | Java/native app code | Do not need |
| Datasets/EVO | EuRoC/TUM/KITTI | phone sessions | Optional offline only |
| 2.0 memory fixes | long-running loop | app lifetime | Concept only |

## What we want

### P0 - live mesh and VIO correctness

1. Patch-level visual pose
- retain SuperPoint keypoints/descriptors per patch
- match new patch to previous patches with LightGlue
- PnP RANSAC gives metric relative pose
- use it as the initial pose for laser ICP

2. Patch pose graph
- edges: odometry + visual links + laser-ICP corrections
- correct old patch poses when a loop returns

3. Rebuild the live TSDF from corrected poses
- re-integrate each patch DA3 depth
- re-integrate every retained laser frame
- rebuild the mesh, not just an exported PLY

4. Stable VIO
- fix the track starvation seen in the logs (tracked often 1-8 frames)
- keep camera/IMU timing deterministic
- do not run live-mesh matcher work on the VIO frame path

### P1 - front-end robustness

5. Adaptive F-RANSAC threshold
- SuperVINS uses a configurable 3 px threshold; our fixed 2 px rejects too little or too much depending on scene.

6. Feature spacing and count tuning
- SuperVINS uses 150 features and 20 px spacing. We use 100/10. Tune with the same evaluation, do not blindly copy.

7. Prediction-gated matching
- we have rotation/velocity/SLAM-depth gates; SuperVINS predicts feature positions from the feature manager and predicted pose.
- add depth prediction only where it is available; do not build a VINS feature manager just for this.

8. Loop-corrected trajectory
- a SuperVINS 2.0 result: dense loop-corrected trajectory as a first-class output.

### P2 - only if evidence demands it

9. Proper online time offset
- the current OpenVINS flag is not observable from the MSCKF visual Jacobians.
- implement it properly or keep the fixed calibrated 14 ms.
- do not enable a fake automatic estimator.

10. Feature depth manager
- only needed if we move from MSCKF to a sliding-window backend.
- not needed for the current live mesh.

## What we do not need

- ROS1, catkin, RViz, launch files
- Docker/CUDA/ONNX Runtime desktop stack
- VINS-Fusion sliding-window Ceres backend
- VINS feature manager inverse-depth representation
- VINS keyframe/marginalization machinery
- g2o host library (Java SE(3) graph already fits)
- camodocal camera models and calibration tools
- stereo/multi-camera paths
- 10 Hz tracking rate
- SuperVINS 512-grayscale preprocessing
- online extrinsic calibration
- failure detection (disabled upstream)
- dataset loaders and EVO evaluation in the product

## Recommended order

1. Fix VIO track stability and timing.
2. Add SuperPoint/LightGlue patch features + PnP, then initial pose.
3. Add patch pose graph and corrected-pose TSDF rebuild.
4. Add DBoW3 loop detection to the live patch graph.
5. Add loop-corrected mesh/trajectory output.
6. Tune F-RANSAC/feature count against measured patch placement.
7. Consider real online time offset only if timestamp evidence requires it.