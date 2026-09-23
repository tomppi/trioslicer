#!/usr/bin/env python3
"""Transport checks for the Blender MCP addon the Android app ships.

The addon is the one tracked piece laid over the fetched Blender package, and
nothing else compiles or exercises it: a syntax error or a transport regression
only shows up on a device. This drives it without Blender - bpy is stubbed in
sys.modules, as the addon itself expects outside the engine - over a real
loopback socket, so the checks cover the token gate, the newline framing, the
size guard, the busy answer and the shutdown that frees the port.

Usage: python3 scripts/test-blender-mcp-slim.py
"""

import importlib.util
import json
import os
import socket
import sys
import threading
import time
import types

# The addon is loaded from the tracked source tree, and a __pycache__ written
# beside it would be copied into the staged Blender assets - and so into the APK
# - by the fetch script.
sys.dont_write_bytecode = True

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADDON = os.path.join(ROOT, "native", "blender", "assets", "startup", "blender_mcp_slim.py")
TOKEN = "token-from-blender_mcp_token.txt"

# Held directly rather than looked up as sys.stdout per line: while a command
# runs, the addon's execute_code redirects sys.stdout process-wide to capture
# what the command prints, and a check reported from this thread during that
# window would otherwise be swallowed along with it.
_STDOUT = sys.stdout

_checks = 0


def check(condition, description):
    """Prints one line per check and stops at the first failure."""
    global _checks
    _checks += 1
    if not condition:
        raise AssertionError("check %d failed: %s" % (_checks, description))
    _STDOUT.write("ok %d - %s\n" % (_checks, description))
    _STDOUT.flush()


def install_bpy_stub():
    """Stands in for bpy so the addon imports outside Blender.

    Only the attributes the addon reads at import and start time are present;
    anything touching real scene data is left to fail, which is what the
    addon's own fallback does too.
    """

    class _Timers:
        @staticmethod
        def is_registered(function):
            return False

        @staticmethod
        def register(function, persistent=False):
            pass

        @staticmethod
        def unregister(function):
            pass

    class _Objects:
        @staticmethod
        def get(name, default=None):
            return default

    bpy = types.ModuleType("bpy")
    bpy.app = types.SimpleNamespace(background=True, timers=_Timers())
    bpy.types = types.SimpleNamespace()
    bpy.data = types.SimpleNamespace(objects=_Objects())
    bpy.context = types.SimpleNamespace(scene=None)
    sys.modules["bpy"] = bpy


def load_addon():
    if not os.path.isfile(ADDON):
        raise SystemExit("the addon is missing: %s" % ADDON)
    spec = importlib.util.spec_from_file_location("blender_mcp_slim", ADDON)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class Replies:
    """Newline-free JSON replies read off one connection.

    The addon answers each request with exactly one JSON object and no
    delimiter, and two answers can arrive in one read, so the buffer is decoded
    object by object rather than whole.
    """

    def __init__(self, client):
        self.client = client
        self.buffer = ""

    def read(self):
        deadline = time.time() + 5.0
        while True:
            start = len(self.buffer) - len(self.buffer.lstrip())
            if start < len(self.buffer):
                try:
                    value, end = json.JSONDecoder().raw_decode(self.buffer, start)
                except ValueError:
                    pass
                else:
                    self.buffer = self.buffer[end:]
                    return value
            if time.time() > deadline:
                raise AssertionError("no reply from the addon")
            self.client.settimeout(max(0.1, deadline - time.time()))
            chunk = self.client.recv(8192)
            if not chunk:
                raise AssertionError("the addon closed the connection before replying")
            self.buffer += chunk.decode("utf-8")


class FakeClient:
    """A client stand-in for the size guard.

    Sending an oversized request over loopback races the addon's own close: the
    guard refuses as soon as the buffer crosses the limit, so a real peer is
    usually still writing when the connection goes away. Handing the handler one
    oversized read checks the same branch deterministically.
    """

    def __init__(self, chunks):
        self.chunks = list(chunks)
        self.sent = []

    def recv(self, size):
        return self.chunks.pop(0) if self.chunks else b""

    def sendall(self, data):
        self.sent.append(data)

    def settimeout(self, value):
        pass

    def shutdown(self, how):
        pass

    def close(self):
        pass


class Harness:
    """The addon's server with its headless driver running on a thread.

    In the engine the app drains the command queue from a driver loop; without
    one, every request would only be queued and no check could see an answer.
    """

    def __init__(self, module):
        self.module = module
        self.server = module.BlenderMCPServer(host="127.0.0.1", port=0, token=TOKEN)
        # The shipped threshold only decides how long a client waits before it
        # is told the engine is busy; any command in flight counts here so the
        # check does not have to idle for ten seconds.
        module.BUSY_ANSWER_AFTER_SECONDS = 0.0
        self.server.start()
        if self.server.start_error:
            raise SystemExit("the addon's server did not start: %s" % self.server.start_error)
        self.port = self.server.port
        self.listener = self.server.socket
        self.driver = threading.Thread(target=self._drive, daemon=True)
        self.driver.start()

    def _drive(self):
        while self.server.running:
            self.server._drain_command_queue()
            time.sleep(0.01)

    def connect(self):
        client = socket.create_connection(("127.0.0.1", self.port), timeout=5)
        return client, Replies(client)

    def request(self, command):
        client, replies = self.connect()
        try:
            client.sendall(json.dumps(command).encode("utf-8"))
            return replies.read()
        finally:
            client.close()

    def close(self):
        if self.server.running:
            self.server.stop()
        self.driver.join(timeout=5)


def main():
    install_bpy_stub()
    module = load_addon()
    harness = Harness(module)
    try:
        refusal = harness.request({"type": "ping"})
        check(
            refusal.get("status") == "error" and "unauthorized" in refusal.get("message", ""),
            "a request without the token is refused",
        )

        answer = harness.request({"type": "ping", "token": TOKEN})
        check(
            answer.get("status") == "success" and answer.get("result", {}).get("pong") is True,
            "a request with the token is answered",
        )

        client, replies = harness.connect()
        try:
            client.sendall(
                (
                    json.dumps({"type": "ping", "token": TOKEN})
                    + "\n"
                    + json.dumps({"type": "get_addon_info", "token": TOKEN})
                    + "\n"
                ).encode("utf-8"),
            )
            first = replies.read()
            second = replies.read()
        finally:
            client.close()
        check(
            first.get("result", {}).get("pong") is True
            and second.get("result", {}).get("headless_ready") is True,
            "two newline-delimited requests in one write are both answered",
        )

        oversized = FakeClient([b"x" * (module.MAX_REQUEST_BYTES + 1)])
        harness.server._handle_client(oversized)
        check(
            oversized.sent
            and json.loads(oversized.sent[-1].decode("utf-8")).get("message") == "request too large",
            "an oversized buffer is refused",
        )

        busy_client, busy_replies = harness.connect()
        try:
            busy_client.sendall(
                json.dumps(
                    {
                        "type": "execute_code",
                        "params": {"code": "import time; time.sleep(0.8)"},
                        "token": TOKEN,
                    },
                ).encode("utf-8"),
            )
            deadline = time.time() + 5.0
            while harness.server._executing_since is None and time.time() < deadline:
                time.sleep(0.01)
            check(harness.server._executing_since is not None, "the long command reached the engine")
            busy = harness.request({"type": "get_addon_info", "token": TOKEN})
            check(
                busy.get("status") == "error" and "busy" in busy.get("message", ""),
                "a request while a command is running is told the engine is busy",
            )
            check(
                busy_replies.read().get("status") == "success",
                "the running command still answers when it finishes",
            )
        finally:
            busy_client.close()

        check(harness.request({"type": "shutdown", "token": TOKEN}).get("status") == "success", "shutdown is acknowledged")
        deadline = time.time() + 5.0
        while harness.server.running and time.time() < deadline:
            time.sleep(0.01)
        try:
            harness.listener.getsockopt(socket.SOL_SOCKET, socket.SO_ACCEPTCONN)
            still_open = True
        except OSError:
            still_open = False
        # The listener is released by the accept loop, which wakes on its own 1 s
        # timeout: close() from another thread does not free the port on Linux until
        # that accept returns. Bounded retry, not an instant demand.
        rebound = False
        deadline = time.time() + 5.0
        while time.time() < deadline and not rebound:
            probe = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            try:
                probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                probe.bind(("127.0.0.1", harness.port))
                probe.listen(1)
                rebound = True
            except OSError:
                time.sleep(0.1)
            finally:
                probe.close()
        check(
            not still_open and rebound,
            "a shutdown closes the listening socket and frees the port",
        )
    finally:
        harness.close()
    _STDOUT.write("all %d checks passed\n" % _checks)
    _STDOUT.flush()
    return 0


if __name__ == "__main__":
    sys.exit(main())
