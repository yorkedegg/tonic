package bridge.transport;

/**
 * One encapsulated message inside a RakNet datagram. Field presence depends on the
 * reliability type (validated byte-for-byte against M0 captures):
 * <pre>
 *   u8  flags        // (reliability << 5) | (fragmented ? 0x10 : 0)
 *   u16 lengthBits   // big-endian, payload length in BITS
 *   u24 reliableIndex   if reliability in {RELIABLE, RELIABLE_ORDERED, RELIABLE_SEQUENCED}
 *   u24 sequenceIndex   if reliability in {UNRELIABLE_SEQUENCED, RELIABLE_SEQUENCED}
 *   u24 orderIndex + u8 orderChannel  if reliability in {UNRELIABLE_SEQUENCED, RELIABLE_ORDERED, RELIABLE_SEQUENCED}
 *   u32 splitCount + u16 splitId + u32 splitIndex   if fragmented   (all big-endian)
 *   payload[length]
 * </pre>
 */
public final class Encapsulated {

    public static final int UNRELIABLE = 0;
    public static final int UNRELIABLE_SEQUENCED = 1;
    public static final int RELIABLE = 2;
    public static final int RELIABLE_ORDERED = 3;
    public static final int RELIABLE_SEQUENCED = 4;

    public int reliability;
    public boolean fragmented;
    public int reliableIndex = -1;
    public int sequenceIndex = -1;
    public int orderIndex = -1;
    public int orderChannel = 0;
    public int splitCount, splitId, splitIndex;
    public byte[] payload;

    static boolean reliable(int r) { return r == RELIABLE || r == RELIABLE_ORDERED || r == RELIABLE_SEQUENCED; }
    static boolean sequenced(int r) { return r == UNRELIABLE_SEQUENCED || r == RELIABLE_SEQUENCED; }
    static boolean ordered(int r) { return r == UNRELIABLE_SEQUENCED || r == RELIABLE_ORDERED || r == RELIABLE_SEQUENCED; }

    public static Encapsulated decode(Buf b) {
        Encapsulated e = new Encapsulated();
        int flags = b.u8();
        e.reliability = (flags & 0xE0) >>> 5;
        e.fragmented = (flags & 0x10) != 0;
        int lengthBytes = (b.u16be() + 7) >> 3;
        if (reliable(e.reliability)) e.reliableIndex = b.u24le();
        if (sequenced(e.reliability)) e.sequenceIndex = b.u24le();
        if (ordered(e.reliability)) { e.orderIndex = b.u24le(); e.orderChannel = b.u8(); }
        if (e.fragmented) { e.splitCount = (int) b.u32be(); e.splitId = b.u16be(); e.splitIndex = (int) b.u32be(); }
        e.payload = b.bytes(lengthBytes);
        return e;
    }

    public void encode(Buf b) {
        b.w8((reliability << 5) | (fragmented ? 0x10 : 0));
        b.w16be(payload.length * 8);
        if (reliable(reliability)) b.w24le(reliableIndex);
        if (sequenced(reliability)) b.w24le(sequenceIndex);
        if (ordered(reliability)) { b.w24le(orderIndex); b.w8(orderChannel); }
        if (fragmented) { b.w32be(splitCount); b.w16be(splitId); b.w32be(splitIndex); }
        b.wbytes(payload);
    }

    /** Header size in bytes (everything before the payload), for MTU fragmentation math. */
    public int headerSize() {
        int n = 3; // flags + u16 length
        if (reliable(reliability)) n += 3;
        if (sequenced(reliability)) n += 3;
        if (ordered(reliability)) n += 4;
        if (fragmented) n += 10;
        return n;
    }

    public int size() { return headerSize() + payload.length; }
}
