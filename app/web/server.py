#!/usr/bin/env python3
"""Static HTTP host for the RhodesLight web preview.

    python web/server.py                 # http://127.0.0.1:5173
    python web/server.py --port 8080 --host 0.0.0.0
"""

from __future__ import annotations

import argparse
import functools
import http.server
import os
import socket
import socketserver
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))


class Handler(http.server.SimpleHTTPRequestHandler):
    extensions_map = {
        **http.server.SimpleHTTPRequestHandler.extensions_map,
        ".js": "text/javascript; charset=utf-8",
        ".css": "text/css; charset=utf-8",
        ".html": "text/html; charset=utf-8",
        ".svg": "image/svg+xml",
        ".json": "application/json; charset=utf-8",
    }

    def end_headers(self) -> None:
        # The preview is edited live; never let the browser cache a stale frame.
        self.send_header("Cache-Control", "no-store, must-revalidate")
        super().end_headers()

    def log_message(self, fmt: str, *args) -> None:
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def main() -> int:
    parser = argparse.ArgumentParser(description="Serve the RhodesLight web preview")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=5173)
    args = parser.parse_args()

    handler = functools.partial(Handler, directory=ROOT)
    with Server((args.host, args.port), handler) as httpd:
        shown = "127.0.0.1" if args.host in ("0.0.0.0", "::") else args.host
        print(f"RhodesLight preview on http://{shown}:{args.port}", flush=True)
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\nstopped", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
