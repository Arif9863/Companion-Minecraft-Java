package com.arif.companion;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/** Handles companion cleanup, obsolete item removal, and aggro protection. */
public final class CompanionListener implements Listener {

    private final CompanionNpcsPlugin plugin;

    public CompanionListener(CompanionNpcsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.getManager().despawn(event.getPlayer());
    }

    /** Remove only the tagged paper control item from earlier GUI builds. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Mark the player according to the configured companion owners, so the Skript commands
        // /yui and /sandrone know who may use them.
        plugin.refreshCompanionUserTag(player);
        // Buffs and DuoVoice in Skript follow companion_active_* tags; drop any that survived a
        // reload while the mannequin is gone.
        plugin.getManager().syncActiveCompanionTags(player);
        NamespacedKey obsolete = new NamespacedKey(plugin, "control");
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
            Byte tagged = meta.getPersistentDataContainer().get(obsolete, PersistentDataType.BYTE);
            if (tagged != null && tagged == 1) {
                contents[slot] = null;
                removed = true;
            }
        }
        if (removed) {
            player.getInventory().setContents(contents);
            player.sendMessage(Component.text("Item panel lama dihapus.", NamedTextColor.LIGHT_PURPLE));
        }
    }

    /** Refuse mob targets so companions never hold aggro instead of their owner. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTargetLiving(EntityTargetLivingEntityEvent event) {
        Entity target = event.getTarget();
        if (target instanceof Mannequin mannequin
                && mannequin.getScoreboardTags().contains(CompanionManager.TAG)) {
            event.setCancelled(true);
        }
    }

    /**
     * Right-clicking Eris opens her pouch — but only for the player who owns her.
     *
     * <p>Everybody else gets Eris's own refusal instead of the bag, so her storage never leaks
     * to another player on the server.</p>
     */
    @EventHandler
    public void onErisInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Mannequin mannequin)
                || !mannequin.getScoreboardTags().contains("companion_eris")) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!plugin.getManager().canInteractWithEris(player, mannequin.getUniqueId())) {
            plugin.getManager().denyEris(player);
            return;
        }
        plugin.getManager().openErisPouch(player);
    }

    /**
     * Right-clicking a companion of Arif's group opens her pouch — but only for the player who
     * owns her.
     *
     * <p>Everybody else gets the companion's own refusal instead of the bag, so a pouch never
     * leaks to another player on the server.</p>
     */
    @EventHandler
    public void onCompanionInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Mannequin mannequin)) {
            return;
        }
        String archetype = null;
        for (String tag : mannequin.getScoreboardTags()) {
            if (tag.startsWith("companion_") && CompanionManager.hasPouch(
                    tag.substring("companion_".length()))) {
                archetype = tag.substring("companion_".length());
                break;
            }
        }
        if (archetype == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!plugin.getManager().canOpenCompanionPouch(player, mannequin.getUniqueId())) {
            plugin.getManager().denyCompanion(player, archetype);
            return;
        }
        plugin.getManager().openCompanionPouch(player, archetype);
    }

    /** Remembers which mob a companion hit, so the kill can be credited to her owner. */
    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Mannequin mannequin)
                || !mannequin.getScoreboardTags().contains(CompanionManager.TAG)) {
            return;
        }
        plugin.getManager().noteCompanionDamage(event.getEntity().getUniqueId(),
                mannequin.getUniqueId());
    }

    /**
     * Handles a trident a companion threw.
     *
     * <p>The trident is fired by the owner, so its damage is already credited to him. A mob that
     * was coming for the companion is handed back to the owner, and the companion that threw it
     * gets the kill credit for the line she says about it.</p>
     */
    @EventHandler
    public void onTridentHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Trident trident)
                || !trident.getScoreboardTags().contains(RangedTridentAttack.PROJECTILE_TAG)) {
            return;
        }
        Entity hit = event.getEntity();
        if (hit instanceof LivingEntity living) {
            plugin.getManager().releaseProjectileAggro(living, trident);
            String thrower = null;
            for (String tag : trident.getScoreboardTags()) {
                if (tag.startsWith(RangedTridentAttack.OWNER_TAG_PREFIX)) {
                    thrower = tag.substring(RangedTridentAttack.OWNER_TAG_PREFIX.length());
                    break;
                }
            }
            if (thrower != null) {
                plugin.getManager().noteProjectileDamage(living.getUniqueId(), thrower);
            }
        }
    }

    /** Removes a thrown trident once it lands, so no stray weapons are left lying around. */
    @EventHandler
    public void onTridentLand(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Trident trident)
                || !trident.getScoreboardTags().contains(RangedTridentAttack.PROJECTILE_TAG)) {
            return;
        }
        trident.remove();
    }

    /** Counts a mob killed by a companion, which is what unlocks Eris's Strength I. */
    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        plugin.getManager().recordCompanionKill(event.getEntity().getUniqueId());
        // Ei's strength follows the owner's own kills, not the companion's.
        if (event.getEntity().getKiller() != null) {
            plugin.getManager().noteOwnerKill(event.getEntity().getKiller());
        }
    }

    /** Saves a companion's pouch when its inventory closes. */
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        plugin.getManager().saveCompanionPouch(player, event.getInventory());
        plugin.getManager().saveErisPouch(player, event.getInventory());
    }
}
