package com.arif.companion;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/** Private, persistent 27-slot storage for each player's Yui. */
final class YuiStorage {
    static final int SIZE = 27;

    private final CompanionNpcsPlugin plugin;
    private final File file;
    private final Map<UUID, ItemStack[]> bags = new HashMap<>();

    YuiStorage(CompanionNpcsPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "yui-storage.yml");
        load();
    }

    /** Opens the owner's own bag, lazily creating an empty one. */
    void open(Player player) {
        UUID id = player.getUniqueId();
        YuiStorageView view = new YuiStorageView(id);
        ItemStack[] contents = bags.computeIfAbsent(id, ignored -> new ItemStack[SIZE]);
        view.getInventory().setContents(copy(contents));
        player.openInventory(view.getInventory());
    }

    /** Saves the inventory snapshot on close and when the plugin disables. */
    void save(UUID id, ItemStack[] contents) {
        bags.put(id, copy(contents));
        persist();
    }

    /** Persist all loaded bags during disable, even if a menu is still open. */
    void saveAll() {
        persist();
    }

    private static ItemStack[] copy(ItemStack[] contents) {
        ItemStack[] result = new ItemStack[SIZE];
        if (contents != null) {
            for (int i = 0; i < Math.min(contents.length, SIZE); i++) {
                result[i] = contents[i] == null ? null : contents[i].clone();
            }
        }
        return result;
    }

    private void persist() {
        FileConfiguration config = new YamlConfiguration();
        for (Map.Entry<UUID, ItemStack[]> entry : bags.entrySet()) {
            for (int slot = 0; slot < entry.getValue().length; slot++) {
                ItemStack item = entry.getValue()[slot];
                if (item != null && !item.getType().isAir()) {
                    config.set(entry.getKey() + "." + slot, item);
                }
            }
        }
        try {
            config.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not save Yui storage to " + file, ex);
        }
    }

    private void load() {
        if (!file.isFile()) {
            return;
        }
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        for (String key : config.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Ignoring invalid UUID in yui-storage.yml: " + key);
                continue;
            }
            ItemStack[] contents = new ItemStack[SIZE];
            if (config.isConfigurationSection(key)) {
                for (String slotName : config.getConfigurationSection(key).getKeys(false)) {
                    try {
                        int slot = Integer.parseInt(slotName);
                        if (slot >= 0 && slot < SIZE) {
                            contents[slot] = config.getItemStack(key + "." + slotName);
                        }
                    } catch (NumberFormatException ex) {
                        plugin.getLogger().warning("Ignoring invalid bag slot: " + key + "." + slotName);
                    }
                }
            }
            bags.put(id, contents);
        }
    }

    /** A holder lets click and close events identify the owning private bag. */
    static final class YuiStorageView implements org.bukkit.inventory.InventoryHolder {
        private final UUID ownerId;
        private final Inventory inventory;

        YuiStorageView(UUID ownerId) {
            this.ownerId = ownerId;
            this.inventory = org.bukkit.Bukkit.createInventory(this, SIZE,
                    net.kyori.adventure.text.Component.text("Kantong Yui"));
        }

        UUID ownerId() {
            return ownerId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
