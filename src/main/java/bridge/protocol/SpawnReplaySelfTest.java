package bridge.protocol;

import java.io.InputStream;
import java.util.List;

/**
 * Validates the batch codec and the captured spawn batch. Run with
 * {@code gradle spawnReplaySelfTest}.
 */
public final class SpawnReplaySelfTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        // 1) Batch round-trip: build then inflate reproduces the packets.
        byte[] ps = Mcpe.playStatus(Mcpe.LOGIN_SUCCESS);
        checkBytes("playStatus(LOGIN_SUCCESS)", ps, new byte[]{0x02, 0, 0, 0, 0});
        byte[] batch = McpeBatch.build(ps, Mcpe.RESOURCE_PACKS_INFO_PKT);
        check("batch starts with 0xFE", batch[0] & 0xFF, 0xFE);
        List<byte[]> rt = McpeBatch.inflate(batch);
        check("round-trip packet count", rt.size(), 2);
        check("rt[0] id", rt.get(0)[0] & 0xFF, Mcpe.PLAY_STATUS);
        check("rt[1] id", rt.get(1)[0] & 0xFF, Mcpe.RESOURCE_PACKS_INFO);
        checkBytes("rt[0] bytes", rt.get(0), ps);

        // 2) A non-batch payload (e.g. the 0x40 hello) comes back as one raw packet.
        List<byte[]> raw = McpeBatch.inflate(new byte[]{0x40});
        check("raw hello count", raw.size(), 1);
        check("raw hello id", raw.get(0)[0] & 0xFF, Mcpe.CLIENT_HELLO);

        // 3) The real captured spawn batch inflates to its 47 packets incl. StartGame + chunk.
        byte[] spawn;
        try (InputStream in = SpawnReplaySelfTest.class.getResourceAsStream("/spawn_batch.bin")) {
            if (in == null) throw new IllegalStateException("spawn_batch.bin not on classpath");
            spawn = in.readAllBytes();
        }
        check("spawn batch is 0xFE", spawn[0] & 0xFF, 0xFE);
        List<byte[]> packets = McpeBatch.inflate(spawn);
        check("spawn inner packet count", packets.size(), 47);
        boolean hasStartGame = packets.stream().anyMatch(p -> (p[0] & 0xFF) == Mcpe.START_GAME);
        boolean hasChunk = packets.stream().anyMatch(p -> (p[0] & 0xFF) == Mcpe.FULL_CHUNK_DATA);
        check("spawn has StartGame (0x0b)", hasStartGame ? 1 : 0, 1);
        check("spawn has FullChunkData (0x3a)", hasChunk ? 1 : 0, 1);

        if (failures == 0) {
            System.out.println("SpawnReplaySelfTest: PASS (batch codec + captured spawn batch OK)");
        } else {
            System.out.println("SpawnReplaySelfTest: FAIL (" + failures + " checks)");
            System.exit(1);
        }
    }

    private static void check(String what, int got, int want) {
        if (got != want) { System.out.println("  FAIL " + what + ": got " + got + " want " + want); failures++; }
    }

    private static void checkBytes(String what, byte[] got, byte[] want) {
        if (!java.util.Arrays.equals(got, want)) {
            System.out.println("  FAIL " + what + ": got " + java.util.HexFormat.of().formatHex(got)); failures++;
        }
    }
}
