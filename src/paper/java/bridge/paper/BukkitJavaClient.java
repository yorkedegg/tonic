package bridge.paper;

import bridge.javaclient.JavaClient;
import bridge.world.WorldModel;

import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.event.HandlerList;

import java.util.List;

/**
 * The Paper plugin's LIVE world source. Subclasses the bot's {@link JavaClient} so the whole proven
 * MC3DS path (SpawnProtocol, JavaLink.buildSpawn, Mc3dsChunk, the chunk streamer, entity + block
 * callbacks) is reused unchanged — but every I/O is against the LOCAL Bukkit server instead of a
 * remote socket. All Bukkit access is on the server main thread (events already are; the 3DS-driven
 * send* hop over via the scheduler). Coordinates from SpawnProtocol are already Java world coords.
 */
public final class BukkitJavaClient extends JavaClient implements Listener {

    private final Plugin plugin;
    private final World bukkitWorld;
    private volatile Material held = Material.STONE;   // what UseItem places, set by sendCreativeSet
    private BukkitTask playerTask;
    private ArmorStand avatar;                          // a stand-in body PC players can see
    private volatile String avatarName = "Tonic3DS";
    private static final int LOAD_RADIUS = 3;          // chunks kept loaded around the player

    public BukkitJavaClient(Plugin plugin, World world) {
        super("", 0, "bridgebot");
        this.plugin = plugin;
        this.bukkitWorld = world;
    }

    /** Initial spawn-area fill (chunks pre-captured on the main thread). Java Y maps to model Y. */
    public void load(List<ChunkSnapshot> snaps, double sx, double sy, double sz, int minY, int maxY) {
        int lo = Math.max(0, minY), hi = Math.min(128, maxY);
        for (ChunkSnapshot snap : snaps) addSnapshot(snap, lo, hi);
        posX = sx; posY = Math.min(sy, 120.0); posZ = sz; spawned = true;
    }

    private void addSnapshot(ChunkSnapshot snap, int lo, int hi) {
        WorldModel.Chunk c = new WorldModel.Chunk(snap.getX(), snap.getZ());
        for (int lx = 0; lx < 16; lx++)
            for (int lz = 0; lz < 16; lz++)
                for (int y = lo; y < hi; y++) {
                    short st = BlockMap.legacy(snap.getBlockType(lx, y, lz));
                    if (st != 0) c.set(lx, y, lz, st);
                }
        world.putChunk(c);
    }

    /** Registers live listeners and the other-player poll. Call on the main thread after load(). */
    public void startLive() {
        // A visible stand-in so PC players can see where the 3DS player is (a real Player entity
        // would need NMS injection; an armour stand is the no-NMS approximation for now).
        try {
            avatar = (ArmorStand) bukkitWorld.spawnEntity(new Location(bukkitWorld, posX, posY, posZ),
                    EntityType.ARMOR_STAND);
            avatar.setCustomName(avatarName);
            avatar.setCustomNameVisible(true);
            avatar.setArms(true);
            avatar.setBasePlate(false);
            avatar.setGravity(false);
            avatar.setInvulnerable(true);
        } catch (Throwable t) { plugin.getLogger().warning("could not spawn 3DS avatar: " + t); }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // Show other players (and their movement) on the 3DS ~5x/sec. onPlayer is wired by
        // SpawnProtocol once the client reaches play; until then it's a no-op.
        playerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (org.bukkit.entity.Player pl : bukkitWorld.getPlayers()) {
                Location l = pl.getLocation();
                onPlayer.accept(new JavaClient.Player(pl.getEntityId(), pl.getUniqueId(), pl.getName(),
                        l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch()), false);
            }
        }, 20L, 4L);
    }

    // ---- other players' block edits -> the 3DS (main thread; onBlockChange converts + filters) ----
    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (b.getWorld() == bukkitWorld) onBlockChange.accept(new int[]{b.getX(), b.getY(), b.getZ(), 0});
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlock();
        if (b.getWorld() == bukkitWorld)
            onBlockChange.accept(new int[]{b.getX(), b.getY(), b.getZ(), BlockMap.legacy(b.getType()) & 0xFFFF});
    }

    // ---- 3DS actions -> the Bukkit world (coords are already Java world; hop to the main thread) ----
    @Override public synchronized void sendDig(int x, int y, int z, int face) {
        Bukkit.getScheduler().runTask(plugin, () -> bukkitWorld.getBlockAt(x, y, z).setType(Material.AIR, true));
    }

    @Override public synchronized void sendPlace(int x, int y, int z, int face) {
        final Material m = held;
        Bukkit.getScheduler().runTask(plugin, () -> {
            int tx = x, ty = y, tz = z;                 // clicked block + the clicked face
            switch (face) { case 0 -> ty--; case 1 -> ty++; case 2 -> tz--;
                            case 3 -> tz++; case 4 -> tx--; default -> tx++; }
            Block b = bukkitWorld.getBlockAt(tx, ty, tz);
            if (b.getType() == Material.AIR || b.isLiquid()) b.setType(m, true);
        });
    }

    @Override public synchronized void sendPositionLook(double x, double y, double z, float yaw, float pitch) {
        posX = x; posY = y; posZ = z;                   // keep our anchor current
        final int ccx = (int) Math.floor(x / 16.0), ccz = (int) Math.floor(z / 16.0);
        Bukkit.getScheduler().runTask(plugin, () -> {
            loadAround(ccx, ccz);                               // feed the chunk streamer
            if (avatar != null && avatar.isValid())
                avatar.teleport(new Location(bukkitWorld, x, y, z, yaw, pitch));
        });
    }

    private void loadAround(int ccx, int ccz) {
        for (int cx = ccx - LOAD_RADIUS; cx <= ccx + LOAD_RADIUS; cx++)
            for (int cz = ccz - LOAD_RADIUS; cz <= ccz + LOAD_RADIUS; cz++) {
                if (world.getChunk(cx, cz) != null) continue;         // already in the model
                addSnapshot(bukkitWorld.getChunkAt(cx, cz).getChunkSnapshot(),
                        Math.max(0, bukkitWorld.getMinHeight()), Math.min(128, bukkitWorld.getMaxHeight()));
            }
    }

    @Override public synchronized void sendCreativeSet(int slot, int itemId, int count, int damage) {
        held = BlockMap.material(itemId, damage);
    }

    @Override public synchronized void sendHeldSlot(int slot) {}

    /** Cancels the poll and unregisters listeners when the 3DS disconnects (it reconnects often). */
    public void close() {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (playerTask != null) playerTask.cancel();
            if (avatar != null) avatar.remove();
            HandlerList.unregisterAll(this);
        });
    }
}
