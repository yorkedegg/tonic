package bridge.tunnel;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;

/**
 * A framed TCP connection to one shim instance — i.e. one 3DS. Reads and writes
 * {@link Frame}s. Writes are synchronized because the bridge's world/transport listeners
 * fire from more than one thread (see design review note on serializing tunnel writes).
 */
public final class Tunnel implements AutoCloseable {

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;

    public Tunnel(Socket socket) throws IOException {
        this.socket = socket;
        // Movement packets are tiny and frequent; Nagle would batch them into ~40ms
        // hitches (design review, risk 6 neighbourhood). Disable it.
        socket.setTcpNoDelay(true);
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    }

    /** Reads the next frame, or {@code null} at clean end-of-stream. */
    public Frame read() throws IOException {
        return Frame.read(in);
    }

    public synchronized void write(Frame f) throws IOException {
        f.write(out);
    }

    public String peer() {
        return String.valueOf(socket.getRemoteSocketAddress());
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
