#!/usr/bin/env python3
# Copyright 2026 Michal Tajchert
# SPDX-License-Identifier: Apache-2.0
"""Capture the exact first DATA payload of a `terminal.attach` channel as hex.

Usage: capture_first_paint.py [OUT_FILE]   (default: stdout)

Starts an isolated herdr-core session (flocket-test-<random>), connects with
`hc connect --session <it>`, negotiates framed mode (`hello`), runs `sh`, attaches at 80x24 and
writes the first DATA payload of the attach channel (the "first paint") as one line of hex.
The isolated server is always stopped afterwards. The default session is never touched.
Frame header (SPEC section 3): channel u32 LE, kind u8, length u32 LE; kind 0 = DATA.
"""
import json
import os
import secrets
import struct
import subprocess
import sys

# Socket paths are limited to ~103 bytes on macOS: keep the session name short.
session = "flocket-test-" + secrets.token_hex(2)
# Drop inherited HC_* variables (HC_TERMINAL_SOCKET etc. point at the caller's own session).
env = {k: v for k, v in os.environ.items() if not k.startswith("HC_")}
env.update(HC_SESSION=session, TMPDIR="/tmp")
out_path = sys.argv[1] if len(sys.argv) > 1 else None


def read_exact(f, n):
    buf = b""
    while len(buf) < n:
        chunk = f.read(n - len(buf))
        if not chunk:
            raise EOFError("hc connect closed")
        buf += chunk
    return buf


def read_frame(f):
    ch, kind, ln = struct.unpack("<IBI", read_exact(f, 9))
    return ch, kind, read_exact(f, ln)


def send_frame(f, ch, kind, payload):
    f.write(struct.pack("<IBI", ch, kind, len(payload)) + payload)
    f.flush()


def call(p, req_id, method, params, pending):
    req = {"jsonrpc": "2.0", "id": req_id, "method": method, "params": params}
    send_frame(p.stdin, 0, 0, json.dumps(req).encode())
    while True:
        ch, kind, data = read_frame(p.stdout)
        if ch == 0 and kind == 0:
            msg = json.loads(data)
            if msg.get("id") == req_id:
                if "error" in msg:
                    raise RuntimeError(msg["error"])
                return msg["result"]
        else:
            pending.append((ch, kind, data))  # early frames for a stream not yet named


def main():
    subprocess.run(["hc", "server", "start"], env=env, check=True,
                   stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        p = subprocess.Popen(["hc", "connect", "--session", session], env=env,
                             stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                             stderr=subprocess.DEVNULL)
        hello_req = {"jsonrpc": "2.0", "id": 0, "method": "hello",
                     "params": {"generation": 1, "framed": True}}
        p.stdin.write(json.dumps(hello_req).encode() + b"\n")
        p.stdin.flush()
        line = b""
        while not line.endswith(b"\n"):
            line += read_exact(p.stdout, 1)
        sys.stderr.write("hc version: %s\n" % json.loads(line)["result"].get("version"))
        pending = []
        run = call(p, 1, "terminal.run", {"argv": ["sh"]}, pending)
        term = run.get("terminal", run)
        if isinstance(term, dict):
            term = term.get("address")
        att = call(p, 2, "terminal.attach",
                   {"terminal": term, "cols": 80, "rows": 24}, pending)
        chan = att["channel"]
        payload = None
        for ch, kind, data in pending:
            if ch == chan and kind == 0:
                payload = data
                break
        while payload is None:
            ch, kind, data = read_frame(p.stdout)
            if ch == chan and kind == 0:
                payload = data
        hexs = payload.hex()
        if out_path:
            with open(out_path, "w") as f:
                f.write(hexs + "\n")
        else:
            print(hexs)
        sys.stderr.write("first DATA payload: %d bytes, starts with %s\n"
                         % (len(payload), hexs[:2]))
        p.stdin.close()
        p.wait(timeout=5)
    finally:
        subprocess.run(["hc", "server", "stop"], env=env,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


main()
