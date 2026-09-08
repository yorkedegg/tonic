package bridge.paper;

import bridge.tunnel.Tunnel;

import org.bukkit.Bukkit;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Paper/Spigot entry point. Opens the MC3DS bridge tunnel port inside the server process, so a 3DS
 * running the Tonic3DS plugin connects straight to this server (yourserver.com:PORT) and becomes a
 * REAL player on it — no external bot account, no ViaProxy.
 *
 * <p>One 3DS == one server player. When a 3DS's login arrives, {@link #connectBot} logs a Java
 * client into this same server under the 3DS player's own name, authenticated through Floodgate's
 * offline handshake (see {@link FloodgateAuth}). That bot receives the live world over normal Java
 * chunk packets; {@link bridge.protocol.SpawnProtocol} reads the bot's world model and serves it to
 * the 3DS, and relays the 3DS's digs/places/moves back through the bot — so other players see a
 * real, named, /tp-able player where the 3DS is standing. The bot is disconnected when the 3DS
 * leaves.
 */
public final class TonicJavaPlugin extends JavaPlugin implements org.bukkit.event.Listener {
    private volatile ServerSocket server;
    private Thread acceptThread;
    private volatile int serverPort = 25565;   // this server's own Java port; bots dial it on 127.0.0.1
    /** Live 3DS sessions: their bot and its real player, for routing server events to each 3DS. */
    private final java.util.Map<bridge.javaclient.JavaClient, Puppet> puppets =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Signs 3DS built-in skins for Java viewers; null (dormant) until config has a mineskin-key. */
    private volatile SkinSigner signer;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        serverPort = Bukkit.getPort();
        int port = getConfig().getInt("tunnel-port", 27953);
        try {
            server = new ServerSocket(port);
        } catch (IOException e) {
            getLogger().severe("could not open MC3DS tunnel port " + port + ": " + e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        acceptThread = new Thread(this::acceptLoop, "mc3ds-tunnel-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        getServer().getPluginManager().registerEvents(this, this);   // drops -> 3DS
        String key = getConfig().getString("mineskin-key", "");
        if (key != null && !key.isBlank()) {
            signer = new SkinSigner(getDataFolder().toPath().resolve("skins"), key.trim(),
                    getConfig().getString("mineskin-user-agent", "TonicJava/0.1 (3DS bridge)"), getLogger());
            getLogger().info("3DS skins for Java viewers: on (plugins/TonicJava/skins/index.tsv)");
        } else {
            getLogger().info("3DS skins for Java viewers: off (set mineskin-key in config.yml)");
        }
        getLogger().info("TonicJava listening on port " + port + "; bots dial 127.0.0.1:" + serverPort);
    }

    private void acceptLoop() {
        ServerSocket s = server;
        while (s != null && !s.isClosed()) {
            try {
                Socket sock = s.accept();
                getLogger().info("3DS connected: " + sock.getRemoteSocketAddress());
                Thread t = new Thread(() -> serve(sock), "mc3ds-session");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (server != null && !server.isClosed()) getLogger().warning("accept failed: " + e);
                return;
            }
        }
    }

    /** Per-session puppet: the bot's real server player and where the 3DS wants it each tick. */
    private static final class Puppet {
        volatile org.bukkit.entity.Player player;
        volatile double[] target;    // {x,y,z,yaw,pitch}
        volatile int taskId = -1;
        volatile String requestedName;   // the 3DS's name as it logged in (no "." prefix)
        volatile Runnable onBotGone;     // ends the 3DS session when the bot's Java connection dies
    }

    private void serve(Socket sock) {
        AtomicReference<bridge.javaclient.JavaClient> botRef = new AtomicReference<>();
        Puppet puppet = new Puppet();
        try (Tunnel tunnel = new Tunnel(sock)) {
            getLogger().info("session start: " + tunnel.peer());
            // If the bot's Java side dies (kicked by a duplicate login on a rejoin, server stop…),
            // close the tunnel so this session ends now — not when the 3DS's silent TCP drop finally
            // times out. Otherwise the stale puppet, its mover and its skin entry linger.
            puppet.onBotGone = () -> { try { tunnel.close(); } catch (Exception ignore) { } };
            // The factory fires when the 3DS's login names it; the bot logs into THIS server as
            // that player and its world becomes the 3DS's world. Remembered so we can log it out.
            java.util.function.Function<bridge.protocol.Login, bridge.javaclient.JavaClient> factory = login -> {
                bridge.javaclient.JavaClient bot = connectBot(login, puppet);
                botRef.set(bot);
                return bot;
            };
            new bridge.Mc3dsSession(tunnel, factory).run();
            getLogger().info("session ended: " + tunnel.peer());
        } catch (Exception e) {
            getLogger().warning("session ended: " + e);
        } finally {
            if (puppet.taskId != -1) Bukkit.getScheduler().cancelTask(puppet.taskId);
            bridge.javaclient.JavaClient bot = botRef.get();
            if (bot != null) {
                puppets.remove(bot);
                bot.disconnect();
                getLogger().info("bot logged out with its 3DS");
            }
        }
    }

    /**
     * Logs a Java client into this server under {@code name}, via Floodgate's offline handshake, and
     * waits for it to spawn with a full chunk square around it so the 3DS's first view is real
     * terrain. Returns the ready bot, or null if it never spawned (Floodgate/ViaBackwards missing).
     */
    private bridge.javaclient.JavaClient connectBot(bridge.protocol.Login login, Puppet puppet) {
        final String name = login.name();
        try {
            byte[] key = java.nio.file.Files.readAllBytes(java.nio.file.Path.of("plugins/floodgate/key.pem"));
            bridge.javaclient.JavaClient bot = new bridge.javaclient.JavaClient("127.0.0.1", serverPort, name);
            // The console's own built-in skin id and stable uuid, from its login. Registered BEFORE
            // connecting: another 3DS sends PlayerList once, on first sight of this player, and
            // must be able to resolve the skin by then.
            bot.skinId = login.skinId();
            bot.skinFor = this::skinFor;
            puppet.requestedName = name;
            puppets.put(bot, puppet);
            getLogger().info("bot '" + name + "': skin " + login.skinId() + ", console " + login.uuid());
            // Server-authoritative movement: every relayed 3DS move just records a target; the
            // per-tick task below teleports the real player there. Bypasses collision/anti-move,
            // which was snapping the client-driven bot back to spawn on every horizontal step.
            bot.onMove = t -> puppet.target = t;
            // Breaks and places are server-side edits too. A non-op player's dig/place packet is
            // silently ignored inside spawn protection (16 blocks here, and the bot spawns AT
            // spawn); a Bukkit edit is not — and breakNaturally drops the block, which a creative
            // client dig never would. The bot still swings and holds the item, so it looks right.
            bot.onDig = c -> Bukkit.getScheduler().runTask(this, () -> {
                org.bukkit.block.Block b = puppetWorld(puppet).getBlockAt(c[0], c[1], c[2]);
                if (b.getType().isAir()) return;
                org.bukkit.Material was = b.getType();
                b.breakNaturally(DIG_TOOL);
                getLogger().info("broke " + was + " at (" + c[0] + "," + c[1] + "," + c[2] + ")");
            });
            bot.onPlace = c -> Bukkit.getScheduler().runTask(this, () -> {
                org.bukkit.Material m = BlockMap.material(c[3], c[4]);
                if (m == null || !m.isBlock()) return;
                org.bukkit.block.Block b = puppetWorld(puppet).getBlockAt(c[0], c[1], c[2]);
                if (!b.getType().isAir()) {   // the server keeps the final say on occupied cells
                    getLogger().info("place refused at (" + c[0] + "," + c[1] + "," + c[2] + "): " + b.getType());
                    return;
                }
                b.setType(m);
                // A Bukkit edit consumes nothing, so take one from the bot's hand ourselves. The
                // 3DS's inventory is a mirror of the bot's; without this the block is refunded.
                org.bukkit.entity.Player pl = puppet.player;
                if (pl != null) {
                    org.bukkit.inventory.ItemStack h = pl.getInventory().getItemInMainHand();
                    if (h != null && !h.getType().isAir()) {
                        org.bukkit.inventory.ItemStack left = h.clone();
                        left.setAmount(h.getAmount() - 1);
                        pl.getInventory().setItemInMainHand(left.getAmount() > 0 ? left : null);
                        pl.updateInventory();
                    }
                }
                getLogger().info("placed " + m + " at (" + c[0] + "," + c[1] + "," + c[2] + ")");
            });
            bot.handshakeAddress = FloodgateAuth.handshake(key, name, FloodgateAuth.xuidFor(name), "127.0.0.1");
            Thread t = new Thread(() -> {
                try { bot.connect(); }
                catch (Exception e) { getLogger().warning("bot '" + name + "' ended: " + e); }
                finally {
                    puppets.remove(bot);
                    Runnable end = puppet.onBotGone;
                    if (end != null) end.run();
                }
            }, "bot-" + name);
            t.setDaemon(true);
            t.start();

            for (int i = 0; i < 100 && !bot.spawned; i++) Thread.sleep(100);   // up to 10s to spawn
            if (!bot.spawned) {
                getLogger().warning("bot '" + name + "' did not spawn (check Floodgate + ViaBackwards)");
                puppets.remove(bot);
                bot.disconnect();
                return null;
            }
            int ox = (int) Math.floor(bot.posX / 16.0), oz = (int) Math.floor(bot.posZ / 16.0);
            int radius = getConfig().getInt("spawn-chunk-radius", 2);
            for (int i = 0; i < 60 && !squareReady(bot, ox, oz, radius); i++) Thread.sleep(100);
            getLogger().info("bot '" + name + "' ready: " + bot.world.chunkCount() + " chunks, at ("
                    + (int) bot.posX + "," + (int) bot.posY + "," + (int) bot.posZ + ")");
            setCreative(name, bot, puppet);   // creative (digs/places take) + start the teleport mover
            return bot;
        } catch (Exception e) {
            puppets.values().remove(puppet);   // drop a half-registered entry
            getLogger().warning("connectBot('" + name + "') failed: " + e);
            return null;
        }
    }

    /**
     * Puts the bot's real server player into creative so instant digs/places from the 3DS take.
     * The Bukkit Player appears a moment after our JOIN GAME (ViaVersion translates in between), so
     * this retries on the main thread until it finds it. Floodgate prefixes the name with '.'.
     */
    private void setCreative(String requested, bridge.javaclient.JavaClient bot, Puppet puppet) {
        Bukkit.getScheduler().runTask(this, () -> tryCreative(requested, bot, puppet, 20));
    }

    private void tryCreative(String requested, bridge.javaclient.JavaClient bot, Puppet puppet, int retries) {
        for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
            String pn = p.getName();
            if (pn.equals(requested) || pn.equals("." + requested)) {
                p.setGameMode(org.bukkit.GameMode.CREATIVE);
                p.setAllowFlight(true);
                p.setFlying(true);      // no gravity, so the per-tick teleport isn't fought by falling
                bot.flying = true;
                puppet.player = p;
                // Drive the real player from the latest relayed target every tick. Server-side
                // teleport is authoritative: it moves through the same terrain the 3DS walks over.
                puppet.taskId = Bukkit.getScheduler().runTaskTimer(this, () -> {
                    double[] t = puppet.target;
                    org.bukkit.entity.Player pl = puppet.player;
                    if (t == null || pl == null || !pl.isOnline()) return;
                    org.bukkit.Location loc = pl.getLocation();
                    loc.setX(t[0]); loc.setY(t[1]); loc.setZ(t[2]);
                    loc.setYaw((float) t[3]); loc.setPitch((float) t[4]);
                    pl.teleport(loc);
                }, 1L, 1L).getTaskId();
                getLogger().info("bot " + pn + " -> creative; teleport-mover started");
                applySkin(p, bot.skinId);
                return;
            }
        }
        if (retries > 0) {
            Bukkit.getScheduler().runTaskLater(this, () -> tryCreative(requested, bot, puppet, retries - 1), 5L);
        } else {
            getLogger().warning("could not find bot player for '" + requested + "' to set creative");
        }
    }

    /**
     * Shows server drops on every 3DS whose bot is nearby. Bukkit is the source rather than the
     * bot's packets because the stack arrives on the wire inside Entity Metadata we don't parse;
     * the event hands over entity id, position and stack directly, and the id is the same one the
     * bot later sees in Collect Item / Destroy Entities. Only block materials can be drawn
     * (BlockMap is a block table) — other drops are still picked up and show in the inventory.
     */
    @org.bukkit.event.EventHandler
    public void onItemSpawn(org.bukkit.event.entity.ItemSpawnEvent e) {
        org.bukkit.entity.Item item = e.getEntity();
        org.bukkit.inventory.ItemStack stack = item.getItemStack();
        org.bukkit.Material mat = stack.getType();
        if (!mat.isBlock()) return;
        int legacy = BlockMap.legacy(mat) & 0xFFFF;
        int itemId = legacy >>> 4, meta = legacy & 0xF;
        if (itemId <= 0) return;
        org.bukkit.Location loc = item.getLocation();
        int eid = item.getEntityId(), count = stack.getAmount();
        for (java.util.Map.Entry<bridge.javaclient.JavaClient, Puppet> en : puppets.entrySet()) {
            org.bukkit.entity.Player p = en.getValue().player;
            if (p == null || !p.getWorld().equals(loc.getWorld())) continue;
            if (p.getLocation().distanceSquared(loc) > DROP_VIEW_RANGE * DROP_VIEW_RANGE) continue;
            bridge.javaclient.JavaClient bot = en.getKey();
            bridge.javaclient.JavaClient.ItemDrop d = new bridge.javaclient.JavaClient.ItemDrop(
                    eid, itemId, count, meta, loc.getX(), loc.getY(), loc.getZ());
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> bot.onItemAdd.accept(d));  // tunnel write, off main
        }
    }

    /**
     * The built-in 3DS skin id of the console piloting the server player {@code playerName}
     * (Floodgate-prefixed, e.g. ".Bob"), or null for anyone who is not a 3DS. Other consoles ask
     * this when they first see that player, so they can draw its real skin from their own list.
     */
    private String skinFor(String playerName) {
        for (java.util.Map.Entry<bridge.javaclient.JavaClient, Puppet> en : puppets.entrySet()) {
            Puppet pu = en.getValue();
            org.bukkit.entity.Player p = pu.player;
            if (p != null && !p.isOnline()) continue;   // a kicked/stale puppet must not answer for a rejoin
            String req = pu.requestedName;
            if ((p != null && p.getName().equals(playerName))
                    || (req != null && (req.equals(playerName) || ("." + req).equals(playerName)))) {
                return en.getKey().skinId;
            }
        }
        return null;
    }

    private static final double DROP_VIEW_RANGE = 96.0;

    /**
     * Gives the bot's real player the 3DS's built-in skin, for Java viewers. Signing (or the cache
     * read) happens off the main thread; the profile swap must be on it. Floodgate manages profiles
     * for its players and may touch this one just after join, so the swap is re-asserted once.
     */
    private void applySkin(org.bukkit.entity.Player p, String skinId) {
        SkinSigner s = signer;
        if (s == null || skinId == null) return;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            String[] tex = s.signed(skinId);
            if (tex != null) Bukkit.getScheduler().runTask(this, () -> setTextures(p, tex, 2));
        });
    }

    private void setTextures(org.bukkit.entity.Player p, String[] tex, int retries) {
        if (!p.isOnline()) return;
        try {
            com.destroystokyo.paper.profile.PlayerProfile prof = p.getPlayerProfile();
            prof.setProperty(new com.destroystokyo.paper.profile.ProfileProperty("textures", tex[0], tex[1]));
            p.setPlayerProfile(prof);
            getLogger().info("applied 3DS skin to " + p.getName());
        } catch (Exception e) {
            getLogger().warning("could not apply skin to " + p.getName() + ": " + e);
            return;
        }
        if (retries > 0) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!p.isOnline()) return;
                boolean kept = p.getPlayerProfile().getProperties().stream()
                        .anyMatch(pp -> "textures".equals(pp.getName()) && tex[0].equals(pp.getValue()));
                if (!kept) setTextures(p, tex, retries - 1);
            }, 40L);
        }
    }

    /** Drops are computed as if mined with this, so stone and ores yield something for a player
     *  who has no tool system wired yet. Tunable. */
    private static final org.bukkit.inventory.ItemStack DIG_TOOL =
            new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND_PICKAXE);

    private static org.bukkit.World puppetWorld(Puppet puppet) {
        org.bukkit.entity.Player p = puppet.player;
        return p != null ? p.getWorld() : Bukkit.getWorlds().get(0);
    }

    /** True once every chunk in the (2*radius+1) square around (ox,oz) has arrived. */
    private boolean squareReady(bridge.javaclient.JavaClient bot, int ox, int oz, int radius) {
        for (int cx = -radius; cx <= radius; cx++)
            for (int cz = -radius; cz <= radius; cz++)
                if (bot.world.getChunk(ox + cx, oz + cz) == null) return false;
        return true;
    }

    @Override
    public void onDisable() {
        ServerSocket s = server;
        server = null;
        if (s != null) try { s.close(); } catch (IOException ignore) {}
        getLogger().info("TonicJava stopped");
    }
}
