package com.syziege.war;

import com.syziege.nation.Nation;
import com.syziege.nation.NationStore;
import com.syziege.region.RegionStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Capture combat for region cores. Members of a nation that does not own a
 * region hit its core block to drain its health; when it reaches zero the
 * region's ownership transfers to the attacking nation and the health resets.
 * Cores that are left alone slowly regenerate.
 */
public final class CoreCombatListener implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final Plugin plugin;
    private final RegionStore regions;
    private final NationStore nations;

    private final int maxHealth;
    private final int damagePerHit;
    private final long hitCooldownMs;
    private final boolean requireNation;
    private final int regenDelaySeconds;
    private final int regenPerSecond;

    private final Map<String, Long> lastHit = new HashMap<>();      // core type id -> epoch ms
    private final Map<UUID, Long> playerCooldown = new HashMap<>();
    private BukkitTask regenTask;

    public CoreCombatListener(Plugin plugin, RegionStore regions, NationStore nations,
                              int maxHealth, int damagePerHit, long hitCooldownMs,
                              boolean requireNation, int regenDelaySeconds, int regenPerSecond) {
        this.plugin = plugin;
        this.regions = regions;
        this.nations = nations;
        this.maxHealth = Math.max(1, maxHealth);
        this.damagePerHit = Math.max(1, damagePerHit);
        this.hitCooldownMs = Math.max(0, hitCooldownMs);
        this.requireNation = requireNation;
        this.regenDelaySeconds = Math.max(0, regenDelaySeconds);
        this.regenPerSecond = Math.max(0, regenPerSecond);
    }

    public void start() {
        if (regenPerSecond > 0) {
            regenTask = Bukkit.getScheduler().runTaskTimer(plugin, this::regenTick, 20L, 20L);
        }
    }

    public void stop() {
        if (regenTask != null) {
            regenTask.cancel();
            regenTask = null;
        }
        regions.saveAllCores();
    }

    @EventHandler
    public void onHit(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        String typeId = regions.coreAt(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        if (typeId == null) {
            return;
        }
        event.setCancelled(true); // the core block must not be broken

        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        Long cooldown = playerCooldown.get(player.getUniqueId());
        if (cooldown != null && now - cooldown < hitCooldownMs) {
            return;
        }
        playerCooldown.put(player.getUniqueId(), now);

        Nation nation = nations.byPlayer(player.getUniqueId());
        if (requireNation && nation == null) {
            actionBar(player, "§c국가에 소속되어야 코어를 점령할 수 있습니다");
            return;
        }
        String attacker = nation == null ? null : nation.name();

        RegionStore.Core core = regions.getCore(typeId);
        if (core == null) {
            return;
        }
        if (core.owner != null && core.owner.equals(attacker)) {
            actionBar(player, "§e이미 우리 국가가 소유한 지역입니다");
            return;
        }

        String regionName = regions.typeName(typeId);
        int health = regions.damageCore(typeId, damagePerHit);
        lastHit.put(typeId, now);

        if (health <= 0) {
            String previous = core.owner;
            regions.captureCore(typeId, attacker, maxHealth);
            lastHit.remove(typeId);
            announceCapture(regionName, attacker, previous);
        } else {
            actionBar(player, "§c" + regionName + " §f코어 §e" + health + "§7/" + maxHealth
                    + " §8(-" + damagePerHit + ")");
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (regions.coreAt(block.getWorld().getName(), block.getX(), block.getY(), block.getZ()) != null) {
            event.setCancelled(true);
        }
    }

    private void regenTick() {
        long now = System.currentTimeMillis();
        for (String typeId : regions.coreTypeIds()) {
            Long last = lastHit.get(typeId);
            if (last == null) {
                continue;
            }
            if (now - last >= regenDelaySeconds * 1000L) {
                int health = regions.healCore(typeId, regenPerSecond, maxHealth);
                if (health >= maxHealth) {
                    lastHit.remove(typeId);
                }
            }
        }
    }

    private void announceCapture(String regionName, String attacker, String previousOwner) {
        String previous = previousOwner == null ? "무소속" : previousOwner;
        Component message = LEGACY.deserialize(
                "§6[국가전쟁] §e" + attacker + "§f 국가가 §e" + regionName
                        + "§f 지역의 코어를 점령했습니다! §7(이전 소유: " + previous + ")");
        Bukkit.getServer().sendMessage(message);
    }

    private void actionBar(Player player, String legacy) {
        player.sendActionBar(LEGACY.deserialize(legacy));
    }
}
