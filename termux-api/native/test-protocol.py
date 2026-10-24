#!/usr/bin/env python3
"""E2E protocol test for the termux-api client against a mock daemon.

Mimics SocketListener: abstract unix socket, 2-byte big-endian length prefix,
JSON payload, response written back on the same connection.
"""
import json
import os
import socket
import struct
import subprocess
import sys
import threading
import time

LISTEN = "\0texmux.api://listen"
CLIENT = sys.argv[1] if len(sys.argv) > 1 else "/tmp/tapi"

results = []


def check(name, ok, detail=""):
    results.append((name, ok, detail))
    print(("PASS  " if ok else "FAIL  ") + name + ("   " + detail if detail else ""))


class MockDaemon(threading.Thread):
    def __init__(self, reply=b'{"ok":true}', delay=0.0, close_without_reply=False):
        super().__init__(daemon=True)
        self.reply = reply
        self.delay = delay
        self.close_without_reply = close_without_reply
        self.received = None
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.bind(LISTEN)
        self.sock.listen(4)
        self.ready = threading.Event()
        self.error = None

    def run(self):
        self.ready.set()
        try:
            conn, _ = self.sock.accept()
        except OSError as e:
            self.error = e
            return
        with conn:
            hdr = conn.recv(2, socket.MSG_WAITALL)
            (length,) = struct.unpack("!H", hdr)
            body = b""
            while len(body) < length:
                chunk = conn.recv(length - len(body))
                if not chunk:
                    break
                body += chunk
            self.received = body.decode()
            if self.close_without_reply:
                return
            if self.delay:
                time.sleep(self.delay)
            conn.sendall(self.reply)

    def stop(self):
        self.sock.close()


def run_client(args, timeout=20, stdin=None):
    t0 = time.time()
    p = subprocess.run([CLIENT] + args, capture_output=True, text=True,
                       timeout=timeout, input=stdin)
    return p.returncode, p.stdout, p.stderr, time.time() - t0


# ── 1. simple call: response reaches stdout, exit 0 ────────────────────────
d = MockDaemon(reply=b'{"percentage":42,"status":"CHARGING"}')
d.start()
d.ready.wait(2)
rc, out, err, _ = run_client(["BatteryStatus"])
d.join(5)
check("BatteryStatus: exit 0", rc == 0, f"rc={rc} err={err.strip()[:80]}")
check("BatteryStatus: body on stdout", out.strip() == '{"percentage":42,"status":"CHARGING"}', repr(out.strip()))
check("BatteryStatus: method sent", json.loads(d.received).get("method") == "BatteryStatus", d.received or "-")
d.stop()

# ── 2. extras encode with the right JSON types ─────────────────────────────
d = MockDaemon(reply=b'{"ok":true}')
d.start()
d.ready.wait(2)
rc, out, err, _ = run_client([
    "Toast", "--es", "text", 'hi "quoted" \\ back',
    "--ez", "short", "false", "--ei", "id", "7", "--ef", "ratio", "1.5",
])
d.join(5)
sent = json.loads(d.received) if d.received else {}
args = sent.get("args", {})
check("extras: string with quotes/backslash", args.get("text") == 'hi "quoted" \\ back', repr(args.get("text")))
check("extras: boolean false", args.get("short") is False, repr(args.get("short")))
check("extras: int 7", args.get("id") == 7, repr(args.get("id")))
check("extras: float 1.5", args.get("ratio") == 1.5, repr(args.get("ratio")))
check("extras: exit 0", rc == 0, f"rc={rc}")
d.stop()

# ── 3. --timeout is client-side only, never leaks into the payload ─────────
d = MockDaemon(reply=b'{"ok":true}')
d.start()
d.ready.wait(2)
rc, out, err, _ = run_client(["Vibrate", "--es", "duration", "100", "--timeout", "5"])
d.join(5)
sent = json.loads(d.received) if d.received else {}
check("--timeout: not sent to app", "--timeout" not in sent.get("args", {}), d.received or "-")
check("--timeout: args preserved", sent.get("args", {}).get("duration") == "100", d.received or "-")
d.stop()

# ── 4. handler that never answers: exit 3, honours --timeout ───────────────
d = MockDaemon(close_without_reply=True)
d.start()
d.ready.wait(2)
rc, out, err, elapsed = run_client(["Location", "--timeout", "1"], timeout=15)
d.join(5)
check("no reply: exit 3", rc == 3, f"rc={rc}")
check("no reply: fast (<=3s)", elapsed <= 3.0, f"{elapsed:.2f}s")
check("no reply: message on stderr", "no response" in err.lower(), err.strip()[:80])
d.stop()

# ── 5. no daemon at all: exit 2, no 30s hang ──────────────────────────────
rc, out, err, elapsed = run_client(["BatteryStatus"], timeout=20)
check("no daemon: exit 2", rc == 2, f"rc={rc}")
check("no daemon: fast (<=3s)", elapsed <= 3.0, f"{elapsed:.2f}s")
check("no daemon: actionable message", "missing" in err.lower() or "unreachable" in err.lower(), err.strip().splitlines()[0][:80] if err else "-")

# ── 6. --help and --version ───────────────────────────────────────────────
rc, out, err, _ = run_client(["--help"])
check("--help: exit 0", rc == 0, f"rc={rc}")
check("--help: documents --timeout", "--timeout" in out and "--es" in out, "")
rc, out, err, _ = run_client(["--version"])
check("--version: prints version", rc == 0 and out.strip().count(".") >= 2, out.strip())

# ── 7. stdin inlining for a stdin-capable method ──────────────────────────
d = MockDaemon(reply=b'{"ok":true}')
d.start()
d.ready.wait(2)
rc, out, err, _ = run_client(["Clipboard", "Set", "--es", "text", "x"], stdin="piped-data")
d.join(5)
sent = json.loads(d.received) if d.received else {}
check("stdin: base64 inlined", sent.get("stdin") == "cGlwZWQtZGF0YQ==", str(sent.get("stdin")))
check("stdin: length recorded", sent.get("stdinLen") == 10, str(sent.get("stdinLen")))
d.stop()

# ── 8. -a is order independent: intentAction stays at the top level ───────
# Regression: the client used to write the action into whatever buffer
# position it had reached, so "-a record" placed AFTER an extra landed
# inside "args" instead of at the top level. The app then never saw an
# Intent action and answered "Unknown command: null".
def action_case(label, args, expect_action):
    d = MockDaemon(reply=b'{"ok":true}')
    d.start()
    d.ready.wait(2)
    rc, out, err, _ = run_client(args)
    d.join(5)
    sent = json.loads(d.received) if d.received else {}
    has_top = "intentAction" in sent
    in_args = "intentAction" in (sent.get("args") or {})
    if expect_action is None:
        # No action requested: there must be no action anywhere.
        ok = not has_top and not in_args
    else:
        # Action requested: it must be at the top level and nowhere else.
        ok = has_top and not in_args and sent.get("intentAction") == expect_action
    check(f"-a order: {label}", ok, d.received or "-")
    d.stop()


action_case("action first", ["MicRecorder", "-a", "record"], "record")
action_case("action after one extra", ["MicRecorder", "--ei", "limit", "3000", "-a", "record"], "record")
action_case("action after two extras",
            ["MicRecorder", "--ei", "limit", "3000", "--es", "encoder", "aac", "-a", "record"], "record")
action_case("action in the middle", ["MicRecorder", "-a", "record", "--ei", "limit", "3000"], "record")
action_case("no action", ["MicRecorder", "--ei", "limit", "3000"], None)

passed = sum(1 for _, ok, _ in results if ok)
print(f"\n{passed}/{len(results)} checks passed")
sys.exit(0 if passed == len(results) else 1)
