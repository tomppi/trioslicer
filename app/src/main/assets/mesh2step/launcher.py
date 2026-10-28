#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Launcher for the bundled Mesh-Mix-S2C (Manifold Mesh Mix) engine.

The app runs its only CPython (the Klipper payload's interpreter) with this script. The engine
itself is upstream v1.6.0, unmodified, and expects OCP 8.0.1; the app ships OCP 7.9.3.1, so
two small adapters are installed before the engine is imported:

- ocp_compat: makes the OCP.collections names the engine imports available on 7.9.
- engine_compat: reads STEP files back through the available XCAF reader, because this
  payload's STEPControl_Reader has no TransferRoots().

The bundled lib/ directory carries the one optional accelerator we ship, the
PyNanoInstantMeshes binding for the engine's Smooth (quad remeshing) path. It is a drop-in
import for unmodified engine code, so it is only put on sys.path, never patched.

Neither the OCP payload nor any vendored engine file is modified.
"""
import os
import sys


def main(argv):
    here = os.path.dirname(os.path.abspath(__file__))
    if here not in sys.path:
        sys.path.insert(0, here)
    bundled = os.path.join(here, "lib")
    if bundled not in sys.path:
        sys.path.insert(0, bundled)
    import ocp_compat

    ocp_compat.install()
    import engine_compat

    engine_compat.install()
    from mmm.cli import main as engine_main

    return engine_main(argv)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))