package bridge;

import java.util.HexFormat;

/**
 * Checks the beacon checksum against every host beacon ever captured, then checks that building a
 * beacon from names reproduces a captured one byte for byte. The formula was solved from these
 * samples and then confirmed by prediction on the console; this keeps it from regressing.
 */
public final class BeaconSelfTest {

    private static final String[][] CAPTURED = {
        {"azahard / My World / 2 players", "4d4303008e0373005e11efcd2473d3b1617a6168617264000000000000000000000000000000000000000000000000004d7920576f726c640000000000000000000000000000000000000000000000000202010000000000"},
        {"azahard / My World / 1 player",  "4d4303008d0373005e11efcd2473d3b1617a6168617264000000000000000000000000000000000000000000000000004d7920576f726c640000000000000000000000000000000000000000000000000102010000000000"},
        {"Tonic / My World / 1 player",    "4d4303002a8773005e11efcd2473d3b1546f6e69630000000000000000000000000000000000000000000000000000004d7920576f726c640000000000000000000000000000000000000000000000000102010000000000"},
        {"Tonic / Tonic v1.0 / 1 player",  "4d430300aa3a73005e11efcd2473d3b1546f6e6963000000000000000000000000000000000000000000000000000000546f6e69632076312e30000000000000000000000000000000000000000000000102000000000000"},
    };

    public static void main(String[] args) {
        int failures = 0;
        for (String[] c : CAPTURED) {
            byte[] b = HexFormat.of().parseHex(c[1]);
            int want = (b[4] & 0xFF) | ((b[5] & 0xFF) << 8);
            int got = Mc3dsSession.checksum(b);
            System.out.printf("  %-34s want 0x%04X got 0x%04X %s%n", c[0], want, got, want == got ? "OK" : "FAIL");
            if (want != got) failures++;
        }
        // Building from names must reproduce the template capture exactly.
        byte[] built = Mc3dsSession.beacon("Tonic", "Tonic v1.0");
        byte[] want = HexFormat.of().parseHex(CAPTURED[3][1]);
        boolean same = java.util.Arrays.equals(built, want);
        System.out.println("  beacon(\"Tonic\", \"Tonic v1.0\") == captured: " + (same ? "OK" : "FAIL"));
        if (!same) failures++;
        System.out.println(failures == 0 ? "PASS" : "FAIL (" + failures + ")");
        System.exit(failures == 0 ? 0 : 1);
    }
}
