package bridge;

import bridge.tunnel.Frame;
import bridge.tunnel.Tunnel;
import bridge.transport.RakNetSession;
import bridge.protocol.SpawnProtocol;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HexFormat;
import java.util.logging.Logger;

/**
 * One MC3DS client session over the tunnel. Routes tunnel control frames (scan/connect)
 * and hands DATA payloads to the {@link RakNetSession}; RakNet's outbound datagrams are
 * wrapped back into DATA frames. Implements {@link RakNetSession.Handler} — for now the
 * game layer just logs; the next increment replays the captured spawn sequence here so the
 * client spawns into a bridge-served static world.
 */
public final class Mc3dsSession {

    private static final Logger log = Logger.getLogger("bridge.session");

    /**
     * Beacon application-data, as captured in M0. This is what the 3DS's join list shows: the host
     * player's name and the world's name, each a 32-byte null-padded field, then the player counts.
     *
     * <p>Bytes 0x04-0x05 are a checksum the client validates; a beacon whose value does not match
     * its contents is dropped from the join list silently. It is a one's-complement sum of the
     * little-endian 16-bit words of the whole 88 bytes (with the field itself as zero), carries
     * folded back in, plus 0xBCB0:
     *
     * <pre>
     *   sum  = Σ words[i]  (i ≠ 2)          fold: while (sum >> 16) sum = (sum & 0xFFFF) + (sum >> 16)
     *   field = (sum + 0xBCB0) & 0xFFFF
     * </pre>
     *
     * Solved from four captured host beacons and then confirmed by prediction: a name pair no real
     * host had ever broadcast ("Tonic" / "JavaLand"), with the field computed by this formula, was
     * listed by the client on the first try. The fold is what matters — a 44-word sum overflows
     * 16 bits, and mod 65536 fits nothing while mod 65535 fits everything. The 0xBCB0 offset is
     * unexplained; most likely the game sums an internal struct longer than the 88 bytes it sends
     * and the extra folds into a constant. It is the same on both ends, so it does not matter here.
     *
     * <p>The template is a real host's beacon ("Tonic" / "Tonic v1.0"); only the names and the
     * checksum are rewritten. MC3DS_HOST_NAME and MC3DS_WORLD_NAME set them. MC3DS_BEACON_HEX still
     * replaces the whole thing with a capture, for experiments.
     */
    private static final byte[] BEACON_APPDATA = beacon(
            System.getenv().getOrDefault("MC3DS_HOST_NAME", "Tonic"),
            System.getenv().getOrDefault("MC3DS_WORLD_NAME", "Tonic v1.0"));

    private static int envInt(String name, int def) {
        try { return Integer.decode(System.getenv().getOrDefault(name, String.valueOf(def))); }
        catch (NumberFormatException e) { return def; }
    }

    /** The one's-complement checksum the client checks bytes 0x04-0x05 against. */
    static int checksum(byte[] appdata) {
        long sum = 0;
        for (int i = 0; i + 1 < appdata.length; i += 2) {
            if (i == CHECKSUM_OFF) continue;                       // the field counts as zero
            sum += (appdata[i] & 0xFF) | ((appdata[i + 1] & 0xFF) << 8);
        }
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (int) ((sum + CHECKSUM_BIAS) & 0xFFFF);
    }

    private static final int CHECKSUM_OFF = 0x04, CHECKSUM_BIAS = 0xBCB0;

    /** A captured host beacon, used as the template for everything but the names and checksum. */
    private static final String TEMPLATE = "4d430300aa3a73005e11efcd2473d3b1546f6e6963000000000000000000000000000000000000000000000000000000546f6e69632076312e30000000000000000000000000000000000000000000000102000000000000";

    static byte[] beacon(String hostName, String worldName) {
        String override = System.getenv("MC3DS_BEACON_HEX");
        if (override != null && !override.isBlank()) {
            byte[] o = HexFormat.of().parseHex(override.trim());
            log.info("beacon: " + o.length + " bytes from MC3DS_BEACON_HEX");
            return o;
        }
        byte[] b = HexFormat.of().parseHex(TEMPLATE);
        writeName(b, HOST_NAME_OFF, hostName);
        writeName(b, WORLD_NAME_OFF, worldName);
        // Player counts, shown as "cur/max" in the join list. Both bytes are honoured as sent —
        // 3/8 displayed with the UDS network still advertising two nodes, so the client does not
        // clamp to that. Max defaults to the Java server's capacity rather than the capture's 2.
        b[0x50] = (byte) envInt("MC3DS_PLAYERS", 1);
        b[0x51] = (byte) envInt("MC3DS_MAX_PLAYERS", 8);
        // 0x52 is still unexplained (01 for "My World", 00 for "Tonic v1.0"). Left as captured.
        b[0x52] = (byte) envInt("MC3DS_BEACON_52", b[0x52]);
        int c = checksum(b);
        b[CHECKSUM_OFF] = (byte) c;
        b[CHECKSUM_OFF + 1] = (byte) (c >>> 8);
        log.info(String.format("beacon: host \"%s\", world \"%s\", checksum 0x%04X",
                hostName, worldName, c));
        return b;
    }

    /** Offsets and sizes of the two name fields inside the beacon's application data. */
    private static final int HOST_NAME_OFF = 0x10, WORLD_NAME_OFF = 0x30, NAME_LEN = 32;

    /** Writes an ASCII name into a fixed zero-padded field, truncating to leave a terminator. */
    private static void writeName(byte[] b, int off, String name) {
        java.util.Arrays.fill(b, off, off + NAME_LEN, (byte) 0);
        // UTF-8: confirmed on the console. A lone 0xA7 blanked both names, the proper C2 A7 rendered
        // — so anything in the system font works in a name. What does NOT work is § colour codes:
        // the join-list menu draws the raw string and shows "§a" literally. That is the same
        // convention MCPE and Java use in chat, but this menu is native UI and ignores it.
        byte[] raw = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        System.arraycopy(raw, 0, b, off, Math.min(raw.length, NAME_LEN - 1));
    }


    private final Tunnel tunnel;
    private final bridge.javaclient.JavaClient injected;   // Paper plugin's world source; null = fixture/standalone
    private final java.util.function.Function<bridge.protocol.Login, bridge.javaclient.JavaClient> botFactory;  // name -> bot
    private RakNetSession raknet;  // reset on each CONNECT
    private int lastChannel = 13; // M0: game traffic rides UDS data channel 13

    public Mc3dsSession(Tunnel tunnel) { this(tunnel, null, null); }

    public Mc3dsSession(Tunnel tunnel, bridge.javaclient.JavaClient injected) { this(tunnel, injected, null); }

    /** Paper plugin, one bot per 3DS: the factory connects a bot named after the 3DS player. */
    public Mc3dsSession(Tunnel tunnel, java.util.function.Function<bridge.protocol.Login, bridge.javaclient.JavaClient> botFactory) {
        this(tunnel, null, botFactory);
    }

    private Mc3dsSession(Tunnel tunnel, bridge.javaclient.JavaClient injected,
                         java.util.function.Function<bridge.protocol.Login, bridge.javaclient.JavaClient> botFactory) {
        this.tunnel = tunnel;
        this.injected = injected;
        this.botFactory = botFactory;
        this.raknet = new RakNetSession(this::sendDatagram, newProtocol());
    }

    private SpawnProtocol newProtocol() {
        return botFactory != null ? new SpawnProtocol(botFactory) : new SpawnProtocol(injected);
    }

    public void run() throws IOException {
        for (Frame f = tunnel.read(); f != null; f = tunnel.read()) {
            switch (f.type()) {
                case SCAN -> {
                    log.info("SCAN -> beacon");
                    tunnel.write(Frame.beacon(BEACON_APPDATA));
                }
                case CONNECT -> {
                    log.info("CONNECT -> connect-ok, node " + Frame.CLIENT + " (fresh session)");
                    // Each connection attempt gets a clean RakNet + protocol state, so a
                    // reconnect over the same tunnel doesn't inherit stale sequence numbers.
                    raknet = new RakNetSession(this::sendDatagram, newProtocol());
                    tunnel.write(Frame.connectOk(Frame.CLIENT));
                }
                case DATA -> {
                    lastChannel = f.channel();
                    raknet.onRaknet(f.payload());
                }
                case DISCONNECT -> {
                    log.info("client disconnected");
                    return;
                }
                default -> log.warning("unexpected inbound frame: " + f.type());
            }
        }
    }

    private void sendDatagram(byte[] datagram) {
        try {
            tunnel.write(Frame.data(Frame.HOST, Frame.CLIENT, lastChannel, datagram));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
