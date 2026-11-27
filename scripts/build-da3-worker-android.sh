#!/usr/bin/env bash
# Builds the persistent arm64 QNN HTP DA3 worker (bin/da3-worker) against
# Qualcomm AI Runtime 2.52.0.261104 headers (extracted into .build/qnn-2.52.0).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NDK="${ANDROID_NDK_HOME:-/opt/android-sdk/ndk/28.2.13676358}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
HDR_DIR="$ROOT/.build/qnn-2.52.0/include/QNN"
if [ ! -f "$HDR_DIR/QnnInterface.h" ]; then
  echo "Fetching QAIRT 2.52.0.261104 QNN headers into $HDR_DIR"
  python3 - "$HDR_DIR" <<'PY'
import io, os, sys, urllib.request, zipfile
out_root = sys.argv[1]
url = 'https://softwarecenter.qualcomm.com/api/download/software/sdks/Qualcomm_AI_Runtime_Community/All/2.52.0.261104/v2.52.0.261104.zip'
req = urllib.request.Request(url, headers={'Range': 'bytes=0-0'})
with urllib.request.urlopen(req, timeout=20) as resp:
    final_url = resp.geturl()
    cr = resp.headers.get('Content-Range', '')
    size = int(cr.split('/')[-1])
class HttpFile(io.RawIOBase):
    def __init__(self, u, sz): self.u = u; self.sz = sz; self.pos = 0
    def readable(self): return True
    def seekable(self): return True
    def seek(self, off, whence=io.SEEK_SET):
        self.pos = off if whence == io.SEEK_SET else (self.pos + off if whence == io.SEEK_CUR else self.sz + off)
        return self.pos
    def tell(self): return self.pos
    def readinto(self, b):
        if self.pos >= self.sz or not b: return 0
        end = min(self.sz - 1, self.pos + len(b) - 1)
        r = urllib.request.Request(self.u, headers={'Range': f'bytes={self.pos}-{end}'})
        with urllib.request.urlopen(r, timeout=30) as resp: data = resp.read()
        b[:len(data)] = data; self.pos += len(data); return len(data)
with zipfile.ZipFile(io.BufferedReader(HttpFile(final_url, size), buffer_size=262144)) as zf:
    for m in zf.namelist():
        if '/include/QNN/' in m and m.endswith('.h'):
            dst = os.path.join(out_root, m.split('/include/QNN/', 1)[1])
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            with zf.open(m) as s, open(dst, 'wb') as o: o.write(s.read())
PY
fi
OUT="$ROOT/splatproto/src/main/assets/masker/bin/da3-worker"
"$TOOLCHAIN/aarch64-linux-android29-clang++" -O3 -std=c++17 -Wall -Wextra -static-libstdc++ \
  -I"$HDR_DIR" "$ROOT/native/splatproto/da3-worker/da3_worker.cpp" -ldl -o "$OUT"
"$TOOLCHAIN/llvm-strip" --strip-unneeded "$OUT"
chmod 755 "$OUT"
ls -lh "$OUT"
