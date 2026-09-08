package bridge.transport;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Validates {@link RakNetSession}: handshake builders reproduce captured MC3DS bytes
 * exactly, MTU fragmentation round-trips, and the inbound handshake drives the right
 * outbound packets. Run with {@code gradle raknetSessionSelfTest}.
 */
public final class RakNetSessionSelfTest {

    private static final HexFormat HEX = HexFormat.of();
    private static int failures = 0;

    // Captured server GUID and templates (from dbg_hs on m0-run2-A-host.log).
    private static final long CAP_GUID = 0x0000000004dbd8b8L;
    private static final String OCREP1 = "0600ffff00fefefefefdfdfdfd123456780000000004dbd8b80005d4";
    private static final String OCREP2 = "0800ffff00fefefefefdfdfdfd123456780000000004dbd8b804ffffffff000e05d400";
    private static final String CONN_REQ = "090000000002647bd0000000c3ea305a0300";
    private static final String PING = "00000000c3ea305a67";
    private static final String PONG = "03000000c3ea305a67000000c3ea305d65";

    public static void main(String[] args) {
        // ---- A) builder byte-exactness vs capture ----
        List<byte[]> sent = new ArrayList<>();
        RakNetSession s = new RakNetSession(sent::add, noopHandler());
        s.serverGuid = CAP_GUID;

        checkBytes("OCREP1", s.openConnectionReply1(), HEX.parseHex(OCREP1));
        checkBytes("OCREP2", s.openConnectionReply2(), HEX.parseHex(OCREP2));

        s.clock = () -> 0x000000c3ea305d03L;
        checkBytes("ConnectionRequestAccepted", s.connectionRequestAccepted(HEX.parseHex(CONN_REQ)),
                HEX.parseHex(expectedCRA()));

        s.clock = () -> 0x000000c3ea305d65L;
        checkBytes("ConnectedPong", s.connectedPong(HEX.parseHex(PING)), HEX.parseHex(PONG));

        // ---- B) fragmentation round-trip ----
        List<byte[]> frag = new ArrayList<>();
        RakNetSession fs = new RakNetSession(frag::add, noopHandler());
        byte[] big = new byte[2000];
        for (int i = 0; i < big.length; i++) big[i] = (byte) (i * 7);
        fs.sendReliable(big);
        check("fragmented into >1 datagram", frag.size() > 1 ? 1 : 0, 1);
        checkBytes("fragments reassemble to original", reassembleAll(frag), big);

        List<byte[]> small = new ArrayList<>();
        RakNetSession ss = new RakNetSession(small::add, noopHandler());
        byte[] tiny = HEX.parseHex("fe0102030405");
        ss.sendReliable(tiny);
        check("small payload = 1 datagram", small.size(), 1);
        Encapsulated se = Datagram.decode(small.get(0)).messages.get(0);
        check("small not fragmented", se.fragmented ? 1 : 0, 0);
        checkBytes("small payload intact", se.payload, tiny);

        // ---- C) inbound handshake flow ----
        List<byte[]> outC = new ArrayList<>();
        boolean[] connected = {false};
        List<byte[]> game = new ArrayList<>();
        RakNetSession cs = new RakNetSession(outC::add, new RakNetSession.Handler() {
            public void onConnected(RakNetSession x) { connected[0] = true; }
            public void onGamePacket(RakNetSession x, byte[] p) { game.add(p); }
        });
        cs.serverGuid = CAP_GUID;
        cs.clock = () -> 0x000000c3ea305d03L;

        cs.onRaknet(HEX.parseHex("05" + "00ffff00fefefefefdfdfdfd12345678" + "08" + "00".repeat(20)));
        check("OCR1 -> one reply", outC.size(), 1);
        checkBytes("OCR1 -> OCREP1", outC.get(0), HEX.parseHex(OCREP1));

        outC.clear();
        cs.onRaknet(datagram(0, Encapsulated.RELIABLE, HEX.parseHex(CONN_REQ)));
        check("ConnReq -> ack + reply", outC.size(), 2);
        check("first is ACK", Datagram.isAck(outC.get(0)) ? 1 : 0, 1);
        byte[] cra = Datagram.decode(outC.get(1)).messages.get(0).payload;
        checkBytes("ConnReq -> ConnectionRequestAccepted", cra, HEX.parseHex(expectedCRA()));

        outC.clear();
        cs.onRaknet(datagram(1, Encapsulated.RELIABLE,
                HEX.parseHex("13" + "04ffffffff000e" + "04ffffffff0000".repeat(20)
                        + "000000c3ea305d03" + "000000c3ea305a67")));
        check("NewIncomingConnection fires onConnected", connected[0] ? 1 : 0, 1);

        outC.clear();
        cs.onRaknet(datagram(2, Encapsulated.UNRELIABLE, HEX.parseHex(PING)));
        check("Ping -> ack + pong", outC.size(), 2);
        byte[] pong = Datagram.decode(outC.get(1)).messages.get(0).payload;
        check("pong id", pong[0] & 0xFF, RakNet.ID_CONNECTED_PONG);

        outC.clear();
        cs.onRaknet(datagram(3, Encapsulated.RELIABLE_ORDERED, HEX.parseHex("fedeadbeef")));
        check("game packet delivered", game.size(), 1);
        checkBytes("game payload", game.get(0), HEX.parseHex("fedeadbeef"));

        if (failures == 0) {
            System.out.println("RakNetSessionSelfTest: PASS (handshake matches capture; fragmentation + flow OK)");
        } else {
            System.out.println("RakNetSessionSelfTest: FAIL (" + failures + " checks)");
            System.exit(1);
        }
    }

    private static String expectedCRA() {
        StringBuilder sb = new StringBuilder("10" + "04ffffffff000e" + "0000");
        for (int i = 0; i < 20; i++) sb.append("04ffffffff0000");
        return sb.append("000000c3ea305a03").append("000000c3ea305d03").toString();
    }

    /** Wraps a payload as a single-message data datagram with the given seq/reliability. */
    private static byte[] datagram(int seq, int reliability, byte[] payload) {
        Encapsulated e = new Encapsulated();
        e.reliability = reliability;
        e.reliableIndex = reliability == Encapsulated.UNRELIABLE ? -1 : seq;
        if (Encapsulated.ordered(reliability)) { e.orderIndex = 0; e.orderChannel = 0; }
        e.payload = payload;
        Datagram d = new Datagram();
        d.seq = seq;
        d.messages.add(e);
        return d.encode();
    }

    private static byte[] reassembleAll(List<byte[]> datagrams) {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        for (byte[] dg : datagrams) {
            for (Encapsulated e : Datagram.decode(dg).messages) bos.writeBytes(e.payload);
        }
        return bos.toByteArray();
    }

    private static RakNetSession.Handler noopHandler() {
        return new RakNetSession.Handler() {
            public void onConnected(RakNetSession s) {}
            public void onGamePacket(RakNetSession s, byte[] p) {}
        };
    }

    private static void check(String what, int got, int want) {
        if (got != want) { System.out.println("  FAIL " + what + ": got " + got + " want " + want); failures++; }
    }

    private static void checkBytes(String what, byte[] got, byte[] want) {
        if (!java.util.Arrays.equals(got, want)) {
            System.out.println("  FAIL " + what + ":\n    got  " + HEX.formatHex(got) + "\n    want " + HEX.formatHex(want));
            failures++;
        }
    }
}
