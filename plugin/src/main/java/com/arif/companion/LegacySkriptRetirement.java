package com.arif.companion;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.lang.reflect.Method;
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
        task.self = plugin.getServer().getScheduler().runTaskTimer(plugin, task, 20L, 20L);
    }

    /** The repeating check that unloads and renames the legacy scripts. */
    private static final class RetirementTask implements Runnable {
        private final JavaPlugin plugin;
        private final File scriptsDir;
        private final Runnable whenRetired;
        private BukkitTask self;
        private int attempts;
        private boolean done;

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
            if (attempts++ >= ATTEMPTS) {
                done = true;
                stop();
                plugin.getLogger().warning("The old Yui/Sandrone Skript scripts could not be retired. "
                        + "Yui and Sandrone may speak twice. Remove them by hand from "
                        + scriptsDir.getPath() + ".");
                whenRetired.run();
                return;
            }
            int unloaded = unloadLoadedScripts(plugin);
            if (unloaded > 0) {
                plugin.getLogger().info("Unloaded " + unloaded
                        + " legacy Yui/Sandrone Skript script(s).");
            }
            // A script that is loaded is still doing the plugin's work, so the plugin's own
            // support abilities keep quiet until it is gone.
            boolean stillLoaded = unloaded > 0
                    || (enabledLegacyFileExists(scriptsDir) && skriptEnabled(plugin));
            legacyRunning = stillLoaded;
            if (stillLoaded) {
                // The files are renamed only once nothing is loaded from them: renaming a file
                // that Skript has open would leave the script running from memory.
                return;
            }
            if (renameLegacyFiles(scriptsDir)) {
                plugin.getLogger().info("Renamed the legacy Yui/Sandrone Skript scripts so Skript "
                        + "skips them from now on.");
            }
            if (!enabledLegacyFileExists(scriptsDir)) {
                done = true;
                stop();
                whenRetired.run();
            }
        }

        private void stop() {
            if (self != null) {
                self.cancel();
            }
        }
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
