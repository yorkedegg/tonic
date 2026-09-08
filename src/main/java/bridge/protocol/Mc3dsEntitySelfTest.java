package bridge.protocol;

/**
 * Rebuilds the capture's own AddPlayer and checks it against the original.
 *
 * <p>This is the test that was missing. AddPlayer was first written with three hand-made metadata
 * entries where the host sends 37; it compiled, it looked plausible, and it crashed the client
 * after the world loaded. A byte comparison against the capture would have caught it before it
 * ever reached the console.
 *
 * <p>The motion vector is the one field allowed to differ: the builder always sends zero, and the
 * captured player happened to be falling.
 */
public final class Mc3dsEntitySelfTest {

    /** Offset of the motion vector: id + uuid + len + "azahard" + uniqueId(6) + runtimeId + pos. */
    private static final int MOTION_AT = 1 + 16 + 1 + 7 + 6 + 1 + 12;
    private static final int MOTION_LEN = 12;

    public static void main(String[] args) throws Exception {
        byte[] batch;
        try (java.io.InputStream in = Mc3dsEntitySelfTest.class.getResourceAsStream("/spawn_batch.bin")) {
            batch = in.readAllBytes();
        }
        byte[] original = null;
        for (byte[] p : McpeBatch.inflate(batch)) {
            if ((p[0] & 0xFF) == 0x0C) original = p;
        }
        if (original == null) throw new AssertionError("no AddPlayer in the capture");

        byte[] uuid = java.util.Arrays.copyOfRange(original, 1, 17);
        byte[] built = Mc3dsEntity.addPlayer(uuid, "azahard", 42949672957L, 1,
                13.79126262664795f, 63.000003814697266f, 17.783185958862305f,
                154.7421875f, -15.549159049987793f);

        if (built.length != original.length) {
            System.out.println("FAIL length " + built.length + " != " + original.length);
            System.exit(1);
        }
        java.util.List<Integer> diff = new java.util.ArrayList<>();
        for (int i = 0; i < built.length; i++) {
            if (built[i] != original[i] && !(i >= MOTION_AT && i < MOTION_AT + MOTION_LEN)) {
                diff.add(i);
            }
        }
        if (!diff.isEmpty()) {
            System.out.println("FAIL differs outside the motion vector at " + diff);
            System.out.println("  want " + java.util.HexFormat.of().formatHex(original));
            System.out.println("  got  " + java.util.HexFormat.of().formatHex(built));
            System.exit(1);
        }

        // And the nametag must actually follow the name we pass, not stay as the capture's.
        byte[] renamed = Mc3dsEntity.addPlayer(uuid, "egoei", 7L, 2000, 1f, 2f, 3f, 0f, 0f);
        String asText = new String(renamed, java.nio.charset.StandardCharsets.ISO_8859_1);
        if (asText.contains("azahard") || !asText.contains("egoei")) {
            System.out.println("FAIL the nametag was not replaced");
            System.exit(1);
        }
        // PlayerList must reproduce the capture's single-entry packet exactly.
        byte[] wantList = null;
        for (byte[] p : McpeBatch.inflate(batch)) {
            if ((p[0] & 0xFF) == 0x3F && p.length == 47) wantList = p;
        }
        if (wantList == null) throw new AssertionError("no single-entry PlayerList in the capture");
        byte[] listUuid = java.util.Arrays.copyOfRange(wantList, 3, 19);
        byte[] gotList = Mc3dsEntity.playerList(listUuid, 42949672721L, "amongs",
                Mc3dsEntity.SKIN_STEVE);
        if (!java.util.Arrays.equals(wantList, gotList)) {
            System.out.println("FAIL PlayerList does not match the capture");
            System.out.println("  want " + java.util.HexFormat.of().formatHex(wantList));
            System.out.println("  got  " + java.util.HexFormat.of().formatHex(gotList));
            System.exit(1);
        }
        System.out.println("PASS AddPlayer and PlayerList both reproduce the capture");
    }
}
