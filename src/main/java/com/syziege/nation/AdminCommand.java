package com.syziege.nation;

import com.syziege.region.RegionStore;
import com.syziege.war.CoreEntityManager;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
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
    private final RegionStore regions;
    private final CoreEntityManager coreEntities;

    public AdminCommand(NationStore store, RegionStore regions, CoreEntityManager coreEntities) {
        this.store = store;
        this.regions = regions;
        this.coreEntities = coreEntities;
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
            case "setcore": case "코어설정": case "코어":
                return setCore(sender, args);
            default:
                usage(sender);
                return true;
        }
    }

    private boolean setCore(CommandSender sender, String[] args) {
        if (regions == null) {
            send(sender, "§c웹맵/지역 기능이 비활성화되어 있습니다.");
            return true;
        }
        if (!(sender instanceof Player)) {
            send(sender, "§c이 명령어는 게임 내에서만 사용할 수 있습니다.");
            return true;
        }
        if (args.length < 2) {
            send(sender, "§c사용법: §f/admin setcore <지역명>");
            return true;
        }
        Player player = (Player) sender;
        String name = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
        RegionStore.RegionType type = regions.typeByName(name);
        if (type == null) {
            send(sender, "§c지역을 찾을 수 없습니다: §f" + name
                    + " §7(관리자 웹에서 지역 종류를 먼저 만드세요)");
            return true;
        }
        Location loc = player.getLocation();
        String world = loc.getWorld().getName();
        int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
        regions.setCore(type.id, world, x, y, z);
        coreEntities.onCoreMoved(type.id);
        RegionStore.Core core = regions.getCore(type.id);
        send(sender, "§a지역 §e" + type.name + "§a 의 점령 코어를 현재 위치로 설정했습니다.");
        send(sender, "§7위치: §f" + world + " (" + x + ", " + y + ", " + z + ")");
        send(sender, "§7체력: §f" + core.health + " §7/ 소유 국가: §f"
                + (core.owner == null ? "없음" : core.owner));
        send(sender, "§8세부 설정은 §7plugins/Syziege/webmap/cores/" + type.id + ".yml §8에서 편집할 수 있습니다.");

        String claimedType = regions.claimAt(world, x >> 4, z >> 4);
        if (!type.id.equals(claimedType)) {
            send(sender, "§e주의: §7이 청크는 해당 지역으로 지정되어 있지 않습니다. "
                    + "관리자 웹에서 청크를 먼저 지정하세요.");
        }
        return true;
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
        send(sender, "§e/admin setcore <지역명> §7- 현재 위치를 지역 점령 코어로 설정");
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
            return filter(Arrays.asList("국가삭제", "강제탈퇴", "setcore"), args[0]);
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
            if ((sub.equals("setcore") || sub.equals("코어설정") || sub.equals("코어")) && regions != null) {
                return filter(regions.typeNames(), args[1]);
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
