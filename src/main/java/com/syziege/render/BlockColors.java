package com.syziege.render;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Top-down colors for blocks, keyed by block name without namespace.
 * Unknown blocks fall back to keyword heuristics so newly added blocks
 * still render with a plausible color.
 */
public final class BlockColors {

    private static final Map<String, Integer> COLORS = new HashMap<>();
    private static final Map<String, Integer> RESOLVED = new ConcurrentHashMap<>();

    public static final int WATER = 0xFF3F5FBF;

    private BlockColors() {
    }

    private static void put(int rgb, String... names) {
        for (String name : names) {
            COLORS.put(name, 0xFF000000 | rgb);
        }
    }

    static {
        // Terrain
        put(0x7FB238, "grass_block");
        put(0x976C4A, "dirt", "coarse_dirt", "rooted_dirt", "farmland", "dirt_path");
        put(0x5C4033, "podzol");
        put(0x4C6E4C, "mycelium");
        put(0x707070, "stone", "cobblestone", "mossy_cobblestone", "stone_bricks", "infested_stone",
                "smooth_stone", "andesite", "polished_andesite", "gravel");
        put(0x8F8F8F, "diorite", "polished_diorite", "calcite");
        put(0x9C6E51, "granite", "polished_granite");
        put(0x4A4A52, "deepslate", "cobbled_deepslate", "deepslate_bricks", "polished_deepslate", "tuff");
        put(0xE7E0C8, "sand", "sandstone", "smooth_sandstone", "cut_sandstone");
        put(0xBF6B33, "red_sand", "red_sandstone", "smooth_red_sandstone");
        put(0x976C4A, "clay");
        put(0x9E7B5A, "terracotta");
        put(0xFFFCF0, "snow", "snow_block", "powder_snow");
        put(0x7DAFF0, "ice", "frosted_ice");
        put(0x5F8FD3, "packed_ice");
        put(0x4A6FD3, "blue_ice");
        put(0x2E2E36, "bedrock");
        put(0x8A8A8A, "coal_ore", "iron_ore", "copper_ore", "gold_ore", "redstone_ore",
                "diamond_ore", "emerald_ore", "lapis_ore");
        put(0xC15A36, "magma_block");
        put(0x101018, "obsidian", "crying_obsidian");
        put(0x35682D, "moss_block", "moss_carpet");
        put(0x6A5ACD, "amethyst_block", "budding_amethyst");
        put(0xB56A48, "mud", "packed_mud", "mud_bricks");
        put(0x8C6E5A, "dripstone_block", "pointed_dripstone");

        // Liquids
        put(0x3F5FBF, "water", "bubble_column");
        put(0xD45A12, "lava");

        // Vegetation
        put(0x4C8C2A, "oak_leaves", "jungle_leaves", "acacia_leaves", "mangrove_leaves",
                "azalea_leaves", "flowering_azalea_leaves");
        put(0x3A6B29, "spruce_leaves", "dark_oak_leaves");
        put(0x6FA84C, "birch_leaves");
        put(0xE87BB0, "cherry_leaves");
        put(0x9CBF57, "pale_oak_leaves");
        put(0x5C9E31, "short_grass", "grass", "tall_grass", "fern", "large_fern");
        put(0x2E8B57, "vine", "kelp", "kelp_plant", "seagrass", "tall_seagrass");
        put(0x2F7040, "cactus");
        put(0x8FBF4C, "sugar_cane", "bamboo");
        put(0x4C8C2A, "azalea", "flowering_azalea", "big_dripleaf", "small_dripleaf");
        put(0xC8C858, "wheat");
        put(0xE59B34, "pumpkin", "carved_pumpkin", "jack_o_lantern");
        put(0x4C7C1E, "melon");
        put(0x8F6F4C, "brown_mushroom", "brown_mushroom_block");
        put(0xC33C39, "red_mushroom", "red_mushroom_block");

        // Wood
        put(0x6E5530, "oak_log", "oak_wood", "oak_planks");
        put(0x503A22, "spruce_log", "spruce_wood", "spruce_planks");
        put(0xC5B77E, "birch_log", "birch_wood", "birch_planks");
        put(0x9C7050, "jungle_log", "jungle_wood", "jungle_planks");
        put(0xA85A32, "acacia_log", "acacia_wood", "acacia_planks");
        put(0x3E2912, "dark_oak_log", "dark_oak_wood", "dark_oak_planks");
        put(0x76473C, "mangrove_log", "mangrove_wood", "mangrove_planks");
        put(0xE2B7AE, "cherry_log", "cherry_wood", "cherry_planks");

        // Nether
        put(0x6E3533, "netherrack", "nether_bricks", "nether_wart_block");
        put(0x8A4ACF, "crimson_nylium", "crimson_stem", "crimson_planks");
        put(0x2C8577, "warped_nylium", "warped_stem", "warped_planks", "warped_wart_block");
        put(0x59391E, "soul_sand", "soul_soil");
        put(0x3B383E, "basalt", "smooth_basalt", "polished_basalt", "blackstone", "polished_blackstone");
        put(0xC7B24A, "glowstone", "shroomlight");
        put(0xE0DCD5, "quartz_block", "smooth_quartz", "nether_quartz_ore");

        // End
        put(0xDEE0B8, "end_stone", "end_stone_bricks");
        put(0xB08FBF, "chorus_plant", "chorus_flower", "purpur_block", "purpur_pillar");

        // Building / misc
        put(0xA84632, "bricks");
        put(0xB8C0C8, "iron_block");
        put(0xF2D24C, "gold_block");
        put(0x6FE3D8, "diamond_block");
        put(0x3FA83F, "emerald_block");
        put(0xC0CDD9, "white_concrete", "white_wool", "white_terracotta");
        put(0x1E1E22, "black_concrete", "black_wool", "coal_block");
        put(0xB02E26, "red_concrete", "red_wool", "redstone_block");
        put(0x3C44AA, "blue_concrete", "blue_wool");
        put(0x5E7C16, "green_concrete", "green_wool");
        put(0xF9801D, "orange_concrete", "orange_wool");
        put(0xFED83D, "yellow_concrete", "yellow_wool");
        put(0xC74EBD, "magenta_concrete", "magenta_wool");
        put(0x8932B8, "purple_concrete", "purple_wool");
        put(0x3AB3DA, "light_blue_concrete", "light_blue_wool");
        put(0x80C71F, "lime_concrete", "lime_wool");
        put(0xF38BAA, "pink_concrete", "pink_wool");
        put(0x474F52, "gray_concrete", "gray_wool");
        put(0x9D9D97, "light_gray_concrete", "light_gray_wool");
        put(0x169C9C, "cyan_concrete", "cyan_wool");
        put(0x835432, "brown_concrete", "brown_wool");
        put(0xC8C8DC, "glass", "glass_pane");
        put(0x6E5530, "crafting_table", "chest", "barrel", "bookshelf");
        put(0x8F8F99, "furnace", "blast_furnace", "smoker", "stonecutter");
        put(0xD8D8D0, "mushroom_stem");
        put(0xC2A24C, "hay_block");
        put(0xE8E4DC, "bone_block");
        put(0x8FA8B8, "prismarine", "prismarine_bricks", "dark_prismarine", "sea_lantern");
    }

    /** ARGB color for the given block name (namespace already stripped). */
    public static int colorFor(String name) {
        Integer direct = COLORS.get(name);
        if (direct != null) {
            return direct;
        }
        return RESOLVED.computeIfAbsent(name, BlockColors::heuristic);
    }

    private static int heuristic(String name) {
        if (name.contains("water")) return WATER;
        if (name.contains("lava")) return 0xFFD45A12;
        if (name.contains("leaves") || name.contains("sapling") || name.contains("flower")
                || name.contains("grass") || name.contains("bush")) return 0xFF4C8C2A;
        if (name.contains("snow")) return 0xFFFFFCF0;
        if (name.contains("ice")) return 0xFF7DAFF0;
        if (name.contains("sand")) return 0xFFE7E0C8;
        if (name.contains("deepslate") || name.contains("blackstone") || name.contains("basalt")) return 0xFF4A4A52;
        if (name.contains("nether")) return 0xFF6E3533;
        if (name.contains("end_stone") || name.contains("purpur")) return 0xFFDEE0B8;
        if (name.contains("log") || name.contains("wood") || name.contains("plank")
                || name.contains("fence") || name.contains("door")) return 0xFF6E5530;
        if (name.contains("terracotta")) return 0xFF9E7B5A;
        if (name.contains("coral")) return 0xFFE87BB0;
        if (name.contains("ore") || name.contains("stone") || name.contains("cobble")
                || name.contains("brick") || name.contains("slab") || name.contains("stairs")
                || name.contains("wall")) return 0xFF707070;
        if (name.contains("dirt") || name.contains("mud") || name.contains("soil")) return 0xFF976C4A;
        if (name.contains("wool") || name.contains("concrete") || name.contains("carpet")) return 0xFFC0C0C8;
        return 0xFF7F7F87;
    }

    /** Blocks that should be skipped when looking for the render surface. */
    public static boolean isInvisible(String name) {
        switch (name) {
            case "air":
            case "cave_air":
            case "void_air":
            case "barrier":
            case "light":
            case "structure_void":
                return true;
            default:
                return false;
        }
    }

    public static boolean isWater(String name) {
        switch (name) {
            case "water":
            case "bubble_column":
            case "kelp":
            case "kelp_plant":
            case "seagrass":
            case "tall_seagrass":
                return true;
            default:
                return false;
        }
    }
}
