package bridge.paper;

import org.bukkit.Material;

import java.util.HashMap;
import java.util.Map;

/**
 * Modern Bukkit {@link Material} -> legacy 1.12.2 block state ({@code id<<4 | meta}), which is what
 * {@link bridge.world.WorldModel} stores and {@link bridge.protocol.Mc3dsChunk} encodes to MC3DS.
 * Modern Minecraft dropped numeric ids at the 1.13 flattening, so this is a hand table for the
 * blocks MC3DS actually understands (roughly the classic ~256). Anything unmapped and solid falls
 * back to stone so terrain stays visible rather than turning to holes.
 */
public final class BlockMap {
    private BlockMap() {}

    private static final Map<String, Short> M = new HashMap<>();
    private static void p(String name, int id, int meta) { M.put(name, (short) ((id << 4) | (meta & 0xF))); }
    private static void p(String name, int id) { p(name, id, 0); }

    static {
        p("STONE", 1); p("GRANITE", 1, 1); p("POLISHED_GRANITE", 1, 2); p("DIORITE", 1, 3);
        p("POLISHED_DIORITE", 1, 4); p("ANDESITE", 1, 5); p("POLISHED_ANDESITE", 1, 6);
        p("DEEPSLATE", 1); p("TUFF", 1); p("CALCITE", 1);
        p("GRASS_BLOCK", 2); p("DIRT", 3); p("COARSE_DIRT", 3, 1); p("PODZOL", 3, 2); p("ROOTED_DIRT", 3);
        p("COBBLESTONE", 4); p("COBBLED_DEEPSLATE", 4);
        p("OAK_PLANKS", 5); p("SPRUCE_PLANKS", 5, 1); p("BIRCH_PLANKS", 5, 2); p("JUNGLE_PLANKS", 5, 3);
        p("ACACIA_PLANKS", 5, 4); p("DARK_OAK_PLANKS", 5, 5);
        p("BEDROCK", 7);
        p("WATER", 9); p("LAVA", 11);
        p("SAND", 12); p("RED_SAND", 12, 1); p("GRAVEL", 13);
        p("GOLD_ORE", 14); p("IRON_ORE", 15); p("COAL_ORE", 16);
        p("OAK_LOG", 17); p("SPRUCE_LOG", 17, 1); p("BIRCH_LOG", 17, 2); p("JUNGLE_LOG", 17, 3);
        p("OAK_WOOD", 17); p("STRIPPED_OAK_LOG", 17);
        p("OAK_LEAVES", 18); p("SPRUCE_LEAVES", 18, 1); p("BIRCH_LEAVES", 18, 2); p("JUNGLE_LEAVES", 18, 3);
        p("ACACIA_LEAVES", 18); p("DARK_OAK_LEAVES", 18);
        p("SPONGE", 19); p("GLASS", 20);
        p("LAPIS_ORE", 21); p("LAPIS_BLOCK", 22); p("SANDSTONE", 24);
        p("WHITE_WOOL", 35); p("DANDELION", 37); p("POPPY", 38); p("BROWN_MUSHROOM", 39); p("RED_MUSHROOM", 40);
        p("GOLD_BLOCK", 41); p("IRON_BLOCK", 42);
        p("BRICKS", 45); p("TNT", 46); p("BOOKSHELF", 47); p("MOSSY_COBBLESTONE", 48); p("OBSIDIAN", 49);
        p("TORCH", 50); p("WALL_TORCH", 50);
        p("DIAMOND_ORE", 56); p("DIAMOND_BLOCK", 57); p("CRAFTING_TABLE", 58); p("FARMLAND", 60); p("FURNACE", 61);
        p("OAK_DOOR", 64); p("LADDER", 65); p("COBBLESTONE_STAIRS", 67);
        p("REDSTONE_ORE", 73); p("ICE", 79); p("SNOW_BLOCK", 80); p("CACTUS", 81); p("CLAY", 82);
        p("SUGAR_CANE", 83); p("PUMPKIN", 86); p("NETHERRACK", 87); p("SOUL_SAND", 88); p("GLOWSTONE", 89);
        p("OAK_STAIRS", 53); p("CHEST", 54);
        p("STONE_BRICKS", 98); p("MOSSY_STONE_BRICKS", 98, 1); p("CRACKED_STONE_BRICKS", 98, 2);
        p("MELON", 103); p("MYCELIUM", 110); p("NETHER_BRICKS", 112);
        p("END_STONE", 121); p("EMERALD_ORE", 129); p("EMERALD_BLOCK", 133);
        p("REDSTONE_BLOCK", 152); p("QUARTZ_BLOCK", 155); p("HAY_BLOCK", 170);
        p("TERRACOTTA", 172); p("COAL_BLOCK", 173); p("PACKED_ICE", 174);
    }

    private static final Map<Integer, Material> REV = new HashMap<>();
    static {
        for (Map.Entry<String, Short> e : M.entrySet()) {
            try { REV.putIfAbsent(e.getValue() & 0xFFFF, Material.valueOf(e.getKey())); }
            catch (IllegalArgumentException ignore) { /* name not in this server version */ }
        }
    }

    /** Reverse of {@link #legacy}: a placeable Material for an MC3DS id/meta, stone as fallback. */
    public static Material material(int id, int meta) {
        Material m = REV.get(((id << 4) | (meta & 0xF)) & 0xFFFF);
        if (m == null) m = REV.get((id << 4) & 0xFFFF);   // ignore meta
        return m != null ? m : Material.STONE;
    }

    /** Legacy state for a Material; 0 (air) for air, else the table, else stone as a safe default. */
    public static short legacy(Material m) {
        if (m == null || m.isAir()) return 0;
        Short v = M.get(m.name());
        if (v != null) return v;
        // common families not individually listed
        String n = m.name();
        if (n.endsWith("_LEAVES")) return (short) (18 << 4);
        if (n.endsWith("_LOG") || n.endsWith("_WOOD")) return (short) (17 << 4);
        if (n.endsWith("_PLANKS")) return (short) (5 << 4);
        if (n.endsWith("_WOOL")) return (short) (35 << 4);
        if (n.endsWith("_CONCRETE") || n.endsWith("_TERRACOTTA")) return (short) (172 << 4);
        if (n.contains("LEAVES")) return (short) (18 << 4);
        if (n.contains("WATER")) return (short) (9 << 4);
        return (short) (1 << 4);   // default: stone
    }
}
