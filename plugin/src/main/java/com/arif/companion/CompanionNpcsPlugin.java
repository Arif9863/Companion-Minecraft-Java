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
import org.bukkit.profile.PlayerTextures;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class CompanionNpcsPlugin extends JavaPlugin {

    private CompanionManager manager;
    private CompanionListener listener;
    private PackHttpServer packServer;
    private double comfortDistance;
    private double closeDistance;
    private int teleportDistance;
    private int closeTeleportDistance;
    private int idleLookRadius;
    private int idleLookStandstillDistance;
    private int packHttpPort;
    private double attackSearchRadius;
    private double waitThreatRadius;
    private double attackDamage;
    private long attackCooldownTicks;
    private double yuiSkeletonMarkRadius;
    private double sandroneCreeperMarkRadius;
    /**
     * UUIDs allowed to use Yui and Sandrone.
     *
     * <p>These two companions are reserved: only the players listed here can call them or
     * change their behaviour. An empty list means nobody is allowed, so the companion is
     * off limits until a UUID is configured.</p>
     */
    private final Set<UUID> yuiSandroneAllowed = new HashSet<>();
    /** The player Eris is reserved for; only they can claim her. */
    private UUID erisOwner;
    /** Players allowed to use Eris while she is still unclaimed. */
    private final Set<UUID> erisTesters = new HashSet<>();

    /**
     * Whether the given player may use Yui and Sandrone.
     *
     * <p>OP does not override this list — the restriction is about the characters themselves,
     * not about server administration.</p>
     */
    boolean mayUseYuiAndSandrone(UUID playerId) {
        return yuiSandroneAllowed.contains(playerId);
    }

    /**
     * Scoreboard tag marking a player who may use Yui and Sandrone.
     *
     * <p>The Skript commands {@code /yui} and {@code /sandrone} print their own friendly or
     * refusal lines, and they cannot read the plugin's config. This tag is the shared answer:
     * the plugin puts it on every allowed player, and the Skript scripts check it before they
     * say anything. Without it an outsider would still receive "Yui is ready to help you"
     * even though the plugin refuses to spawn the companion.</p>
     */
    static final String COMPANION_USER_TAG = "companion_npc_user";

    /**
     * Marks or unmarks a player according to the configured companion owners.
     *
     * <p>Called on join and after a config reload, so a player who loses access loses the tag
     * as well.</p>
     */
    void refreshCompanionUserTag(Player player) {
        if (mayUseYuiAndSandrone(player.getUniqueId())) {
            player.addScoreboardTag(COMPANION_USER_TAG);
        } else {
            player.removeScoreboardTag(COMPANION_USER_TAG);
        }
    }

    /** Applies the companion-user tag to every player who is online right now. */
    void refreshCompanionUserTags() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            refreshCompanionUserTag(player);
        }
    }

    /** The player Eris is reserved for, or null when she is free for anybody to claim. */
    UUID erisOwner() {
        return erisOwner;
    }

    /** Players allowed to use Eris while nobody has claimed her yet. */
    Set<UUID> erisTesters() {
        return erisTesters;
    }

    /**
     * The companions the shared group commands may address.
     *
     * <p>These are Arif's own companions: the original pair plus the four newer ones. Eris has
     * her own commands and is deliberately not in this list.</p>
     */
    private static final List<String> GROUP_TARGETS =
            List.of("yui", "sandrone", "furina", "ei", "columbina", "escoffier", "semua");

    /**
     * Whether the given name addresses a member of the shared companion group.
     *
     * @param target the name the player typed
     */
    private static boolean isGroupTarget(String target) {
        return GROUP_TARGETS.contains(target.toLowerCase(Locale.ROOT));
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // Bring the config on disk up to date first, then read it: a value that was just added
        // to the file by hand is picked up in the same startup.
        updateConfigFile();
        reloadConfig();
        readConfig();
        manager = new CompanionManager(this);
        File serverRoot = getDataFolder().getParentFile().getParentFile();
        SkriptPouchMigrator.migrateIfPresent(this, serverRoot);

        registerCommand("yui-npc", new CompanionCommand("yui"));
        registerCommand("sandrone-npc", new CompanionCommand("sandrone"));
        registerCommand("furina-npc", new CompanionCommand("furina"));
        registerCommand("ei-npc", new CompanionCommand("ei"));
        registerCommand("columbina-npc", new CompanionCommand("columbina"));
        registerCommand("escoffier-npc", new CompanionCommand("escoffier"));
        registerCommand("kesini", new ModeCommand(CompanionManager.FollowMode.CLOSE, "Kesini"));
        registerCommand("ikut", new ModeCommand(CompanionManager.FollowMode.FOLLOW, "Ikut"));
        registerCommand("tunggu", new WaitCommand());
        registerCommand("istri", new IstriCommand());
        registerCommand("companionnpc", new AdminCommand());
        registerCommand("eris", new ErisCommand());
        listener = new CompanionListener(this);
        Bukkit.getPluginManager().registerEvents(listener, this);
        // Mark the players who may use Yui and Sandrone, so the Skript scripts can tell an
        // outsider from the owner before they print their own lines.
        refreshCompanionUserTags();

        startPackServer();

        getLogger().info("Companion NPCs enabled. Commands: /yui-npc, /sandrone-npc, /ikut, /kesini, /tunggu, /companionnpc");
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

    /**
     * Parses a UUID from the config, ignoring a blank entry and reporting a broken one.
     *
     * @return the UUID, or null when the value is blank or invalid
     */
    private UUID parseOptionalUuid(String value, String setting) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ex) {
            getLogger().warning("Ignoring invalid UUID in " + setting + ": " + value);
            return null;
        }
    }

    private void readConfig() {
        FileConfiguration config = getConfig();
        yuiSandroneAllowed.clear();
        List<String> configuredOwners = config.getStringList("companion-owners");
        if (configuredOwners.isEmpty()) {
            // Existing installation default: reserve Yui and Sandrone to Arif9863.
            configuredOwners = List.of("515402e5-832e-311e-b35f-3f3f0eca8f4f");
            config.set("companion-owners", configuredOwners);
            // Write the repaired list back to disk. Without this the file on disk keeps its old
            // shape, and an admin editing it would not find the owner list at all.
            saveConfig();
        }
        for (String entry : configuredOwners) {
            try {
                yuiSandroneAllowed.add(UUID.fromString(entry.trim()));
            } catch (IllegalArgumentException ex) {
                getLogger().warning("Ignoring invalid UUID in companion-owners: " + entry);
            }
        }
        erisOwner = parseOptionalUuid(config.getString("eris-owner", ""), "eris-owner");
        erisTesters.clear();
        for (String entry : config.getStringList("eris-testers")) {
            UUID tester = parseOptionalUuid(entry, "eris-testers");
            if (tester != null) {
                erisTesters.add(tester);
            }
        }
        // Skin URLs for the other archetypes are read straight from the config when they are
        // needed, so a new companion needs no field of its own here.
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
        // How far a hostile mob may be from a companion before it defends itself and its owner.
        attackSearchRadius = Math.max(2.0, Math.min(16.0, config.getDouble("attack-search-radius", 8.0)));
        // How far a waiting companion reacts to hostile mobs around the spot it was told to
        // wait at. Detection and fighting range in one: a mob inside it is fought, one further
        // out is left alone, and the companion holds its post.
        waitThreatRadius = Math.max(4.0, Math.min(48.0, config.getDouble("wait-threat-radius", 12.0)));
        // Damage per melee swing.
        attackDamage = Math.max(1.0, Math.min(40.0, config.getDouble("attack-damage", 5.0)));
        // Ticks between two swings (20 ticks = one second).
        attackCooldownTicks = Math.max(4L, Math.min(40L, config.getLong("attack-cooldown-ticks", 16L)));
        // Companion-specific defensive markers (10-24 blocks, default 16).
        yuiSkeletonMarkRadius = Math.max(10.0, Math.min(24.0, config.getDouble("yui-skeleton-mark-radius", 16.0)));
        sandroneCreeperMarkRadius = Math.max(10.0, Math.min(24.0, config.getDouble("sandrone-creeper-mark-radius", 16.0)));
    }

    /**
     * Brings the {@code config.yml} on disk up to date with the options this plugin version
     * knows about.
     *
     * <p>Bukkit only writes the bundled config when there is none yet, so a config created by an
     * older version keeps its old shape and never gains the new options. Adding the missing ones
     * — without touching the values that are already set — is what lets the owner discover and
     * change them.</p>
     */
    private void updateConfigFile() {
        try {
            File file = getDataFolder().toPath().resolve("config.yml").toFile();
            org.bukkit.configuration.file.YamlConfiguration defaults =
                    new org.bukkit.configuration.file.YamlConfiguration();
            try (java.io.InputStream stream = getResource("config.yml")) {
                if (stream == null) {
                    return;
                }
                defaults.loadFromString(new String(stream.readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8));
            }
            String report = ConfigUpdater.merge(file, defaults);
            getLogger().info("Config: " + report);
        } catch (Exception ex) {
            getLogger().warning("Could not update config.yml: " + ex.getMessage());
        }
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
        listener = null;
    }

    public CompanionManager getManager() {
        return manager;
    }

    String skinUrl(String archetype) {
        return getConfig().getString(archetype.toLowerCase(Locale.ROOT) + ".skin-url", "");
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

    /** How far an owner-threatening hostile may be for a companion to defend against. */
    double attackSearchRadius() {
        return attackSearchRadius;
    }

    /** How far a waiting companion reacts to hostile mobs around the spot it was told to wait at. */
    double waitThreatRadius() {
        return waitThreatRadius;
    }

    /** Damage a companion's swing deals to a hostile mob. */
    double attackDamage() {
        return attackDamage;
    }

    /** Ticks between two melee swings. */
    long attackCooldownTicks() {
        return attackCooldownTicks;
    }

    /** Radius around the owner where Yui marks skeletons while combat mode is enabled. */
    double yuiSkeletonMarkRadius() {
        return yuiSkeletonMarkRadius;
    }

    /** Radius around the owner where Sandrone marks creepers while combat mode is enabled. */
    double sandroneCreeperMarkRadius() {
        return sandroneCreeperMarkRadius;
    }

    /**
     * Returns the resource pack skin key when the bundled PNG exists, otherwise null.
     *
     * <p>Checked on disk so the mannequin never points at a texture the pack does not ship — a
     * missing key would render as the missing-texture pattern on the client.</p>
     */
    net.kyori.adventure.key.Key packSkinKey(String archetype) {
        String texture = textureFileFor(archetype);
        if (texture == null) {
            return null;
        }
        File serverRoot = getDataFolder().getParentFile().getParentFile();
        File png = new File(serverRoot, "resource-pack/assets/companion/textures/" + texture + ".png");
        if (!png.isFile()) {
            getLogger().fine("No bundled skin at " + png.getPath() + "; " + archetype
                    + " uses default/URL skin.");
            return null;
        }
        return net.kyori.adventure.key.Key.key("companion", texture);
    }

    /**
     * The texture file name an archetype uses in the resource pack.
     *
     * <p>A resource-pack path is a Minecraft resource location, whose rules allow only
     * {@code a-z0-9_-.} — spaces are illegal — so the file on disk must carry the same name as
     * the archetype. Sandrone's skin is now the mirrored variant under the plain
     * {@code sandrone.png} name, so every archetype resolves to its own lower-cased name and the
     * companion appears under a resource location the client can actually load.
     *
     * @return the file name without its extension, or {@code null} when the archetype is unknown
     */
    private String textureFileFor(String archetype) {
        if (archetype == null) {
            return null;
        }
        String name = archetype.toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z0-9_\\-.]+")) {
            return null;
        }
        return name;
    }

    /**
     * The skin model (slim or classic) an archetype's texture was drawn for.
     *
     * <p>A slim skin is drawn with three-pixel-wide arms; a classic skin with four. Using the
     * wrong one makes the arms render at the wrong width on the client.</p>
     */
    PlayerTextures.SkinModel skinModelFor(String archetype) {
        return switch (archetype == null ? "" : archetype.toLowerCase(Locale.ROOT)) {
            case "yui", "eris" -> PlayerTextures.SkinModel.SLIM;
            default -> PlayerTextures.SkinModel.CLASSIC;
        };
    }

    void setSkinUrl(String archetype, String url) {
        String safeUrl = url == null ? "" : url.trim();
        getConfig().set(archetype.toLowerCase(Locale.ROOT) + ".skin-url", safeUrl);
        saveConfig();
        reloadConfig();
        readConfig();
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
            if (!mayUseYuiAndSandrone(player.getUniqueId())) {
                // The newer companions have a deny line of their own; the pair does not.
                manager.denyCompanion(player, archetype);
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
            if (!isGroupTarget(target)) {
                if (target.equals("eris")) {
                    player.sendMessage(Component.text("Eris punya command sendiri: /eris "
                            + label.toLowerCase(Locale.ROOT), NamedTextColor.YELLOW));
                    return true;
                }
                player.sendMessage(Component.text("Pilih siapa: /" + commandLabel
                        + " [yui|sandrone|furina|ei|columbina|escoffier|semua]", NamedTextColor.YELLOW));
                return true;
            }
            if (!mayUseYuiAndSandrone(player.getUniqueId())) {
                player.sendMessage(Component.text("Yui dan Sandrone hanya untuk pemiliknya.",
                        NamedTextColor.RED));
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
                List<String> options = new ArrayList<>(GROUP_TARGETS);
                String prefix = args[0].toLowerCase(Locale.ROOT);
                options.removeIf(option -> !option.startsWith(prefix));
                return options;
            }
            return List.of();
        }
    }

    /**
     * The {@code /tunggu} command: the companion stops following the owner and waits where it is.
     *
     * <p>It keeps watching the owner from where it stands, wanders a few blocks around its own
     * spot, and is never caught up by teleport — being far from the owner is exactly what was
     * asked for.</p>
     */
    private final class WaitCommand implements CommandExecutor, TabCompleter {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String commandLabel, String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("This command can only be used by a player.", NamedTextColor.RED));
                return true;
            }
            String target = args.length == 0 ? "semua" : args[0].toLowerCase(Locale.ROOT);
            if (!isGroupTarget(target)) {
                if (target.equals("eris")) {
                    player.sendMessage(Component.text("Eris punya command sendiri: /eris tunggu",
                            NamedTextColor.YELLOW));
                    return true;
                }
                player.sendMessage(Component.text("Pilih siapa: /" + commandLabel
                        + " [yui|sandrone|furina|ei|columbina|escoffier|semua]", NamedTextColor.YELLOW));
                return true;
            }
            if (!mayUseYuiAndSandrone(player.getUniqueId())) {
                player.sendMessage(Component.text("Yui dan Sandrone hanya untuk pemiliknya.",
                        NamedTextColor.RED));
                return true;
            }
            int changed = manager.callWait(player, target);
            if (changed == 0) {
                player.sendMessage(Component.text("Tidak ada pendamping yang bisa diubah."
                        + " Panggil dengan /yui-npc atau /sandrone-npc dulu.", NamedTextColor.YELLOW));
            } else {
                player.sendMessage(Component.text("Tunggu di tempat untuk " + target
                        + ". Mereka menunggu di tempat.", NamedTextColor.GREEN));
            }
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (args.length == 1) {
                List<String> options = new ArrayList<>(GROUP_TARGETS);
                String prefix = args[0].toLowerCase(Locale.ROOT);
                options.removeIf(option -> !option.startsWith(prefix));
                return options;
            }
            return List.of();
        }
    }

    /**
     * The {@code /istri} command: calls every companion that belongs to this owner at once.
     *
     * <p>It is the one-step version of typing each {@code /yui-npc}, {@code /sandrone-npc},
     * {@code /furina-npc} and so on in turn. Companions that are already present are replaced,
     * so running it again is a clean reset rather than a duplicate spawn. Eris is not part of
     * the group, so she is never touched here.</p>
     */
    private final class IstriCommand implements CommandExecutor, TabCompleter {

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("This command can only be used by a player.",
                        NamedTextColor.RED));
                return true;
            }
            // The argument is a formality: /istri and /istri arif both mean the same thing.
            if (args.length > 0 && !args[0].equalsIgnoreCase("arif")) {
                player.sendMessage(Component.text("Usage: /istri [arif]", NamedTextColor.YELLOW));
                return true;
            }
            if (!mayUseYuiAndSandrone(player.getUniqueId())) {
                player.sendMessage(Component.text(
                        "Istri-istri Arif hanya untuk Arif sendiri.", NamedTextColor.RED));
                return true;
            }

            List<String> summoned = new ArrayList<>();
            for (String archetype : CompanionManager.pairedArchetypes()) {
                if (manager.spawn(player, archetype)) {
                    summoned.add(labelFor(archetype));
                }
            }
            if (summoned.isEmpty()) {
                player.sendMessage(Component.text(
                        "Tidak ada istri yang bisa dipanggil sekarang.", NamedTextColor.YELLOW));
                return true;
            }
            // They arrive one by one, but the owner gets a single line instead of six.
            player.sendMessage(Component.text(
                    summoned.size() + " istri siap mengikutimu: " + String.join(", ", summoned) + ".",
                    NamedTextColor.LIGHT_PURPLE));
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias,
                                          String[] args) {
            if (args.length == 1) {
                String prefix = args[0].toLowerCase(Locale.ROOT);
                return List.of("arif").stream().filter(option -> option.startsWith(prefix)).toList();
            }
            return List.of();
        }
    }

    /**
     * The {@code /eris} command.
     *
     * <p>Eris is a standalone companion with one permanent owner: whoever calls her first keeps
     * her. Everyone else is refused with Eris's own deny line, and only the owner can change
     * her behaviour afterwards.</p>
     */
    private final class ErisCommand implements CommandExecutor, TabCompleter {
        private static final List<String> SUBCOMMANDS =
                List.of("ikut", "kesini", "tunggu", "off");

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("This command can only be used by a player.", NamedTextColor.RED));
                return true;
            }
            if (!player.hasPermission("companion.npc.eris")) {
                player.sendMessage(Component.text("Kamu tidak punya izin memakai Eris.", NamedTextColor.RED));
                return true;
            }
            // Eris is reserved: only her owner and, while she is still unclaimed, her testers.
            if (!manager.mayUseEris(player.getUniqueId())) {
                manager.denyEris(player);
                return true;
            }
            // Once Eris belongs to somebody, nobody else may command her at all.
            if (manager.erisClaimed() && !manager.isErisOwner(player.getUniqueId())) {
                manager.denyEris(player);
                return true;
            }
            if (args.length == 0) {
                if (!manager.claimEris(player)) {
                    manager.denyEris(player);
                    return true;
                }
                manager.spawn(player, "eris", true);
                player.sendMessage(Component.text("Eris sekarang mengikuti Ferdi-Kun.", NamedTextColor.RED));
                return true;
            }

            String option = args[0].toLowerCase(Locale.ROOT);
            switch (option) {
                case "off", "remove", "clear" -> {
                    manager.despawn(player, "eris");
                    player.sendMessage(Component.text("Eris disingkirkan.", NamedTextColor.RED));
                }
                case "ikut" -> applyErisMode(player, CompanionManager.FollowMode.FOLLOW, "Ikut");
                case "kesini" -> applyErisMode(player, CompanionManager.FollowMode.CLOSE, "Kesini");
                case "tunggu" -> applyErisWait(player);
                default -> player.sendMessage(Component.text(
                        "Usage: /" + label + " [ikut|kesini|tunggu|off]", NamedTextColor.YELLOW));
            }
            return true;
        }

        private void applyErisMode(Player player, CompanionManager.FollowMode mode, String name) {
            int changed = mode == CompanionManager.FollowMode.CLOSE
                    ? manager.callClose(player, "eris")
                    : manager.callFollow(player, "eris");
            if (changed == 0) {
                player.sendMessage(Component.text("Eris belum dipanggil. Gunakan /eris dulu.",
                        NamedTextColor.YELLOW));
                return;
            }
            player.sendMessage(Component.text("Mode " + name + " aktif untuk Eris.", NamedTextColor.GREEN));
        }

        private void applyErisWait(Player player) {
            int changed = manager.callWait(player, "eris");
            if (changed == 0) {
                player.sendMessage(Component.text("Eris belum dipanggil. Gunakan /eris dulu.",
                        NamedTextColor.YELLOW));
                return;
            }
            player.sendMessage(Component.text("Eris menunggu di tempat.",
                    NamedTextColor.GREEN));
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (args.length == 1) {
                List<String> options = new ArrayList<>(SUBCOMMANDS);
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
                sender.sendMessage(Component.text("Usage: /" + label + " <clear|reload|config>",
                        NamedTextColor.YELLOW));
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
                    refreshCompanionUserTags();
                    sender.sendMessage(Component.text("Companion NPC config reloaded.", NamedTextColor.GREEN));
                }
                case "config" -> {
                    // Adds the options this plugin version knows about to the file on disk,
                    // without touching the ones that are already set.
                    reloadConfig();
                    readConfig();
                    updateConfigFile();
                    sender.sendMessage(Component.text("Config file updated. Restart or reload to apply.",
                            NamedTextColor.GREEN));
                }
                default -> sender.sendMessage(Component.text(
                        "Usage: /" + label + " <clear|reload|config>", NamedTextColor.YELLOW));
            }
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (args.length == 1) {
                List<String> options = new ArrayList<>(List.of("clear", "reload", "config"));
                String prefix = args[0].toLowerCase(Locale.ROOT);
                options.removeIf(option -> !option.startsWith(prefix));
                return options;
            }
            return List.of();
        }
    }

    private static NamedTextColor colorFor(String archetype) {
        return switch (archetype.toLowerCase(Locale.ROOT)) {
            case "yui" -> NamedTextColor.LIGHT_PURPLE;
            case "eris" -> NamedTextColor.RED;
            case "furina" -> NamedTextColor.AQUA;
            case "ei" -> NamedTextColor.GOLD;
            case "columbina" -> NamedTextColor.LIGHT_PURPLE;
            case "escoffier" -> NamedTextColor.GRAY;
            default -> NamedTextColor.AQUA;
        };
    }

    private static String labelFor(String archetype) {
        return switch (archetype.toLowerCase(Locale.ROOT)) {
            case "yui" -> "Yui";
            case "eris" -> "Eris";
            case "furina" -> "Furina";
            case "ei" -> "Ei";
            case "columbina" -> "Columbina";
            case "escoffier" -> "Escoffier";
            default -> "Sandrone";
        };
    }
}
