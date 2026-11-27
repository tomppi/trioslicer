#!/usr/bin/env python3
"""
Fetch Snapdragon multi-generation QNN HTP (V68, V69, V73, V75, V79, V81) stub/skel
libraries and Qualcomm Adreno QNN GPU backend (libQnnGpu.so) from QAIRT 2.52.0.261104
via HTTP Range requests (avoids downloading the full ~4 GB SDK archive).

Covers:
  - V68: Snapdragon 888 / 888+ / 7+ Gen 2 / 7s Gen 2 (INT8 HTP; FP16 falls back to QNN GPU)
  - V69: Snapdragon 8 Gen 1 / 8+ Gen 1 (FP16 HTP)
  - V73: Snapdragon 8 Gen 2 / 8s Gen 3 / 7+ Gen 3 (FP16 HTP)
  - V75: Snapdragon 8 Gen 3 (FP16 HTP)
  - V79: Snapdragon 8 Elite / SM8750 (FP16 HTP)
  - V81: Snapdragon 8 Elite Gen 2 / SM8850 (FP16 HTP)
  - QnnGpu: Adreno FP16 GPU fallback for SD 888 / 865 / 870 / 7xx / 6xx
"""

import io
import os
import sys
import time
import urllib.request
import zipfile

QAIRT_URL = (
    "https://softwarecenter.qualcomm.com/api/download/software/sdks/"
    "Qualcomm_AI_Runtime_Community/All/2.52.0.261104/v2.52.0.261104.zip"
)
HTP_ARCHES = ["68", "69", "73", "75", "79", "81"]


class HttpFile(io.RawIOBase):
    def __init__(self, url, size):
        self.url = url
        self.size = size
        self.pos = 0

    def readable(self):
        return True

    def seekable(self):
        return True

    def seek(self, off, whence=io.SEEK_SET):
        if whence == io.SEEK_SET:
            self.pos = off
        elif whence == io.SEEK_CUR:
            self.pos += off
        else:
            self.pos = self.size + off
        return self.pos

    def tell(self):
        return self.pos

    def readinto(self, b):
        if self.pos >= self.size or not b:
            return 0
        end = min(self.size - 1, self.pos + len(b) - 1)
        last_err = None
        for attempt in range(5):
            try:
                req = urllib.request.Request(self.url, headers={"Range": f"bytes={self.pos}-{end}"})
                with urllib.request.urlopen(req, timeout=60) as resp:
                    data = resp.read()
                n = len(data)
                b[:n] = data
                self.pos += n
                return n
            except Exception as err:
                last_err = err
                time.sleep(1.5 * (attempt + 1))
        raise last_err


def main():
    root = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    out_dir = os.path.join(root, "easyscanapp", "src", "main", "jniLibs", "arm64-v8a")
    os.makedirs(out_dir, exist_ok=True)

    wanted = {
        "qairt/2.52.0.261104/lib/aarch64-android/libQnnGpu.so": "libQnnGpu.so",
    }
    for v in HTP_ARCHES:
        wanted[f"qairt/2.52.0.261104/lib/aarch64-android/libQnnHtpV{v}Stub.so"] = f"libQnnHtpV{v}Stub.so"
        wanted[f"qairt/2.52.0.261104/lib/hexagon-v{v}/unsigned/libQnnHtpV{v}Skel.so"] = f"libQnnHtpV{v}Skel.so"

    missing = {k: v for k, v in wanted.items() if not os.path.exists(os.path.join(out_dir, v))}
    if not missing:
        print("All multi-Snapdragon QNN libraries already present in", out_dir)
        return

    req = urllib.request.Request(QAIRT_URL, headers={"Range": "bytes=0-0"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        final_url = resp.geturl()
        size = int(resp.headers.get("Content-Range", "").split("/")[-1])

    with zipfile.ZipFile(io.BufferedReader(HttpFile(final_url, size), buffer_size=524288)) as zf:
        for member, fname in missing.items():
            dst = os.path.join(out_dir, fname)
            tmp = dst + ".tmp"
            print(f"Extracting {fname} ...", flush=True)
            with zf.open(member) as src, open(tmp, "wb") as out:
                while True:
                    chunk = src.read(1024 * 1024)
                    if not chunk:
                        break
                    out.write(chunk)
            os.replace(tmp, dst)
            print(f"  wrote {os.path.getsize(dst) / 1e6:.2f} MB -> {dst}", flush=True)


if __name__ == "__main__":
    main()
