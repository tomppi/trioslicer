# Splat Proto payload v1

The two built payloads behind TrioSlicer's **Gaussian splatting** workspace, packaged together so
a source build can be reproduced without a 233 MB file in Git.

## Contents

| path | what |
|---|---|
| `jniLibs/arm64-v8a/libbrush_jni.so` | Brush's trainer/renderer behind TrioSlicer's JNI crate. Built from `ArthurBrussee/brush` at `1388f74c6fe0236f68ee4915564bf00e9d2e3747` plus `native/splatproto/brush-android.patch` and `native/splatproto/brush-jni`. Apache-2.0. |
| `assets/masker/**` | U2Net-Segmentation (Qualcomm AI Hub Models v0.66.0, w8a8) compiled to a QAIRT HTP v73 context binary, and the QAIRT 2.52.0.261104 runtime that executes it: qnn-net-run, the HTP backend/stub/skeleton and the two HTP config files. |
| `SOURCE.md` | Where each came from and how to rebuild it. |
| `LICENSES/` | Apache-2.0 (Brush, U2Net), BSD-3-Clause (AI Hub Models), the Qualcomm AI Stack licence and its notice. |
| `SHA256SUMS` | Digest of every file in the package. |

The app ships `assets/masker/SOURCE.md`, `SHA256SUMS` and the licence set inside the APK beside
the object code.

## Reproduce it

    scripts/build-splatproto-payload-android.sh --package

needs Rust stable with the `aarch64-linux-android` target, cargo-ndk and NDK 28.2.13676358, plus
the masker directory (`native/splatproto/masker/SOURCE.md` describes the QAIRT compile).

## Use it

    scripts/fetch-splatproto-payload-android.sh

verifies this asset's pinned SHA-256 and stages it into `:splatproto`. CI runs the same script
before the Gradle build.

## Provenance notes

- The Brush patch replaces WGSL subgroup scans with a portable scan/serial-histogram path: the
  Adreno GPU on the target phone's wgpu build has no SUBGROUP capability, and the unpatched
  kernels do not compile there.
- The masker is redistributed as object code inside the application under the Qualcomm AI Stack
  licence; nothing here is a standalone SDK distribution. See `LICENSES/LICENSE-Qualcomm-AI-Stack.pdf`.
