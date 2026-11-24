#!/usr/bin/env python3
"""
Stand-in for the goggles receiver, for testing the Android app without goggles.

Implements the VisorSim "VMS2" TCP protocol (reverse-engineered from VisorSim.exe):
  hello (16 B LE): "VMS2", width, height, fps  -> reply b'R'
  then: u32 LE length + H.264 Annex-B frame; length 0 = keepalive.

Usage: python3 test_receiver.py [--port 9000] [--out dump.h264]
Then play the dump:  ffplay -f h264 dump.h264
"""
import argparse, socket, struct, time

def recv_exact(c, n):
    b = b""
    while len(b) < n:
        d = c.recv(n - len(b))
        if not d:
            raise ConnectionError("peer closed")
        b += d
    return b

def serve(c, out, max_frames=0):
    hello = recv_exact(c, 16)
    magic, w, h, fps = hello[:4], *struct.unpack("<III", hello[4:])
    if magic[:3] != b"VMS":
        raise ValueError(f"bad magic {magic!r}")
    print(f"hello: magic={magic!r} {w}x{h} @ {fps} fps")
    c.sendall(b"R")
    frames = keep = nbytes = 0
    t0 = last = time.time()
    while True:
        (n,) = struct.unpack("<I", recv_exact(c, 4))
        if n == 0:
            keep += 1
            continue
        data = recv_exact(c, n)
        if out: out.write(data)
        frames += 1; nbytes += n + 4
        if max_frames and frames >= max_frames:
            break
        now = time.time()
        if now - last >= 1:
            print(f"{frames/(now-t0):5.1f} fps avg  {nbytes*8/(now-t0)/1e6:6.2f} Mb/s  keepalives={keep}")
            last = now
    return frames, keep

if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=9000)
    ap.add_argument("--out", default="dump.h264")
    a = ap.parse_args()
    s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("0.0.0.0", a.port)); s.listen(1)
    print(f"listening on :{a.port}")
    while True:
        c, addr = s.accept(); print("connection from", addr)
        with open(a.out, "wb") as f:
            try: serve(c, f)
            except Exception as e: print("session ended:", e)
