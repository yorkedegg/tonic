package bridge.tunnel;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;

/**
 * One frame on the tunnel between the shim (Azahar patch, or the 3GX plugin on hardware)
 * and the bridge. The tunnel is deliberately dumb: TCP handles ordering, and the game's
 * own reliability layer (RakNet, confirmed in M0) rides inside {@link Type#DATA} payloads.
 *
 * <p>Wire layout (see design doc §2):
 * <pre>u16 length | u8 type | u8 src_node | u8 dst_node | u8 channel | payload…</pre>
 * {@code length} counts the four header bytes after it plus the payload.
 *
 * <p>Node IDs follow UDS conventions: host = 1, first client = 2, broadcast = 0xFF.
 */
public record Frame(Type type, int src, int dst, int channel, byte[] payload) {

    /** Frame types. Ordinal+1 is the on-wire type byte (0 is reserved / never sent). */
    public enum Type { SCAN, BEACON, CONNECT, CONNECT_OK, DATA, DISCONNECT }

    public static final int HOST = 1;
    public static final int CLIENT = 2;
    public static final int BROADCAST = 0xFF;

    public void write(DataOutputStream out) throws IOException {
        if (payload.length > 0xFFFF - 4) {
            throw new IOException("frame payload too large: " + payload.length);
        }
        out.writeShort(4 + payload.length);
        out.writeByte(type.ordinal() + 1);
        out.writeByte(src & 0xFF);
        out.writeByte(dst & 0xFF);
        out.writeByte(channel & 0xFF);
        out.write(payload);
        out.flush();
    }

    /** Reads one frame, or returns {@code null} on a clean end-of-stream. */
    public static Frame read(DataInputStream in) throws IOException {
        int len;
        try {
            len = in.readUnsignedShort();
        } catch (EOFException eof) {
            return null;
        }
        if (len < 4) {
            throw new IOException("short frame length: " + len);
        }
        int t = in.readUnsignedByte();
        int src = in.readUnsignedByte();
        int dst = in.readUnsignedByte();
        int ch = in.readUnsignedByte();
        byte[] payload = in.readNBytes(len - 4);
        if (payload.length != len - 4) {
            throw new EOFException("truncated frame payload");
        }
        if (t < 1 || t > Type.values().length) {
            throw new IOException("bad frame type byte: " + t);
        }
        return new Frame(Type.values()[t - 1], src, dst, ch, payload);
    }

    // --- convenience builders for the frames the bridge emits ---

    public static Frame beacon(byte[] appData) {
        return new Frame(Type.BEACON, HOST, BROADCAST, 0, appData);
    }

    public static Frame connectOk(int nodeId) {
        return new Frame(Type.CONNECT_OK, HOST, nodeId, 0, new byte[]{(byte) nodeId});
    }

    public static Frame data(int src, int dst, int channel, byte[] p) {
        return new Frame(Type.DATA, src, dst, channel, p);
    }
}
