package com.arif.companion;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * The chest GUI opened by the companion control item, the panel's replacement for
 * {@code /ikut}, {@code /kesini}, {@code /serang} and {@code /tahan}.
 *
 * <p>Yui's item is the key: right-clicking it opens this menu, which drives whichever
 * companions the current target selection covers — Yui alone, Sandrone alone, or both.
 * The commands stay registered as a fallback and a shortcut; both paths call the very
 * same {@link CompanionManager} methods, so the two can never disagree about state.</p>
 */
final class CompanionControlMenu implements InventoryHolder {

    private static final String TITLE = "Kontrol Pendamping";
    private static final int SIZE = 27;

    private static final int SLOT_YUI_INFO = 0;
    private static final int SLOT_TARGET = 4;
    private static final int SLOT_SANDRONE_INFO = 8;
    private static final int SLOT_FOLLOW = 10;
    private static final int SLOT_CLOSE = 11;
    private static final int SLOT_ATTACK = 13;
    private static final int SLOT_HOLD = 14;

    private final CompanionNpcsPlugin plugin;
    private final Player owner;
    private final Inventory inventory;
    /** Whom the four buttons act on: "yui", "sandrone" or "semua". */
    private String target = "semua";

    private CompanionControlMenu(CompanionNpcsPlugin plugin, Player owner) {
        this.plugin = plugin;
        this.owner = owner;
        this.inventory = Bukkit.createInventory(this, SIZE, Component.text(TITLE));
        render();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** Opens the panel for {@code player}, honouring the {@code companion.npc.control} permission. */
    static void open(CompanionNpcsPlugin plugin, Player player) {
        if (!player.hasPermission("companion.npc.control")) {
            player.sendMessage(Component.text("Kamu tidak punya izin memakai panel pendamping.",
                    NamedTextColor.RED));
            return;
        }
        player.openInventory(new CompanionControlMenu(plugin, player).inventory);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.3f);
    }

    /**
     * Applies a click inside the panel.
     *
     * @return true when the slot belonged to a button, so the caller can refresh the view
     */
    boolean handleClick(Player player, int slot) {
        switch (slot) {
            case SLOT_TARGET -> {
                target = target.equals("semua") ? "yui"
                        : target.equals("yui") ? "sandrone" : "semua";
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.5f);
            }
            case SLOT_FOLLOW -> applyMode(player, CompanionManager.FollowMode.FOLLOW, "Ikut");
            case SLOT_CLOSE -> applyMode(player, CompanionManager.FollowMode.CLOSE, "Kesini");
            case SLOT_ATTACK -> applyAttack(player, true);
            case SLOT_HOLD -> applyAttack(player, false);
            default -> {
                return false;
            }
        }
        render();
        return true;
    }

    /** Same behaviour and wording as {@code /ikut} and {@code /kesini}. */
    private void applyMode(Player player, CompanionManager.FollowMode mode, String label) {
        if (!player.hasPermission("companion.npc.close")) {
            player.sendMessage(Component.text("Kamu tidak punya izin mengubah mode pendamping.",
                    NamedTextColor.RED));
            return;
        }
        int changed = mode == CompanionManager.FollowMode.CLOSE
                ? plugin.getManager().callClose(player, target)
                : plugin.getManager().callFollow(player, target);
        if (changed == 0) {
            player.sendMessage(Component.text("Tidak ada pendamping yang bisa diubah."
                    + " Panggil dengan /yui-npc atau /sandrone-npc dulu.", NamedTextColor.YELLOW));
            return;
        }
        player.sendMessage(Component.text("Mode " + label + " aktif untuk " + target
                + ". Berlaku sampai kamu mengganti mode.", NamedTextColor.GREEN));
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.0f);
    }

    /** Same behaviour and wording as {@code /serang} and {@code /tahan}. */
    private void applyAttack(Player player, boolean enabled) {
        if (!player.hasPermission("companion.npc.attack")) {
            player.sendMessage(Component.text("Kamu tidak punya izin mengatur pertempuran pendamping.",
                    NamedTextColor.RED));
            return;
        }
        int changed = plugin.getManager().setAttacking(player, target, enabled);
        if (changed == 0) {
            player.sendMessage(Component.text("Tidak ada pendamping yang bisa diubah."
                    + " Panggil dengan /yui-npc atau /sandrone-npc dulu.", NamedTextColor.YELLOW));
            return;
        }
        if (enabled) {
            player.sendMessage(Component.text("Siap bertarung untuk " + target
                    + ". Mereka menyerang mob yang menyerangmu.", NamedTextColor.GREEN));
        } else {
            player.sendMessage(Component.text("Tidak bertarung untuk " + target
                    + ". Pedang disingkirkan.", NamedTextColor.GREEN));
        }
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, enabled ? 1.6f : 0.8f);
    }

    /** Rebuilds every slot so the panel always shows the live state after a click. */
    private void render() {
        inventory.clear();
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, filler());
        }
        inventory.setItem(SLOT_YUI_INFO, companionInfo("yui"));
        inventory.setItem(SLOT_SANDRONE_INFO, companionInfo("sandrone"));
        inventory.setItem(SLOT_TARGET, targetButton());
        inventory.setItem(SLOT_FOLLOW, button(Material.COMPASS, "Ikut", NamedTextColor.AQUA,
                modeActive(CompanionManager.FollowMode.FOLLOW),
                "Berjalan dalam formasi di sisimu", "dengan jarak nyaman."));
        inventory.setItem(SLOT_CLOSE, button(Material.LEAD, "Kesini", NamedTextColor.GOLD,
                modeActive(CompanionManager.FollowMode.CLOSE),
                "Menghampiri sampai sedekat mungkin", "denganmu, tanpa formasi."));
        inventory.setItem(SLOT_ATTACK, button(Material.IRON_SWORD, "Serang", NamedTextColor.RED,
                attackActive(true),
                "Melawan mob yang menyerangmu,", "dengan pedang terhunus."));
        inventory.setItem(SLOT_HOLD, button(Material.SHIELD, "Tahan", NamedTextColor.GRAY,
                attackActive(false),
                "Menyimpan pedang dan berhenti", "menyerang."));
    }

    private ItemStack companionInfo(String archetype) {
        String name = archetype.equals("yui") ? "Yui" : "Sandrone";
        NamedTextColor color = archetype.equals("yui")
                ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.AQUA;
        if (!plugin.getManager().hasCompanion(owner.getUniqueId(), archetype)) {
            return icon(Material.GRAY_DYE, name, NamedTextColor.DARK_GRAY,
                    "Tidak dipanggil saat ini",
                    "Panggil dengan /" + archetype + "-npc");
        }
        CompanionManager.FollowMode mode = plugin.getManager().modeOf(owner, archetype);
        boolean attacking = plugin.getManager().isAttacking(owner, archetype);
        String modeLabel = mode == CompanionManager.FollowMode.CLOSE ? "Kesini" : "Ikut";
        return icon(Material.ARMOR_STAND, name, color,
                "Mode: " + modeLabel,
                "Tanganku: " + (attacking ? "siap bertarung" : "pedang tersimpan"));
    }

    private ItemStack targetButton() {
        return icon(Material.TARGET, "Target: " + targetLabel(), NamedTextColor.YELLOW,
                "Klik untuk memindah pilihan —", "tombol di bawah hanya untuk", "karakter ini.");
    }

    private String targetLabel() {
        if (target.equals("yui")) {
            return "Yui";
        }
        return target.equals("sandrone") ? "Sandrone" : "Keduanya";
    }

    /** The archetypes the current target selection covers. */
    private String[] archetypes() {
        return target.equals("semua") ? new String[]{"yui", "sandrone"} : new String[]{target};
    }

    /** True when every summoned companion in the current target shares {@code mode}. */
    private boolean modeActive(CompanionManager.FollowMode mode) {
        boolean checked = false;
        for (String archetype : archetypes()) {
            if (!plugin.getManager().hasCompanion(owner.getUniqueId(), archetype)) {
                continue;
            }
            checked = true;
            if (plugin.getManager().modeOf(owner, archetype) != mode) {
                return false;
            }
        }
        return checked;
    }

    /** True when every summoned companion in the current target has combat {@code enabled}. */
    private boolean attackActive(boolean enabled) {
        boolean checked = false;
        for (String archetype : archetypes()) {
            if (!plugin.getManager().hasCompanion(owner.getUniqueId(), archetype)) {
                continue;
            }
            checked = true;
            if (plugin.getManager().isAttacking(owner, archetype) != enabled) {
                return false;
            }
        }
        return checked;
    }

    private static ItemStack button(Material material, String name, NamedTextColor color,
                                    boolean active, String... lore) {
        List<String> lines = new ArrayList<>(List.of(lore));
        if (active) {
            lines.add("- sedang aktif");
        }
        return icon(material, name, active ? NamedTextColor.GREEN : color,
                lines.toArray(new String[0]));
    }

    private static ItemStack icon(Material material, String name, NamedTextColor color, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
        if (lore.length > 0) {
            List<Component> lines = new ArrayList<>(lore.length);
            for (String line : lore) {
                lines.add(Component.text(line, NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lines);
        }
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Builds the control item: the "key" the owner right-clicks to open this panel.
     *
     * <p>It carries a hidden tag rather than relying on the material or the name, so a renamed
     * look-alike cannot open the menu and the item survives a material change in the config.</p>
     */
    static ItemStack createItem(CompanionNpcsPlugin plugin) {
        Material material = plugin.controlItemMaterial();
        ItemStack item = icon(material, "Panel Pendamping", NamedTextColor.LIGHT_PURPLE,
                "Klik kanan untuk membuka menu",
                "perintah Yui dan Sandrone.",
                "",
                "Ikut, Kesini, Serang, Tahan",
                "tanpa mengetik command.");
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(
                    plugin.controlKey(), PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Whether this stack is the control item, recognised by its hidden tag. */
    static boolean isControlItem(CompanionNpcsPlugin plugin, ItemStack item) {
        if (plugin == null || item == null || item.getType().isAir()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        Byte tagged = meta.getPersistentDataContainer()
                .get(plugin.controlKey(), PersistentDataType.BYTE);
        return tagged != null && tagged == 1;
    }

    private static ItemStack filler() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty());
            item.setItemMeta(meta);
        }
        return item;
    }
}
