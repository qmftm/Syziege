package com.syziege.mca;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A parsed 1.18+ chunk: block palette sections plus the WORLD_SURFACE
 * heightmap, enough to render a top-down view of the terrain.
 */
public final class ChunkColumn {

    private static final class Section {
        final String[] palette;
        final long[] data;
        final int bits;

        Section(String[] palette, long[] data) {
            this.palette = palette;
            this.data = data;
            int b = 4;
            while ((1 << b) < palette.length) {
                b++;
            }
            this.bits = b;
        }

        String blockAt(int x, int y, int z) {
            if (data == null || palette.length == 1) {
                return palette.length > 0 ? palette[0] : "air";
            }
            int index = (y << 8) | (z << 4) | x;
            int perLong = 64 / bits;
            long word = data[index / perLong];
            int shift = (index % perLong) * bits;
            int paletteIndex = (int) ((word >>> shift) & ((1L << bits) - 1));
            if (paletteIndex >= palette.length) {
                return "air";
            }
            return palette[paletteIndex];
        }
    }

    /** Biome palette for a 16-block section, at 4x4x4 (biome cell) resolution. */
    private static final class BiomeSection {
        final String[] palette;
        final long[] data;
        final int bits;

        BiomeSection(String[] palette, long[] data) {
            this.palette = palette;
            this.data = data;
            int b = 1;
            while ((1 << b) < palette.length) {
                b++;
            }
            this.bits = Math.max(1, b);
        }

        String biomeAt(int cx, int cy, int cz) {
            if (data == null || palette.length == 1) {
                return palette.length > 0 ? palette[0] : "plains";
            }
            int index = (cy << 4) | (cz << 2) | cx;
            int perLong = 64 / bits;
            long word = data[index / perLong];
            int shift = (index % perLong) * bits;
            int paletteIndex = (int) ((word >>> shift) & ((1L << bits) - 1));
            return paletteIndex < palette.length ? palette[paletteIndex] : palette[0];
        }
    }

    private final Map<Integer, Section> sections = new HashMap<>();
    private final Map<Integer, BiomeSection> biomes = new HashMap<>();
    private final long[] surfaceHeightmap;
    private final int heightmapBits;
    private final int minSectionY;
    private final int maxSectionY;
    private final int worldMinY;

    private ChunkColumn(Map<String, Object> root) {
        List<Object> sectionList = Nbt.getList(root, "sections");
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        if (sectionList != null) {
            for (Object obj : sectionList) {
                if (!(obj instanceof Map)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> sec = (Map<String, Object>) obj;
                Map<String, Object> blockStates = Nbt.getCompound(sec, "block_states");
                if (blockStates == null) {
                    continue;
                }
                List<Object> paletteList = Nbt.getList(blockStates, "palette");
                if (paletteList == null || paletteList.isEmpty()) {
                    continue;
                }
                String[] palette = new String[paletteList.size()];
                for (int i = 0; i < palette.length; i++) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> entry = (Map<String, Object>) paletteList.get(i);
                    String name = Nbt.getString(entry, "Name");
                    palette[i] = name == null ? "air" : stripNamespace(name);
                }
                int y = Nbt.getInt(sec, "Y", 0);
                sections.put(y, new Section(palette, Nbt.getLongArray(blockStates, "data")));
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);

                Map<String, Object> biomeTag = Nbt.getCompound(sec, "biomes");
                if (biomeTag != null) {
                    List<Object> biomePalette = Nbt.getList(biomeTag, "palette");
                    if (biomePalette != null && !biomePalette.isEmpty()) {
                        String[] names = new String[biomePalette.size()];
                        for (int i = 0; i < names.length; i++) {
                            Object entry = biomePalette.get(i);
                            names[i] = entry instanceof String ? stripNamespace((String) entry) : "plains";
                        }
                        biomes.put(y, new BiomeSection(names, Nbt.getLongArray(biomeTag, "data")));
                    }
                }
            }
        }
        this.minSectionY = minY == Integer.MAX_VALUE ? 0 : minY;
        this.maxSectionY = maxY == Integer.MIN_VALUE ? -1 : maxY;
        this.worldMinY = Nbt.getInt(root, "yPos", this.minSectionY) * 16;

        Map<String, Object> heightmaps = Nbt.getCompound(root, "Heightmaps");
        long[] surface = null;
        if (heightmaps != null) {
            surface = Nbt.getLongArray(heightmaps, "WORLD_SURFACE");
            if (surface == null) {
                surface = Nbt.getLongArray(heightmaps, "MOTION_BLOCKING");
            }
        }
        this.surfaceHeightmap = surface;
        this.heightmapBits = surface == null ? 0 : Math.max(1, (surface.length * 64) / 256);
    }

    /** Parses a chunk NBT root, returning null for chunks that are not fully generated. */
    public static ChunkColumn parse(Map<String, Object> root) {
        if (root == null) {
            return null;
        }
        String status = Nbt.getString(root, "Status");
        if (status == null) {
            status = Nbt.getString(root, "status");
        }
        if (status != null && !status.endsWith("full")) {
            return null;
        }
        ChunkColumn column = new ChunkColumn(root);
        if (column.sections.isEmpty()) {
            return null;
        }
        return column;
    }

    private static String stripNamespace(String name) {
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    public int minY() {
        return minSectionY * 16;
    }

    public int maxY() {
        return maxSectionY * 16 + 15;
    }

    /** Block name (namespace stripped) at chunk-local x/z and absolute y. */
    public String blockAt(int x, int y, int z) {
        Section section = sections.get(Math.floorDiv(y, 16));
        if (section == null) {
            return "air";
        }
        return section.blockAt(x & 15, y & 15, z & 15);
    }

    /** Biome name (namespace stripped) at chunk-local x/z and absolute y. */
    public String biomeAt(int x, int y, int z) {
        BiomeSection section = biomes.get(Math.floorDiv(y, 16));
        if (section == null) {
            return "plains";
        }
        return section.biomeAt((x & 15) >> 2, (y & 15) >> 2, (z & 15) >> 2);
    }

    /**
     * Absolute Y of the highest surface block at chunk-local x/z according
     * to the heightmap, or {@link Integer#MIN_VALUE} if unknown.
     */
    public int surfaceY(int x, int z) {
        if (surfaceHeightmap == null || heightmapBits <= 0) {
            return Integer.MIN_VALUE;
        }
        int index = (z << 4) | x;
        int perLong = 64 / heightmapBits;
        int wordIndex = index / perLong;
        if (wordIndex >= surfaceHeightmap.length) {
            return Integer.MIN_VALUE;
        }
        long word = surfaceHeightmap[wordIndex];
        int shift = (index % perLong) * heightmapBits;
        int value = (int) ((word >>> shift) & ((1L << heightmapBits) - 1));
        if (value == 0) {
            return Integer.MIN_VALUE;
        }
        return worldMinY + value - 1;
    }
}
