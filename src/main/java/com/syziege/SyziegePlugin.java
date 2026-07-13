package com.syziege;

import com.syziege.nation.AdminCommand;
import com.syziege.nation.NationCommand;
import com.syziege.nation.NationListener;
import com.syziege.nation.NationStore;
import com.syziege.region.RegionStore;
import com.syziege.war.CoreCombatListener;
import com.syziege.webmap.PlayerTracker;
import com.syziege.webmap.ServerStateTracker;
import com.syziege.webmap.TileService;
import com.syziege.webmap.WebAuth;
import com.syziege.webmap.WebServer;
import com.syziege.webmap.WorldRegistry;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.logging.Level;

public final class SyziegePlugin extends JavaPlugin implements Listener {

    private WorldRegistry worldRegistry;
    private TileService tileService;
    private PlayerTracker playerTracker;
    private ServerStateTracker serverStateTracker;
    private RegionStore regionStore;
    private NationStore nationStore;
    private CoreCombatListener coreCombat;
    private WebAuth webAuth;
    private WebServer webServer;
    private String adminKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // Nations and regions work independently of the web map, so set them
        // up first; the admin command needs the region store for setcore.
        nationStore = new NationStore(
                getDataFolder().toPath().resolve("nations.json"), getLogger());
        nationStore.load();
        regionStore = new RegionStore(
                getDataFolder().toPath().resolve("webmap").resolve("regions.json"),
                getLogger());
        regionStore.load();

        NationCommand nationCommand = new NationCommand(nationStore);
        getCommand("국가").setExecutor(nationCommand);
        getCommand("국가").setTabCompleter(nationCommand);
        AdminCommand adminCommand = new AdminCommand(nationStore, regionStore);
        getCommand("admin").setExecutor(adminCommand);
        getCommand("admin").setTabCompleter(adminCommand);
        Bukkit.getPluginManager().registerEvents(new NationListener(nationStore), this);

        coreCombat = new CoreCombatListener(this, regionStore, nationStore,
                getConfig().getInt("core.max-health", 100),
                getConfig().getInt("core.damage-per-hit", 5),
                getConfig().getLong("core.hit-cooldown-ms", 400),
                getConfig().getBoolean("core.require-nation", true),
                getConfig().getInt("core.regen-delay-seconds", 30),
                getConfig().getInt("core.regen-per-second", 2));
        Bukkit.getPluginManager().registerEvents(coreCombat, this);
        coreCombat.start();

        if (!getConfig().getBoolean("webmap.enabled", true)) {
            getLogger().info("Web map is disabled in config.yml");
            return;
        }

        worldRegistry = new WorldRegistry(getConfig().getStringList("webmap.worlds"));
        for (World world : Bukkit.getWorlds()) {
            worldRegistry.register(world);
        }
        Bukkit.getPluginManager().registerEvents(this, this);

        tileService = new TileService(
                worldRegistry,
                getDataFolder().toPath().resolve("webmap").resolve("tiles"),
                getConfig().getInt("webmap.tile-cache-seconds", 30),
                getLogger());

        playerTracker = new PlayerTracker();
        playerTracker.start(this);

        serverStateTracker = new ServerStateTracker();
        serverStateTracker.start(this);

        webAuth = new WebAuth(getDataFolder().toPath().resolve("webmap").resolve("webusers.json"), getLogger());
        webAuth.load();

        adminKey = resolveAdminKey();

        webServer = new WebServer(
                worldRegistry,
                tileService,
                playerTracker,
                serverStateTracker,
                regionStore,
                nationStore,
                webAuth,
                this::getResource,
                adminKey,
                getLogger());

        String bind = getConfig().getString("webmap.bind", "0.0.0.0");
        int port = getConfig().getInt("webmap.port", 8123);
        getCommand("webmap").setExecutor(new WebmapCommand(webAuth, port));
        getCommand("webmap").setTabCompleter(new WebmapCommand(webAuth, port));
        try {
            webServer.start(bind, port);
            getLogger().info("User map:  http://<server>:" + port + "/");
            getLogger().info("Admin map: http://<server>:" + port + "/admin  (key: " + adminKey + ")");
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Failed to start web map server on " + bind + ":" + port, e);
            webServer = null;
        }

        SyziegeCommand command = new SyziegeCommand(this);
        getCommand("syziege").setExecutor(command);
        getCommand("syziege").setTabCompleter(command);
    }

    @Override
    public void onDisable() {
        if (webServer != null) {
            webServer.stop();
            webServer = null;
        }
        if (serverStateTracker != null) {
            serverStateTracker.stop();
            serverStateTracker = null;
        }
        if (playerTracker != null) {
            playerTracker.stop();
            playerTracker = null;
        }
        if (coreCombat != null) {
            coreCombat.stop(); // cancels regen task and flushes core health to disk
            coreCombat = null;
        }
    }

    /**
     * Returns the configured admin key, generating and persisting a random one
     * on first run so the admin editor is never wide open by default.
     */
    private String resolveAdminKey() {
        String key = getConfig().getString("webmap.admin-key", "");
        if (key != null && !key.isBlank()) {
            return key.trim();
        }
        String generated = randomKey();
        getConfig().set("webmap.admin-key", generated);
        saveConfig();
        getLogger().info("Generated a web map admin key (stored in config.yml).");
        return generated;
    }

    private static String randomKey() {
        String alphabet = "abcdefghijkmnpqrstuvwxyz23456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        if (worldRegistry != null) {
            worldRegistry.register(event.getWorld());
        }
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        if (worldRegistry != null) {
            worldRegistry.unregister(event.getWorld().getName());
        }
    }

    public WorldRegistry worldRegistry() {
        return worldRegistry;
    }

    public TileService tileService() {
        return tileService;
    }

    public String adminKey() {
        return adminKey;
    }

    public NationStore nationStore() {
        return nationStore;
    }
}
