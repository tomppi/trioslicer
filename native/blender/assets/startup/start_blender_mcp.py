# start_blender_mcp.py -- Blender STARTUP script for embedded/on-device builds.
# Drop into <blender-assets>/scripts/startup/ (e.g. OBLFiles assets on Android)
# or any folder Blender loads at startup. Auto-starts the slim MCP socket server
# so the app/PC client can drive generation without CLI args.
#
# Port: env BLENDER_MCP_PORT, or DEFAULT_PORT (9876). Config file variant:
#   <blender-home>/blender_mcp_port.txt containing the port number.
#
# Files the app reads and writes, all next to this script:
#   blender_mcp_token.txt    shared secret, written by the app before start;
#                            every request must carry it. Absent => open (dev).
#   blender_mcp_status.json  last known server state, for the app to surface
#   blender_mcp_restart.txt  the app touches this to ask for a new server
#
# This script never returns: see the serve loop at the bottom.

import os
import sys
import time

# Locate the addon source next to this startup script (assets/scripts/startup)
_startup_dir = os.path.dirname(os.path.abspath(__file__))
_addon_dir = os.path.dirname(_startup_dir)
if _addon_dir not in sys.path:
    sys.path.insert(0, _addon_dir)
if _startup_dir not in sys.path:
    sys.path.insert(0, _startup_dir)

# Bundled addons that are worth having and are off by default.
#
# Blender ships 104 addons and enables 11 of them. The rest are off because they
# add UI panels, and this engine has no UI - but the operators are what matter,
# and they register perfectly well without a window. Between them these are the
# tools for repairing and building geometry: Looptools bridges and relaxes loops,
# F2 fills a hole from a ring of vertices, Bool Tool and Carver cut solids, Extra
# Objects and BoltFactory make parametric parts, tinyCAD intersects precisely.
_MODELLING_ADDONS = (
    "mesh_looptools",
    "mesh_f2",
    "mesh_inset",
    "mesh_tools",
    "mesh_tiny_cad",
    "mesh_snap_utilities_line",
    "mesh_auto_mirror",
    "mesh_tissue",
    "object_boolean_tools",
    "object_carver",
    "add_mesh_extra_objects",
    "add_mesh_BoltFactory",
    "add_curve_extra_objects",
    "curve_tools",
    "lighting_tri_lights",
    "io_import_images_as_planes",
    "io_import_dxf",
)


def _enable_modelling_addons() -> None:
    """Register the modelling addons. Failures are reported, never fatal."""
    import addon_utils

    for name in _MODELLING_ADDONS:
        try:
            addon_utils.enable(name, default_set=False, persistent=False)
        except Exception as error:
            print(f"start_blender_mcp: could not enable {name}: {error}")


def _port_setting() -> int:
    """Port resolution: env var > port file > 9876."""
    try:
        return int(os.environ.get("BLENDER_MCP_PORT", "0") or "0")
    except ValueError:
        return 0

def _read_token():
    """The shared secret the app writes before it starts the engine.

    None means there is no token file - a bare `blender -b --python` development
    run. The server refuses to serve in that case, so write a token file beside
    this script to run one by hand. The app always writes one."""
    path = os.path.join(_startup_dir, "blender_mcp_token.txt")
    try:
        with open(path, "r", encoding="utf-8") as handle:
            token = handle.read().strip()
    except OSError:
        return None
    return token or None


def start() -> bool:
    """Start the MCP server; returns True if started fresh."""
    import bpy  # noqa: F401 -- startup scripts always run with bpy

    _enable_modelling_addons()

    port = _port_setting()
    # Allow override via a tiny file next to the addon (no env on Android).
    for p in (os.path.join(_addon_dir, "blender_mcp_port.txt"),
              os.path.join(_startup_dir, "blender_mcp_port.txt")):
        try:
            with open(p, encoding="utf-8") as f:
                port = int(f.read().strip())
            break
        except (OSError, ValueError):
            continue

    if port <= 0 or port > 65535:
        port = 9876

    host = os.environ.get("BLENDER_MCP_HOST", "") or "localhost"

    try:
        import blender_mcp_slim as bm
    except ImportError:
        # The addon file lives right next to this script?
        import importlib.util
        spec = importlib.util.spec_from_file_location(
            "blender_mcp_slim", os.path.join(_addon_dir, "blender_mcp_slim.py"))
        if spec is None or spec.loader is None:
            print(f"start_blender_mcp: addon not found in {_addon_dir}")
            return False
        bm = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(bm)

    global _server, _stopped
    token = _read_token()
    status_path = os.path.join(_startup_dir, "blender_mcp_status.json")
    restart_file = os.path.join(_startup_dir, "blender_mcp_restart.txt")

    # Serve, and never return.
    #
    # In `blender -b --python <file>` this script runs inside the engine's own
    # main entry point, so *returning* ends Blender's background main - and
    # Blender's teardown calls exit(), which kills the whole app process and
    # everything unsaved in it. That is what a "shutdown" command used to do, and
    # what a failed bind did too. Parking instead keeps the process (and the
    # user's scene) alive; the app asks for a server again by touching the
    # restart file, which costs nothing and keeps the scene intact.
    while True:
        try:
            os.remove(restart_file)
        except OSError:
            pass
        server = bm.BlenderMCPServer(host=host, port=port, token=token, status_path=status_path)
        _server = server
        try:
            server.start()
        except Exception as e:
            print(f"start_blender_mcp: start failed: {e}")
        # Which mode this is must NOT be read off the server: headless_driver is
        # only set once the bind succeeded, so a failed start under `blender -b`
        # looked like the desktop case and returned from this script - which ends
        # Blender's background main, and its teardown calls exit(), killing the app
        # process. bpy.app.background is the honest signal.
        if not bpy.app.background:
            # Desktop/GUI: Blender's own event loop drives the server through a bpy
            # timer, so parking here would block its startup for ever.
            print("start_blender_mcp: not headless; returning to Blender (server running: "
                  + str(bool(server.running)) + ", error: "
                  + (server.start_error or "none") + ")")
            return True
        if server.headless_driver:
            # Background mode (blender -b --python ...): the script runs
            # synchronously on the bpy main thread, so draining MCP commands
            # here is what keeps the engine alive and answering.
            print("start_blender_mcp: headless driver loop running")
            server.run_headless()
            print("start_blender_mcp: headless driver exiting")
        else:
            # Headless, and the server did not come up (a busy port, most likely).
            # Park anyway: this script returning is what kills the process, and the
            # app asks for a server again through the restart file.
            print("start_blender_mcp: headless start failed: "
                  + (server.start_error or "server did not start"))
        bm.write_status(status_path, {
            "running": False,
            "port": server.port,
            "error": server.start_error,
        })
        print("start_blender_mcp: parked; touch " + restart_file + " to serve again")
        while not _stopped and not os.path.exists(restart_file):
            time.sleep(0.5)
        if _stopped:
            return False

_server = None
_stopped = False
if __name__ == "__main__":
    start()

# Auto-start when loaded as a startup script (module import context).
try:
    if __name__ != "__main__":
        import bpy as _bpy
        if not getattr(_bpy.context.scene, "blendermcp_started", False):
            _bpy.types.Scene.blendermcp_started = _bpy.props.BoolProperty(default=False)
            start()
            _bpy.context.scene.blendermcp_started = True
except Exception as e:
    print(f"start_blender_mcp: auto-start skipped: {e}")
