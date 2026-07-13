package com.syziege;

import com.syziege.webmap.WebAuth;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * {@code /webmap [pw|password|재설정]}: shows a player their web map login
 * password (creating one on first use) or regenerates it.
 */
public final class WebmapCommand implements CommandExecutor, TabCompleter {

    private final WebAuth auth;
    private final int port;

    public WebmapCommand(WebAuth auth, int port) {
        this.auth = auth;
        this.port = port;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            send(sender, "§c이 명령어는 게임 내에서만 사용할 수 있습니다.");
            return true;
        }
        if (auth == null) {
            send(sender, "§c웹맵이 비활성화되어 있습니다.");
            return true;
        }
        Player player = (Player) sender;
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "pw";

        // Set a chosen password: /webmap 변경 <새 비밀번호>
        if (sub.equals("변경") || sub.equals("change") || sub.equals("set")) {
            if (args.length < 2) {
                send(sender, "§c사용법: §f/webmap 변경 <새 비밀번호>");
                return true;
            }
            String newPassword = args[1];
            if (!isValidPassword(newPassword)) {
                send(sender, "§c비밀번호는 공백 없이 4~32자여야 합니다.");
                return true;
            }
            auth.setPassword(player.getUniqueId(), player.getName(), newPassword);
            send(sender, "§a웹 지도 비밀번호를 변경했습니다.");
            send(sender, "§7아이디: §f" + player.getName() + " §7/ 비밀번호: §e" + newPassword);
            return true;
        }

        boolean reset = sub.equals("재설정") || sub.equals("reset") || sub.equals("초기화");
        String password = reset
                ? auth.resetPassword(player.getUniqueId(), player.getName())
                : auth.ensurePassword(player.getUniqueId(), player.getName());

        send(sender, "§6=== 웹 지도 로그인 ===");
        send(sender, "§7주소: §fhttp://<서버주소>:" + port + "/");
        send(sender, "§7아이디: §f" + player.getName());
        send(sender, "§7비밀번호: §e" + password + (reset ? " §a(새로 발급됨)" : ""));
        send(sender, "§8로그인하면 지도에서 같은 국가원의 위치만 보입니다. 비밀번호는 공유하지 마세요.");
        send(sender, "§8비밀번호 변경: §7/webmap 변경 <새 비밀번호> §8· 재발급: §7/webmap 재설정");
        return true;
    }

    static boolean isValidPassword(String password) {
        if (password == null || password.length() < 4 || password.length() > 32) {
            return false;
        }
        for (int i = 0; i < password.length(); i++) {
            if (Character.isWhitespace(password.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static void send(CommandSender sender, String legacy) {
        sender.sendMessage(LegacyComponentSerializer.legacySection().deserialize(legacy));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String lower = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new java.util.ArrayList<>();
            for (String option : Arrays.asList("pw", "password", "변경", "재설정")) {
                if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                    out.add(option);
                }
            }
            return out;
        }
        return List.of();
    }
}
