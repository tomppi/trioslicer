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
        from OCP.BRepGProp import BRepGProp
        from OCP.GProp import GProp_GProps
        props = GProp_GProps()
        BRepGProp.VolumeProperties_s(shape.wrapped, props)
        return float(props.Mass())
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
    root, extension = os.path.splitext(path)
    temp = root + "-part" + extension
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

    def render(self, shape, width, height, view, shaded, highlight=None):
        """Draw shape and return the view, ready for ToPixMap."""
        from OCP.AIS import AIS_Shape, AIS_Shaded, AIS_WireFrame
        from OCP.Quantity import Quantity_Color, Quantity_TOC_RGB

        self.resize(width, height)
        self.context.RemoveAll(False)
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
        except Exception:
            pass
        self.context.Display(presentation, False)
        self.context.SetDisplayMode(
            presentation, AIS_Shaded if shaded else AIS_WireFrame, False)
        # Shaded and wireframe as two presentations of the same shape. SetDrawEdges() on
        # the drawer has no effect on this GLES path - the renders come out byte-identical
        # with and without it - so edges are drawn as a second, overlaid wireframe. Without
        # them a boss on a plate is invisible from directly above, which is exactly the view
        # used to check where a feature sits.
        if shaded:
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
        direction = VIEW_DIRECTIONS.get(view, VIEW_DIRECTIONS["iso"])
        self.view.SetProj(*direction)
        self.view.FitAll(0.02)
        self.view.Redraw()
        return self.view


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
            "analyze": self.analyze,
            "section": self.section,
            "import_file": self.import_file,
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
                    explorer = TopExp_Explorer(wrapped, kind)
                    count = 0
                    while explorer.More():
                        count += 1
                        explorer.Next()
                    info[label] = count
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
        from OCP.TopoDS import TopoDS
        result = _solid_or_compound(op.Shape())
        b3d = kernel()["build123d"]
        wrapped = b3d.Shape.cast(result) if hasattr(b3d.Shape, "cast") else None
        info = {"shapes": label, "axis": axis.lower(), "offset": offset,
                "plane_at": round(base, 4),
                "edges": len(list(result.edges())) if hasattr(result, "edges") else None}
        if out:
            written = _publish(out, lambda temp: b3d.export_svg(result, temp))
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

    def import_file(self, filepath, name="", unit="mm"):
        """Import STEP, BREP, STL or SVG into the scene.

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
        label = name or os.path.splitext(os.path.basename(filepath))[0]
        SCENE.add(label, shape)
        info = _summary(label, shape)
        info["imported_from"] = filepath
        info["imported_as"] = importer
        return info

    # Renders are capped so a request cannot ask for an image larger than the device can
    # hold: 2048x2048 RGBA is 16 MB, and the pixmap is uncompressed on the way out.
    RENDER_MIN, RENDER_MAX = 64, 2048

    def render(self, filepath, name="", view="iso", width=640, height=640, shaded=True,
               highlight_faces=None, highlight_overhang=None):
        """Draw the scene (or one shape) offscreen and write it as a PNG.

        The engine has a GL viewer and no window. It works: OCCT builds its own EGL context
        and renders into a pixmap, which is how the user gets to see what the agent sees.

        OCCT writes PPM here - this build has no libpng - so the conversion to PNG goes
        through Pillow, which is already in the payload for build123d's sake.
        """
        k = kernel()
        if k["build123d"] is None:
            raise RuntimeError(k["error"] or "build123d is unavailable")
        from OCP.Image import Image_AlienPixMap

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

        def write(temp):
            # ToPixMap needs a path it can open, and the publish target is a .part file
            # whose extension says nothing about the format. So the pixmap goes to its own
            # PPM alongside, and Pillow writes the PNG to the real destination.
            ppm = temp + ".ppm"
            pixmap = Image_AlienPixMap()
            viewport.ToPixMap(pixmap, width, height)
            try:
                if not pixmap.Save(ppm):
                    raise RuntimeError("OCCT could not write the pixmap")
                from PIL import Image
                Image.open(ppm).convert("RGB").save(temp, format="PNG")
            finally:
                try:
                    os.remove(ppm)
                except OSError:
                    pass

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
        entry["centroid"] = [round((bbox["min"][i] + bbox["max"][i]) / 2.0, 6)
                             for i in range(3)]
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
