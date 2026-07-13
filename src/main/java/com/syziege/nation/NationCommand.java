package com.syziege.nation;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The {@code /국가} command: create, disband, invite, leave, info and list.
 * Invites are delivered as clickable chat prompts ([수락]/[거절]).
 */
public final class NationCommand implements CommandExecutor, TabCompleter {

    private static final long INVITE_TTL_MILLIS = 120_000L;
    private static final int NAME_MIN = 2;
    private static final int NAME_MAX = 16;

    private final NationStore store;
    // invited player -> (nation key -> expiry epoch millis)
    private final Map<UUID, Map<String, Long>> invites = new HashMap<>();

    public NationCommand(NationStore store) {
        this.store = store;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            usage(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "생성": case "create":
                return create(sender, args);
            case "해체": case "disband":
                return disband(sender);
            case "초대": case "invite":
                return invite(sender, args);
            case "탈퇴": case "leave":
                return leave(sender);
            case "정보": case "info":
                return info(sender, args);
            case "목록": case "list":
                return list(sender);
            case "색상": case "색": case "color":
                return color(sender, args);
            case "수락": case "accept":
                return accept(sender, args);
            case "거절": case "decline":
                return decline(sender, args);
            default:
                usage(sender);
                return true;
        }
    }

    private boolean create(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return true;
        }
        if (args.length < 2) {
            send(sender, "§c사용법: §f/국가 생성 <이름>");
            return true;
        }
        String name = args[1];
        if (name.length() < NAME_MIN || name.length() > NAME_MAX || name.contains("§")) {
            send(sender, "§c국가 이름은 " + NAME_MIN + "~" + NAME_MAX + "자여야 합니다.");
            return true;
        }
        if (store.byPlayer(player.getUniqueId()) != null) {
            send(sender, "§c이미 국가에 소속되어 있습니다. 먼저 §f/국가 탈퇴§c 하세요.");
            return true;
        }
        if (store.exists(name)) {
            send(sender, "§c이미 존재하는 국가 이름입니다.");
            return true;
        }
        store.create(name, player.getUniqueId(), player.getName());
        send(sender, "§a국가 §e" + name + "§a 을(를) 설립했습니다. 당신이 국가장입니다.");
        return true;
    }

    private boolean disband(CommandSender sender) {
        Player player = asPlayer(sender);
        if (player == null) {
            return true;
        }
        Nation nation = store.byPlayer(player.getUniqueId());
        if (nation == null) {
            send(sender, "§c소속된 국가가 없습니다.");
            return true;
        }
        if (!nation.isLeader(player.getUniqueId())) {
            send(sender, "§c국가장만 국가를 해체할 수 있습니다.");
            return true;
        }
        String name = nation.name();
        for (UUID member : new ArrayList<>(nation.members().keySet())) {
            Player online = Bukkit.getPlayer(member);
            if (online != null) {
                send(online, "§c국가 §e" + name + "§c 이(가) 해체되었습니다.");
            }
        }
        store.disband(nation);
        return true;
    }

    private boolean invite(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return true;
        }
        Nation nation = store.byPlayer(player.getUniqueId());
        if (nation == null || !nation.isLeader(player.getUniqueId())) {
            send(sender, "§c국가장만 초대할 수 있습니다.");
            return true;
        }
        if (args.length < 2) {
            send(sender, "§c사용법: §f/국가 초대 <플레이어>");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            send(sender, "§c접속 중인 플레이어를 찾을 수 없습니다: " + args[1]);
            return true;
        }
        if (target.equals(player)) {
            send(sender, "§c자기 자신은 초대할 수 없습니다.");
            return true;
        }
        if (store.byPlayer(target.getUniqueId()) != null) {
            send(sender, "§c" + target.getName() + " 님은 이미 국가에 소속되어 있습니다.");
            return true;
        }
        invites.computeIfAbsent(target.getUniqueId(), u -> new HashMap<>())
                .put(nation.name().toLowerCase(Locale.ROOT), System.currentTimeMillis() + INVITE_TTL_MILLIS);

        send(sender, "§a" + target.getName() + " 님을 §e" + nation.name() + "§a 국가로 초대했습니다.");
        sendInvite(target, nation.name(), player.getName());
        return true;
    }

    private boolean accept(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return true;
        }
        if (args.length < 2) {
            send(sender, "§c사용법: §f/국가 수락 <국가>");
            return true;
        }
        if (store.byPlayer(player.getUniqueId()) != null) {
            send(sender, "§c이미 국가에 소속되어 있습니다.");
            return true;
        }
        if (!hasValidInvite(player.getUniqueId(), args[1])) {
            send(sender, "§c유효한 초대가 없습니다. (초대가 만료되었을 수 있습니다)");
            return true;
        }
        Nation nation = store.byName(args[1]);
        if (nation == null) {
            send(sender, "§c해당 국가가 더 이상 존재하지 않습니다.");
            clearInvite(player.getUniqueId(), args[1]);
            return true;
        }
        store.addMember(nation, player.getUniqueId(), player.getName());
        clearInvite(player.getUniqueId(), args[1]);
        send(sender, "§a국가 §e" + nation.name() + "§a 에 가입했습니다.");
        for (UUID member : nation.members().keySet()) {
            if (member.equals(player.getUniqueId())) {
                continue;
            }
            Player online = Bukkit.getPlayer(member);
            if (online != null) {
                send(online, "§e" + player.getName() + "§a 님이 국가에 가입했습니다.");
            }
        }
        return true;
    }

    private boolean decline(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return true;
        }
        if (args.length < 2) {
            send(sender, "§c사용법: §f/국가 거절 <국가>");
            return true;
        }
        clearInvite(player.getUniqueId(), args[1]);
        send(sender, "§7" + args[1] + " 국가의 초대를 거절했습니다.");
        return true;
    }

    private boolean leave(CommandSender sender) {
        Player player = asPlayer(sender);
        if (player == null) {
            return true;
        }
        Nation nation = store.byPlayer(player.getUniqueId());
        if (nation == null) {
            send(sender, "§c소속된 국가가 없습니다.");
            return true;
        }
        if (nation.isLeader(player.getUniqueId())) {
            send(sender, "§c국가장은 탈퇴할 수 없습니다. 국가를 넘기거나 §f/국가 해체§c 하세요.");
            return true;
        }
        store.removeMember(nation, player.getUniqueId());
        send(sender, "§7국가 §f" + nation.name() + "§7 에서 탈퇴했습니다.");
        for (UUID member : nation.members().keySet()) {
            Player online = Bukkit.getPlayer(member);
            if (online != null) {
                send(online, "§7" + player.getName() + " 님이 국가를 떠났습니다.");
            }
        }
        return true;
    }

    private boolean info(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return true;
        }
        Nation nation = args.length >= 2 ? store.byName(args[1]) : store.byPlayer(player.getUniqueId());
        if (nation == null) {
            send(sender, args.length >= 2 ? "§c해당 국가를 찾을 수 없습니다." : "§c소속된 국가가 없습니다.");
            return true;
        }
        NationGui.openMembers(player, nation);
        return true;
    }

    private boolean color(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return true;
        }
        Nation nation = store.byPlayer(player.getUniqueId());
        if (nation == null || !nation.isLeader(player.getUniqueId())) {
            send(sender, "§c국가장만 국가 색상을 바꿀 수 있습니다.");
            return true;
        }
        if (args.length < 2) {
            send(sender, "§c사용법: §f/국가 색상 <#RRGGBB> §7(예: /국가 색상 #3aa0ff)");
            return true;
        }
        String color = normalizeHex(args[1]);
        if (color == null) {
            send(sender, "§c색상은 #RRGGBB 형식이어야 합니다. (예: #e63946)");
            return true;
        }
        store.setColor(nation, color);
        send(sender, "§a국가 색상을 §f" + color + "§a 으로 변경했습니다. 웹 지도의 영토에 반영됩니다.");
        return true;
    }

    private static String normalizeHex(String input) {
        String c = input.trim();
        if (!c.startsWith("#")) {
            c = "#" + c;
        }
        return c.matches("#[0-9a-fA-F]{6}") ? c.toLowerCase(Locale.ROOT) : null;
    }

    private boolean list(CommandSender sender) {
        Player player = asPlayer(sender);
        if (player == null) {
            return true;
        }
        if (store.all().isEmpty()) {
            send(sender, "§7아직 설립된 국가가 없습니다.");
            return true;
        }
        NationGui.openList(player, store.all());
        return true;
    }

    // ---- helpers ----

    private void sendInvite(Player target, String nationName, String inviter) {
        LegacyComponentSerializer legacy = LegacyComponentSerializer.legacySection();
        Component accept = Component.text(" [수락] ", NamedTextColor.GREEN, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/국가 수락 " + nationName))
                .hoverEvent(HoverEvent.showText(Component.text("클릭하여 가입")));
        Component decline = Component.text(" [거절] ", NamedTextColor.RED, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/국가 거절 " + nationName))
                .hoverEvent(HoverEvent.showText(Component.text("클릭하여 거절")));
        target.sendMessage(legacy.deserialize(
                "§e" + inviter + "§a 님이 §e" + nationName + "§a 국가로 초대했습니다.").append(accept).append(decline));
    }

    private boolean hasValidInvite(UUID uuid, String nationName) {
        Map<String, Long> playerInvites = invites.get(uuid);
        if (playerInvites == null) {
            return false;
        }
        Long expiry = playerInvites.get(nationName.toLowerCase(Locale.ROOT));
        if (expiry == null) {
            return false;
        }
        if (expiry < System.currentTimeMillis()) {
            playerInvites.remove(nationName.toLowerCase(Locale.ROOT));
            return false;
        }
        return true;
    }

    private void clearInvite(UUID uuid, String nationName) {
        Map<String, Long> playerInvites = invites.get(uuid);
        if (playerInvites != null) {
            playerInvites.remove(nationName.toLowerCase(Locale.ROOT));
            if (playerInvites.isEmpty()) {
                invites.remove(uuid);
            }
        }
    }

    private Player asPlayer(CommandSender sender) {
        if (sender instanceof Player) {
            return (Player) sender;
        }
        send(sender, "§c이 명령어는 게임 내에서만 사용할 수 있습니다.");
        return null;
    }

    private void usage(CommandSender sender) {
        send(sender, "§6=== 국가 명령어 ===");
        send(sender, "§e/국가 생성 <이름> §7- 국가 설립 (국가장)");
        send(sender, "§e/국가 해체 §7- 국가 해체 (국가장)");
        send(sender, "§e/국가 초대 <플레이어> §7- 국가원 초대 (국가장)");
        send(sender, "§e/국가 탈퇴 §7- 국가 탈퇴");
        send(sender, "§e/국가 정보 [국가] §7- 국가원 목록 보기");
        send(sender, "§e/국가 목록 §7- 전체 국가 목록");
        send(sender, "§e/국가 색상 <#RRGGBB> §7- 국가 영토 색 변경 (국가장)");
    }

    private static void send(CommandSender sender, String legacy) {
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize(legacy));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(Arrays.asList("생성", "해체", "초대", "탈퇴", "정보", "목록", "색상"), args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("초대") || sub.equals("invite")) {
                List<String> names = new ArrayList<>();
                for (Player online : Bukkit.getOnlinePlayers()) {
                    names.add(online.getName());
                }
                return filter(names, args[1]);
            }
            if (sub.equals("정보") || sub.equals("info")) {
                List<String> names = new ArrayList<>();
                for (Nation nation : store.all()) {
                    names.add(nation.name());
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
