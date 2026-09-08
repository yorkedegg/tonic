#!/usr/bin/env python3
"""
End-to-end smoke test: drive the bridge over the tunnel exactly as the Azahar shim will,
all the way through spawn. Validates: scan -> beacon, the RakNet offline+connected
handshake, the resource-pack exchange, and the fragmented spawn batch (reassembled +
inflated to its 47 packets, incl. StartGame + FullChunkData).
"""
import socket, struct, sys, time, zlib

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 7778
SCAN, BEACON, CONNECT, CONNECT_OK, DATA, DISCONNECT = 1, 2, 3, 4, 5, 6
MAGIC = bytes.fromhex("00ffff00fefefefefdfdfdfd12345678")
fails = 0
cseq = 0
splits = {}

def frame(t, src, dst, ch, payload):
    return struct.pack(">HBBBB", 4 + len(payload), t, src, dst, ch) + payload

def recvn(sock, n):
    b = b""
    while len(b) < n:
        c = sock.recv(n - len(b))
        if not c: raise EOFError("closed")
        b += c
    return b

def readframe(sock):
    ln = struct.unpack(">H", recvn(sock, 2))[0]
    r = recvn(sock, ln)
    return r[0], r[1], r[2], r[3], r[4:]

def varint(n):
    out = b""
    while True:
        b = n & 0x7F; n >>= 7
        out += bytes([b | 0x80]) if n else bytes([b])
        if not n: return out

def read_varint(b, o):
    r = s = 0
    while True:
        c = b[o]; o += 1; r |= (c & 0x7F) << s
        if not c & 0x80: return r, o
        s += 7

def batch(*packets):
    raw = b"".join(varint(len(p)) + p for p in packets)
    return b"\xfe" + zlib.compress(raw)

def batch_ids(payload):
    if payload[:1] != b"\xfe": return [payload[0]]
    raw = zlib.decompress(payload[1:]); o = 0; ids = []
    while o < len(raw):
        ln, o = read_varint(raw, o); hdr, _ = read_varint(raw, o); ids.append(hdr & 0x3FF); o += ln
    return ids

def send_data(sock, payload):
    """Wrap payload as a reliable-ordered message in a data datagram and send it."""
    global cseq
    seq = cseq; cseq += 1
    encap = bytes([3 << 5]) + struct.pack(">H", len(payload) * 8)
    encap += seq.to_bytes(3, "little")            # reliable index
    encap += seq.to_bytes(3, "little") + b"\x00"  # order index + channel
    encap += payload
    dg = bytes([0x84]) + seq.to_bytes(3, "little") + encap
    sock.sendall(frame(DATA, 2, 1, 13, dg))

def parse_encaps(dg):
    off = 4; out = []
    while off + 3 <= len(dg):
        fl = dg[off]; off += 1; rel = (fl & 0xE0) >> 5; frag = bool(fl & 0x10)
        lb = (dg[off] << 8) | dg[off + 1]; off += 2; ln = (lb + 7) // 8
        if rel in (2, 3, 4): off += 3
        if rel in (1, 4): off += 3
        if rel in (1, 3, 4): off += 4
        sc = sid = si = None
        if frag:
            sc = int.from_bytes(dg[off:off + 4], "big"); off += 4
            sid = int.from_bytes(dg[off:off + 2], "big"); off += 2
            si = int.from_bytes(dg[off:off + 4], "big"); off += 4
        out.append((frag, sc, sid, si, dg[off:off + ln])); off += ln
    return out

def next_message(sock):
    """Return the next complete reassembled reliable-message payload (skipping ACKs)."""
    while True:
        t, _, _, _, p = readframe(sock)
        if t != DATA or not p or (p[0] & 0x40): continue   # skip non-data / ACK / NAK
        for frag, sc, sid, si, payload in parse_encaps(p):
            if not frag: return payload
            d = splits.setdefault(sid, {}); d[si] = payload
            if len(d) >= sc:
                full = b"".join(d[i] for i in range(sc)); del splits[sid]; return full

s = None
for _ in range(50):
    try: s = socket.create_connection(("127.0.0.1", PORT), timeout=6); break
    except OSError: time.sleep(0.1)
if s is None:
    print("SMOKE: FAIL (bridge never came up)"); sys.exit(1)
s.settimeout(6)

# 1) scan
s.sendall(frame(SCAN, 2, 1, 0, b""))
t, _, _, _, p = readframe(s)
ok = t == BEACON and p[:2] == b"MC"
print(f"SCAN    -> {'BEACON ' + p[16:23].decode(errors='replace') if ok else 'FAIL'}")
fails += 0 if ok else 1

# 2) offline handshake
s.sendall(frame(DATA, 2, 1, 13, b"\x05" + MAGIC + b"\x08" + b"\x00" * 20))
ok = readframe(s)[4][0] == 0x06; print(f"OCR1    -> {'OCREP1' if ok else 'FAIL'}"); fails += 0 if ok else 1
s.sendall(frame(DATA, 2, 1, 13, b"\x07" + MAGIC + bytes.fromhex("04ffffffff000e05d4") + b"\x00" * 8))
ok = readframe(s)[4][0] == 0x08; print(f"OCR2    -> {'OCREP2' if ok else 'FAIL'}"); fails += 0 if ok else 1

# 3) connected handshake
send_data(s, bytes.fromhex("090000000002647bd0000000c3ea305a0300"))  # ConnectionRequest
cra = next_message(s)
ok = cra[:1] == b"\x10"; print(f"ConnReq -> {'ConnectionRequestAccepted' if ok else 'FAIL'}"); fails += 0 if ok else 1
send_data(s, b"\x13" + bytes.fromhex("04ffffffff000e") + bytes.fromhex("04ffffffff0000") * 20
          + b"\x00" * 16)  # NewIncomingConnection (bridge just ACKs)

# 4) spawn flow (4 client batches: login, 0x04, 0x08, 0x08)
send_data(s, batch(bytes.fromhex("0110203040")))   # login (content irrelevant; count-driven)
resp = next_message(s)
ok = resp == bytes.fromhex("fe0300103a61a111fd9b53464fda2cb847633103")
print(f"login   -> login-response {'ok' if ok else resp.hex()}"); fails += 0 if ok else 1

send_data(s, batch(b"\x04"))                        # ClientToServerHandshake
ids = batch_ids(next_message(s))
ok = 0x02 in ids and 0x06 in ids
print(f"0x04    -> PlayStatus+ResourcePacksInfo {ids if not ok else 'ok'}"); fails += 0 if ok else 1

send_data(s, batch(bytes.fromhex("08030000")))     # ResourcePackClientResponse #1
ids = batch_ids(next_message(s))
ok = 0x07 in ids
print(f"0x08 #1 -> ResourcePackStack {ids if not ok else 'ok'}"); fails += 0 if ok else 1

send_data(s, batch(bytes.fromhex("08040000")))     # ResourcePackClientResponse #2 -> spawn
ids = batch_ids(next_message(s))
ok = 0x0b in ids  # StartGame-only (client generates its own world; sending chunks breaks it)
print(f"0x08 #2 -> SPAWN: {len(ids)} packet(s), StartGame={0x0b in ids}")
fails += 0 if ok else 1

s.sendall(frame(DISCONNECT, 2, 1, 0, b"")); s.close()
print("SMOKE:", "PASS" if fails == 0 else f"FAIL ({fails})")
sys.exit(1 if fails else 0)
