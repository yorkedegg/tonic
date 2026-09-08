package bridge.transport;

import java.util.ArrayList;
import java.util.List;

/**
 * A RakNet online datagram: a header byte, a 24-bit little-endian sequence number, and one
 * or more {@link Encapsulated} messages. ACK/NAK datagrams are handled separately.
 */
public final class Datagram {

    public int seq;
    public final List<Encapsulated> messages = new ArrayList<>();

    public static boolean isValid(byte[] d) { return d.length > 0 && (d[0] & RakNet.FLAG_VALID) != 0; }
    public static boolean isAck(byte[] d) { return isValid(d) && (d[0] & RakNet.FLAG_ACK) != 0; }
    public static boolean isNak(byte[] d) { return isValid(d) && (d[0] & RakNet.FLAG_NAK) != 0; }

    /** Decodes a data datagram (caller has checked it is valid and not ACK/NAK). */
    public static Datagram decode(byte[] data) {
        Buf b = new Buf(data);
        b.u8();                 // header flags
        Datagram d = new Datagram();
        d.seq = b.u24le();
        while (b.hasRemaining()) d.messages.add(Encapsulated.decode(b));
        return d;
    }

    public byte[] encode() {
        Buf b = new Buf(new byte[0]);
        b.w8(RakNet.FLAG_VALID);
        b.w24le(seq);
        for (Encapsulated e : messages) e.encode(b);
        return b.out();
    }

    /** Parses the sequence numbers acknowledged by an ACK/NAK datagram. */
    public static List<Integer> parseAckSeqs(byte[] data) {
        Buf b = new Buf(data);
        b.u8();                 // header (ACK/NAK flags)
        int records = b.u16be();
        List<Integer> seqs = new ArrayList<>();
        for (int i = 0; i < records; i++) {
            int single = b.u8();
            if (single == 1) {
                seqs.add(b.u24le());
            } else {
                int lo = b.u24le(), hi = b.u24le();
                for (int s = lo; s <= hi; s++) seqs.add(s);
            }
        }
        return seqs;
    }

    /** Builds an ACK datagram for one sequence number (single-record form). */
    public static byte[] ack(int seq) {
        Buf b = new Buf(new byte[0]);
        b.w8(RakNet.FLAG_VALID | RakNet.FLAG_ACK);
        b.w16be(1);   // one record
        b.w8(1);      // 1 = single (0 = range)
        b.w24le(seq);
        return b.out();
    }
}
