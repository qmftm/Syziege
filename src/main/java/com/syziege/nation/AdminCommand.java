package com.syziege.nation;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Staff-only moderation for nations: {@code /admin 국가삭제 <국가>} force-disbands
 * a nation and {@code /admin 강제탈퇴 <플레이어>} removes a player from their
 * nation. Gated by the syziege.admin permission.
 */
public final class AdminCommand implements CommandExecutor, TabCompleter {

    private final NationStore store;

    public AdminCommand(NationStore store) {
        this.store = store;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("syziege.admin")) {
            send(sender, "§c권한이 없습니다.");
            return true;
        }
        if (args.length == 0) {
            usage(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "국가삭제": case "deletenation": case "disband":
                return forceDisband(sender, args);
            case "강제탈퇴": case "forcekick": case "kick":
                return forceKick(sender, args);
            default:
                usage(sender);
                return true;
        }
    }

    private boolean forceDisband(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, "§c사용법: §f/admin 국가삭제 <국가>");
            return true;
        }
        Nation nation = store.byName(args[1]);
        if (nation == null) {
            send(sender, "§c해당 국가를 찾을 수 없습니다: " + args[1]);
            return true;
        }
        String name = nation.name();
        for (UUID member : new ArrayList<>(nation.members().keySet())) {
            Player online = Bukkit.getPlayer(member);
            if (online != null) {
                send(online, "§c국가 §e" + name + "§c 이(가) 관리자에 의해 삭제되었습니다.");
            }
        }
        store.disband(nation);
        send(sender, "§a국가 §e" + name + "§a 을(를) 강제로 삭제했습니다. (국가원 " + nation.size() + "명 해산)");
        return true;
    }

    private boolean forceKick(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, "§c사용법: §f/admin 강제탈퇴 <플레이어>");
            return true;
        }
        String targetName = args[1];

        UUID targetId = null;
        Player online = Bukkit.getPlayerExact(targetName);
        if (online != null) {
            targetId = online.getUniqueId();
        }
        if (targetId == null || store.byPlayer(targetId) == null) {
            UUID byName = store.findMemberByName(targetName);
            if (byName != null) {
                targetId = byName;
            }
        }
        if (targetId == null) {
            send(sender, "§c플레이어를 찾을 수 없습니다: " + targetName);
            return true;
        }

        Nation nation = store.byPlayer(targetId);
        if (nation == null) {
            send(sender, "§c" + targetName + " 님은 국가에 소속되어 있지 않습니다.");
            return true;
        }
        if (nation.isLeader(targetId)) {
            send(sender, "§c" + targetName + " 님은 국가장입니다. §f/admin 국가삭제 " + nation.name()
                    + "§c 를 사용하세요.");
            return true;
        }

        String memberName = nation.members().get(targetId);
        store.removeMember(nation, targetId);
        send(sender, "§a" + memberName + " 님을 국가 §e" + nation.name() + "§a 에서 강제 탈퇴시켰습니다.");

        Player onlineTarget = Bukkit.getPlayer(targetId);
        if (onlineTarget != null) {
            send(onlineTarget, "§c관리자에 의해 국가 §e" + nation.name() + "§c 에서 탈퇴되었습니다.");
        }
        for (UUID member : nation.members().keySet()) {
            Player onlineMember = Bukkit.getPlayer(member);
            if (onlineMember != null) {
                send(onlineMember, "§7" + memberName + " 님이 관리자에 의해 국가에서 제외되었습니다.");
            }
        }
        return true;
    }

    private void usage(CommandSender sender) {
        send(sender, "§6=== 관리자 명령어 ===");
        send(sender, "§e/admin 국가삭제 <국가> §7- 국가를 강제로 해체");
        send(sender, "§e/admin 강제탈퇴 <플레이어> §7- 국가원을 강제로 제외");
    }

    private static void send(CommandSender sender, String legacy) {
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize(legacy));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("syziege.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(Arrays.asList("국가삭제", "강제탈퇴"), args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("국가삭제") || sub.equals("deletenation") || sub.equals("disband")) {
                List<String> names = new ArrayList<>();
                for (Nation nation : store.all()) {
                    names.add(nation.name());
                }
                return filter(names, args[1]);
            }
            if (sub.equals("강제탈퇴") || sub.equals("forcekick") || sub.equals("kick")) {
                List<String> names = new ArrayList<>();
                for (Player online : Bukkit.getOnlinePlayers()) {
                    names.add(online.getName());
                }
                return filter(names, args[1]);
            }
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        List<String> result = new ArrayList<>();
        String lower = prefix.toLowerCase(Locale.ROOT);
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(option);
            }
        }
        return result;
    }
}
