package com.arif.companion;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public final class CompanionListener implements Listener {

    private final CompanionNpcsPlugin plugin;

    public CompanionListener(CompanionNpcsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.getManager().despawn(event.getPlayer());
    }
}
