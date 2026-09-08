package bridge.world;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Canonical world state, in 1.12.2 terms: chunk columns whose blocks are stored as
 * {@code id<<4 | meta} (the model both the Java leg and MC3DS share). The Java leg writes
 * here; the MC3DS legs will read here (M3). Thread-safe map so the Java read thread can
 * publish while other threads read.
 */
public final class WorldModel {

    /** One 16x256x16 column. Sections are 16 blocks tall; absent sections are null (all air). */
    public static final class Chunk {
        public final int x, z;
        public final short[][] sections = new short[16][]; // [sectionY] -> 4096 states (id<<4|meta)
        /** The server's own lighting, 2048 nibbles per section; null where it sent none. */
        public final byte[][] skyLight = new byte[16][];
        public final byte[][] blockLight = new byte[16][];

        public Chunk(int x, int z) {
            this.x = x;
            this.z = z;
        }

        /** Block state at local (0..15, 0..255, 0..15); 0 = air for absent sections. */
        public short get(int lx, int y, int lz) {
            if (y < 0 || y > 255) return 0;
            short[] s = sections[y >> 4];
            if (s == null) return 0;
            return s[((y & 15) << 8) | (lz << 4) | lx];
        }

        /** Writes a block state, creating the section if that part of the column was empty. */
        public void set(int lx, int y, int lz, short state) {
            if (y < 0 || y > 255) return;
            short[] sec = sections[y >> 4];
            if (sec == null) {
                if (state == 0) return;
                sec = sections[y >> 4] = new short[4096];
            }
            sec[((y & 15) << 8) | (lz << 4) | lx] = state;
        }

        /** Sky light 0-15 at a local position, or -1 if the server sent none for that section. */
        public int sky(int lx, int y, int lz) { return nibble(skyLight, lx, y, lz); }

        /** Block light 0-15 at a local position, or -1 if the server sent none for that section. */
        public int block(int lx, int y, int lz) { return nibble(blockLight, lx, y, lz); }

        private static int nibble(byte[][] arrays, int lx, int y, int lz) {
            if (y < 0 || y > 255) return -1;
            byte[] a = arrays[y >> 4];
            if (a == null) return -1;
            int i = ((y & 15) << 8) | (lz << 4) | lx;   // Java packs y-major within a section
            int b = a[i >> 1] & 0xFF;
            return (i & 1) == 0 ? b & 0x0F : b >> 4;
        }

        public int highestBlockY(int lx, int lz) {
            for (int y = 255; y >= 0; y--) if (get(lx, y, lz) != 0) return y;
            return -1;
        }
    }

    private final Map<Long, Chunk> chunks = new ConcurrentHashMap<>();

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    public void putChunk(Chunk c) {
        chunks.put(key(c.x, c.z), c);
    }

    public Chunk getChunk(int x, int z) {
        return chunks.get(key(x, z));
    }

    public int chunkCount() {
        return chunks.size();
    }

    /** Block state (id<<4|meta) at world coords, or 0 (air) if that chunk isn't loaded. */
    public short getBlock(int wx, int wy, int wz) {
        Chunk c = getChunk(wx >> 4, wz >> 4);
        return c == null ? 0 : c.get(wx & 15, wy, wz & 15);
    }

    /** Writes a block state at world coords; a no-op if that chunk isn't loaded. */
    public void setBlock(int wx, int wy, int wz, short state) {
        Chunk c = getChunk(wx >> 4, wz >> 4);
        if (c != null) c.set(wx & 15, wy, wz & 15, state);
    }

    public static int blockId(short state) { return (state & 0xFFFF) >> 4; }
    public static int blockMeta(short state) { return state & 0xF; }
}
