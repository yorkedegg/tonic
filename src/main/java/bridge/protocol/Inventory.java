package bridge.protocol;

import java.io.ByteArrayOutputStream;

/**
 * ContainerSetContent (0x34) — the packet that puts items in the player's hands.
 *
 * <p>Decoded from the M0 capture rather than guessed. The host sends five of these at join, one
 * per window; window 0 is the player inventory, 45 slots, with a trailing table that links the
 * nine hotbar positions to slots in it:
 *
 * <pre>
 *   34 | windowId u8 | entityUniqueId varlong | slotCount varint
 *      | slot x slotCount:  00                                      (empty)
 *                        |  zz(id) | zz(aux) | zz(9) | zz(0) | 00 00 00 00
 *      | hotbarCount varint | zz(slot + 9) x hotbarCount
 * </pre>
 *
 * <p>{@code aux} is MCPE's packed {@code damage << 8 | count}. That is what confirmed the layout:
 * it predicts the capture's creative list exactly, where stone brick appears four times with aux
 * {@code 02}, {@code 82 04}, {@code 82 08}, {@code 82 0c} — metas 0 to 3, all count 1.
 *
 * <p>The two constant fields written as {@code zz(9)} and {@code zz(0)} are what a real host sends
 * for every player-inventory slot. Their meaning is not established; they are reproduced rather
 * than interpreted.
 */
public final class Inventory {

    /** Window ids the host uses, from the capture. */
    public static final int WINDOW_PLAYER = 0x00;

    /** MC3DS's player inventory: 45 slots, the first nine of which the hotbar points at. */
    public static final int PLAYER_SLOTS = 45;
    public static final int HOTBAR_SLOTS = 9;

    /** The hotbar table stores slot + 9; the capture's nine entries are 9..17 for slots 0..8. */
    private static final int HOTBAR_LINK_BIAS = 9;

    private Inventory() {}

    /**
     * @param slots {@code {id, count, damage}} per slot, null or id 0 for empty. Only the first
     *              {@link #PLAYER_SLOTS} are sent.
     */
    public static byte[] setContent(long entityUniqueId, int windowId, int[][] slots) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0x34);
        o.write(windowId);
        varlong(o, entityUniqueId);
        writeVarint(o, PLAYER_SLOTS);
        for (int i = 0; i < PLAYER_SLOTS; i++) {
            int[] s = i < slots.length ? slots[i] : null;
            if (s == null || s[0] <= 0 || s[1] <= 0) { o.write(0); continue; }
            // Show something the 3DS can draw. Substituting is safe because the bridge relays the
            // player's hotbar slot rather than the item id, so the server still acts on the real
            // item — see Mc3dsItems.
            writeZigzag(o, Mc3dsItems.substitute(s[0]));
            writeZigzag(o, ((s[2] & 0xFFFF) << 8) | (s[1] & 0xFF));   // aux = damage<<8 | count
            writeZigzag(o, 9);
            writeZigzag(o, 0);
            o.write(0); o.write(0); o.write(0); o.write(0);
        }
        writeVarint(o, HOTBAR_SLOTS);
        for (int i = 0; i < HOTBAR_SLOTS; i++) writeZigzag(o, i + HOTBAR_LINK_BIAS);
        return o.toByteArray();
    }

    private static void writeZigzag(ByteArrayOutputStream o, int v) {
        writeVarint(o, (v << 1) ^ (v >> 31));
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
