package com.arif.companion;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

/**
 * The holder behind a companion's pouch.
 *
 * <p>Its own class is what makes the pouch recognisable in inventory events. A chest of the
 * same size opened by the owner must never be mistaken for it, so the save path only touches
 * an inventory whose holder is this type.</p>
 *
 * <p>The archetype is carried along with the owner, because each companion has her own pouch:
 * Furina's bag is not Escoffier's, and neither of them is the owner's own chest.</p>
 */
final class CompanionPouchHolder implements InventoryHolder {

    /** Number of slots in the pouch: the same as a single chest. */
    static final int SIZE = 27;

    private final UUID ownerId;
    private final String archetype;
    private final Inventory inventory;

    /**
     * @param ownerId   the player the pouch belongs to
     * @param archetype the companion whose pouch this is
     */
    CompanionPouchHolder(UUID ownerId, String archetype) {
        this.ownerId = ownerId;
        this.archetype = archetype;
        this.inventory = Bukkit.createInventory(this, SIZE, titleFor(archetype));
    }

    /** The title shown on the pouch window. */
    private static Component titleFor(String archetype) {
        String label = switch (archetype == null ? "" : archetype.toLowerCase(java.util.Locale.ROOT)) {
            case "yui" -> "Yui";
            case "sandrone" -> "Sandrone";
            case "furina" -> "Furina";
            case "ei" -> "Ei";
            case "columbina" -> "Columbina";
            case "escoffier" -> "Escoffier";
            default -> "Companion";
        };
        return Component.text("Kantong " + label);
    }

    UUID ownerId() {
        return ownerId;
    }

    /** The companion whose pouch this is. */
    String archetype() {
        return archetype;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** Whether this pouch belongs to the given player. */
    boolean belongsTo(Player player) {
        return ownerId.equals(player.getUniqueId());
    }
}
