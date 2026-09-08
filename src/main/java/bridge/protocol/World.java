package bridge.protocol;

import java.util.HashMap;
import java.util.Map;

/**
 * Server-side view of the world the bridge served, indexed from the spawn batch's
 * FullChunkData (0x3a) packets so the bridge can answer questions about blocks it sent.
 *
 * <p>Chunk payload layout (see FINDINGS.md), validated by round-trip:
 * <pre>
 *   3a | chunkX u8 | chunkZ u8 | varint payloadLen | payload
 *   payload: count u8 | count x [ ids 4096 | meta 2048 | skylight 2048 | blocklight 2048 ]
 *   ids: one byte per block, index (x*16 + z)*16 + localY, section s covers y = 16s..16s+15
 * </pre>
 *
 * <p>Writes are kept in memory so the world stays consistent with what the client has been
 * told: break a block and a later lookup at that position reports air.
 */
public final class World {

    /**
     * Section layout, validated against all six captured chunks (see FINDINGS.md):
     * <pre>
     *   flagA 1 | ids 4096 | meta 2048 | ? 2048 | ? 2048 | flagB 1 | light 4096 if flagB
     * </pre>
     * So sections are 10242 or 14338 bytes — VARIABLE. Any fixed stride puts section 0 in
     * the right place and drifts every section above it into meta/light data.
     */
    private static final int SEC_FIXED = 1 + 4096 + 2048 + 2048 + 2048 + 1;   // 10242
    private static final int LIGHT = 4096;
    private static final int IDS = 1;
    private static final int TAIL = 770;        // heightmap 512 + biome 256 + 2
    private static final int AIR = 0;

    private final Map<Integer, byte[]> chunks = new HashMap<>();

    /** Indexes every 0x3a packet in a spawn batch. */
    public static World fromSpawn(byte[] spawnBatch) {
        World w = new World();
        for (byte[] p : McpeBatch.inflate(spawnBatch)) {
            if (p.length > 3 && (p[0] & 0xFF) == 0x3A) {
                // chunk coords are ZIGZAG varints — the client works in signed world coords
                w.chunks.put(key(zigzag(p[1] & 0xFF), zigzag(p[2] & 0xFF)), p);
            }
        }
        return w;
    }

    /**
     * Indexes a chunk packet we are streaming to the client. Without this the model only ever knew
     * the chunks that went out with the spawn, so everywhere else {@link #blockAt} answered AIR:
     * break particles came out as air (invisible), placement refused to build against real blocks,
     * and the break log reported id=0 for blocks that plainly existed.
     */
    public void addChunk(byte[] chunkPacket) {
        if (chunkPacket.length > 3 && (chunkPacket[0] & 0xFF) == 0x3A) {
            chunks.put(key(zigzag(chunkPacket[1] & 0xFF), zigzag(chunkPacket[2] & 0xFF)), chunkPacket);
        }
    }

    public int chunkCount() {
        return chunks.size();
    }

    /** Whether a block position falls in a chunk we actually served the client. */
    public boolean isServed(int x, int z) {
        return chunks.containsKey(key(chunkOf(x), chunkOf(z)));
    }

    private static int key(int cx, int cz) {
        return ((cx & 0xFFFF) << 16) | (cz & 0xFFFF);
    }

    /** Decodes a zigzag-encoded unsigned value to its signed form. */
    public static int zigzag(int v) {
        return (v >>> 1) ^ -(v & 1);
    }

    /** Floor-division by 16, correct for negative coordinates (-2 >> 4 == -1). */
    private static int chunkOf(int v) {
        return v >> 4;
    }

    private static int index(int x, int z, int y) {
        return (((x & 15) * 16) + (z & 15)) * 16 + (y & 15);
    }

    /**
     * Offset of the ids array for the section containing {@code y}, or -1 if absent.
     * Sections must be walked sequentially: each one's length depends on its own flagB.
     */
    private int idsOffset(byte[] pkt, int y) {
        int j = 3, n = 0, shift = 0;
        while (j < pkt.length) {
            int b = pkt[j++] & 0xFF;
            n |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        int want = y >> 4;
        if (y < 0 || j >= pkt.length || want >= (pkt[j] & 0xFF)) return -1;
        int pos = j + 1;
        for (int s = 0; s <= want; s++) {
            if (s == want) return pos + IDS + 4096 <= pkt.length ? pos + IDS : -1;
            int flagB = pos + SEC_FIXED - 1;
            if (flagB >= pkt.length) return -1;
            pos += SEC_FIXED + ((pkt[flagB] & 0xFF) != 0 ? LIGHT : 0);
        }
        return -1;
    }

    /**
     * Block id at a world position, or {@link #AIR} if we never served that chunk.
     *
     */
    public int blockAt(int x, int y, int z) {
        byte[] pkt = chunks.get(key(chunkOf(x), chunkOf(z)));
        if (pkt == null) return AIR;
        int off = idsOffset(pkt, y);
        if (off < 0) return AIR;
        int b = off + index(x, z, y);
        return b >= pkt.length ? AIR : pkt[b] & 0xFF;
    }

    /** Block metadata nibble. */
    public int metaAt(int x, int y, int z) {
        byte[] pkt = chunks.get(key(chunkOf(x), chunkOf(z)));
        if (pkt == null) return 0;
        int off = idsOffset(pkt, y);
        if (off < 0) return 0;
        int b = off + 4096 + index(x, z, y) / 2;   // meta nibbles follow the ids
        if (b >= pkt.length) return 0;
        int n = pkt[b] & 0xFF;
        return (index(x, z, y) & 1) == 0 ? (n & 0x0F) : (n >>> 4);
    }

    /** Records a block change so later lookups match what the client was told. */
    /**
     * @return whether the write actually landed. It silently did nothing when the position fell
     *         outside the sections we encoded, which left the model claiming air where the player
     *         had just built — so the same cell could be "placed into" over and over.
     */
    public boolean setBlock(int x, int y, int z, int id) {
        byte[] pkt = chunks.get(key(chunkOf(x), chunkOf(z)));
        if (pkt == null) return false;
        int off = idsOffset(pkt, y);
        if (off < 0) return false;
        int b = off + index(x, z, y);
        if (b >= pkt.length) return false;
        pkt[b] = (byte) id;
        return true;
    }
}
