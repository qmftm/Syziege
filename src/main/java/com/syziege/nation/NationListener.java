package com.syziege.nation;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;

/** Keeps nation GUIs read-only and refreshes cached member names on login. */
public final class NationListener implements Listener {

    private final NationStore store;

    public NationListener(NationStore store) {
        this.store = store;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof NationGui.Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof NationGui.Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        store.refreshName(event.getPlayer().getUniqueId(), event.getPlayer().getName());
    }
}
