package bridge.protocol;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * MCPE 0xFE batch codec (MC3DS dialect, from M0): {@code 0xFE | zlib( [varint len | packet]* )}.
 * zlib is the standard header-framed format (M0 tag "zlib"), which Java's Deflater/Inflater
 * produce and consume by default.
 */
public final class McpeBatch {

    public static final int ID = 0xFE;

    private McpeBatch() {}

    /** Wraps one or more MCPE packets into a single batch. */
    public static byte[] build(byte[]... packets) {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        for (byte[] p : packets) {
            writeVarint(raw, p.length);
            raw.writeBytes(p);
        }
        byte[] z = deflate(raw.toByteArray());
        ByteArrayOutputStream out = new ByteArrayOutputStream(z.length + 1);
        out.write(ID);
        out.writeBytes(z);
        return out.toByteArray();
    }

    /**
     * Splits a batch into its MCPE packets, tolerant of compression style. Host&lt;-&gt;client
     * batches are zlib (M0), but the MC3DS client-&gt;host batches are UNCOMPRESSED with a small
     * header, so: try zlib, then raw-deflate, else treat the body as uncompressed. Never throws.
     * A non-batch payload (no 0xFE) is returned as a single packet.
     */
    public static List<byte[]> inflate(byte[] payload) {
        if (payload.length == 0) return List.of();
        if ((payload[0] & 0xFF) != ID) return List.of(payload);
        byte[] body = java.util.Arrays.copyOfRange(payload, 1, payload.length);
        byte[] raw = tryInflate(body);
        if (raw == null) raw = body; // uncompressed batch
        List<byte[]> pkts = new ArrayList<>();
        int[] o = {0};
        while (o[0] < raw.length) {
            int start = o[0];
            int len = readVarint(raw, o);
            if (len <= 0 || o[0] + len > raw.length) { o[0] = start; break; }
            pkts.add(java.util.Arrays.copyOfRange(raw, o[0], o[0] + len));
            o[0] += len;
        }
        if (pkts.isEmpty()) pkts.add(raw); // fallback: hand the whole body to the caller
        return pkts;
    }

    private static byte[] deflate(byte[] data) {
        Deflater d = new Deflater(Deflater.DEFAULT_COMPRESSION);
        d.setInput(data);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        while (!d.finished()) out.write(buf, 0, d.deflate(buf));
        d.end();
        return out.toByteArray();
    }

    /** Tries zlib then raw-deflate; returns null (no throw) if the body isn't either. */
    private static byte[] tryInflate(byte[] body) {
        for (boolean nowrap : new boolean[]{false, true}) {
            Inflater inf = new Inflater(nowrap);
            inf.setInput(body);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            try {
                while (!inf.finished()) {
                    int n = inf.inflate(buf);
                    if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                    out.write(buf, 0, n);
                }
                if (out.size() > 0) return out.toByteArray();
            } catch (DataFormatException ignored) {
                // not this compression style; try the next
            } finally {
                inf.end();
            }
        }
        return null;
    }

    static void writeVarint(ByteArrayOutputStream o, int v) {
        while ((v & ~0x7F) != 0) { o.write((v & 0x7F) | 0x80); v >>>= 7; }
        o.write(v & 0x7F);
    }

    static int readVarint(byte[] b, int[] o) {
        int r = 0, s = 0, c;
        do { c = b[o[0]++] & 0xFF; r |= (c & 0x7F) << s; s += 7; } while ((c & 0x80) != 0);
        return r;
    }
}
