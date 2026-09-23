# blender_mcp_slim.py -- Minimal headless MCP transport addon for Blender 3.6 arm64
# Derived from blender-mcp (MIT, github.com/ahujasid/blender-mcp) addon.py.
# Modifications for on-device print generation (DSH project):
#   1) Headless support: commands drained on main thread via driver loop
#      (works with 'blender -b --python this.py') instead of bpy.app.timers,
#      which do not run in background mode.
#   2) Stripped: requests/web asset services (polyhaven/sketchfab/polypizza/
#      hyper3d/hunyuan), telemetry, trajectory, edit-capture, safe-mode.
#      Keep: socket transport, execute_code, get_scene_info, get_object_info.
# License: MIT (same as upstream). Blender itself GPL; this addon is MIT.

try:
    import bpy
except ImportError:  # pragma: no cover - desktop smoke tests only
    # Minimal stand-in so the transport layer can be exercised without Blender.
    # Nothing here emulates real bpy data; scene/data/context calls fail
    # naturally (returning error responses) when accessed outside Blender.
    class _TimerRegistry:
        @staticmethod
        def is_registered(fn):
            return False

        @staticmethod
        def register(fn, persistent=False):
            pass

        @staticmethod
        def unregister(fn):
            pass

    class _App:
        background = True
        timers = _TimerRegistry()

    class _DictLike:
        def get(self, name, default=None):
            return default

    class _BpyStub:
        app = _App()
        types = type("_TypesStub", (), {})
        data = type("_DataStub", (), {"objects": _DictLike()})()
        context = type("_ContextStub", (), {"scene": None})()

    bpy = _BpyStub()
try:
    import mathutils  # kept in namespace for generated scripts
except ImportError:
    mathutils = None  # smoke tests outside Blender
import json
import threading
import socket
import queue
import time
import traceback
import io
import os
from contextlib import redirect_stdout

bl_info = {
    "name": "MCP for Blender (Slim Headless)",
    "author": "DSH project (derived from BlenderMCP)",
    "version": (0, 1),
    "blender": (3, 0, 0),
    "description": "Minimal socket MCP transport for headless print-model generation",
    "category": "Interface",
}

DEFAULT_PORT = 9876

# A request larger than this is refused rather than buffered: nothing legitimate
# sends more than a script, and the buffer lives inside the app process.
MAX_REQUEST_BYTES = 8 * 1024 * 1024

# One command at a time runs on bpy's main thread. A client that asks while a
# command has been running this long is told so, instead of waiting out its own
# timeout with no explanation.
BUSY_ANSWER_AFTER_SECONDS = 10.0


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

def _mesh_to_binary_stl(mesh, filepath):
    """Write a Blender Mesh to a spec-compliant binary STL (little endian).

    Avoids bpy.ops.export_scene.stl, whose operator registration is
    unreliable in embedded headless builds (the addon classes register but
    the op entry is missing, so calls raise 'could not be found').
    """
    import struct
    mesh.calc_loop_triangles()
    tris = mesh.loop_triangles
    with open(filepath, "wb") as f:
        f.write(struct.pack("<80sI", b"enderslicercura MCP STL", len(tris)))
        for t in tris:
            vs = [mesh.vertices[j].co for j in t.vertices]
            # flat normal
            ux, uy, uz = vs[1].x - vs[0].x, vs[1].y - vs[0].y, vs[1].z - vs[0].z
            wx, wy, wz = vs[2].x - vs[0].x, vs[2].y - vs[0].y, vs[2].z - vs[0].z
            nx = uy * wz - uz * wy
            ny = uz * wx - ux * wz
            nz = ux * wy - uy * wx
            ln = (nx * nx + ny * ny + nz * nz) ** 0.5 or 1.0
            nx, ny, nz = nx / ln, ny / ln, nz / ln
            f.write(struct.pack("<3f", nx, ny, nz))
            for v in vs:
                f.write(struct.pack("<3f", v.x, v.y, v.z))
            f.write(struct.pack("<H", 0))
    return len(tris)

def _export_scene_stl(filepath,):
    """Export all mesh objects of the current scene to a binary STL."""
    import bpy
    total = 0
    with open(filepath, "wb") as f:
        # first pass: count triangles
        import struct
        meshes = [o.data for o in bpy.context.scene.objects if o.type == "MESH" and o.data]
        for mesh in meshes:
            mesh.calc_loop_triangles()
        count = sum(len(m.loop_triangles) for m in meshes)
        f.write(struct.pack("<80sI", b"enderslicercura MCP STL", count))
        for mesh in meshes:
            for t in mesh.loop_triangles:
                vs = [mesh.vertices[j].co for j in t.vertices]
                ux, uy, uz = vs[1].x - vs[0].x, vs[1].y - vs[0].y, vs[1].z - vs[0].z
                wx, wy, wz = vs[2].x - vs[0].x, vs[2].y - vs[0].y, vs[2].z - vs[0].z
                nx = uy * wz - uz * wy
                ny = uz * wx - ux * wz
                nz = ux * wy - uy * wx
                ln = (nx * nx + ny * ny + nz * nz) ** 0.5 or 1.0
                nx, ny, nz = nx / ln, ny / ln, nz / ln
                f.write(struct.pack("<3f", nx, ny, nz))
                for v in vs:
                    f.write(struct.pack("<3f", v.x, v.y, v.z))
                f.write(struct.pack("<H", 0))
        total = count
    return total


class BlenderMCPServer:
    def __init__(self, host='localhost', port=DEFAULT_PORT, token=None, status_path=None):
        self.host = host
        self.port = port
        # Shared secret the app writes next to this addon and sends with every
        # request. Without it any co-installed app could reach 127.0.0.1:9876 and
        # run Python as this app's uid. None means "no token file", and start()
        # refuses to serve in that case, so a bare `blender -b --python` dev run
        # is no longer open either.
        self.token = token
        self.status_path = status_path
        self.start_error = None
        self.running = False
        self.socket = None
        self.server_thread = None
        self.command_queue = queue.Queue()
        self._clients = set()
        self._clients_lock = threading.Lock()
        self.headless_driver = False
        self._shutdown_requested = False
        self._executing_since = None
        self._busy_lock = threading.Lock()

    def start(self):
        if self.running:
            print("BlenderMCP slim: server already running")
            return
        if not self.token:
            # Fail closed: _authorized() would have nothing to check a request
            # against, so serving would let any co-installed app run Python as
            # this process's uid. Refuse, and record why for the app to read.
            self.start_error = "no MCP token loaded; refusing to serve"
            print("BlenderMCP slim: " + self.start_error)
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
            if bpy.app.background:
                self.headless_driver = True
                print(f"BlenderMCP slim server started HEADLESS on {self.host}:{self.port}")
            else:
                if not bpy.app.timers.is_registered(self._drain_command_queue):
                    bpy.app.timers.register(self._drain_command_queue, persistent=True)
                print(f"BlenderMCP slim server started on {self.host}:{self.port}")
            write_status(self.status_path, {
                "running": True,
                "port": self.port,
                "pid": os.getpid(),
                "auth": bool(self.token),
            })
        except Exception as e:
            # Remember the reason: the caller parks instead of returning, and the
            # app reads the status file to say why the engine is not answering.
            self.start_error = str(e)
            print(f"BlenderMCP slim start failed: {e}")
            write_status(self.status_path, {"running": False, "port": self.port, "error": str(e)})
            self.stop()

    def stop(self):
        self.running = False
        try:
            if not bpy.app.background and bpy.app.timers.is_registered(self._drain_command_queue):
                bpy.app.timers.unregister(self._drain_command_queue)
        except Exception:
            pass
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
        if self.server_thread and self.server_thread.is_alive():
            self.server_thread.join(timeout=1.0)
        self.server_thread = None
        print("BlenderMCP slim server stopped")

    def _server_loop(self):
        # A local reference, and a timeout, so this loop always reaches a point
        # where it can see [running] go false: stop() closes the socket and clears
        # the attribute from another thread, and on Linux a close() issued while an
        # accept() is blocked does not release the port until that accept returns -
        # which is what made a rebind fail with EADDRINUSE.
        listener = self.socket
        listener.settimeout(1.0)
        while self.running:
            try:
                try:
                    client, address = listener.accept()
                    print(f"BlenderMCP slim: client connected {address}")
                    t = threading.Thread(target=self._handle_client, args=(client,), daemon=True)
                    t.start()
                except socket.timeout:
                    continue
                except Exception as e:
                    if not self.running:
                        break
                    print(f"BlenderMCP slim: accept error {e}")
                    time.sleep(0.5)
            except Exception as e:
                print(f"BlenderMCP slim: server loop error {e}")
                if not self.running:
                    break
                time.sleep(0.5)
        # The loop is the last user of the listener, so it is what actually frees
        # the port once accept() has returned.
        try:
            listener.close()
        except Exception:
            pass
        print("BlenderMCP slim: server loop stopped")

    def _reply(self, client, message):
        try:
            client.sendall(json.dumps({"status": "error", "message": message}).encode('utf-8'))
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
            self._reply(client, "unauthorized: send the token from blender_mcp_token.txt")
            return
        with self._busy_lock:
            started = self._executing_since
        if started is not None and command.get("type") != "ping":
            waited = time.time() - started
            if waited > BUSY_ANSWER_AFTER_SECONDS:
                self._reply(client, "engine busy in another command for %.0fs" % waited)
                return
        self.command_queue.put((command, client))

    def _handle_client(self, client):
        client.settimeout(1.0)
        with self._clients_lock:
            self._clients.add(client)
        buffer = b''
        try:
            while self.running:
                try:
                    data = client.recv(8192)
                    if not data:
                        break
                    buffer += data
                    if len(buffer) > MAX_REQUEST_BYTES:
                        self._reply(client, "request too large")
                        break
                    # Newline-delimited requests are parsed one per line; the app
                    # writes a single unterminated JSON object, so an unterminated
                    # buffer is parsed as soon as it is complete. Both in one
                    # stream is what makes a coalesced write work rather than hang.
                    while buffer:
                        newline = buffer.find(b'\n')
                        if newline >= 0:
                            payload, buffer = buffer[:newline], buffer[newline + 1:]
                            if not payload.strip():
                                continue
                        else:
                            payload = buffer
                            try:
                                command = json.loads(payload.decode('utf-8'))
                            except (json.JSONDecodeError, UnicodeDecodeError):
                                break  # incomplete: wait for the rest
                            buffer = b''
                            self._dispatch(command, client)
                            break
                        try:
                            command = json.loads(payload.decode('utf-8'))
                        except (json.JSONDecodeError, UnicodeDecodeError):
                            self._reply(client, "malformed JSON request")
                            continue
                        self._dispatch(command, client)
                except socket.timeout:
                    continue
                except Exception as e:
                    if self.running:
                        print(f"BlenderMCP slim: recv error {e}")
                    break
        finally:
            with self._clients_lock:
                self._clients.discard(client)
            try:
                client.close()
            except Exception:
                pass

    def _drain_command_queue(self):
        """Run queued commands on the main thread. Timer mode (GUI) and
        headless driver loop both call this."""
        if not self.running:
            return None
        while True:
            try:
                command, client = self.command_queue.get_nowait()
            except queue.Empty:
                break
            try:
                # Published while the command runs: a client that asks now is told
                # the engine is busy rather than left to time out silently.
                with self._busy_lock:
                    self._executing_since = time.time()
                response = self.execute_command(command)
                response_json = json.dumps(response)
            except Exception as e:
                print(f"BlenderMCP slim: execute error {e}")
                traceback.print_exc()
                response_json = json.dumps({"status": "error", "message": str(e)})
            try:
                client.sendall(response_json.encode('utf-8'))
            except Exception:
                print("BlenderMCP slim: send failed, client gone")
            finally:
                with self._busy_lock:
                    self._executing_since = None
        if self._shutdown_requested:
            self._shutdown_requested = False
            print("BlenderMCP slim: shutdown requested; driver exiting")
            with self._busy_lock:
                self._executing_since = None
            # stop() closes the listening socket. Leaving it open and merely
            # clearing [running] kept it in LISTEN - the server thread's reference
            # cycle delays collection - so the park loop's rebind of the same port
            # failed with EADDRINUSE and the engine never came back.
            self.stop()
            write_status(self.status_path, {"running": False, "stopped": True, "port": self.port})
        return 0.05

    def run_headless(self):
        """Headless driver: run on the python main thread.

        NOTE: in `blender -b --python <file>`, Blender executes the script
        synchronously; the python main thread IS the main thread for bpy,"
        so bpy data access here is safe."""
        while self.running:
            self._drain_command_queue()
            time.sleep(0.02)
        return 0

    def execute_command(self, command):
        try:
            return self._execute_command_internal(command)
        except Exception as e:
            print(f"BlenderMCP slim: command error {e}")
            traceback.print_exc()
            return {"status": "error", "message": str(e)}

    def _execute_command_internal(self, command):
        cmd_type = command.get("type")
        params = command.get("params", {})

        # Trivial liveness check. Touches no bpy data, so a successful ping
        # alongside a failing command isolates data access from transport.
        if cmd_type == "ping":
            return {"status": "success", "result": {"pong": True}}

        # Graceful stop: response is sent first, then the drain loop exits,
        # then the headless driver exits, then the process ends.
        if cmd_type == "shutdown":
            self._shutdown_requested = True
            return {"status": "success", "result": {"stopping": True}}

        handlers = {
            "export_stl": self.export_stl,
            "execute_code": self.execute_code,
            "get_scene_info": self.get_scene_info,
            "get_object_info": self.get_object_info,
            "get_world_state_snapshot": self.get_world_state_snapshot,
            "get_addon_info": self.get_addon_info,
        }
        handler = handlers.get(cmd_type)
        if handler is None:
            return {"status": "error", "message": f"Unknown command type: {cmd_type}"}
        try:
            result = handler(**params)
            return {"status": "success", "result": result}
        except Exception as e:
            print(f"BlenderMCP slim: error in {cmd_type}: {e}")
            traceback.print_exc()
            return {"status": "error", "message": str(e)}


    def export_stl(self, filepath):
        """Export all scene meshes to a binary STL. Returns bytes written and
        triangle count. Uses a native writer (no bpy.ops dependency)."""
        import os
        count = _export_scene_stl(filepath)
        return {"filepath": filepath, "bytes": os.path.getsize(filepath), "triangles": count}
    def execute_code(self, code):
        """Execute arbitrary bpy code. The generated-script entrypoint.

        Returns {"executed": True, "result": stdout}; the dispatcher wraps this
        in {"status": "success", "result": ...}. Exceptions propagate to the
        dispatcher, which answers {"status": "error", "message": ...} -- the
        same transport contract as upstream blender-mcp (MIT)."""
        namespace = {"bpy": bpy, "mathutils": mathutils, "json": json, "os": os}
        capture_buffer = io.StringIO()
        with redirect_stdout(capture_buffer):
            exec(code, namespace)
        captured_output = capture_buffer.getvalue()
        return {"executed": True, "result": captured_output}

    def get_scene_info(self):
        scene = bpy.context.scene
        objects = []
        for obj in scene.objects:
            objects.append({
                "name": obj.name,
                "type": obj.type,
                "location": list(obj.location),
                "dimensions": list(obj.dimensions),
            })
        return {
            "name": scene.name,
            "object_count": len(objects),
            "objects": objects,
        }

    def get_object_info(self, name=""):
        obj = bpy.data.objects.get(name)
        if not obj:
            raise Exception(f"Object not found: {name}")
        info = {
            "name": obj.name,
            "type": obj.type,
            "location": list(obj.location),
            "dimensions": list(obj.dimensions),
        }
        if obj.type == 'MESH':
            info["vertices"] = len(obj.data.vertices)
            info["polygons"] = len(obj.data.polygons)
        return info

    def get_world_state_snapshot(self):
        return {"objects": [o.name for o in bpy.context.scene.objects]}

    def get_addon_info(self):
        return {
            "name": bl_info["name"],
            "version": list(bl_info["version"]),
            "headless_ready": bpy.app.background,
        }

def register():
    bpy.types.Scene.blendermcp_port = bpy.props.IntProperty(
        name="Port", description="MCP socket port", default=DEFAULT_PORT,
        min=1024, max=65535)
    bpy.types.Scene.blendermcp_server_running = bpy.props.BoolProperty(
        name="Server Running", default=False)

def unregister():
    pass

if __name__ == '__main__':
    # Direct invocation (blender -b --python blender_mcp_slim.py):
    # run the headless driver.
    import sys
    port = DEFAULT_PORT
    if '--' in sys.argv:
        extra = sys.argv[sys.argv.index('--') + 1:]
        for i, a in enumerate(extra):
            if a == '--port' and i + 1 < len(extra):
                port = int(extra[i + 1])
    server = BlenderMCPServer(port=port)
    server.start()
    if server.headless_driver:
        # Background mode: bpy.app.timers never fire, so drain on the main
        # thread ourselves for the lifetime of the process (blender -b).
        print(f"BlenderMCP slim: headless driver on port {port}; serving until killed")
        server.run_headless()
        server.stop()
        print("BlenderMCP slim: driver exited cleanly")
    else:
        # GUI mode: return control to Blender's WM event loop; commands are
        # drained by the registered bpy.app.timers callback. This is also the
        # mode used by embedded builds (e.g. epai OBlender on Android).
        print(f"BlenderMCP slim: timer mode on port {port}; WM loop drives the queue")
