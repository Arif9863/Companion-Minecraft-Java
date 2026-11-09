package com.arif.companion;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Retires the two Skript scripts that used to run Yui and Sandrone.
 *
 * <p>Those scripts carried the pouch, the healing, the absorption and the voice lines. The plugin
 * does all of that now, so a script that is still loaded does the same work a second time: the
 * owner hears Yui's healing twice and is healed twice as fast, and every line that the two
 * systems share is said twice.</p>
 *
 * <p>The scripts are therefore unloaded from Skript and renamed with the disabled prefix, so
 * neither this session nor the next one runs them. The renaming happens on disk as well, because
 * a server whose scripts folder is not in version control keeps the old files otherwise.</p>
 *
 * <p>Skript is reached through reflection, so the plugin does not depend on it being installed.
 * When Skript is absent, or when it never loaded these scripts, there is nothing to retire.</p>
 */
final class LegacySkriptRetirement {

    /** The base names of the scripts that must not run any more. */
    private static final List<String> LEGACY_SCRIPTS =
            List.of("yui_companion", "sandrone_companion");

    /** How many times the retirement looks before it stops. */
    private static final int ATTEMPTS = 120;

    /**
     * Whether a legacy script is running right now and still doing the plugin's work.
     *
     * <p>The plugin's own Yui and Sandrone support abilities stay out of the way while this is
     * true, so a script that could not be retired never causes a double heal or a double
     * voice line.</p>
     */
    private static volatile boolean legacyRunning;

    private LegacySkriptRetirement() {
    }

    /** Whether the legacy scripts are still doing the plugin's work. */
    static boolean legacyStillRunning() {
        return legacyRunning;
    }

    /**
     * Starts retiring the legacy scripts.
     *
     * @param plugin      the plugin doing the retiring
     * @param serverRoot  the server folder, whose {@code plugins/Skript/scripts} holds the scripts
     * @param whenRetired run once the scripts are gone, so their commands can be taken over
     */
    static void schedule(JavaPlugin plugin, File serverRoot, Runnable whenRetired) {
        File scriptsDir = new File(serverRoot, "plugins/Skript/scripts");
        legacyRunning = enabledLegacyFileExists(scriptsDir) && skriptEnabled(plugin);
        RetirementTask task = new RetirementTask(plugin, scriptsDir, whenRetired);
        // The first check waits a few seconds: Skript loads its scripts on its own thread, and
        // a script that is still on its way into memory must be unloaded, not renamed away.
        task.self = plugin.getServer().getScheduler().runTaskTimer(plugin, task, 60L, 20L);
    }

    /** The repeating check that unloads and renames the legacy scripts. */
    private static final class RetirementTask implements Runnable {
        private final JavaPlugin plugin;
        private final File scriptsDir;
        private final Runnable whenRetired;
        private BukkitTask self;
        private int attempts;
        private boolean done;
        /** Whether the plugin's commands have already been taken over. */
        private boolean handedOver;

        private RetirementTask(JavaPlugin plugin, File scriptsDir, Runnable whenRetired) {
            this.plugin = plugin;
            this.scriptsDir = scriptsDir;
            this.whenRetired = whenRetired;
        }

        @Override
        public void run() {
            if (done) {
                return;
            }
            // The scripts are unloaded and renamed on every pass, and the pass keeps running for
            // the whole window: Skript loads its scripts on its own thread, so a script can still
            // arrive after the first check. Stopping at the first success would let that script
            // run for the rest of the session.
            int unloaded = unloadLoadedScripts(plugin);
            if (unloaded > 0) {
                plugin.getLogger().info("Unloaded " + unloaded
                        + " legacy Yui/Sandrone Skript script(s).");
            }
            // The truth about whether the old script is still doing the plugin's work is what
            // Skript has loaded — not what sits in the scripts folder. A file that merely exists
            // on disk proves nothing, and treating it as "still running" used to switch the
            // plugin's own healing off for the whole session.
            boolean stillLoaded = !loadedLegacyScripts(plugin).isEmpty();
            legacyRunning = stillLoaded;
            if (!stillLoaded) {
                // Nothing is running from those files any more, so the plugin may take over.
                // Renaming them keeps Skript from loading them again on the next start.
                renameLegacyFiles(scriptsDir);
            }
            if (!handedOver && !enabledLegacyFileExists(scriptsDir)) {
                handedOver = true;
                whenRetired.run();
            }
            if (attempts++ >= ATTEMPTS) {
                done = true;
                stop();
                if (legacyRunning) {
                    plugin.getLogger().warning("The old Yui/Sandrone Skript scripts are still loaded "
                            + "and could not be unloaded. Yui and Sandrone will not heal or protect "
                            + "the owner, because the script would do it as well. Remove them by hand "
                            + "from " + scriptsDir.getPath() + ".");
                }
            }
        }

        private void stop() {
            if (self != null) {
                self.cancel();
            }
        }
    }

    /**
     * The legacy scripts that Skript currently has loaded.
     *
     * <p>This is the only trustworthy answer to "is the old script still doing the plugin's
     * work?". A script file sitting in the scripts folder proves nothing: Skript may never have
     * loaded it, or it may have been unloaded already. Only the loader's own list of loaded
     * scripts tells the truth, and reading that list is also what makes the rename possible —
     * the old code asked the filesystem, so a script that had already been unloaded still looked
     * "present", the rename never happened, and the plugin's own healing stayed switched off
     * for the whole session.</p>
     *
     * @return the loaded legacy scripts; empty when none is running
     */
    private static Set<Object> loadedLegacyScripts(JavaPlugin plugin) {
        Set<Object> found = new LinkedHashSet<>();
        if (!skriptEnabled(plugin)) {
            return found;
        }
        try {
            Class<?> loaderClass = Class.forName("ch.njol.skript.ScriptLoader");
            Method getLoaded = loaderClass.getMethod("getLoadedScripts");
            Object loaded = getLoaded.invoke(null);
            if (!(loaded instanceof Set<?> scripts)) {
                return found;
            }
            for (Object script : scripts) {
                if (isLegacy(script)) {
                    found.add(script);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            plugin.getLogger().warning("Could not check the legacy Skript scripts: " + ex);
        }
        return found;
    }

    /**
     * Unloads the legacy scripts from Skript.
     *
     * @return how many scripts were unloaded
     */
    private static int unloadLoadedScripts(JavaPlugin plugin) {
        if (!skriptEnabled(plugin)) {
            return 0;
        }
        try {
            Class<?> loaderClass = Class.forName("ch.njol.skript.ScriptLoader");
            Class<?> scriptClass = Class.forName("org.skriptlang.skript.lang.script.Script");
            Method getLoaded = loaderClass.getMethod("getLoadedScripts");
            Method unload = loaderClass.getMethod("unloadScript", scriptClass);
            Object loaded = getLoaded.invoke(null);
            if (!(loaded instanceof Set<?> scripts)) {
                return 0;
            }
            int count = 0;
            for (Object script : scripts) {
                if (!isLegacy(script)) {
                    continue;
                }
                unload.invoke(null, script);
                count++;
            }
            return count;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            plugin.getLogger().warning("Could not unload the legacy Skript scripts: " + ex);
            return 0;
        }
    }

    /** Whether the given loaded Skript script is one of the legacy companion scripts. */
    private static boolean isLegacy(Object script) throws ReflectiveOperationException {
        Object config = script.getClass().getMethod("getConfig").invoke(script);
        File file = (File) config.getClass().getMethod("getFile").invoke(config);
        if (file == null) {
            return false;
        }
        return LEGACY_SCRIPTS.contains(baseName(file.getName()));
    }

    /**
     * Renames the legacy scripts on disk so Skript skips them.
     *
     * @return true when a file was renamed
     */
    private static boolean renameLegacyFiles(File scriptsDir) {
        boolean renamed = false;
        for (String name : LEGACY_SCRIPTS) {
            File file = new File(scriptsDir, name + ".sk");
            if (!file.isFile()) {
                continue;
            }
            File target = new File(scriptsDir, "-" + name + ".sk");
            if (target.exists()) {
                // The disabled copy is already there, so the old one is only clutter.
                if (file.delete()) {
                    renamed = true;
                }
                continue;
            }
            if (file.renameTo(target)) {
                renamed = true;
            }
        }
        return renamed;
    }

    /** Whether a legacy script that Skript would still load is present. */
    private static boolean enabledLegacyFileExists(File scriptsDir) {
        for (String name : LEGACY_SCRIPTS) {
            if (new File(scriptsDir, name + ".sk").isFile()) {
                return true;
            }
        }
        return false;
    }

    /** Whether Skript itself is installed and running. */
    private static boolean skriptEnabled(JavaPlugin plugin) {
        Plugin skript = plugin.getServer().getPluginManager().getPlugin("Skript");
        return skript != null && skript.isEnabled();
    }

    /** The script's name without its extension and without the disabled prefix. */
    private static String baseName(String fileName) {
        String name = fileName;
        if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".sk")) {
            name = name.substring(0, name.length() - 3);
        }
        while (name.startsWith("-")) {
            name = name.substring(1);
        }
        return name.toLowerCase(java.util.Locale.ROOT);
    }
}
