package bridge.transport;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * RakNet v8 server session for one MC3DS client. Drives the offline + connected handshake,
 * ACKs inbound datagrams, and sends reliable-ordered messages (fragmenting to the MTU).
 * Packet formats are reproduced byte-for-byte from the M0 capture (see dbg_hs extraction):
 * offline OCREP1/OCREP2, and connected ConnectionRequestAccepted / ConnectedPong, the last
 * two using the MC3DS dialect's 20 internal SystemAddresses.
 *
 * <p>The tunnel underneath is TCP, so it was assumed nothing could be lost and retransmission was
 * left out. That was wrong: the client NAKs, in bursts of hundreds of datagrams, and because a
 * reliable-ordered channel holds everything behind a gap, one unanswered NAK stalls the session
 * for good — break-progress events, block updates and chunks all stop arriving while the link
 * still looks healthy. So NAKs are answered here.
 */
public final class RakNetSession {

    /** Upper-layer callbacks (implemented by the protocol/spawn layer). */
    public interface Handler {
        /** Fired once, after the client's NewIncomingConnection: the RakNet link is up. */
        void onConnected(RakNetSession session);
        /** A delivered game payload: an MCPE 0xFE batch, the 0x40 hello, or 0x04, etc. */
        void onGamePacket(RakNetSession session, byte[] payload);
    }

    private static final int DGRAM_HEADER = 4;          // 1 flags + 3 seq
    /** RakNet's MTU counts the IP (20) + UDP (8) headers, so only MTU-28 is ours to fill.
     *  We were sizing datagrams to the full 1492 and every multi-fragment message failed:
     *  the client ACKed the oversized frames but reassembled garbage and left with a generic
     *  "disconnected". The real host capped its fragment datagrams at 1456 B, and the client's
     *  own MTU probe pads to exactly 1464 (= 1492 - 28), which is the real ceiling. */
    private static final int IP_UDP_HEADER = 28;
    private static final int USABLE = RakNet.MTU - IP_UDP_HEADER;   // 1464
    private static final int ENCAP_RO_HEADER = 10;       // reliable-ordered, unfragmented
    private static final int ENCAP_RO_FRAG_HEADER = 20;  // reliable-ordered, fragmented

    /** A real host answers ConnectedPing with an UNRELIABLE pong (env-gated for A/B testing). */
    private static final boolean PONG_UNRELIABLE = "1".equals(System.getenv("MC3DS_PONG_UNRELIABLE"));

    private final Consumer<byte[]> out;   // emits one raw RakNet datagram (tunnel wraps it in DATA)
    private final Handler handler;

    /** Overridable for tests so builder output can be checked byte-for-byte. */
    public long serverGuid = RakNet.SERVER_GUID;
    public LongSupplier clock = System::currentTimeMillis;

    // outbound reliability state
    private int outSeq = 0;
    private int reliableIndex = 0;
    private int orderIndex = 0;
    private int splitId = 0;

    // inbound fragment reassembly (rarely used in M1 — client packets are small)
    private final Map<Integer, Map<Integer, byte[]>> splits = new HashMap<>();
    private boolean connected = false;

    public RakNetSession(Consumer<byte[]> out, Handler handler) {
        this.out = out;
        this.handler = handler;
    }

    // ---- inbound ----

    private static final java.util.logging.Logger log =
            java.util.logging.Logger.getLogger("bridge.raknet");

    /** Feed one raw payload from a tunnel DATA frame (an offline packet or a datagram). */
    public void onRaknet(byte[] data) {
        if (data.length == 0) return;
        if ((data[0] & RakNet.FLAG_VALID) == 0) {
            log.info(String.format("RX offline id=0x%02x", data[0] & 0xFF));
            onOffline(data);
            return;
        }
        if (Datagram.isNak(data)) {
            resend(Datagram.parseAckSeqs(data));
            return;
        }
        if (Datagram.isAck(data)) { log.info("RX ACK seqs=" + Datagram.parseAckSeqs(data)); return; }
        Datagram dg = Datagram.decode(data);
        synchronized (this) { out.accept(Datagram.ack(dg.seq)); }
        StringBuilder ids = new StringBuilder();
        for (Encapsulated e : dg.messages) ids.append(String.format("0x%02x ", e.payload[0] & 0xFF));
        log.info("RX dg seq=" + dg.seq + " msgs=[" + ids.toString().trim() + "]");
        for (Encapsulated e : dg.messages) {
            byte[] full = reassemble(e);
            if (full != null) onMessage(full);
        }
    }

    private void onOffline(byte[] d) {
        switch (d[0] & 0xFF) {
            case RakNet.ID_OPEN_CONNECTION_REQUEST_1 -> { log.info("TX OCREP1"); out.accept(openConnectionReply1()); }
            case RakNet.ID_OPEN_CONNECTION_REQUEST_2 -> { log.info("TX OCREP2"); out.accept(openConnectionReply2()); }
            default -> { /* ignore other offline traffic */ }
        }
    }

    private void onMessage(byte[] p) {
        switch (p[0] & 0xFF) {
            case RakNet.ID_CONNECTION_REQUEST -> sendReliable(connectionRequestAccepted(p));
            case RakNet.ID_NEW_INCOMING_CONNECTION -> {
                if (!connected) { connected = true; handler.onConnected(this); }
            }
            case RakNet.ID_CONNECTED_PING -> {
                byte[] pong = connectedPong(p);
                if (PONG_UNRELIABLE) sendUnreliable(pong); else sendReliable(pong);
            }
            case RakNet.ID_DISCONNECTION_NOTIFICATION -> { /* client left */ }
            default -> handler.onGamePacket(this, p); // 0xFE batch, 0x40 hello, 0x04, game packets
        }
    }

    private byte[] reassemble(Encapsulated e) {
        if (!e.fragmented) return e.payload;
        Map<Integer, byte[]> parts = splits.computeIfAbsent(e.splitId, k -> new HashMap<>());
        parts.put(e.splitIndex, e.payload);
        if (parts.size() < e.splitCount) return null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        for (int i = 0; i < e.splitCount; i++) bos.writeBytes(parts.get(i));
        splits.remove(e.splitId);
        return bos.toByteArray();
    }

    // ---- outbound ----

    /** Sends a connected message reliable-ordered on channel 0, fragmenting to the MTU.
     *  Synchronized so a background stream thread and the receive loop can both send safely. */
    public synchronized void sendReliable(byte[] payload) {
        int maxUnfrag = USABLE - DGRAM_HEADER - ENCAP_RO_HEADER;
        if (payload.length <= maxUnfrag) {
            Encapsulated e = new Encapsulated();
            e.reliability = Encapsulated.RELIABLE_ORDERED;
            e.reliableIndex = reliableIndex++;
            e.orderIndex = orderIndex++;
            e.orderChannel = 0;
            e.payload = payload;
            sendOne(e);
            return;
        }
        int maxFrag = USABLE - DGRAM_HEADER - ENCAP_RO_FRAG_HEADER;
        int count = (payload.length + maxFrag - 1) / maxFrag;
        int sid = splitId++ & 0xFFFF;
        int oi = orderIndex++;              // all fragments of one message share the order index
        for (int i = 0; i < count; i++) {
            int from = i * maxFrag, to = Math.min(from + maxFrag, payload.length);
            Encapsulated e = new Encapsulated();
            e.reliability = Encapsulated.RELIABLE_ORDERED;
            e.reliableIndex = reliableIndex++;  // each fragment is its own reliable message
            e.orderIndex = oi;
            e.orderChannel = 0;
            e.fragmented = true;
            e.splitCount = count;
            e.splitId = sid;
            e.splitIndex = i;
            e.payload = java.util.Arrays.copyOfRange(payload, from, to);
            sendOne(e);
            // M3: the real host trickled its 30-fragment spawn out ~2 datagrams per 300ms
            // (93.6s total, 1015 retransmits) and the guest accepted it; we deliver the same
            // bytes in ~20ms and the client rejects them. MC3DS_FRAG_BURST/MC3DS_FRAG_MS
            // reproduce that pacing so it can be tested as a variable.
            if (FRAG_BURST > 0 && (i + 1) % FRAG_BURST == 0 && i + 1 < count) {
                try {
                    Thread.sleep(FRAG_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static final int FRAG_BURST = envInt("MC3DS_FRAG_BURST", 0);
    private static final int FRAG_MS = envInt("MC3DS_FRAG_MS", 300);

    private static int envInt(String name, int def) {
        try {
            return Integer.parseInt(System.getenv().getOrDefault(name, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private void sendOne(Encapsulated e) {
        Datagram d = new Datagram();
        d.seq = outSeq++;
        d.messages.add(e);
        log.info(String.format("TX dg seq=%d id=0x%02x%s", d.seq, e.payload[0] & 0xFF,
                e.fragmented ? " frag " + e.splitIndex + "/" + e.splitCount : ""));
        history.put(d.seq, e);
        out.accept(d.encode());
    }

    /**
     * Datagrams recently sent, so a NAK can be answered. RakNet numbers datagrams, not messages,
     * so the client asks for a datagram sequence back and we re-send what was inside it under a
     * fresh sequence number; the receiver de-duplicates on the reliable index.
     */
    private final Map<Integer, Encapsulated> history = new java.util.LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, Encapsulated> eldest) {
            return size() > HISTORY;
        }
    };

    /** How many datagrams to keep for retransmission — a few seconds of a chunk-heavy stream. */
    private static final int HISTORY = 4096;

    private synchronized void resend(java.util.List<Integer> seqs) {
        int found = 0;
        for (int seq : seqs) {
            Encapsulated e = history.get(seq);
            if (e == null) continue;   // already evicted; nothing we can do for that one
            found++;
            sendOne(e);
        }
        log.warning("RX NAK for " + seqs.size() + " datagrams, resent " + found
                + (found < seqs.size() ? " (" + (seqs.size() - found) + " already evicted)" : ""));
    }

    // ---- packet builders (reproduce captured MC3DS bytes) ----

    public byte[] openConnectionReply1() {
        Buf b = new Buf(new byte[0]);
        b.w8(RakNet.ID_OPEN_CONNECTION_REPLY_1).wbytes(RakNet.MAGIC).w64be(serverGuid)
                .w8(0)               // no security
                .w16be(RakNet.MTU);
        return b.out();
    }

    public byte[] openConnectionReply2() {
        Buf b = new Buf(new byte[0]);
        b.w8(RakNet.ID_OPEN_CONNECTION_REPLY_2).wbytes(RakNet.MAGIC).w64be(serverGuid);
        writeAddress(b, 0x0e);       // client SystemAddress (dummy, matches capture)
        b.w16be(RakNet.MTU).w8(0);   // MTU + no security
        return b.out();
    }

    public byte[] connectionRequestAccepted(byte[] request) {
        // request: 09 clientGUID(8) requestTime(8) security(1)
        Buf rb = new Buf(request);
        rb.skip(9);
        long requestTime = rb.u64be();
        Buf b = new Buf(new byte[0]);
        b.w8(RakNet.ID_CONNECTION_REQUEST_ACCEPTED);
        writeAddress(b, 0x0e);       // client system address
        b.w16be(0);                  // system index
        for (int i = 0; i < 20; i++) writeAddress(b, 0x00); // 20 internal addresses (MC3DS dialect)
        b.w64be(requestTime);        // echo request time
        b.w64be(clock.getAsLong());  // our time
        return b.out();
    }

    /** Sends one unreliable connected message (no reliable/order index) — matches a real host's pong. */
    public synchronized void sendUnreliable(byte[] payload) {
        Encapsulated e = new Encapsulated();
        e.reliability = Encapsulated.UNRELIABLE;
        e.payload = payload;
        sendOne(e);
    }

    public byte[] connectedPong(byte[] ping) {
        Buf pb = new Buf(ping);
        pb.skip(1);
        long pingTime = pb.u64be();
        Buf b = new Buf(new byte[0]);
        b.w8(RakNet.ID_CONNECTED_PONG).w64be(pingTime).w64be(clock.getAsLong());
        return b.out();
    }

    /** RakNet IPv4 SystemAddress: version(4) + inverted IP (0xffffffff = null) + port. */
    private static void writeAddress(Buf b, int port) {
        b.w8(4).w8(0xff).w8(0xff).w8(0xff).w8(0xff).w16be(port);
    }
}
