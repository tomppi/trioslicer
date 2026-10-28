# SPDX-License-Identifier: AGPL-3.0-only
"""Engine compatibility for the OCP 7.9.3.1 payload the app ships.

The app's OCP is a cross-compiled build of OCCT 7.9.3.1 that exposes only part of the
cadquery-ocp Python API. Two gaps that Mesh-Mix-S2C (written for OCP 8.0.1) trips on are
adapted here, without editing any vendored engine file or the OCP payload:

- STEP read-back: STEPControl_Reader has no TransferRoots() on this build, so OneShape()
  returns a null shape and every saved STEP file would be reported unreadable. The adapted
  reader uses STEPCAFControl_Reader, the same reader the engine already uses for multi-part
  files, and returns the free shapes as one compound.

- DistShapeShape: BRepExtrema_DistShapeShape only exposes the default constructor here; the
  engine's fidelity check constructs it with two shapes. The adapted class builds it with the
  LoadS1/LoadS2/PerformDist sequence the same object provides.
"""
_installed = False


def install():
    """Install the adapters (idempotent; a no-op for parts the payload already supports)."""
    global _installed
    if _installed:
        return
    _install_step_reader()
    _install_distance_shape()
    _installed = True


def _install_step_reader():
    from OCP.STEPControl import STEPControl_Reader

    if hasattr(STEPControl_Reader, "TransferRoots"):
        # OCP 8: the engine's own reader path works; nothing to do.
        return

    from OCP.BRep import BRep_Builder
    from OCP.TopoDS import TopoDS_Compound

    import mmm.stepio as stepio

    def read_step(path):
        """Read a STEP file to one TopoDS_Shape using the available XCAF reader."""
        shapes = stepio.read_step_named(path)
        if not shapes:
            raise RuntimeError("the STEP file has no shapes")
        builder = BRep_Builder()
        compound = TopoDS_Compound()
        builder.MakeCompound(compound)
        for shape, _name in shapes:
            builder.Add(compound, shape)
        return compound

    stepio.read_step = read_step


def _install_distance_shape():
    import OCP.BRepExtrema as brep_extrema
    from OCP.BRepExtrema import BRepExtrema_DistShapeShape as original

    if not all(hasattr(original, name) for name in ("LoadS1", "LoadS2", "PerformDist")):
        return

    class DistShapeShape(original):
        """BRepExtrema_DistShapeShape(shape1, shape2) on a default-constructor build."""

        def __init__(self, shape1=None, shape2=None, *args, **kwargs):
            super().__init__()
            if shape1 is not None:
                self.LoadS1(shape1)
            if shape2 is not None:
                self.LoadS2(shape2)
            if shape1 is not None and shape2 is not None:
                self.PerformDist()

    brep_extrema.BRepExtrema_DistShapeShape = DistShapeShape
