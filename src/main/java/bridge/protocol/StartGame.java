package bridge.protocol;

import java.io.ByteArrayOutputStream;

/**
 * StartGamePacket (0x0b) — parsed from the M0 capture and rebuildable field by field.
 *
 * <p>Replaying the captured StartGame verbatim was the root cause of four separate defects,
 * because every per-player field in it describes the <em>captured</em> session rather than ours:
 * the player inherited creative flight, spawned at the capture's height (0.5, 70, 4.5) regardless
 * of where the live terrain actually was, and took the runtime id 11 that the capture's other
 * packets were addressed to. Authoring the packet removes that whole class of bug.
 *
 * <p>Layout, verified by decoding the capture (the position floats decode exactly to the point the
 * player appeared at, and the runtime id to the entity the client reports in its own MovePlayer):
 * <pre>
 *   0b
 *   varlong entityUniqueId          // arbitrary; the capture's is a large negative value
 *   varint  entityRuntimeId         // the client's own entity id for the rest of the session
 *   varint  playerGamemode          // zigzag: 0 survival, 1 creative
 *   f32 x, f32 y, f32 z             // where the player appears
 *   f32 yaw, f32 pitch
 *   varint  seed, dimension, generator, worldGamemode, difficulty
 *   varint  spawnX, spawnY, spawnZ  // world spawn point (block coords)
 *   ... tail ...                    // world-level config: flags, gamerules, level id, world name
 * </pre>
 *
 * <p>The tail is copied from the capture verbatim. It carries no per-player state — gamerules,
 * the level id and the world name — so there is nothing to gain from decoding it further, and
 * keeping the captured bytes keeps a known-good region known-good.
 */
public final class StartGame {

    /** Byte offset where the fields above end and the world-config tail begins. */
    private static final int TAIL_OFFSET = 43;

    public long entityUniqueId;
    public int entityRuntimeId;
    public int gamemode;          // raw (zigzag-encoded) value as it goes on the wire
    public float x, y, z, yaw, pitch;
    /** The seed is an unsigned 32-bit value on the wire; a Java int would sign-extend it. */
    public long seed;
    public int dimension, generator, worldGamemode, difficulty;
    public int spawnX, spawnY, spawnZ;
    public byte[] tail;

    /** MCPE's player eye height. StartGame and MovePlayer both carry the eye, not the feet. */
    public static final float EYE_HEIGHT = 1.62f;

    /** Gamemode as written on the wire: zigzag, so survival is 0 and creative is 2. */
    public static final int SURVIVAL = 0;
    public static final int CREATIVE = 2;

    /** Parses a captured StartGame so its fields can be changed and the rest reused. */
    public static StartGame parse(byte[] p) {
        if (p.length < TAIL_OFFSET || (p[0] & 0xFF) != Mcpe.START_GAME) {
            throw new IllegalArgumentException("not a StartGame packet");
        }
        StartGame s = new StartGame();
        Cursor c = new Cursor(p, 1);
        s.entityUniqueId = c.varlong();
        s.entityRuntimeId = (int) c.varlong();
        s.gamemode = (int) c.varlong();
        s.x = c.f32(); s.y = c.f32(); s.z = c.f32();
        s.yaw = c.f32(); s.pitch = c.f32();
        s.seed = c.varlong();
        s.dimension = (int) c.varlong();
        s.generator = (int) c.varlong();
        s.worldGamemode = (int) c.varlong();
        s.difficulty = (int) c.varlong();
        s.spawnX = (int) c.varlong();
        s.spawnY = (int) c.varlong();
        s.spawnZ = (int) c.varlong();
        s.tail = java.util.Arrays.copyOfRange(p, c.i, p.length);
        return s;
    }

    public byte[] encode() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(Mcpe.START_GAME);
        varlong(o, entityUniqueId);
        varlong(o, entityRuntimeId);
        varlong(o, gamemode);
        f32(o, x); f32(o, y); f32(o, z);
        f32(o, yaw); f32(o, pitch);
        varlong(o, seed);
        varlong(o, dimension);
        varlong(o, generator);
        varlong(o, worldGamemode);
        varlong(o, difficulty);
        varlong(o, spawnX);
        varlong(o, spawnY);
        varlong(o, spawnZ);
        o.writeBytes(tail);
        return o.toByteArray();
    }

    /**
     * Puts the player standing on the block below {@code feetY}, in survival.
     *
     * <p>StartGame's y is the EYE position, not the feet — the same convention MovePlayer uses.
     * Passing the feet height buried the player by 1.62 blocks, which read as spawning one or two
     * blocks inside the ground depending on where the fraction fell relative to a block boundary;
     * the client then shoved them up out of the terrain and settled with the eye 1.62 above the
     * surface, which is how the offset was finally measured.
     */
    public StartGame placePlayer(float px, float feetY, float pz, int runtimeId) {
        float py = feetY;
        this.x = px; this.y = feetY + EYE_HEIGHT; this.z = pz;
        this.entityRuntimeId = runtimeId;
        this.gamemode = SURVIVAL;
        this.worldGamemode = SURVIVAL;
        // The world spawn point doubles as the respawn point; keep it under the player.
        this.spawnX = zigzag((int) px);
        this.spawnY = (int) py;
        this.spawnZ = zigzag((int) pz);
        return this;
    }

    /** Encodes a signed value the way the client reads zigzag varints. */
    public static int zigzag(int v) { return (v << 1) ^ (v >> 31); }

    private static void varlong(ByteArrayOutputStream o, long v) {
        while ((v & ~0x7FL) != 0) { o.write((int) (v & 0x7F) | 0x80); v >>>= 7; }
        o.write((int) (v & 0x7F));
    }

    private static void f32(ByteArrayOutputStream o, float f) {
        int b = Float.floatToIntBits(f);
        o.write(b); o.write(b >>> 8); o.write(b >>> 16); o.write(b >>> 24);
    }

    private static final class Cursor {
        final byte[] p; int i;
        Cursor(byte[] p, int i) { this.p = p; this.i = i; }
        long varlong() {
            long r = 0; int s = 0;
            while (true) {
                int b = p[i++] & 0xFF;
                r |= (long) (b & 0x7F) << s; s += 7;
                if ((b & 0x80) == 0) return r;
            }
        }
        float f32() {
            int b = (p[i] & 0xFF) | ((p[i + 1] & 0xFF) << 8) | ((p[i + 2] & 0xFF) << 16) | (p[i + 3] << 24);
            i += 4;
            return Float.intBitsToFloat(b);
        }
    }
}
