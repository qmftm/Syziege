package com.syziege.war;

import com.syziege.region.RegionStore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Slime;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Spawns and tracks each region core as an invisible, AI-less slime that
 * players hit to contest the core. Core slimes exist only during war time;
 * outside war they are removed. The slimes are non-persistent and are
 * respawned by a periodic sweep whenever their chunk is loaded, so they never
 * pile up across restarts.
 */
public final class CoreEntityManager {

    public static final String CORE_TAG = "syziege-core";

    private final Plugin plugin;
    private final RegionStore regions;
    private final WarSchedule warSchedule;
    private final Map<String, UUID> byType = new HashMap<>();
    private final Map<UUID, String> byEntity = new HashMap<>();
    private BukkitTask task;

    public CoreEntityManager(Plugin plugin, RegionStore regions, WarSchedule warSchedule) {
        this.plugin = plugin;
        this.regions = regions;
        this.warSchedule = warSchedule;
    }

    public void start() {
        spawnAll();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::spawnAll, 100L, 100L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        removeAll();
    }

    private static String tagFor(String typeId) {
        return CORE_TAG + "-" + typeId;
    }

    /**
     * During war time, ensures a slime exists for every core and clears cores
     * that are gone; outside war time, removes all core slimes.
     */
    public synchronized void spawnAll() {
        if (!warSchedule.active()) {
            removeAll();
            return;
        }
        for (String typeId : regions.coreTypeIds()) {
            RegionStore.Core core = regions.getCore(typeId);
            if (core != null) {
                ensure(typeId, core);
            }
        }
        byType.keySet().removeIf(typeId -> {
            if (regions.getCore(typeId) == null) {
                despawn(typeId);
                return true;
            }
            return false;
        });
    }

    /** Respawns the slime for a core that was just placed or moved (war time only). */
    public synchronized void onCoreMoved(String typeId) {
        despawn(typeId);
        RegionStore.Core core = regions.getCore(typeId);
        if (core != null && warSchedule.active()) {
            ensure(typeId, core);
        }
    }

    public String typeOf(Entity entity) {
        String tracked = byEntity.get(entity.getUniqueId());
        if (tracked != null) {
            return tracked;
        }
        for (String tag : entity.getScoreboardTags()) {
            if (tag.startsWith(CORE_TAG + "-")) {
                return tag.substring(CORE_TAG.length() + 1);
            }
        }
        return null;
    }

    private void ensure(String typeId, RegionStore.Core core) {
        UUID existing = byType.get(typeId);
        if (existing != null) {
            Entity entity = Bukkit.getEntity(existing);
            if (entity != null && entity.isValid()) {
                return;
            }
            byEntity.remove(existing);
            byType.remove(typeId);
        }
        World world = Bukkit.getWorld(core.world);
        if (world == null || !world.isChunkLoaded(core.x >> 4, core.z >> 4)) {
            return; // spawn later, once the chunk is loaded
        }
        Location loc = new Location(world, core.x + 0.5, core.y, core.z + 0.5);
        for (Entity entity : world.getNearbyEntities(loc, 2, 2, 2)) {
            if (entity.getScoreboardTags().contains(tagFor(typeId))) {
                track(typeId, entity.getUniqueId());
                return; // reuse a slime from a previous sweep
            }
        }
        Slime slime = world.spawn(loc, Slime.class, s -> configure(s, typeId));
        track(typeId, slime.getUniqueId());
    }

    private void configure(Slime slime, String typeId) {
        slime.setSize(2);
        slime.setAI(false);
        slime.setSilent(true);
        slime.setInvisible(true);
        slime.setInvulnerable(false);
        slime.setCollidable(false);
        slime.setGravity(false);
        slime.setPersistent(false);
        slime.setRemoveWhenFarAway(true);
        slime.addScoreboardTag(CORE_TAG);
        slime.addScoreboardTag(tagFor(typeId));
    }

    private void track(String typeId, UUID id) {
        byType.put(typeId, id);
        byEntity.put(id, typeId);
    }

    private void despawn(String typeId) {
        UUID id = byType.remove(typeId);
        if (id != null) {
            byEntity.remove(id);
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) {
                entity.remove();
            }
        }
    }

    private void removeAll() {
        for (UUID id : new ArrayList<>(byEntity.keySet())) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) {
                entity.remove();
            }
        }
        byType.clear();
        byEntity.clear();
    }
}
