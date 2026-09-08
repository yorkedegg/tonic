package bridge.javaclient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.function.BiConsumer;
import java.util.logging.Logger;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import bridge.world.WorldModel;
import bridge.world.ChunkParser;

/**
 * Minimal Java-Edition 1.12.2 (protocol 340) client for the bridge's Java leg. Connects to
 * ViaProxy (which translates a modern server down to 1.12.2 / id&lt;&lt;4|meta blocks), logs in
 * offline (no encryption), stays alive (keep-alive + teleport-confirm), and surfaces Join Game
 * and Chunk Data. Hand-rolled to avoid an old MCProtocolLib artifact and to control chunk
 * parsing for the MC3DS translation.
 *
 * <p>M2: connects, stays alive (keep-alive + teleport-confirm + Client Settings), and parses
 * received Chunk Data into {@link WorldModel} as {@code id<<4|meta}. M3 will feed that world
 * back out through the MC3DS legs.
 */
public class JavaClient {   // non-final: BukkitJavaClient subclasses it for the Paper plugin

    private static final Logger log = Logger.getLogger("bridge.java");
    private static final int PROTOCOL_1_12_2 = 340;

    private final String host;
    private final int port;
    private final String username;
    /** Called for each received chunk: (chunkX, chunkZ). Wired to the world model later. */
    private BiConsumer<Integer, Integer> onChunk = (x, z) -> {};

    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private int compressionThreshold = -1; // -1 = no compression
    private volatile boolean play = false;
    private int chunkCount = 0;
    private int dimension = 0;
    public final WorldModel world = new WorldModel();

    /** Our position on the Java server, from the last Player Position And Look. */
    public volatile double posX, posY, posZ;
    public volatile String handshakeAddress;   // Floodgate handshake blob; null = plain host
    public volatile boolean spawned;
    public volatile boolean flying;             // creative flight: puppet moves are accepted freely
    /** Plugin mode: {x,y,z,yaw,pitch} for each relayed move. When set, the plugin teleports the
     *  bot's real server player itself (authoritative — no collision or anti-move), and the bot
     *  sends no client movement packet, so the two never fight. */
    public volatile java.util.function.Consumer<double[]> onMove;
    /** Plugin mode: {x,y,z} of a block the 3DS broke, Java coords. The plugin breaks it through the
     *  Bukkit API — which spawn protection cannot refuse, and which drops the block, unlike a
     *  creative client dig — instead of the bot sending a dig packet. */
    public volatile java.util.function.Consumer<int[]> onDig;
    /** Plugin mode: {x,y,z,id,meta} — the cell to fill and the legacy block to put there. */
    public volatile java.util.function.Consumer<int[]> onPlace;
    /** A dropped item stack on the server, as the plugin sees it spawn (Bukkit ItemSpawnEvent). */
    public static final class ItemDrop {
        public final int entityId, itemId, count, damage;
        public final double x, y, z;
        public ItemDrop(int entityId, int itemId, int count, int damage, double x, double y, double z) {
            this.entityId = entityId; this.itemId = itemId; this.count = count; this.damage = damage;
            this.x = x; this.y = y; this.z = z;
        }
    }
    /** Plugin mode: a drop appeared near the bot. SpawnProtocol shows it on the 3DS. */
    public volatile java.util.function.Consumer<ItemDrop> onItemAdd = d -> {};
    /** A drop's server entity id is gone (collected, despawned). SpawnProtocol removes it from the 3DS. */
    public volatile java.util.function.IntConsumer onItemGone = e -> {};
    /** The built-in 3DS skin id of the console piloting THIS bot ("Standard_Steve", …). */
    public volatile String skinId;
    /** Plugin mode: server player name -> the 3DS skin id of the console piloting it, or null. */
    public volatile java.util.function.Function<String, String> skinFor = n -> null;

    /**
     * Walks our Java-side avatar to a position so the server streams chunks around it. This is
     * what makes the 3DS player's movement load real terrain instead of a fixed snapshot.
     * Serverbound Player Position (0x0D in 1.12.2): x, feetY, z as doubles + onGround.
     */
    public synchronized void sendPosition(double x, double y, double z) {
        try {
            double jump = Math.sqrt(Math.pow(x - posX, 2) + Math.pow(y - posY, 2) + Math.pow(z - posZ, 2));
            if (jump > MAX_STEP) {
                log.warning(String.format("bot jump of %.1f blocks to (%.1f,%.1f,%.1f) — the server"
                        + " will reject this and rubber-band", jump, x, y, z));
            }
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            writeVarInt(o, 0x0D);
            writeDouble(o, x);
            writeDouble(o, y);
            writeDouble(o, z);
            o.write(1);                      // onGround
            sendPacket(o.toByteArray());
            posX = x; posY = y; posZ = z;
        } catch (Exception e) {
            log.warning("sendPosition failed: " + e);
        }
    }

    /**
     * 1.12.2 packs a block position into one long: 26 bits x, 12 bits y, 26 bits z.
     * (1.14 moved y to the low bits; ViaProxy speaks 1.12.2 to us, so this is the right layout.)
     */
    private static long blockPos(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }

    /**
     * Serverbound Player Digging (0x14). Sent as start-then-finish: in survival the server times
     * the dig, so a lone "finish" is rejected for anything that isn't instantly breakable.
     * The face is the side that was hit, which the server uses to reject impossible clicks.
     */
    public synchronized void sendDig(int x, int y, int z, int face) {
        try {
            // The server enforces a reach limit (~4.5 blocks) and silently ignores anything
            // further, so log the distance: a rejected dig looks exactly like a successful one
            // from here, since the server does not echo a block change to the player who broke it.
            stepWithinReach(x, y, z);
            log.info(String.format("dig java(%d,%d,%d), bot at (%.1f,%.1f,%.1f)",
                    x, y, z, posX, posY, posZ));
            sendSwing();
            java.util.function.Consumer<int[]> d = onDig;
            if (d != null) {   // plugin mode: the server breaks it; a client dig inside spawn protection is ignored
                d.accept(new int[] {x, y, z});
                return;
            }
            for (int status : new int[] {0, 2}) {   // 0 = start digging, 2 = finish digging
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                writeVarInt(o, 0x14);
                writeVarInt(o, status);
                writeLong(o, blockPos(x, y, z));
                o.write(face);
                sendPacket(o.toByteArray());
            }
        } catch (Exception e) {
            log.warning("sendDig failed: " + e);
        }
    }

    /**
     * The 3DS reaches further than the Java server allows, and the bot only moves when the player
     * sends MovePlayer — so a break at the edge of the 3DS's reach lands outside the server's, and
     * the server drops it silently: no block change comes back, no item drops, and the 3DS is left
     * showing a hole that does not exist. Stepping the bot next to the block first makes the
     * interaction legal. The next relayed MovePlayer puts the bot back under the player.
     */
    private void stepWithinReach(int x, int y, int z) {
        double tx = x + 0.5, ty = y + 0.5, tz = z + 0.5;
        double d = Math.sqrt(Math.pow(tx - posX, 2) + Math.pow(ty - posY, 2) + Math.pow(tz - posZ, 2));
        if (d <= REACH) return;
        // Do NOT move. The bridge now stands the bot exactly where the player is before every
        // interaction, so anything the 3DS lets you click is already in reach; walking the bot to
        // the block on top of that only made it visibly teleport and put it somewhere the player
        // is not. If this still fires, the interesting question is why the sync did not cover it,
        // and moving the bot would hide that.
        log.warning(String.format("block(%d,%d,%d) is %.1f blocks from the bot at (%.1f,%.1f,%.1f)"
                + " — the server may refuse this", x, y, z, d, posX, posY, posZ));
    }

    /** The server's block-interaction limit; anything beyond it is ignored without a reply. */
    private static final double REACH = 6.0;

    /** Vanilla rejects a single move longer than this and teleports the player back. */
    private static final double MAX_STEP = 8.0;


    /**
     * Serverbound Player Block Placement (0x1F): the position and face of the block that was
     * CLICKED, not the cell being filled — the server derives that itself.
     */
    public synchronized void sendPlace(int x, int y, int z, int face) {
        try {
            stepWithinReach(x, y, z);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            writeVarInt(o, 0x1F);
            writeLong(o, blockPos(x, y, z));
            writeVarInt(o, face);
            writeVarInt(o, 0);              // hand: main
            for (float c : new float[] {0.5f, 0.5f, 0.5f}) writeInt(o, Float.floatToIntBits(c));
            sendPacket(o.toByteArray());
            sendSwing();
        } catch (Exception e) {
            log.warning("sendPlace failed: " + e);
        }
    }

    /** Serverbound Animation (0x1D): swings the arm, which some anti-cheat plugins expect. */
    private void sendSwing() throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        writeVarInt(o, 0x1D);
        writeVarInt(o, 0);                  // hand: main
        sendPacket(o.toByteArray());
    }

    /** Serverbound Chat Message (0x02). With the bot opped this is how the bridge runs commands. */
    public synchronized void sendChat(String message) {
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            writeVarInt(o, 0x02);
            writeString(o, message.length() > 256 ? message.substring(0, 256) : message);
            sendPacket(o.toByteArray());
            log.info("sent to server: " + message);
        } catch (Exception e) {
            log.warning("sendChat failed: " + e);
        }
    }

    /**
     * Serverbound Creative Inventory Action (0x1B): puts an item straight into a slot. This is how
     * a creative client arms itself — without it the bot's hands are empty and every placement the
     * 3DS makes is refused, because the server places what the player is holding, not what the
     * client claims.
     */
    public synchronized void sendCreativeSet(int slot, int itemId, int count, int damage) {
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            writeVarInt(o, 0x1B);
            o.write(slot >>> 8); o.write(slot);
            if (itemId <= 0) {
                o.write(0xFF); o.write(0xFF);          // empty slot
            } else {
                o.write(itemId >>> 8); o.write(itemId);
                o.write(count);
                o.write(damage >>> 8); o.write(damage);
                o.write(0);                            // no NBT
            }
            sendPacket(o.toByteArray());
        } catch (Exception e) {
            log.warning("sendCreativeSet failed: " + e);
        }
    }

    /** Serverbound Held Item Change (0x1A): picks the hotbar slot placement will draw from. */
    public synchronized void sendHeldSlot(int slot) {
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            writeVarInt(o, 0x1A);
            o.write(slot >>> 8); o.write(slot);   // short, big-endian
            sendPacket(o.toByteArray());
        } catch (Exception e) {
            log.warning("sendHeldSlot failed: " + e);
        }
    }

    /**
     * Serverbound Player Position And Look (0x0E in 1.12.2). Carries where the bot is AND which way
     * it is facing, so its head turns with the 3DS player's instead of staring in one direction.
     * Yaw and pitch use the same convention on both sides (yaw 0 = +Z, pitch positive = down), and
     * the server normalises yaw, so the 3DS's unbounded accumulating value can go straight through.
     */
    public synchronized void sendPositionLook(double x, double y, double z, float yaw, float pitch) {
        try {
            posX = x; posY = y; posZ = z;
            java.util.function.Consumer<double[]> m = onMove;
            if (m != null) {   // plugin mode: the server teleports the player; don't also send a client move
                m.accept(new double[] {x, y, z, yaw, pitch});
                return;
            }
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            writeVarInt(o, 0x0E);
            writeDouble(o, x); writeDouble(o, y); writeDouble(o, z);
            writeInt(o, Float.floatToIntBits(yaw));
            writeInt(o, Float.floatToIntBits(pitch));
            o.write(flying ? 0 : 1);         // onGround: 0 while flying, or the server clears flight
            sendPacket(o.toByteArray());
            posX = x; posY = y; posZ = z;
        } catch (Exception e) {
            log.warning("sendPositionLook failed: " + e);
        }
    }

    private static void writeDouble(ByteArrayOutputStream o, double d) {
        writeLong(o, Double.doubleToLongBits(d));
    }

    private double readDouble(java.io.InputStream in) throws IOException {
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (in.read() & 0xFFL);
        return Double.longBitsToDouble(v);
    }

    public JavaClient(String host, int port, String username) {
        this.host = host;
        this.port = port;
        this.username = username;
    }

    public void onChunk(BiConsumer<Integer, Integer> cb) { this.onChunk = cb; }

    /** Close the socket so the bot leaves the server (readLoop then ends). */
    public void disconnect() {
        try { if (socket != null) socket.close(); } catch (Exception ignore) {}
    }

    public void connect() throws IOException {
        socket = new Socket(host, port);
        socket.setTcpNoDelay(true);
        in = new DataInputStream(socket.getInputStream());
        out = new DataOutputStream(socket.getOutputStream());

        // Handshake -> Login
        ByteArrayOutputStream hs = new ByteArrayOutputStream();
        writeVarInt(hs, 0x00);
        writeVarInt(hs, PROTOCOL_1_12_2);
        writeString(hs, handshakeAddress != null ? handshakeAddress : host);
        hs.write((port >> 8) & 0xFF);
        hs.write(port & 0xFF);
        writeVarInt(hs, 2); // next state: login
        sendRaw(hs.toByteArray());

        ByteArrayOutputStream ls = new ByteArrayOutputStream();
        writeVarInt(ls, 0x00);
        writeString(ls, username);
        sendRaw(ls.toByteArray());

        log.info("connecting to " + host + ":" + port + " as " + username + " (proto 1.12.2)");
        readLoop();
    }

    private void readLoop() throws IOException {
        while (true) {
            byte[] packet;
            try {
                packet = readPacket();
            } catch (EOFException eof) {
                log.warning("java server closed the connection");
                return;
            }
            ByteArrayInputStream bin = new ByteArrayInputStream(packet);
            int id = readVarInt(bin);
            if (!play) {
                handleLogin(id, bin);
            } else {
                handlePlay(id, bin);
            }
        }
    }

    private void handleLogin(int id, ByteArrayInputStream bin) throws IOException {
        switch (id) {
            case 0x03 -> { // Set Compression
                compressionThreshold = readVarInt(bin);
                log.info("compression threshold=" + compressionThreshold);
            }
            case 0x02 -> { // Login Success
                String uuid = readString(bin);
                String name = readString(bin);
                play = true;
                log.info("login success: " + name + " (" + uuid + ") -> PLAY");
            }
            case 0x00 -> log.warning("login disconnect: " + readString(bin));
            default -> { /* login plugin request etc. — ignore */ }
        }
    }

    private boolean dumped = false;

    private void handlePlay(int id, ByteArrayInputStream bin) throws IOException {
        switch (id) {
            case 0x23 -> { // Join Game
                int entityId = readInt(bin);
                int gamemode = bin.read();
                int dim = readInt(bin);
                this.dimension = dim;
                int difficulty = bin.read();
                int maxPlayers = bin.read();
                String levelType = readString(bin);
                log.info(String.format("JOIN GAME: entityId=%d gamemode=%d dim=%d level=%s",
                        entityId, gamemode, dim, levelType));
                // Client Settings (0x04): real clients send this early; view distance drives chunk sending.
                ByteArrayOutputStream cs = new ByteArrayOutputStream();
                writeVarInt(cs, 0x04);
                writeString(cs, "en_US");
                cs.write(8);         // view distance
                writeVarInt(cs, 0);  // chat mode: enabled
                cs.write(1);         // chat colors
                cs.write(0x7F);      // displayed skin parts
                writeVarInt(cs, 1);  // main hand: right
                sendPacket(cs.toByteArray());
            }
            case 0x20 -> { // Chunk Data
                int cx = readInt(bin);
                int cz = readInt(bin);
                boolean groundUp = bin.read() != 0;
                int bitmask = readVarInt(bin);
                int dataSize = readVarInt(bin);
                byte[] data = new byte[dataSize];
                new DataInputStream(bin).readFully(data);
                chunkCount++;
                if (chunkCount % 25 == 0) log.info("chunks received=" + chunkCount + " stored=" + world.chunkCount());
                if (groundUp) {
                    try {
                        WorldModel.Chunk c = ChunkParser.parse(cx, cz, data, bitmask, groundUp, dimension);
                        world.putChunk(c);
                        if (!dumped) { dumped = true; dumpColumn(c); }
                    } catch (Exception e) {
                        log.warning("chunk (" + cx + "," + cz + ") parse failed: " + e);
                    }
                }
                onChunk.accept(cx, cz);
            }
            case 0x1F -> { // Keep Alive (clientbound) -> must echo
                long k = readLong(bin);
                ByteArrayOutputStream ka = new ByteArrayOutputStream();
                writeVarInt(ka, 0x0B); // serverbound Keep Alive
                writeLong(ka, k);
                sendPacket(ka.toByteArray());
            }
            case 0x2F -> { // Player Position And Look -> Teleport Confirm so we "spawn"
                // x,y,z (double*3) + yaw,pitch (float*2) + flags(byte) + teleportId(varint)
                posX = readDouble(bin); posY = readDouble(bin); posZ = readDouble(bin);
                spawned = true;
                skip(bin, 4 * 2 + 1);
                int teleportId = readVarInt(bin);
                ByteArrayOutputStream tc = new ByteArrayOutputStream();
                writeVarInt(tc, 0x00); // serverbound Teleport Confirm
                writeVarInt(tc, teleportId);
                sendPacket(tc.toByteArray());
                log.info(String.format("server repositioned bot to (%.1f,%.1f,%.1f) confirm %d",
                        posX, posY, posZ, teleportId));
            }
            case 0x0B -> { // Block Change: the server's verdict on a break or a place
                long pos = readLong(bin);
                int state = readVarInt(bin);
                applyBlockChange((int) (pos >> 38), (int) ((pos >> 26) & 0xFFF),
                        (int) (pos << 38 >> 38), state);
            }
            case 0x10 -> { // Multi Block Change: several cells in one chunk
                int cx = readInt(bin), cz = readInt(bin);
                int n = readVarInt(bin);
                for (int i = 0; i < n; i++) {
                    int horiz = bin.read();
                    int y = bin.read();
                    int state = readVarInt(bin);
                    applyBlockChange((cx << 4) + (horiz >> 4), y, (cz << 4) + (horiz & 15), state);
                }
            }
            case 0x00 -> { // Spawn Object: type 2 is a dropped item stack
                int eid = readVarInt(bin);
                skip(bin, 16);                       // UUID
                int type = bin.read();
                double ox = readDouble(bin), oy = readDouble(bin), oz = readDouble(bin);
                if (type == 2) {
                    log.info(String.format("server dropped an item: entity %d at (%.1f,%.1f,%.1f)",
                            eid, ox, oy, oz));
                }
            }
            case 0x4B -> { // Collect Item: the bot picked a drop up
                int collected = readVarInt(bin), collector = readVarInt(bin), count = readVarInt(bin);
                log.info("collected item entity " + collected + " x" + count + " by " + collector);
                onItemGone.accept(collected);
            }
            case 0x14 -> { // Window Items: the whole inventory at once, incl. what the bot logged in with
                int window = bin.read();
                int n = (short) ((bin.read() << 8) | bin.read());
                if (window != 0) break;
                for (int slot = 0; slot < n; slot++) {
                    int[] stack = readSlot(bin);
                    if (slot < inventory.length) inventory[slot] = stack;
                }
                log.info("java window items: " + n + " slots, "
                        + java.util.Arrays.stream(inventory).filter(java.util.Objects::nonNull).count()
                        + " filled");
                try {
                    onInventory.run();
                } catch (Exception e) {
                    log.warning("inventory listener failed: " + e);
                }
            }
            case 0x16 -> { // Set Slot: how we learn the bot picked something up
                int window = bin.read();
                int slot = (short) ((bin.read() << 8) | bin.read());
                int itemId = (short) ((bin.read() << 8) | bin.read());
                int count = 0, damage = 0;
                if (itemId != -1) {
                    count = bin.read();
                    damage = (short) ((bin.read() << 8) | bin.read());
                    // NBT follows, but it is the last field of this packet so it can be ignored.
                }
                if (window == 0 && slot >= 0 && slot < inventory.length) {
                    inventory[slot] = itemId <= 0 || count <= 0
                            ? null : new int[] {itemId, count, damage};
                    log.info("java slot " + slot + " = item " + itemId + " x" + count + " dmg " + damage);
                    try {
                        onInventory.run();
                    } catch (Exception e) {
                        log.warning("inventory listener failed: " + e);
                    }
                }
            }
            case 0x2E -> { // Player List Item: where a player's NAME comes from
                int action = readVarInt(bin);
                int n = readVarInt(bin);
                for (int i = 0; i < n; i++) {
                    long hi = readLong(bin), lo = readLong(bin);
                    if (action != 0) break;            // only "add player" carries a name
                    String name = readString(bin);
                    int props = readVarInt(bin);
                    for (int k = 0; k < props; k++) {
                        readString(bin); readString(bin);
                        if (bin.read() != 0) readString(bin);
                    }
                    readVarInt(bin); readVarInt(bin);  // gamemode, ping
                    if (bin.read() != 0) readString(bin);
                    names.put(new java.util.UUID(hi, lo), name);
                }
            }
            case 0x05 -> { // Spawn Player
                int eid = readVarInt(bin);
                long hi = readLong(bin), lo = readLong(bin);
                double x = readDouble(bin), y = readDouble(bin), z = readDouble(bin);
                float yaw = angle(bin.read()), pitch = angle(bin.read());
                java.util.UUID who = new java.util.UUID(hi, lo);
                Player p = new Player(eid, who, names.getOrDefault(who, "player"), x, y, z, yaw, pitch);
                players.put(eid, p);
                log.info("player " + p.name + " spawned at (" + (int) x + "," + (int) y + "," + (int) z + ")");
                fire(p, false);
            }
            case 0x26, 0x27 -> { // Entity (Look And) Relative Move: deltas in 1/4096 of a block
                countEntityPacket(id);
                int eid = readVarInt(bin);
                double dx = readShort(bin) / 4096.0, dy = readShort(bin) / 4096.0,
                       dz = readShort(bin) / 4096.0;
                Player p = players.get(eid);
                if (p == null) break;
                p.x += dx; p.y += dy; p.z += dz;
                if (id == 0x27) { p.yaw = angle(bin.read()); p.pitch = angle(bin.read()); }
                fire(p, false);
            }
            case 0x28 -> { // Entity Look
                countEntityPacket(id);
                Player p = players.get(readVarInt(bin));
                if (p == null) break;
                p.yaw = angle(bin.read()); p.pitch = angle(bin.read());
                fire(p, false);
            }
            case 0x4C -> { // Entity Teleport
                countEntityPacket(id);
                int eid = readVarInt(bin);
                double x = readDouble(bin), y = readDouble(bin), z = readDouble(bin);
                Player p = players.get(eid);
                if (p == null) break;
                p.x = x; p.y = y; p.z = z;
                p.yaw = angle(bin.read()); p.pitch = angle(bin.read());
                fire(p, false);
            }
            case 0x32 -> { // Destroy Entities
                int n = readVarInt(bin);
                for (int i = 0; i < n; i++) {
                    int gone = readVarInt(bin);
                    Player p = players.remove(gone);
                    if (p != null) { log.info("player " + p.name + " despawned"); fire(p, true); }
                    else onItemGone.accept(gone);   // a drop despawned or someone else picked it up
                }
            }
            case 0x1A -> log.warning("play disconnect: " + readString(bin));
            default -> { /* ignore the rest for M2 step 1 */ }
        }
    }

    /**
     * Reads one 1.12.2 Slot: {@code short id}, and when that is not -1, {@code byte count},
     * {@code short damage} and an NBT tag. Returns {@code {id, count, damage}} or null when empty.
     */
    private int[] readSlot(ByteArrayInputStream in) throws IOException {
        int id = (short) ((in.read() << 8) | in.read());
        if (id == -1) return null;
        int count = in.read();
        int damage = (short) ((in.read() << 8) | in.read());
        skipNbt(in);
        return count <= 0 ? null : new int[] {id, count, damage};
    }

    /**
     * Skips one NBT tag. Window Items is an array of slots, so an item carrying NBT — anything
     * enchanted, named, or damaged with data — would otherwise desync the read and corrupt every
     * slot after it. A single 0x00 means the item has no NBT at all, which is the common case.
     */
    private void skipNbt(ByteArrayInputStream in) throws IOException {
        int type = in.read();
        if (type <= 0) return;              // TAG_End, or nothing left
        skip(in, ((in.read() << 8) | in.read()));   // the root tag's name
        skipNbtPayload(in, type);
    }

    private void skipNbtPayload(ByteArrayInputStream in, int type) throws IOException {
        switch (type) {
            case 1 -> skip(in, 1);
            case 2 -> skip(in, 2);
            case 3, 5 -> skip(in, 4);
            case 4, 6 -> skip(in, 8);
            case 7 -> skip(in, readInt(in));                    // byte array
            case 8 -> skip(in, (in.read() << 8) | in.read());   // string
            case 9 -> {                                          // list
                int elem = in.read();
                int len = readInt(in);
                for (int i = 0; i < len; i++) skipNbtPayload(in, elem);
            }
            case 10 -> {                                         // compound
                int t;
                while ((t = in.read()) > 0) {
                    skip(in, (in.read() << 8) | in.read());      // the entry's name
                    skipNbtPayload(in, t);
                }
            }
            case 11 -> skip(in, 4L * readInt(in));               // int array
            case 12 -> skip(in, 8L * readInt(in));               // long array
            default -> throw new IOException("unknown NBT tag type " + type);
        }
    }

    /**
     * Applies the server's authoritative block change to our model and tells whoever is listening,
     * so the 3DS can be shown what actually happened rather than what it predicted. A 1.12.2 block
     * state is `id << 4 | meta`, the same packing the model and MC3DS both use.
     */
    private void applyBlockChange(int x, int y, int z, int state) {
        log.info(String.format("server block change java(%d,%d,%d) -> id=%d meta=%d",
                x, y, z, state >> 4, state & 0xF));
        world.setBlock(x, y, z, (short) state);
        try {
            onBlockChange.accept(new int[] {x, y, z, state});
        } catch (Exception e) {
            log.warning("block-change listener failed: " + e);
        }
    }

    /** Another player in the world, as the server describes them. */
    public static final class Player {
        public final int entityId;
        public final java.util.UUID uuid;
        public final String name;
        public double x, y, z;
        public float yaw, pitch;
        public Player(int entityId, java.util.UUID uuid, String name,
               double x, double y, double z, float yaw, float pitch) {
            this.entityId = entityId; this.uuid = uuid; this.name = name;
            this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.pitch = pitch;
        }
    }

    public final java.util.Map<Integer, Player> players = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<java.util.UUID, String> names = new java.util.concurrent.ConcurrentHashMap<>();

    /** Called with (player, removed) whenever another player appears, moves or leaves. */
    public volatile java.util.function.BiConsumer<Player, Boolean> onPlayer = (p, gone) -> {};

    /** Counts the entity packets the server actually sends us, reported once a second. */
    private final java.util.Map<Integer, Integer> entityPacketCounts =
            new java.util.concurrent.ConcurrentHashMap<>();
    private long lastEntityLog;

    private void countEntityPacket(int id) {
        entityPacketCounts.merge(id, 1, Integer::sum);
        long now = System.currentTimeMillis();
        if (now - lastEntityLog > 1000) {
            lastEntityLog = now;
            log.info("entity packets so far: " + entityPacketCounts + "  tracked players: "
                    + players.size());
        }
    }

    private void fire(Player p, boolean gone) {
        try {
            onPlayer.accept(p, gone);
        } catch (Exception e) {
            log.warning("player listener failed: " + e);
        }
    }

    /** Minecraft sends rotations as a byte: a full turn is 256, not 360. */
    private static float angle(int b) { return (byte) b * 360f / 256f; }

    private static int readShort(InputStream s) throws IOException {
        return (short) ((s.read() << 8) | s.read());
    }

    /** Set by the bridge to receive {x, y, z, state} for every server-confirmed block change. */
    public volatile java.util.function.Consumer<int[]> onBlockChange = c -> {};

    /**
     * The bot's window-0 inventory as {@code {id, count, damage}} per slot, null when empty.
     * Java's layout: 0 crafting output, 1-4 crafting grid, 5-8 armour, 9-35 main, 36-44 hotbar,
     * 45 offhand.
     *
     * <p>Filled from Set Slot only. Window Items would give the whole inventory in one packet, but
     * its slots carry NBT and skipping that needs an NBT reader; a desync there would corrupt every
     * slot after it. Set Slot arrives for each individual change, including pickups, which is what
     * matters here — the cost is that an inventory the bot logs in already holding is invisible
     * until something in it changes.
     */
    public final int[][] inventory = new int[46][];

    /** Fired after a hotbar slot changes, so the bridge can push the inventory to the 3DS. */
    public volatile Runnable onInventory = () -> {};

    /** Verifies chunk parsing by dumping a surface column of the first received chunk. */
    private void dumpColumn(WorldModel.Chunk c) {
        int top = c.highestBlockY(8, 8);
        StringBuilder sb = new StringBuilder("PARSED chunk(" + c.x + "," + c.z + ") local(8,y,8): ");
        for (int y = Math.max(top - 4, 0); y <= Math.min(top + 1, 255); y++) {
            short st = c.get(8, y, 8);
            sb.append('y').append(y).append('=')
              .append(WorldModel.blockId(st)).append(':').append(WorldModel.blockMeta(st)).append(' ');
        }
        sb.append(" topY=").append(top);
        log.info(sb.toString());
    }

    // ---- packet framing ----

    /** Sends a packet (id+data already encoded) with pre-compression framing: [len][body]. */
    private void sendRaw(byte[] body) throws IOException {
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        writeVarInt(f, body.length);
        f.write(body);
        out.write(f.toByteArray());
        out.flush();
    }

    /** Sends a play packet honoring the negotiated compression threshold. */
    private void sendPacket(byte[] body) throws IOException {
        if (compressionThreshold < 0) { sendRaw(body); return; }
        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        if (body.length >= compressionThreshold) {
            writeVarInt(inner, body.length);   // uncompressed size
            inner.write(deflate(body));
        } else {
            writeVarInt(inner, 0);             // 0 = uncompressed
            inner.write(body);
        }
        writeVarInt(framed, inner.size());
        framed.write(inner.toByteArray());
        out.write(framed.toByteArray());
        out.flush();
    }

    /** Reads one packet, returning its decompressed [id+data] bytes. */
    private byte[] readPacket() throws IOException {
        int length = readVarInt(in);
        byte[] buf = new byte[length];
        in.readFully(buf);
        if (compressionThreshold < 0) return buf;
        ByteArrayInputStream bin = new ByteArrayInputStream(buf);
        int dataLength = readVarInt(bin);
        byte[] rest = bin.readAllBytes();
        if (dataLength == 0) return rest; // uncompressed
        return inflate(rest, dataLength);
    }

    // ---- primitives ----

    private static void writeVarInt(ByteArrayOutputStream o, int v) {
        while ((v & ~0x7F) != 0) { o.write((v & 0x7F) | 0x80); v >>>= 7; }
        o.write(v & 0x7F);
    }

    private static int readVarInt(InputStream s) throws IOException {
        int r = 0, pos = 0, b;
        do {
            b = s.read();
            if (b < 0) throw new EOFException();
            r |= (b & 0x7F) << pos;
            pos += 7;
        } while ((b & 0x80) != 0);
        return r;
    }

    private static void writeString(ByteArrayOutputStream o, String s) {
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeVarInt(o, b.length);
        o.write(b, 0, b.length);
    }

    private static String readString(InputStream s) throws IOException {
        int len = readVarInt(s);
        byte[] b = new byte[len];
        new DataInputStream(s).readFully(b);
        return new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static int readInt(InputStream s) throws IOException {
        return (s.read() << 24) | (s.read() << 16) | (s.read() << 8) | s.read();
    }

    private static long readLong(InputStream s) throws IOException {
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (s.read() & 0xFF);
        return v;
    }

    private static void writeInt(ByteArrayOutputStream o, int v) {
        o.write(v >>> 24); o.write(v >>> 16); o.write(v >>> 8); o.write(v);
    }

    private static void writeLong(ByteArrayOutputStream o, long v) {
        for (int i = 56; i >= 0; i -= 8) o.write((int) ((v >>> i) & 0xFF));
    }

    private static void skip(InputStream s, long n) throws IOException {
        for (long i = 0; i < n; i++) s.read();
    }

    private static byte[] deflate(byte[] data) {
        Deflater d = new Deflater();
        d.setInput(data); d.finish();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        while (!d.finished()) o.write(b, 0, d.deflate(b));
        d.end();
        return o.toByteArray();
    }

    private static byte[] inflate(byte[] data, int expected) throws IOException {
        Inflater inf = new Inflater();
        inf.setInput(data);
        byte[] out = new byte[expected];
        try {
            int off = 0;
            while (off < expected && !inf.finished()) {
                int n = inf.inflate(out, off, expected - off);
                if (n == 0) {
                    if (inf.finished() || inf.needsDictionary()) break;
                    if (inf.needsInput()) throw new IOException("truncated zlib from server");
                }
                off += n;
            }
            if (off != expected) throw new IOException("zlib size mismatch: got " + off + " expected " + expected);
        } catch (java.util.zip.DataFormatException e) {
            throw new IOException("bad zlib from server", e);
        } finally {
            inf.end();
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 25568;
        new JavaClient(host, port, "bridgebot").connect();
    }
}
