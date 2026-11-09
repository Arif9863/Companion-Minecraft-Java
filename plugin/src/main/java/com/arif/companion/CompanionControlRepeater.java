package com.arif.companion;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Sends the clickable control line again from time to time.
 *
 * <p>The line is sent once when the companions are called, but a chat line scrolls away: after a
 * while the owner would have to hunt for it to switch a mode again. This task puts a fresh line
 * at the bottom of the chat every so often, so the newest line is always the one that is on
 * screen and can be clicked straight away.</p>
 *
 * <p>Only owners with companions out receive it, so a server without companions stays quiet, and
 * the interval can be switched off in the config.</p>
 */
final class CompanionControlRepeater implements Runnable {

    /** Ticks in one second, used to turn the configured seconds into ticks. */
    private static final long TICKS_PER_SECOND = 20L;

    private final JavaPlugin plugin;
    private final CompanionManager manager;
    private final int intervalSeconds;
    private BukkitTask task;

    /**
     * @param plugin          the plugin that owns the task
     * @param manager         used to see who still has companions out
     * @param intervalSeconds how often the line is repeated; 0 means never
     */
    CompanionControlRepeater(JavaPlugin plugin, CompanionManager manager, int intervalSeconds) {
        this.plugin = plugin;
        this.manager = manager;
        this.intervalSeconds = Math.max(0, intervalSeconds);
    }

    /** Starts the repeating task. */
    void start() {
        if (intervalSeconds <= 0) {
            return;
        }
        long periodTicks = intervalSeconds * TICKS_PER_SECOND;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this, periodTicks, periodTicks);
    }

    /** Stops the repeating task. */
    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    @Override
    public void run() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!hasCompanions(player)) {
                continue;
            }
            CompanionControlBar.send(player, CompanionControlBar.forGroup("semua"));
        }
    }

    /** Whether this player has any of Arif's companions out. */
    private boolean hasCompanions(Player player) {
        for (String archetype : CompanionManager.pairedArchetypes()) {
            if (manager.hasCompanion(player.getUniqueId(), archetype)) {
                return true;
            }
        }
        return false;
    }
}
