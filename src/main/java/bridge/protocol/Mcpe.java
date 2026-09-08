package bridge.protocol;

/**
 * MC3DS MCPE packet ids (empirical, from FINDINGS.md) and builders for the small
 * handshake packets the host sends. Byte layouts match the capture exactly.
 */
public final class Mcpe {

    private Mcpe() {}

    // client -> host
    public static final int CLIENT_HELLO = 0x40;                 // 1-byte hello after RakNet connect
    public static final int CLIENT_TO_SERVER_HANDSHAKE = 0x04;   // "ready to join" trigger
    public static final int RESOURCE_PACK_CLIENT_RESPONSE = 0x08;

    // host -> client
    public static final int PLAY_STATUS = 0x02;
    public static final int RESOURCE_PACKS_INFO = 0x06;
    public static final int RESOURCE_PACK_STACK = 0x07;
    public static final int START_GAME = 0x0b;
    public static final int FULL_CHUNK_DATA = 0x3a;

    // PlayStatus statuses (int32 BE payload)
    public static final int LOGIN_SUCCESS = 0;
    public static final int PLAYER_SPAWN = 3;

    /** PlayStatus: {@code 02 | int32be status}. Captured LOGIN_SUCCESS = 02 00 00 00 00. */
    public static byte[] playStatus(int status) {
        return new byte[]{(byte) PLAY_STATUS,
                (byte) (status >>> 24), (byte) (status >>> 16), (byte) (status >>> 8), (byte) status};
    }

    public static final int SET_TIME = 0x0a;

    /** SetTime: {@code 0a | varint(time)}. Captured as e.g. {@code 0a 82 60}. The real host sends
     *  a steady stream of these (and entity updates) during play; the bridge streams them to keep
     *  the client's world "live" after a chunk spawn. */
    public static byte[] setTime(int time) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        o.write(SET_TIME);
        while ((time & ~0x7F) != 0) { o.write((time & 0x7F) | 0x80); time >>>= 7; }
        o.write(time & 0x7F);
        return o.toByteArray();
    }

    /** ResourcePacksInfo, exact bytes as captured (no packs). */
    public static final byte[] RESOURCE_PACKS_INFO_PKT = {0x06, 0x00, 0x00, 0x00, 0x00, 0x00};

    /** ResourcePackStack, exact bytes as captured (no packs). */
    public static final byte[] RESOURCE_PACK_STACK_PKT = {0x07, 0x00, 0x00, 0x00};

    /**
     * The host's login-response: an UNCOMPRESSED 0xFE batch the real host sends immediately
     * after the client's login, before the client will send 0x04. Replayed verbatim from the
     * M0 capture (the trailing 16 bytes are a session value the client appears not to validate).
     */
    public static final byte[] LOGIN_RESPONSE =
            java.util.HexFormat.of().parseHex("fe0300103a61a111fd9b53464fda2cb847633103");
}
