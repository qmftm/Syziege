package com.syziege.war;

import com.syziege.nation.Nation;
import com.syziege.nation.NationStore;
import com.syziege.region.RegionStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Capture combat: members of a nation that doesn't own a region hit its core
 * slime to drain the core's health, shown on a boss bar to nearby players.
 * When it reaches zero, ownership transfers to the attacking nation, the health
 * resets, and the capture is announced. Untouched cores slowly regenerate.
 */
public final class CoreCombatListener implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final double BAR_RANGE = 48.0;

    private final Plugin plugin;
    private final RegionStore regions;
    private final NationStore nations;
    private final CoreEntityManager coreEntities;

    private final int maxHealth;
    private final int damagePerHit;
    private final long hitCooldownMs;
    private final boolean requireNation;
    private final int regenDelaySeconds;
    private final int regenPerSecond;

    private final Map<String, Long> lastHit = new HashMap<>();
    private final Map<UUID, Long> playerCooldown = new HashMap<>();
    private final Map<String, BossBar> bars = new HashMap<>();
    private BukkitTask tickTask;

    public CoreCombatListener(Plugin plugin, RegionStore regions, NationStore nations,
                              CoreEntityManager coreEntities, int maxHealth, int damagePerHit,
                              long hitCooldownMs, boolean requireNation,
                              int regenDelaySeconds, int regenPerSecond) {
        this.plugin = plugin;
        this.regions = regions;
        this.nations = nations;
        this.coreEntities = coreEntities;
        this.maxHealth = Math.max(1, maxHealth);
        this.damagePerHit = Math.max(1, damagePerHit);
        this.hitCooldownMs = Math.max(0, hitCooldownMs);
        this.requireNation = requireNation;
        this.regenDelaySeconds = Math.max(0, regenDelaySeconds);
        this.regenPerSecond = Math.max(0, regenPerSecond);
    }

    public void start() {
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        for (BossBar bar : bars.values()) {
            bar.removeAll();
        }
        bars.clear();
        regions.saveAllCores();
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        Entity victim = event.getEntity();
        if (!victim.getScoreboardTags().contains(CoreEntityManager.CORE_TAG)) {
            return;
        }
        event.setCancelled(true); // the core slime never takes real damage

        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null) {
            return;
        }
        String typeId = coreEntities.typeOf(victim);
        if (typeId == null) {
            return;
        }

        long now = System.currentTimeMillis();
        Long cooldown = playerCooldown.get(attacker.getUniqueId());
        if (cooldown != null && now - cooldown < hitCooldownMs) {
            return;
        }
        playerCooldown.put(attacker.getUniqueId(), now);

        Nation nation = nations.byPlayer(attacker.getUniqueId());
        if (requireNation && nation == null) {
            attacker.sendActionBar(LEGACY.deserialize("§c국가에 소속되어야 코어를 점령할 수 있습니다"));
            return;
        }
        String attackerNation = nation == null ? null : nation.name();

        RegionStore.Core core = regions.getCore(typeId);
        if (core == null) {
            return;
        }
        if (core.owner != null && core.owner.equals(attackerNation)) {
            attacker.sendActionBar(LEGACY.deserialize("§e이미 우리 국가가 소유한 지역입니다"));
            return;
        }

        String regionName = regions.typeName(typeId);
        int health = regions.damageCore(typeId, damagePerHit);
        lastHit.put(typeId, now);

        if (health <= 0) {
            String previous = core.owner;
            regions.captureCore(typeId, attackerNation, maxHealth);
            announceCapture(typeId, regionName, attackerNation, previous);
        } else {
            updateBar(typeId, core, regionName, BarColor.RED,
                    "§e" + regionName + " §f코어 §7" + health + "/" + maxHealth
                            + (core.owner == null ? "" : " §8· 소유 " + core.owner));
        }
    }

    private Player resolveAttacker(Entity damager) {
        if (damager instanceof Player) {
            return (Player) damager;
        }
        if (damager instanceof Projectile) {
            ProjectileSource shooter = ((Projectile) damager).getShooter();
            if (shooter instanceof Player) {
                return (Player) shooter;
            }
        }
        return null;
    }

    private void announceCapture(String typeId, String regionName, String attacker, String previousOwner) {
        String previous = previousOwner == null ? "무소속" : previousOwner;
        Bukkit.getServer().sendMessage(LEGACY.deserialize(
                "§6[국가전쟁] §e" + attacker + "§f 국가가 §e" + regionName
                        + "§f 지역의 코어를 점령했습니다! §7(이전 소유: " + previous + ")"));
        RegionStore.Core core = regions.getCore(typeId);
        if (core != null) {
            updateBar(typeId, core, regionName, BarColor.GREEN,
                    "§a" + attacker + " 국가가 " + regionName + " 코어 점령!");
        }
    }

    /** Updates (creating if needed) the core's boss bar and its nearby viewers. */
    private void updateBar(String typeId, RegionStore.Core core, String regionName,
                           BarColor color, String title) {
        BossBar bar = bars.computeIfAbsent(typeId,
                k -> Bukkit.createBossBar(title, color, BarStyle.SEGMENTED_10));
        bar.setTitle(title);
        bar.setColor(color);
        bar.setProgress(clamp01(core.health / (double) maxHealth));
        refreshViewers(bar, core);
    }

    private void refreshViewers(BossBar bar, RegionStore.Core core) {
        World world = Bukkit.getWorld(core.world);
        if (world == null) {
            bar.removeAll();
            return;
        }
        Location center = new Location(world, core.x + 0.5, core.y, core.z + 0.5);
        bar.removeAll();
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(center) <= BAR_RANGE * BAR_RANGE) {
                bar.addPlayer(player);
            }
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (String typeId : regions.coreTypeIds()) {
            RegionStore.Core core = regions.getCore(typeId);
            if (core == null) {
                dropBar(typeId);
                continue;
            }
            Long last = lastHit.get(typeId);

            if (regenPerSecond > 0 && last != null && core.health < maxHealth
                    && now - last >= regenDelaySeconds * 1000L) {
                regions.healCore(typeId, regenPerSecond, maxHealth);
            }

            BossBar bar = bars.get(typeId);
            if (bar != null) {
                boolean idle = last == null || now - last > 4000L;
                if (core.health >= maxHealth && idle) {
                    dropBar(typeId);
                } else {
                    bar.setProgress(clamp01(core.health / (double) maxHealth));
                    refreshViewers(bar, core);
                }
            }
        }
        // Forget cores that no longer exist.
        bars.keySet().removeIf(id -> regions.getCore(id) == null);
    }

    private void dropBar(String typeId) {
        BossBar bar = bars.remove(typeId);
        if (bar != null) {
            bar.removeAll();
        }
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }
}
