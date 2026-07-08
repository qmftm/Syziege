package com.syziege.webmap;

import com.syziege.render.RegionRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders and caches map tiles. Tiles map 1:1 to region files
 * (one 512x512 PNG per r.X.Z.mca) and are re-rendered when the region
 * file on disk is newer than the cached PNG.
 */
public final class TileService {

    private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    private final WorldRegistry worlds;
    private final Path cacheDir;
    private final long cacheMillis;
    private final Logger logger;
    private final RegionRenderer renderer = new RegionRenderer();
    private final ConcurrentMap<String, Object> tileLocks = new ConcurrentHashMap<>();

    public TileService(WorldRegistry worlds, Path cacheDir, int cacheSeconds, Logger logger) {
        this.worlds = worlds;
        this.cacheDir = cacheDir;
        this.cacheMillis = Math.max(0, cacheSeconds) * 1000L;
        this.logger = logger;
    }

    /**
     * Returns the PNG bytes for the given tile, rendering it from the
     * region file if needed. Returns null when the region doesn't exist.
     */
    public byte[] getTile(String worldName, int regionX, int regionZ) {
        WorldRegistry.WorldInfo world = worlds.get(worldName);
        if (world == null) {
            return null;
        }
        Path regionFile = world.regionDir.resolve("r." + regionX + "." + regionZ + ".mca");
        if (!Files.isRegularFile(regionFile)) {
            return null;
        }
        Path tileFile = tilePath(worldName, regionX, regionZ);

        String key = worldName + "/" + regionX + "/" + regionZ;
        Object lock = tileLocks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            try {
                if (isFresh(tileFile, regionFile)) {
                    return Files.readAllBytes(tileFile);
                }
                BufferedImage image = renderer.render(regionFile);
                if (image == null) {
                    return null;
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
                ImageIO.write(image, "png", out);
                byte[] png = out.toByteArray();
                writeAtomically(tileFile, png);
                return png;
            } catch (IOException e) {
                logger.log(Level.WARNING, "Failed to render tile " + key, e);
                return null;
            } finally {
                tileLocks.remove(key, lock);
            }
        }
    }

    /**
     * Renders every region of a world, invoking the callback with
     * (done, total) after each tile. Intended to run off the main thread.
     */
    public int renderAll(String worldName, BiConsumer<Integer, Integer> progress) throws IOException {
        WorldRegistry.WorldInfo world = worlds.get(worldName);
        if (world == null) {
            return -1;
        }
        java.util.List<int[]> regions = new java.util.ArrayList<>();
        try (var stream = Files.list(world.regionDir)) {
            stream.forEach(path -> {
                Matcher m = REGION_FILE.matcher(path.getFileName().toString());
                if (m.matches()) {
                    regions.add(new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))});
                }
            });
        }
        int done = 0;
        for (int[] region : regions) {
            getTile(worldName, region[0], region[1]);
            done++;
            progress.accept(done, regions.size());
        }
        return regions.size();
    }

    private boolean isFresh(Path tileFile, Path regionFile) throws IOException {
        if (!Files.isRegularFile(tileFile)) {
            return false;
        }
        long tileTime = Files.getLastModifiedTime(tileFile).toMillis();
        long regionTime = Files.getLastModifiedTime(regionFile).toMillis();
        if (tileTime >= regionTime) {
            return true;
        }
        // Region changed: keep serving the stale tile briefly to avoid
        // re-rendering on every request while the server is saving.
        return System.currentTimeMillis() - tileTime < cacheMillis;
    }

    private Path tilePath(String worldName, int regionX, int regionZ) {
        return cacheDir.resolve(sanitize(worldName)).resolve(regionX + "_" + regionZ + ".png");
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }

    private static void writeAtomically(Path target, byte[] data) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, data);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }
}
