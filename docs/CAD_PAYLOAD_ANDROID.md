# The CAD payload, and how it is built

The CAD engine is the one engine in the app that is not a native executable. It is Python -
build123d over **OCP**, the official OpenCASCADE bindings - running under the CPython the
Klipper host already brings. Everything it needs beyond that interpreter is the *payload*:
`app/src/main/assets/cad/`, ~533 MB across 10,700 files, gitignored, and staged by
`scripts/stage-cad-android.sh`.

This file records what that payload is, how something in it gets changed, and how a change is
published - the parts that are not in the repository because the build trees are not either.

## What is in it

| part | what it is |
|---|---|
| `lib/python3.11/site-packages/OCP.cpython-311.so` | OCCT 7.9.3 with the pywrap bindings, statically linked, stripped (213 MB) |
| `lib/python3.11/site-packages/build123d` | build123d, and numpy / scipy / scikit-learn / Pillow / ezxdf / py-lib3mf beside it |
| `libexec/` | the payload's own shared libraries (openblas, gfortran, libpng, omp, jpeg) |
| `cad_mcp_slim.py` | the engine itself. The app ships its own copy in assets and uses that one |

OCP's own `DT_NEEDED` list is only `libpython3.11.so.1.0`, `libEGL.so`, `libGLESv2.so` and
the libc family: OCCT is linked into the extension, so there is no `libTKV3d.so` to ship.

## The build tree (off-repo)

    /root/occt-port/OCP          the OCP/pywrap checkout (CadQuery's ocp-build-system)
    /root/occt-port/OCP/OCP      the *generated* bindings - 300-odd .cpp files, 3.7 GB link
    /root/occt-port/OCP/OCP/build   the arm64-v8a ninja build (NDK r28c)
    /root/occt-port/build/OCCT-7_9_3    the OCCT the bindings compile against
    /root/occt-port/cad-assets.tar      the payload source archive staging reads

Rebuilding the extension after any binding change is one incremental link - under a minute:

    ninja -C /root/occt-port/OCP/OCP/build OCP
    llvm-strip --strip-unneeded -o OCP.cpython-311.so /root/occt-port/OCP/OCP/build/OCP.so

`--strip-unneeded`, never `--strip-all`: the latter removes the dynamic symbol table, and a
C++ runtime stripped that way is what broke every CAD command in 1.8.1 (see the 1.8.2 commits).

## Binding patches

pywrap does not emit every method the engine needs. Each gap has a small script beside the
build tree that patches the generated C++; each one is safe to re-run and refuses to apply
twice:

| script | what it adds |
|---|---|
| `add-triangulation.py` | `BRep_Tool.Triangulation_s`, `BRepTools.Triangulation_s` |
| `add-occt-surface-bindings.py` | the HLR, section and analysis entry points |
| `add-gltf.py` | `RWGltf_CafWriter` and friends |
| `add-image-pixels.py` | `Image_PixMap.ReadBytes` - reading a rendered frame |

A copy of the last one lives in this repository as `scripts/patch-ocp-image-access.py`, because
what it fixes is otherwise invisible: pywrap binds `Standard_Byte*` as the byte it points at, so
`Image_PixMap::Data()` returns a pixel value rather than an address. `ReadBytes()` is the
accessor that was missing, and it is what lets the engine hand a frame to Pillow directly
instead of routing it through a PPM file that OCCT's `Save()` writes whatever the extension says.

## Publishing a new payload

The payload is a release asset, because `jniLibs/` and `assets/cad/` are both gitignored and CI
has neither the 4 GB build tree nor the cross-compiles:

    # 1. stage the tree the app and the asset both come from
    CAD_ARCHIVE=/root/occt-port/cad-assets.tar scripts/stage-cad-android.sh

    # 2. package exactly what was staged
    tar czf cad-payload-arm64-v1.1.1.tar.gz -C app/src/main/assets/cad cad_mcp_slim.py lib libexec

    # 3. publish it, then pin the digest the app's fetch script checks
    gh release create cad-payload-v1.1.1 cad-payload-arm64-v1.1.1.tar.gz
    #    scripts/fetch-cad-payload-android.sh: CAD_PAYLOAD_VERSION, CAD_PAYLOAD_SHA256_PINNED

The version bumps whenever the payload changes, and the app version with it: an old app against a
new payload is fine (it ignores what it does not use), a new app against an old payload is not
guaranteed - the engine keeps a fallback for the missing `ReadBytes`, and nothing else.
