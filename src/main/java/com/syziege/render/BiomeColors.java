package com.syziege.render;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-biome tint colors for grass, foliage and water. The renderer replaces
 * the flat colors of tintable blocks (grass, leaves, water) with these so
 * terrain reads as distinct biomes, and blends them across biome borders
 * for smooth gradients.
 */
public final class BiomeColors {

    /** RGB (no alpha) grass, foliage and water tints for a biome. */
    public static final class Tint {
        public final int grass;
        public final int foliage;
        public final int water;

        Tint(int grass, int foliage, int water) {
            this.grass = grass;
            this.foliage = foliage;
            this.water = water;
        }
    }

    private static final Map<String, Tint> TINTS = new HashMap<>();
    private static final Tint DEFAULT = new Tint(0x79C05A, 0x59AE30, 0x3F76E4);

    private BiomeColors() {
    }

    private static void put(int grass, int foliage, int water, String... biomes) {
        Tint tint = new Tint(grass, foliage, water);
        for (String biome : biomes) {
            TINTS.put(biome, tint);
        }
    }

    static {
        // Temperate
        put(0x79C05A, 0x59AE30, 0x3F76E4, "plains", "sunflower_plains", "meadow");
        put(0x88BB67, 0x6BA941, 0x3F76E4, "forest", "flower_forest", "grove");
        put(0x79C05A, 0x59AE30, 0x3F76E4, "birch_forest", "old_growth_birch_forest");
        put(0x59AE30, 0x30BB0B, 0x3F76E4, "dark_forest", "pale_garden");
        put(0x86B87F, 0x6DA36B, 0x3D57D6, "windswept_hills", "windswept_gravelly_hills",
                "windswept_forest", "stony_shore");
        put(0x91BD59, 0x77AB2F, 0x44AFF5, "river");

        // Cold / snowy
        put(0x80B497, 0x60A17B, 0x3D57D6, "taiga", "old_growth_pine_taiga", "old_growth_spruce_taiga");
        put(0x80B497, 0x60A17B, 0x3938C9, "snowy_taiga", "snowy_plains", "ice_spikes",
                "snowy_slopes", "frozen_peaks", "jagged_peaks", "snowy_beach");
        put(0x80B497, 0x60A17B, 0x3D57D6, "stony_peaks", "frozen_river", "frozen_ocean",
                "deep_frozen_ocean");

        // Warm / dry
        put(0xBFB755, 0xAEA42A, 0x32A598, "savanna", "savanna_plateau", "windswept_savanna");
        put(0xBFB755, 0xAEA42A, 0x32A598, "desert", "badlands", "eroded_badlands",
                "wooded_badlands");

        // Lush / wet
        put(0x59C93C, 0x30BB0B, 0x22FFC3, "jungle", "sparse_jungle", "bamboo_jungle");
        put(0x6A7039, 0x496137, 0x617B64, "swamp");
        put(0x6A7039, 0x496137, 0x4E7F81, "mangrove_swamp");
        put(0x91BD59, 0x77AB2F, 0x3F76E4, "beach", "mushroom_fields");

        // Oceans
        put(0x8EB971, 0x6FA850, 0x3F76E4, "ocean", "deep_ocean");
        put(0x8EB971, 0x6FA850, 0x43D5EE, "warm_ocean", "lukewarm_ocean", "deep_lukewarm_ocean");
        put(0x8EB971, 0x6FA850, 0x2080C9, "cold_ocean", "deep_cold_ocean");

        // Nether / End / caves keep neutral tints
        put(0x79C05A, 0x59AE30, 0x3F76E4, "the_void", "the_end", "small_end_islands",
                "end_midlands", "end_highlands", "end_barrens");
        put(0x6A7039, 0x496137, 0x905957, "nether_wastes", "crimson_forest", "warped_forest",
                "soul_sand_valley", "basalt_deltas");
        put(0x79C05A, 0x59AE30, 0x3F76E4, "dripstone_caves", "lush_caves", "deep_dark");
    }

    public static Tint tintFor(String biome) {
        Tint tint = TINTS.get(biome);
        return tint != null ? tint : DEFAULT;
    }
}
