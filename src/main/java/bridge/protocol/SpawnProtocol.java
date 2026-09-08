package bridge.protocol;

import bridge.transport.RakNetSession;

import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Logger;

/**
 * M1 spawn state machine: replays the captured MC3DS host spawn sequence so the client
 * spawns into the bridge-served static world. Drives the resource-pack handshake off the
 * client's own responses, exactly as the real host did (FINDINGS.md):
 *
 * <pre>
 *   client 0x04  -> PlayStatus(LOGIN_SUCCESS) + ResourcePacksInfo
 *   client 0x08  -> ResourcePackStack
 *   client 0x08  -> spawn batch (StartGame, gamerules, inventory, skins, chunks, entities)
 * </pre>
 *
 * The spawn batch is the real 30.6 KB captured batch (47 packets) loaded from resources.
 */
public final class SpawnProtocol implements RakNetSession.Handler {

    private static final Logger log = Logger.getLogger("bridge.protocol");

    private byte[] spawnBatch;
    private final byte[] chunkPacket; // optional 0x3a FullChunkData, sent after StartGame (M3 test)
    private final boolean sendSpawnStatus; // append PlayStatus(PLAYER_SPAWN)? real host never does
    private final boolean postSpawnChunk;  // send the chunk AFTER spawn (during play) instead of in spawn
    private boolean postChunkSent = false;
    private final boolean streamAfterSpawn; // keep sending host-like packets after spawn (M3 stream test)
    private final boolean sendLoginResponse; // send the extra 0x00 login-response? real host sends none
    private final byte[] streamFileData;     // captured post-spawn stream to replay (MC3DS_STREAM_FILE)
    private final boolean worldActions;      // answer RequestChunkRadius / RemoveBlock (MC3DS_WORLD=1)
    private final boolean particles;         // also emit LevelEvent on break (MC3DS_PARTICLES=1)
    private volatile World world;            // set at spawn time; live world once the bot is ready
    private final int placeId;               // block to place on UseItem (MC3DS_PLACE, default stone)
    private volatile bridge.javaclient.JavaClient javaLink;  // the live bot, once connected
    private final java.util.function.Function<bridge.protocol.Login, bridge.javaclient.JavaClient> botFactory;  // name -> connected bot
    private volatile bridge.javaclient.JavaClient pendingBot;   // bot the login kicked off connecting
    private volatile boolean botStarted;
    private volatile int javaOriginX, javaOriginZ;     // MC3DS chunk (0,0) == this Java chunk
    private volatile boolean streaming = false;
    // Sequence driven by counting the client's game batches (client->host batches are
    // uncompressed with a header we don't fully frame yet), matching the M0 handshake order:
    //   batch 0 (login)          -> login-response (uncompressed 0x00 batch)
    //   batch 1 (0x04)           -> PlayStatus(LOGIN_SUCCESS) + ResourcePacksInfo
    //   batch 2 (rp response #1) -> ResourcePackStack
    //   batch 3 (rp response #2) -> spawn batch
    private int step = 0;

    public SpawnProtocol() { this(null, null); }

    public SpawnProtocol(bridge.javaclient.JavaClient injected) { this(injected, null); }

    /** Paper plugin: a factory that connects a bot named after the 3DS player once its login arrives. */
    public SpawnProtocol(java.util.function.Function<bridge.protocol.Login, bridge.javaclient.JavaClient> factory) { this(null, factory); }

    private SpawnProtocol(bridge.javaclient.JavaClient injected,
                          java.util.function.Function<bridge.protocol.Login, bridge.javaclient.JavaClient> factory) {
        this.botFactory = factory;
        // MC3DS_SPAWN selects the spawn batch resource. Default is StartGame-only: live testing
        // proved the MC3DS client GENERATES its own world from StartGame and spawns cleanly, while
        // replaying the captured chunks makes it reject the spawn and disconnect (0x15). So the
        // working M1 spawn is StartGame with NO chunks. (spawn_batch.bin = full 47-packet replay
        // incl. chunks; spawn_min.bin = StartGame + chunks — both cause the client to disconnect.)
        String res = System.getenv().getOrDefault("MC3DS_SPAWN", "spawn_startonly.bin");
        byte[] spawnBatch = loadResource("/" + res);
        log.info("spawn resource: " + res + " (" + spawnBatch.length + " B)");

        // Optional FullChunkData (0x3a) packet sent right after StartGame. M3 acceptance test:
        // does the client render a bridge-authored chunk? chunk_gold.bin = synthetic gold surface,
        // chunk_real00.bin = the real captured chunk(0,0). Unset = StartGame-only (self-gen world).
        String chunkRes = System.getenv("MC3DS_CHUNK");
        this.chunkPacket = (chunkRes == null || chunkRes.isEmpty()) ? null : loadResource("/" + chunkRes);
        if (chunkPacket != null) log.info("chunk resource: " + chunkRes + " (" + chunkPacket.length + " B)");

        // The real host NEVER sends PlayStatus(PLAYER_SPAWN=3): the client spawns from the batch
        // content alone (M0 capture: only PlayStatus=0 at login). startonly self-gen needs the
        // trigger; a chunk-bearing batch must NOT get it or the client rejects (0x15). Skip via env.
        this.sendSpawnStatus = !"1".equals(System.getenv("MC3DS_NO_PLAYSTATUS"));
        this.postSpawnChunk = "1".equals(System.getenv("MC3DS_CHUNK_POSTSPAWN"));

        // M3 stream test: the real host floods live packets (SetTime + entity updates) every tick,
        // never going silent. Our bridge went quiet after the spawn and the client dropped ~2s
        // after a chunk. When set, keep sending SetTime after spawn to keep the world "live".
        this.streamAfterSpawn = "1".equals(System.getenv("MC3DS_STREAM"));
        // The real host sends ZERO 0x00 packets (verified in realjoin + m0-run2 captures). Our
        // extra LOGIN_RESPONSE is a session divergence; MC3DS_NO_LOGIN_RESPONSE=1 drops it.
        this.sendLoginResponse = !"1".equals(System.getenv("MC3DS_NO_LOGIN_RESPONSE"));

        // M3 liveness test: after the spawn, replay a CAPTURED real-host post-spawn stream
        // (records [u32be delayMs][u32be len][bytes]) with original timing, to mimic a live
        // two-way host and see if that keeps a chunk-spawned client from disconnecting.
        this.worldActions = "1".equals(System.getenv("MC3DS_WORLD"));
        this.particles = !"0".equals(System.getenv("MC3DS_PARTICLES"));
        int pid = 1;
        try { pid = Integer.parseInt(System.getenv().getOrDefault("MC3DS_PLACE", "1")); } catch (Exception ignore) {}
        this.placeId = pid;
        // MC3DS_JAVA=1: build the spawn from a live Java server instead of a fixture, and keep
        // the connection open so the 3DS player's movement drives real chunk loading.
        // Injected client (Paper plugin's BukkitJavaClient) takes priority; otherwise the standalone
        // bridge resolves the bot when MC3DS_JAVA=1 — the original behaviour, unchanged.
        bridge.javaclient.JavaClient jc = injected;
        int ox = 0, oz = 0;
        if (jc == null && factory == null && "1".equals(System.getenv("MC3DS_JAVA"))) jc = JavaLink.get();
        if (jc != null) {
            ox = (int) Math.floor(jc.posX / 16.0);
            oz = (int) Math.floor(jc.posZ / 16.0);
            byte[] live = JavaLink.buildSpawn(spawnBatch, jc, ox, oz);
            if (live != null) {
                spawnBatch = live;
                log.info("spawn built from the live world at chunk (" + ox + "," + oz + ")");
            }
        }
        this.javaLink = jc;
        this.javaOriginX = ox;
        this.javaOriginZ = oz;
        this.world = World.fromSpawn(spawnBatch);
        this.spawnBatch = spawnBatch;
        log.info("world model: " + world.chunkCount() + " chunks indexed from the spawn");
        if (jc != null) logEncodedSpawnColumn();

        String streamRes = System.getenv("MC3DS_STREAM_FILE");
        this.streamFileData = (streamRes == null || streamRes.isEmpty()) ? null : loadResource("/" + streamRes);
        if (streamFileData != null) log.info("stream-file resource: " + streamRes + " (" + streamFileData.length + " B)");
    }

    /**
     * Reads the spawn column back out of the bytes we are about to send, using {@link World} —
     * the reader that was validated against the captured chunks, and which shares no code with
     * {@link Mc3dsChunk}. JavaLink logs the same column from the Java side before encoding, so the
     * two together say whether a height disagreement is in our encoder or in the client.
     */
    private void logEncodedSpawnColumn() {
        int x = (int) Math.floor(JavaLink.lastSpawnX), z = (int) Math.floor(JavaLink.lastSpawnZ);
        int base = (int) Math.floor(JavaLink.lastSpawnY);
        StringBuilder sb = new StringBuilder("ENCODED spawn column (3DS " + x + "," + z + "):");
        for (int y = base + 4; y >= base - 4; y--) {
            sb.append(String.format("%n    y=%-3d id=%-4d%s", y, world.blockAt(x, y, z),
                    y == base ? "   <- where the player is put" : ""));
        }
        log.info(sb.toString());
    }

    /** Replays the captured post-spawn stream with its original timing (see MC3DS_STREAM_FILE). */
    private void startStreamFileReplay(RakNetSession session) {
        Thread t = new Thread(() -> {
            log.info("stream-file replay ON (" + streamFileData.length + " B)");
            try {
                long start = System.currentTimeMillis();
                int off = 0, sent = 0;
                byte[] d = streamFileData;
                while (off + 8 <= d.length) {
                    long delayMs = ((long) (d[off] & 0xFF) << 24) | ((d[off + 1] & 0xFF) << 16)
                            | ((d[off + 2] & 0xFF) << 8) | (d[off + 3] & 0xFF);
                    int len = ((d[off + 4] & 0xFF) << 24) | ((d[off + 5] & 0xFF) << 16)
                            | ((d[off + 6] & 0xFF) << 8) | (d[off + 7] & 0xFF);
                    off += 8;
                    if (off + len > d.length) break;
                    byte[] batch = java.util.Arrays.copyOfRange(d, off, off + len);
                    off += len;
                    long due = start + delayMs, now = System.currentTimeMillis();
                    if (due > now) Thread.sleep(due - now);
                    session.sendReliable(batch);
                    sent++;
                }
                log.info("stream-file replay done (" + sent + " batches)");
            } catch (Exception e) {
                log.info("stream-file replay stopped: " + e);
            }
        }, "mc3ds-streamfile");
        t.setDaemon(true);
        t.start();
    }

    /** Background thread mimicking the real host's continuous stream (SetTime every 500ms). */
    private void startStream(RakNetSession session) {
        if (streaming) return;
        streaming = true;
        Thread t = new Thread(() -> {
            int time = 0;
            // Rate hypothesis: the real host floods ~hundreds of pkts/s; 500ms (2/s) may read as
            // "host dead". MC3DS_STREAM_MS sets the interval (default 500, try ~15 for a flood).
            int ms = 500;
            try { ms = Integer.parseInt(System.getenv().getOrDefault("MC3DS_STREAM_MS", "500")); } catch (Exception ignore) {}
            log.info("host-like stream ON (SetTime every " + ms + "ms)");
            try {
                while (streaming) {
                    Thread.sleep(ms);
                    session.sendReliable(McpeBatch.build(Mcpe.setTime(time += 20)));
                }
            } catch (Exception e) {
                log.info("stream stopped: " + e);
            }
        }, "mc3ds-stream");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void onConnected(RakNetSession session) {
        log.info("RakNet link up; awaiting client login batch");
    }

    @Override
    public void onGamePacket(RakNetSession session, byte[] payload) {
        try {
            logClient(payload);
            boolean batch = payload.length > 0 && (payload[0] & 0xFF) == McpeBatch.ID;
            if (!batch) return; // non-batch (pings are handled in the RakNet layer)
            switch (step) {
                case 0 -> {
                    // The 3DS login batch is uncompressed and carries {"playerName":"..."} in the
                    // clear. Read it now and kick off a Java bot that logs in under that same name,
                    // so multiple 3DS clients show up as distinct real players. The bot connects on
                    // its own thread; step 3 waits for it before authoring the spawn.
                    if (botFactory != null && !botStarted) {
                        botStarted = true;
                        // The whole login, in full: logClient truncates at 48 bytes, and this is
                        // where a console's skin id (and its stable uuid) first shows up.
                        log.info("3DS login JSON: " + loginJson(payload));
                        String nm = extractPlayerName(payload);
                        final String botName = (nm != null) ? nm : "Tonic3DS";
                        final String skin = loginField(payload, "skinId");
                        final String consoleUuid = loginField(payload, "uuid");
                        log.info("3DS login as '" + botName + "' skin " + skin + " -> connecting its own server bot");
                        final Login login = new Login(botName, skin, consoleUuid);
                        Thread t = new Thread(() -> {
                            try { pendingBot = botFactory.apply(login); }
                            catch (Exception e) { log.warning("bot '" + botName + "' connect failed: " + e); }
                        }, "3ds-bot-" + botName);
                        t.setDaemon(true);
                        t.start();
                    }
                    log.info("client login -> login-response (0x00)"
                            + (sendLoginResponse ? "" : " [SKIPPED via MC3DS_NO_LOGIN_RESPONSE]"));
                    if (sendLoginResponse) session.sendReliable(Mcpe.LOGIN_RESPONSE);
                    step = 1;
                }
                case 1 -> {
                    log.info("client 0x04 -> PlayStatus(LOGIN_SUCCESS) + ResourcePacksInfo");
                    session.sendReliable(McpeBatch.build(
                            Mcpe.playStatus(Mcpe.LOGIN_SUCCESS), Mcpe.RESOURCE_PACKS_INFO_PKT));
                    step = 2;
                }
                case 2 -> {
                    log.info("client rp-response #1 -> ResourcePackStack");
                    session.sendReliable(McpeBatch.build(Mcpe.RESOURCE_PACK_STACK_PKT));
                    step = 3;
                }
                case 3 -> {
                    // Deferred-bot path: the login started a named bot connecting. Give it a bounded
                    // window to finish (login + first chunks) so the spawn we author reflects the real
                    // world at the bot's position. The 3DS sits on its loading screen meanwhile.
                    if (botFactory != null && javaLink == null) {
                        for (int i = 0; i < 60 && pendingBot == null; i++) {
                            try { Thread.sleep(100); } catch (InterruptedException ie) { break; }
                        }
                        if (pendingBot != null) {
                            javaOriginX = (int) Math.floor(pendingBot.posX / 16.0);
                            javaOriginZ = (int) Math.floor(pendingBot.posZ / 16.0);
                            byte[] live = JavaLink.buildSpawn(spawnBatch, pendingBot, javaOriginX, javaOriginZ);
                            if (live != null) spawnBatch = live;
                            world = World.fromSpawn(spawnBatch);
                            javaLink = pendingBot;
                            log.info("live spawn from bot at Java chunk (" + javaOriginX + "," + javaOriginZ + ")");
                        } else {
                            log.warning("bot not ready in time; serving fixture spawn");
                        }
                    }
                    log.info("client rp-response #2 -> SPAWN BATCH (" + spawnBatch.length
                            + " B)" + (chunkPacket != null ? " + FullChunkData" : "")
                            + (sendSpawnStatus ? " + PlayStatus(PLAYER_SPAWN)" : " (no PlayStatus)"));
                    session.sendReliable(spawnBatch);
                    if (chunkPacket != null && !postSpawnChunk) {
                        session.sendReliable(McpeBatch.build(chunkPacket));
                        log.info("sent FullChunkData batch (pre-spawn, " + chunkPacket.length + " B raw)");
                    }
                    // "You're in" — startonly self-gen needs this trigger; the real host omits it
                    // when the batch already carries the spawn (chunks/AddPlayer/SetHealth).
                    if (sendSpawnStatus) session.sendReliable(McpeBatch.build(Mcpe.playStatus(Mcpe.PLAYER_SPAWN)));
                    // Now that we author StartGame we know the player's entity id up front, so the
                    // movement attribute can go out with the spawn instead of waiting for the first
                    // MovePlayer (which left one burst of far-too-fast movement before it landed).
                    if (javaLink != null) { speedSent = true; sendMovementSpeed(session, JavaLink.RUNTIME_ID); }
                    if (streamAfterSpawn) startStream(session);
                    if (streamFileData != null) startStreamFileReplay(session);
                    startJavaChunkStream(session);
                    if (javaLink != null) {
                        javaLink.onBlockChange = ch -> onJavaBlockChange(session, ch);
                        javaLink.onInventory = () -> sendInventory(session);
                        javaLink.onPlayer = (pl, gone) -> onJavaPlayer(session, pl, gone);  // always on in live/plugin mode
                        javaLink.onTime = t -> sendTime(session, t, false);
                        if (javaLink.timeOfDay != -1) sendTime(session, javaLink.timeOfDay, true);  // the spawn's SetTime is a captured value
                        javaLink.onItemAdd = d -> onJavaItemAdd(session, d);
                        javaLink.onItemGone = eid -> onJavaItemGone(session, eid);
                        sendInventory(session);
                    }
                    step = 4;
                }
                default -> {
                    // Post-spawn chunk test: once the client is in-world, push a chunk as a live update.
                    if (postSpawnChunk && chunkPacket != null && !postChunkSent) {
                        postChunkSent = true;
                        session.sendReliable(McpeBatch.build(chunkPacket));
                        log.info("sent FullChunkData batch (POST-spawn, " + chunkPacket.length + " B raw)");
                    }
                    handleWorldActions(session, payload);
                }
            }
        } catch (Exception e) {
            log.warning("onGamePacket error (continuing): " + e);
        }
    }


    /**
     * Answers the world-interaction packets the client sends once it is in-world. Ids are from
     * the ROM-extracted map (tools/PACKET-IDS.json); the payload layouts are inferred from captures
     * and are the part still worth verifying on hardware.
     *
     * <ul>
     *   <li>0x45 RequestChunkRadius -> 0x46 ChunkRadiusUpdated. The client asks for a radius
     *       (observed {@code 45 06}) and the bridge has never replied.</li>
     *   <li>0x15 RemoveBlock -> 0x16 UpdateBlock setting that position to air. Without a reply
     *       the client's break is dropped, which is why the world is read-only.</li>
     * </ul>
     */
    /**
     * The 3DS login batch is uncompressed and carries one small JSON object — verified from a
     * capture as {"playerName":"…","skinId":"Standard_Steve","uuid":"…"} (103 bytes, no skin
     * pixels: the skin is an id the receiving console looks up in its own built-in list).
     */
    static String loginJson(byte[] payload) {
        String s = new String(payload, java.nio.charset.StandardCharsets.ISO_8859_1);
        int i = s.indexOf('{'), j = s.lastIndexOf('}');
        if (i < 0 || j <= i) return "(no JSON in " + payload.length + " bytes)";
        String json = s.substring(i, j + 1);
        return json.length() > 400 ? json.substring(0, 400) + "…" : json;
    }

    /** A string field of the login JSON, raw (not sanitized); null if absent. */
    static String loginField(byte[] payload, String key) {
        String s = new String(payload, java.nio.charset.StandardCharsets.ISO_8859_1);
        String k = "\"" + key + "\":\"";
        int i = s.indexOf(k);
        if (i < 0) return null;
        i += k.length();
        int j = s.indexOf('"', i);
        if (j < 0 || j <= i) return null;
        return s.substring(i, j);
    }

    /** "playerName" from the login, sanitized to a MC-legal name (room left for Floodgate's "."). */
    static String extractPlayerName(byte[] payload) {
        String raw = loginField(payload, "playerName");
        if (raw == null) return null;
        String name = raw.replaceAll("[^A-Za-z0-9_]", "");
        if (name.isEmpty()) return null;
        return name.length() > 14 ? name.substring(0, 14) : name;
    }

    private volatile long lastTimeSentAt;
    private volatile int lastTimeSent = -1;

    /**
     * Day/night. The client runs its own clock between updates (the StartGame gamerule has
     * dodaylightcycle on), so the server's once-a-second Time Update only needs forwarding every
     * few seconds to stay in step — plus immediately on a jump (a /time set, or the cycle being
     * stopped, which Java signals with a negative time that must be re-pinned often).
     */
    private void sendTime(RakNetSession session, long javaTime, boolean force) {
        int ticks = (int) (Math.abs(javaTime) % 24000L);
        boolean stopped = javaTime < 0;
        long now = System.currentTimeMillis();
        int drift = lastTimeSent < 0 ? 24000 : Math.abs(ticks - lastTimeSent);
        boolean due = now - lastTimeSentAt >= (stopped ? 1000 : 5000);
        if (!force && !due && drift < 200) return;
        lastTimeSentAt = now; lastTimeSent = ticks;
        session.sendReliable(McpeBatch.build(Mcpe.setTime(ticks)));
    }

    private static final int AIR_MAX = 400;      // the value the captured player metadata carries
    private int lastAirSent = -1;

    /**
     * Bubbles. The 3DS drains the air bar locally while the head is under water, but it only
     * refills when the host says so — and we never did, so the bubbles stayed up for good. Watch
     * the block at the player's EYE (MovePlayer carries the eye, not the feet) in the world we
     * served, and push a full bar once each time they surface. Underwater we stay quiet and let
     * the client drain, so drowning still works.
     */
    private void refillAir(RakNetSession session, int entityId, float x, float eyeY, float z) {
        int at = world.blockAt((int) Math.floor(x), (int) Math.floor(eyeY), (int) Math.floor(z));
        if (at == 8 || at == 9) { lastAirSent = -1; return; }   // still under: the client is draining it
        if (lastAirSent == AIR_MAX) return;                     // already told them, once is enough
        lastAirSent = AIR_MAX;
        session.sendReliable(McpeBatch.build(Mc3dsEntity.airSupply(entityId, AIR_MAX)));
        log.info("surfaced -> air refilled for entity " + entityId);
    }

    private void handleWorldActions(RakNetSession session, byte[] payload) {
        if (!worldActions && javaLink == null) return;  // live/plugin mode always handles 3DS actions
        for (byte[] p : McpeBatch.inflate(payload)) {
            if (p.length == 0) continue;
            int pktId = p[0] & 0xFF;
            if (!HANDLED.contains(pktId) && seenIds.add(pktId)) {
                log.info(String.format("client sends 0x%02x (%d B), unhandled: %s", pktId, p.length,
                        java.util.HexFormat.of().formatHex(p, 0, Math.min(p.length, 32))));
            }
            switch (pktId) {
                case 0x1F -> {
                    // MobEquipment: what the player is holding and from which hotbar slot.
                    //   1f | entityId varint | stack | inventorySlot u8 | hotbarSlot u8 | window u8
                    // The stack is the same shape as ContainerSetContent's:
                    //   zz(id) | zz(aux) | zz(9) | zz(0) | 00 00 00 00,  aux = damage<<8 | count
                    // Placement on the server uses what the BOT is holding, and the bot's selected
                    // slot was always 0 — so a player placing from any other slot had the bot try
                    // to place an empty hand, and nothing appeared in the Java world.
                    int[] e = readVarint(p, 1);
                    if (e == null) break;
                    int[] stack = readStack(p, e[1]);
                    if (stack == null || stack[3] + 1 >= p.length) break;
                    int hotbarSlot = p[stack[3] + 1] & 0xFF;
                    if (hotbarSlot > 8) break;
                    if (hotbarSlot != heldSlot || stack[0] != heldItem) {
                        heldSlot = hotbarSlot;
                        heldItem = stack[0];
                        log.info("client holds item " + stack[0] + " x" + stack[1] + " dmg " + stack[2]
                                + " in hotbar slot " + hotbarSlot);
                        if (javaLink != null) armBot(hotbarSlot, stack);
                    }
                }
                case 0x45 -> {                      // RequestChunkRadius
                    int radius = p.length > 1 ? (p[1] & 0xFF) : 8;
                    session.sendReliable(McpeBatch.build(new byte[] {0x46, (byte) radius}));
                    log.info("client RequestChunkRadius(" + radius + ") -> ChunkRadiusUpdated");
                }
                case 0x24 -> {
                    // PlayerAction, read off the wire (24 0b 02 1c 48 39 00) and confirmed by the
                    // RemoveBlock that followed it naming the same block:
                    //   24 | entityId varint | action zz | zigzag x | y u8 | zigzag z | face u8
                    // The action values the client actually uses match MCPE's enum: 0 START_BREAK,
                    // 1 ABORT_BREAK, 2 STOP_BREAK, 18 CONTINUE_BREAK, 8 jump, 9/10 sprint.
                    int[] e = readVarint(p, 1);
                    int[] a = e == null ? null : readVarint(p, e[1]);
                    int[] c = a == null ? null : readPos(p, a[1]);
                    if (c == null) break;
                    int action = World.zigzag(a[0]);
                    if (seenActions.add(action)) {
                        log.info("client PlayerAction action=" + action + " at (" + c[0] + ","
                                + c[1] + "," + c[2] + ")  raw=" + java.util.HexFormat.of().formatHex(p));
                    }
                    if (!breakProgress) break;
                    switch (action) {
                        case ACTION_START_BREAK -> startBreak(session, c[0], c[1], c[2]);
                        // CONTINUE_BREAK arrives every tick while the button is held. It means the
                        // crosshair moved to a new block, not "still going" — re-sending the start
                        // event on each one restarted the animation, which is why the crack raced.
                        case ACTION_CONTINUE_BREAK -> {
                            if (breakX != c[0] || breakY != c[1] || breakZ != c[2]) {
                                startBreak(session, c[0], c[1], c[2]);
                            }
                        }
                        case ACTION_ABORT_BREAK, ACTION_STOP_BREAK -> stopBreak(session);
                        default -> { }
                    }
                }
                case 0x15 -> {                      // RemoveBlock: 15 | x varint | y u8 | z varint
                    int[] c = readPos(p, 1);
                    if (c == null) break;
                    int was = world.blockAt(c[0], c[1], c[2]);
                    world.setBlock(c[0], c[1], c[2], 0);
                    session.sendReliable(McpeBatch.build(updateBlock(c[0], c[1], c[2], 0, 0)));
                    log.info("client RemoveBlock(" + c[0] + "," + c[1] + "," + c[2]
                            + ") was id=" + was + " -> UpdateBlock air");
                    // Break it on the Java server too, so the world actually changes there and the
                    // block drops its item. RemoveBlock carries no face; the server is lenient
                    // about which one a dig claims, so claim the top.
                    if (javaLink != null) {
                        syncBotToPlayer();
                        javaLink.sendDig(c[0] + javaOriginX * 16, c[1] - JavaLink.Y_SHIFT,
                                c[2] + javaOriginZ * 16, 1);
                    }
                    if (particles) {
                        // position is the block corner: the client appears to centre it itself
                        session.sendReliable(McpeBatch.build(
                                levelEvent(2001, c[0], c[1], c[2], was)));
                        log.info("  + LevelEvent(2001) data=" + was);
                    }
                }
                case 0x13 -> {                      // MovePlayer -> walk our Java avatar there
                    // 13 | varint id | f32 x | f32 y | f32 z | ...
                    if (javaLink == null || p.length < 14) break;
                    int[] eid = readVarint(p, 1);
                    if (eid == null) break;
                    // Backstop for runs that still replay the captured StartGame: its
                    // UpdateAttributes targets the captured session's entity, so our player gets no
                    // movement attribute and falls back to a default that is far too fast.
                    // MovePlayer names our real entity id. The live path sends this at spawn.
                    if (!speedSent) { speedSent = true; sendMovementSpeed(session, eid[0]); }
                    float x = f32(p, eid[1]), y = f32(p, eid[1] + 4), z = f32(p, eid[1] + 8);
                    long now = System.currentTimeMillis();
                    // Speed over a 3s window rather than between consecutive packets.
                    // sendReliable is synchronized and the chunk-streaming thread holds it while
                    // encoding ~14 KB chunks, so inbound packets arrive in bursts: consecutive-pair
                    // timing gives wildly inflated figures. A long window averages that out.
                    track.add(new double[] {now, x, y, z});
                    while (track.size() > 1 && now - track.get(0)[0] > 3000) track.remove(0);
                    if (now - lastSpeedLog > 3000 && track.size() > 4) {
                        lastSpeedLog = now;
                        double[] a = track.get(0), b = track.get(track.size() - 1);
                        double dt = (b[0] - a[0]) / 1000.0;
                        if (dt > 0.5) {
                            double flat = Math.hypot(b[1] - a[1], b[3] - a[3]) / dt;
                            double path = 0;
                            for (int k = 1; k < track.size(); k++) {
                                double[] q = track.get(k - 1), r = track.get(k);
                                path += Math.hypot(r[1] - q[1], r[3] - q[3]);
                            }
                            int fx = (int) Math.floor(x), fz = (int) Math.floor(z);
                            int fy = (int) Math.floor(feet(y));   // MovePlayer carries the eye
                            log.info(String.format("   at x=%.1f z=%.1f -> MC3DS chunk (%d,%d)",
                                    x, z, fx >> 4, fz >> 4));
                            log.info(String.format(
                                    "%.1fs window: net %.1f b/s, path %.1f b/s, y %.1f -> %.1f | "
                                    + "at feet id=%d, head id=%d, under id=%d",
                                    dt, flat, path / dt, a[2], b[2],
                                    world.blockAt(fx, fy, fz),
                                    world.blockAt(fx, fy + 1, fz),
                                    world.blockAt(fx, fy - 1, fz)));
                        }
                    }
                    // MovePlayer: 13 | eid | f32 x,y,z | f32 pitch,yaw,headYaw | 3 B.
                    // Verified against live packets — pitch stays within +-90 and yaw and headYaw
                    // are always equal.
                    refillAir(session, eid[0], x, y, z);
                    lastPitch = f32(p, eid[1] + 12);
                    lastYaw = f32(p, eid[1] + 16);
                    lastPosX = x; lastPosY = y; lastPosZ = z; lastPosT = now;
                    playerCx = ((int) Math.floor(x)) >> 4;
                    playerCz = ((int) Math.floor(z)) >> 4;
                    if (now - lastMoveSent < 50) break;       // relay at ~20/s, like a real client;
                                                              // 250ms made the bot jump 4x/s to others
                    lastMoveSent = now;
                    if ("1".equals(System.getenv("MC3DS_NO_RELAY"))) break;
                    // MovePlayer carries the EYE position; Java's position packet wants the feet.
                    // Relaying it raw walked the bot ~1.6 blocks above the ground, which is why its
                    // reported y kept climbing and no longer matched the terrain underneath it.
                    javaLink.sendPositionLook(x + javaOriginX * 16.0,
                            y - JavaLink.Y_SHIFT - JavaLink.EYE_HEIGHT, z + javaOriginZ * 16.0,
                            lastYaw, lastPitch);
                }
                case 0x23 -> {                      // UseItem = block placement
                    // Log one raw sample per FACE, not per length: every UseItem is 40 bytes, so
                    // deduping by length showed only the first placement ever made and hid every
                    // side placement — the exact case that was failing.
                    if (p.length >= 8) {
                        int[] pf = readVarint(p, posEnd(p, 1));
                        int seen = pf == null ? -1 : World.zigzag(pf[0]);
                        if (seenUseItem.add(seen)) {
                            log.info("client UseItem raw (face " + seen + "): "
                                    + java.util.HexFormat.of().formatHex(p));
                        }
                    }
                    // 23 | BlockPos | face varint | varint | Vec3 | Vec3 | ItemStack(count,id)
                    // The client also sends bare single-byte 0x23s; those must be ignored, or
                    // they parse as (0,0,0) face 0 and drop a phantom block under the world.
                    if (p.length < 32) break;
                    int[] c = readPos(p, 1);
                    if (c == null) break;
                    // The field straight after the BlockPos is the id of the block being CLICKED,
                    // not the face — grass (2) in a packet placing on the ground, birch log (17) in
                    // one placing against a tree. The face is the varint after that, zigzag-encoded
                    // like every other signed field here.
                    //
                    // Both fields read 02 when placing on top of something, so reading the first
                    // one worked for exactly that case and silently produced nonsense (face -9) for
                    // every other. The click position in the same packet settles it: clickPos.y==1.0
                    // is the top face, clickPos.x==0.0 the west face.
                    int[] clickedId = readVarint(p, posEnd(p, 1));
                    if (clickedId == null) break;
                    int[] f = readVarint(p, clickedId[1]);
                    if (f == null) break;
                    int face = World.zigzag(f[0]);
                    if (face < 0 || face > 5) break;                // not a face -> not a placement
                    int q = f[1] + 24;                              // two Vec3s
                    int[] before = readVarint(p, q);                // one field ahead of the stack
                    if (before == null) break;
                    int[] stack = readStack(p, before[1]);
                    if (stack == null) break;
                    int id = stack[0], meta = stack[2];
                    if (id <= 0 || id > 255) break;                 // not a placeable block
                    // You can only place against a block that exists. The client also emits
                    // all-zero 0x23s (lengths 1/5/6/7 and some 40s) which parse as (0,0,0);
                    // without this they placed a copy of the held block at y=-1, and since
                    // updateBlock writes y as a byte that became y=255 — a stray block in the
                    // sky. Requiring a solid click target rejects every one of them.
                    int against = world.blockAt(c[0], c[1], c[2]);
                    int[] d = FACE_OFFSET[face];
                    int x = c[0] + d[0], y = c[1] + d[1], z = c[2] + d[2];
                    int into = world.blockAt(x, y, z);
                    // Say WHY a placement was refused. Silently breaking out of the switch made a
                    // rejected placement look identical to one that never arrived, which is how
                    // this went undiagnosed: the packet was being parsed correctly the whole time.
                    if (against == 0 || (y < 0 || y > 127) || !isReplaceable(into)) {
                        log.info("refusing UseItem: clicked (" + c[0] + "," + c[1] + "," + c[2]
                                + ") holds id=" + against + ", target (" + x + "," + y + "," + z
                                + ") holds id=" + into + ", face=" + face + ", item=" + id
                                + (world.isServed(c[0], c[2]) ? "" : "  [chunk not in the model]"));
                        // The client has already taken the block out of the hand. Nothing tells it
                        // otherwise, so a refused placement silently ate an item until some later
                        // inventory change happened to correct the count. Push the bot's inventory,
                        // which is the truth, and the block comes straight back.
                        if (javaLink != null) sendInventory(session);
                        break;
                    }
                    if (!world.setBlock(x, y, z, id)) {
                        log.warning("placed (" + x + "," + y + "," + z + ") but the model would not"
                                + " record it — the next placement here will be accepted again");
                    }
                    // Send the metadata too. It was hardcoded to 0, which is oak for a log, so
                    // placing birch (id 17, damage 2) drew oak on the 3DS while the server — which
                    // places whatever the bot is holding, the real item — put down birch.
                    session.sendReliable(McpeBatch.build(updateBlock(x, y, z, id, meta)));
                    // Java's placement packet names the block that was CLICKED and the face, and
                    // works out the target cell itself — so send the click, not our computed cell.
                    if (javaLink != null) {
                        syncBotToPlayer();
                        if (javaLink.onPlace != null) {
                            // Plugin mode: hand the server the cell we computed and the block to put
                            // there. A bot placement packet is refused inside spawn protection; a
                            // Bukkit edit is not. The click+face form below is the client-packet path.
                            javaLink.onPlace.accept(new int[] {x + javaOriginX * 16, y - JavaLink.Y_SHIFT,
                                    z + javaOriginZ * 16, id, meta});
                        } else {
                            javaLink.sendPlace(c[0] + javaOriginX * 16, c[1] - JavaLink.Y_SHIFT,
                                    c[2] + javaOriginZ * 16, face);
                        }
                        // The server has the final say — it can refuse for reasons this side cannot
                        // see, a player standing in the cell being the obvious one. Re-push the
                        // inventory once the round trip has had time to land: if the placement
                        // stuck the bot is one block down, and if it did not the block reappears.
                        inventoryRefreshAt = System.currentTimeMillis() + PLACE_ROUND_TRIP_MS;
                    }
                    log.info("client UseItem at (" + c[0] + "," + c[1] + "," + c[2] + ") face=" + face
                            + " item=" + id + ":" + meta + " -> place at (" + x + "," + y + "," + z + ")");
                }
                default -> { }
            }
        }
    }

    /**
     * Sets the player's movement attribute explicitly. The capture's UpdateAttributes carries
     * minecraft:movement = 0.1 (vanilla walking), but it targets the captured session's entity,
     * so our player may never receive it. MC3DS_SPEED overrides the value for testing.
     *
     * 0x1e: id | entityId varint | count varint | [ min f32 | max f32 | current f32 |
     *       default f32 | varint nameLen | name ]*
     */
    private void sendMovementSpeed(RakNetSession session, int eid) {
        float speed = Float.parseFloat(System.getenv().getOrDefault("MC3DS_SPEED", "0.1"));
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        o.write(0x1e);
        writeVarint(o, eid);
        writeVarint(o, 1);
        for (float f : new float[] {0f, 3.4028235e38f, speed, speed}) {
            int b = Float.floatToIntBits(f);
            o.write(b & 0xFF); o.write((b >>> 8) & 0xFF);
            o.write((b >>> 16) & 0xFF); o.write((b >>> 24) & 0xFF);
        }
        byte[] name = "minecraft:movement".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        writeVarint(o, name.length);
        o.writeBytes(name);
        session.sendReliable(McpeBatch.build(o.toByteArray()));
        log.info("sent UpdateAttributes: minecraft:movement=" + speed + " for entity " + eid);
    }

    /**
     * The Java server's verdict on a block, pushed to the 3DS. This is what makes the world
     * two-way: a break the server refuses comes back as the original block, a break another
     * player makes appears here, and our optimistic local edit gets corrected rather than
     * silently diverging from the server's world.
     */
    private void onJavaBlockChange(RakNetSession session, int[] ch) {
        int x = ch[0] - javaOriginX * 16, z = ch[2] - javaOriginZ * 16;
        int y = ch[1] + JavaLink.Y_SHIFT;
        int id = ch[3] >> 4, meta = ch[3] & 0xF;
        // Say why a change was dropped. Every one of these returns used to be silent, so a change
        // the 3DS never saw was indistinguishable from one the server never sent.
        if (y < 0 || y > 255) { log.info("skip block change: y=" + y + " out of range"); return; }
        if (!world.isServed(x, z)) {
            log.info("skip block change (" + x + "," + y + "," + z + "): chunk not served");
            return;
        }
        if (id > 255) { log.info("skip block change: id " + id + " has no MC3DS equivalent"); return; }
        // NB a stale model reads as air, and "the server says air too" then looks like agreement —
        // which is exactly the case where the 3DS most needs telling.
        if (world.blockAt(x, y, z) == id && world.metaAt(x, y, z) == meta) return;
        world.setBlock(x, y, z, id);
        session.sendReliable(McpeBatch.build(updateBlock(x, y, z, id, meta)));
        log.info("java block change (" + x + "," + y + "," + z + ") -> id=" + id + " meta=" + meta);
    }

    /** MovePlayer's y is the eye; the world has to be sampled at the feet. */
    private static float feet(float eyeY) { return eyeY - StartGame.EYE_HEIGHT; }

    /** Ids handleWorldActions acts on; everything else is logged once so it stops being invisible. */
    private static final java.util.Set<Integer> HANDLED =
            java.util.Set.of(0x45, 0x24, 0x15, 0x23, 0x13, 0x1F);
    private final java.util.Set<Integer> seenIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static final int ACTION_START_BREAK = 0, ACTION_ABORT_BREAK = 1,
            ACTION_STOP_BREAK = 2, ACTION_CONTINUE_BREAK = 18;

    /** The block the client is currently cracking, so we can stop that one before starting another. */
    private int breakX, breakY, breakZ;
    private boolean breaking;

    /**
     * Starts the crack overlay on a block. The client does not draw break progress on its own — it
     * waits for the host — so without this nothing happens; with it sent indiscriminately the crack
     * raced, stuck after a single punch, and stayed on blocks the player had looked away from.
     *
     * <p>MCPE's LevelEvent 3600 starts the animation and 3601 stops it, with 3600's data being
     * 65535 / break-time-in-ticks. 2001 (destroy particles) already proved the LevelEvent id space
     * is MCPE's.
     */
    private void startBreak(RakNetSession session, int x, int y, int z) {
        if (breaking) stopBreak(session);
        breakX = x; breakY = y; breakZ = z; breaking = true;
        int ticks = breakTicks(world.blockAt(x, y, z));
        session.sendReliable(McpeBatch.build(levelEvent(3600, x, y, z, 65535 / ticks)));
    }

    private void stopBreak(RakNetSession session) {
        if (!breaking) return;
        breaking = false;
        session.sendReliable(McpeBatch.build(levelEvent(3601, breakX, breakY, breakZ, 0)));
    }

    /**
     * Roughly how long a bare hand takes on a block, in ticks: hardness x 1.5 s for blocks that
     * need no tool, x 5 s for the ones that do. The crack only has to look right — the client runs
     * its own timer and tells us when the block is actually gone.
     */
    private static int breakTicks(int id) {
        int t = switch (id) {
            case 18, 161, 31, 175, 37, 38, 39, 40 -> 6;      // leaves, grass, flowers
            case 20, 102, 89 -> 9;                           // glass, panes, glowstone
            case 3, 12, 78, 79, 82 -> 15;                    // dirt, sand, snow, ice, clay
            case 2, 13 -> 18;                                // grass block, gravel
            case 35, 5 -> 24;                                // wool, planks
            case 17, 162 -> 60;                              // logs
            case 87 -> 40;                                   // netherrack
            case 24 -> 80;                                   // sandstone
            case 1, 48, 98 -> 150;                           // stone and its family (needs a pick)
            case 4, 45, 43, 44 -> 200;                       // cobblestone, brick, slabs
            case 14, 15, 16, 21, 56, 73, 74, 129 -> 300;     // ores
            case 49 -> 900;                                  // obsidian
            default -> 30;
        };
        return Math.max(t, 2);
    }

    /** Log each PlayerAction value once; the client repeats the same one many times per dig. */
    private final java.util.Set<Integer> seenActions = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final boolean breakProgress = !"0".equals(System.getenv("MC3DS_BREAK_PROGRESS"));
    /** Ticks a break is claimed to take, for LevelEvent 3600's rate. */
    private static final int BREAK_TICKS =
            Integer.parseInt(System.getenv().getOrDefault("MC3DS_BREAK_TICKS", "20"));

    /**
     * Puts the bot exactly where the player is, immediately, before an interaction is forwarded.
     *
     * <p>The movement relay is throttled to 250 ms so it does not flood the server, which leaves
     * the bot up to a block or two behind — enough for an interaction at arm's length to fall
     * outside the server's reach. The previous answer was to teleport the bot next to the block
     * being touched and let the relay drag it back, and that is what produced Paper's
     * "bridgebot moved too quickly" warnings: two large opposing jumps in quick succession. A
     * rejected move gets rubber-banded, the interaction that follows lands out of reach, and the
     * server drops it without a word — which is how placements ended up on the 3DS and nowhere else.
     *
     * <p>Following the player instead keeps every step small. The 3DS enforces its own reach, so a
     * block the player can click is a block the bot standing in their shoes can reach.
     */
    private void syncBotToPlayer() {
        if (lastPosT == 0) return;                       // no MovePlayer yet; nothing to sync to
        javaLink.sendPositionLook(lastPosX + javaOriginX * 16.0,
                lastPosY - JavaLink.Y_SHIFT - JavaLink.EYE_HEIGHT,
                lastPosZ + javaOriginZ * 16.0, lastYaw, lastPitch);
    }

    /**
     * Mirrors the bot's inventory into the 3DS's hotbar and pack, so blocks the player mines
     * actually turn up in their hands. Java's hotbar lives at slots 36-44 and its main inventory at
     * 9-35; MC3DS's window 0 is 45 slots whose first nine the hotbar table points at, so the two
     * hotbars are moved to the front and the pack keeps its position.
     */
    private void sendInventory(RakNetSession session) {
        inventoryDirty = true;
        if (!inventoryPumpStarted) startInventoryPump(session);
    }

    /**
     * Sends the inventory at most every {@link #INVENTORY_MIN_GAP_MS}, and always sends the last
     * state. Picking up a stack produces a burst of Set Slot packets; rate-limiting by dropping
     * them would have left the hotbar showing whatever the burst happened to start with.
     */
    private void startInventoryPump(RakNetSession session) {
        inventoryPumpStarted = true;
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(INVENTORY_MIN_GAP_MS);
                    long due = inventoryRefreshAt;
                    if (due != 0 && System.currentTimeMillis() >= due) {
                        inventoryRefreshAt = 0;
                        inventoryDirty = true;
                    }
                    if (!inventoryDirty) continue;
                    inventoryDirty = false;
                    int[][] slots = new int[Inventory.PLAYER_SLOTS][];
                    for (int i = 0; i < 9; i++) slots[i] = javaLink.inventory[36 + i];   // hotbar
                    for (int i = 9; i < 36; i++) slots[i] = javaLink.inventory[i];       // main pack
                    session.sendReliable(McpeBatch.build(
                            Inventory.setContent(JavaLink.spawnUniqueId, Inventory.WINDOW_PLAYER, slots)));
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    log.warning("inventory push failed: " + e);
                }
            }
        }, "mc3ds-inventory");
        t.setDaemon(true);
        t.start();
    }

    private volatile boolean inventoryDirty, inventoryPumpStarted;
    private volatile long inventoryRefreshAt;
    private static final long INVENTORY_MIN_GAP_MS = 150;
    /** Long enough for the server to have accepted or refused a placement. */
    private static final long PLACE_ROUND_TRIP_MS = 400;

    /**
     * Whether a block can be built into. Minecraft lets you place through tall grass, flowers,
     * snow and liquids; treating every non-air block as an obstruction refused those placements,
     * and since the 3DS does not draw some of them the refusal looked like it came from nowhere.
     */
    private static boolean isReplaceable(int id) {
        return switch (id) {
            case 0,                       // air
                 8, 9, 10, 11,            // water and lava, still and flowing
                 31, 32,                  // tall grass, dead bush
                 37, 38, 39, 40,          // flowers and mushrooms
                 51,                      // fire
                 78,                      // snow layer
                 106,                     // vines
                 175 -> true;             // double plants
            default -> false;
        };
    }

    /**
     * Points the bot at the same hotbar slot the player is using, so a placement puts down the
     * block the player actually has selected. In creative the bot is handed the item outright —
     * a creative client arms itself with Creative Inventory Action, and without it the bot's hands
     * stay empty however the 3DS's own inventory looks.
     */
    private void armBot(int hotbarSlot, int[] stack) {
        javaLink.sendHeldSlot(hotbarSlot);
        if (JavaLink.CREATIVE && stack[0] > 0) {
            javaLink.sendCreativeSet(36 + hotbarSlot, stack[0], Math.max(stack[1], 1), stack[2]);
        }
    }

    /**
     * Reads an MC3DS item stack, the shape shared by ContainerSetContent, MobEquipment and
     * DropItem: {@code zz(id) | zz(aux) | zz(9) | zz(0) | 00 00 00 00}, with aux packing
     * {@code damage << 8 | count}. Returns {id, count, damage, endOffset}, or null if it runs off
     * the end. An id of 0 is an empty stack, written as a single zero byte.
     */
    private static int[] readStack(byte[] p, int off) {
        int[] id = readVarint(p, off);
        if (id == null) return null;
        if (World.zigzag(id[0]) == 0) return new int[] {0, 0, 0, id[1]};
        int[] aux = readVarint(p, id[1]);
        if (aux == null) return null;
        int[] c = readVarint(p, aux[1]);
        if (c == null) return null;
        int[] d = readVarint(p, c[1]);
        if (d == null || d[1] + 4 > p.length) return null;
        int a = World.zigzag(aux[0]);
        return new int[] {World.zigzag(id[0]), a & 0xFF, a >> 8, d[1] + 4};
    }

    private int heldSlot = -1, heldItem = -1;
    /** One raw UseItem sample per face value, so a failing face is never invisible. */
    private final java.util.Set<Integer> seenUseItem = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Shows another Java player on the 3DS. Their entity id doubles as the MC3DS runtime id, offset
     * clear of the local player's, and the unique id is derived from it so RemoveEntity can name
     * the same entity later.
     *
     * <p>Untested: written while the console was unavailable. AddPlayer and MoveEntity are both
     * read from captures, but nothing has yet confirmed the client accepts what we build.
     */
    /** MC3DS_ITEMS=0 turns dropped-item rendering off, so a crash can be pinned on AddItemEntity. */
    private static final boolean ITEMS = !"0".equals(System.getenv().getOrDefault("MC3DS_ITEMS", "1"));

    /** Shows a server drop on the 3DS. Positioned as-is: items have no eye offset. */
    private void onJavaItemAdd(RakNetSession session, bridge.javaclient.JavaClient.ItemDrop d) {
        if (!ITEMS) return;
        float x = (float) (d.x - javaOriginX * 16.0);
        float y = (float) (d.y + JavaLink.Y_SHIFT);
        float z = (float) (d.z - javaOriginZ * 16.0);
        if (!world.isServed((int) Math.floor(x), (int) Math.floor(z))) return;
        if (runtimeIds.containsKey(d.entityId)) return;          // already shown
        int id = nextRuntimeId++;
        runtimeIds.put(d.entityId, id);
        session.sendReliable(McpeBatch.build(
                Mc3dsEntity.addItemEntity(id, id, d.itemId, d.count, d.damage, x, y, z)));
        log.info("showing drop item " + d.itemId + " x" + d.count + " on the 3DS at (" + (int) x + ","
                + (int) y + "," + (int) z + ") as entity " + id);
    }

    private void onJavaItemGone(RakNetSession session, int javaEntityId) {
        Integer id = runtimeIds.remove(javaEntityId);
        if (id != null) session.sendReliable(McpeBatch.build(Mc3dsEntity.removeEntity(id)));
    }

    private void onJavaPlayer(RakNetSession session, bridge.javaclient.JavaClient.Player p,
                              boolean gone) {
        if (gone) {
            Integer id = runtimeIds.remove(p.entityId);
            if (id != null) session.sendReliable(McpeBatch.build(Mc3dsEntity.removeEntity(id)));
            return;
        }
        float x = (float) (p.x - javaOriginX * 16.0);
        // Players are positioned by the EYE, as in StartGame and MovePlayer. Sending the feet
        // height drew the model about 1.6 blocks low, sunk to its feet in the ground.
        float y = (float) (p.y + JavaLink.Y_SHIFT + StartGame.EYE_HEIGHT);
        float z = (float) (p.z - javaOriginZ * 16.0);
        entityCalls++;
        if (!world.isServed((int) Math.floor(x), (int) Math.floor(z))) {
            entitySkippedUnserved++;
            reportEntityRate();
            return;
        }
        Integer id = runtimeIds.get(p.entityId);
        if (id == null) {
            // Small ids, allocated in sequence. Java's entity ids run into the thousands, and the
            // captured host numbers its entities from 1 — if the client indexes a fixed-size table
            // by runtime id, handing it 2863 is enough on its own to bring it down.
            id = nextRuntimeId++;
            runtimeIds.put(p.entityId, id);
            byte[] uuid = new byte[16];
            java.nio.ByteBuffer.wrap(uuid)
                    .putLong(p.uuid.getMostSignificantBits())
                    .putLong(p.uuid.getLeastSignificantBits());
            // The skin must be registered BEFORE the entity exists, or the first attempt to draw
            // it has nothing to draw with.
            // Another 3DS's real skin: its console named its built-in skin in its login, and this
            // console resolves the same id locally — no pixels needed (plugin mode wires skinFor;
            // anything unknown stays Steve). The Floodgate "." prefix is a server-side artefact,
            // so it is dropped from the nametag.
            String skin = javaLink != null ? javaLink.skinFor.apply(p.name) : null;
            if (skin == null || skin.isEmpty()) skin = Mc3dsEntity.SKIN_STEVE;
            String shown = p.name.startsWith(".") ? p.name.substring(1) : p.name;
            session.sendReliable(McpeBatch.build(
                    Mc3dsEntity.playerList(uuid, id, shown, skin)));
            session.sendReliable(McpeBatch.build(Mc3dsEntity.addPlayer(
                    uuid, shown, id, id, x, y, z, p.yaw, p.pitch)));
            log.info("showing player " + shown + " (" + skin + ") on the 3DS at (" + (int) x + ","
                    + (int) y + "," + (int) z + ") as entity " + id);
            return;
        }
        // MC3DS_ENTITY_MOVE=0 leaves spawned players standing still, so a crash can be pinned on
        // AddPlayer or on MoveEntity rather than on "entities" as a whole.
        if (!ENTITY_MOVE) return;
        // Every update is forwarded. Rate-limiting to ten a second made the motion MORE broken,
        // not less, which rules out volume as the cause of the jitter: fewer absolute positions
        // just means bigger jumps between them.
        entityMovesSent++;
        reportEntityRate();
        session.sendReliable(McpeBatch.build(
                Mc3dsEntity.moveEntity(id, x, y, z, p.yaw, p.pitch, true, ENTITY_TELEPORT)));
    }

    /**
     * MoveEntity's last byte. The capture always has 1, but its only sample is an entity spinning
     * on the spot, where snapping and interpolating look identical. If the byte means "teleport",
     * 0 lets the client move the model between updates instead of restating its position twenty
     * times a second — which is what the jitter looks like. MC3DS_ENTITY_TELEPORT=1 restores the
     * captured value.
     */
    private static final boolean ENTITY_TELEPORT = "1".equals(System.getenv("MC3DS_ENTITY_TELEPORT"));

    private int entityCalls, entityMovesSent, entitySkippedUnserved;
    private long lastEntityRateLog;

    /** Says how many player updates arrive versus how many reach the 3DS, and why the rest do not. */
    private void reportEntityRate() {
        long now = System.currentTimeMillis();
        if (now - lastEntityRateLog < 1000) return;
        lastEntityRateLog = now;
        log.info("player updates: " + entityCalls + " in, " + entityMovesSent + " sent, "
                + entitySkippedUnserved + " skipped (chunk not served)");
    }

    private static final boolean ENTITIES = "1".equals(System.getenv("MC3DS_ENTITIES"));
    private static final boolean ENTITY_MOVE = !"0".equals(System.getenv("MC3DS_ENTITY_MOVE"));

    /** Java entity id -> the small runtime id we give it on the 3DS. Our own player is 11. */
    private final java.util.Map<Integer, Integer> runtimeIds =
            new java.util.concurrent.ConcurrentHashMap<>();
    private volatile int nextRuntimeId = 20;

    private boolean speedSent;
    private long lastMoveSent, lastPosT, lastSpeedLog;
    private float lastPosX, lastPosY, lastPosZ, lastYaw, lastPitch;
    private double fastest;
    private final java.util.List<double[]> track = new java.util.ArrayList<>();
    private final java.util.Set<Long> sentChunks = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.BlockingQueue<int[]> pending =
            new java.util.concurrent.LinkedBlockingQueue<>();
    /** Chunks around the PLAYER to keep loaded; 4 => a 9x9 area that travels with them. */
    private static final int STREAM_RADIUS =
            Integer.parseInt(System.getenv().getOrDefault("MC3DS_STREAM_RADIUS", "6"));
    /**
     * How far past the radius a chunk must fall before we forget having sent it. Without the
     * margin, a player pacing across a chunk border would make the boundary chunks drop and
     * re-send on every step.
     */
    private static final int STREAM_HYSTERESIS = 2;
    /** Gap between streamed chunks, so the client can keep up. */
    private static final int STREAM_INTERVAL_MS =
            Integer.parseInt(System.getenv().getOrDefault("MC3DS_STREAM_INTERVAL", "250"));

    /** The player's chunk, from MovePlayer — what the streamer centres on. */
    private volatile int playerCx, playerCz;

    /**
     * Pushes chunks to the 3DS as the Java server loads them, so walking reveals real terrain
     * instead of the edge of a snapshot. Chunks are sent once each and only within a radius of
     * the origin, since MC3DS coordinates are a fixed chunk offset from Java's.
     */
    private void startJavaChunkStream(RakNetSession session) {
        if (javaLink == null) return;
        for (byte[] p : McpeBatch.inflate(spawnBatch)) {
            if ((p[0] & 0xFF) == 0x3A) sentChunks.add(key(World.zigzag(p[1] & 0xFF), World.zigzag(p[2] & 0xFF)));
        }
        playerCx = 0;
        playerCz = 0;
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(STREAM_INTERVAL_MS);
                    if (!sendNearestMissingChunk(session)) forgetDistantChunks();
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    log.warning("chunk stream failed: " + e);
                }
            }
        }, "mc3ds-chunkstream");
        t.setDaemon(true);
        t.start();
        log.info("chunk streaming on: radius " + STREAM_RADIUS + " around the player, one chunk"
                + " every " + STREAM_INTERVAL_MS + "ms (" + sentChunks.size() + " sent with the spawn)");
    }

    /**
     * Sends the one nearest chunk the player should have but doesn't, and reports whether it found
     * anything to do.
     *
     * <p>This is driven by where the player IS, not by what the Java server happens to push to the
     * bot. The old version listened for the server's chunk sends and clamped them to a fixed square
     * around the spawn, which made the world a 9x9 island: walk off the edge and there was nothing,
     * and walking back over ground the bot had already loaded produced no packets at all, because
     * the server does not re-send a chunk a player already has.
     *
     * <p>Nearest-first matters. Chunks cost ~14 KB each and arrive one at a time, so the order
     * decides whether the player is walking onto ground or into a hole.
     */
    private boolean sendNearestMissingChunk(RakNetSession session) {
        int pcx = playerCx, pcz = playerCz;
        int bestCx = 0, bestCz = 0, bestDist = Integer.MAX_VALUE;
        bridge.world.WorldModel.Chunk best = null;
        for (int cx = pcx - STREAM_RADIUS; cx <= pcx + STREAM_RADIUS; cx++) {
            for (int cz = pcz - STREAM_RADIUS; cz <= pcz + STREAM_RADIUS; cz++) {
                int dist = (cx - pcx) * (cx - pcx) + (cz - pcz) * (cz - pcz);
                if (dist >= bestDist || sentChunks.contains(key(cx, cz))) continue;
                bridge.world.WorldModel.Chunk c =
                        javaLink.world.getChunk(cx + javaOriginX, cz + javaOriginZ);
                if (c == null) continue;   // the bot has not been sent it yet; try again next tick
                best = c; bestCx = cx; bestCz = cz; bestDist = dist;
            }
        }
        if (best == null) return false;
        byte[] chunk = Mc3dsChunk.encode(best, bestCx, bestCz, JavaLink.Y_SHIFT);
        byte[] packet = McpeBatch.build(chunk);
        world.addChunk(chunk);
        sentChunks.add(key(bestCx, bestCz));
        session.sendReliable(packet);
        log.info("streamed chunk -> MC3DS (" + bestCx + "," + bestCz + "), "
                + Math.sqrt(bestDist) + " chunks from the player");
        return true;
    }

    /**
     * Forgets chunks the player has walked well away from, so they are served again on the way
     * back. The client unloads distant chunks itself; without this the bridge would remember having
     * sent them and the player would return to holes. Only runs when there is nothing to send, so
     * re-serving never competes with serving new ground.
     */
    private void forgetDistantChunks() {
        int pcx = playerCx, pcz = playerCz, limit = STREAM_RADIUS + STREAM_HYSTERESIS;
        sentChunks.removeIf(k -> {
            int cx = (int) (k >> 32), cz = (int) (long) k;
            return Math.abs(cx - pcx) > limit || Math.abs(cz - pcz) > limit;
        });
    }

    /** Packs a chunk coordinate pair; both halves must be recoverable (see forgetDistantChunks). */
    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /** Little-endian float at {@code off}. */
    private static float f32(byte[] p, int off) {
        if (off + 4 > p.length) return 0f;
        int b = (p[off] & 0xFF) | ((p[off + 1] & 0xFF) << 8)
                | ((p[off + 2] & 0xFF) << 16) | ((p[off + 3] & 0xFF) << 24);
        return Float.intBitsToFloat(b);
    }

    /** Offsets for the six block faces: 0=-Y 1=+Y 2=-Z 3=+Z 4=-X 5=+X. */
    private static final int[][] FACE_OFFSET = {
        {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}
    };

    /** Offset just past a BlockPos ({@code zigzag x | varint y | zigzag z}) at {@code off}. */
    private static int posEnd(byte[] p, int off) {
        int[] x = readVarint(p, off);
        if (x == null) return p.length;
        int[] y = readVarint(p, x[1]);
        if (y == null) return p.length;
        int[] z = readVarint(p, y[1]);
        return z == null ? p.length : z[1];
    }


    /** Reads {@code x varint | y u8 | z varint} at {@code off}; null if truncated. */
    private static int[] readPos(byte[] p, int off) {
        // BlockPos per the ROM writer 0x7da49c: zigzag(x) | varint(y) | zigzag(z)
        int[] x = readVarint(p, off);
        if (x == null) return null;
        int[] y = readVarint(p, x[1]);
        if (y == null) return null;
        int[] z = readVarint(p, y[1]);
        return z == null ? null : new int[] {World.zigzag(x[0]), y[0], World.zigzag(z[0])};
    }

    /** Returns {value, nextOffset} or null. */
    private static int[] readVarint(byte[] p, int off) {
        int v = 0, shift = 0;
        while (off < p.length) {
            int b = p[off++] & 0xFF;
            v |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return new int[] {v, off};
            shift += 7;
            if (shift > 28) break;
        }
        return null;
    }

    private static void writeVarint(java.io.ByteArrayOutputStream o, int v) {
        while (true) {
            int b = v & 0x7F;
            v >>>= 7;
            o.write(v != 0 ? (b | 0x80) : b);
            if (v == 0) return;
        }
    }

    /**
     * UpdateBlock (0x16): {@code 16 | x varint | y u8 | z varint | id u8 | flags u8}.
     * Layout taken from the one real host UpdateBlock in the realjoin capture,
     * {@code 16 8c01 40 14 03 20} = x=140 y=64 z=20 id=3(dirt) flags=0x20. Writing raw bytes
     * instead of varints happens to work only while x and z stay below 128.
     */
    /**
     * UpdateBlock (0x16). The trailing byte is MCPE's {@code flags << 4 | metadata}, so passing a
     * bare 0-15 sets the block variant with no flags. That packing is inferred from MCPE rather
     * than read out of the ROM; a wrong guess shows up as the wrong variant (wrong wood, wrong
     * wool colour), not as a protocol error.
     */
    private static byte[] updateBlock(int x, int y, int z, int id, int flags) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        o.write(0x16);
        writeVarint(o, (x << 1) ^ (x >> 31));   // zigzag, matching what the client sends
        o.write(y & 0xFF);
        writeVarint(o, (z << 1) ^ (z >> 31));
        o.write(id & 0xFF);
        o.write(flags & 0xFF);
        return o.toByteArray();
    }


    /** Zigzag varint, the encoding writer A (0x1bc620) uses: (v << 1) ^ (v >> 31), then LEB128. */
    private static void writeZigzag(java.io.ByteArrayOutputStream o, int v) {
        writeVarint(o, (v << 1) ^ (v >> 31));
    }

    /**
     * LevelEvent (0x1a): {@code 1a | zigzag eventId | f32 x,y,z | zigzag data}.
     * Layout read out of the ROM rather than a capture — LevelEventPacket's vtable serializer
     * (slot +1, 0x006E1624) writes field @+8 via the zigzag-varint writer, the 12-byte Vec3
     * @+0xc via the struct writer, then field @+0x18 via the zigzag-varint writer.
     * Event 2001 is MCPE's classic destroy-block particle event; that constant is a guess,
     * the framing is not.
     */
    private static byte[] levelEvent(int eventId, float x, float y, float z, int data) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        o.write(0x1a);
        writeZigzag(o, eventId);
        for (float f : new float[] {x, y, z}) {
            int bits = Float.floatToIntBits(f);
            o.write(bits & 0xFF); o.write((bits >>> 8) & 0xFF);
            o.write((bits >>> 16) & 0xFF); o.write((bits >>> 24) & 0xFF);
        }
        writeZigzag(o, data);
        return o.toByteArray();
    }

    /** Logs the first bytes of a client packet (hex + ascii) for live protocol observation. */
    private void logClient(byte[] p) {
        int n = Math.min(p.length, 48);
        StringBuilder hex = new StringBuilder();
        StringBuilder asc = new StringBuilder();
        for (int i = 0; i < n; i++) {
            hex.append(String.format("%02x", p[i]));
            char c = (char) (p[i] & 0xFF);
            asc.append(c >= 32 && c < 127 ? c : '.');
        }
        log.info(String.format("client pkt len=%d [%s] %s", p.length, hex, asc));
    }

    private static byte[] loadResource(String path) {
        try (InputStream in = SpawnProtocol.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("resource not found: " + path);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to load " + path, e);
        }
    }
}
