package bridge.protocol;

import bridge.javaclient.JavaClient;
import bridge.world.WorldModel;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Holds one connection to the Java server (through ViaProxy) for the lifetime of the bridge, and
 * builds MC3DS spawn batches from whatever it has loaded.
 *
 * <p>MC3DS chunk (0,0) is mapped to the Java chunk the bot is standing in, so the 3DS spawn point
 * lands on real terrain and 3DS coordinates translate to Java coordinates by a fixed chunk offset.
 */
public final class JavaLink {

    private static final Logger log = Logger.getLogger("bridge.javalink");
    private static JavaClient client;

    /** Packet ids to omit from the spawn, e.g. MC3DS_DROP=0x37,0x1e — for bisecting. */
    private static final java.util.Set<Integer> DROP = parseDrop();

    private static java.util.Set<Integer> parseDrop() {
        java.util.Set<Integer> out = new java.util.HashSet<>();
        String v = System.getenv("MC3DS_DROP");
        if (v == null || v.isBlank()) return out;
        for (String part : v.split(",")) {
            String t = part.trim().toLowerCase();
            if (t.isEmpty()) continue;
            out.add(Integer.parseInt(t.startsWith("0x") ? t.substring(2) : t, 16));
        }
        log.info("dropping spawn packets: " + out.stream().map(i -> String.format("0x%02x", i)).toList());
        return out;
    }

    private JavaLink() {}

    private static final int WATER = 9, FLOWING_WATER = 8;

    /**
     * Picks a dry standing spot: the bot's own column when its surface is solid, otherwise the
     * nearest column in the served square that isn't under water. The 3DS spawn tracks wherever
     * the bot is, and the bot follows the last player — so ending a session in the sea used to
     * mean every later session began underwater, drowning in survival before anyone could move.
     *
     * @return {x, y, z}, with y one above the surface so the player's feet rest on it.
     */
    private static float[] spawnSpot(JavaClient jc, int originX, int originZ, float px, float pz,
                                     float fallbackY) {
        for (int r = 0; r <= RADIUS * 16; r += 4) {          // rings outward, coarse but cheap
            for (int dx = -r; dx <= r; dx += 4) {
                for (int dz = -r; dz <= r; dz += 4) {
                    if (r > 0 && Math.abs(dx) != r && Math.abs(dz) != r) continue;  // ring only
                    float x = px + dx, z = pz + dz;
                    WorldModel.Chunk c = jc.world.getChunk(originX + (int) Math.floor(x / 16.0),
                                                           originZ + (int) Math.floor(z / 16.0));
                    if (c == null) continue;
                    int lx = ((int) Math.floor(x)) & 15, lz = ((int) Math.floor(z)) & 15;
                    int top = c.highestBlockY(lx, lz);
                    if (top < 0) continue;
                    int id = WorldModel.blockId(c.get(lx, top, lz));
                    if (id == WATER || id == FLOWING_WATER) continue;
                    if (r > 0) log.info("bot's column is under water; spawning " + r + " blocks away");
                    return new float[] {x + 0.5f, top + 1, z + 0.5f};
                }
            }
        }
        log.warning("no dry column in the served square; spawning on the bot");
        return new float[] {px, fallbackY, pz};
    }

    /**
     * Prints the block column the player spawns in, from the same data the chunk encoder sends.
     * The client and the world model have disagreed about terrain height before, and guessing at
     * an offset without seeing both sides is how that disagreement went unresolved for so long.
     */
    private static void logSpawnColumn(JavaClient jc, int originX, int originZ,
                                       float px, float py, float pz) {
        WorldModel.Chunk c = jc.world.getChunk(originX + (int) Math.floor(px / 16.0),
                                               originZ + (int) Math.floor(pz / 16.0));
        if (c == null) { log.warning("spawn column: chunk not loaded"); return; }
        int lx = ((int) Math.floor(px)) & 15, lz = ((int) Math.floor(pz)) & 15;
        StringBuilder sb = new StringBuilder("spawn column (local " + lx + "," + lz + "):");
        int base = (int) Math.floor(py);
        for (int y = base + 4; y >= base - 4; y--) {
            int id = WorldModel.blockId(c.get(lx, y, lz));
            sb.append(String.format("%n    y=%-3d id=%-4d%s", y, id, y == base ? "   <- StartGame y" : ""));
        }
        sb.append("\n    highestBlockY = ").append(c.highestBlockY(lx, lz));
        log.info(sb.toString());
    }

    /** How many of the (2*RADIUS+1)^2 chunks around a chunk origin the bot has actually received. */
    private static int squareCount(JavaClient jc, int ox, int oz) {
        int n = 0;
        for (int cx = -RADIUS; cx <= RADIUS; cx++) {
            for (int cz = -RADIUS; cz <= RADIUS; cz++) {
                if (jc.world.getChunk(ox + cx, oz + cz) != null) n++;
            }
        }
        return n;
    }

    /**
     * Starts connecting in the background so nothing that matters has to wait for it.
     *
     * <p>{@link #get()} used to be called from the Mc3dsSession constructor, on the same thread
     * that answers the 3DS's network scans — and the shim allows one second for a scan reply
     * before it reports no servers found. Connecting the bot takes several seconds, and the shim
     * opens its tunnel when the player opens the server list, so the first scans after opening
     * Join were guaranteed to go unanswered. That looked like an intermittent "no servers found"
     * and sent a long hunt after the beacon's contents, which were never at fault.
     */
    public static void warmUp() {
        Thread t = new Thread(() -> get(), "java-link-warmup");
        t.setDaemon(true);
        t.start();
    }

    private static volatile boolean connecting;

    /**
     * The live Java client, or null if it is not ready yet. Never blocks behind another thread's
     * attempt: a caller that arrives mid-connect gets null and serves what it can rather than
     * stalling something time-critical.
     */
    public static JavaClient get() {
        if (client != null) return client;
        synchronized (JavaLink.class) {
            if (client != null) return client;
            if (connecting) return null;
            connecting = true;
        }
        try {
            return connect();
        } finally {
            connecting = false;
        }
    }

    private static synchronized JavaClient connect() {
        if (client != null) return client;
        // Default target: your.server.example. Its _minecraft._tcp SRV record points at port 11126
        // (nothing on 25565), and our raw-socket client does no SRV lookup, so the port is explicit.
        // Override with MC3DS_JAVA_HOST / MC3DS_JAVA_PORT (e.g. 127.0.0.1:25568 to go via ViaProxy).
        String host = System.getenv().getOrDefault("MC3DS_JAVA_HOST", "your.server.example");
        int port = Integer.parseInt(System.getenv().getOrDefault("MC3DS_JAVA_PORT", "11126"));
        JavaClient jc = new JavaClient(host, port, "bridgebot");
        Thread t = new Thread(() -> {
            try { jc.connect(); } catch (Exception e) { log.warning("java link ended: " + e); }
        }, "java-link");
        t.setDaemon(true);
        t.start();
        for (int i = 0; i < 100 && !jc.spawned; i++) {
            try { Thread.sleep(200); } catch (InterruptedException ignore) { return null; }
        }
        if (!jc.spawned) { log.warning("java link did not spawn — is ViaProxy up?"); return null; }
        // Wait for the square we are about to serve, not just for some number of chunks: a chunk
        // whose neighbours are missing never gets a mesh built, so a partly-filled spawn shows up
        // as invisible terrain. Counting chunks globally let partial squares through.
        int ox = (int) Math.floor(jc.posX / 16.0), oz = (int) Math.floor(jc.posZ / 16.0);
        int want = (2 * RADIUS + 1) * (2 * RADIUS + 1);
        for (int i = 0; i < 100 && squareCount(jc, ox, oz) < want; i++) {
            try { Thread.sleep(200); } catch (InterruptedException ignore) { return null; }
        }
        int have = squareCount(jc, ox, oz);
        if (have < want) log.warning("only " + have + "/" + want + " spawn chunks arrived from Java");
        log.info("java link ready: " + jc.world.chunkCount() + " chunks, at ("
                + (int) jc.posX + "," + (int) jc.posY + "," + (int) jc.posZ + ")");
        jc.sendChat("/gamemode " + (CREATIVE ? "creative" : "survival"));
        client = jc;
        return client;
    }

    /**
     * The Java world used to be shifted vertically so its surface met the height baked into the
     * captured StartGame. Now that StartGame is authored ({@link StartGame}) the player is simply
     * placed where the terrain actually is, so the shift is always zero and Java and 3DS block
     * coordinates differ only by the chunk origin. Kept as a named constant because the chunk
     * encoder and the movement relay both have to agree about it.
     */
    public static final int Y_SHIFT = 0;

    /** MCPE's player eye height. Defined once in {@link StartGame}; both spawn placement and the
     *  movement relay need it, because StartGame and MovePlayer both carry the eye, not the feet. */
    public static final double EYE_HEIGHT = StartGame.EYE_HEIGHT;

    /**
     * The entity id we give the player in the authored StartGame. Anything works as long as the
     * bridge addresses the same id in the packets it sends afterwards (attributes, in particular),
     * which is precisely what replaying the capture got wrong.
     */
    public static final int RUNTIME_ID = 11;

    /**
     * MC3DS_GAMEMODE=creative puts BOTH sides in creative. The 3DS half alone is not enough: the
     * client would break instantly and place freely, but the server would reject the fast digs and
     * refuse every placement, because the bot is still a survival player with empty hands.
     */
    public static final boolean CREATIVE = "creative".equalsIgnoreCase(System.getenv("MC3DS_GAMEMODE"));

    /** Where the last built spawn put the player, so the encoded chunk can be checked there. */
    public static volatile float lastSpawnX, lastSpawnY, lastSpawnZ;

    /** The entityUniqueId the authored StartGame carried; ContainerSetContent is addressed to it. */
    public static volatile long spawnUniqueId;

    /** Half-width of the served square; 2 => 5x5 chunks around the spawn. */
    private static final int RADIUS =
            Integer.parseInt(System.getenv().getOrDefault("MC3DS_SPAWN_RADIUS", "2"));


    public static byte[] buildSpawn(byte[] fixture, JavaClient jc, int originX, int originZ) {
        // Where the player goes on the 3DS: the bot's column, with the bot's chunk becoming 3DS
        // chunk (0,0). The height comes from the terrain we are about to send, NOT from the bot's
        // own y: the movement relay walks the bot around, so its y is wherever the last 3DS player
        // left it. Deriving the height from the blocks makes the spawn self-consistent with the
        // chunks in the same batch.
        float[] spot = spawnSpot(jc, originX, originZ, (float) (jc.posX - originX * 16.0),
                (float) (jc.posZ - originZ * 16.0), (float) jc.posY);
        float px = spot[0], py = spot[1], pz = spot[2];
        lastSpawnX = px; lastSpawnY = py; lastSpawnZ = pz;

        List<byte[]> out = new ArrayList<>();
        for (byte[] p : McpeBatch.inflate(fixture)) {
            int id = p[0] & 0xFF;
            if (id == 0x3A || id == 0x0C || id == 0x0D) continue;
            // AdventureSettings (0x37) is `flags varint | byte` (serializer 0x00709C30) and the
            // capture carries flags=96. The bit meanings are NOT established, so rather than
            // guess: MC3DS_DROP lists packet ids to omit, for bisecting which one is responsible
            // for the player's inherited movement state.
            if (DROP.contains(id)) continue;
            if (id == 0x0B) {
                // Author StartGame instead of replaying it: the captured one describes another
                // session's player, which is where the inherited creative flight, the wrong spawn
                // height and the wrong entity id all came from.
                StartGame sg = StartGame.parse(p).placePlayer(px, py, pz, RUNTIME_ID);
                spawnUniqueId = sg.entityUniqueId;
                // Survival is the target — creative flight is what the capture leaked — but the
                // spawn point and the gamemode are two changes at once, so keep them separable.
                boolean creative = CREATIVE;
                if (creative) { sg.gamemode = StartGame.CREATIVE; sg.worldGamemode = StartGame.CREATIVE; }
                logSpawnColumn(jc, originX, originZ, px, py, pz);
                log.info(String.format("StartGame authored: entity %d, %s, at (%.1f,%.1f,%.1f)"
                        + " = Java (%.1f,%.1f,%.1f)", RUNTIME_ID, creative ? "creative" : "survival",
                        px, py, pz, jc.posX, jc.posY, jc.posZ));
                out.add(sg.encode());
                continue;
            }
            out.add(p);
        }
        int n = 0;
        // Serve a square with a full ring around the spawn chunk. A chunk's mesh is not built
        // until its neighbours are loaded, so anything on the edge stays invisible; a 4x5 patch
        // leaves the player's own chunk under-served on one side.
        for (int cx = -RADIUS; cx <= RADIUS; cx++) {
            for (int cz = -RADIUS; cz <= RADIUS; cz++) {
                WorldModel.Chunk c = jc.world.getChunk(originX + cx, originZ + cz);
                if (c == null) continue;
                out.add(Mc3dsChunk.encode(c, cx, cz, Y_SHIFT));
                n++;
            }
        }
        if (n == 0) return null;
        log.info("built spawn from " + n + " live Java chunks");
        return McpeBatch.build(out.toArray(new byte[0][]));
    }
}
