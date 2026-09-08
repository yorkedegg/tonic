package bridge.protocol;

import bridge.world.WorldModel;

import java.io.ByteArrayOutputStream;

/**
 * Encodes a Java (1.12.2) chunk column into an MC3DS FullChunkData (0x3a) packet.
 *
 * <p>ViaProxy down-translates the modern server to 1.12.2, which still stores blocks as
 * {@code id<<4|meta} — the same model MC3DS uses — so block states carry across directly and
 * no flattening table is needed.
 *
 * <p>Wire format (see FINDINGS.md; derived from the ROM deserializer at 0x0048F750 and
 * validated against all six captured chunks):
 * <pre>
 *   3a | zigzag chunkX | zigzag chunkZ | varint payloadLen | payload
 *   payload: count u8
 *            count x [ flagA 1 | ids 4096 | meta 2048 | pad 2048 | pad 2048
 *                      | flagB 1 | light 4096 (only when flagB) ]
 *            heightmap 512 (256 x u16 LE) | biome 256 | 2 zero bytes
 * </pre>
 *
 * <p>Two indexing details matter: MC3DS orders block ids with <b>y varying fastest</b>
 * ({@code (x*16 + z)*16 + y}) while Java 1.12.2 uses {@code (y<<8)|(z<<4)|x}, so the arrays are
 * transposed; and the light byte is {@code skylight<<4 | blocklight}, so open air is 0xF0 —
 * writing 0xFF lights every air block like a torch and the client spends a frame undoing it.
 */
public final class Mc3dsChunk {

    /** MC3DS worlds are 128 blocks tall: 8 sections, against Java's 16. */
    public static final int SECTIONS = 8;
    private static final int SECTION_HEIGHT = 16;

    private Mc3dsChunk() {}

    /** Builds the 0x3a packet for one column. */
    public static byte[] encode(WorldModel.Chunk c, int chunkX, int chunkZ) {
        return encode(c, chunkX, chunkZ, 0);
    }

    /**
     * @param yShift blocks to raise the Java terrain by. The 3DS spawn height comes from the
     *               captured StartGame, so unless the served terrain is aligned to it the player
     *               spawns in mid-air and never lands — which in-game reads as absurd speed,
     *               because Minecraft keeps horizontal momentum during a fall.
     */
    public static byte[] encode(WorldModel.Chunk c, int chunkX, int chunkZ, int yShift) {
        int count = sectionCount(c);
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        p.write(count);

        int maxY = count * SECTION_HEIGHT;

        // Lighting comes from the Java server when it sent any: it has already worked out, for
        // every block type in the game, how much light gets through. The fallback below only runs
        // for chunks with no server light (synthetic test chunks, or a dimension that sends none),
        // and it depends on blocksSky(), a hand-written list that will always be missing something
        // — kelp cast a shadow because it was not on it.
        boolean haveServerLight = false;
        for (int sec = 0; sec < count && !haveServerLight; sec++) {
            haveServerLight = c.skyLight[sec] != null || c.blockLight[sec] != null;
        }
        byte[] sky = new byte[16 * 16 * 256];
        int[] height = new int[256];
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        for (int lx = 0; !haveServerLight && lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int h = -1;
                boolean open = true;
                for (int y = maxY - 1; y >= 0; y--) {
                    int id = WorldModel.blockId(c.get(lx, y - yShift, lz));
                    if (open && blocksSky(id)) { open = false; h = y; }
                    if (open) {
                        sky[skyIdx(lx, y, lz)] = 15;
                        queue.add(skyIdx(lx, y, lz));
                    }
                }
                height[lx * 16 + lz] = h;
            }
        }
        while (!haveServerLight && !queue.isEmpty()) {
            int at = queue.poll();
            int lx = (at >> 12) & 15, lz = (at >> 8) & 15, y = at & 255;
            int level = sky[at];
            if (level <= 1) continue;
            for (int[] d : NEIGHBOURS) {
                int nx = lx + d[0], ny = y + d[1], nz = lz + d[2];
                if (nx < 0 || nx > 15 || nz < 0 || nz > 15 || ny < 0 || ny >= maxY) continue;
                if (blocksSky(WorldModel.blockId(c.get(nx, ny - yShift, nz)))) continue;
                int ni = skyIdx(nx, ny, nz);
                if (sky[ni] >= level - 1) continue;
                sky[ni] = (byte) (level - 1);
                queue.add(ni);
            }
        }

        if (haveServerLight) {
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    int h = -1;
                    for (int y = maxY - 1; y >= 0 && h < 0; y--) {
                        if (blocksSky(WorldModel.blockId(c.get(lx, y - yShift, lz)))) h = y;
                    }
                    height[lx * 16 + lz] = h;
                }
            }
        }

        for (int sec = 0; sec < count; sec++) {
            byte[] ids = new byte[4096];
            byte[] meta = new byte[2048];
            byte[] light = new byte[4096];
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int ly = 0; ly < 16; ly++) {
                        int y = sec * SECTION_HEIGHT + ly;
                        short state = c.get(lx, y - yShift, lz);
                        int i = (lx * 16 + lz) * 16 + ly;      // MC3DS: y fastest
                        int id = WorldModel.blockId(state);
                        ids[i] = (byte) (id > 255 ? 0 : id);
                        setNibble(meta, i, WorldModel.blockMeta(state));
                        // MC3DS packs one byte per block as skylight<<4 | blocklight.
                        int sl, bl;
                        if (haveServerLight) {
                            sl = c.sky(lx, y - yShift, lz);
                            bl = c.block(lx, y - yShift, lz);
                            if (sl < 0) sl = (y - yShift) > c.highestBlockY(lx, lz) ? 15 : 0;
                            if (bl < 0) bl = 0;
                        } else {
                            sl = sky[skyIdx(lx, y, lz)] & 0x0F;
                            bl = 0;
                        }
                        light[i] = (byte) ((sl << 4) | (bl & 0x0F));
                    }
                }
            }
            p.write(0);                        // flagA
            p.writeBytes(ids);
            p.writeBytes(meta);
            p.writeBytes(new byte[2048]);      // unidentified nibble array
            p.writeBytes(new byte[2048]);      // unidentified nibble array
            p.write(1);                        // flagB: lighting follows
            p.writeBytes(light);
        }

        for (int i = 0; i < 256; i++) {        // heightmap, u16 LE
            int h = height[i] + 1;
            p.write(h & 0xFF);
            p.write((h >>> 8) & 0xFF);
        }
        p.writeBytes(new byte[256]);           // biome
        p.write(0);                            // optional-block flag: absent
        p.write(0);                            // trailing count: none

        byte[] payload = p.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x3A);
        writeVarint(out, zigzag(chunkX));
        writeVarint(out, zigzag(chunkZ));
        writeVarint(out, payload.length);
        out.writeBytes(payload);
        return out.toByteArray();
    }

    /**
     * Whether a block stops skylight. Water and other transparent blocks must NOT, or every
     * column with water on top goes pitch black below the surface — which is exactly what
     * happened the first time a sea-level spawn was served.
     */
    private static boolean blocksSky(int id) {
        switch (id) {
            case 0:                       // air
            case 8: case 9:               // water
            case 18: case 161:            // leaves
            case 20: case 95:             // glass
            case 30:                      // cobweb
            case 31: case 37: case 38:    // grass, flowers
            case 39: case 40:             // mushrooms
            case 50: case 65: case 78:    // torch, ladder, snow layer
            case 106:                     // vines
                return false;
            default:
                return true;
        }
    }

    /** Sections up to the highest non-empty one, capped at the MC3DS world height. */
    /**
     * How many sections to encode: everything the Java chunk uses, plus headroom to build into.
     *
     * <p>Stopping at the terrain meant the world model had nothing above it, so a block placed in
     * the air was accepted, drawn by the client, and then forgotten — leaving the model insisting
     * the cell was still empty. Empty sections are almost free once the batch is deflated.
     */
    private static int sectionCount(WorldModel.Chunk c) {
        for (int sec = SECTIONS - 1; sec >= 0; sec--) {
            if (c.sections[sec] != null) return Math.min(sec + 1 + HEADROOM_SECTIONS, SECTIONS);
        }
        return 1;
    }

    /** Sections of empty space to encode above the terrain, so the player can build there. */
    private static final int HEADROOM_SECTIONS = 2;

    private static final int[][] NEIGHBOURS =
            {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};

    private static int skyIdx(int lx, int y, int lz) {
        return (lx << 12) | (lz << 8) | (y & 255);
    }

    private static void setNibble(byte[] a, int index, int value) {
        int b = index >> 1;
        if ((index & 1) == 0) a[b] = (byte) ((a[b] & 0xF0) | (value & 0x0F));
        else a[b] = (byte) ((a[b] & 0x0F) | ((value & 0x0F) << 4));
    }

    private static int zigzag(int v) {
        return (v << 1) ^ (v >> 31);
    }

    private static void writeVarint(ByteArrayOutputStream o, int v) {
        while (true) {
            int b = v & 0x7F;
            v >>>= 7;
            o.write(v != 0 ? (b | 0x80) : b);
            if (v == 0) return;
        }
    }
}
