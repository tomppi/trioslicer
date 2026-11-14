# Splat Proto payload — sources and the release package

TrioSlicer's **Gaussian splatting** workspace (More -> Experimental -> Gaussian splatting) runs a
capture -> SfM -> Brush-training pipeline entirely on the phone. The code is the
[:splatproto](../splatproto) library module. Two built payloads it needs are larger than a Git
repository should carry, so they are published as one **release asset** and staged by
`scripts/fetch-splatproto-payload-android.sh`, the same way the Blender and SolidCraft engines are:

    https://github.com/tomppi/trioslicer/releases/download/splatproto-payload-v1/splatproto-payload-arm64-v1.tar.gz

| payload | what it is | where it lands in a build |
|---|---|---|
| `jniLibs/arm64-v8a/libbrush_jni.so` | Brush's trainer/renderer behind a small JNI bridge | `splatproto/src/main/jniLibs/arm64-v8a/` |
| `assets/masker/**` | U2Net as a QAIRT HTP v73 context binary, the QNN runtime it runs on, and the two HTP config files | `splatproto/src/main/assets/masker/` |

The fetch script pins the asset's SHA-256; a deliberate re-pin updates it together with the tag.

## Brush JNI (`libbrush_jni.so`), Apache-2.0

- Upstream: `ArthurBrussee/brush`, pinned commit **`1388f74c6fe0236f68ee4915564bf00e9d2e3747`**
- JNI crate: [`brush-jni/`](brush-jni) (vendored here — it is not upstream)
- Android patch: [`brush-android.patch`](brush-android.patch), applied to the pinned checkout:
  - `Cargo.toml` — adds `apps/brush-jni` as a workspace member
  - `crates/rrfd/Cargo.toml` — pins the Android `jni` crate to 0.21 so the binary carries one
    JNI version (the JNI crate uses 0.21)
  - `crates/brush-scan/src/kernels.rs`, `crates/brush-sort/src/kernels.rs` — replace WGSL
    subgroup scans with a portable Hillis-Steele scan / serial histogram scan. Adreno GPUs on
    this phone's wgpu build do not expose the SUBGROUP capability, and the subgroup path fails to
    compile there.
  - `Cargo.lock` — the lock the patched workspace builds

  Rebuild with:

      scripts/build-splatproto-payload-android.sh --package

  which fetches the pinned commit into `.build/`, applies the patch, copies the JNI crate in,
  builds with `cargo ndk -t arm64-v8a -P 29 build --release -p brush-jni` (Rust stable, NDK
  28.2.13676358, cargo-ndk 4.1.2 or newer) and assembles the release package.

## NPU masker (`assets/masker/`)

- Model: **U2Net-Segmentation**, `w8a8` `qnn_dlc` asset from
  [Qualcomm AI Hub Models](https://aihub.qualcomm.com/models/u2net_segmentation) v0.66.0
  (`u2net_segmentation-qnn_dlc-w8a8.zip`). The model is Apache-2.0 (U-2-Net by Xuebin Qin et
  al.); the AI Hub packaging code is BSD-3-Clause; both licence texts travel with the payload.
- Compiled offline to an **HTP v73 context binary** with the Qualcomm AI Runtime SDK
  **2.52.0.261104** (see [`masker/SOURCE.md`](masker/SOURCE.md) for the exact commands).
- Runtime files: QAIRT's `qnn-net-run`, `libQnnHtp.so`, `libQnnHtpV73Stub.so`,
  `libQnnHtpNetRunExtensions.so`, `libQnnSystem.so`, and the unsigned HTP skeletons in
  `skel/`. They are redistributed as object code inside the app under the Qualcomm AI Stack
  licence ([`masker/LICENSE-Qualcomm-AI-Stack.pdf`](masker/LICENSE-Qualcomm-AI-Stack.pdf),
  [`masker/NOTICE-Qualcomm.txt`](masker/NOTICE-Qualcomm.txt)).

The generated files are not in Git; the package carries `SOURCE.md`, the licence texts and a
`SHA256SUMS` so an APK can be traced back to what produced it. The app's masking code is
`splatproto/src/main/java/com/tomppi/splatproto/NpuMasker.kt`.
