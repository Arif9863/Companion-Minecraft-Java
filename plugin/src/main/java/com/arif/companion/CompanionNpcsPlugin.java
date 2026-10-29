package com.arif.companion;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CompanionNpcsPlugin extends JavaPlugin {

    private CompanionManager manager;
    private PackHttpServer packServer;
    private String yuiSkinUrl;
    private String sandroneSkinUrl;
    private double comfortDistance;
    private double closeDistance;
    private int teleportDistance;
    private int closeTeleportDistance;
    private int idleLookRadius;
    private int idleLookStandstillDistance;
    private int packHttpPort;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        readConfig();
        manager = new CompanionManager(this);

        registerCommand("yui-npc", new CompanionCommand("yui"));
        registerCommand("sandrone-npc", new CompanionCommand("sandrone"));
        registerCommand("kesini", new ModeCommand(CompanionManager.FollowMode.CLOSE, "Kesini"));
        registerCommand("ikut", new ModeCommand(CompanionManager.FollowMode.FOLLOW, "Ikut"));
        registerCommand("companionnpc", new AdminCommand());
        Bukkit.getPluginManager().registerEvents(new CompanionListener(this), this);

        startPackServer();

        getLogger().info("Companion NPCs enabled. Commands: /yui-npc, /sandrone-npc, /ikut, /kesini, /companionnpc");
        if (getConfig().getBoolean("debug-self-test", false)) {
            runSelfTest();
        }
    }

    /**
     * Starts the HTTP server that lets clients download the resource pack.
     *
     * <p>The ZIP sits next to the server jar, and the URL is printed so it can be pasted
     * into {@code resource-pack=} in server.properties. Players on the LAN fetch it
     * directly; nobody has to install the pack by hand.</p>
     */
    private void startPackServer() {
        if (!getConfig().getBoolean("pack-http-server", true)) {
            getLogger().info("Pack HTTP server disabled by config.");
            return;
        }
        File serverRoot = getDataFolder().getParentFile().getParentFile();
        File zip = new File(serverRoot, "companion-voices-26.3.zip");
        if (!zip.isFile()) {
            getLogger().warning("Resource pack ZIP not found at " + zip.getPath()
                    + "; pack HTTP server not started.");
            return;
        }
        packServer = new PackHttpServer(zip, packHttpPort, getLogger());
        packServer.start();
        if (packServer.isRunning()) {
            getLogger().info("Set in server.properties:  resource-pack="
                    + packServer.url(zip.getName()));
        }
    }

    private void registerCommand(String name, CommandExecutor executor) {
        if (getCommand(name) == null) {
            throw new IllegalStateException("Command missing from plugin.yml: " + name);
        }
        getCommand(name).setExecutor(executor);
        if (executor instanceof TabCompleter completer) {
            getCommand(name).setTabCompleter(completer);
        }
    }

    private void readConfig() {
        FileConfiguration config = getConfig();
        yuiSkinUrl = config.getString("yui.skin-url", "");
        sandroneSkinUrl = config.getString("sandrone.skin-url", "");
        // Standing distance: how far away a companion settles while the owner is idle.
        comfortDistance = config.getDouble("comfort-distance", 3.0);
        // Close distance: how near they come when the owner calls them with /kesini.
        // 1.8 blocks keeps them close without shoving the owner aside.
        closeDistance = config.getDouble("close-distance", 1.8);
        // Catch-up threshold while in FOLLOW mode (supported range 16-32).
        int requested = config.getInt("teleport-distance", 24);
        teleportDistance = Math.max(16, Math.min(32, requested));
        if (teleportDistance != requested) {
            getLogger().warning("teleport-distance " + requested + " clamped to " + teleportDistance
                    + " (supported range 16-32).");
        }
        // Catch-up threshold in CLOSE mode. Same safe range and conservative 24-block default.
        int requestedClose = config.getInt("close-teleport-distance", 24);
        closeTeleportDistance = Math.max(16, Math.min(32, requestedClose));
        if (closeTeleportDistance != requestedClose) {
            getLogger().warning("close-teleport-distance " + requestedClose + " clamped to "
                    + closeTeleportDistance + " (supported range 16-32).");
        }
        // Mob glance radius: how far the companion looks around for a mob while idle.
        int requestedRadius = config.getInt("idle-look-mob-radius", 12);
        idleLookRadius = Math.max(4, Math.min(24, requestedRadius));
        // Lingering distance: how far from the owner a barely-moving companion may glance at mobs.
        int requestedIdleDist = config.getInt("idle-look-standstill-distance", 6);
        idleLookStandstillDistance = Math.max(2, Math.min(12, requestedIdleDist));
        // Pack HTTP server port (LAN file serving).
        packHttpPort = config.getInt("pack-http-port", 8765);
    }

    @Override
    public void onDisable() {
        if (packServer != null) {
            packServer.stop();
            packServer = null;
        }
        if (manager != null) {
            manager.despawnEverything();
        }
    }

    public CompanionManager getManager() {
        return manager;
    }

    String skinUrl(String archetype) {
        return archetype.equals("yui") ? yuiSkinUrl : sandroneSkinUrl;
    }

    int followDistance() {
        return teleportDistance;
    }

    /** Distance at which the companion stops closing in and stands still. */
    double comfortDistance() {
        return comfortDistance;
    }

    /** Distance beyond which the companion teleports to catch up with its owner. */
    int teleportDistance() {
        return teleportDistance;
    }

    /** Distance beyond which the companion teleports back to the owner in CLOSE mode. */
    int closeTeleportDistance() {
        return closeTeleportDistance;
    }

    /** Distance the companions come to when the owner calls them with /kesini. */
    double closeDistance() {
        return closeDistance;
    }

    /** Radius within which an idle companion may glance at a nearby mob. */
    int idleLookRadius() {
        return idleLookRadius;
    }

    /**
     * Distance at which a barely-moving companion may still glance at mobs.
     *
     * <p>Wider than {@link #comfortDistance()} so the gaze actually runs during normal play —
     * it used to require standing on top of the owner, which almost never happened.</p>
     */
    int idleLookStandstillDistance() {
        return idleLookStandstillDistance;
    }

    /**
     * Returns the resource pack skin key when the bundled PNG exists, otherwise null.
     *
     * <p>Checked on disk so the mannequin never points at a texture the pack does not ship — a
     * missing key would render as the missing-texture pattern on the client.</p>
     */
    net.kyori.adventure.key.Key packSkinKey(String archetype) {
        File serverRoot = getDataFolder().getParentFile().getParentFile();
        File png = new File(serverRoot, "resource-pack/assets/companion/textures/" + archetype + ".png");
        if (!png.isFile()) {
            getLogger().fine("No bundled skin at " + png.getPath() + "; " + archetype
                    + " uses default/URL skin.");
            return null;
        }
        return net.kyori.adventure.key.Key.key("companion", archetype);
    }

    void setSkinUrl(String archetype, String url) {
        String safeUrl = url == null ? "" : url.trim();
        if (archetype.equals("yui")) {
            yuiSkinUrl = safeUrl;
        } else {
            sandroneSkinUrl = safeUrl;
        }
        getConfig().set(archetype + ".skin-url", safeUrl);
        saveConfig();
    }

    private void runSelfTest() {
        Player alreadyOnline = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
        if (alreadyOnline != null) {
            Bukkit.getScheduler().runTaskLater(this, () -> testSequentialSpawn(alreadyOnline), 60L);
            return;
        }
        SelfTestListener listener = new SelfTestListener();
        selfTestListener = listener;
        Bukkit.getPluginManager().registerEvents(listener, this);
        getLogger().info("SELF-TEST: waiting for a player to join before testing sequential multi-NPC spawn.");
    }

    private SelfTestListener selfTestListener;

    private final class SelfTestListener implements Listener {
        @EventHandler
        public void onJoin(PlayerJoinEvent event) {
            Player owner = event.getPlayer();
            if (selfTestListener != null) {
                HandlerList.unregisterAll(selfTestListener);
                selfTestListener = null;
            }
            Bukkit.getScheduler().runTaskLater(CompanionNpcsPlugin.this,
                    () -> testSequentialSpawn(owner), 20L);
        }
    }

    private void testSequentialSpawn(Player owner) {
        try {
            manager.spawn(owner, "yui");
            Entity yui = manager.getCompanion(owner.getUniqueId(), "yui");
            manager.spawn(owner, "sandrone");
            Entity sandrone = manager.getCompanion(owner.getUniqueId(), "sandrone");
            boolean bothAlive = yui != null && yui.isValid() && !yui.isDead()
                    && sandrone != null && sandrone.isValid() && !sandrone.isDead();
            if (bothAlive) {
                getLogger().info("SELF-TEST PASS: spawned Yui, then Sandrone; both mannequins remain alive simultaneously.");
            } else {
                getLogger().severe("SELF-TEST FAIL: spawned Yui, then Sandrone; expected both alive, yui="
                        + describe(yui) + ", sandrone=" + describe(sandrone));
            }
        } catch (RuntimeException ex) {
            getLogger().log(java.util.logging.Level.SEVERE, "SELF-TEST FAIL: sequential multi-NPC spawn", ex);
        } finally {
            manager.despawn(owner);
        }
    }

    private static String describe(Entity entity) {
        if (entity == null) {
            return "missing";
        }
        return "valid=" + entity.isValid() + ",dead=" + entity.isDead();
    }

    private final class CompanionCommand implements CommandExecutor, TabCompleter {
        private final String archetype;

        private CompanionCommand(String archetype) {
            this.archetype = archetype;
        }

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("This command can only be used by a player.", NamedTextColor.RED));
                return true;
            }

            if (args.length == 0) {
                manager.spawn(player, archetype);
                player.sendMessage(Component.text(labelFor(archetype) + " is now following you.", colorFor(archetype)));
                return true;
            }

            String option = args[0].toLowerCase(Locale.ROOT);
            if (option.equals("off") || option.equals("remove") || option.equals("clear")) {
                manager.despawn(player, archetype);
                player.sendMessage(Component.text(labelFor(archetype) + " dismissed.", colorFor(archetype)));
                return true;
            }
            if (option.equals("default") || option.equals("reset")) {
                setSkinUrl(archetype, "");
                manager.spawn(player, archetype);
                player.sendMessage(Component.text(labelFor(archetype) + " skin reset.", colorFor(archetype)));
                return true;
            }
            if (args[0].startsWith("http://") || args[0].startsWith("https://")) {
                setSkinUrl(archetype, args[0]);
                manager.spawn(player, archetype);
                player.sendMessage(Component.text(labelFor(archetype) + " skin URL saved.", colorFor(archetype)));
                return true;
            }
            player.sendMessage(Component.text("Usage: /" + label + " [default|off|https://skin-url]", NamedTextColor.YELLOW));
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (args.length == 1) {
                List<String> options = new ArrayList<>(List.of("default", "off"));
                String prefix = args[0].toLowerCase(Locale.ROOT);
                options.removeIf(option -> !option.startsWith(prefix));
                return options;
            }
            return List.of();
        }
    }

    private final class ModeCommand implements CommandExecutor, TabCompleter {
        private final CompanionManager.FollowMode mode;
        private final String label;

        private ModeCommand(CompanionManager.FollowMode mode, String label) {
            this.mode = mode;
            this.label = label;
        }

        @Override
        public boolean onCommand(CommandSender sender, Command command, String commandLabel, String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("This command can only be used by a player.", NamedTextColor.RED));
                return true;
            }
            String target = args.length == 0 ? "semua" : args[0].toLowerCase(Locale.ROOT);
            if (!target.equals("yui") && !target.equals("sandrone") && !target.equals("semua")) {
                player.sendMessage(Component.text("Pilih siapa: /" + commandLabel
                        + " [yui|sandrone|semua]", NamedTextColor.YELLOW));
                return true;
            }
            int changed = mode == CompanionManager.FollowMode.CLOSE
                    ? manager.callClose(player, target)
                    : manager.callFollow(player, target);
            if (changed == 0) {
                player.sendMessage(Component.text("Tidak ada pendamping yang bisa diubah."
                        + " Panggil dengan /yui-npc atau /sandrone-npc dulu.", NamedTextColor.YELLOW));
            } else {
                player.sendMessage(Component.text("Mode " + label + " aktif untuk " + target
                        + ". Berlaku sampai kamu mengganti mode.", NamedTextColor.GREEN));
            }
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (args.length == 1) {
                List<String> options = new ArrayList<>(List.of("yui", "sandrone", "semua"));
                String prefix = args[0].toLowerCase(Locale.ROOT);
                options.removeIf(option -> !option.startsWith(prefix));
                return options;
            }
            return List.of();
        }
    }

    private final class AdminCommand implements CommandExecutor, TabCompleter {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (args.length == 0) {
                sender.sendMessage(Component.text("Usage: /" + label + " <clear|reload>", NamedTextColor.YELLOW));
                return true;
            }
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "clear" -> {
                    manager.despawnEverything();
                    sender.sendMessage(Component.text("All companion mannequins removed.", NamedTextColor.GREEN));
                }
                case "reload" -> {
                    reloadConfig();
                    readConfig();
                    sender.sendMessage(Component.text("Companion NPC config reloaded.", NamedTextColor.GREEN));
                }
                default -> sender.sendMessage(Component.text("Usage: /" + label + " <clear|reload>", NamedTextColor.YELLOW));
            }
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (args.length == 1) {
                List<String> options = new ArrayList<>(List.of("clear", "reload"));
                String prefix = args[0].toLowerCase(Locale.ROOT);
                options.removeIf(option -> !option.startsWith(prefix));
                return options;
            }
            return List.of();
        }
    }

    private static NamedTextColor colorFor(String archetype) {
        return archetype.equals("yui") ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.AQUA;
    }

    private static String labelFor(String archetype) {
        return archetype.equals("yui") ? "Yui" : "Sandrone";
    }
}
