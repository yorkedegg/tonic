package bridge.tunnel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Arrays;

/**
 * Dependency-free round-trip check for the tunnel {@link Frame} codec.
 * Run with {@code gradle frameSelfTest}. Exits non-zero on failure.
 */
public final class FrameSelfTest {

    public static void main(String[] args) throws Exception {
        int failures = 0;

        // A representative DATA frame carrying a (pretend) RakNet datagram.
        byte[] payload = new byte[]{(byte) 0x84, 0x00, 0x00, 0x00, 0x0b, 0x02, (byte) 0xFE};
        failures += roundTrip(Frame.data(Frame.CLIENT, Frame.HOST, 13, payload));

        // Beacon frame (broadcast) with the M0-style "MC" app-data prefix.
        failures += roundTrip(Frame.beacon(new byte[]{'M', 'C', 0x03, 0x00}));

        // CONNECT_OK assigning the first client node id.
        failures += roundTrip(Frame.connectOk(Frame.CLIENT));

        // Empty payload edge case.
        failures += roundTrip(new Frame(Frame.Type.SCAN, Frame.CLIENT, Frame.HOST, 0, new byte[0]));

        if (failures == 0) {
            System.out.println("FrameSelfTest: PASS (all frames round-tripped)");
        } else {
            System.out.println("FrameSelfTest: FAIL (" + failures + " mismatches)");
            System.exit(1);
        }
    }

    private static int roundTrip(Frame f) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        f.write(new DataOutputStream(bos));
        Frame back = Frame.read(new DataInputStream(new ByteArrayInputStream(bos.toByteArray())));

        boolean ok = back != null
                && back.type() == f.type()
                && back.src() == f.src()
                && back.dst() == f.dst()
                && back.channel() == f.channel()
                && Arrays.equals(back.payload(), f.payload());
        if (!ok) {
            System.out.println("  mismatch for " + f.type() + ": got " + back);
        }
        return ok ? 0 : 1;
    }
}
