package com.syziege.webmap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Periodically snapshots online player positions on the main thread so
 * HTTP threads can serve them without touching the Bukkit API.
 */
public final class PlayerTracker {

    public static final class PlayerInfo {
        public final UUID uuid;
        public final String name;
        public final String world;
        public final int x;
        public final int y;
        public final int z;

        PlayerInfo(UUID uuid, String name, String world, int x, int y, int z) {
            this.uuid = uuid;
            this.name = name;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private volatile List<PlayerInfo> snapshot = Collections.emptyList();
    private BukkitTask task;

    public void start(Plugin plugin) {
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            List<PlayerInfo> players = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                Location loc = player.getLocation();
                players.add(new PlayerInfo(player.getUniqueId(), player.getName(), loc.getWorld().getName(),
                        loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
            }
            snapshot = players;
        }, 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        snapshot = Collections.emptyList();
    }

    public List<PlayerInfo> players() {
        return snapshot;
    }
}
