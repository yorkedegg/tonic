#!/usr/bin/env python3
"""
M0 recon parser for the MC3DS <-> Java bridge project.

Reads Azahar logs produced by the patched nwm_uds.cpp (the [BRIDGE] TX/RX/BEACON
hexdump hooks) and turns them into a merged, chronological packet trace, then
auto-checks several items from the M0 recon checklist:

  - Transport: RakNet offline magic present?  zlib (0x78) batching?  0xFE batch wrapper?
  - Login sequence: order of packets by first-byte packet ID.
  - Beacon application-data blob (what the fake host must broadcast).

Usage:
  python3 m0_parse.py [LOG ...]                # defaults to both instance logs
  python3 m0_parse.py --full                   # hexdump every packet, not just first-of-kind
  python3 m0_parse.py --max N                  # cap packets shown (default 80)

With no LOG args it reads the host (instance A) and client (instance B) logs:
  ~/Library/Application Support/Azahar/log/azahar_log.txt
  ~/azahar-instance-b/Library/Application Support/Azahar/log/azahar_log.txt
"""
import os
import re
import sys

HOME = os.path.expanduser("~")
DEFAULT_LOGS = [
    (os.path.join(HOME, "Library/Application Support/Azahar/log/azahar_log.txt"), "A"),
    (os.path.join(HOME, "azahar-instance-b/Library/Application Support/Azahar/log/azahar_log.txt"), "B"),
]

# RakNet offline connection magic (16 bytes). Its presence => transport is RakNet.
RAKNET_MAGIC = bytes.fromhex("00ffff00fefefefefdfdfdfd12345678")

LINE_RE = re.compile(
    r"^\[\s*([\d.]+)\]\s+Service\.NWM.*?\[BRIDGE\]\s+(TX|RX|BEACON)\s+(.*)$"
)
KV_RE = re.compile(r"(\w+)=([^\s]+)")


class Pkt:
    __slots__ = ("t", "inst", "dir", "src", "dst", "ch", "flags", "size", "data")

    def __init__(self, t, inst, d):
        self.t, self.inst, self.dir = t, inst, d
        self.src = self.dst = self.ch = self.flags = None
        self.size = 0
        self.data = b""

    @property
    def pid(self):
        return self.data[0] if self.data else None


def parse_line(line, inst):
    m = LINE_RE.match(line)
    if not m:
        return None
    t = float(m.group(1))
    direction = m.group(2)
    rest = m.group(3)
    p = Pkt(t, inst, direction)
    node = re.search(r"node=(\d+)->(\d+)", rest)
    if node:
        p.src, p.dst = int(node.group(1)), int(node.group(2))
    kv = dict(KV_RE.findall(rest))
    if "ch" in kv:
        p.ch = int(kv["ch"])
    if "flags" in kv:
        p.flags = int(kv["flags"], 0)
    if "size" in kv:
        try:
            p.size = int(kv["size"])
        except ValueError:
            pass
    if "data" in kv:
        try:
            p.data = bytes.fromhex(kv["data"])
        except ValueError:
            p.data = b""
    return p


def hexdump(b, indent="    "):
    out = []
    for off in range(0, len(b), 16):
        chunk = b[off:off + 16]
        hx = " ".join(f"{c:02x}" for c in chunk)
        asc = "".join(chr(c) if 32 <= c < 127 else "." for c in chunk)
        out.append(f"{indent}{off:04x}  {hx:<47}  {asc}")
    return "\n".join(out)


def classify(data):
    tags = []
    if not data:
        return tags
    if RAKNET_MAGIC in data:
        tags.append("RAKNET-MAGIC")
    b0 = data[0]
    if b0 == 0xFE:
        tags.append("0xFE-batch?")
    # zlib header: 0x78 followed by 0x01/0x9c/0xda (common compression levels)
    for i in range(min(len(data) - 1, 4)):
        if data[i] == 0x78 and data[i + 1] in (0x01, 0x5E, 0x9C, 0xDA):
            tags.append(f"zlib@{i}")
            break
    return tags


def main():
    args = sys.argv[1:]
    full = "--full" in args
    args = [a for a in args if a != "--full"]
    maxn = 80
    if "--max" in args:
        i = args.index("--max")
        maxn = int(args[i + 1])
        del args[i:i + 2]

    logs = [(a, os.path.basename(os.path.dirname(os.path.dirname(a))) or a) for a in args] \
        if args else [(p, i) for p, i in DEFAULT_LOGS if os.path.exists(p)]

    if not logs:
        print("No logs found. Pass log paths explicitly, or run a patched-Azahar session first.")
        return 1

    pkts = []
    for path, inst in logs:
        if not os.path.exists(path):
            print(f"! missing: {path}")
            continue
        with open(path, "r", errors="replace") as f:
            for line in f:
                if "[BRIDGE]" not in line:
                    continue
                p = parse_line(line, inst)
                if p:
                    pkts.append(p)

    pkts.sort(key=lambda p: (p.t, p.inst))
    tx = sum(1 for p in pkts if p.dir == "TX")
    rx = sum(1 for p in pkts if p.dir == "RX")
    be = sum(1 for p in pkts if p.dir == "BEACON")
    print(f"== M0 capture: {len(pkts)} bridge events  (TX={tx} RX={rx} BEACON={be})  from {len(logs)} log(s) ==\n")

    # ---- findings ----
    any_raknet = any("RAKNET-MAGIC" in classify(p.data) for p in pkts)
    any_zlib = any(t.startswith("zlib") for p in pkts for t in classify(p.data))
    any_fe = any(p.data and p.data[0] == 0xFE for p in pkts)
    print("-- TRANSPORT CHECKLIST --")
    print(f"  RakNet offline magic seen : {'YES -> transport is RakNet' if any_raknet else 'no'}")
    print(f"  zlib (0x78) batching seen : {'YES' if any_zlib else 'no'}")
    print(f"  0xFE batch first-byte seen: {'YES' if any_fe else 'no'}")

    ids = {}
    for p in pkts:
        if p.dir in ("TX", "RX") and p.pid is not None:
            ids.setdefault(p.pid, 0)
            ids[p.pid] += 1
    print("\n-- PACKET IDs (first payload byte) by frequency --")
    for pid, n in sorted(ids.items(), key=lambda kv: -kv[1]):
        print(f"  0x{pid:02x}  x{n}")

    beacons = [p for p in pkts if p.dir == "BEACON"]
    if beacons:
        b = beacons[0]
        print(f"\n-- BEACON application-data ({b.size} bytes) --")
        print(hexdump(b.data))

    # ---- chronological trace ----
    print("\n-- TRACE (login sequence first) --")
    seen_kinds = set()
    shown = 0
    for p in pkts:
        if shown >= maxn:
            print(f"    ... ({len(pkts) - shown} more; use --max to raise)")
            break
        kind = (p.dir, p.pid, p.ch)
        first = kind not in seen_kinds
        seen_kinds.add(kind)
        tags = " ".join(classify(p.data))
        pids = f"id=0x{p.pid:02x}" if p.pid is not None else ""
        route = f"{p.src}->{p.dst}" if p.src is not None else ""
        print(f"[{p.inst}] t={p.t:9.3f} {p.dir:6} {route:5} ch{p.ch} size={p.size:<4} {pids} {tags}")
        if full or first:
            print(hexdump(p.data))
        shown += 1

    print("\n(first occurrence of each (dir,id,channel) is hexdumped; --full dumps all)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
