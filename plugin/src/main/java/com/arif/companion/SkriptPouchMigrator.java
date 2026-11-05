package com.arif.companion;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/** One-time import of Yui/Sandrone pouch items from Skript {@code variables.csv}. */
final class SkriptPouchMigrator {

    private static final UUID PRIMARY_OWNER =
            UUID.fromString("515402e5-832e-311e-b35f-3f3f0eca8f4f");
    private static final UUID LEGACY_OFFLINE_OWNER =
            UUID.fromString("6f599e15-f35f-478a-b672-ffe3a1e3be8a");

    private SkriptPouchMigrator() {
    }

    static void migrateIfPresent(JavaPlugin plugin, File serverRoot) {
        if (serverRoot == null) {
            return;
        }
        File csv = new File(serverRoot, "plugins/Skript/variables.csv");
        if (!csv.isFile()) {
            return;
        }
        int imported = 0;
        try (BufferedReader reader = Files.newBufferedReader(csv.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("companion::storage::")) {
                    continue;
                }
                String[] parts = line.split(", ", 3);
                if (parts.length < 3 || !"itemstack".equals(parts[1])) {
                    continue;
                }
                ParsedKey key = parseKey(parts[0]);
                if (key == null) {
                    continue;
                }
                ItemStack stack = decodeItem(parts[2].trim());
                if (stack == null) {
                    continue;
                }
                if (writeIfEmpty(plugin, key, stack)) {
                    imported++;
                }
            }
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not read Skript variables.csv for pouch import", ex);
            return;
        }
        if (imported > 0) {
            plugin.getLogger().info("Imported " + imported + " pouch slot(s) from Skript variables.csv.");
        }
    }

    private record ParsedKey(UUID ownerId, String kind, int slot) {
    }

    private static ParsedKey parseKey(String raw) {
        String body = raw.substring("companion::storage::".length());
        int first = body.indexOf("::");
        if (first < 0) {
            return null;
        }
        int second = body.indexOf("::", first + 2);
        if (second < 0) {
            return null;
        }
        try {
            UUID ownerId = UUID.fromString(body.substring(0, first));
            String kind = body.substring(first + 2, second).toLowerCase(Locale.ROOT);
            if (!"yui".equals(kind) && !"sandrone".equals(kind)) {
                return null;
            }
            int slot = Integer.parseInt(body.substring(second + 2));
            if (slot < 0 || slot >= CompanionPouchHolder.SIZE) {
                return null;
            }
            return new ParsedKey(ownerId, kind, slot);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static ItemStack decodeItem(String hex) {
        if (hex.length() % 2 != 0) {
            return null;
        }
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        if (bytes.length < 10 || bytes[0] != (byte) 0x81 || bytes[1] != 0x05) {
            return null;
        }
        int len = bytes[9] & 0xFF;
        if (bytes.length < 10 + len) {
            return null;
        }
        String yaml = new String(bytes, 10, len, StandardCharsets.UTF_8);
        YamlConfiguration section = new YamlConfiguration();
        try {
            section.loadFromString(yaml);
        } catch (Exception ex) {
            return null;
        }
        Object value = section.get("value");
        if (value instanceof ItemStack stack) {
            return stack;
        }
        if (value instanceof Map<?, ?> map) {
            try {
                return ItemStack.deserialize(castMap(map));
            } catch (RuntimeException ex) {
                return null;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static boolean writeIfEmpty(JavaPlugin plugin, ParsedKey key, ItemStack stack) {
        if (!PRIMARY_OWNER.equals(key.ownerId()) && !LEGACY_OFFLINE_OWNER.equals(key.ownerId())) {
            return false;
        }
        File file = new File(plugin.getDataFolder(), key.kind() + "-pouch.yml");
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        String path = PRIMARY_OWNER + "." + key.kind() + "." + key.slot();
        ItemStack existing = config.getItemStack(path);
        if (existing != null && !existing.getType().isAir()) {
            return false;
        }
        config.set(path, stack);
        try {
            config.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not save imported " + key.kind() + " pouch", ex);
            return false;
        }
        return true;
    }
}
