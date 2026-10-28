# SPDX-License-Identifier: AGPL-3.0-only
"""OCP.collections compatibility for OCP 7.9.3.1.

Mesh-Mix-S2C v1.6.0 was written for OCP 8.0.1, whose OCP.collections module
exports the OCCT 8 generic array/map wrappers under the names below. The app
ships OCP 7.9.3.1, which has the same OCCT 7.9 collection classes under their
classic names but no OCP.collections. This module installs that one module,
mapping every name the engine imports onto the class it needs. It is imported
by launcher.py before the engine; nothing in the OCP payload is edited.
"""
import sys
import types


def install():
    """Make OCP.collections importable (idempotent)."""
    if "OCP.collections" in sys.modules:
        return
    try:
        import OCP
    except ImportError as error:  # the app's payload is missing or unprepared
        raise ImportError(
            "the app's CAD payload (OCP) is not on PYTHONPATH; the mesh-to-STEP "
            "converter needs it: " + str(error)
        ) from error

    try:
        from OCP.collections import Array1_gp_Pnt  # OCP 8.0: nothing to do
        return
    except ImportError:
        pass

    from OCP.TColgp import (
        TColgp_Array1OfPnt as Array1_gp_Pnt,
        TColgp_HArray1OfPnt as HArray1_gp_Pnt,
        TColgp_Array2OfPnt as Array2_gp_Pnt,
        TColgp_Array1OfPnt2d as Array1_gp_Pnt2d,
        TColgp_HArray1OfPnt2d as HArray1_gp_Pnt2d,
    )
    from OCP.TColStd import (
        TColStd_Array1OfReal as Array1_double,
        TColStd_Array1OfInteger as Array1_int,
        TColStd_HArray1OfReal as HArray1_double,
    )
    from OCP.TopTools import (
        TopTools_IndexedMapOfShape as IndexedMap_TopoDS_Shape_TopTools_ShapeMapHasher,
    )
    from OCP.TDF import TDF_LabelSequence as Sequence_TDF_Label

    collections = types.ModuleType("OCP.collections")
    for name, value in list(locals().items()):
        if name.startswith(("Array", "HArray", "IndexedMap", "Sequence")):
            setattr(collections, name, value)
    sys.modules["OCP.collections"] = collections
    setattr(OCP, "collections", collections)
