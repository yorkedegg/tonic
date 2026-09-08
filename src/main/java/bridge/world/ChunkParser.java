package bridge.world;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Parses a 1.12.2 (protocol 340) chunk-data blob into a {@link WorldModel.Chunk}. Each present
 * section is: bitsPerBlock (u8), palette (varint length + varint entries; length 0 = direct
 * global palette), a long[] of packed block indices (values may span longs, 1.12.2-style),
 * then 2048 B block light and (overworld) 2048 B sky light. Values/palette entries are
 * {@code id<<4|meta}, which is exactly WorldModel's storage.
 */
public final class ChunkParser {

    private ChunkParser() {}

    public static WorldModel.Chunk parse(int cx, int cz, byte[] data, int primaryBitMask,
                                         boolean groundUp, int dimension) throws IOException {
        WorldModel.Chunk chunk = new WorldModel.Chunk(cx, cz);
        InputStream in = new ByteArrayInputStream(data);

        for (int sy = 0; sy < 16; sy++) {
            if ((primaryBitMask & (1 << sy)) == 0) continue;

            int bitsPerBlock = in.read();
            if (bitsPerBlock <= 0) bitsPerBlock = 13;

            int paletteLength = readVarInt(in);
            int[] palette = new int[paletteLength];
            for (int i = 0; i < paletteLength; i++) palette[i] = readVarInt(in);

            int longCount = readVarInt(in);
            long[] blockData = new long[longCount];
            for (int i = 0; i < longCount; i++) blockData[i] = readLong(in);

            short[] section = new short[4096];
            for (int idx = 0; idx < 4096; idx++) {
                int v = getIndex(blockData, idx, bitsPerBlock);
                int state = (paletteLength > 0) ? palette[v] : v; // indirect vs direct global palette
                section[idx] = (short) state;
            }
            chunk.sections[sy] = section;

            // Keep the light rather than skipping it. The server has already computed lighting
            // for every block type; recomputing it here meant maintaining a list of which blocks
            // are see-through, and anything missing from that list (kelp, for one) cast a shadow
            // it should not have.
            chunk.blockLight[sy] = readBytes(in, 2048);
            if (dimension == 0) chunk.skyLight[sy] = readBytes(in, 2048);
        }
        // trailing biomes (256 B) if groundUp — not needed for block state
        return chunk;
    }

    /** Extracts the packed value for block index i; 1.12.2 lets a value straddle two longs. */
    private static byte[] readBytes(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) break;
            off += r;
        }
        return b;
    }

    private static int getIndex(long[] data, int i, int bitsPerBlock) {
        long bitIndex = (long) i * bitsPerBlock;
        int startLong = (int) (bitIndex >>> 6);
        int startBit = (int) (bitIndex & 63);
        int endLong = (int) (((long) (i + 1) * bitsPerBlock - 1) >>> 6);
        long value;
        if (startLong == endLong) {
            value = data[startLong] >>> startBit;
        } else {
            value = (data[startLong] >>> startBit) | (data[endLong] << (64 - startBit));
        }
        return (int) (value & ((1L << bitsPerBlock) - 1));
    }

    private static int readVarInt(InputStream s) throws IOException {
        int r = 0, pos = 0, b;
        do {
            b = s.read();
            r |= (b & 0x7F) << pos;
            pos += 7;
        } while ((b & 0x80) != 0);
        return r;
    }

    private static long readLong(InputStream s) throws IOException {
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (s.read() & 0xFF);
        return v;
    }

    private static void skip(InputStream s, int n) throws IOException {
        long left = n;
        while (left > 0) {
            long sk = s.skip(left);
            if (sk <= 0) { if (s.read() < 0) break; left--; } else left -= sk;
        }
    }
}
