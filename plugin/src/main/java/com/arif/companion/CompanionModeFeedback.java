package com.arif.companion;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

/**
 * Says which mode a companion is in.
 *
 * <p>Used when the mode is changed by crouching and right-clicking the companion, so the
 * owner sees what just happened without having to look at her behaviour.</p>
 */
final class CompanionModeFeedback {

    private CompanionModeFeedback() {
    }

    /**
     * Tells the owner which mode the companion is in now.
     *
     * @param owner     the player who owns the companion
     * @param archetype the companion's archetype
     * @param mode      the mode she is in now
     */
    static void announce(Player owner, String archetype, CompanionManager.FollowMode mode) {
        if (owner == null || mode == null) {
            return;
        }
        String label = CompanionManager.labelFor(archetype);
        owner.sendMessage(Component.text(label, NamedTextColor.LIGHT_PURPLE)
                .append(Component.text(" sekarang ", NamedTextColor.GRAY))
                .append(Component.text(modeName(mode), NamedTextColor.GREEN))
                .append(Component.text(".", NamedTextColor.GRAY)));
    }

    /** The word the owner knows the mode by. */
    static String modeName(CompanionManager.FollowMode mode) {
        return switch (mode) {
            case FOLLOW -> "Ikut";
            case CLOSE -> "Kesini";
            case WAIT -> "Tunggu";
        };
    }
}
