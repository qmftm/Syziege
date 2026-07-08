package com.syziege;

import com.syziege.webmap.WorldRegistry;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SyziegeCommand implements CommandExecutor, TabCompleter {

    private final SyziegePlugin plugin;
    private final AtomicBoolean rendering = new AtomicBoolean(false);

    public SyziegeCommand(SyziegePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            return false;
        }
        switch (args[0].toLowerCase()) {
            case "render":
                return handleRender(sender, args);
            case "status":
                return handleStatus(sender);
            case "admin":
                return handleAdmin(sender);
            default:
                return false;
        }
    }

    private boolean handleAdmin(CommandSender sender) {
        if (plugin.adminKey() == null) {
            sender.sendMessage("§c웹맵이 비활성화되어 있습니다.");
            return true;
        }
        int port = plugin.getConfig().getInt("webmap.port", 8123);
        sender.sendMessage("§6=== Syziege 관리자 지도 ===");
        sender.sendMessage("§7주소: §fhttp://<서버주소>:" + port + "/admin");
        sender.sendMessage("§7관리자 키: §e" + plugin.adminKey());
        sender.sendMessage("§8이 키가 있어야 지역을 편집할 수 있습니다. 외부에 노출하지 마세요.");
        return true;
    }

    private boolean handleRender(CommandSender sender, String[] args) {
        if (plugin.tileService() == null) {
            sender.sendMessage("§c웹맵이 비활성화되어 있습니다.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("§c사용법: /syziege render <월드>");
            return true;
        }
        String worldName = args[1];
        if (plugin.worldRegistry().get(worldName) == null) {
            sender.sendMessage("§c웹맵에 등록되지 않은 월드입니다: " + worldName);
            return true;
        }
        if (!rendering.compareAndSet(false, true)) {
            sender.sendMessage("§c이미 렌더링이 진행 중입니다.");
            return true;
        }
        sender.sendMessage("§e[" + worldName + "] 전체 렌더링을 시작합니다...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                int total = plugin.tileService().renderAll(worldName, (done, count) -> {
                    if (done % 10 == 0 || done == count) {
                        sender.sendMessage("§7[" + worldName + "] 렌더링 " + done + "/" + count);
                    }
                });
                sender.sendMessage("§a[" + worldName + "] 렌더링 완료 (리전 " + total + "개)");
            } catch (IOException e) {
                sender.sendMessage("§c렌더링 실패: " + e.getMessage());
            } finally {
                rendering.set(false);
            }
        });
        return true;
    }

    private boolean handleStatus(CommandSender sender) {
        if (plugin.worldRegistry() == null) {
            sender.sendMessage("§c웹맵이 비활성화되어 있습니다.");
            return true;
        }
        int port = plugin.getConfig().getInt("webmap.port", 8123);
        sender.sendMessage("§6=== Syziege 웹맵 ===");
        sender.sendMessage("§7포트: §f" + port);
        for (WorldRegistry.WorldInfo world : plugin.worldRegistry().all()) {
            sender.sendMessage("§7월드: §f" + world.name + " §7(스폰 " + world.spawnX + ", " + world.spawnZ + ")");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(Arrays.asList("render", "status", "admin"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("render") && plugin.worldRegistry() != null) {
            List<String> names = new ArrayList<>();
            for (WorldRegistry.WorldInfo world : plugin.worldRegistry().all()) {
                names.add(world.name);
            }
            return filter(names, args[1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        List<String> result = new ArrayList<>();
        String lower = prefix.toLowerCase();
        for (String option : options) {
            if (option.toLowerCase().startsWith(lower)) {
                result.add(option);
            }
        }
        return result;
    }
}
