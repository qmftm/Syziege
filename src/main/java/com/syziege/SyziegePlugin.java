package com.syziege;

import com.syziege.nation.NationCommand;
import com.syziege.nation.NationListener;
import com.syziege.nation.NationStore;
import com.syziege.region.RegionStore;
import com.syziege.webmap.PlayerTracker;
import com.syziege.webmap.ServerStateTracker;
import com.syziege.webmap.TileService;
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
    private WebServer webServer;
    private String adminKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // Nations work independently of the web map, so set them up first.
        nationStore = new NationStore(
                getDataFolder().toPath().resolve("nations.json"), getLogger());
        nationStore.load();
        NationCommand nationCommand = new NationCommand(nationStore);
        getCommand("국가").setExecutor(nationCommand);
        getCommand("국가").setTabCompleter(nationCommand);
        Bukkit.getPluginManager().registerEvents(new NationListener(nationStore), this);

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

        regionStore = new RegionStore(
                getDataFolder().toPath().resolve("webmap").resolve("regions.json"),
                getLogger());
        regionStore.load();

        adminKey = resolveAdminKey();

        webServer = new WebServer(
                worldRegistry,
                tileService,
                playerTracker,
                serverStateTracker,
                regionStore,
                this::getResource,
                adminKey,
                getLogger());

        String bind = getConfig().getString("webmap.bind", "0.0.0.0");
        int port = getConfig().getInt("webmap.port", 8123);
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
