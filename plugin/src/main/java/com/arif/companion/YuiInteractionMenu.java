package com.arif.companion;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/** The small choice menu shown when the owner right-clicks Yui. */
final class YuiInteractionMenu implements InventoryHolder {

    private static final int SIZE = 27;
    private static final int CONTROL_SLOT = 11;
    private static final int BAG_SLOT = 15;

    private final CompanionNpcsPlugin plugin;
    private final Inventory inventory;

    private YuiInteractionMenu(CompanionNpcsPlugin plugin) {
        this.plugin = plugin;
        this.inventory = Bukkit.createInventory(this, SIZE, Component.text("Yui"));
        ItemStack filler = icon(Material.PINK_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY);
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, filler);
        }
        inventory.setItem(CONTROL_SLOT, icon(Material.COMPASS, "Kontrol Pendamping", NamedTextColor.AQUA,
                "Atur Ikut, Kesini, Serang, dan Tahan", "untuk Yui dan Sandrone."));
        inventory.setItem(BAG_SLOT, icon(Material.CHEST, "Kantong Yui", NamedTextColor.LIGHT_PURPLE,
                "Simpan atau ambil barang pribadi.", "Isi kantong tersimpan setelah restart."));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    static void open(CompanionNpcsPlugin plugin, Player player) {
        if (!player.hasPermission("companion.npc.control")) {
            player.sendMessage(Component.text("Kamu tidak punya izin memakai menu Yui.", NamedTextColor.RED));
            return;
        }
        player.openInventory(new YuiInteractionMenu(plugin).inventory);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.3f);
    }

    void handleClick(Player player, int slot) {
        if (slot == CONTROL_SLOT) {
            if (!player.hasPermission("companion.npc.control")) {
                player.sendMessage(Component.text("Kamu tidak punya izin memakai panel pendamping.",
                        NamedTextColor.RED));
                return;
            }
            // One tick later: swapping inventories inside the click that opened them desyncs
            // the client on some builds.
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> CompanionControlMenu.open(plugin, player));
        } else if (slot == BAG_SLOT) {
            if (!player.hasPermission("companion.npc.storage")) {
                player.sendMessage(Component.text("Kamu tidak punya izin memakai Kantong Yui.",
                        NamedTextColor.RED));
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> plugin.getStorage().open(player));
        }
    }

    private static ItemStack icon(Material material, String name, NamedTextColor color, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
        if (lore.length > 0) {
            meta.lore(List.of(lore).stream()
                    .map(line -> Component.text(line, NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false))
                    .toList());
        }
        item.setItemMeta(meta);
        return item;
    }
}

/** A live view of one owner's persisted 27-slot bag. */
final class YuiStorageView implements InventoryHolder {
    private final java.util.UUID ownerId;
    private final Inventory inventory;

    YuiStorageView(java.util.UUID ownerId) {
        this.ownerId = ownerId;
        this.inventory = Bukkit.createInventory(this, YuiStorage.SIZE, Component.text("Kantong Yui"));
    }

    java.util.UUID ownerId() {
        return ownerId;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
