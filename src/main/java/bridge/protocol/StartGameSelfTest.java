package bridge.protocol;

/**
 * Proves {@link StartGame}'s field split against the capture: parsing the captured StartGame and
 * re-encoding it unchanged must reproduce the original bytes exactly. If the split were wrong —
 * a varint read as two fields, a float boundary off by one — the re-encode would differ.
 */
public final class StartGameSelfTest {

    public static void main(String[] args) throws Exception {
        byte[] batch;
        try (java.io.InputStream in = StartGameSelfTest.class.getResourceAsStream("/spawn_startonly.bin")) {
            batch = in.readAllBytes();
        }
        byte[] original = null;
        for (byte[] p : McpeBatch.inflate(batch)) {
            if ((p[0] & 0xFF) == Mcpe.START_GAME) original = p;
        }
        if (original == null) throw new AssertionError("no StartGame in the fixture");

        StartGame s = StartGame.parse(original);
        System.out.printf("parsed: runtimeId=%d gamemode=%d pos=(%.1f,%.1f,%.1f) yaw=%.1f pitch=%.1f%n",
                s.entityRuntimeId, s.gamemode, s.x, s.y, s.z, s.yaw, s.pitch);
        System.out.printf("        seed=%d dim=%d gen=%d worldGamemode=%d difficulty=%d spawn=(%d,%d,%d)%n",
                s.seed, s.dimension, s.generator, s.worldGamemode, s.difficulty,
                s.spawnX, s.spawnY, s.spawnZ);
        System.out.println("        tail " + s.tail.length + " B (gamerules, level id, world name)");

        byte[] again = s.encode();
        if (!java.util.Arrays.equals(original, again)) {
            System.out.println("FAIL round-trip differs");
            System.out.println("  want " + java.util.HexFormat.of().formatHex(original));
            System.out.println("  got  " + java.util.HexFormat.of().formatHex(again));
            System.exit(1);
        }

        // And the fields we actually change must survive a second round-trip. placePlayer takes the
        // FEET height and writes the eye, because that is what the client reads — getting this
        // backwards spawned the player inside the ground.
        StartGame moved = StartGame.parse(original).placePlayer(120.5f, 72.0f, -33.5f, 7);
        StartGame back = StartGame.parse(moved.encode());
        if (back.x != 120.5f || back.z != -33.5f
                || back.entityRuntimeId != 7 || back.gamemode != StartGame.SURVIVAL) {
            System.out.println("FAIL edited fields did not survive a round-trip");
            System.exit(1);
        }
        if (back.y != 72.0f + StartGame.EYE_HEIGHT) {
            System.out.println("FAIL feet 72.0 should be written as eye "
                    + (72.0f + StartGame.EYE_HEIGHT) + ", got " + back.y);
            System.exit(1);
        }
        if (back.spawnY != 72) {          // the world spawn point stays a block coordinate
            System.out.println("FAIL spawnY should stay the feet block, got " + back.spawnY);
            System.exit(1);
        }

        System.out.println("PASS round-trip is byte-identical; edits survive re-parse");
    }
}
