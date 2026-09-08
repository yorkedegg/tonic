package bridge.protocol;

/**
 * Checks {@link Inventory} against the capture: re-encoding the host's own window-0 contents must
 * reproduce its bytes exactly. The layout was inferred from one packet, so this is the only thing
 * standing between a plausible-looking guess and a correct one.
 */
public final class InventorySelfTest {

    public static void main(String[] args) throws Exception {
        byte[] batch;
        try (java.io.InputStream in = InventorySelfTest.class.getResourceAsStream("/spawn_batch.bin")) {
            batch = in.readAllBytes();
        }
        byte[] original = null;
        for (byte[] p : McpeBatch.inflate(batch)) {
            if ((p[0] & 0xFF) == 0x34 && p.length == 138) original = p;
        }
        if (original == null) throw new AssertionError("no window-0 ContainerSetContent in the capture");

        // The host's hotbar, as decoded: nine stacks of 64 in the first nine slots.
        int[][] slots = new int[Inventory.PLAYER_SLOTS][];
        int[][] hotbar = {{1, 64, 0}, {4, 64, 0}, {3, 64, 0}, {5, 64, 0}, {5, 64, 1},
                          {50, 64, 0}, {108, 64, 0}, {139, 64, 0}, {6, 64, 0}};
        System.arraycopy(hotbar, 0, slots, 0, hotbar.length);

        byte[] built = Inventory.setContent(42949672721L, Inventory.WINDOW_PLAYER, slots);
        if (!java.util.Arrays.equals(original, built)) {
            System.out.println("FAIL ContainerSetContent does not match the capture");
            System.out.println("  want " + java.util.HexFormat.of().formatHex(original));
            System.out.println("  got  " + java.util.HexFormat.of().formatHex(built));
            System.exit(1);
        }
        System.out.println("PASS ContainerSetContent reproduces the captured packet byte-for-byte");
    }
}
