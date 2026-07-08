package com.syziege.nation;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read-only chest GUIs for nation info (members) and the nation list. */
public final class NationGui {

    /** Marks an inventory as one of ours so clicks can be cancelled. */
    public static final class Holder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private NationGui() {
    }

    private static Component plain(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private static int fitSize(int count) {
        int rows = Math.max(1, Math.min(6, (count + 8) / 9));
        return rows * 9;
    }

    /** Opens a member roster for the given nation. */
    public static void openMembers(Player viewer, Nation nation) {
        Holder holder = new Holder();
        Inventory inv = Bukkit.createInventory(holder, fitSize(nation.size()),
                plain(nation.name() + " 국가원 (" + nation.size() + ")", NamedTextColor.GOLD));
        holder.inventory = inv;

        for (Map.Entry<UUID, String> member : nation.members().entrySet()) {
            boolean leader = nation.isLeader(member.getKey());
            OfflinePlayer offline = Bukkit.getOfflinePlayer(member.getKey());

            List<Component> lore = new ArrayList<>();
            lore.add(plain(leader ? "국가장" : "국가원", leader ? NamedTextColor.YELLOW : NamedTextColor.GRAY));
            lore.add(offline.isOnline()
                    ? plain("● 접속 중", NamedTextColor.GREEN)
                    : plain("● 오프라인", NamedTextColor.DARK_GRAY));

            inv.addItem(head(offline,
                    plain((leader ? "★ " : "") + member.getValue(),
                            leader ? NamedTextColor.YELLOW : NamedTextColor.WHITE),
                    lore));
        }
        viewer.openInventory(inv);
    }

    /** Opens the list of all nations, one leader head per nation. */
    public static void openList(Player viewer, Collection<Nation> nations) {
        Holder holder = new Holder();
        Inventory inv = Bukkit.createInventory(holder, fitSize(Math.max(1, nations.size())),
                plain("국가 목록 (" + nations.size() + ")", NamedTextColor.GOLD));
        holder.inventory = inv;

        for (Nation nation : nations) {
            List<Component> lore = new ArrayList<>();
            lore.add(plain("국가장: " + nation.members().get(nation.leader()), NamedTextColor.GRAY));
            lore.add(plain("국가원: " + nation.size() + "명", NamedTextColor.GRAY));
            lore.add(plain("설립: " + daysAgo(nation.createdAt()) + "일 전", NamedTextColor.DARK_GRAY));
            inv.addItem(head(Bukkit.getOfflinePlayer(nation.leader()),
                    plain(nation.name(), NamedTextColor.GOLD), lore));
        }
        viewer.openInventory(inv);
    }

    private static ItemStack head(OfflinePlayer owner, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(owner);
        meta.displayName(name);
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static long daysAgo(long epochMillis) {
        return Math.max(0, Duration.between(Instant.ofEpochMilli(epochMillis), Instant.now()).toDays());
    }
}
