package com.syziege.render;

import com.syziege.mca.ChunkColumn;
import com.syziege.mca.RegionFile;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Renders one region file (32x32 chunks = 512x512 blocks) into a 512x512
 * top-down image, one pixel per block. Grass, foliage and water are tinted
 * by the chunk's biome data and blended across biome borders for smooth
 * gradients, then shaded by terrain height and slope.
 */
public final class RegionRenderer {

    public static final int TILE_SIZE = 512;
    private static final int AREA = TILE_SIZE * TILE_SIZE;
    /** Half-width, in blocks, of the biome color blend. */
    private static final int BIOME_BLEND_RADIUS = 5;

    /** Per-render scratch buffers, so a single renderer is safe across threads. */
    private static final class Buffers {
        final int[] colors = new int[AREA];
        final int[] heights = new int[AREA];
        final byte[] tintType = new byte[AREA];
        final int[] grassTint = new int[AREA];
        final int[] foliageTint = new int[AREA];
        final int[] waterTint = new int[AREA];
        final int[] waterFloor = new int[AREA];
        final int[] waterDepth = new int[AREA];
        final boolean[] filled = new boolean[AREA];
        final int[] blurTmp = new int[AREA];

        Buffers() {
            Arrays.fill(heights, Integer.MIN_VALUE);
        }
    }

    /**
     * Renders the given region file. Returns null when the file contains
     * no renderable chunks.
     */
    public BufferedImage render(Path regionPath) throws IOException {
        Buffers b = new Buffers();
        boolean any = false;

        try (RegionFile region = new RegionFile(regionPath)) {
            for (int cz = 0; cz < 32; cz++) {
                for (int cx = 0; cx < 32; cx++) {
                    ChunkColumn chunk;
                    try {
                        chunk = ChunkColumn.parse(region.readChunk(cx, cz));
                    } catch (IOException | RuntimeException e) {
                        continue; // skip corrupt chunks, keep rendering the rest
                    }
                    if (chunk == null) {
                        continue;
                    }
                    any = true;
                    renderChunk(b, chunk, cx * 16, cz * 16);
                }
            }
        }

        if (!any) {
            return null;
        }

        blur(b, b.grassTint);
        blur(b, b.foliageTint);
        blur(b, b.waterTint);
        combineTints(b);
        applyShading(b);

        BufferedImage image = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, TILE_SIZE, TILE_SIZE, b.colors, 0, TILE_SIZE);
        return image;
    }

    private void renderChunk(Buffers b, ChunkColumn chunk, int pixelX, int pixelZ) {
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int y = chunk.surfaceY(x, z);
                if (y == Integer.MIN_VALUE) {
                    y = scanDown(chunk, x, chunk.maxY(), z);
                    if (y == Integer.MIN_VALUE) {
                        continue;
                    }
                }
                String block = chunk.blockAt(x, y, z);
                while (BlockColors.isInvisible(block) && y > chunk.minY()) {
                    y--;
                    block = chunk.blockAt(x, y, z);
                }
                if (BlockColors.isInvisible(block)) {
                    continue;
                }

                int idx = (pixelZ + z) * TILE_SIZE + (pixelX + x);

                // Biome tint fields are filled for every rendered pixel so the
                // blur blends smoothly, even over blocks that aren't tinted.
                BiomeColors.Tint tint = BiomeColors.tintFor(chunk.biomeAt(x, y, z));
                b.grassTint[idx] = tint.grass;
                b.foliageTint[idx] = tint.foliage;
                b.waterTint[idx] = tint.water;

                int type = BlockColors.tintType(block);
                b.tintType[idx] = (byte) type;

                if (type == BlockColors.TINT_WATER) {
                    int floorY = y;
                    while (floorY > chunk.minY() && BlockColors.isWater(chunk.blockAt(x, floorY, z))) {
                        floorY--;
                    }
                    b.waterFloor[idx] = BlockColors.colorFor(chunk.blockAt(x, floorY, z));
                    b.waterDepth[idx] = y - floorY;
                    y = floorY; // shade water by the sea floor so it lies flat
                } else if (type == BlockColors.TINT_NONE) {
                    b.colors[idx] = BlockColors.colorFor(block);
                }

                b.heights[idx] = y;
                b.filled[idx] = true;
            }
        }
    }

    /** Resolves each tinted pixel to a final color once the biome fields are blurred. */
    private void combineTints(Buffers b) {
        for (int idx = 0; idx < AREA; idx++) {
            if (!b.filled[idx]) {
                continue;
            }
            switch (b.tintType[idx]) {
                case BlockColors.TINT_GRASS:
                    b.colors[idx] = 0xFF000000 | b.grassTint[idx];
                    break;
                case BlockColors.TINT_FOLIAGE:
                    b.colors[idx] = 0xFF000000 | b.foliageTint[idx];
                    break;
                case BlockColors.TINT_WATER: {
                    int depth = b.waterDepth[idx];
                    int surface = scale(0xFF000000 | b.waterTint[idx], clamp(1.0 - depth * 0.02, 0.45, 1.0));
                    double alpha = Math.min(0.9, 0.55 + depth * 0.03);
                    b.colors[idx] = blend(b.waterFloor[idx], surface, alpha);
                    break;
                }
                default:
                    break; // TINT_NONE already has its color
            }
        }
    }

    private int scanDown(ChunkColumn chunk, int x, int fromY, int z) {
        for (int y = fromY; y >= chunk.minY(); y--) {
            if (!BlockColors.isInvisible(chunk.blockAt(x, y, z))) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    /**
     * Masked separable box blur of an RGB field over rendered pixels only, so
     * biome colors fade into each other without bleeding into empty chunks.
     */
    private void blur(Buffers b, int[] field) {
        int r = BIOME_BLEND_RADIUS;
        boolean[] filled = b.filled;
        int[] tmp = b.blurTmp;
        for (int z = 0; z < TILE_SIZE; z++) {
            int rowBase = z * TILE_SIZE;
            for (int x = 0; x < TILE_SIZE; x++) {
                int idx = rowBase + x;
                if (!filled[idx]) {
                    tmp[idx] = field[idx];
                    continue;
                }
                int sr = 0, sg = 0, sb = 0, c = 0;
                int lo = Math.max(0, x - r), hi = Math.min(TILE_SIZE - 1, x + r);
                for (int xx = lo; xx <= hi; xx++) {
                    int i = rowBase + xx;
                    if (!filled[i]) {
                        continue;
                    }
                    int v = field[i];
                    sr += (v >> 16) & 0xFF;
                    sg += (v >> 8) & 0xFF;
                    sb += v & 0xFF;
                    c++;
                }
                tmp[idx] = c == 0 ? field[idx] : ((sr / c) << 16) | ((sg / c) << 8) | (sb / c);
            }
        }
        for (int x = 0; x < TILE_SIZE; x++) {
            for (int z = 0; z < TILE_SIZE; z++) {
                int idx = z * TILE_SIZE + x;
                if (!filled[idx]) {
                    field[idx] = tmp[idx];
                    continue;
                }
                int sr = 0, sg = 0, sb = 0, c = 0;
                int lo = Math.max(0, z - r), hi = Math.min(TILE_SIZE - 1, z + r);
                for (int zz = lo; zz <= hi; zz++) {
                    int i = zz * TILE_SIZE + x;
                    if (!filled[i]) {
                        continue;
                    }
                    int v = tmp[i];
                    sr += (v >> 16) & 0xFF;
                    sg += (v >> 8) & 0xFF;
                    sb += v & 0xFF;
                    c++;
                }
                field[idx] = c == 0 ? tmp[idx] : ((sr / c) << 16) | ((sg / c) << 8) | (sb / c);
            }
        }
    }

    /**
     * Relief shading: south-facing slopes brighten and north-facing ones
     * darken (dynmap-style), and overall brightness rises with altitude so
     * lowlands read darker than peaks.
     */
    private void applyShading(Buffers b) {
        int[] heights = b.heights;
        for (int z = TILE_SIZE - 1; z >= 0; z--) {
            for (int x = 0; x < TILE_SIZE; x++) {
                int idx = z * TILE_SIZE + x;
                int h = heights[idx];
                if (h == Integer.MIN_VALUE) {
                    continue;
                }
                double factor = clamp(0.84 + (h - 64) * 0.0028, 0.72, 1.14);
                if (z > 0) {
                    int hn = heights[idx - TILE_SIZE];
                    if (hn != Integer.MIN_VALUE) {
                        factor *= clamp(1.0 + (h - hn) * 0.08, 0.72, 1.25);
                    }
                }
                if (factor != 1.0) {
                    b.colors[idx] = scale(b.colors[idx], clamp(factor, 0.6, 1.35));
                }
            }
        }
    }

    private static int blend(int base, int over, double alpha) {
        int r = (int) (((base >> 16) & 0xFF) * (1 - alpha) + ((over >> 16) & 0xFF) * alpha);
        int g = (int) (((base >> 8) & 0xFF) * (1 - alpha) + ((over >> 8) & 0xFF) * alpha);
        int b = (int) ((base & 0xFF) * (1 - alpha) + (over & 0xFF) * alpha);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int scale(int argb, double factor) {
        int r = Math.min(255, (int) (((argb >> 16) & 0xFF) * factor));
        int g = Math.min(255, (int) (((argb >> 8) & 0xFF) * factor));
        int b = Math.min(255, (int) ((argb & 0xFF) * factor));
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
