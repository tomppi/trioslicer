# Splat Proto payload — sources and the release package

TrioSlicer's on-device **3D Gaussian Splatting** and **DA3 NPU depth** pipelines live in the
[:splatproto](../splatproto) library module. Their prebuilt arm64 binaries and compiled QNN
context models are larger than a Git repository should carry, so they are published as one
**release asset** and staged by `scripts/fetch-splatproto-payload-android.sh`:

    https://github.com/tomppi/trioslicer/releases/download/splatproto-payload-v2/splatproto-payload-arm64-v2.tar.gz

| payload | what it is | where it lands in a build |
|---|---|---|
| `jniLibs/arm64-v8a/libcross3dgs_android.so` | Cross3DGS Vulkan 3DGS trainer library (arm64-v8a) | `splatproto/src/main/jniLibs/arm64-v8a/` |
| `assets/masker/**` | U2Net + Depth Anything 3 QAIRT HTP v73 context binaries, `da3-worker`, `qnn-net-run`, QNN runtime and HTP configs | `splatproto/src/main/assets/masker/` |

The fetch script pins the asset's SHA-256; a deliberate re-pin updates it together with the tag.

## Cross3DGS Vulkan trainer (`libcross3dgs_android.so`), AGPL-3.0-or-later

- Upstream: `unclearness/cross3dgs`, pinned commit **`d69f79e`**
  (Hiroyuki Sato & Yusuke Takimoto, WACV 2027)
- License: GNU Affero General Public License v3.0 or later (`AGPL-3.0-or-later`)
- Built for Android `arm64-v8a` (API 29, NDK 28.2.13676358) via CMake/Ninja and stripped to
  ~10.9 MB. Replaces the earlier 158 MB Brush wgpu trainer.

## NPU masker & Depth Anything 3 (`assets/masker/`)

- **U2Net-Segmentation**: `w8a8` `qnn_dlc` asset from
  [Qualcomm AI Hub Models](https://aihub.qualcomm.com/models/u2net_segmentation) v0.66.0
  (U-2-Net Apache-2.0; AI Hub packaging BSD-3-Clause), compiled to `models/u2net.bin` (HTP v73).
- **Depth Anything 3 (DA3)**: `ByteDance-Seed/Depth-Anything-3` (Apache-2.0), compiled to FP16
  HTP v73 context binary `models/da3_depth_fp16.bin`, driven by the persistent arm64 worker
  `bin/da3-worker` (source in [`da3-worker/da3_worker.cpp`](da3-worker/da3_worker.cpp), built via
  `scripts/build-da3-worker-android.sh`).
- **QAIRT 2.52.0.261104 runtime**: `bin/qnn-net-run`, `libQnnHtp.so`, `libQnnHtpV73Stub.so`,
  `libQnnHtpNetRunExtensions.so`, `libQnnSystem.so`, and unsigned HTP skeletons in `skel/`.
  Redistributed as application object code under the Qualcomm AI Stack license
  ([`masker/LICENSE-Qualcomm-AI-Stack.pdf`](masker/LICENSE-Qualcomm-AI-Stack.pdf),
  [`masker/NOTICE-Qualcomm.txt`](masker/NOTICE-Qualcomm.txt)).

See [`masker/SOURCE.md`](masker/SOURCE.md) for the QAIRT compilation details.
