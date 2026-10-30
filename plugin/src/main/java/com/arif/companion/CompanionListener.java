package com.arif.companion;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/** Handles companion cleanup, obsolete item removal, and aggro protection. */
public final class CompanionListener implements Listener {

    private final CompanionNpcsPlugin plugin;

    public CompanionListener(CompanionNpcsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.getManager().despawn(event.getPlayer());
    }

    /** Remove only the tagged paper control item from earlier GUI builds. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        NamespacedKey obsolete = new NamespacedKey(plugin, "control");
        ItemStack[] contents = player.getInventory().getContents();
        boolean removed = false;
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.getType().isAir()) {
                continue;
            }
            ItemMeta meta = item.getItemMeta();
            if (meta == null) {
                continue;
            }
            Byte tagged = meta.getPersistentDataContainer().get(obsolete, PersistentDataType.BYTE);
            if (tagged != null && tagged == 1) {
                contents[slot] = null;
                removed = true;
            }
        }
        if (removed) {
            player.getInventory().setContents(contents);
            player.sendMessage(Component.text("Item panel lama dihapus.", NamedTextColor.LIGHT_PURPLE));
        }
    }

    /** Refuse mob targets so companions never hold aggro instead of their owner. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTargetLiving(EntityTargetLivingEntityEvent event) {
        Entity target = event.getTarget();
        if (target instanceof Mannequin mannequin
                && mannequin.getScoreboardTags().contains(CompanionManager.TAG)) {
            event.setCancelled(true);
        }
    }
}
