"""Isolated lamp for emulator UI checks; MuMu NAT reaches it at 10.0.2.2.

Only binds host loopback. Never contacts a physical lamp or stores Wi-Fi data.
"""
import json
import math
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs


state = dict(on=True, brightness=56, breathing=False, breath_period=6000,
             breath_floor=0, breath_curve=100)
started = time.monotonic()


class Lamp(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def respond(self):
        sample = dict(state, mac="02:00:00:00:00:44", device_id="rhodes-020000000044",
                      breath_phase=((time.monotonic() - started) / state["breath_period"] * 1000 * 2 * math.pi) % (2 * math.pi),
                      connected=True, ip="127.0.0.1", ssid="UU-Test")
        if self.path == "/api/wifi/ble":
            sample = dict(ready=True, name="RhodesLight-Test", pop_required=False, expires_ms=300000)
        body = json.dumps(sample).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        # Match a closing embedded server; do not let clients reuse this HTTP/1.0 socket.
        self.send_header("Connection", "close")
        self.close_connection = True
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        self.respond()

    def do_POST(self):
        global started
        if self.headers.get("X-Rhodes-Control") != "1":
            self.send_error(403)
            return
        size = int(self.headers.get("Content-Length", "0"))
        if size > 1024:
            self.send_error(413)
            return
        form = {key: values[-1] for key, values in parse_qs(self.rfile.read(size).decode()).items()}
        if self.path == "/api/light":
            if "brightness" in form:
                brightness = max(0, min(100, int(form["brightness"])))
                state["on"] = brightness > 0
                if brightness:
                    state["brightness"] = brightness
            if "on" in form:
                state["on"] = form["on"] == "1"
            state["breathing"] = False
        elif self.path == "/api/mode":
            state["breathing"] = form.get("mode") == "breathing"
            state["on"] = True
            started = time.monotonic()
        elif self.path == "/api/breath":
            for source, target in (("period", "breath_period"), ("floor", "breath_floor"), ("curve", "breath_curve")):
                if source in form:
                    state[target] = int(form[source])
        self.respond()


if __name__ == "__main__":
    print("Emulator fixture ready on 127.0.0.1:80", flush=True)
    ThreadingHTTPServer(("127.0.0.1", 80), Lamp).serve_forever()
