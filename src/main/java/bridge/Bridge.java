package bridge;

import bridge.tunnel.Tunnel;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.logging.Logger;

/**
 * MC3DS &lt;-&gt; Java bridge.
 *
 * <p>M1 milestone (fake host, no Java leg yet): listen for the Azahar/3GX shim on the
 * tunnel port; each shim connection is one 3DS. The next increment hands the connection
 * to an Mc3dsSession that runs the RakNet v8 transport and replays the captured spawn
 * sequence so the client spawns into a bridge-served static flat world.
 *
 * <p>Right now the loop just logs frames, which verifies the tunnel + shim wiring end to
 * end before the transport/protocol layers land.
 */
public final class Bridge {

    private static final Logger log = Logger.getLogger("bridge");
    public static final int TUNNEL_PORT = 7777;

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : TUNNEL_PORT;
        log.info("MC3DS bridge (M1 fake host) listening for shim on tunnel port " + port);
        // Connect the Java link now, off to one side. It used to happen inside the first session's
        // constructor, on the thread that answers the 3DS's scans — and the shim gives us one
        // second per scan before reporting no servers found.
        if ("1".equals(System.getenv("MC3DS_JAVA"))) bridge.protocol.JavaLink.warmUp();

        try (ServerSocket server = new ServerSocket(port)) {
            for (int n = 1; ; n++) {
                Socket sock = server.accept();
                Tunnel tunnel = new Tunnel(sock);
                log.info("shim connected: " + tunnel.peer() + " (client " + n + ")");
                // One thread per client. Running the session inline meant a second client's TCP
                // handshake completed into the listen backlog and was then never serviced — so the
                // console reported CONNECTED while the bridge logged nothing, and both were true.
                // Now the emulator and the console can be up at once, which hardware testing needs.
                Thread t = new Thread(() -> {
                    try (tunnel) {
                        new Mc3dsSession(tunnel).run();
                        log.info("session ended cleanly: " + tunnel.peer());
                    } catch (Exception e) {
                        log.warning("session ended: " + tunnel.peer() + ": " + e);
                    }
                }, "mc3ds-session-" + n);
                t.setDaemon(true);
                t.start();
            }
        }
    }
}
