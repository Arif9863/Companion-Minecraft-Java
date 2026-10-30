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
import org.bukkit.event.inventory.InventoryCloseEvent;
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
        saveOpenBag(event.getPlayer());
        plugin.getManager().despawn(event.getPlayer());
    }

    /**
     * Retires the obsolete "Panel Pendamping" paper from older builds.
     *
     * <p>The mannequin replaced the item, so a leftover copy would sit in the backpack doing
     * nothing. It is removed by its hidden tag only — never by material or name — so nothing
     * else a player owns is touched.</p>
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

    /** Persist a bag even if the player disconnects while its inventory is open. */
    void saveOpenBag(Player player) {
        Inventory top = player.getOpenInventory().getTopInventory();
        if (top.getHolder() instanceof YuiStorage.YuiStorageView view
                && view.ownerId().equals(player.getUniqueId())) {
            plugin.getStorage().save(view.ownerId(), top.getContents());
        }
    }

    /**
     * Clicking a tagged mannequin opens the menu, but only when it is the player's own Yui.
     *
     * <p>Every other interaction with a companion is refused: the mannequin is not a trade
     * partner, a nametag target or anything else a right-click could normally do.</p>
     */
    @EventHandler
    public void onCompanionInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (!(event.getRightClicked() instanceof Mannequin mannequin)
                || !mannequin.getScoreboardTags().contains(CompanionManager.TAG)) {
            return;
        }
        event.setCancelled(true);
        if (!mannequin.getScoreboardTags().contains("companion_yui")
                || !plugin.getManager().isOwnedBy(event.getPlayer(), mannequin.getUniqueId())) {
            return;
        }
        YuiInteractionMenu.open(plugin, event.getPlayer());
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

    /** Private bag contents can only be used by its owner. */
    @EventHandler
    public void onStorageClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof YuiStorage.YuiStorageView view)) {
            return;
        }
        if (!view.ownerId().equals(event.getWhoClicked().getUniqueId())) {
            event.setCancelled(true);
            event.getWhoClicked().closeInventory();
        }
    }

    /** Keep decorative menu slots immutable without blocking normal storage interactions. */
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
        if (event.getView().getTopInventory().getHolder() instanceof YuiStorage.YuiStorageView view
                && !view.ownerId().equals(event.getWhoClicked().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onStorageClose(InventoryCloseEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof YuiStorage.YuiStorageView view
                && view.ownerId().equals(event.getPlayer().getUniqueId())) {
            plugin.getStorage().save(view.ownerId(), event.getView().getTopInventory().getContents());
        }
    }
}
