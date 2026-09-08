package bridge.paper;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Builds a Floodgate handshake so a plain Java client is accepted by an online-mode server as an
 * offline "Bedrock" player — exactly what Geyser does. Format reverse-engineered from the running
 * Floodgate 2.2.5 jar (crypto/AesCipher, crypto/FloodgateCipher, util/BedrockData):
 *
 * <pre>
 *   cipher      = AES/GCM/NoPadding, 12-byte IV, 128-bit tag, 16-byte key (floodgate/key.pem)
 *   wire        = "^Floodgate^>" + base64(IV) + '!' + base64(ciphertext+tag)
 *   plaintext   = version \0 username \0 xuid \0 deviceOs \0 lang \0 uiProfile \0 inputMode \0 ip
 *                 \0 "null"(linkedPlayer) \0 fromProxy \0 subscribeId \0 verifyCode      (12 fields)
 *   handshake   = &lt;wire&gt; \0 &lt;realServerAddress&gt;   (Floodgate finds the item whose header matches)
 * </pre>
 */
public final class FloodgateAuth {
    private static final String HEADER = "^Floodgate^>";
    private static final SecureRandom RNG = new SecureRandom();

    private FloodgateAuth() {}

    /** The full handshake address string to put in the Java handshake packet. */
    public static String handshake(byte[] key, String username, String xuid, String realAddress) throws Exception {
        String data =
                "1.20.0" + '\0' +            // version (informational)
                username + '\0' +
                xuid + '\0' +
                7 + '\0' +                   // deviceOs (Windows)
                "en_US" + '\0' +
                0 + '\0' +                   // uiProfile (Classic)
                1 + '\0' +                   // inputMode (keyboard)
                "127.0.0.1" + '\0' +         // ip
                "null" + '\0' +              // linkedPlayer
                0 + '\0' +                   // fromProxy (direct connection)
                0 + '\0' +                   // subscribeId
                "0";                         // verifyCode (must be non-empty: split needs 12 parts)

        byte[] iv = new byte[12];
        RNG.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(data.getBytes(StandardCharsets.UTF_8));

        String wire = HEADER
                + new String(Base64.getEncoder().encode(iv), StandardCharsets.UTF_8)
                + '!'
                + new String(Base64.getEncoder().encode(ct), StandardCharsets.UTF_8);
        return wire + '\0' + realAddress;
    }

    /** A stable numeric xuid derived from a name, so each player gets a consistent Floodgate UUID. */
    public static String xuidFor(String username) {
        long h = 0;
        for (int i = 0; i < username.length(); i++) h = h * 31 + username.charAt(i);
        return Long.toString(Math.abs(h) | 1L);
    }

    // --- self-test: encrypt, then decrypt back with the same key, and confirm the plaintext ---
    public static void main(String[] a) throws Exception {
        byte[] key = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(a[0]));
        System.out.println("key bytes: " + key.length);
        String hs = handshake(key, "Tonic3DS", xuidFor("Tonic3DS"), "localhost");
        String wire = hs.substring(0, hs.indexOf('\0'));
        System.out.println("handshake wire (" + wire.length() + " chars): " + wire);
        // reverse it (mimic Floodgate decrypt) to prove the format is valid
        String body = wire.substring(HEADER.length());
        int bang = body.indexOf('!');
        byte[] iv = Base64.getDecoder().decode(body.substring(0, bang));
        byte[] ct = Base64.getDecoder().decode(body.substring(bang + 1));
        Cipher d = Cipher.getInstance("AES/GCM/NoPadding");
        d.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        String back = new String(d.doFinal(ct), StandardCharsets.UTF_8);
        System.out.println("round-trip fields (" + back.split("\0").length + "): " + back.replace('\0','|'));
        System.out.println(back.split("\0").length == 12 ? "OK: 12 fields, decrypt matches" : "BAD field count");
    }
}
