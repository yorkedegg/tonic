package bridge.transport;

/**
 * RakNet constants for the MC3DS dialect, as confirmed in M0 recon (see FINDINGS.md):
 * protocol version 8, MTU 0x05d4, no encryption. These are frozen in the MC3DS title, so
 * the bridge hard-codes them rather than negotiating.
 */
public final class RakNet {
    private RakNet() {}

    /** 16-byte offline connection magic. */
    public static final byte[] MAGIC = {
        0x00, (byte) 0xff, (byte) 0xff, 0x00, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe,
        (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, 0x12, 0x34, 0x56, 0x78
    };

    public static final int PROTOCOL_VERSION = 8;   // MC3DS-frozen (older than mainline PE 1.x)
    public static final int MTU = 0x05d4;           // 1492, from captured OpenConnectionReply2
    public static final long SERVER_GUID = 0x0BADC0DE_1B871710L; // arbitrary but stable

    // Offline (unconnected) message IDs.
    public static final int ID_OPEN_CONNECTION_REQUEST_1 = 0x05;
    public static final int ID_OPEN_CONNECTION_REPLY_1   = 0x06;
    public static final int ID_OPEN_CONNECTION_REQUEST_2 = 0x07;
    public static final int ID_OPEN_CONNECTION_REPLY_2   = 0x08;

    // Online (connected) message IDs.
    public static final int ID_CONNECTED_PING              = 0x00;
    public static final int ID_CONNECTED_PONG              = 0x03;
    public static final int ID_CONNECTION_REQUEST          = 0x09;
    public static final int ID_CONNECTION_REQUEST_ACCEPTED = 0x10;
    public static final int ID_NEW_INCOMING_CONNECTION     = 0x13;
    public static final int ID_DISCONNECTION_NOTIFICATION  = 0x15;

    // Datagram header flag bits (first byte of every online datagram).
    public static final int FLAG_VALID = 0x80;
    public static final int FLAG_ACK   = 0x40;
    public static final int FLAG_NAK   = 0x20;

    /** MCPE batch wrapper id (0xFE), carried as the payload of a reliable-ordered message. */
    public static final int ID_MCPE_BATCH = 0xFE;
}
