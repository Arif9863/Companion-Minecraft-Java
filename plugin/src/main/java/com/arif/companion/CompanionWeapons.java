package com.arif.companion;

import org.bukkit.Material;
import org.bukkit.entity.Mannequin;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;

/**
 * The weapon each companion carries, and how it fights.
 *
 * <p>Every companion of Arif's group has its own weapon:</p>
 * <ul>
 *   <li><b>Yui, Sandrone, Eris</b> — an iron sword, swung.</li>
 *   <li><b>Furina</b> — a diamond sword, swung, hitting harder.</li>
 *   <li><b>Ei</b> — a diamond spear, thrust as a jab rather than swung.</li>
 *   <li><b>Columbina and Escoffier</b> — a trident, thrown at the target from a distance.</li>
 * </ul>
 *
 * <p>The weapon is only in the companion's hand while it has something to fight, so a companion
 * that is idle or waiting walks around unarmed.</p>
 */
final class CompanionWeapons {

    /** How a companion strikes. */
    enum Style {
        /** A swing of a sword: the arm sweeps across. */
        SWING,
        /** A thrust of a spear: the arm jabs forward. */
        JAB,
        /** A thrown weapon: the companion keeps its distance and throws. */
        THROWN
    }

    /** A companion's weapon: what it holds, how much it deals, and how it swings it. */
    record Weapon(String archetype, Material material, double damage, Style style) {

        boolean isRanged() {
            return style == Style.THROWN;
        }
    }

    private static final Weapon YUI =
            new Weapon("yui", Material.IRON_SWORD, 5.0, Style.SWING);
    private static final Weapon SANDRONE =
            new Weapon("sandrone", Material.IRON_SWORD, 5.0, Style.SWING);
    private static final Weapon ERIS =
            new Weapon("eris", Material.IRON_SWORD, 5.0, Style.SWING);
    private static final Weapon FURINA =
            new Weapon("furina", Material.DIAMOND_SWORD, 7.0, Style.SWING);
    private static final Weapon EI =
            new Weapon("ei", Material.DIAMOND_SPEAR, 6.0, Style.JAB);
    private static final Weapon COLUMBINA =
            new Weapon("columbina", Material.TRIDENT, 6.0, Style.THROWN);
    private static final Weapon ESCOFFIER =
            new Weapon("escoffier", Material.TRIDENT, 6.0, Style.THROWN);

    private CompanionWeapons() {
    }

    /**
     * The weapon an archetype carries.
     *
     * @return the weapon, or {@code null} for an archetype that has none
     */
    static Weapon of(String archetype) {
        if (archetype == null) {
            return null;
        }
        return switch (archetype.toLowerCase(java.util.Locale.ROOT)) {
            case "yui" -> YUI;
            case "sandrone" -> SANDRONE;
            case "eris" -> ERIS;
            case "furina" -> FURINA;
            case "ei" -> EI;
            case "columbina" -> COLUMBINA;
            case "escoffier" -> ESCOFFIER;
            default -> null;
        };
    }

    /**
     * Puts the weapon into the companion's hand, or takes it away.
     *
     * <p>The state is tracked on the mannequin itself, so the hand is only written when it
     * actually changes: rewriting the item every tick would make the client flicker.</p>
     *
     * @param npc     the companion
     * @param weapon  the weapon it should hold, or {@code null} to disarm it
     */
    static void equip(Mannequin npc, Weapon weapon) {
        EntityEquipment equipment = npc.getEquipment();
        if (equipment == null) {
            return;
        }
        Material wanted = weapon == null ? Material.AIR : weapon.material();
        Material current = equipment.getItemInMainHand().getType();
        if (current == wanted) {
            return;
        }
        equipment.setItemInMainHand(weapon == null ? null : new ItemStack(weapon.material()));
    }
}
