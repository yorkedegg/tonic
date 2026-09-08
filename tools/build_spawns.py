#!/usr/bin/env python3
"""Generate spawn-batch variants from the captured spawn (src/main/resources/spawn_realjoin.bin).

These are the fixtures the M3 investigation used; they are derived, not captured,
so they are gitignored and rebuilt with this script:

    python3 tools/build_spawns.py

Encodes what we know about the MC3DS wire format (see PACKET-FORMATS.md):

  batch   0xFE | zlib( [varint len | packet]* )
  0x3a    3a | chunkX(u8) | chunkZ(u8) | varint payloadLen | payload
  payload count(u8) | count x [ ids 4096 | meta 2048 | skylight 2048 | blocklight 2048 ]
          | <undecoded region> | heightmap 256 x u16 LE | biome 256 | 2
  ids     one byte per block, index (x*16 + z)*16 + localY, section s covers y = 16s..16s+15
"""
import os, sys, zlib

RES = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources")
SEC_FIXED = 10242        # flagA 1 + ids 4096 + meta 2048 + 2048 + 2048 + flagB 1
LIGHT = 4096             # present only when flagB != 0 -> sections are VARIABLE length
TAIL = 770               # heightmap(512) + biome(256) + 2


def varint(n):
    out = b""
    while True:
        b = n & 0x7F
        n >>= 7
        out += bytes([b | (0x80 if n else 0)])
        if not n:
            return out


def unbatch(path):
    """Split a 0xFE batch into its packets."""
    inner = zlib.decompress(open(path, "rb").read()[1:])
    pkts, i = [], 0
    while i < len(inner):
        n = s = 0
        while True:
            b = inner[i]; i += 1
            n |= (b & 0x7F) << s; s += 7
            if not (b & 0x80):
                break
        pkts.append(inner[i:i + n]); i += n
    return pkts


def batch(pkts):
    body = b"".join(varint(len(p)) + p for p in pkts)
    co = zlib.compressobj(6, zlib.DEFLATED, 15)
    return b"\xfe" + co.compress(body) + co.flush(), len(body)


def chunk_payload_offset(pkt):
    """Byte offset of the payload inside a 0x3a packet."""
    j, n, s = 3, 0, 0
    while True:
        b = pkt[j]; j += 1
        n |= (b & 0x7F) << s; s += 7
        if not (b & 0x80):
            break
    return j


def set_ids(pkt, fn, light=None):
    """Rewrite every section's block ids (and optionally its light), walking the
    variable-length sections. fn(section, x, z, localY) -> block id."""
    p = bytearray(pkt)
    base = chunk_payload_offset(p)
    pos = base + 1
    for sec in range(p[base]):
        ids = pos + 1
        buf = bytearray(4096)
        lit = bytearray(4096)
        for x in range(16):
            for z in range(16):
                for ly in range(16):
                    i = (x * 16 + z) * 16 + ly
                    buf[i] = fn(sec, x, z, ly)
                    if light:
                        lit[i] = light(sec, x, z, ly)
        p[ids:ids + 4096] = buf
        flag_b = pos + SEC_FIXED - 1
        has = p[flag_b] != 0
        if light and has:
            p[flag_b + 1:flag_b + 1 + LIGHT] = lit
        pos += SEC_FIXED + (LIGHT if has else 0)
    return bytes(p)


def write(name, data, note=""):
    for d in (RES, os.path.join(RES, "..", "..", "..", "build", "resources", "main")):
        d = os.path.normpath(d)
        if os.path.isdir(d):
            open(os.path.join(d, name), "wb").write(data)
    print("  %-20s %7d B  %s" % (name, len(data), note))


def main():
    src = os.path.join(RES, "spawn_realjoin.bin")
    pkts = unbatch(src)
    # 0x0c AddPlayer / 0x0d AddEntity put a player model at our own spawn point —
    # the camera ends up inside its head, which reads as a wall of blocks. Drop them.
    noent = [p for p in pkts if p[0] not in (0x0C, 0x0D)]
    chunks = [p for p in pkts if p[0] == 0x3A]
    print("source: %d packets, %d chunks" % (len(pkts), len(chunks)))

    b, n = batch(noent)
    write("sp_real_np.bin", b, "captured chunks, no entities (%d B inflated)" % n)

    # A chunk's mesh is only built once its NEIGHBOURS are loaded. The captured 2x3
    # area leaves every chunk short a neighbour, so nothing draws. A 4x4 grid gives
    # the interior chunks a full ring and the world becomes visible.
    tmpl = min(chunks, key=len)
    grid = [p for p in noent if p[0] != 0x3A]
    for cx in range(4):
        for cz in range(4):
            c = bytearray(tmpl); c[1] = cx; c[2] = cz
            grid.append(bytes(c))
    b, n = batch(grid)
    write("sp_grid16.bin", b, "4x4 chunk grid — RENDERS (%d B inflated)" % n)

    flat = [set_ids(p, lambda s, x, z, ly: (lambda gy: 7 if gy == 0 else 1 if gy <= 61
            else 3 if gy == 62 else 2 if gy == 63 else 0)(s * 16 + ly))
            if p[0] == 0x3A else p for p in noent]
    b, n = batch(flat)
    write("sp_flat_np.bin", b, "flat world, surface y=63 (%d B inflated)" % n)

    # Round-trip proof: paint one section gold and it appears exactly there.
    paint = []
    for p in noent:
        if p[0] == 0x3A and p[1] == 0 and p[2] == 0:
            q = bytearray(p); off = chunk_payload_offset(q)
            o = off + 1 + 5 * SECTION
            q[o:o + 4096] = bytes([41]) * 4096      # 41 = gold block, y = 80..95
            p = bytes(q)
        paint.append(p)
    b, n = batch(paint)
    write("sp_paint.bin", b, "section 5 painted gold (%d B inflated)" % n)


if __name__ == "__main__":
    main()
