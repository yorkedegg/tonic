#!/usr/bin/env python3
"""
M0 stage-2: unwrap the RakNet + zlib layers on a captured MC3DS session and read
out the MCPE application packets.

Layers peeled, outer -> inner:
  UDS payload ([BRIDGE] TX/RX hex)  ->  RakNet datagram (0x8x header + u24 seq)
  -> encapsulated message(s) (reliability framing, + split/fragment reassembly)
  -> RakNet system msg  OR  0xFE MCPE batch (zlib) -> inner MCPE packets (varint-framed)

Reads ONE instance log (the host by default) because a host log already contains both
directions: TX = host->client, RX = client->host.

Usage:
  python3 m0_unwrap.py [HOST_LOG]        # default: captures/m0-run1-A-host.log
  python3 m0_unwrap.py --dump-ids A,B    # extra hexdump for these MCPE ids (hex, comma-sep)

NOTE: the [BRIDGE] hook caps each packet at 512 bytes (kBridgeDumpMax), so datagrams
larger than that are truncated. Small packets and the start of large ones (incl. the
Login protocol field) survive; full StartGame/chunk bodies need a re-capture with a
bigger cap. Truncated/incomplete fragment groups are reported, not silently dropped.
"""
import os
import re
import sys
import zlib

HOME = os.path.expanduser("~")
DEFAULT_LOG = os.path.join(HOME, "mc3ds-bridge/captures/m0-run1-A-host.log")

LINE_RE = re.compile(r"^\[\s*([\d.]+)\].*?\[BRIDGE\]\s+(TX|RX)\s+node=(\d+)->(\d+).*?data=([0-9a-f]*)")

# RakNet reliability -> which extra header fields are present
REL_RELIABLE = {2, 3, 4}       # has reliable message number (u24)
REL_SEQUENCED = {1, 4}         # has sequencing index (u24)
REL_ORDERED = {1, 3, 4}        # has ordering index (u24) + channel (u8)

RAKNET_NAMES = {
    0x00: "ConnectedPing", 0x01: "UnconnectedPing", 0x03: "ConnectedPong",
    0x05: "OpenConnectionRequest1", 0x06: "OpenConnectionReply1",
    0x07: "OpenConnectionRequest2", 0x08: "OpenConnectionReply2",
    0x09: "ConnectionRequest", 0x10: "ConnectionRequestAccepted",
    0x13: "NewIncomingConnection", 0x15: "DisconnectionNotification",
    0xa0: "NAK", 0xc0: "ACK", 0xfe: "McpeBatch",
}

# Best-effort MCPE packet-id names for the PE ~0.15-1.1 era. ADVISORY: exact numbering is
# protocol-version specific -- cross-check against the version printed from Login.
MCPE_NAMES = {
    0x01: "Login", 0x02: "PlayStatus", 0x03: "ServerToClientHandshake",
    0x04: "ClientToServerHandshake", 0x05: "Disconnect", 0x06: "ResourcePacksInfo",
    0x07: "ResourcePackStack", 0x08: "ResourcePackClientResponse", 0x09: "Text",
    0x0a: "SetTime", 0x0b: "StartGame", 0x0c: "AddPlayer", 0x0d: "AddEntity",
    0x0e: "RemoveEntity", 0x0f: "AddItemEntity", 0x11: "TakeItemEntity",
    0x12: "MoveEntity", 0x13: "MovePlayer", 0x15: "UpdateBlock", 0x1b: "LevelEvent",
    0x1f: "MobEquipment", 0x27: "SetEntityData", 0x28: "SetEntityMotion",
    0x2d: "SetHealth", 0x2e: "SetSpawnPosition", 0x2f: "Animate", 0x31: "Inventory/ContainerOpen",
    0x37: "InventorySlot", 0x3a: "FullChunkData/LevelChunk", 0x3b: "SetCommandsEnabled",
    0x45: "PlayerList", 0x8f: "SetLocalPlayerAsInitialized",
}


def u24le(b, o):
    return b[o] | (b[o + 1] << 8) | (b[o + 2] << 16)


def read_varint(b, o):
    shift = result = 0
    while o < len(b):
        c = b[o]; o += 1
        result |= (c & 0x7F) << shift
        if not (c & 0x80):
            return result, o
        shift += 7
    return result, o


def try_inflate(data):
    """Return (bytes, note). Tolerant of raw/zlib and of truncated streams (partial output)."""
    for wbits, tag in ((15, "zlib"), (-15, "raw"), (47, "auto")):
        try:
            d = zlib.decompressobj(wbits)
            out = d.decompress(data)
            out += d.flush()
            if out:
                return out, tag
        except zlib.error:
            # keep whatever partial output the object produced before erroring
            try:
                if d.unused_data is not None and len(out := d.decompress(b"")):
                    return out, tag + "/partial"
            except Exception:
                pass
    # some builds prefix a 4-byte length before the zlib stream
    for wbits, tag in ((15, "zlib+4"), (-15, "raw+4")):
        try:
            d = zlib.decompressobj(wbits)
            out = d.decompress(data[4:]) + d.flush()
            if out:
                return out, tag
        except zlib.error:
            continue
    return None, "FAILED"


class Reasm:
    """RakNet fragment reassembly for one direction."""
    def __init__(self):
        self.splits = {}  # split_id -> {index: bytes, count: n}

    def add_fragment(self, split_id, split_count, split_index, payload):
        s = self.splits.setdefault(split_id, {"count": split_count, "frags": {}})
        s["frags"][split_index] = payload
        if len(s["frags"]) >= s["count"]:
            full = b"".join(s["frags"][i] for i in sorted(s["frags"]))
            del self.splits[split_id]
            return full, True
        return None, False


def parse_datagram(data, reasm, out):
    if not data or not (data[0] & 0x80):
        return
    if data[0] & (0x40 | 0x20):  # ACK / NAK
        return
    off = 4  # 1 header byte + u24 datagram seq
    n = len(data)
    while off + 3 <= n:
        flags = data[off]; off += 1
        reliability = (flags & 0xE0) >> 5
        fragmented = bool(flags & 0x10)
        if off + 2 > n:
            break
        length_bits = (data[off] << 8) | data[off + 1]; off += 2
        length = (length_bits + 7) // 8
        if length == 0:
            break
        if reliability in REL_RELIABLE:
            off += 3
        if reliability in REL_SEQUENCED:
            off += 3
        if reliability in REL_ORDERED:
            off += 4  # u24 ordering index + u8 channel
        split_id = split_index = split_count = None
        if fragmented:
            if off + 10 > n:
                break
            split_count = int.from_bytes(data[off:off + 4], "big"); off += 4
            split_id = int.from_bytes(data[off:off + 2], "big"); off += 2
            split_index = int.from_bytes(data[off:off + 4], "big"); off += 4
        payload = data[off:off + length]; off += length
        if fragmented:
            full, done = reasm.add_fragment(split_id, split_count, split_index, payload)
            if done:
                out.append((full, None))
            # incomplete splits are reported at end-of-run, not emitted mid-stream
        else:
            out.append((payload, None))


def parse_batch(payload):
    """0xFE batch -> list of (mcpe_id, packet_bytes, note)."""
    inflated, tag = try_inflate(payload[1:])
    if not inflated:
        return [], f"inflate {tag}"
    pkts = []
    off = 0
    while off < len(inflated):
        ln, o2 = read_varint(inflated, off)
        if ln == 0 or o2 + ln > len(inflated):
            # not varint-framed (or truncated) -> treat remainder as one packet
            rest = inflated[off:]
            if rest:
                hdr, _ = read_varint(rest, 0)
                pkts.append((hdr & 0x3FF, rest, "unframed/tail"))
            break
        pkt = inflated[o2:o2 + ln]
        off = o2 + ln
        hdr, _ = read_varint(pkt, 0)
        pkts.append((hdr & 0x3FF, pkt, tag))
    return pkts, tag


def hexdump(b, n=96, indent="      "):
    b = b[:n]
    out = []
    for o in range(0, len(b), 16):
        c = b[o:o + 16]
        hx = " ".join(f"{x:02x}" for x in c)
        asc = "".join(chr(x) if 32 <= x < 127 else "." for x in c)
        out.append(f"{indent}{o:04x}  {hx:<47}  {asc}")
    return "\n".join(out)


def main():
    args = sys.argv[1:]
    dump_ids = set()
    if "--dump-ids" in args:
        i = args.index("--dump-ids")
        dump_ids = {int(x, 16) for x in args[i + 1].split(",")}
        del args[i:i + 2]
    log = args[0] if args else DEFAULT_LOG
    if not os.path.exists(log):
        print(f"missing log: {log}")
        return 1

    reasm = {"H>C": Reasm(), "C>H": Reasm()}
    events = []  # (t, direction, kind, id, bytes, note)
    partials = 0
    seen_big = set()  # content hashes of large batches, to collapse RakNet retransmits
    dropped_retx = 0

    with open(log, errors="replace") as f:
        for line in f:
            if "[BRIDGE]" not in line:
                continue
            m = LINE_RE.match(line)
            if not m:
                continue
            t = float(m.group(1))
            src, dst = int(m.group(3)), int(m.group(4))
            direction = "H>C" if src == 1 else "C>H"
            try:
                data = bytes.fromhex(m.group(5))
            except ValueError:
                continue
            msgs = []
            parse_datagram(data, reasm[direction], msgs)
            for payload, note in msgs:
                if note:
                    partials += 1
                if not payload:
                    continue
                if payload[0] == 0xFE:
                    if len(payload) > 256:  # collapse retransmits of large (spawn) batches
                        h = hash(payload)
                        if h in seen_big:
                            dropped_retx += 1
                            continue
                        seen_big.add(h)
                    inner, tag = parse_batch(payload)
                    for pid, pbytes, pnote in inner:
                        events.append((t, direction, "MCPE", pid, pbytes, note or pnote))
                else:
                    events.append((t, direction, "RAKNET", payload[0], payload, note))

    incomplete = [(d, sid, s["count"], len(s["frags"]))
                  for d in reasm for sid, s in reasm[d].splits.items()]

    mcpe = [e for e in events if e[2] == "MCPE"]
    rak = [e for e in events if e[2] == "RAKNET"]
    print(f"== M0 unwrap: {len(events)} messages  (MCPE={len(mcpe)} RakNet-system={len(rak)})  "
          f"retransmits collapsed: {dropped_retx}  incomplete splits: {len(incomplete)} ==")
    for d, sid, cnt, got in incomplete:
        print(f"   ! incomplete split {d} id={sid}: {got}/{cnt} fragments")
    print()

    # RakNet system message id tally
    print("-- RakNet system messages --")
    rc = {}
    for _, _, _, pid, _, _ in rak:
        rc[pid] = rc.get(pid, 0) + 1
    for pid, n in sorted(rc.items()):
        print(f"  0x{pid:02x} {RAKNET_NAMES.get(pid,'?'):26} x{n}")

    # MCPE id tally
    print("\n-- MCPE packet ids (advisory names; verify vs protocol below) --")
    mc = {}
    for _, _, _, pid, _, _ in mcpe:
        mc[pid] = mc.get(pid, 0) + 1
    for pid, n in sorted(mc.items(), key=lambda kv: -kv[1]):
        print(f"  0x{pid:02x} {MCPE_NAMES.get(pid,'?'):26} x{n}")

    # Protocol version from the first Login (0x01)
    print("\n-- LOGIN / protocol version --")
    login = next((e for e in mcpe if e[3] == 0x01), None)
    if login:
        pk = login[4]
        _, o = read_varint(pk, 0)  # skip packet header varint
        proto_be = int.from_bytes(pk[o:o + 4], "big") if len(pk) >= o + 4 else None
        proto_le = int.from_bytes(pk[o:o + 4], "little") if len(pk) >= o + 4 else None
        print(f"  Login found ({len(pk)} B). protocol candidate: BE={proto_be}  LE={proto_le}")
        print(f"  (map this number to a PocketMine/Nukkit release to lock the codec)")
        print(hexdump(pk, 64))
    else:
        print("  no Login packet decoded (likely truncated in first fragment) — re-capture with bigger cap")

    # Chronological trace, first-of-each hexdumped
    print("\n-- MCPE trace (first of each id hexdumped) --")
    seen = set()
    for t, d, _, pid, pk, note in mcpe:
        name = MCPE_NAMES.get(pid, "?")
        tag = f"  [{note}]" if note else ""
        first = pid not in seen
        seen.add(pid)
        print(f"  t={t:9.3f} {d} 0x{pid:02x} {name:24} {len(pk):5}B{tag}")
        if first or pid in dump_ids:
            print(hexdump(pk, 160 if pid in dump_ids else 96))
    return 0


if __name__ == "__main__":
    sys.exit(main())
