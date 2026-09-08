package bridge.transport;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Minimal binary reader/writer for RakNet framing. RakNet mixes endianness: lengths and
 * the offline fields are big-endian, but datagram/message sequence numbers are 24-bit
 * little-endian. Both are provided explicitly so call sites read like the wire spec.
 */
public final class Buf {

    // ---- reading ----
    private final byte[] a;
    private int p;

    public Buf(byte[] a) { this.a = a; }

    public int pos() { return p; }
    public int remaining() { return a.length - p; }
    public boolean hasRemaining() { return p < a.length; }

    public int u8() { return a[p++] & 0xFF; }
    public int u16be() { return (u8() << 8) | u8(); }
    public int u24le() { return u8() | (u8() << 8) | (u8() << 16); }
    public long u32be() { return ((long) u8() << 24) | (u8() << 16) | (u8() << 8) | u8(); }
    public long u64be() { long v = 0; for (int i = 0; i < 8; i++) v = (v << 8) | u8(); return v; }
    public byte[] bytes(int n) { byte[] r = Arrays.copyOfRange(a, p, p + n); p += n; return r; }
    public byte[] rest() { return bytes(remaining()); }
    public void skip(int n) { p += n; }

    // ---- writing ----
    private final ByteArrayOutputStream o = new ByteArrayOutputStream();

    public Buf w8(int v) { o.write(v & 0xFF); return this; }
    public Buf w16be(int v) { o.write((v >>> 8) & 0xFF); o.write(v & 0xFF); return this; }
    public Buf w24le(int v) { o.write(v & 0xFF); o.write((v >>> 8) & 0xFF); o.write((v >>> 16) & 0xFF); return this; }
    public Buf w32be(long v) {
        o.write((int) ((v >>> 24) & 0xFF)); o.write((int) ((v >>> 16) & 0xFF));
        o.write((int) ((v >>> 8) & 0xFF)); o.write((int) (v & 0xFF)); return this;
    }
    public Buf w64be(long v) { for (int i = 56; i >= 0; i -= 8) o.write((int) ((v >>> i) & 0xFF)); return this; }
    public Buf wbytes(byte[] b) { o.write(b, 0, b.length); return this; }
    public byte[] out() { return o.toByteArray(); }
}
