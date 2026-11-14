# NPU masker payload — how it was built

The app segments keyframes with U2Net on the Snapdragon HTP. This directory is the runtime
payload that runs it; it is not built by the Android build.

## What was downloaded

```
qai-hub-models fetch U2Net-Segmentation --runtime qnn_dlc --precision w8a8
```

→ `u2net_segmentation-qnn_dlc-w8a8.zip` (Qualcomm AI Hub Models v0.66.0,
`u2net_segmentation-qnn_dlc-w8a8.zip`, SHA-256 recorded in that release's asset list), which
contains `u2net_segmentation.dlc`: input `image` 1x320x320x3 uint8 NHWC (scale
0.003928265068680048), output `mask` 1x1x320x320 uint8 (scale 0.00390625).

Input/output types and the 320x320 shape are the reasons `NpuMasker.kt` writes raw uint8
NHWC input and reads `mask_native.raw` with `qnn-net-run --use_native_input_files
--use_native_output_files`; the tool's default float32 path turns the uint8 buffer into stripes.

## How the context binary was produced

On an x86-64 Linux host, with Qualcomm AI Runtime SDK **2.52.0.261104**
(`QNN_SDK_ROOT=<sdk>/qairt/2.52.0.261104`) and the SDK's host libraries on
`LD_LIBRARY_PATH`:

```
htp_config.json
{
  "devices": [
    { "dsp_arch": "v73", "cores": [{ "perf_profile": "burst", "rpc_control_latency": 100 }] }
  ]
}

htp_ext.json
{
  "backend_extensions": {
    "shared_library_path": "/data/local/tmp/splatproto-masker/libQnnHtpNetRunExtensions.so",
    "config_file_path": "/data/local/tmp/splatproto-masker/models/htp_config.json"
  }
}

qairt-dlc-prepare \
  --backend      "$QNN_SDK_ROOT/lib/x86_64-linux-clang/libQairtHtp.so" \
  --input_dlc    u2net_segmentation.dlc \
  --config_file  htp_ext.json \
  --output_dir   out \
  --binary_file  u2net.bin
```

The result, `out/u2net.bin` (45,604,864 bytes), is the HTP v73 context binary the phone
retrieves. The extension JSON names the on-device staging directory
(`/data/local/tmp/splatproto-masker`, the `DEVICE_PKG` constant in `NpuMasker.kt`), so a change
to that constant must be made here too.

The device payload is staged by the app itself: `assets/masker` is copied to
`filesDir/masker/pkg`, then to `/data/local/tmp/splatproto-masker` through `su` (an app
sandbox cannot load the unsigned HTP skeleton). `PKG_VERSION` in `NpuMasker.kt` is the
restaging stamp.

## Runtime files in this payload

| file | role |
|---|---|
| `bin/qnn-net-run` | QAIRT's reference runner (the host shim the app executes under `su`) |
| `libQnnHtp.so` | QNN HTP backend |
| `libQnnHtpV73Stub.so` | HTP v73 user-space stub (SM8550) |
| `skel/libQnnHtpV73Skel.so` | unsigned HTP v73 skeleton (DSP side) |
| `skel/libQnnNetRunDirectV73Skel.so` | the runner's direct skeleton |
| `libQnnHtpNetRunExtensions.so` | backend extensions loaded by the ext JSON above |
| `libQnnSystem.so` | QNN system library |
| `models/u2net.bin` | the compiled U2Net context binary |
| `models/htp_config.json`, `models/htp_ext.json` | HTP config the runner loads |

All of these are Qualcomm AI Runtime object code except `u2net.bin`, which is the compiled
model. Their licence is the Qualcomm AI Stack licence in `LICENSES/`; the app ships that
directory and the source notice with the binary.
