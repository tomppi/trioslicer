# Splat Proto payload v2

The built native payloads behind TrioSlicer's on-device **3D Gaussian Splatting** and
**DA3 NPU depth** pipelines, packaged as a release asset so CI and local clones reproduce the
exact same APK without storing ~150 MB of prebuilt binaries and context models in Git.

## Contents

| path | what |
|---|---|
| `jniLibs/arm64-v8a/libcross3dgs_android.so` | Cross3DGS Vulkan 3DGS trainer library (`unclearness/cross3dgs` @ `d69f79e`, `AGPL-3.0-or-later`), stripped arm64-v8a. Replaces Brush. |
| `assets/masker/models/u2net.bin` | U2Net-Segmentation (Qualcomm AI Hub Models v0.66.0, w8a8) compiled to a QAIRT 2.52.0.261104 HTP v73 context binary. |
| `assets/masker/models/da3_depth_fp16.bin` | Depth Anything 3 (`ByteDance-Seed/Depth-Anything-3`, Apache-2.0) FP16 depth model compiled to a QAIRT 2.52.0.261104 HTP v73 context binary. |
| `assets/masker/bin/da3-worker` | Persistent arm64 QNN HTP DA3 worker binary built from `native/splatproto/da3-worker/da3_worker.cpp` (`scripts/build-da3-worker-android.sh`). |
| `assets/masker/bin/qnn-net-run` & QNN HTP libs/skels | QAIRT 2.52.0.261104 object-code runtime (`libQnnHtp.so`, `libQnnHtpV73Stub.so`, `libQnnHtpNetRunExtensions.so`, `libQnnSystem.so`, `skel/libQnnHtpV73Skel.so`, `skel/libQnnNetRunDirectV73Skel.so`) and HTP config JSONs. |
| `SOURCE.md` | Exact provenance and build commands. |
| `LICENSES/` | AGPL-3.0 (Cross3DGS), Apache-2.0 (U2Net, DA3), BSD-3-Clause (AI Hub Models), Qualcomm AI Stack license and Qualcomm notice. |
| `SHA256SUMS` | SHA-256 digest of every file in the package. |

## Use it

```sh
scripts/fetch-splatproto-payload-android.sh
```

verifies the pinned SHA-256 and stages `libcross3dgs_android.so` and `assets/masker/` into
`:splatproto`. CI runs the same script before Gradle assembly.
