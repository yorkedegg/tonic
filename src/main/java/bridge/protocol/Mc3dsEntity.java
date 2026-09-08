package bridge.protocol;

import java.io.ByteArrayOutputStream;

/**
 * The packets that put other people in the 3DS's world.
 *
 * <p>All three layouts are read out of captures, not guessed. AddPlayer comes from the M0 spawn
 * batch and MoveEntity from the live two-player session in {@code captures/realjoin-A-host.log},
 * which carries 124,432 of them.
 *
 * <pre>
 *   0c | uuid(16) | string name | varlong uniqueId | varint runtimeId
 *      | f32 x,y,z | f32 motion x,y,z | f32 pitch,yaw,headYaw
 *      | item stack (held) | metadata
 *
 *   12 | varint runtimeId | f32 x,y,z | u8 pitch | u8 yaw | u8 headYaw | u8 onGround | u8 teleport
 * </pre>
 *
 * <p>MoveEntity's rotations are byte angles — 256 to a turn, Minecraft's usual encoding — which the
 * capture shows plainly: a spinning entity's yaw byte walks f9, f2, eb, e4, dd, d6 while its
 * position never changes.
 */
public final class Mc3dsEntity {

    private Mc3dsEntity() {}

    /**
     * Everything after the rotation in the captured AddPlayer: the held-item stack and all 37
     * metadata entries. Reused verbatim, with only the nametag string swapped for the player we
     * are showing.
     *
     * <p>Building this by hand did not work. Three entries were written where the host sends 37 —
     * the ones whose meaning seemed established — and the client loaded the world and then crashed.
     * Reproducing a known-good tail and changing one field is the approach that worked for
     * StartGame, and it does not depend on understanding every entry.
     */
    private static final byte[] TAIL = loadTail();

    /** The metadata key holding an entity's nametag, a string. */
    private static final int META_NAMETAG = 4, TYPE_STRING = 4;

    private static byte[] loadTail() {
        try (java.io.InputStream in = Mc3dsEntity.class.getResourceAsStream("/addplayer-tail.bin")) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new ExceptionInInitializerError("addplayer-tail.bin missing: " + e);
        }
    }

    /**
     * PlayerList (0x3f), which is how the client learns a player's skin.
     *
     * <pre>
     *   3f | action u8 | count varint | [ uuid(16) | varlong uniqueId | string name | string skin ]*
     * </pre>
     *
     * <p>AddPlayer carries no skin, so the client looks one up by UUID in this list. Sending
     * AddPlayer without it crashed the console — not on receipt, but the moment the player turned
     * to look at the spawned entity, which is exactly what rendering a model whose skin was never
     * registered would do.
     *
     * <p>The capture only ever names a built-in skin: {@code Standard_Steve} or
     * {@code Standard_Alex}. No pixel data is sent.
     */
    public static byte[] playerList(byte[] uuid, long uniqueId, String name, String skin) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0x3F);
        o.write(0);                                   // action 0 = add
        writeVarint(o, 1);                            // one entry
        o.writeBytes(uuid);
        varlong(o, uniqueId);
        writeString(o, name);
        writeString(o, skin);
        return o.toByteArray();
    }

    public static final String SKIN_STEVE = "Standard_Steve";

    private static void writeString(ByteArrayOutputStream o, String s) {
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeVarint(o, b.length);
        o.writeBytes(b);
    }

    /** Spawns another player. {@code uuid} must be 16 bytes. */
    public static byte[] addPlayer(byte[] uuid, String name, long uniqueId, int runtimeId,
                                   float x, float y, float z, float yaw, float pitch) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0x0C);
        o.writeBytes(uuid);
        byte[] n = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeVarint(o, n.length);
        o.writeBytes(n);
        varlong(o, uniqueId);
        writeVarint(o, runtimeId);
        f32(o, x); f32(o, y); f32(o, z);
        f32(o, 0f); f32(o, 0f); f32(o, 0f);          // motion
        f32(o, pitch); f32(o, yaw); f32(o, yaw);      // the capture always has headYaw == yaw
        o.writeBytes(retagged(n));
        return o.toByteArray();
    }

    /**
     * The captured tail with the nametag replaced. Entries are copied byte for byte; only the one
     * string value is rewritten, so nothing depends on knowing what the other 36 mean.
     */
    private static byte[] retagged(byte[] name) {
        int[] at = {0};
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        skipStack(TAIL, at);
        o.writeBytes(java.util.Arrays.copyOfRange(TAIL, 0, at[0]));   // held item, unchanged
        int count = (int) varlongAt(TAIL, at);
        writeVarint(o, count);
        for (int i = 0; i < count; i++) {
            int start = at[0];
            int key = (int) varlongAt(TAIL, at);
            int type = (int) varlongAt(TAIL, at);
            int valueAt = at[0];
            skipValue(TAIL, at, type);
            if (key == META_NAMETAG && type == TYPE_STRING) {
                o.writeBytes(java.util.Arrays.copyOfRange(TAIL, start, valueAt));
                writeVarint(o, name.length);
                o.writeBytes(name);
            } else {
                o.writeBytes(java.util.Arrays.copyOfRange(TAIL, start, at[0]));
            }
        }
        return o.toByteArray();
    }

    /** An item stack: a lone zero for empty, else id, aux and two constants followed by 4 bytes. */
    private static void skipStack(byte[] p, int[] at) {
        if (varlongAt(p, at) == 0) return;
        varlongAt(p, at); varlongAt(p, at); varlongAt(p, at);
        at[0] += 4;
    }

    /** Metadata value sizes, confirmed by parsing the capture's 37 entries to the exact byte. */
    private static void skipValue(byte[] p, int[] at, int type) {
        switch (type) {
            case 0 -> at[0] += 1;                    // byte
            case 1 -> at[0] += 2;                    // short
            case 3 -> at[0] += 4;                    // float
            case 8 -> at[0] += 12;                   // vec3f
            case 2, 7 -> varlongAt(p, at);           // int, long
            // NB the length must be read into a local first. `at[0] += varlongAt(p, at)` looks
            // right and is not: Java evaluates the left side of a compound assignment before the
            // right, so the advance varlongAt makes gets overwritten and every string loses a byte.
            case 4 -> { int len = (int) varlongAt(p, at); at[0] += len; }
            case 6 -> { varlongAt(p, at); varlongAt(p, at); varlongAt(p, at); }  // block position
            default -> throw new IllegalStateException("unknown metadata type " + type);
        }
    }

    private static long varlongAt(byte[] p, int[] at) {
        long r = 0; int s = 0;
        while (true) {
            int b = p[at[0]++] & 0xFF;
            r |= (long) (b & 0x7F) << s; s += 7;
            if ((b & 0x80) == 0) return r;
        }
    }

    /** Moves an entity. Rotations are degrees here and converted to the wire's byte angles. */
    public static byte[] moveEntity(int runtimeId, float x, float y, float z,
                                    float yaw, float pitch, boolean onGround) {
        return moveEntity(runtimeId, x, y, z, yaw, pitch, onGround, true);
    }

    public static byte[] moveEntity(int runtimeId, float x, float y, float z,
                                    float yaw, float pitch, boolean onGround, boolean teleport) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0x12);
        writeVarint(o, runtimeId);
        f32(o, x); f32(o, y); f32(o, z);
        o.write(angle(pitch));
        o.write(angle(yaw));
        o.write(angle(yaw));
        o.write(onGround ? 1 : 0);
        o.write(teleport ? 1 : 0);
        return o.toByteArray();
    }

    /**
     * Removes an entity. No sample of this one appears in any capture with a body we could read,
     * so the single varlong is inferred from the shape of its neighbours rather than observed —
     * the one layout here that has not been confirmed against bytes.
     */
    public static byte[] removeEntity(long uniqueId) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0x0E);
        varlong(o, uniqueId);
        return o.toByteArray();
    }

    /**
     * A dropped item stack lying in the world. Not in any capture; the layout follows the family
     * the captured packets pin down. AddPlayer's varlong uniqueId + varint runtimeId and its
     * varint-counted metadata put this client on the MCPE 1.0 packet table (0b StartGame, 0c
     * AddPlayer, 0e RemoveEntity, 12 MoveEntity, 13 MovePlayer, 3f PlayerList all agree), where
     * AddItemEntity is 0x0f:
     *
     * <pre>
     *   0f | varlong uniqueId | varint runtimeId | item stack | f32 x,y,z | f32 motion x,y,z | metadata
     * </pre>
     *
     * The stack is the ContainerSetContent shape; metadata is sent empty (count 0).
     * MC3DS_ITEMS=0 in SpawnProtocol turns these off if the console objects.
     */
    public static byte[] addItemEntity(long uniqueId, int runtimeId, int itemId, int count, int damage,
                                       float x, float y, float z) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0x0F);
        varlong(o, uniqueId);
        writeVarint(o, runtimeId);
        int id = Mc3dsItems.substitute(itemId);
        if (id <= 0) {
            writeVarint(o, 0);                                            // empty stack
        } else {
            writeVarint(o, StartGame.zigzag(id));
            writeVarint(o, StartGame.zigzag(((damage & 0xFFFF) << 8) | (Math.max(count, 1) & 0xFF)));
            writeVarint(o, StartGame.zigzag(9));
            writeVarint(o, StartGame.zigzag(0));
            o.write(0); o.write(0); o.write(0); o.write(0);
        }
        f32(o, x); f32(o, y); f32(o, z);
        f32(o, 0f); f32(o, 0f); f32(o, 0f);                              // motion
        writeVarint(o, 0);                                                // no metadata entries
        return o.toByteArray();
    }

    /** An empty held-item stack, in the shape ContainerSetContent established. */
    private static void heldNothing(ByteArrayOutputStream o) {
        writeVarint(o, 0);
    }

    /** Minecraft's byte angle: a full turn is 256, not 360. */
    private static int angle(float degrees) {
        return (int) Math.floor(degrees * 256.0f / 360.0f) & 0xFF;
    }

    private static void f32(ByteArrayOutputStream o, float v) {
        int b = Float.floatToIntBits(v);
        o.write(b); o.write(b >>> 8); o.write(b >>> 16); o.write(b >>> 24);
    }

    private static void writeVarint(ByteArrayOutputStream o, int v) {
        while ((v & ~0x7F) != 0) { o.write((v & 0x7F) | 0x80); v >>>= 7; }
        o.write(v & 0x7F);
    }

    private static void varlong(ByteArrayOutputStream o, long v) {
        while ((v & ~0x7FL) != 0) { o.write((int) (v & 0x7F) | 0x80); v >>>= 7; }
        o.write((int) (v & 0x7F));
    }
}
