#!/usr/bin/env python3
"""Minimal Source-RCON client for driving the local test server console.

Used to stage and reset the demo world reproducibly, so a botched take just
means re-running a script rather than rebuilding a set by hand.

    scripts/rcon.py "time set day" "weather clear"
    scripts/rcon.py --file scripts/demo-world.txt
"""
import argparse
import os
import socket
import struct
import sys

HOST = os.environ.get("MSC_RCON_HOST", "127.0.0.1")
PORT = int(os.environ.get("MSC_RCON_PORT", "25575"))
PASSWORD = os.environ.get("MSC_RCON_PASSWORD", "minescripture-local")

LOGIN, COMMAND, RESPONSE = 3, 2, 0


class Rcon:
    def __init__(self, host=HOST, port=PORT, password=PASSWORD, timeout=10):
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self._req = 0
        if self._send(LOGIN, password)[0] == -1:
            raise SystemExit("RCON auth failed — check rcon.password in server.properties")

    def _send(self, kind, body):
        self._req += 1
        payload = struct.pack("<ii", self._req, kind) + body.encode("utf8") + b"\x00\x00"
        self.sock.sendall(struct.pack("<i", len(payload)) + payload)
        (length,) = struct.unpack("<i", self._recv_exactly(4))
        data = self._recv_exactly(length)
        req_id, _ = struct.unpack("<ii", data[:8])
        return req_id, data[8:-2].decode("utf8", errors="replace")

    def _recv_exactly(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise SystemExit("RCON connection closed early")
            buf += chunk
        return buf

    def run(self, command):
        return self._send(COMMAND, command)[1]

    def close(self):
        self.sock.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("commands", nargs="*")
    ap.add_argument("--file", help="file of commands, one per line; # comments allowed")
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args()

    cmds = list(args.commands)
    if args.file:
        with open(args.file, encoding="utf8") as fh:
            cmds += [ln.strip() for ln in fh
                     if ln.strip() and not ln.strip().startswith("#")]
    if not cmds:
        ap.error("nothing to run")

    con = Rcon()
    try:
        for cmd in cmds:
            out = con.run(cmd).strip()
            if not args.quiet:
                print(f"> {cmd}\n  {out}" if out else f"> {cmd}")
    finally:
        con.close()


if __name__ == "__main__":
    main()
