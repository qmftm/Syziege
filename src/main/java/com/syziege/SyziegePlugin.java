package com.syziege;

import com.syziege.webmap.PlayerTracker;
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
import java.util.logging.Level;

public final class SyziegePlugin extends JavaPlugin implements Listener {

    private WorldRegistry worldRegistry;
    private TileService tileService;
    private PlayerTracker playerTracker;
    private WebServer webServer;

    @Override
    public void onEnable() {
        saveDefaultConfig();

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

        webServer = new WebServer(
                worldRegistry,
                tileService,
                playerTracker,
                () -> getResource("web/index.html"),
                getLogger());

        String bind = getConfig().getString("webmap.bind", "0.0.0.0");
        int port = getConfig().getInt("webmap.port", 8123);
        try {
            webServer.start(bind, port);
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
        if (playerTracker != null) {
            playerTracker.stop();
            playerTracker = null;
        }
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
}
