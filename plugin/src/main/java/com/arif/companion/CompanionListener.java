package com.arif.companion;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public final class CompanionListener implements Listener {

    private final CompanionNpcsPlugin plugin;

    public CompanionListener(CompanionNpcsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.getManager().despawn(event.getPlayer());
    }

    /**
     * Retires the old tagged paper control item without touching ordinary player items.
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        org.bukkit.NamespacedKey obsolete = new org.bukkit.NamespacedKey(plugin, "control");
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
            Byte tagged = meta.getPersistentDataContainer()
                    .get(obsolete, PersistentDataType.BYTE);
            if (tagged != null && tagged == 1) {
                contents[slot] = null;
                removed = true;
            }
        }
        if (removed) {
            player.getInventory().setContents(contents);
            player.sendMessage(Component.text("Panel lama dihapus. Klik kanan Yui untuk membuka"
                    + " Kontrol Pendamping dan Kantong Yui.", net.kyori.adventure.text.format.NamedTextColor.LIGHT_PURPLE));
        }
    }

    /**
     * Prevents vanilla actions on companions. Only the owner's Yui gets the choice menu.
     */
    @EventHandler
    public void onCompanionInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || !(event.getRightClicked() instanceof Mannequin mannequin)
                || !mannequin.getScoreboardTags().contains(CompanionManager.TAG)
                || !mannequin.getScoreboardTags().contains("companion_yui")
                || !plugin.getManager().isOwnedBy(event.getPlayer(), mannequin.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        Player owner = event.getPlayer();
        // Open on the next tick so legacy Skript listeners cannot replace the choice menu with
        // their old direct-to-bag inventory.
        plugin.getServer().getScheduler().runTask(plugin,
                () -> YuiInteractionMenu.open(plugin, owner));
    }

    /**
     * Stops a mob from ever choosing a companion as its target.
     *
     * <p>A companion is immortal, so a mob that targeted one would keep hitting it forever
     * instead of going after the owner. The event is cancelled here, which makes the mob look for
     * another target — the owner, usually.</p>
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTargetLiving(EntityTargetLivingEntityEvent event) {
        Entity target = event.getTarget();
        if (target instanceof Mannequin mannequin
                && mannequin.getScoreboardTags().contains(CompanionManager.TAG)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteractionMenuClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof YuiInteractionMenu menu)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() == top && event.getWhoClicked() instanceof Player player) {
            menu.handleClick(player, event.getSlot());
        }
    }

    @EventHandler
    public void onControlMenuClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof CompanionControlMenu menu)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() == top && event.getWhoClicked() instanceof Player player) {
            menu.handleClick(player, event.getSlot());
        }
    }

    /**
     * Keeps the player from placing items in the decorative menu panes.
     */
    @EventHandler
    public void onMenuDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof YuiInteractionMenu
                || event.getView().getTopInventory().getHolder() instanceof CompanionControlMenu) {
            int size = event.getView().getTopInventory().getSize();
            for (int rawSlot : event.getRawSlots()) {
                if (rawSlot < size) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }
}
