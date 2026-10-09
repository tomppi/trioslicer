# cad_mcp_slim.py -- CAD engine for the TrioSlicer Android app.
#
# The CAD counterpart of blender_mcp_slim.py, and deliberately a near-copy of
# its transport: same request envelope, same token auth, same one-command-at-a-
# time queue, same atomic status and export writes. Anything the app learns
# about one engine therefore holds for the other.
#
# What differs is the payload. Blender's engine drives *bpy* and holds a bpy
# scene; this one drives **build123d on top of OCP** (the official OpenCASCADE
# Python bindings) and holds a plain dict of named shapes. It runs under the
# app's CPython 3.11 payload -- the interpreter that libklipper_exec.so hosts --
# because that is where OCP, build123d and their dependencies are installed.
#
# License: MIT, matching the Blender addon this is derived from.
#
# Commands
#   ping                    liveness; touches no geometry
#   shutdown                drain, close the socket, exit
#   execute_code            run build123d/OCP code; stdout captured
#   get_scene_info          the named shapes, with volume and bounding box
#   get_object_info         one shape in detail
#   get_metrics             volume, area, centroid, bounding box, topology counts
#   export_step             write the scene (or one shape) as STEP
#   export_stl              tessellate the scene (or one shape) to binary STL
#   get_addon_info          engine name, version, kernel versions
#
# Every request carries the token; without it _authorized() refuses, fail-closed.

import io
import json
import math
import os
import queue
import socket
import threading
import time
import traceback
from contextlib import redirect_stdout

# 9877, not Blender's 9876: the two engines can be running at the same time and
# each needs its own port. Overridable with cad_mcp_port.txt next to this file.
DEFAULT_PORT = 9877

# A request larger than this is refused rather than buffered: nothing legitimate
# sends more than a script.
MAX_REQUEST_BYTES = 8 * 1024 * 1024

# One command at a time runs on the main thread. A client that asks while a
# command has been running this long is told so, instead of waiting out its own
# timeout with no explanation.
BUSY_ANSWER_AFTER_SECONDS = 10.0

VERSION = (0, 1)


# --------------------------------------------------------------------------
# OpenCASCADE / build123d
# --------------------------------------------------------------------------

def _import_kernel():
    """Import OCP and build123d once, at startup.

    Import failure is not fatal to the process: the engine still serves ping and
    reports the reason, so the app can say why CAD is unavailable rather than
    showing a dead port.
    """
    kernel = {"ocp": None, "build123d": None, "error": None}
    try:
        import OCP
        kernel["ocp"] = OCP
    except Exception as exc:
        kernel["error"] = "OCP import failed: %r" % (exc,)
        return kernel
    try:
        import build123d
        kernel["build123d"] = build123d
    except Exception as exc:
        kernel["error"] = "build123d import failed: %r" % (exc,)
    return kernel


_KERNEL = None


def kernel():
    global _KERNEL
    if _KERNEL is None:
        _KERNEL = _import_kernel()
    return _KERNEL


# --------------------------------------------------------------------------
# The scene
# --------------------------------------------------------------------------

class Scene:
    """Named shapes, in insertion order.

    Blender's engine gets persistence for free from bpy.data; there is no
    equivalent here, so the engine owns a plain dict. Names are the handle an
    agent uses between commands.
    """

    def __init__(self):
        self._shapes = {}

    def add(self, name, shape):
        if not name:
            raise ValueError("add() needs a name")
        self._shapes[str(name)] = shape
        return str(name)

    def get(self, name):
        if name not in self._shapes:
            raise KeyError("no shape named %r; scene holds %s"
                           % (name, sorted(self._shapes) or "nothing"))
        return self._shapes[name]

    def remove(self, name):
        if name not in self._shapes:
            raise KeyError("no shape named %r" % (name,))
        return self._shapes.pop(name)

    def clear(self):
        self._shapes.clear()

    def names(self):
        return list(self._shapes)

    def items(self):
        return list(self._shapes.items())

    def empty(self):
        return not self._shapes


SCENE = Scene()


def _solid_or_compound(shape):
    """build123d shapes expose .solid()/.solids(); OCP shapes do not."""
    return shape


def _volume_of(shape):
    try:
        return float(shape.volume)
    except Exception:
        pass
    try:
        props = _volume_properties(shape)
        return float(props.Mass())
    except Exception:
        return None


def _volume_properties(shape):
    """GProp_GProps for a solid: mass, and with it the centre of mass.

    One call answers both, which is why the centroid does not have to be guessed at from the
    bounding box.
    """
    from OCP.BRepGProp import BRepGProp
    from OCP.GProp import GProp_GProps
    props = GProp_GProps()
    BRepGProp.VolumeProperties_s(getattr(shape, "wrapped", shape), props)
    return props


def _centre_of_mass(shape):
    """Where the material actually balances, or None for something with no volume.

    Not the middle of the bounding box: for an L, a cone or anything with a boss on one side the
    two are different points, and calling the box centre a "centroid" told an agent to trust a
    number that was wrong for exactly the parts worth measuring.
    """
    try:
        centre = _volume_properties(shape).CentreOfMass()
        return [round(centre.X(), 6), round(centre.Y(), 6), round(centre.Z(), 6)]
    except Exception:
        return None


def _area_of(shape):
    try:
        return float(shape.area)
    except Exception:
        pass
    try:
        from OCP.BRepGProp import BRepGProp
        from OCP.GProp import GProp_GProps
        props = GProp_GProps()
        BRepGProp.SurfaceProperties_s(shape.wrapped, props)
        return float(props.Mass())
    except Exception:
        return None


def _bbox_of(shape):
    try:
        box = shape.bounding_box()
        return {
            "min": [round(box.min.X, 6), round(box.min.Y, 6), round(box.min.Z, 6)],
            "max": [round(box.max.X, 6), round(box.max.Y, 6), round(box.max.Z, 6)],
            "size": [round(box.size.X, 6), round(box.size.Y, 6), round(box.size.Z, 6)],
        }
    except Exception:
        return None


def _summary(name, shape):
    entry = {"name": name}
    volume = _volume_of(shape)
    if volume is not None:
        entry["volume"] = round(volume, 6)
    bbox = _bbox_of(shape)
    if bbox is not None:
        entry["bbox"] = bbox
    if hasattr(shape, "solids"):
        try:
            entry["solids"] = len(shape.solids())
        except Exception:
            pass
    return entry


# --------------------------------------------------------------------------
# Export
# --------------------------------------------------------------------------

# One loaded environment cloud at a time: a capture is hundreds of thousands of points, the
# tree over it is built once, and the phone has one of these to think about.
_CLEARANCE = {}


def _probe_points(shape, samples=16):
    """Points spread over a shape, so a clearance is its closest approach and not its nearest
    corner.

    Vertices and edge midpoints are free, and a face gets a grid: a corner can be millimetres
    from the world while the middle of a face is touching it, which is exactly the case a jig
    is built to avoid.
    """
    points = []

    def add(vector):
        try:
            points.append((float(vector.X), float(vector.Y), float(vector.Z)))
        except Exception:
            pass

    for vertex in shape.vertices():
        add(vertex)
    for edge in shape.edges():
        for t in (0.25, 0.5, 0.75):
            try:
                add(edge.position_at(t))
            except Exception:
                break
    grid = max(2, int(math.sqrt(max(4, samples))))
    for face in shape.faces():
        try:
            for i in range(grid):
                for j in range(grid):
                    add(face.position_at((i + 0.5) / grid, (j + 0.5) / grid))
        except Exception:
            # A face that will not take a parameter is still covered by its own vertices.
            continue
    return points


def _resolve_target(name):
    """(label, shape) for an export. No name means the whole scene.

    Several shapes are combined into one Compound so a STEP/STL export always
    produces a single coherent part rather than silently writing only the first.
    """
    if name:
        return name, SCENE.get(name)
    if SCENE.empty():
        raise ValueError("scene is empty; add a shape before exporting")
    if len(SCENE.names()) == 1:
        only = SCENE.names()[0]
        return only, SCENE.get(only)
    b3d = kernel()["build123d"]
    return "scene", b3d.Compound(children=[s for _, s in SCENE.items()])


def _publish(path, write):
    """Write through a temporary name, then rename into place.

    The rename is what publishes the file: the app polls the export directory
    and a half-written STEP or STL is not a file it can use. Mirrors
    blender_mcp_slim's handoff exactly.
    """
    directory = os.path.dirname(os.path.abspath(path))
    if directory:
        os.makedirs(directory, exist_ok=True)
    # The temporary keeps the extension, with the marker before it rather than after.
    # Exports that dispatch on the file extension - glTF is one - write nothing when
    # handed "model.gltf.part", and the rename is still atomic either way.
    #
    # And it is staged in a directory of its own, beside the target rather than in the middle
    # of the directory being watched. The whole point of the temporary is that a file appearing
    # next to the finished ones is a whole file; leaving it there breaks exactly that - the app
    # reports every new .png, .stl and .step in the export directory, so the empty temporary was
    # announced as "render ready: viewport-part.png (0 bytes)" twice a second while the frame it
    # belonged to was being written, and never finished. The rename is atomic either way: same
    # filesystem, one directory down.
    root, extension = os.path.splitext(path)
    staging = os.path.join(directory or ".", ".parts")
    os.makedirs(staging, exist_ok=True)
    temp = os.path.join(staging, os.path.basename(root) + "-part" + extension)
    try:
        result = write(temp)
        # An exporter that returns False, or writes nothing, must not look like success.
        # OCCT's glTF writer does exactly that on this platform, and reporting it as a
        # written file sends the agent looking for one that was never created.
        if result is False:
            raise RuntimeError("the exporter reported failure")
        if not os.path.exists(temp) or os.path.getsize(temp) == 0:
            raise RuntimeError("the exporter wrote nothing")
        os.replace(temp, path)
    except BaseException:
        try:
            os.remove(temp)
        except OSError:
            pass
        raise
    return os.path.getsize(path)


# --------------------------------------------------------------------------
# Offscreen rendering
# --------------------------------------------------------------------------

# Views an agent can ask for, as the direction vector V3d_View.SetProj takes.
# Isometric first because it is the one that shows a part as a part; the three
# orthographic views are what you ask for once you know which face to inspect.
VIEW_DIRECTIONS = {
    "iso": (1.0, -1.0, 1.0),
    "top": (0.0, 0.0, 1.0),
    "front": (0.0, -1.0, 0.0),
    "right": (1.0, 0.0, 0.0),
    "left": (-1.0, 0.0, 0.0),
    "back": (0.0, 1.0, 0.0),
    "bottom": (0.0, 0.0, -1.0),
}

# --------------------------------------------------------------------------
# The viewer's appearance
# --------------------------------------------------------------------------

# What the viewport looks like, as opposed to what is in it. Set from the app's Viewer settings
# through the `viewer_settings` command, and consulted by every render - the scene is cleared and
# rebuilt on each frame, so a presentation is not somewhere a setting could live between frames.
#
# Values are the app's own vocabulary rather than OCCT's: the sheet offers four tessellation
# levels and three backgrounds, and the mapping to numbers belongs here, next to the code that
# has to honour it.
VIEWER = {
    "tessellation": "standard",
    "background": "dark",
    "grid": False,
    "grid_step_mm": 10.0,
    "axes": True,
    "projection": "perspective",
    "edges": True,
    "antialiasing": True,
}

# AIS drawer deviation coefficient: the biggest gap between the true surface and the triangles
# drawn for it, as a fraction of the shape's size. Lower is finer and costs triangles.
TESSELLATION_LEVELS = {
    "coarse": 0.01,
    "standard": 0.001,
    "fine": 0.0005,
    "very fine": 0.0001,
}

BACKGROUND_COLOURS = {
    "dark": (0.16, 0.17, 0.19),
    "mid": (0.34, 0.35, 0.37),
    "light": (0.86, 0.86, 0.85),
}

# OCCT needs a GL context, and building one costs seconds of shader compilation - so it is
# built once and kept, not per render. Confirmed working on the device: the driver comes up
# with no window and no X server, and warns "a Window is created without a EGL Surface!"
# along the way. That warning is benign - OCCT makes its own context - and the working path
# deliberately does NOT hand it an EGL surface: SetNativeHandle(EGLSurface) segfaults,
# because OCCT treats the handle as an ANativeWindow* and calls eglCreateWindowSurface on it.
_GL = {}


class _Renderer:
    """A viewer, kept alive between renders.

    The scene is re-displayed on every render rather than left attached: a shape added by a
    later command has to appear, and re-attaching is cheaper than tracking what changed.
    """

    def __init__(self, width, height):
        from OCP.Aspect import Aspect_DisplayConnection, Aspect_NeutralWindow
        from OCP.OpenGl import OpenGl_GraphicDriver
        from OCP.V3d import V3d_Viewer
        from OCP.AIS import AIS_InteractiveContext

        self.display = Aspect_DisplayConnection()
        self.driver = OpenGl_GraphicDriver(self.display)
        self.viewer = V3d_Viewer(self.driver)
        # Without lights every shaded face renders black - the renderer is working and the
        # picture looks broken.
        try:
            self.viewer.SetDefaultLights()
        except Exception:
            pass
        self.viewer.SetLightOn()
        self.height = int(height)
        self.view = self.viewer.CreateView()
        self.window = Aspect_NeutralWindow()
        self.window.SetSize(width, height)
        self.view.SetWindow(self.window)
        self.context = AIS_InteractiveContext(self.viewer)
        self.width = width
        self.height = height

    def resize(self, width, height):
        if (width, height) != (self.width, self.height):
            self.window.SetSize(width, height)
            self.width, self.height = width, height

    def render(self, shape, width, height, view, shaded, highlight=None, fit=True):
        """Draw shape and return the view, ready for ToPixMap."""
        from OCP.AIS import AIS_Shape, AIS_Shaded, AIS_WireFrame
        from OCP.Quantity import Quantity_Color, Quantity_TOC_RGB

        self.resize(width, height)
        self.context.RemoveAll(False)
        # The grid, under everything, as geometry: a thing of a real size seen with the part
        # rather than a screen-space backdrop. Displayed here and never added to SCENE, so it
        # cannot reach an export.
        # Tessellation the way that works here: mesh the shape itself, before AIS looks at it.
        # AIS_Shape displays an existing triangulation and only computes one when there is none,
        # which is why the drawer's deviation coefficient changed nothing - the same reason
        # SetDrawEdges changed nothing, and it is written down in render() already.
        try:
            from OCP.BRepMesh import BRepMesh_IncrementalMesh
            from OCP.BRepTools import BRepTools

            wrapped = getattr(shape, "wrapped", shape)
            # The old triangulation has to go first: BRepMesh keeps an existing mesh that is
            # already finer than the one asked for, so a coarser setting would change nothing
            # and look like a broken control.
            try:
                BRepTools.Clean_s(wrapped)
            except Exception:
                pass
            # Relative deflection - a fraction of the shape's own size. The levels are
            # fractions, and passing one as millimetres asked for 0.0001 mm on a 20 mm
            # cylinder, which is millions of triangles and killed the render.
            BRepMesh_IncrementalMesh(
                wrapped,
                TESSELLATION_LEVELS.get(VIEWER["tessellation"],
                                        TESSELLATION_LEVELS["standard"]),
                True, 0.5, True)
        except Exception as exc:
            VIEWER.setdefault("_errors", {})["tessellation"] = "%s: %s" % (
                type(exc).__name__, exc)
        self._grids = []
        if VIEWER["grid"]:
            try:
                grid = AIS_Shape(_grid_shape(float(VIEWER["grid_step_mm"])))
                self.context.Display(grid, False)
                self.context.SetDisplayMode(grid, AIS_WireFrame, False)
                self.context.SetColor(
                    grid, Quantity_Color(0.45, 0.47, 0.52, Quantity_TOC_RGB), False)
                # Kept, so the fit below can leave it out of the frame.
                self._grids.append(grid)
            except Exception:
                pass
        presentation = AIS_Shape(shape)
        # Edges on the shape's own drawer, and set before it is displayed: a shaded solid
        # with no edges hides a boss standing on a plate, because from straight above the
        # two top faces are the same colour. The orthographic views are the ones used to
        # check a dimension, and without edges they are an outline and nothing else.
        try:
            attributes = presentation.Attributes()
            attributes.SetDrawEdges(True)
            attributes.SetDrawSilhouettes(True)
            attributes.SetEdgeColor(Quantity_Color(0.1, 0.1, 0.1, Quantity_TOC_RGB))
            # The drawer's deviation coefficient used to be set here for the tessellation
            # setting. It is not: on this driver the coefficient changes nothing - the frame
            # came back byte-identical at every level - and the setting is honoured by meshing
            # the shape before AIS ever sees it, in render().
        except Exception:
            pass
        self.presentation = presentation
        self.context.Display(presentation, False)
        self.context.SetDisplayMode(
            presentation, AIS_Shaded if shaded else AIS_WireFrame, False)
        # Shaded and wireframe as two presentations of the same shape. SetDrawEdges() on
        # the drawer has no effect on this GLES path - the renders come out byte-identical
        # with and without it - so edges are drawn as a second, overlaid wireframe. Without
        # them a boss on a plate is invisible from directly above, which is exactly the view
        # used to check where a feature sits.
        if shaded and VIEWER["edges"]:
            try:
                outline = AIS_Shape(shape)
                self.context.Display(outline, False)
                self.context.SetDisplayMode(outline, AIS_WireFrame, False)
                self.context.SetColor(outline, Quantity_Color(0.1, 0.1, 0.1, Quantity_TOC_RGB), False)
            except Exception:
                pass
        if shaded:
            # A warm neutral that reads well against the dark background and does not
            # pretend to be the part's real colour - the engine models geometry, not finish.
            colour = Quantity_Color(0.85, 0.72, 0.35, Quantity_TOC_RGB)
            try:
                self.context.SetColor(presentation, colour, False)
            except Exception:
                pass
        # Silhouettes as well as surfaces. Without edges a boss on a plate is invisible
        # from directly above - its top face is the same colour as the plate it stands on -
        # and the orthographic views, which are the ones you use to check a dimension, show
        # nothing but an outline.
        try:
            drawer = self.context.DefaultDrawer()
            drawer.SetDrawEdges(True)
            drawer.SetDrawSilhouettes(True)
        except Exception:
            pass
        if highlight:
            # Each face is its own presentation. Wrapping them in a compound first would be
            # tidier, but TopoDS.Compound() is a cast helper rather than a constructor in
            # this binding, so there is nothing to build an empty compound with.
            #
            # A face index the shape does not have is skipped rather than raising: the
            # caller is usually an agent that counted faces in an earlier turn, and the
            # model may have changed since.
            for face in highlight:
                marker = AIS_Shape(face)
                self.context.Display(marker, False)
                self.context.SetDisplayMode(marker, AIS_Shaded, False)
                self.context.SetColor(
                    marker, Quantity_Color(0.95, 0.2, 0.2, Quantity_TOC_RGB), False)
            # Deliberately not wrapped in try/except: a highlight that silently fails to
            # draw is worse than one that reports why, because the render still comes back
            # looking like a successful answer.
        _apply_viewer(self.view)
        direction = VIEW_DIRECTIONS.get(view, VIEW_DIRECTIONS["iso"])
        # SetProj resets the orientation, which would undo an orbit the caller just made.
        if fit:
            self.view.SetProj(*direction)
        # A viewport keeps its camera; a still is framed for the caller every time.
        if fit:
            # Fit the part, not the furniture. The grid is real geometry in this same context and
            # FitAll fits everything displayed, so with a 200 mm bed grid on a 10 mm part came
            # back as a speck: reported from the phone as a toy car 20 px across in an 800 px
            # frame, and filling it with the grid off.
            #
            # Erased across the fit and put straight back - cheaper and more certain than building
            # a Bnd_Box for FitAll to take, and it changes nothing else about the display.
            for grid in getattr(self, "_grids", []):
                try:
                    self.context.Erase(grid, False)
                except Exception:
                    pass
            self.view.FitAll(0.02)
            for grid in getattr(self, "_grids", []):
                try:
                    self.context.Display(grid, False)
                except Exception:
                    pass
        self.view.Redraw()
        return self.view


def _grid_shape(step, half=100.0):
    """A rectangular grid on the bed, as ordinary edges.

    Built rather than asked for: V3d_View's own grid killed this driver outright - SetGrid /
    SetGridActivity segfault inside OCCT's GL path, reproducibly, and a segfault cannot be
    caught in Python. Edges take the same route as the model itself, which this driver has
    been drawing all along.

    Cached by step: a rebuild per frame would put a hundred edges through the mesher sixty
    times a second for a backdrop that never changes.
    """
    from OCP.BRep import BRep_Builder
    from OCP.BRepBuilderAPI import BRepBuilderAPI_MakeEdge
    from OCP.TopoDS import TopoDS_Compound
    from OCP.gp import gp_Pnt

    key = ("grid", step, half)
    cached = _GL.get(key)
    if cached is not None:
        return cached
    builder = BRep_Builder()
    compound = TopoDS_Compound()
    builder.MakeCompound(compound)
    count = int(half / step)
    for i in range(-count, count + 1):
        offset = i * step
        builder.Add(compound, BRepBuilderAPI_MakeEdge(
            gp_Pnt(offset, -half, 0.0), gp_Pnt(offset, half, 0.0)).Edge())
        builder.Add(compound, BRepBuilderAPI_MakeEdge(
            gp_Pnt(-half, offset, 0.0), gp_Pnt(half, offset, 0.0)).Edge())
    _GL[key] = compound
    return compound


def _turntable_angles(direction):
    """The turntable angles of a view direction, in the convention _apply_turntable uses."""
    x, y, z = (float(component) for component in direction[:3])
    length = math.sqrt(x * x + y * y + z * z) or 1.0
    x, y, z = x / length, y / length, z / length
    return (math.degrees(math.atan2(x, -y)),
            math.degrees(math.asin(max(-1.0, min(1.0, z)))))


def _apply_turntable(view, yaw_deg, pitch_deg):
    """Point the camera from a spherical position, with world Z as up.

    SetProj takes the eye's offset from the part. The source is explicit that it sets the
    camera's *direction* and nothing else, which is what makes it the right call here: the
    distance and the centre survive, so turning the part cannot move it or resize it. The up
    then follows from the view's twist, which nothing in this engine sets any more - so a roll
    is impossible rather than corrected, the same rule the plate's own viewer follows, where the
    up vector is a constant world Z and the pitch is clamped short of the poles.

    The arcball this replaces (StartRotation/Rotation) rolled as it turned, which is what laid a
    part on its side whenever the phone was dragged diagonally.

    The convention matches the app's ModellingCamera - azimuth measured from -Y, elevation above
    the horizontal - so both viewers turn a model the same way.
    """
    azimuth = math.radians(yaw_deg)
    elevation = math.radians(pitch_deg)
    horizontal = math.cos(elevation)
    view.SetProj(horizontal * math.sin(azimuth), -horizontal * math.cos(azimuth),
                 math.sin(elevation))


def _apply_viewer(view):
    """Put the viewer's appearance on the view, and collect anything that refuses.

    Every one of these goes through OCCT's rendering path, where a call can be accepted and
    ignored - SetGrid segfaulted this driver outright, SetDrawEdges changed nothing, and MSAA
    changed nothing. Failures are recorded rather than swallowed, because a setting that reports
    success while doing nothing is worse than one that admits it, and the read-back lives in the
    viewer_settings command so a caller can ask the viewer what it is actually doing.
    """
    from OCP.Quantity import Quantity_Color, Quantity_TOC_RGB
    from OCP.Graphic3d import Graphic3d_Camera

    def note(name, exc):
        VIEWER.setdefault("_errors", {})[name] = "%s: %s" % (type(exc).__name__, exc)

    colour = BACKGROUND_COLOURS.get(VIEWER["background"], BACKGROUND_COLOURS["dark"])
    try:
        view.SetBackgroundColor(
            Quantity_Color(colour[0], colour[1], colour[2], Quantity_TOC_RGB))
    except Exception as exc:
        note("background", exc)

    # The little axis cross in the corner, which tells the user which way is which without a
    # second click.
    try:
        if VIEWER["axes"]:
            from OCP.Aspect import Aspect_TypeOfTriedronPosition

            position = getattr(Aspect_TypeOfTriedronPosition, "Aspect_TOTP_LEFT_LOWER",
                               getattr(Aspect_TypeOfTriedronPosition, "Aspect_TOTP_CENTER"))
            view.TriedronDisplay(position,
                                 Quantity_Color(0.9, 0.9, 0.9, Quantity_TOC_RGB), 0.08)
        else:
            view.TriedronErase()
    except Exception as exc:
        note("axes", exc)

    # Orthographic for judging a dimension, perspective for looking at a part.
    try:
        camera = view.Camera()
        camera.SetProjectionType(
            Graphic3d_Camera.Projection_Orthographic
            if VIEWER["projection"] == "orthographic"
            else Graphic3d_Camera.Projection_Perspective)
        # Camera() hands back a copy through this binding, so the change has to be handed back
        # to the view or it dies with the local handle. This is what made an orthographic
        # setting do nothing until it was measured.
        view.SetCamera(camera)
        view.Invalidate()
    except Exception as exc:
        note("projection", exc)

    # Multisampling: accepted and ignored here. Anti-aliasing is done by supersampling the frame
    # in _write_view instead, which the measurements can see.
    try:
        view.ChangeRenderingParams().NbMsaaSamples = 4 if VIEWER["antialiasing"] else 0
    except Exception as exc:
        note("msaa", exc)


def _renderer(width, height):
    """The cached viewer, rebuilt if the size changed."""
    key = "renderer"
    renderer = _GL.get(key)
    if renderer is None:
        renderer = _Renderer(width, height)
        _GL[key] = renderer
    return renderer


# --------------------------------------------------------------------------
# The server
# --------------------------------------------------------------------------


def _ray_hits(shape, point, direction):
    """Sorted distances where a ray enters and leaves `shape`.

    This is the thickness measurement, and it is done this way because OCCT 7.9.3 has no
    thickness class at all - there is no ShapeAnalysis_Thickness_Maker in the source tree,
    which was checked rather than assumed. Casting a line through the solid and taking the
    gap between consecutive intersections gives the wall it passes through, which is the
    number that decides whether a part prints.
    """
    from OCP.IntCurvesFace import IntCurvesFace_ShapeIntersector
    from OCP.gp import gp_Lin
    inter = IntCurvesFace_ShapeIntersector()
    inter.Load(shape.wrapped if hasattr(shape, 'wrapped') else shape, 1e-6)
    inter.Perform(gp_Lin(point, direction), 0.0, 1e6)
    return sorted(inter.Pnt(i + 1).Distance(point) for i in range(inter.NbPnt()))


def _wall_thickness(shape, samples=14):
    """Wall thickness through the part, sampled along all three axes.

    Casting only two directions misses the wall that matters: a 0.3 mm fin is thin in Y and
    20 mm along X, so an X-ray reports the fin as solid and the part as printable. Every axis
    is cast and the smallest gap found anywhere is the answer, because a part is only as
    printable as its thinnest wall.
    """
    box = shape.bounding_box()
    lo, hi = box.min, box.max
    span = (hi.X - lo.X, hi.Y - lo.Y, hi.Z - lo.Z)
    gaps = []
    axes = [
        ((1, 0, 0), lambda f, g: _gp_pnt(lo.X - 1.0, lo.Y + span[1] * f, lo.Z + span[2] * g)),
        ((0, 1, 0), lambda f, g: _gp_pnt(lo.X + span[0] * f, lo.Y - 1.0, lo.Z + span[2] * g)),
        ((0, 0, -1), lambda f, g: _gp_pnt(lo.X + span[0] * f, lo.Y + span[1] * g, hi.Z + 1.0)),
    ]
    for direction, origin in axes:
        for i in range(samples):
            for j in range(samples):
                f = (i + 0.5) / samples
                g = (j + 0.5) / samples
                hits = _ray_hits(shape, origin(f, g), _gp_dir(*direction))
                gaps += [hits[k + 1] - hits[k] for k in range(0, len(hits) - 1, 2)]
    return gaps


def _gp_pnt(x, y, z):
    from OCP.gp import gp_Pnt
    return gp_Pnt(float(x), float(y), float(z))


def _gp_dir(x, y, z):
    from OCP.gp import gp_Dir
    return gp_Dir(float(x), float(y), float(z))


def _overhangs(shape, threshold_deg=45.0, bed_z=None):
    """Faces that need support, and how much of the part they are.

    A face is an overhang when its outward normal points downward by more than the
    threshold from horizontal - the same rule a slicer uses, measured on the exact
    surface rather than on the triangle mesh the slicer will see.
    """
    import math
    down = math.cos(math.radians(90.0 - threshold_deg))
    faces = []
    total = 0.0
    for face in shape.faces():
        try:
            area = face.area
        except Exception:
            continue
        total += area
        try:
            normal = face.normal_at()
        except Exception:
            continue
        # normal_at() may return a list on a shell; take the first.
        if isinstance(normal, (list, tuple)):
            normal = normal[0]
        if -normal.Z > down:
            faces.append({
                "area": round(area, 4),
                "z": round(face.center().Z, 4),
                "tilt_from_horizontal_deg": round(math.degrees(math.asin(max(-1.0, min(1.0, -normal.Z)))), 2),
            })
    faces.sort(key=lambda f: -f["area"])
    return {"count": len(faces), "area": round(sum(f["area"] for f in faces), 4),
            "fraction_of_surface": round((sum(f["area"] for f in faces) / total) if total else 0.0, 4),
            "largest": faces[:8]}


def _triangulate(shape, tolerance=0.1):
    """Mesh the shape and yield (points, triangles) in world coordinates.

    Every face is meshed and transformed by its own location, because a face triangulation
    is stored in the face's local frame - reading it without the location puts the triangles
    somewhere the part is not.
    """
    from OCP.BRepMesh import BRepMesh_IncrementalMesh
    from OCP.BRep import BRep_Tool
    from OCP.TopoDS import TopoDS
    from OCP.TopExp import TopExp_Explorer
    from OCP.TopAbs import TopAbs_FACE, TopAbs_REVERSED
    from OCP.TopLoc import TopLoc_Location
    from OCP.gp import gp_Pnt

    wrapped = shape.wrapped if hasattr(shape, 'wrapped') else shape
    BRepMesh_IncrementalMesh(wrapped, float(tolerance))

    points = []
    triangles = []
    explorer = TopExp_Explorer(wrapped, TopAbs_FACE)
    while explorer.More():
        face = TopoDS.Face(explorer.Current())
        location = TopLoc_Location()
        tri = BRep_Tool.Triangulation_s(face, location)
        if tri is not None:
            trsf = location.Transformation()
            base = len(points)
            nodes = tri.MapNodeArray()
            for i in range(1, nodes.Length() + 1):
                p = nodes.Value(i).Transformed(trsf)
                points.append((p.X(), p.Y(), p.Z()))
            reversed_face = face.Orientation() == TopAbs_REVERSED
            for i in range(1, tri.NbTriangles() + 1):
                a, b, c = tri.Triangle(i).Get()
                # Winding matters: a face whose orientation is reversed faces the other way,
                # and a viewer that trusts the winding will light it wrong.
                if reversed_face:
                    b, c = c, b
                triangles.append((base + a, base + b, base + c))
        explorer.Next()
    return points, triangles


def _write_obj(shape, path, tolerance=0.1):
    """Write a Wavefront OBJ. build123d has an exporter but it needs ComputeNormals, which
    this binding does not have - and the triangles are readable without it, so writing them
    out directly is shorter than another rebuild.
    """
    points, triangles = _triangulate(shape, tolerance)
    if not points:
        raise RuntimeError("nothing to export: the shape produced no triangles")
    # OBJ indices are 1-based and per-file, which is why the triangulation carries an offset.
    with open(path, "w") as handle:
        handle.write("# TrioSlicer CAD engine\n")
        for x, y, z in points:
            handle.write("v %.6f %.6f %.6f\n" % (x, y, z))
        for a, b, c in triangles:
            handle.write("f %d %d %d\n" % (a, b, c))
    return len(points), len(triangles)

def _describe_at(shape, x, y, z):
    """Which face, edge or vertex of the exact shape is at a point from the mesh.

    The viewer picks triangles and a triangle approximates the surface the engine actually
    holds. Answering with the triangle would answer about the mesh; this asks the exact
    geometry instead, so "this face" means the B-rep face that gets filleted.
    """
    from OCP.BRepExtrema import BRepExtrema_DistShapeShape
    from OCP.BRepBuilderAPI import BRepBuilderAPI_MakeVertex
    from OCP.gp import gp_Pnt
    from OCP.TopAbs import TopAbs_FACE, TopAbs_EDGE, TopAbs_VERTEX

    vertex = BRepBuilderAPI_MakeVertex(gp_Pnt(float(x), float(y), float(z))).Vertex()
    wrapped = shape.wrapped if hasattr(shape, 'wrapped') else shape
    # The class has no two-shape constructor and no Perform - it is created empty, given
    # its shapes, and run with PerformDist, which is one of the bindings added by hand.
    probe = BRepExtrema_DistShapeShape()
    probe.LoadS1(vertex)
    probe.LoadS2(wrapped)
    probe.PerformDist()
    if not probe.IsDone():
        raise RuntimeError("could not measure the shape from that point")
    distance = probe.Value()

    # SupportOnShape2 gives the entity, and its ShapeType is a TopAbs_ShapeEnum. The
    # SupportTypeShape2 beside it is a BRepExtrema_SupportType - a different enum that
    # says how the solution touches the shape, not what kind of shape it is.
    sub = probe.SupportOnShape2(1)
    kind = sub.ShapeType()
    if kind == TopAbs_FACE:
        label, pool = "face", list(shape.faces())
    elif kind == TopAbs_EDGE:
        label, pool = "edge", list(shape.edges())
    elif kind == TopAbs_VERTEX:
        label, pool = "vertex", list(shape.vertices())
    else:
        return {"kind": "none", "distance_mm": round(distance, 6)}

    index = None
    for position, candidate in enumerate(pool):
        inner = candidate.wrapped if hasattr(candidate, 'wrapped') else candidate
        if inner.IsSame(sub):
            index = position
            break

    info = {"kind": label, "index": index, "distance_mm": round(distance, 6)}
    # A face reports how it is oriented and how big it is, because that is what decides
    # whether it can be built on, drilled into, or needs support under it.
    if label == "face" and index is not None:
        try:
            face = pool[index]
            normal = face.normal_at()
            if isinstance(normal, (list, tuple)):
                normal = normal[0]
            info["normal"] = [round(normal.X, 4), round(normal.Y, 4), round(normal.Z, 4)]
            info["area_mm2"] = round(face.area, 4)
            centre = face.center()
            info["centre"] = [round(centre.X, 4), round(centre.Y, 4), round(centre.Z, 4)]
        except Exception:
            pass
    return info


def _write_view(viewport, width, height, path, output=None):
    """Write what the viewport is showing as a PNG, straight out of the render buffer.

    ToPixMap is the render: it draws the scene offscreen at this size and asks the GL driver
    for the pixels back. Two things about the asking were wrong.

    The pixels were requested as RGB. The framebuffer is RGBA, so OCCT took its slow path -
    one glReadPixels per row and a software conversion of every pixel
    (OpenGl_FrameBuffer::Buffer(), the toConvRgba2Rgb branch). Asking for RGBA matches the
    framebuffer and takes the single batch glReadPixels of the whole image.

    And the buffer could not be read from Python at all: Image_PixMap.Data() returns the
    byte it points at rather than an address - pywrap binds Standard_Byte* as its pointee -
    so Data() on a fresh frame came back as 77, the top-left pixel's blue channel, and a
    ctypes.string_at on it segfaulted the engine. The frame therefore went out through
    OCCT's Save(), which writes a raw PPM whatever the file is called, and back in through
    Pillow. ReadBytes() is the accessor that was missing (add-image-pixels.py adds it to
    the payload), so the frame is now GL buffer -> Python bytes -> PNG: no PPM, no second
    file, no serializer.

    Rows arrive bottom-up - how OpenGL stores them, and what the batch copy requires (a
    top-down image sends OCCT back to reading row by row) - so the raw decoder is told -1
    and flips them as it reads. The stride is the pixmap's own SizeRowBytes(): the allocator
    may pad a row, and assuming SizeX() * 4 would shear the picture.
    """
    from OCP.Image import Image_AlienPixMap
    from PIL import Image

    pixmap = Image_AlienPixMap()
    if hasattr(pixmap, "ReadBytes"):
        from OCP.Graphic3d import Graphic3d_BT_RGBA

        if not viewport.ToPixMap(pixmap, width, height, Graphic3d_BT_RGBA):
            raise RuntimeError("OCCT could not render the viewport")
        frame = Image.frombytes(
            "RGBA", (int(width), int(height)), pixmap.ReadBytes(),
            "raw", "RGBA", int(pixmap.SizeRowBytes()), -1)
        frame = frame.convert("RGB")
        # Anti-aliasing, done on the way out: the view is drawn larger and averaged down. MSAA
        # is accepted and ignored on this driver - the frame came back byte-identical with
        # NbMsaaSamples at 0 and at 4 - so smoothing has to happen here or not at all.
        if output and (int(output[0]), int(output[1])) != (int(width), int(height)):
            frame = frame.resize((int(output[0]), int(output[1])), Image.LANCZOS)
        frame.save(path, format="PNG")
        return

    # A payload older than the ReadBytes binding: keep the PPM round trip so a new script
    # against an old payload still draws, rather than failing on a missing method.
    ppm = path + ".ppm"
    try:
        if not viewport.ToPixMap(pixmap, width, height):
            raise RuntimeError("OCCT could not render the viewport")
        if not pixmap.Save(ppm):
            raise RuntimeError("OCCT could not write the viewport pixels")
        legacy = Image.open(ppm).convert("RGB")
        if output and (int(output[0]), int(output[1])) != (int(width), int(height)):
            legacy = legacy.resize((int(output[0]), int(output[1])), Image.LANCZOS)
        legacy.save(path, format="PNG")
    finally:
        try:
            os.remove(ppm)
        except OSError:
            pass


def _pick_at(renderer, x, y, shape):
    """Which face of the exact shape is under a screen point.

    AIS picks against what it is displaying; the entity it names is then matched back to the
    shape's own face list, so the answer is an index the other commands take. Detection is
    enough - a tap does not need a persistent selection, only to know what was under it.
    """
    from OCP.TopAbs import TopAbs_FACE, TopAbs_EDGE, TopAbs_VERTEX

    context = renderer.context
    # Activate face picking on the presentation. An AIS_Shape displays in mode 0, which
    # detects the whole solid - so a tap reports a SOLID and no face is ever named. Mode 4
    # is AIS_Shape's face mode. The overlay shapes for a highlight are deliberately left
    # alone; only the part itself should be pickable.
    presentation = getattr(renderer, 'presentation', None)
    if presentation is not None:
        try:
            context.SetSelectionModeActive(presentation, 4, True)
        except Exception:
            try:
                context.Activate(presentation, 4)
            except Exception:
                pass
    # OCCT view coordinates start at the bottom left; a touch comes from the top left.
    height = renderer.height
    context.MoveTo(int(x), int(height) - int(y), renderer.view, True)
    if not context.HasDetected():
        return {"kind": "none", "x": int(x), "y": int(y)}

    sub = context.DetectedShape()
    kind = sub.ShapeType()
    if kind == TopAbs_FACE:
        label, pool = "face", list(shape.faces())
    elif kind == TopAbs_EDGE:
        label, pool = "edge", list(shape.edges())
    elif kind == TopAbs_VERTEX:
        label, pool = "vertex", list(shape.vertices())
    else:
        # A whole-shape hit is a real answer even when no face was named.
        return {"kind": "shape", "x": int(x), "y": int(y)}

    index = None
    for position, candidate in enumerate(pool):
        inner = candidate.wrapped if hasattr(candidate, 'wrapped') else candidate
        if inner.IsSame(sub):
            index = position
            break

    info = {"kind": label, "index": index, "x": int(x), "y": int(y)}
    if label == "face" and index is not None:
        try:
            face = pool[index]
            normal = face.normal_at()
            if isinstance(normal, (list, tuple)):
                normal = normal[0]
            info["normal"] = [round(normal.X, 4), round(normal.Y, 4), round(normal.Z, 4)]
            info["area_mm2"] = round(face.area, 4)
            centre = face.center()
            info["centre"] = [round(centre.X, 4), round(centre.Y, 4), round(centre.Z, 4)]
        except Exception:
            pass
    return info

class CadMCPServer:
    def __init__(self, host='localhost', port=DEFAULT_PORT, token=None,
                 status_path=None):
        self.host = host
        self.port = port
        # Shared secret the app writes next to this engine and sends with every
        # request. Without it any co-installed app could reach 127.0.0.1 and run
        # Python as this app's uid. None means "no token file", and start()
        # refuses to serve in that case.
        self.token = token
        self.status_path = status_path
        self.start_error = None
        self.running = False
        self.socket = None
        self.server_thread = None
        self.command_queue = queue.Queue()
        self._clients = set()
        self._clients_lock = threading.Lock()
        self._shutdown_requested = False
        # The camera as a turntable: azimuth around the world's vertical axis and elevation above
        # the horizontal plane, both absolute rather than drag deltas, so a frame asks for a
        # camera rather than a movement. Seeded from the view a fit starts at, so the first turn
        # continues from what is already on screen.
        self._yaw_deg, self._pitch_deg = _turntable_angles(VIEW_DIRECTIONS["iso"])
        self._executing_since = None
        self._busy_lock = threading.Lock()

    # -- lifecycle ---------------------------------------------------------

    def start(self):
        if self.running:
            print("CadMCP slim: server already running")
            return
        if not self.token:
            # Fail closed: _authorized() would have nothing to check a request
            # against, so serving would let any co-installed app run Python as
            # this process's uid. Refuse, and record why for the app to read.
            self.start_error = "no MCP token loaded; refusing to serve"
            print("CadMCP slim: " + self.start_error)
            write_status(self.status_path, {
                "running": False,
                "port": self.port,
                "error": self.start_error,
            })
            return
        self.running = True
        try:
            self.socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            self.socket.bind((self.host, self.port))
            self.socket.listen(5)
            if self.port == 0:
                self.port = self.socket.getsockname()[1]
            self.server_thread = threading.Thread(target=self._server_loop, daemon=True)
            self.server_thread.start()
            print(f"CadMCP slim server started on {self.host}:{self.port}")
            write_status(self.status_path, {
                "running": True,
                "port": self.port,
                "pid": os.getpid(),
                "auth": bool(self.token),
            })
        except Exception as e:
            # Remember the reason: the caller parks instead of returning, and
            # the app reads the status file to say why the engine is not
            # answering.
            self.start_error = str(e)
            print(f"CadMCP slim start failed: {e}")
            write_status(self.status_path,
                         {"running": False, "port": self.port, "error": str(e)})
            self.stop()

    def stop(self):
        self.running = False
        if self.socket:
            try:
                # shutdown() wakes a blocked accept() immediately where the
                # platform supports it; close() alone can leave the port bound
                # until that accept returns.
                self.socket.shutdown(socket.SHUT_RDWR)
            except Exception:
                pass
            try:
                self.socket.close()
            except Exception:
                pass
            self.socket = None
        with self._clients_lock:
            clients = list(self._clients)
            self._clients.clear()
        for c in clients:
            try:
                c.shutdown(socket.SHUT_RDWR)
            except Exception:
                pass
            try:
                c.close()
            except Exception:
                pass
        while True:
            try:
                self.command_queue.get_nowait()
            except queue.Empty:
                break
        write_status(self.status_path, {"running": False, "port": self.port})

    # -- transport ---------------------------------------------------------

    def _server_loop(self):
        """Accept connections on a background thread.

        Requests are parsed and authorized here, then queued; they are *run* on
        the main thread by run_headless(). OCP is not documented as thread-safe
        and the Blender engine has the same rule for bpy, so one command at a
        time on one thread is the contract both engines share.
        """
        self.socket.settimeout(1.0)
        while self.running:
            try:
                client, _address = self.socket.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            except Exception as e:
                print(f"CadMCP slim: accept error {e}")
                continue
            with self._clients_lock:
                self._clients.add(client)
            threading.Thread(target=self._handle_client, args=(client,),
                             daemon=True).start()

    def _reply(self, client, message):
        try:
            client.sendall(json.dumps({"status": "error", "message": message}).encode("utf-8"))
        except Exception:
            pass

    def _handle_client(self, client):
        client.settimeout(1.0)
        buffer = b""
        try:
            while self.running:
                try:
                    chunk = client.recv(65536)
                except socket.timeout:
                    continue
                except Exception:
                    break
                if not chunk:
                    break
                buffer += chunk
                if len(buffer) > MAX_REQUEST_BYTES:
                    self._reply(client, "request too large")
                    break
                while True:
                    newline = buffer.find(b"\n")
                    if newline >= 0:
                        payload, buffer = buffer[:newline], buffer[newline + 1:]
                    else:
                        # A single write with no terminator is what the app
                        # does; try it whole, and keep waiting if it is not yet
                        # valid JSON.
                        payload, buffer = buffer, b""
                    if not payload.strip():
                        break
                    try:
                        command = json.loads(payload.decode("utf-8"))
                    except (ValueError, UnicodeDecodeError):
                        if newline < 0:
                            buffer = payload
                            break
                        self._reply(client, "request is not valid JSON")
                        continue
                    self._dispatch(command, client)
                    if newline < 0:
                        break
        finally:
            with self._clients_lock:
                self._clients.discard(client)
            try:
                client.close()
            except Exception:
                pass

    def _authorized(self, command):
        # Fail closed. start() already refuses a tokenless server; this is the
        # second line, so no future caller can re-open the port by skipping it.
        if not self.token:
            return False
        return command.get("token") == self.token

    def _dispatch(self, command, client):
        """Queue a parsed request, or answer it here when it cannot run."""
        if not isinstance(command, dict):
            self._reply(client, "request must be a JSON object")
            return
        if not self._authorized(command):
            self._reply(client, "unauthorized: send the token from cad_mcp_token.txt")
            return
        with self._busy_lock:
            started = self._executing_since
        if started is not None and command.get("type") != "ping":
            waited = time.time() - started
            if waited > BUSY_ANSWER_AFTER_SECONDS:
                self._reply(client, "engine busy in another command for %.0fs" % waited)
                return
        self.command_queue.put((command, client))

    def _drain_command_queue(self):
        while True:
            try:
                command, client = self.command_queue.get_nowait()
            except queue.Empty:
                return
            with self._busy_lock:
                self._executing_since = time.time()
            try:
                response = self.execute_command(command)
            finally:
                with self._busy_lock:
                    self._executing_since = None
            try:
                client.sendall(json.dumps(response).encode("utf-8"))
            except Exception:
                print("CadMCP slim: send failed, client gone")
            finally:
                try:
                    client.close()
                except Exception:
                    pass
                with self._clients_lock:
                    self._clients.discard(client)
            if self._shutdown_requested:
                self.running = False
                return

    def run_headless(self):
        """Drain the queue on the calling thread for the life of the process."""
        while self.running:
            self._drain_command_queue()
            time.sleep(0.02)
        return 0

    # -- commands ----------------------------------------------------------

    def execute_command(self, command):
        try:
            return self._execute_command_internal(command)
        except Exception as e:
            print(f"CadMCP slim: command error {e}")
            traceback.print_exc()
            return {"status": "error", "message": str(e)}

    def _execute_command_internal(self, command):
        cmd_type = command.get("type")
        params = command.get("params", {})

        # Trivial liveness check. Touches no geometry, so a successful ping
        # alongside a failing command isolates kernel access from transport.
        if cmd_type == "ping":
            return {"status": "success", "result": {"pong": True}}

        # Graceful stop: the response is sent first, then the drain loop exits.
        if cmd_type == "shutdown":
            self._shutdown_requested = True
            return {"status": "success", "result": {"stopping": True}}

        handlers = {
            "execute_code": self.execute_code,
            "get_scene_info": self.get_scene_info,
            "get_object_info": self.get_object_info,
            "get_metrics": self.get_metrics,
            "export_step": self.export_step,
            "export_stl": self.export_stl,
            "export_mesh": self.export_mesh,
            "describe_at": self.describe_at,
            "view": self.view,
            "analyze": self.analyze,
            "section": self.section,
            "import_file": self.import_file,
            "clearance": self.clearance,
            "viewer_settings": self.viewer_settings,
            "render": self.render,
            "get_addon_info": self.get_addon_info,
        }
        handler = handlers.get(cmd_type)
        if handler is None:
            return {"status": "error", "message": f"Unknown command type: {cmd_type}"}
        try:
            result = handler(**params)
            return {"status": "success", "result": result}
        except Exception as e:
            print(f"CadMCP slim: error in {cmd_type}: {e}")
            traceback.print_exc()
            return {"status": "error", "message": str(e)}

    def execute_code(self, code):
        """Execute arbitrary build123d/OCP code. The generated-script entrypoint.

        The namespace pre-imports build123d and OCP and exposes the scene
        helpers, so a script can go straight to modelling. Returns
        {"executed": True, "result": stdout}; exceptions propagate to the
        dispatcher, which answers {"status": "error", "message": ...} -- the same
        transport contract as the Blender engine.
        """
        k = kernel()
        namespace = {
            "json": json,
            "os": os,
            "math": math,
            "scene": SCENE,
            "add": SCENE.add,
            "get": SCENE.get,
            "remove": SCENE.remove,
            "shapes": SCENE.names,
            "OCP": k["ocp"],
            "build123d": k["build123d"],
        }
        if k["build123d"] is not None:
            # The common names, so a script can start modelling immediately.
            for name in ("Box", "Cylinder", "Sphere", "Cone", "Torus", "Plane",
                         "Pos", "Location", "Axis", "fillet", "chamfer",
                         "extrude", "revolve", "loft", "sweep", "export_step",
                         "export_stl", "import_step", "import_brep"):
                if hasattr(k["build123d"], name):
                    namespace[name] = getattr(k["build123d"], name)
        capture_buffer = io.StringIO()
        with redirect_stdout(capture_buffer):
            exec(code, namespace)
        return {"executed": True, "result": capture_buffer.getvalue()}

    def get_scene_info(self):
        return {
            "shape_count": len(SCENE.names()),
            "shapes": [_summary(name, shape) for name, shape in SCENE.items()],
            "kernel": self._kernel_state(),
        }

    def get_object_info(self, name=""):
        shape = SCENE.get(name)
        info = _summary(name, shape)
        wrapped = getattr(shape, "wrapped", None)
        if wrapped is not None:
            try:
                from OCP.TopExp import TopExp_Explorer
                from OCP.TopAbs import (TopAbs_EDGE, TopAbs_FACE, TopAbs_SOLID,
                                        TopAbs_VERTEX)
                for label, kind in (("solids", TopAbs_SOLID), ("faces", TopAbs_FACE),
                                    ("edges", TopAbs_EDGE), ("vertices", TopAbs_VERTEX)):
                    # Distinct sub-shapes, not visits. TopExp_Explorer walks the B-rep tree and
                    # yields a shared edge once per face that uses it, so a 20 mm cube came out as
                    # 24 edges and 48 vertices where build123d itself says 12 and 8. build123d
                    # dedups the same way, by hash, for the same reason.
                    seen = set()
                    explorer = TopExp_Explorer(wrapped, kind)
                    while explorer.More():
                        seen.add(hash(explorer.Current()))
                        explorer.Next()
                    info[label] = len(seen)
            except Exception as exc:
                info["topology_error"] = str(exc)
        return info

    def get_metrics(self, name=""):
        """Volume, area, centroid, bounding box and topology counts."""
        if name:
            shape = SCENE.get(name)
            return {"name": name, **_metrics(shape)}
        return {
            "shape_count": len(SCENE.names()),
            "shapes": [{"name": n, **_metrics(s)} for n, s in SCENE.items()],
        }

    def export_step(self, filepath, name=""):
        if kernel()["build123d"] is None:
            raise RuntimeError(kernel()["error"] or "build123d is unavailable")
        label, shape = _resolve_target(name)
        b3d = kernel()["build123d"]
        written = _publish(filepath, lambda temp: b3d.export_step(shape, temp))
        return {"filepath": filepath, "bytes": written, "shapes": label}

    def export_stl(self, filepath, name="", tolerance=0.1):
        if kernel()["build123d"] is None:
            raise RuntimeError(kernel()["error"] or "build123d is unavailable")
        label, shape = _resolve_target(name)
        b3d = kernel()["build123d"]
        written = _publish(
            filepath,
            lambda temp: b3d.export_stl(shape, temp, tolerance=tolerance),
        )
        return {"filepath": filepath, "bytes": written, "shapes": label}

    def export_mesh(self, filepath, name="", format=""):
        """Write the scene as BREP, glTF or OBJ, chosen by the file extension.

        BREP is the exact geometry in OCCT's own form - lossless and re-importable, which
        neither STEP nor STL is as a round trip. glTF and OBJ are for looking at the model
        elsewhere.
        """
        if kernel()["build123d"] is None:
            raise RuntimeError(kernel()["error"] or "build123d is unavailable")
        label, shape = _resolve_target(name)
        b3d = kernel()["build123d"]
        extension = ("." + format.lstrip(".")).lower() if format else os.path.splitext(filepath)[1].lower()
        # A format that disagrees with the file's own extension is refused rather than written.
        # The app announces and routes an export by its extension, so BREP bytes in a ".stl" were
        # handed to the STL importer and failed there, far from the call that caused it.
        named = os.path.splitext(filepath)[1].lower()
        if format and named and named != extension:
            raise ValueError(
                "format %r does not match the file name %r; rename it or drop the argument"
                % (extension, named))
        writers = {
            ".brep": lambda temp: b3d.export_brep(shape, temp),
            ".gltf": lambda temp: b3d.export_gltf(shape, temp),
            ".glb": lambda temp: b3d.export_gltf(shape, temp),
            # Not the build123d exporter: it needs Poly_Triangulation.ComputeNormals,
            # which this binding does not have. The triangles are readable without it.
            ".obj": lambda temp: _write_obj(shape, temp),
        }
        writer = writers.get(extension)
        if writer is None:
            raise ValueError("cannot export %r; supported: %s" % (extension, ", ".join(sorted(writers))))
        written = _publish(filepath, writer)
        return {"filepath": filepath, "bytes": written, "shapes": label, "format": extension}

    def describe_at(self, x, y, z, name=""):
        """What the user clicked on, in the engine's own terms.

        The app's viewer picks a triangle; this answers with the exact face, edge or vertex
        nearest that point, its index, and - for a face - its normal and area. It is what
        turns "this one here" into something the next command can act on.
        """
        if kernel()["build123d"] is None:
            raise RuntimeError(kernel()["error"] or "build123d is unavailable")
        label, shape = _resolve_target(name)
        info = _describe_at(shape, x, y, z)
        info["shapes"] = label
        info["picked_at"] = [round(float(v), 4) for v in (x, y, z)]
        # A click that lands nowhere near the part is a mis-tap or a stale view, and saying
        # so beats naming the nearest face of a model that has since changed.
        if info["distance_mm"] > 1.0:
            info["warning"] = ("the nearest geometry is %.2f mm away; the model may have "
                               "changed since the view was drawn" % info["distance_mm"])
        return info

    def viewer_settings(self, tessellation=None, background=None, grid=None,
                        grid_step_mm=None, axes=None, projection=None, edges=None,
                        antialiasing=None):
        """The viewer's appearance: what a frame looks like, not what is in it.

        Every argument is optional; what is given is applied and nothing else is touched. The
        answer carries the settings *and* what the viewer reports back, because several of these
        go through OCCT's rendering path, where a value can be accepted and quietly ignored -
        SetDrawEdges did exactly that on this GLES driver, and the renders came out byte-identical
        with and without it. `readback` is the viewer's own answer; grid and the axis cross have
        no getter in this binding, so those two are honestly absent from it and are checked by
        looking at a frame.
        """
        if tessellation is not None:
            if tessellation not in TESSELLATION_LEVELS:
                raise ValueError("tessellation must be one of %s"
                                 % ", ".join(sorted(TESSELLATION_LEVELS)))
            VIEWER["tessellation"] = tessellation
        if background is not None:
            if background not in BACKGROUND_COLOURS:
                raise ValueError("background must be one of %s"
                                 % ", ".join(sorted(BACKGROUND_COLOURS)))
            VIEWER["background"] = background
        if grid is not None:
            VIEWER["grid"] = bool(grid)
        if grid_step_mm is not None:
            VIEWER["grid_step_mm"] = max(0.1, float(grid_step_mm))
        if axes is not None:
            VIEWER["axes"] = bool(axes)
        if projection is not None:
            if projection not in ("perspective", "orthographic"):
                raise ValueError("projection must be perspective or orthographic")
            VIEWER["projection"] = projection
        if edges is not None:
            VIEWER["edges"] = bool(edges)
        if antialiasing is not None:
            VIEWER["antialiasing"] = bool(antialiasing)

        # The OCCT view belongs to the cached renderer, not to this class - the `view` command
        # method has that name here, which is a trap worth the comment. With no renderer yet
        # there is no view to talk to, and nothing to do: every setting is consulted when a
        # frame is built, so it will be honoured by the first one.
        renderer = _GL.get("renderer")
        viewer = getattr(renderer, "view", None)
        readback = {"applied_to_view": viewer is not None}
        if viewer is not None:
            _apply_viewer(viewer)
            viewer.Redraw()
            try:
                readback["projection"] = str(viewer.Camera().ProjectionType()).split(".")[-1]
            except Exception:
                pass
            try:
                readback["msaa_samples"] = int(viewer.RenderingParams().NbMsaaSamples)
            except Exception:
                pass
            try:
                colour = viewer.BackgroundColor()
                readback["background_rgb"] = [round(colour.Red(), 3), round(colour.Green(), 3),
                                              round(colour.Blue(), 3)]
            except Exception:
                pass
            try:
                presentation = getattr(renderer, "presentation", None)
                if presentation is not None:
                    readback["deviation_coefficient"] = float(
                        presentation.Attributes().DeviationCoefficient())
            except Exception:
                pass
        if VIEWER.get("_errors"):
            readback["errors"] = dict(VIEWER["_errors"])
            VIEWER.pop("_errors", None)
        return {"settings": {k: v for k, v in VIEWER.items() if not k.startswith("_")},
                "readback": readback}

    def view(self, filepath, name="", turn_yaw=0.0, turn_pitch=0.0,
             pan_dx=0.0, pan_dy=0.0, zoom=1.0,
             select_x=None, select_y=None, reset=False,
             width=640, height=640, shaded=True, orientation=None,
             antialiasing=None):
        """The engine's own viewport: turn it, or pick a surface in it.

        This is the CAD screen's view. It is not a picture rendered for a report - the camera
        persists between calls, so a drag rotates the part the way a desktop CAD application
        would, and a tap goes to AIS to find which face is under the finger.

        The pick answers with the exact face, not a triangle: the mesh OCCT rasterises is
        only how the B-rep gets drawn, and what comes back is the B-rep.
        """
        if kernel()["build123d"] is None:
            raise RuntimeError(kernel()["error"] or "build123d is unavailable")
        label, shape = _resolve_target(name)
        width = max(self.RENDER_MIN, min(self.RENDER_MAX, int(width)))
        height = max(self.RENDER_MIN, min(self.RENDER_MAX, int(height)))

        # Anti-aliasing by supersampling: the frame is drawn twice as wide and tall and
        # averaged down on the way out. MSAA is accepted and ignored on this driver - the frame
        # came back byte-identical with NbMsaaSamples at 0 and at 4 - so the smoothing has to
        # happen here or not at all. Everything the caller sends is in the frame they asked for,
        # so drag deltas and tap coordinates are scaled on the way in and the answer on the way
        # out.
        # The caller can turn it off per frame, and the app does: a frame drawn while a finger
        # is moving wants speed, the frame that settles under it wants the smooth edges. On this
        # device that is the difference between a viewport and a slideshow on a heavy part.
        aa = 2 if (VIEWER["antialiasing"] if antialiasing is None else antialiasing) else 1
        # The cap is on the pixmap, not on the request: 2048x2048 RGBA is the 16 MB the comment
        # below names, and rendering at width*aa made a 2048-wide request a 4096-wide pixmap -
        # 64 MB, plus the same again for the read-back copy. Supersampling is given up rather
        # than giving up the frame - and only after aa is known, or this reads it unbound.
        if width * aa > self.RENDER_MAX or height * aa > self.RENDER_MAX:
            aa = 1
        renderer = _renderer(width * aa, height * aa)
        # fit=False keeps the camera. That is the whole difference between a viewport and a
        # set of stills: FitAll() would undo every rotation the user just made.
        # A named view is a preset: it puts the camera where that view lives and frames the
        # part again, which is what pressing Front or Top means.
        viewport = renderer.render(getattr(shape, "wrapped", shape), width * aa, height * aa,
                                   orientation or "iso", bool(shaded),
                                   fit=bool(reset or orientation))

        if reset or orientation:
            # The render above pointed the camera at a named view and framed the part. The
            # turntable's angles are taken from the direction it actually applied, so the next
            # drag continues from what is on screen instead of jumping back to wherever the
            # angles were left - which is what makes a preset feel like it undid itself.
            self._yaw_deg, self._pitch_deg = _turntable_angles(
                VIEW_DIRECTIONS.get(orientation or "iso", VIEW_DIRECTIONS["iso"]))

        if turn_yaw or turn_pitch:
            # A turn in degrees, added to the angles the camera is already at. Degrees rather than
            # the drag distance the arcball took: an angle means the same thing at every render
            # size, so nothing needs scaling by the supersampling factor and the app can send one
            # number for a 320 px frame and a 1080 px one. Pitch is clamped short of the poles so
            # that "up" never becomes ambiguous.
            self._yaw_deg = (self._yaw_deg + float(turn_yaw)) % 360.0
            self._pitch_deg = max(-89.0, min(89.0, self._pitch_deg + float(turn_pitch)))
            _apply_turntable(viewport, self._yaw_deg, self._pitch_deg)

        if pan_dx or pan_dy:
            # Integers: pywrap types Pan's two deltas SupportsInt and refuses a float with
            # "incompatible function arguments", which the app's drag-scaled deltas would always
            # be. OCCT pans by whole pixels in any case.
            #
            # The y is negated. Pan works in the window's coordinates, which grow up from the
            # bottom-left corner; a delta from a finger grows down the screen. Measured on the
            # phone, before this: Pan(+100, 0) moved the part +102 px right (right, it should),
            # and Pan(0, +100) moved it 99 px *up* - the part travelling against the hand on one
            # axis and with it on the other, which is exactly how it was reported.
            viewport.Pan(int(round(pan_dx * aa)), -int(round(pan_dy * aa)))
        if zoom != 1.0:
            viewport.SetZoom(float(zoom), True)
        viewport.Redraw()

        picked = None
        if select_x is not None and select_y is not None:
            picked = _pick_at(renderer, select_x * aa, select_y * aa, shape)

        written = _publish(filepath, lambda temp: _write_view(
            viewport, width * aa, height * aa, temp, output=(width, height)))
        result = {
            "filepath": filepath,
            "bytes": written,
            "shapes": label,
            "width": width,
            "height": height,
        }
        if picked is not None:
            result["picked"] = picked
        return result
    def analyze(self, name="", nozzle=0.4, layer=0.2, overhang_deg=45.0,
                bed_x=None, bed_y=None, bed_z=None, samples=14):
        """Whether this part will print, as numbers rather than an opinion.

        Three questions, each of which has sunk a print: is any wall thinner than the
        nozzle can lay down, how much of the part overhangs and needs support, and does it
        fit the plate. The first two are computed on the exact surface - the slicer will
        only ever see triangles, and a wall that is thin in the model is thinner still
        once it is meshed.
        """
        if kernel()["build123d"] is None:
            raise RuntimeError(kernel()["error"] or "build123d is unavailable")
        label, shape = _resolve_target(name)
        box = shape.bounding_box()
        size = box.size
        gaps = _wall_thickness(shape, samples=samples)
        thin = [round(g, 4) for g in gaps if g < nozzle]
        report = {
            "shapes": label,
            "volume_mm3": round(_volume_of(shape), 4),
            "surface_mm2": round(_area_of(shape), 4),
            "bbox_mm": [round(size.X, 3), round(size.Y, 3), round(size.Z, 3)],
            "wall": {
                "samples": len(gaps),
                "min_mm": round(min(gaps), 4) if gaps else None,
                "max_mm": round(max(gaps), 4) if gaps else None,
                "below_nozzle": len(thin),
                "thin_examples": sorted(thin)[:6],
                "nozzle_mm": nozzle,
            },
            "overhang": _overhangs(shape, threshold_deg=overhang_deg),
            "overhang_threshold_deg": overhang_deg,
        }
        if bed_x and bed_y:
            fits_xy = size.X <= bed_x and size.Y <= bed_y
            fits_rotated = min(max(size.X, size.Y), max(size.Y, size.X)) <= max(bed_x, bed_y)
            report["bed"] = {
                "bed_mm": [bed_x, bed_y, bed_z],
                "fits_as_modelled": bool(fits_xy),
                "fits_if_rotated_90": bool(fits_xy or (size.X <= bed_y and size.Y <= bed_x)),
                "needs_rotation": bool(not fits_xy and size.X <= bed_y and size.Y <= bed_x),
                "fits_height": (size.Z <= bed_z) if bed_z else None,
            }
        # The verdict, so the agent does not have to interpret the numbers to be useful.
        problems = []
        if report["wall"]["below_nozzle"]:
            problems.append("%d wall sample(s) thinner than the %.2f mm nozzle, smallest %.2f mm"
                            % (len(thin), nozzle, min(thin)))
        if report["overhang"]["fraction_of_surface"] > 0.05:
            problems.append("%.0f%% of the surface overhangs past %g deg and may need support"
                            % (report["overhang"]["fraction_of_surface"] * 100, overhang_deg))
        if report.get("bed") and not report["bed"]["fits_as_modelled"]:
            if report["bed"]["needs_rotation"]:
                problems.append("does not fit the bed as modelled; rotating 90 deg would")
            else:
                problems.append("does not fit the bed")
        report["verdict"] = "printable as modelled" if not problems else "; ".join(problems)
        return report

    def section(self, name="", axis="x", offset=0.0, out=""):
        """A cross-section through the part, as a face, optionally written to a file.

        Reading a section is how you find the void nobody modelled and the wall nobody
        meant: an outside view of a solid looks the same whether or not it is hollow.
        """
        if kernel()["build123d"] is None:
            raise RuntimeError(kernel()["error"] or "build123d is unavailable")
        from OCP.BRepAlgoAPI import BRepAlgoAPI_Section
        from OCP.gp import gp_Pln, gp_Pnt, gp_Dir
        label, shape = _resolve_target(name)
        box = shape.bounding_box()
        centre = box.center()
        normals = {'x': (1, 0, 0), 'y': (0, 1, 0), 'z': (0, 0, 1)}
        n = normals.get(axis.lower())
        if n is None:
            raise ValueError("axis must be x, y or z")
        base = {"x": centre.X, "y": centre.Y, "z": centre.Z}[axis.lower()] + offset
        loc = {'x': (base, centre.Y, centre.Z), 'y': (centre.X, base, centre.Z),
               'z': (centre.X, centre.Y, base)}[axis.lower()]
        plane = gp_Pln(gp_Pnt(*loc), gp_Dir(*n))
        op = BRepAlgoAPI_Section(shape.wrapped, plane)
        op.Build()
        if not op.IsDone():
            raise RuntimeError("the section could not be computed")
        b3d = kernel()["build123d"]
        # The result of BRepAlgoAPI_Section is a raw OCP shape, which has no .edges() - pywrap
        # binds none - so the cast to a build123d Shape is what makes the section readable rather
        # than reported as "edges: null" whatever it cut through. The cast was computed and never
        # used before this.
        result = b3d.Shape.cast(op.Shape()) if hasattr(b3d.Shape, "cast") else op.Shape()
        info = {"shapes": label, "axis": axis.lower(), "offset": offset,
                "plane_at": round(base, 4),
                "edges": len(list(result.edges())) if hasattr(result, "edges") else None}
        if out:
            # build123d has no export_svg: SVG comes from ExportSVG, added and written. The old
            # call raised AttributeError every time, so out= could never write a file.
            def _write_svg(temp):
                # The unit belongs to the exporter and the path to write(), the same shape the
                # skill's own DXF example uses: ExportSVG(path, unit=...) passes it twice.
                exporter = b3d.ExportSVG(unit="mm")
                exporter.add_shape(result)
                exporter.write(temp)
            written = _publish(out, _write_svg)
            info["filepath"] = out
            info["bytes"] = written
        return info
    # Extension -> build123d importer. STEP and BREP carry analytic geometry, which is
    # what makes them worth importing rather than the mesh formats: a hole is a cylinder,
    # not a ring of triangles, so it can be re-dimensioned afterwards.
    IMPORTERS = {
        ".step": "import_step",
        ".stp": "import_step",
        ".stpz": "import_step",
        ".brep": "import_brep",
        ".stl": "import_stl",
        ".svg": "import_svg",
        # build123d has import_dxf and ezdxf is already bundled; only the map entry was
        # missing. A DXF or SVG profile is how a flat part arrives: a drawing, a logo, a
        # panel outline - extruded into something printable.
        ".dxf": "import_dxf",
    }

    def import_file(self, filepath, name="", unit="mm", replace=True):
        """Import a part into the scene, replacing what is there by default.

        Importing is how a part arrives, and a viewport with no outliner cannot
        show that a second part is now sitting inside the first. Pass
        replace=False to add alongside instead.

        The shape is added under `name`, or the file's stem when none is given, so the
        agent can address it in the next command exactly as if it had been modelled here.
        """
        k = kernel()
        if k["build123d"] is None:
            raise RuntimeError(k["error"] or "build123d is unavailable")
        if not os.path.isfile(filepath):
            raise FileNotFoundError("no such file: %s" % filepath)
        extension = os.path.splitext(filepath)[1].lower()
        importer = self.IMPORTERS.get(extension)
        if importer is None:
            raise ValueError("cannot import %r; supported: %s"
                             % (extension, ", ".join(sorted(self.IMPORTERS))))
        b3d = k["build123d"]
        load = getattr(b3d, importer)
        # import_stl takes a unit; the others do not.
        shape = load(filepath, unit) if importer == "import_stl" else load(filepath)
        # import_svg and import_dxf hand back a ShapeList - build123d's list of wires or faces -
        # not a shape. Stored as one it reported success, cleared the scene, and broke every later
        # command that resolved the name: the next frame raised AIS_Shape(list). Refused here,
        # before anything is cleared, so a refusal costs the caller nothing.
        if not hasattr(shape, "wrapped"):
            raise ValueError(
                "%s imports as a list of profiles, which the scene cannot hold yet; import it in "
                "execute_code and add the shape you want"
                % os.path.splitext(os.path.basename(filepath))[1])
        label = name or os.path.splitext(os.path.basename(filepath))[0]
        if replace:
            SCENE.clear()
        SCENE.add(label, shape)
        info = _summary(label, shape)
        info["imported_from"] = filepath
        info["imported_as"] = importer
        return info

    def clearance(self, cloud, name="", x=None, y=None, z=None, samples=16):
        """How far the part is from the scanned environment.

        The number a jig design turns on, and the one question a render cannot answer. The
        environment arrives as a point cloud - the `environment.npy` the capture pipeline
        writes beside the mesh - while the mesh in the scene is a decimated stand-in for
        display. Measuring against that mesh would be the error of an approximation of an
        approximation, so this measures against the cloud: every point of it, through a k-d
        tree built once per file and kept.

        Give `name` to measure the closest approach of a shape in the scene, or x, y and z
        for a single point. The answer carries both ends of the closest pair, so the next
        command can act on where the part comes near the world rather than on the number.
        """
        import numpy as np
        from scipy.spatial import cKDTree

        path = os.path.abspath(cloud)
        if not os.path.isfile(path):
            raise FileNotFoundError("no such cloud: %s" % path)
        cached = _CLEARANCE.get(path)
        stamp = os.path.getmtime(path)
        if cached is None or cached[0] != stamp:
            points = np.load(path)
            if points.ndim != 2 or points.shape[1] < 3:
                raise ValueError("expected an Nx3 array in %s, found %s"
                                 % (path, getattr(points, "shape", None)))
            points = np.ascontiguousarray(points[:, :3], dtype=np.float64)
            if len(points) == 0:
                raise ValueError("the cloud in %s is empty" % path)
            cached = (stamp, points, cKDTree(points))
            _CLEARANCE[path] = cached
            # One cloud at a time: these are millions of points, and the phone has one task.
            for other in [p for p in _CLEARANCE if p != path]:
                del _CLEARANCE[other]
        _, points, tree = cached

        if x is None and y is None and z is None:
            label, shape = _resolve_target(name)
            probes = _probe_points(shape, samples)
            source = label
        else:
            if x is None or y is None or z is None:
                raise ValueError("a point query needs all of x, y and z")
            probes = [(float(x), float(y), float(z))]
            source = "point"
        if not probes:
            raise RuntimeError("nothing to measure: %s has no probe points" % source)

        distances, indices = tree.query(np.array(probes, dtype=np.float64))
        nearest = int(np.argmin(distances))
        return {
            "shapes": source,
            "distance_mm": round(float(distances[nearest]), 4),
            "from": [round(v, 4) for v in probes[nearest]],
            "to": [round(float(v), 4) for v in points[int(indices[nearest])]],
            "probes": len(probes),
            "cloud_points": int(len(points)),
            "cloud": path,
        }

    # Renders are capped so a request cannot ask for an image larger than the device can
    # hold: 2048x2048 RGBA is 16 MB, and the pixmap is uncompressed on the way out.
    RENDER_MIN, RENDER_MAX = 64, 2048

    def render(self, filepath, name="", view="iso", width=640, height=640, shaded=True,
               highlight_faces=None, highlight_overhang=None):
        """Draw the scene (or one shape) offscreen and write it as a PNG.

        The engine has a GL viewer and no window. It works: OCCT builds its own EGL context
        and renders into a pixmap, which is how the user gets to see what the agent sees.

        The pixmap is read directly and encoded with Pillow (already in the payload for
        build123d's sake); see _write_view for why that is no longer a PPM round trip.
        """
        k = kernel()
        if k["build123d"] is None:
            raise RuntimeError(k["error"] or "build123d is unavailable")

        label, shape = _resolve_target(name)
        width = max(self.RENDER_MIN, min(self.RENDER_MAX, int(width)))
        height = max(self.RENDER_MIN, min(self.RENDER_MAX, int(height)))
        if view not in VIEW_DIRECTIONS:
            raise ValueError("unknown view %r; try one of %s"
                             % (view, ", ".join(sorted(VIEW_DIRECTIONS))))

        if highlight_overhang is not None and not highlight_faces:
            # "Show me what needs support" without making the caller find the faces first.
            import math as _math
            down = _math.cos(_math.radians(90.0 - float(highlight_overhang)))
            highlight_faces = []
            for index, face in enumerate(shape.faces()):
                try:
                    normal = face.normal_at()
                except Exception:
                    continue
                if isinstance(normal, (list, tuple)):
                    normal = normal[0]
                if -normal.Z > down:
                    highlight_faces.append(index)

        # Indices in, wrapped faces out. The renderer only ever sees a TopoDS shape,
        # so anything that needs to ask the model a question has to happen here.
        marked = []
        if highlight_faces:
            faces = list(shape.faces())
            for index in highlight_faces:
                if isinstance(index, int) and 0 <= index < len(faces):
                    face = faces[index]
                    marked.append(getattr(face, "wrapped", face))

        renderer = _renderer(width, height)
        viewport = renderer.render(getattr(shape, "wrapped", shape),
                                   width, height, view, bool(shaded),
                                   highlight=marked)

        # A still is rendered through the same V3d_View the user's screen is using, and that call
        # fits and re-orients it - so rendering a picture for the agent left the viewport pointing
        # at the still's camera while the turntable angles still described the old one, and the
        # next drag jumped. The direction is put back here; the framing stays the still's FitAll
        # until the user presses Reset view, which is a smaller surprise than a camera that moves
        # on its own.
        _apply_turntable(viewport, self._yaw_deg, self._pitch_deg)
        viewport.Redraw()

        def write(temp):
            _write_view(viewport, width, height, temp)

        written = _publish(filepath, write)
        return {
            "filepath": filepath,
            "bytes": written,
            "shapes": label,
            "view": view,
            "width": width,
            "height": height,
            "shaded": bool(shaded),
            "highlighted_faces": sorted(highlight_faces) if highlight_faces else [],
        }

    def get_addon_info(self):
        k = kernel()
        info = {
            "name": "MCP for CAD (Slim)",
            "version": list(VERSION),
            "kernel": self._kernel_state(),
        }
        b3d = k["build123d"]
        if b3d is not None:
            try:
                info["build123d_version"] = getattr(b3d, "__version__", None)
            except Exception:
                pass
        return info

    def _kernel_state(self):
        k = kernel()
        return {
            "ocp": k["ocp"] is not None,
            "build123d": k["build123d"] is not None,
            "error": k["error"],
        }


def _metrics(shape):
    entry = {}
    volume = _volume_of(shape)
    if volume is not None:
        entry["volume"] = round(volume, 6)
    area = _area_of(shape)
    if area is not None:
        entry["area"] = round(area, 6)
    bbox = _bbox_of(shape)
    if bbox is not None:
        entry["bbox"] = bbox
        # What the box's middle is, named as the box's middle. It used to be reported as
        # "centroid", which is a different point on any part that is not symmetric - and the
        # bounding box is still what you want for placing a part, so both are here now.
        entry["bbox_centre"] = [round((bbox["min"][i] + bbox["max"][i]) / 2.0, 6)
                                for i in range(3)]
    centre_of_mass = _centre_of_mass(shape)
    if centre_of_mass is not None:
        entry["centroid"] = centre_of_mass
    return entry

# --------------------------------------------------------------------------
# Status file
# --------------------------------------------------------------------------

def write_status(path, payload):
    """Publish the server's state for the app to read.

    Written through a temporary file and renamed, so the app never reads a
    half-written status while it is deciding what to tell the user."""
    if not path:
        return
    try:
        temp = path + ".part"
        with open(temp, "w", encoding="utf-8") as handle:
            json.dump(payload, handle)
        os.replace(temp, path)
    except Exception:
        pass


# --------------------------------------------------------------------------
# Entry point
# --------------------------------------------------------------------------

def read_token(path):
    """The shared secret the app writes before it starts the engine.

    None means there is no token file: a development run, where the server
    refuses to serve rather than staying open.
    """
    try:
        with open(path, "r", encoding="utf-8") as handle:
            token = handle.read().strip()
    except OSError:
        return None
    return token or None


def _seed_default_scene():
    """Put a part in the viewport before anyone asks for one.

    The engine started empty, and the CAD screen has nothing to draw until a shape exists - so a
    first visit was a spinner over an empty scene, and the app's own caption ("the engine starts
    on its default scene") described something that was not there. A 20 mm cube on the bed gives
    the viewport something to show, the viewer settings something to be judged against, and the
    agent a part it can measure before it has been asked for anything.

    Returns why it could not, or None when the cube is in place.
    """
    k = kernel()
    if k["build123d"] is None:
        return k["error"] or "build123d is unavailable"
    b3d = k["build123d"]
    SCENE.clear()
    # Sitting on the bed rather than centred on the origin: a part that floats is a part whose
    # relationship to the grid and the axes cannot be read.
    SCENE.add("cube", b3d.Pos(0, 0, 10) * b3d.Box(20, 20, 20))
    return None


def main(argv=None):
    import sys
    argv = list(sys.argv[1:] if argv is None else argv)
    here = os.path.dirname(os.path.abspath(__file__))

    port = DEFAULT_PORT
    if "--port" in argv:
        try:
            port = int(argv[argv.index("--port") + 1])
        except (IndexError, ValueError):
            print("CadMCP slim: --port needs a number; using default")
    # Allow override via a tiny file next to the engine (no env on Android).
    try:
        with open(os.path.join(here, "cad_mcp_port.txt"), encoding="utf-8") as handle:
            port = int(handle.read().strip())
    except (OSError, ValueError):
        pass
    if port <= 0 or port > 65535:
        port = DEFAULT_PORT

    host = os.environ.get("CAD_MCP_HOST", "") or "localhost"

    # Import the kernel before announcing a port, so the first request never
    # pays for it and a broken install is visible in the status file.
    k = kernel()
    if k["error"]:
        print(f"CadMCP slim: {k['error']}")
    else:
        seeded = _seed_default_scene()
        print("CadMCP slim: default scene seeded" if seeded is None
              else f"CadMCP slim: no default scene ({seeded})")

    token = read_token(os.path.join(here, "cad_mcp_token.txt"))
    status_path = os.path.join(here, "cad_mcp_status.json")
    restart_file = os.path.join(here, "cad_mcp_restart.txt")

    # Serve, and never return. Parking keeps the process (and the user's scene)
    # alive; the app asks for a server again by touching the restart file, which
    # costs nothing and keeps the scene intact.
    while True:
        try:
            os.remove(restart_file)
        except OSError:
            pass
        server = CadMCPServer(host=host, port=port, token=token,
                              status_path=status_path)
        server.start()
        if server.running:
            print(f"CadMCP slim: serving on {host}:{port}; waiting for the restart file")
            while server.running and not os.path.exists(restart_file):
                server._drain_command_queue()
                time.sleep(0.02)
            # Say which of the two ended the serve loop. They look identical from the
            # outside - the port closes either way - and only one of them is expected.
            print("CadMCP slim: serve loop ended (shutdown_requested=%s restart_file=%s "
                  "running=%s)" % (server._shutdown_requested,
                                   os.path.exists(restart_file), server.running))
            server.stop()
            if server._shutdown_requested:
                print("CadMCP slim: shutdown requested; exiting")
                return 0
            print("CadMCP slim: parked until the restart file appears")
        else:
            time.sleep(0.5)


if __name__ == "__main__":
    raise SystemExit(main())
