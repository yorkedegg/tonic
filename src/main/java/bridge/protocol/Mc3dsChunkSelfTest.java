package bridge.protocol;

import bridge.world.WorldModel;

/**
 * Round-trips a synthetic Java chunk through {@link Mc3dsChunk} and back out via {@link World},
 * which is the reader already validated against the six captured chunks. Catches transposition
 * and offset mistakes without needing the console.
 */
public final class Mc3dsChunkSelfTest {

    private static int checks, failures;

    private static void check(String what, int got, int want) {
        checks++;
        if (got != want) {
            failures++;
            System.out.printf("  FAIL %-40s got %d want %d%n", what, got, want);
        }
    }

    public static void main(String[] args) {
        // A recognisable column: bedrock floor, stone, a dirt/grass surface, one marker block.
        WorldModel.Chunk c = new WorldModel.Chunk(0, 0);
        for (int sec = 0; sec < 5; sec++) c.sections[sec] = new short[4096];
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                for (int y = 0; y < 70; y++) {
                    int id = y == 0 ? 7 : y < 60 ? 1 : y < 63 ? 3 : y == 63 ? 2 : 0;
                    c.sections[y >> 4][((y & 15) << 8) | (lz << 4) | lx] = (short) (id << 4);
                }
            }
        }
        c.sections[4][((5 & 15) << 8) | (9 << 4) | 3] = (short) (57 << 4);   // marker at (3,69,9)

        byte[] pkt = Mc3dsChunk.encode(c, 0, 0);
        System.out.println("encoded 0x3a: " + pkt.length + " B");

        World w = World.fromSpawn(McpeBatch.build(pkt));
        check("chunks indexed", w.chunkCount(), 1);

        // every block must survive the transposition
        int mismatches = 0;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                for (int y = 0; y < 70; y++) {
                    int want = WorldModel.blockId(c.get(lx, y, lz));
                    if (w.blockAt(lx, y, lz) != want) mismatches++;
                }
            }
        }
        check("block mismatches across 17920 positions", mismatches, 0);
        check("bedrock at y=0", w.blockAt(5, 0, 5), 7);
        check("stone at y=30", w.blockAt(5, 30, 5), 1);
        check("dirt at y=62", w.blockAt(5, 62, 5), 3);
        check("grass at y=63", w.blockAt(5, 63, 5), 2);
        check("air at y=64", w.blockAt(5, 64, 5), 0);
        check("marker block at (3,69,9)", w.blockAt(3, 69, 9), 57);
        check("marker did not smear to (9,69,3)", w.blockAt(9, 69, 3), 0);

        System.out.printf("%n%d checks, %d failures -> %s%n",
                checks, failures, failures == 0 ? "PASS" : "FAIL");
        if (failures != 0) System.exit(1);
    }
}
