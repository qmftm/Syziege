package com.syziege.webmap;

import org.bukkit.Location;
import org.bukkit.World;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe snapshot of the worlds exposed on the web map. Populated on
 * the main thread (startup + world load events) and read from HTTP threads.
 */
public final class WorldRegistry {

    public static final class WorldInfo {
        public final String name;
        public final Path regionDir;
        public final int spawnX;
        public final int spawnZ;

        WorldInfo(String name, Path regionDir, int spawnX, int spawnZ) {
            this.name = name;
            this.regionDir = regionDir;
            this.spawnX = spawnX;
            this.spawnZ = spawnZ;
        }
    }

    private final Map<String, WorldInfo> worlds = new ConcurrentHashMap<>();
    private final List<String> allowedWorlds;

    public WorldRegistry(List<String> allowedWorlds) {
        this.allowedWorlds = allowedWorlds;
    }

    /** Must be called on the main thread. */
    public void register(World world) {
        if (!allowedWorlds.isEmpty() && !allowedWorlds.contains(world.getName())) {
            return;
        }
        Path regionDir = resolveRegionDir(world);
        if (regionDir == null) {
            return;
        }
        Location spawn = world.getSpawnLocation();
        worlds.put(world.getName(),
                new WorldInfo(world.getName(), regionDir, spawn.getBlockX(), spawn.getBlockZ()));
    }

    public void unregister(String worldName) {
        worlds.remove(worldName);
    }

    public WorldInfo get(String name) {
        return worlds.get(name);
    }

    public List<WorldInfo> all() {
        return new ArrayList<>(worlds.values());
    }

    private static Path resolveRegionDir(World world) {
        Path folder = world.getWorldFolder().toPath();
        Path[] candidates;
        switch (world.getEnvironment()) {
            case NETHER:
                candidates = new Path[]{folder.resolve("DIM-1").resolve("region"), folder.resolve("region")};
                break;
            case THE_END:
                candidates = new Path[]{folder.resolve("DIM1").resolve("region"), folder.resolve("region")};
                break;
            default:
                candidates = new Path[]{folder.resolve("region")};
                break;
        }
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
