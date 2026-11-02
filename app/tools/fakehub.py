#!/usr/bin/env python3
"""A fake herdr-pocket hub serving canned data, for tests and for trying the app without Herdr.

    python3 tools/fakehub.py [port] [token]

Prints the port on the first line. GET /_log (no auth) returns the requests it has seen.
Each /api/events connection sends the list, a ping, waits up to 1 s for a change and closes,
so clients exercise their reconnect path.
"""
import json
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOKEN = sys.argv[2] if len(sys.argv) > 2 else "tok"
lock = threading.Condition()
log = []
pushes = set()
state = {
    "agents": [
        {"machine": "thor", "pane": "w1:p2", "agent": "claude", "status": "working", "since": int(time.time()) - 90,
         "title": "Fix the build", "cwd": "/home/magnus/dev/game", "workspace": "game", "important": False},
        {"machine": "thor", "pane": "w1:p3", "agent": "codex", "status": "blocked", "since": int(time.time()) - 30,
         "title": "", "cwd": "/home/magnus/dev/notatis", "workspace": "notatis", "important": True},
        {"machine": "loki", "pane": "w2:p1", "agent": "claude", "status": "idle", "since": int(time.time()) - 4000,
         "title": "Old chat", "cwd": "/srv/x", "workspace": "", "important": False},
    ],
    "machines": [{"name": "thor", "connected": True}, {"name": "loki", "connected": False}],
}


class Hub(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def reply(self, status, obj):
        body = json.dumps(obj).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def handle_any(self, method):
        url = urllib.parse.urlsplit(self.path)
        parts = [urllib.parse.unquote(p) for p in url.path.split("/")[1:]]
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length).decode() if length else ""
        if url.path == "/_log":
            with lock:
                return self.reply(200, {"requests": log, "pushes": sorted(pushes)})
        with lock:
            log.append({"method": method, "path": parts, "query": url.query, "body": raw})
        if self.headers.get("Authorization") != f"Bearer {TOKEN}":
            return self.reply(401, {"error": "bad token"})
        body = json.loads(raw) if raw else {}

        if method == "GET" and parts == ["api", "agents"]:
            with lock:
                return self.reply(200, state)
        if method == "GET" and parts == ["api", "events"]:
            return self.events()
        if parts[:2] == ["api", "agents"] and len(parts) == 5:
            machine, pane, action = parts[2], parts[3], parts[4]
            if machine == "loki":
                return self.reply(503, {"error": "loki is not connected"})
            if method == "GET" and action == "screen":
                lines = urllib.parse.parse_qs(url.query).get("lines", ["200"])[0]
                return self.reply(200, {"text": f"$ make\nbuilding {pane}\nlines={lines}\n\n"})
            if method == "POST" and action in ("prompt", "keys"):
                return self.reply(200, {})
            if method == "PUT" and action == "important":
                with lock:
                    for a in state["agents"]:
                        if a["machine"] == machine and a["pane"] == pane:
                            a["important"] = bool(body["important"])
                    lock.notify_all()
                return self.reply(200, {})
        if parts == ["api", "push"] and method in ("POST", "DELETE"):
            with lock:
                (pushes.add if method == "POST" else pushes.discard)(body["endpoint"])
            return self.reply(200, {})
        return self.reply(404, {"error": "no such route"})

    def events(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()
        with lock:
            snapshot = json.dumps(state)
        self.wfile.write(f"event: agents\ndata: {snapshot}\n\n: ping\n\n".encode())
        self.wfile.flush()
        with lock:
            changed = lock.wait(timeout=1.0)
            snapshot = json.dumps(state)
        if changed:
            self.wfile.write(f"event: agents\ndata: {snapshot}\n\n".encode())
            self.wfile.flush()
        self.close_connection = True

    def do_GET(self):
        self.handle_any("GET")

    def do_POST(self):
        self.handle_any("POST")

    def do_PUT(self):
        self.handle_any("PUT")

    def do_DELETE(self):
        self.handle_any("DELETE")


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 0
    server = ThreadingHTTPServer(("127.0.0.1", port), Hub)
    server.daemon_threads = True
    print(server.server_address[1], flush=True)
    server.serve_forever()
