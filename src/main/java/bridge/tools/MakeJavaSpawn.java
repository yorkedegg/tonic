package bridge.tools;

import bridge.javaclient.JavaClient;
import bridge.protocol.Mc3dsChunk;
import bridge.protocol.McpeBatch;
import bridge.world.WorldModel;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Pulls real chunks from a Java server (via ViaProxy) and writes an MC3DS spawn batch, so the
 * 3DS can be shown a world that came from Minecraft rather than from a capture.
 *
 * <pre>
 *   java bridge.tools.MakeJavaSpawn [host] [port] [seconds]
 * </pre>
 *
 * The non-chunk half of the spawn (StartGame, gamerules, inventory, ...) is reused verbatim from
 * the captured spawn — authoring those is a separate job — while every 0x3a chunk is generated
 * from the live server. AddPlayer/AddEntity are dropped so nothing blocks the camera.
 *
 * Java chunks are laid out onto MC3DS chunk coords -2..1 / -2..2, keeping their relative
 * positions, so the 3DS spawn point lands inside the served area.
 */
public final class MakeJavaSpawn {

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 25568;
        int seconds = args.length > 2 ? Integer.parseInt(args[2]) : 12;

        JavaClient jc = new JavaClient(host, port, "bridgebot");
        Thread t = new Thread(() -> {
            try { jc.connect(); } catch (Exception e) { System.out.println("java client ended: " + e); }
        }, "javaclient");
        t.setDaemon(true);
        t.start();

        System.out.println("collecting chunks for " + seconds + "s ...");
        for (int i = 0; i < seconds; i++) {
            Thread.sleep(1000);
            System.out.println("  " + jc.world.chunkCount() + " chunks");
        }

        // pick a contiguous 4x5 block of loaded chunks
        int[] origin = findOrigin(jc.world);
        if (origin == null) {
            System.out.println("no contiguous 4x5 area loaded — is the server generating terrain?");
            return;
        }
        System.out.println("using Java chunks from (" + origin[0] + "," + origin[1] + ")");

        List<byte[]> packets = new ArrayList<>();
        for (byte[] p : McpeBatch.inflate(loadResource("/spawn_realjoin.bin"))) {
            int id = p[0] & 0xFF;
            if (id != 0x3A && id != 0x0C && id != 0x0D) packets.add(p);
        }
        int made = 0;
        for (int dx = 0; dx < 4; dx++) {
            for (int dz = 0; dz < 5; dz++) {
                WorldModel.Chunk c = jc.world.getChunk(origin[0] + dx, origin[1] + dz);
                if (c == null) continue;
                packets.add(Mc3dsChunk.encode(c, -2 + dx, -2 + dz));
                made++;
            }
        }
        byte[] batch = McpeBatch.build(packets.toArray(new byte[0][]));
        for (String dir : new String[] {"src/main/resources", "build/resources/main"}) {
            if (Files.isDirectory(Path.of(dir))) {
                try (FileOutputStream f = new FileOutputStream(dir + "/sp_java.bin")) { f.write(batch); }
            }
        }
        System.out.println("wrote sp_java.bin: " + batch.length + " B, " + made + " live chunks");
    }

    /**
     * The fully-loaded 4x5 area with the most varied surface. Picking the first area found tends
     * to land in ocean, which looks identical everywhere and tells you nothing about whether the
     * translation is right; scoring by distinct surface blocks plus height spread finds terrain
     * you can actually judge.
     */
    private static int[] findOrigin(WorldModel w) {
        int[] best = null;
        int bestScore = -1;
        for (int x = -24; x <= 24; x++) {
            for (int z = -24; z <= 24; z++) {
                boolean all = true;
                for (int dx = 0; dx < 4 && all; dx++)
                    for (int dz = 0; dz < 5 && all; dz++)
                        if (w.getChunk(x + dx, z + dz) == null) all = false;
                if (!all) continue;
                int score = score(w, x, z);
                if (score > bestScore) { bestScore = score; best = new int[] {x, z}; }
            }
        }
        if (best != null) System.out.println("terrain score " + bestScore);
        return best;
    }

    /** Distinct surface block ids, plus the spread between the lowest and highest surface. */
    private static int score(WorldModel w, int ox, int oz) {
        java.util.Set<Integer> kinds = new java.util.HashSet<>();
        int lo = 999, hi = -1, water = 0;
        for (int dx = 0; dx < 4; dx++) {
            for (int dz = 0; dz < 5; dz++) {
                WorldModel.Chunk c = w.getChunk(ox + dx, oz + dz);
                for (int lx = 0; lx < 16; lx += 4) {
                    for (int lz = 0; lz < 16; lz += 4) {
                        int y = c.highestBlockY(lx, lz);
                        if (y < 0) continue;
                        int id = WorldModel.blockId(c.get(lx, y, lz));
                        // heavily penalise ocean: it renders identically everywhere and tells
                        // you nothing about whether the translation is faithful
                        if (id == 8 || id == 9) { water++; continue; }
                        kinds.add(id);
                        lo = Math.min(lo, y);
                        hi = Math.max(hi, y);
                    }
                }
            }
        }
        if (hi < 0) return -1;
        return kinds.size() * 10 + (hi - lo) - water * 3;
    }

    private static byte[] loadResource(String path) throws Exception {
        try (InputStream in = MakeJavaSpawn.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing " + path);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            in.transferTo(o);
            return o.toByteArray();
        }
    }
}
