package bridge.transport;

import java.util.HexFormat;

/**
 * Validates the RakNet codec against REAL bytes captured from an MC3DS session in M0
 * (not just self-consistency). Run with {@code gradle raknetSelfTest}.
 */
public final class RakNetCodecSelfTest {

    private static final HexFormat HEX = HexFormat.of();
    private static int failures = 0;

    public static void main(String[] args) {
        // 1) Real OpenConnectionReply2 (offline). Ends with MTU 0x05d4 and doSecurity 0x00.
        byte[] ocrep2 = HEX.parseHex("0800ffff00fefefefefdfdfdfd123456780000000004dbd8b804ffffffff000e05d400");
        Buf b = new Buf(ocrep2);
        check("OCREP2 id", b.u8(), RakNet.ID_OPEN_CONNECTION_REPLY_2);
        checkBytes("OCREP2 magic", b.bytes(16), RakNet.MAGIC);
        b.u64be();                       // server GUID (value is capture-specific)
        b.u8(); b.bytes(4); b.u16be();   // client SystemAddress: version + IPv4 + port
        check("OCREP2 MTU", b.u16be(), RakNet.MTU);
        check("OCREP2 doSecurity", b.u8(), 0);

        // 2) Real connected datagram carrying a RELIABLE ConnectedPing.
        //    84 |03 00 00 seq| 40 flags |00 48 =9B| 01 00 00 relIdx| payload(0x00 ...)
        byte[] pingDg = HEX.parseHex("8403000040004801000000000000c3ea307097");
        check("datagram isValid", Datagram.isValid(pingDg) ? 1 : 0, 1);
        check("datagram isAck", Datagram.isAck(pingDg) ? 1 : 0, 0);
        Datagram dg = Datagram.decode(pingDg);
        check("datagram seq", dg.seq, 3);
        check("datagram msg count", dg.messages.size(), 1);
        Encapsulated e = dg.messages.get(0);
        check("encap reliability", e.reliability, Encapsulated.RELIABLE);
        check("encap fragmented", e.fragmented ? 1 : 0, 0);
        check("encap reliableIndex", e.reliableIndex, 1);
        check("encap payload len", e.payload.length, 9);
        check("encap payload[0]=ConnectedPing", e.payload[0] & 0xFF, RakNet.ID_CONNECTED_PING);

        // 3) Real ACK datagram for sequence 0.
        byte[] ack = HEX.parseHex("c0000101000000");
        check("ack isAck", Datagram.isAck(ack) ? 1 : 0, 1);
        check("ack seqs", Datagram.parseAckSeqs(ack).get(0), 0);

        // 4) Our own ack() builder must reproduce that byte-for-byte.
        checkBytes("ack() builder", Datagram.ack(0), ack);

        // 5) Encode round-trip of a reliable-ordered fragment.
        Encapsulated f = new Encapsulated();
        f.reliability = Encapsulated.RELIABLE_ORDERED;
        f.fragmented = true;
        f.reliableIndex = 7; f.orderIndex = 2; f.orderChannel = 0;
        f.splitCount = 22; f.splitId = 0; f.splitIndex = 5;
        f.payload = HEX.parseHex("fe0102030405");
        Buf w = new Buf(new byte[0]);
        f.encode(w);
        Encapsulated g = Encapsulated.decode(new Buf(w.out()));
        check("rt reliability", g.reliability, f.reliability);
        check("rt splitId", g.splitId, f.splitId);
        check("rt splitIndex", g.splitIndex, f.splitIndex);
        check("rt orderIndex", g.orderIndex, f.orderIndex);
        checkBytes("rt payload", g.payload, f.payload);

        if (failures == 0) {
            System.out.println("RakNetCodecSelfTest: PASS (codec matches real MC3DS bytes)");
        } else {
            System.out.println("RakNetCodecSelfTest: FAIL (" + failures + " checks)");
            System.exit(1);
        }
    }

    private static void check(String what, int got, int want) {
        if (got != want) { System.out.println("  FAIL " + what + ": got " + got + " want " + want); failures++; }
    }

    private static void checkBytes(String what, byte[] got, byte[] want) {
        if (!java.util.Arrays.equals(got, want)) {
            System.out.println("  FAIL " + what + ": got " + HEX.formatHex(got) + " want " + HEX.formatHex(want));
            failures++;
        }
    }
}
