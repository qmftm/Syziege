package com.syziege.render;

import com.syziege.mca.ChunkColumn;
import com.syziege.mca.RegionFile;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Renders one region file (32x32 chunks = 512x512 blocks) into a 512x512
 * top-down image, one pixel per block, with height-based hill shading.
 */
public final class RegionRenderer {

    public static final int TILE_SIZE = 512;

    /**
     * Renders the given region file. Returns null when the file contains
     * no renderable chunks.
     */
    public BufferedImage render(Path regionPath) throws IOException {
        int[] colors = new int[TILE_SIZE * TILE_SIZE];
        int[] heights = new int[TILE_SIZE * TILE_SIZE];
        Arrays.fill(heights, Integer.MIN_VALUE);
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
                    renderChunk(chunk, colors, heights, cx * 16, cz * 16);
                }
            }
        }

        if (!any) {
            return null;
        }

        applyShading(colors, heights);

        BufferedImage image = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, TILE_SIZE, TILE_SIZE, colors, 0, TILE_SIZE);
        return image;
    }

    private void renderChunk(ChunkColumn chunk, int[] colors, int[] heights, int pixelX, int pixelZ) {
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

                int argb;
                if (BlockColors.isWater(block)) {
                    int floorY = y;
                    while (floorY > chunk.minY() && BlockColors.isWater(chunk.blockAt(x, floorY, z))) {
                        floorY--;
                    }
                    String floor = chunk.blockAt(x, floorY, z);
                    int depth = y - floorY;
                    double alpha = Math.min(0.88, 0.5 + depth * 0.035);
                    argb = blend(BlockColors.colorFor(floor), BlockColors.waterColor(depth), alpha);
                    y = floorY; // shade by the sea floor so water looks flat
                } else {
                    argb = BlockColors.colorFor(block);
                }

                int idx = (pixelZ + z) * TILE_SIZE + (pixelX + x);
                colors[idx] = argb;
                heights[idx] = y;
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
     * Relief shading: south-facing slopes brighten and north-facing ones
     * darken (dynmap-style), and overall brightness rises with altitude so
     * lowlands read darker than peaks.
     */
    private void applyShading(int[] colors, int[] heights) {
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
                    colors[idx] = scale(colors[idx], clamp(factor, 0.6, 1.35));
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
