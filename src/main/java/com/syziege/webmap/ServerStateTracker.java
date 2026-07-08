package com.syziege.webmap;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Snapshots each world's time and weather on the main thread so HTTP threads
 * can serve them for the user view's clock and weather readout.
 */
public final class ServerStateTracker {

    public static final class WorldState {
        public final String world;
        public final long timeOfDay;   // 0..23999
        public final long day;         // full days elapsed
        public final String weather;   // clear | rain | thunder
        public final int playerCount;

        WorldState(String world, long timeOfDay, long day, String weather, int playerCount) {
            this.world = world;
            this.timeOfDay = timeOfDay;
            this.day = day;
            this.weather = weather;
            this.playerCount = playerCount;
        }
    }

    private volatile List<WorldState> snapshot = Collections.emptyList();
    private BukkitTask task;

    public void start(Plugin plugin) {
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            List<WorldState> states = new ArrayList<>();
            for (World world : Bukkit.getWorlds()) {
                String weather = world.isThundering() ? "thunder"
                        : world.hasStorm() ? "rain" : "clear";
                states.add(new WorldState(
                        world.getName(),
                        world.getTime(),
                        world.getFullTime() / 24000L,
                        weather,
                        world.getPlayers().size()));
            }
            snapshot = states;
        }, 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        snapshot = Collections.emptyList();
    }

    public List<WorldState> states() {
        return snapshot;
    }
}
