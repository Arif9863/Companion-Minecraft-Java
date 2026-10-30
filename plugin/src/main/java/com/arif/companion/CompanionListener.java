package com.arif.companion;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

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
     * Hands every player their control item on first join, and again whenever the backpack no
     * longer holds one — so the panel is reachable without knowing any command.
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> giveControlIfMissing(event.getPlayer()), 1L);
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

    /**
     * Right-clicking the companion control item opens the control panel.
     *
     * <p>Only the main hand is handled, otherwise one right-click fires twice — once per hand —
     * and the panel would open and immediately close again.</p>
     */
    @EventHandler
    public void onControlUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR
                && event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (!CompanionControlMenu.isControlItem(plugin, event.getItem())) {
            return;
        }
        // Block the vanilla use as well, so a control item that happens to be a usable material
        // never plants, places or eats anything when the player only wanted the menu.
        event.setCancelled(true);
        CompanionControlMenu.open(plugin, event.getPlayer());
    }

    /**
     * Routes clicks inside the control panel to the button under the cursor and stops the
     * panel from behaving like a real chest.
     */
    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof CompanionControlMenu menu)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != top) {
            return;
        }
        if (event.getWhoClicked() instanceof Player player) {
            menu.handleClick(player, event.getSlot());
        }
    }

    /** Refuses drags into the panel, otherwise items could still be dropped into its slots. */
    @EventHandler
    public void onMenuDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof CompanionControlMenu)) {
            return;
        }
        int size = event.getView().getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < size) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /** Hands a returning owner their control item if the backpack no longer has one. */
    void giveControlIfMissing(Player player) {
        if (plugin.controlItemMaterial() == null) {
            return;
        }
        if (hasControlItem(player)) {
            return;
        }
        ItemStack leftover = player.getInventory().addItem(CompanionControlMenu.createItem(plugin))
                .values().stream().findFirst().orElse(null);
        if (leftover != null) {
            player.sendMessage(Component.text("Backpack penuh, panel pendamping tidak masuk."
                    + " Ambil lagi dengan /yui-npc item.", NamedTextColor.YELLOW));
            return;
        }
        player.sendMessage(Component.text("Klik kanan [Panel Pendamping] untuk membuka"
                + " perintah Yui dan Sandrone.", NamedTextColor.LIGHT_PURPLE));
    }

    private boolean hasControlItem(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (CompanionControlMenu.isControlItem(plugin, item)) {
                return true;
            }
        }
        return false;
    }
}
