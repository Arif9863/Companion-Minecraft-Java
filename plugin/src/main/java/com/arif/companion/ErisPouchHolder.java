package com.arif.companion;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

/**
 * The holder behind Eris's pouch.
 *
 * <p>Its own class is what makes the pouch recognisable in inventory events. A chest of the
 * same size opened by the owner must never be mistaken for it, so the save path only touches
 * an inventory whose holder is this type.</p>
 */
final class ErisPouchHolder implements InventoryHolder {

    /** Number of slots in the pouch: the same as a single chest. */
    static final int SIZE = 27;

    private final UUID ownerId;
    private final Inventory inventory;

    ErisPouchHolder(UUID ownerId) {
        this.ownerId = ownerId;
        this.inventory = Bukkit.createInventory(this, SIZE, Component.text("Kantong Eris"));
    }

    UUID ownerId() {
        return ownerId;
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
