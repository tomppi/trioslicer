# Camera–IMU calibration app (`:imucalib`)

A separate Android app for calibrating the camera–IMU rig used by TrioSlicer's
marker-free scanner. It is deliberately **outside EasyScan**: scanning stays in
EasyScan, calibration has its own app, its own data and its own tests.

## Modules

| module | what it is |
|---|---|
| `:imucalibcore` | the algorithms + tests (Kotlin, JVM tests; no Android dependencies) |
| `:imucalib` | the app: records a session, solves on the phone, writes the Kalibr YAML + report |

The temporary Camera–IMU step that was briefly added to EasyScan has been removed;
EasyScan no longer records or solves calibration.

## Algorithm (port of Kalibr's formulation, not of its ROS toolbox)

Continuous-time batch calibration. The camera trajectory is a uniform cubic
B-spline of a 6-vector, `[translation, rotation-vector]`, with
`R(t) = exp(theta(t))`:

- body angular velocity `omega = J_r(theta) * theta_dot` (J_r = SO(3) right Jacobian);
- world linear acceleration = second time derivative of the translation curve.

Residuals, all on one clock (`t_spline = t_imu - td`):

1. **Board pose** from the engine: rotation error in the camera frame and
   translation error rotated into the camera frame, weighted by the board
   solver sigmas.
2. **Gyroscope**: measured vs `R_imu<-cam * omega_body + bg`.
3. **Accelerometer**: specific force measured at the IMU origin,
   `R_imu<-world * (a_imu_point - g_world) + ba`, including the lever-arm terms
   `alpha x r + omega x (omega x r)`; gravity direction is a free unit vector in
   the board frame.
4. **Lever arm prior** (weak) — the lever arm is weakly observable from board-pose
   priors, so it is fixed at zero by default (`estimateLeverArm = false`).

Unknowns: spline coefficients + `T_cam_imu` (rotation, translation), time offset,
gyro/accel biases, gravity direction (16 calibration parameters).

Optimizer: Levenberg–Marquardt. The spline block of the normal equations is kept
banded (a cubic residual touches four knots) and eliminated with a Schur
complement for the 16-parameter calibration block — the same structure Kalibr
delegates to Ceres/SuiteSparse. Local Jacobians are numeric over each residual's
parameter window. IMU per-sample sigmas are estimated from the quietest window
(first-difference noise), with floors so a noise-free synthetic dataset is not
over-weighted.

Knot spacing default is **0.2 s**, coarser than Kalibr's pixel-observation
default: with board-pose priors (6 numbers per frame instead of many corner
pixels) denser knots fit the measurement noise and destroy the IMU-derived
acceleration signal.

## Tests

```
./gradlew :imucalibcore:testDebugUnitTest
```

Covers: SO(3) exp/log/right-Jacobian, cubic B-spline basis and derivatives,
SE(3) spline angular velocity against the numeric rotation derivative, banded
Cholesky vs dense Gaussian elimination, Horn rotation fit, Kalibr YAML writer,
CSV session parser round-trip, analytic gradient vs finite differences, and
synthetic ground-truth recovery (rotation, time offset, biases, gravity;
noise-free and with sensor noise). There is also an integration test for a real
recorded session (`IMU_CALIB_SESSION=/path`) and one for a synthetic Aprilgrid
session.

Status at the time of writing: **30 tests, 0 failures** (29 always-on unit tests + the
real-session integration test enabled with IMU_CALIB_SESSION).

A recorder-format test covers the layouts the scanner actually writes: the pose row
shifted one column (observed on device), millimetre poses, and asynchronous gyro/accel
rows that the session parser must merge onto one clock.

## Host-Kalibr validation

A synthetic Aprilgrid dataset was generated under
`/root/kalibr-work/synthetic` using **Kalibr's own target layout** (raster of
`kalibr_create_target_pdf`), with known ground truth. Both Kalibr (built in an
Ubuntu 20.04 / ROS Noetic chroot at `/root/kalibr-rootfs`, native x86 — no
emulation) and this port calibrated the identical bag/session.

| quantity | truth | Kalibr | this port |
|---|---|---|---|
| camera–IMU rotation error | – | 0.226° | **0.09°** |
| time offset | 13.0 ms | 4.85 ms (error 8.2 ms) | **13.9 ms (error 0.9 ms)** |
| lever arm | (18, −12, 4) mm | (30.8, −12.8, −5.6) mm | fixed at 0 (optional estimation is weaker) |
| intrinsics (fx) | 700 | 698.5 | uses the engine's calibrated model |
| reprojection | – | 0.238 px | board-pose residual 0.081° / 0.78 mm |

The port recovers the dominant valid quantity (camera–IMU rotation) to a few
tenths of a degree and the synthetic time offset to ~1 ms, and matches the
physical convention Kalibr uses (`T_cam_imu` maps IMU → camera; OpenVINS inverts
it to `T_CtoI`).

A real recorded session (old EasyScan tracker session, 1200 frames of board
poses + IMU) is also parsed and solved by the integration test: gyro residual
0.058 rad/s, accelerometer residual 0.39 m/s², time offset 11.5 ms — i.e. the
phone data path is exercised end to end.

### Phone-format end-to-end check

The same synthetic truth was re-encoded exactly the way the scanner records it:
JPEG frames, the recorder's CSV layout with the pose shifted into the vio_pose16
column (as seen on the device), metre/millimetre auto-detection, and asynchronous
gyro/accel rows. Then:

1. our parser/solver on that session: rotation error 0.08 degrees, time-offset
   error 2.5 ms (the async interpolation costs about 1.5 ms versus the clean
   session), gyro residual 0.005 rad/s, accelerometer residual 0.030 m/s2;
2. session_to_bag.py to ROS 1 bag to Kalibr: rotation error 0.278 degrees, time
   offset 5.15 ms, intrinsics fx 700.7 (truth 700), reprojection 0.239 px.

This exercises the exact data path a phone recording takes from the recorder's
files through the converter into the host Kalibr reference.

## A4 Aprilgrid target

`/root/kalibr-work/target/` and the phone folder
`/sdcard/Download/dsh-agent/imu-calib-target/`:

- `aprilgrid_a4_6x6_22mm_kalibr.pdf` — **Kalibr's own Aprilgrid 36h11 layout**
  (2-cell black border, symmetric corner squares), 6×6, tagSize 22.0 mm,
  tagSpacing 0.3. Page is 178.9 mm square; print on A4 at 100 %, measure one
  tag = 22.0 mm.
- `aprilgrid_a4_6x6_22mm_kalibr.yaml` — matching Kalibr target YAML.
- `README.txt` — print/measure instructions.

(An earlier OpenCV-marker target was discarded: OpenCV's AprilTag rendering uses
a different border convention and Kalibr cannot detect it.)

## App usage

1. Install `imucalib-debug.apk` (separate package `com.rawmechatronics.imucalib`).
2. Point at the board, press **Record 40 s**: hold still, then yaw → pitch → roll
   with the whole board in view (banner guides the phases).
3. **Solve latest recording** runs the estimator on the phone (progress shown)
   and writes `kalibr_imucam_chain.yaml` + `calib_report.txt` to
   `Android/data/com.rawmechatronics.imucalib/files/calib-output/<timestamp>/`.
4. Copy that YAML to
   `/data/data/com.tomppi.enderslicercura/files/openvins-config/` (adb/root) and
   restart EasyScan; the VIO then loads the new `T_cam_imu` and time offset.
   The app keeps the engine's calibrated intrinsics for the 640×480 VIO stream
   and writes only the extrinsic/time-offset fields.

The recorder also writes full-rate frames and 480 Hz IMU; `session_to_bag.py`
converts a session into a ROS 1 bag for the real Kalibr run on a PC.

## Host Kalibr reference run (repeatable)

```bash
# one-time: Ubuntu 20.04 chroot + ROS Noetic + Kalibr (native x86)
/root/kalibr-work-setup.sh && /root/kalibr-work/build.sh

# synthetic validation
/root/kalibr-work/make_aprilgrid2.py       # Kalibr-native board raster
/root/kalibr-work/make_synthetic.py        # bag + session + truth
/root/kalibr-work/run_kalibr_synthetic.sh  # cam + imu-camera calibration
/root/kalibr-work/make_phonefmt.py          # recorder-format session (JPEG, shifted pose, async IMU)
/root/kalibr-work/session_to_bag.py <session-dir> <bag>
/root/kalibr-work/run_kalibr_phonefmt.sh    # the same pipeline on the recorder-format session
/root/venv-cv/bin/python /root/kalibr-work/parse_kalibr.py \
  /root/kalibr-work/synthetic-camchain-imucam.yaml \
  /root/kalibr-work/synthetic/truth.json
```
