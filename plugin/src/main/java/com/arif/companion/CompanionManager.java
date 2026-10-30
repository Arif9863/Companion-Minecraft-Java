package com.arif.companion;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.Openable;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.profile.PlayerTextures;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Spawns and tracks the skin-bearing mannequin companions.
 *
 * <p>The companion is a {@link Mannequin}: 26.3's entity that renders with the player model, so it
 * can show a real character skin via {@link Mannequin#setProfile}. It extends {@code Avatar}, not
 * {@code Mob}, so it has no pathfinder and no vanilla AI — movement is handled here by teleporting
 * it to follow its owner, matching how the Skript companions already behave.</p>
 */
public final class CompanionManager {

    /** Scoreboard tag identifying companion NPCs, so they can be found and cleaned up. */
    public static final String TAG = "companion_npc";

    /** Resource pack namespace whose textures supply the character skins. */
    private static final String SKIN_NAMESPACE = "companion";

    private record CompanionKey(UUID ownerId, String archetype) {}

    /**
     * The two standing behaviours a companion can have.
     *
     * <p>A mode stays in force until another command replaces it — there is no timeout. The
     * formation is therefore not a permanent behaviour but one of two choices, which is what
     * makes both a cramped room and open exploration work without a separate plugin.</p>
     */
    public enum FollowMode {
        /** Keeps the formation and a comfortable distance — good for exploring outdoors. */
        FOLLOW,
        /** Stays as close as the geometry allows — good for small rooms. */
        CLOSE
    }

    /** A* and movement budget: never exceed a small per-tick planning window. */
    private static final long REPLAN_INTERVAL_TICKS = 100L;
    /**
     * How often the route is refreshed while the companion is chasing a mob.
     *
     * <p>6 ticks (0.3 seconds). A fight is too dynamic for the relaxed 5-second follow interval:
     * a stale route makes the companion walk towards where the mob used to be.</p>
     */
    private static final long COMBAT_REPLAN_INTERVAL_TICKS = 6L;
    private static final double GOAL_REPLAN_DISTANCE_SQUARED = 9.0;
    private static final double WAYPOINT_REACHED_DISTANCE_SQUARED = 0.36;
    private static final double STUCK_PROGRESS_DISTANCE_SQUARED = 0.0225;
    /**
     * How long a companion may make no progress before it is considered stuck.
     *
     * <p>3 seconds (60 ticks). Long enough that a companion squeezing through a doorway or
     * stepping up a block is not mistaken for a jam, short enough that a genuine wall still
     * gets rescued quickly.</p>
     */
    private static final long STUCK_TIMEOUT_TICKS = 60L;
    /** Vertical separation at which a grounded companion is considered fallen behind. */
    private static final double EMERGENCY_FALL_DISTANCE = 8.0;
    /**
     * How long a companion may fail to find a route before it is rescued to its owner.
     *
     * <p>5 seconds (100 ticks). Long enough for the route to be recomputed a few times, short
     * enough that a companion stranded at the bottom of a ravine does not wait for the owner to
     * walk 24 blocks away.</p>
     */
    private static final long NO_PATH_RESCUE_TICKS = 100L;
    private static final long FAILED_PLAN_RETRY_TICKS = 20L;
    private static final int PATH_RADIUS = 48;
    /**
     * How close a door must be before the companion opens it.
     *
     * <p>2 blocks: close enough to walk into, tight enough that a door in the next room or
     * merely within earshot stays shut.</p>
     */
    private static final double DOOR_OPEN_DISTANCE_SQUARED = 4.0;
    /**
     * How far past a door the companion must be before the door is closed again.
     *
     * <p>3.5 blocks — comfortably more than the open radius. A door may only be shut once the
     * companion has genuinely walked through it. Closing it sooner makes the door swing shut into
     * the companion, which bounces it back and leaves it opening and closing the door forever
     * without ever getting through.</p>
     */
    private static final double DOOR_CLOSE_DISTANCE_SQUARED = 12.25;
    /**
     * How long a door may stay open waiting for a companion that is still on the way.
     *
     * <p>6 seconds (120 ticks). Long enough for the second companion to reach the doorway after
     * the first one shut it, short enough that a companion which never shows up does not leave the
     * house open for the rest of the night.</p>
     */
    private static final long DOOR_HOLD_TIMEOUT_TICKS = 120L;
    /**
     * How close a companion must be to a door to count as "still on its way through".
     *
     * <p>6 blocks. A companion further away than this is not waiting at the door and must not keep
     * it open.</p>
     */
    private static final double DOOR_PENDING_DISTANCE_SQUARED = 36.0;
    /**
     * Minimum dot product between the direction to the owner and the direction to a door.
     *
     * <p>0 means the door has to be directly ahead; a little slack lets a door slightly off to
     * the side still be opened when it really is the way through.</p>
     */
    private static final double DOOR_DIRECTION_THRESHOLD = -0.25;
    /**
     * Maximum angle between the companion's body direction and the mob it glances at.
     *
     * <p>Beyond roughly 90 degrees the head would have to turn further than a neck allows, and
     * Minecraft compensates by twisting the whole body, which looks broken. A companion simply
     * does not notice something behind it.</p>
     */
    private static final double MAX_GLANCE_ANGLE_DEGREES = 90.0;
    /** Ticks a glance lasts: 2-3 seconds, so the behaviour varies between companions. */
    private static final long GLANCE_DURATION_TICKS = 40L;
    /** Extra random ticks so two companions never glance in lockstep. */
    private static final long GLANCE_DURATION_VARIANCE_TICKS = 20L;
    /** Idle ticks between two glance checks: 3-4 seconds, deliberately unhurried. */
    private static final long GLANCE_CHECK_INTERVAL_TICKS = 60L;
    /** Movement speed below which the companion counts as standing still. */
    private static final double STILL_SPEED = 0.02;
    /** Minimum horizontal movement before a block-based footstep can play. */
    private static final double FOOTSTEP_SPEED = 0.055;
    /** Footstep cadence while running, in server ticks (20 ticks = 1 second). */
    private static final long FOOTSTEP_INTERVAL_TICKS = 9L;
    /** How far beside the owner a companion appears when summoned or rescued. */
    private static final double SIDE_PLACEMENT_DISTANCE = 1.2;
    /** How far to the owner's left or right a companion stands while following. */
    private static final double SIDE_FORMATION_OFFSET = 1.6;
    /**
     * How far to the owner's left or right a companion stands in close mode.
     *
     * <p>Close mode ignores the formation entirely: both companions converge on this one spot
     * ahead of the owner, so they may end up pressed against each other. That is intended —
     * an emergency call should bring them in tight, not tidy.</p>
     */
    private static final double CLOSE_FORMATION_OFFSET = 0.7;
    /** Search radius used when checking whether a close-mode spot is actually reachable. */
    private static final int CLOSE_SEARCH_RADIUS = 12;
    /**
     * How far the owner may walk before the close target is recomputed.
     *
     * <p>The close target is only worth recomputing when the owner has actually moved: while it
     * stands still the spot stays valid, and a fresh A* search every tick would be wasteful.</p>
     */
    private static final double CLOSE_TARGET_REFRESH_DISTANCE = 1.0;
    /**
     * How far the owner may walk before a close-mode spot is refreshed even without a move.
     *
     * <p>A safety net for the case where the owner stays put but the geometry around them
     * changed, for example a door that got closed.</p>
     */
    private static final long CLOSE_TARGET_REFRESH_TICKS = 40L;
    /**
     * Horizontal radius of the space the owner can walk to around themselves.
     *
     * <p>Used to tell which side of a wall a candidate spot lies on: a spot the owner cannot
     * walk to is on the far side of some wall, and must never be chosen.</p>
     */
    private static final int OWNER_SPACE_RADIUS = 3;
    /** Node budget for that local walk, so the search stays cheap. */
    private static final int OWNER_SPACE_BUDGET = 512;
    /** How far from the owner a close-mode spot may lie. */
    private static final double CLOSE_TARGET_MAX_DISTANCE = 2.5;
    /** How many sideways escape attempts a jammed companion gets before it pauses for replanning. */
    private static final int MAX_SIDE_STEP_ATTEMPTS = 2;
    /**
     * How close the companion aims to the mob when it is first assigned to fight.
     *
     * <p>1.4 blocks — close, but not so close that A*'s block-centred waypoints put the
     * companion inside the mob's collision box.</p>
     */
    private static final double ATTACK_APPROACH_DISTANCE = 1.4;
    /**
     * Horizontal reach of the vanilla player melee attack in Minecraft 1.9+.
     *
     * <p>3.0 blocks in Survival. The mannequin has no built-in player controller, so we enforce
     * the reach explicitly before applying its damage.</p>
     */
    private static final double ATTACK_REACH = 3.0;
    /**
     * Stop walking once within this distance, but only swing at {@link #ATTACK_REACH}.
     *
     * <p>This prevents a companion oscillating against a moving mob: it may settle slightly
     * outside reach rather than continuously pushing into the mob, while the larger reach still
     * lets it strike.</p>
     */
    private static final double ATTACK_STOP_DISTANCE = 2.8;
    /**
     * How long a mob keeps being hunted after it stopped being a valid target.
     *
     * <p>60 ticks (3 seconds). Without this a mob that steps out of range would make the companion
     * turn back and forth at the edge of the search radius.</p>
     */
    private static final long ATTACK_TARGET_GRACE_TICKS = 60L;
    /** How often a new marked hostile may be reported in owner chat (10 seconds). */
    private static final long MARKER_ALERT_COOLDOWN_TICKS = 200L;
    /** Particle marker interval: every 3 ticks so it stays visible without flooding particles. */
    private static final long MARKER_PARTICLE_INTERVAL_TICKS = 3L;
    /** Particle lifetime: short-lived; refreshed periodically while the hostile stays in range. */
    private static final long MARKER_PARTICLE_LIFETIME_TICKS = 8L;
    /** Maximum height offset for particles above a marked hostile. */
    private static final double MARKER_PARTICLE_HEIGHT = 1.2;

    private static final class FollowState {
        private List<org.bukkit.util.Vector> path = List.of();
        private int waypointIndex;
        private Location plannedGoal;
        private long lastPlanTick = Long.MIN_VALUE;
        private long lastAttemptTick = Long.MIN_VALUE;
        /**
         * Tick at which the companion last found itself without a walkable route.
         *
         * <p>Kept apart from {@link #lastAttemptTick}, which is reset on every replan — a
         * companion that retries every second would otherwise never reach the rescue threshold.</p>
         */
        private long noPathSinceTick = Long.MIN_VALUE;
        private org.bukkit.util.Vector lastProgressPosition;
        private long lastProgressTick;
        /** Doors this companion opened and still has to close. */
        private final List<Location> openedDoors = new ArrayList<>();
        /** Mob currently being watched, or null when focused on the owner. */
        private UUID gazeTarget;
        /** Tick at which the current mob gaze ends. */
        private long gazeEndTick;
        /** Earliest tick at which another mob may be considered for gazing. */
        private long nextGazeCheckTick;
        /** Yaw the companion last walked with, reused while idle instead of the owner's yaw. */
        private float lastWalkingYaw;
        /** Sideways nudges already tried for the current jam. */
        private int sideStepAttempts;
        /** Tick at which a danger marker was last sent, to avoid chat spam. */
        private long lastMarkerAlertTick = Long.MIN_VALUE;
        /** UUIDs of hostile mobs this companion is currently marking. */
        private final Set<UUID> markedHostiles = new HashSet<>();
        /** The standing behaviour of this companion: follow in formation, or stay close. */
        private FollowMode mode = FollowMode.FOLLOW;
        /** Whether this companion is allowed to attack hostile mobs near the owner. */
        private boolean attackEnabled;
        /** The mob this companion is currently engaging, or null when none. */
        private UUID attackTarget;
        /** Tick at which the current attack target was picked, used for the grace period. */
        private long attackTargetTick = Long.MIN_VALUE;
        /** Tick of the last swing, used to space the melee hits out. */
        private long lastSwingTick = Long.MIN_VALUE;
        /** Tick of the last ground footstep sound. */
        private long lastFootstepTick = Long.MIN_VALUE;
        /** Whether the mannequin's current pose is swimming, to avoid redundant pose writes. */
        private boolean swimmingPose;
        /** Shared close-mode target selected from a reachable, unblocked nearby spot. */
        private Location closeTarget;
        /** Tick at which the close target was last recomputed. */
        private long closeTargetTick = Long.MIN_VALUE;
        /** The archetype this companion belongs to — Yui or Sandrone. */
        private String archetype;
        /** Stable formation facing, updated from the owner's movement direction, never camera look. */
        private float formationYaw;
        /** Owner's previous position for deriving movement direction. */
        private Location lastOwnerPosition;
        /** Direction the companion last travelled in, for adopting doors somebody else opened. */
        private double lastGoalDx;
        private double lastGoalDz;
        /**
         * Side of the owner this companion holds: {@code +1} left, {@code -1} right, {@code 0}
         * when no side fits. Kept between ticks so a cramped room does not make it pace about.
         */
        private double formationSide;
        /**
         * Whether the held side was taken as a fallback. A fallback side is released as soon as
         * the natural side has room again, so companions drift back to Yui-left / Sandrone-right.
         */
        private boolean formationIsFallback;
    }

    private final CompanionNpcsPlugin plugin;
    /**
     * A door a companion has opened, plus the direction it was passed through.
     *
     * @param axisX horizontal component of the travel direction across the doorway
     * @param axisZ horizontal component of the travel direction across the doorway
     * @param side  which side of the doorway the companion stood on when opening it
     * @param originX companion's horizontal position when the door was claimed
     * @param originZ companion's horizontal position when the door was claimed
     * @param openedTick tick at which the door was claimed, used for the hold timeout
     */
    private record DoorHold(double axisX, double axisZ, double side,
                            double originX, double originZ, long openedTick) {}

    private final Map<CompanionKey, UUID> ownerToNpc = new HashMap<>();
    private final Map<CompanionKey, BukkitTask> followTasks = new HashMap<>();
    private final Map<CompanionKey, FollowState> followStates = new HashMap<>();
    /**
     * The mode each player last selected, keyed by owner and archetype.
     *
     * <p>Persisted so a companion respawned after a server restart or a rejoin keeps the mode the
     * owner chose instead of silently reverting to FOLLOW.</p>
     */
    private final Map<CompanionKey, FollowMode> savedModes = new HashMap<>();
    /**
     * Doors any companion currently holds open, shared across all companions so the second one
     * walking through a doorway doesn't fight the first over who closes it.
     */
    private final Map<Location, DoorHold> sharedOpenDoors = new HashMap<>();

    public CompanionManager(CompanionNpcsPlugin plugin) {
        this.plugin = plugin;
        loadSavedModes();
    }

    /** Loads modes previously chosen by a player from plugins/CompanionNPCs/modes.yml. */
    private void loadSavedModes() {
        File file = new File(plugin.getDataFolder(), "modes.yml");
        if (!file.isFile()) {
            return;
        }
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        for (String ownerId : config.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(ownerId);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Ignoring invalid player UUID in modes.yml: " + ownerId);
                continue;
            }
            for (String archetype : List.of("yui", "sandrone")) {
                String value = config.getString(ownerId + "." + archetype);
                if (value == null) {
                    continue;
                }
                try {
                    savedModes.put(new CompanionKey(uuid, archetype), FollowMode.valueOf(value));
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("Ignoring invalid mode for " + ownerId + "/" + archetype
                            + " in modes.yml: " + value);
                }
            }
        }
    }

    /** Saves all chosen modes. Called only when a player switches modes. */
    private void saveSavedModes() {
        File file = new File(plugin.getDataFolder(), "modes.yml");
        FileConfiguration config = new YamlConfiguration();
        for (Map.Entry<CompanionKey, FollowMode> entry : savedModes.entrySet()) {
            CompanionKey key = entry.getKey();
            config.set(key.ownerId() + "." + key.archetype(), entry.getValue().name());
        }
        try {
            config.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not save companion modes to " + file, ex);
        }
    }

    /**
     * Spawns a companion for a player, replacing only that character's existing mannequin.
     *
     * @param owner     player the companion belongs to
     * @param archetype "yui" or "sandrone"
     */
    public void spawn(Player owner, String archetype) {
        String kind = archetype.toLowerCase(Locale.ROOT);
        CompanionKey key = new CompanionKey(owner.getUniqueId(), kind);
        despawn(owner, kind);

        float initialFormationYaw = owner.getLocation().getYaw();
        Location spot = spawnFormationSpot(owner.getLocation(), kind, initialFormationYaw);
        Mannequin npc = createAt(spot, kind, owner.getName());
        if (npc == null) {
            return;
        }

        ownerToNpc.put(key, npc.getUniqueId());
        startFollowing(key, owner, npc, initialFormationYaw);
        plugin.getLogger().info(() -> "Spawned " + kind + " mannequin for " + owner.getName());
    }

    /** Creates and configures the mannequin, or returns null when spawning fails. */
    private Mannequin createAt(Location spot, String archetype, String ownerName) {
        Mannequin npc;
        try {
            npc = spot.getWorld().spawn(spot, Mannequin.class, mannequin -> {
                mannequin.setAI(false);
                mannequin.setGravity(true);
                mannequin.setInvulnerable(true);
                mannequin.setSilent(true);
                mannequin.setPersistent(false);
                mannequin.setRemoveWhenFarAway(false);
                mannequin.addScoreboardTag(TAG);
                mannequin.addScoreboardTag("companion_" + archetype);
            });
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not spawn " + archetype + " mannequin", ex);
            return null;
        }

        applySkin(npc, archetype, ownerName);
        npc.customName(nameOf(archetype));
        npc.setCustomNameVisible(true);
        // Sembunyikan label bawaan Minecraft ("Nonpemain"/"NPC") di bawah nama custom.
        npc.setDescription(null);
        return npc;
    }

    /** Applies the configured skin, preferring a hosted URL and falling back to the pack texture. */
    private void applySkin(Mannequin npc, String archetype, String ownerName) {
        String url = plugin.skinUrl(archetype);
        Key packKey = plugin.packSkinKey(archetype);
        PlayerTextures.SkinModel model = archetype.equals("yui")
                ? PlayerTextures.SkinModel.SLIM
                : PlayerTextures.SkinModel.CLASSIC;

        ResolvableProfile profile;
        try {
            profile = SkinFactory.build(archetype, url, packKey, model, plugin.getLogger());
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not build skin profile for " + archetype, ex);
            profile = SkinFactory.build(archetype, "", null, model, plugin.getLogger());
        }
        npc.setProfile(profile);
        plugin.getLogger().fine(() -> "Applied " + archetype + " skin for " + ownerName
                + " (url=" + (url == null || url.isBlank() ? "none" : "set")
                + ", pack=" + (packKey == null ? "none" : packKey.asString()) + ")");
    }

    /** Builds the floating name shown above the companion. */
    private static Component nameOf(String archetype) {
        return archetype.equals("yui")
                ? Component.text("Yui", NamedTextColor.LIGHT_PURPLE)
                : Component.text("Sandrone", NamedTextColor.AQUA);
    }

    /** Returns a spot just above the given location, nudged out of any solid block. */
    private static Location spawnSpot(Location base) {
        Location spot = base.clone();
        spot.setY(spot.getY() + 1.0);
        if (!spot.getBlock().getType().isAir()) {
            spot.setY(spot.getY() + 1.0);
        }
        return spot;
    }

    /**
     * Finds a free spot beside the owner for the companion to appear in.
     *
     * <p>Tries the four horizontal sides at the owner's own height first, then one block lower
     * (which is where the owner usually stands when the space beside them is blocked), then gives
     * up and returns the spot directly above the owner so the caller can still place it.</p>
     *
     * <p>Used both when the companion is first summoned and when it has to be rescued, so it
     * always ends up beside the player instead of dropping onto their head.</p>
     */
    private static Location sideSpot(Location ownerLocation) {
        World world = ownerLocation.getWorld();
        if (world == null) {
            return spawnSpot(ownerLocation);
        }
        // Prefer the side the owner is facing, so the companion appears where they are looking.
        float yaw = ownerLocation.getYaw();
        double[] order = {0, Math.PI / 2, -Math.PI / 2, Math.PI};
        for (double offset : order) {
            double angle = yaw + offset;
            double x = ownerLocation.getX() - Math.sin(angle) * SIDE_PLACEMENT_DISTANCE;
            double z = ownerLocation.getZ() + Math.cos(angle) * SIDE_PLACEMENT_DISTANCE;
            Location candidate = new Location(world, x, ownerLocation.getY(), z);
            if (isFreeForCompanion(candidate)) {
                return candidate;
            }
            // One block lower: the owner stands on something and the side is free underneath.
            Location lower = candidate.clone();
            lower.setY(ownerLocation.getY() - 1.0);
            if (isFreeForCompanion(lower)) {
                return lower;
            }
        }
        // Tight spot: no room beside the owner, so fall back to directly above them.
        return spawnSpot(ownerLocation);
    }

    /**
     * The spot a companion aims for while following, with fallbacks so it never leaves the room.
     *
     * <p>Candidates in order: the companion's own side (Yui left, Sandrone right, relative to the
     * owner's facing), the opposite side, then a neutral spot in front of the owner. The first
     * spot the companion can actually stand in wins — and once it picks one, it keeps it
     * ({@code sticky}) until the owner turns far enough that the side genuinely moved, so a
     * companion in a cramped bedroom does not pace in and out to satisfy the formation.</p>
     */
    private Location formationSpot(Mannequin npc, Player owner, FollowState state, boolean close) {
        Location ownerLocation = owner.getLocation();
        World world = ownerLocation.getWorld();
        if (world == null) {
            return ownerLocation.clone();
        }
        if (close) {
            // Emergency: use a nearby point that is both clear and actually reachable from this
            // companion's current location. Never point it at a close-mode coordinate through a wall.
            if (state.closeTarget == null || !world.equals(state.closeTarget.getWorld())
                    || ownerLocation.distanceSquared(state.closeTarget) > CLOSE_TARGET_REFRESH_DISTANCE
                            * CLOSE_TARGET_REFRESH_DISTANCE
                    || Bukkit.getCurrentTick() - state.closeTargetTick >= CLOSE_TARGET_REFRESH_TICKS) {
                Set<PathPlanner.BlockPos> footprint = PathPlanner.ownerSpace(world, ownerLocation,
                        OWNER_SPACE_RADIUS, OWNER_SPACE_BUDGET);
                Set<PathPlanner.BlockPos> companionReachable = PathPlanner.reachableArea(world,
                        npc.getLocation(), CLOSE_SEARCH_RADIUS, OWNER_SPACE_BUDGET);
                state.closeTarget = nearestReachableCloseSpot(npc.getLocation(), ownerLocation,
                        state.formationYaw, footprint, companionReachable);
                state.closeTargetTick = Bukkit.getCurrentTick();
            }
            return state.closeTarget.clone();
        }

        // The side this companion naturally owns: Yui left, Sandrone right.
        boolean naturalLeft = "yui".equalsIgnoreCase(state.archetype);
        // Sticky: hold on to the current side while it still fits, so a cramped room does not
        // make the companion pace about. A side taken as a fallback does not become permanent —
        // the natural side is reclaimed as soon as there is room again.
        double held = state.formationSide;
        if (held == 0.0 || state.formationIsFallback) {
            // (Re)claim the natural side when nothing is held or the hold was only a fallback.
            Location natural = sideSpotAt(ownerLocation, state.formationYaw, naturalLeft ? 1.0 : -1.0);
            if (isFreeForCompanion(natural)) {
                state.formationSide = naturalLeft ? 1.0 : -1.0;
                state.formationIsFallback = false;
                return natural;
            }
            // Natural side blocked: keep whatever was held, or start by trying the other side.
            if (held == 0.0) {
                state.formationSide = naturalLeft ? -1.0 : 1.0;
                state.formationIsFallback = true;
                held = state.formationSide;
            }
        }
        boolean wantLeft = held > 0.0;

        Location[] candidates = new Location[3];
        // The side it holds first...
        candidates[0] = sideSpotAt(ownerLocation, state.formationYaw, wantLeft ? 1.0 : -1.0);
        // ...then the opposite side...
        candidates[1] = sideSpotAt(ownerLocation, state.formationYaw, wantLeft ? -1.0 : 1.0);
        // ...then a neutral spot in front of the owner, based on the anchored formation direction.
        double yaw = Math.toRadians(state.formationYaw);
        candidates[2] = new Location(world,
                ownerLocation.getX() - Math.sin(yaw) * SIDE_FORMATION_OFFSET,
                ownerLocation.getY(),
                ownerLocation.getZ() + Math.cos(yaw) * SIDE_FORMATION_OFFSET);

        for (int i = 0; i < candidates.length; i++) {
            if (!isFreeForCompanion(candidates[i])) {
                continue;
            }
            if (i == 0) {
                return candidates[0];
            }
            if (i == 1) {
                // Fell back to the other side. Hold it, but remember it was only a fallback.
                state.formationSide = wantLeft ? -1.0 : 1.0;
                state.formationIsFallback = true;
                return candidates[1];
            }
            // No side fits: stand in front of the owner, without a side.
            state.formationSide = 0.0;
            state.formationIsFallback = true;
            return candidates[2];
        }
        // Everything is blocked: stand where the owner is, out of the way of the door.
        state.formationSide = 0.0;
        state.formationIsFallback = true;
        return sideSpot(ownerLocation);
    }

    /**
     * Finds the height at which a companion would actually stand at the given column.
     *
     * <p>Starts at the given height and looks at most one block down. That is enough when the
     * owner stands on a bed, and it never drops the companion through the floor into a cellar.</p>
     *
     * @return the standing height, or the given height when no floor is found nearby
     */
    private static double standingHeight(World world, double x, double z, double fromY) {
        int startBlock = (int) Math.floor(fromY);
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        for (int y = startBlock; y >= startBlock - 1; y--) {
            Block feet = world.getBlockAt(bx, y, bz);
            Block head = world.getBlockAt(bx, y + 1, bz);
            Block floor = world.getBlockAt(bx, y - 1, bz);
            if (!feet.isPassable() || !head.isPassable()) {
                continue;
            }
            if (!floor.getCollisionShape().getBoundingBoxes().isEmpty()) {
                return y;
            }
        }
        return fromY;
    }

    /**
     * The tightest spot around the owner that is standable and reachable from this companion.
     *
     * <p>Searches a small 1.2-block radius around and just behind the owner, with candidates
     * ordered from closest to furthest. Each candidate must be clear, have a non-empty A* path
     * from the companion's current location, and — crucially — lie in the area the owner can walk
     * to. That last check is what keeps a companion from choosing a spot on the far side of a
     * wall: the owner standing in the corner of a room cannot reach the ground outside it, so
     * every such candidate is discarded.</p>
     *
     * @param ownerFootprint the blocks the owner can walk to, so candidates stay on their side
     * @param companionReachable the blocks this companion can reach without crossing geometry
     */
    private static Location nearestReachableCloseSpot(Location npcLocation, Location ownerLocation,
                                                       float formationYaw,
                                                       Set<PathPlanner.BlockPos> ownerFootprint,
                                                       Set<PathPlanner.BlockPos> companionReachable) {
        World world = ownerLocation.getWorld();
        double angle = Math.toRadians(formationYaw);
        double forwardX = -Math.sin(angle);
        double forwardZ = Math.cos(angle);
        double rightX = Math.cos(angle);
        double rightZ = Math.sin(angle);

        // 0.7 blocks ahead is the preferred point. Sample close alternatives around it, then
        // progressively widen to 1.2 blocks. This keeps the call-close grouping tight.
        double[][] offsets = {
                {0.0, 0.0},
                {0.35, 0.0}, {-0.35, 0.0}, {0.0, 0.35}, {0.0, -0.35},
                {0.55, 0.0}, {-0.55, 0.0}, {0.0, 0.55}, {0.0, -0.55},
                {0.75, 0.0}, {-0.75, 0.0}, {0.0, 0.75}, {0.0, -0.75},
                {0.35, 0.35}, {0.35, -0.35}, {-0.35, 0.35}, {-0.35, -0.35}
        };
        Location preferred = new Location(world,
                ownerLocation.getX() + forwardX * CLOSE_FORMATION_OFFSET,
                ownerLocation.getY(),
                ownerLocation.getZ() + forwardZ * CLOSE_FORMATION_OFFSET);
        // The NPC's reachable area already proves a walkable route to the candidate. Use it to
        // filter candidates rather than running A* once per sample (up to 34 full searches).
        double[] heights = {ownerLocation.getY(), ownerLocation.getY() - 1.0};
        for (double[] offset : offsets) {
            double x = preferred.getX() + forwardX * offset[0] + rightX * offset[1];
            double z = preferred.getZ() + forwardZ * offset[0] + rightZ * offset[1];
            for (double height : heights) {
                Location candidate = new Location(world, x, height, z);
                candidate.setY(standingHeight(world, x, z, height));
                if (!isFreeForCompanion(candidate)) {
                    continue;
                }
                // The candidate must lie in the area the owner can walk to. A spot one wall away is
                // close in a straight line but unreachable for the owner, and is exactly what used
                // to send the companion to the other side of the house.
                if (!isInFootprint(candidate, ownerFootprint)
                        || !isInFootprint(candidate, companionReachable)) {
                    continue;
                }
                return candidate;
            }
        }
        // If no valid close point exists, keep the current target inside the owner's room. A
        // position inside the bed itself must not be used as an A* goal: the pathfinder could
        // otherwise resolve it to the nearest free block on the other side of a wall.
        for (PathPlanner.BlockPos position : ownerFootprint) {
            Location candidate = new Location(world, position.x() + 0.5, position.y(), position.z() + 0.5);
            if (!isFreeForCompanion(candidate) || !isInFootprint(candidate, companionReachable)) {
                continue;
            }
            double dx = candidate.getX() - ownerLocation.getX();
            double dy = candidate.getY() - ownerLocation.getY();
            double dz = candidate.getZ() - ownerLocation.getZ();
            if (dx * dx + dy * dy + dz * dz <= CLOSE_TARGET_MAX_DISTANCE * CLOSE_TARGET_MAX_DISTANCE) {
                return candidate;
            }
        }
        // No mutually reachable safe spot in the small close radius. Return the owner's location
        // only as an inert fallback; movement code will wait rather than steering directly at it.
        return ownerLocation.clone();
    }

    /**
     * Finds the mob this companion should be fighting.
     *
     * <p>Only hostile mobs that are actually a threat qualify: either they are already coming for
     * the owner, or they are already coming for this companion. A mob that is merely standing
     * nearby is ignored, so the companion does not go on the offensive. The search radius is
     * configurable (8 blocks by default), so the owner is never left undefended for a distant fight.</p>
     *
     * @return the mob to engage, or null when there is nothing to fight
     */
    private LivingEntity findAttackTarget(Mannequin npc, Player owner, FollowState state) {
        Location ownerLocation = owner.getLocation();
        double radius = plugin.attackSearchRadius();
        double radiusSquared = radius * radius;
        LivingEntity best = null;
        double bestDistance = radiusSquared;

        for (Entity entity : owner.getNearbyEntities(radius, radius, radius)) {
            if (!(entity instanceof LivingEntity living) || !isHostileMob(living)) {
                continue;
            }
            if (!living.isValid() || living.isDead()) {
                continue;
            }
            // A mob that is already coming for the owner or for this companion is a threat.
            if (!isThreatToParty(living, owner, npc)) {
                continue;
            }
            double distance = living.getLocation().distanceSquared(ownerLocation);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = living;
            }
        }
        return best;
    }

    /**
     * Whether the mob counts as hostile. Only the undead and the classic cave dwellers qualify;
     * animals, villagers and other peaceful creatures are never attacked.
     */
    private static boolean isHostileMob(LivingEntity entity) {
        if (entity instanceof org.bukkit.entity.Monster) {
            return true;
        }
        EntityType type = entity.getType();
        return type == EntityType.CREEPER || type == EntityType.SLIME
                || type == EntityType.WITHER_SKELETON || type == EntityType.GHAST
                || type == EntityType.PHANTOM || type == EntityType.PILLAGER
                || type == EntityType.VINDICATOR || type == EntityType.EVOKER
                || type == EntityType.ILLUSIONER || type == EntityType.RAVAGER
                || type == EntityType.SHULKER;
    }

    /**
     * Whether the mob is actively coming for the owner or for a companion.
     *
     * <p>A mob whose target is the owner, or one of the owner's companions, is a threat. A mob
     * that targets something else — or nothing at all — is left alone.</p>
     */
    private static boolean isThreatToParty(LivingEntity mob, Player owner, Mannequin npc) {
        if (!(mob instanceof org.bukkit.entity.Mob hostile)) {
            return false;
        }
        LivingEntity target = hostile.getTarget();
        if (target == null) {
            return false;
        }
        if (target.getUniqueId().equals(owner.getUniqueId())) {
            return true;
        }
        if (target instanceof Mannequin companion) {
            return companion.getScoreboardTags().contains(TAG);
        }
        return false;
    }

    /**
     * Updates this companion's defensive marker set and emits particles/chat for new threats.
     *
     * <p>Yui marks skeletons, Sandrone marks creepers. This only runs while /serang is enabled;
     * it doesn't affect follow/close mode and doesn't apply the glowing effect that is visible
     * through walls. Particles are spawned above each marked hostile and therefore only appear
     * when there is a line of sight.</p>
     */
    private void updateDangerMarkers(Mannequin npc, Player owner, FollowState state,
                                    long currentTick) {
        if (!state.attackEnabled || !owner.isOnline()) {
            clearDangerMarkers(state);
            return;
        }

        String kind = "yui".equalsIgnoreCase(state.archetype) ? "skeleton" : "creeper";
        double radius = "yui".equalsIgnoreCase(state.archetype)
                ? plugin.yuiSkeletonMarkRadius()
                : plugin.sandroneCreeperMarkRadius();
        double radiusSquared = radius * radius;
        EntityType wantedType = "yui".equalsIgnoreCase(state.archetype)
                ? EntityType.SKELETON : EntityType.CREEPER;
        Set<UUID> foundNow = new java.util.HashSet<>();

        for (Entity entity : owner.getNearbyEntities(radius, radius, radius)) {
            if (!(entity instanceof LivingEntity hostile)
                    || hostile.getType() != wantedType
                    || !hostile.isValid() || hostile.isDead()) {
                continue;
            }
            if (!isThreatToParty(hostile, owner, npc)) {
                continue;
            }
            if (hostile.getLocation().distanceSquared(owner.getLocation()) > radiusSquared) {
                continue;
            }
            UUID hostileId = hostile.getUniqueId();
            foundNow.add(hostileId);
            if (!state.markedHostiles.contains(hostileId)) {
                state.markedHostiles.add(hostileId);
                sendMarkerAlert(owner, state, kind, currentTick);
            }
            if (currentTick % MARKER_PARTICLE_INTERVAL_TICKS == 0L) {
                Location particleLocation = hostile.getLocation().add(0.0, hostile.getHeight() + 0.25, 0.0);
                Particle.DustOptions dust = new Particle.DustOptions(
                        "yui".equalsIgnoreCase(state.archetype)
                                ? org.bukkit.Color.fromRGB(255, 105, 180)
                                : org.bukkit.Color.fromRGB(80, 220, 255),
                        1.2f);
                hostile.getWorld().spawnParticle(Particle.DUST, particleLocation,
                        3, 0.18, 0.12, 0.18, 0.0, dust);
            }
        }
        // Remove UUIDs for mobs that died, left the radius, or stopped targeting the owner.
        state.markedHostiles.retainAll(foundNow);
    }

    /** Clear marker bookkeeping when the combat toggle is turned off. */
    private static void clearDangerMarkers(FollowState state) {
        state.markedHostiles.clear();
    }

    /** Send a companion-name-prefixed alert, capped to one message every 10 seconds. */
    private void sendMarkerAlert(Player owner, FollowState state, String kind, long currentTick) {
        boolean firstAlert = state.lastMarkerAlertTick == Long.MIN_VALUE;
        if (!firstAlert && currentTick - state.lastMarkerAlertTick < MARKER_ALERT_COOLDOWN_TICKS) {
            return;
        }
        state.lastMarkerAlertTick = currentTick;
        Component prefix = "yui".equalsIgnoreCase(state.archetype)
                ? Component.text("[Yui] ", NamedTextColor.LIGHT_PURPLE)
                : Component.text("[Sandrone] ", NamedTextColor.AQUA);
        Component message = Component.text("Ada " + kind + " di sekitar!", NamedTextColor.YELLOW);
        owner.sendMessage(prefix.append(message));
    }

    /**
     * Picks the mob this companion fights, keeping the current target while it is still valid.
     *
     * <p>The current target is kept for a short grace period so the companion does not drop a mob
     * that stepped one block out of reach, and re-picks when the mob dies or the grace expires.</p>
     */
    private LivingEntity resolveAttackTarget(Mannequin npc, Player owner, FollowState state,
                                             long currentTick) {
        if (state.attackTarget != null) {
            Entity existing = Bukkit.getEntity(state.attackTarget);
            // The grace check is skipped on the very first pick, because subtracting the
            // never-set sentinel from the tick would overflow to a negative number.
            boolean withinGrace = state.attackTargetTick == Long.MIN_VALUE
                    || currentTick - state.attackTargetTick <= ATTACK_TARGET_GRACE_TICKS;
            if (withinGrace && existing instanceof LivingEntity living
                    && living.isValid() && !living.isDead()) {
                return living;
            }
        }
        LivingEntity found = findAttackTarget(npc, owner, state);
        if (found == null) {
            state.attackTarget = null;
            state.attackTargetTick = Long.MIN_VALUE;
            return null;
        }
        state.attackTarget = found.getUniqueId();
        state.attackTargetTick = currentTick;
        return found;
    }

    /**
     * The spot the companion walks to in order to strike its target: within melee reach, on the
     * side the companion is already approaching from.
     *
     * <p>Placing it on the far side instead would force the companion to walk around the mob to
     * reach it, which looks clumsy and often loses the fight. When the spot itself cannot be
     * stood on — the mob may be on a ledge — the mob's own block is used, which is always
     * standable.</p>
     */
    private static Location meleeSpot(Mannequin npc, LivingEntity target) {
        Location targetLocation = target.getLocation();
        Location npcLocation = npc.getLocation();
        double dx = targetLocation.getX() - npcLocation.getX();
        double dz = targetLocation.getZ() - npcLocation.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 0.05) {
            // Overlapping the target: step aside so the swing has room to happen.
            return targetLocation.clone().add(ATTACK_APPROACH_DISTANCE, 0, 0);
        }
        // Stop short of the target rather than beyond it, so the companion walks up to the mob
        // instead of having to walk around it.
        double scale = ATTACK_APPROACH_DISTANCE / length;
        Location spot = new Location(targetLocation.getWorld(),
                targetLocation.getX() - dx * scale,
                targetLocation.getY(),
                targetLocation.getZ() - dz * scale);
        if (!isFreeForCompanion(spot)) {
            // The spot hangs in the air or inside a block; the mob's own block always works.
            return targetLocation.clone();
        }
        return spot;
    }

    /**
     * Swings at the target: the companion's arm animates like a player's, and the mob takes the
     * damage. A mob that had the companion as its target is released first, so it stops hitting
     * an immortal companion and looks for the owner again.
     */
    private void swingAtTarget(Mannequin npc, Player owner, LivingEntity target,
                                FollowState state, long currentTick) {
        // A never-swing companion starts at Long.MIN_VALUE; subtracting that from the tick would
        // overflow to a negative number and permanently satisfy the cooldown, so the first swing
        // has to be allowed through explicitly.
        boolean firstSwing = state.lastSwingTick == Long.MIN_VALUE;
        if (!firstSwing && currentTick - state.lastSwingTick < plugin.attackCooldownTicks()) {
            return;
        }
        state.lastSwingTick = currentTick;

        // The swing animation is a player's own, so the hit reads like a real melee attack.
        npc.swingMainHand();
        // Take the mob's attention off the companion: an immortal target would otherwise keep
        // the mob busy forever instead of letting it return to the owner.
        if (target instanceof org.bukkit.entity.Mob hostile
                && hostile.getTarget() instanceof Mannequin companion
                && companion.getScoreboardTags().contains(TAG)) {
            hostile.setTarget(owner);
        }
        target.damage(plugin.attackDamage(), npc);
    }

    /**
     * Whether the given spot lies inside the area the owner can walk to.
     *
     * <p>An empty footprint means the check cannot be made — the owner's own block is then used
     * as the answer, which is always safe.</p>
     */
    private static boolean isInFootprint(Location location, Set<PathPlanner.BlockPos> footprint) {
        if (footprint == null || footprint.isEmpty()) {
            return true;
        }
        return footprint.contains(new PathPlanner.BlockPos(
                location.getBlockX(), location.getBlockY(), location.getBlockZ()));
    }

    /** Returns the yaw that points from one location towards another. */
    private static float yawToward(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        if (dx * dx + dz * dz < 1.0E-6) {
            return from.getYaw();
        }
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** Returns the spot 1.2 blocks to the owner's left/right, relative to a fixed formation yaw. */
    private static Location sideSpotAt(Location ownerLocation, float formationYaw, double side) {
        double angle = Math.toRadians(formationYaw) + side * Math.PI / 2.0;
        return new Location(ownerLocation.getWorld(),
                ownerLocation.getX() - Math.sin(angle) * SIDE_FORMATION_OFFSET,
                ownerLocation.getY(),
                ownerLocation.getZ() + Math.cos(angle) * SIDE_FORMATION_OFFSET);
    }

    /**
     * Where a companion appears when summoned: its side of the formation, with fallbacks.
     *
     * <p>At spawn the formation is anchored to the owner's facing at that moment. Later camera
     * movement does not change it.</p>
     */
    private static Location spawnFormationSpot(Location ownerLocation, String archetype, float formationYaw) {
        boolean wantLeft = "yui".equalsIgnoreCase(archetype);
        Location own = sideSpotAt(ownerLocation, formationYaw, wantLeft ? 1.0 : -1.0);
        if (isFreeForCompanion(own)) {
            return own;
        }
        Location other = sideSpotAt(ownerLocation, formationYaw, wantLeft ? -1.0 : 1.0);
        if (isFreeForCompanion(other)) {
            return other;
        }
        return sideSpot(ownerLocation);
    }

    /** Whether a companion could stand at the given location without clipping into blocks. */
    private static boolean isFreeForCompanion(Location location) {
        Block feet = location.getBlock();
        Block head = location.getWorld().getBlockAt(location.getBlockX(), location.getBlockY() + 1,
                location.getBlockZ());
        return feet.isPassable() && head.isPassable();
    }

    /**
     * Follows the owner along an A* route, driving the companion with velocity.
     *
     * <p>Mannequins are not mobs and have no pathfinder, so routes are computed by {@link PathPlanner}
     * and walked one waypoint at a time. Replans happen when the owner moves away from the planned
     * goal, when the route is consumed, or periodically; teleport remains the fallback when the
     * companion is left far behind or cannot make progress.</p>
     */
    private void startFollowing(CompanionKey key, Player owner, Mannequin npc, float initialFormationYaw) {
        FollowState state = new FollowState();
        state.archetype = key.archetype();
        state.formationYaw = initialFormationYaw;
        state.lastOwnerPosition = owner.getLocation().clone();
        // Restore the mode this player last chose, so a respawn does not silently undo it.
        FollowMode saved = savedModes.get(key);
        state.mode = saved == null ? FollowMode.FOLLOW : saved;
        followStates.put(key, state);
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!owner.isOnline() || npc.isDead() || !npc.isValid()) {
                cancel(key);
                return;
            }
            if (!npc.getWorld().equals(owner.getWorld())) {
                rescueTeleport(npc, owner, owner.getLocation(), state);
                return;
            }

            Location npcLocation = npc.getLocation();
            Location ownerLocation = owner.getLocation();
            // Keep the pose in sync even in the branches that stand still, so a companion that
            // stops moving right after leaving water does not stay stuck in the swim pose.
            updateMovementPose(npc, state, isInWater(npc, npcLocation));
            // Anchor the formation to actual player movement, not head/camera yaw. A camera-only
            // look-around must never send a companion to the opposite wall. Only update after the
            // owner has walked a meaningful distance so tiny position jitter does not spin the
            // formation either.
            if (state.lastOwnerPosition != null) {
                double moveX = ownerLocation.getX() - state.lastOwnerPosition.getX();
                double moveZ = ownerLocation.getZ() - state.lastOwnerPosition.getZ();
                if (moveX * moveX + moveZ * moveZ >= 0.09) {
                    state.formationYaw = (float) Math.toDegrees(Math.atan2(-moveX, moveZ));
                }
            }
            state.lastOwnerPosition = ownerLocation.clone();
            double dx = ownerLocation.getX() - npcLocation.getX();
            double dz = ownerLocation.getZ() - npcLocation.getZ();
            double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
            double distance = npcLocation.distance(ownerLocation);

            // The mode is persistent until another command replaces it. Its standing distance is
            // the only difference here; the selected target handles formation vs. close positioning.
            boolean closeMode = state.mode == FollowMode.CLOSE;
            double standStill = closeMode
                    ? plugin.closeDistance()
                    : plugin.comfortDistance();

            // The companion heads for its own spot in the formation, not for the owner directly.
            // In CLOSE mode formationSpot picks a point within the owner's reachable room.
            Location targetSpot = formationSpot(npc, owner, state, closeMode);
            long currentTick = Bukkit.getCurrentTick();

            // Combat overrides the formation while it is active: the companion walks to the mob
            // instead of holding its side, and looks at what it is fighting.
            LivingEntity attackTarget = null;
            if (state.attackEnabled) {
                attackTarget = resolveAttackTarget(npc, owner, state, currentTick);
                if (attackTarget != null) {
                    targetSpot = meleeSpot(npc, attackTarget);
                }
            }
            // Defensive markers only exist while combat mode is on.
            updateDangerMarkers(npc, owner, state, currentTick);
            double targetDx = targetSpot.getX() - npcLocation.getX();
            double targetDz = targetSpot.getZ() - npcLocation.getZ();
            double targetDistance = Math.sqrt(targetDx * targetDx + targetDz * targetDz);
            // While fighting, "arrived" means being within melee reach of the mob itself, not
            // merely near the spot the companion aims for. Without this the companion would stop
            // a block short and swing at nothing. The reach is measured horizontally, the way a
            // player's own melee reach is, so a mob one block up or down still counts as in
            // reach instead of making the companion stand there refusing to swing.
            double combatDistance = Double.MAX_VALUE;
            if (attackTarget != null) {
                Location targetPosition = attackTarget.getLocation();
                double reachDx = targetPosition.getX() - npcLocation.getX();
                double reachDz = targetPosition.getZ() - npcLocation.getZ();
                combatDistance = Math.sqrt(reachDx * reachDx + reachDz * reachDz);
            }
            double arriveAt = attackTarget == null ? standStill : ATTACK_STOP_DISTANCE;

            // Emergency rescue on a vertical separation. When the owner stands on safe ground and
            // the companion is far below, waiting for the usual 24-block radius is wrong: the
            // owner can be directly above it while it lies at the bottom of a ravine, so their
            // straight-line distance stays small. A companion that is still rising — falling is
            // over and it swims upward — is left alone, so a swim towards the owner is not
            // interrupted.
            if (ownerLocation.getY() - npcLocation.getY() >= EMERGENCY_FALL_DISTANCE
                    && npc.getVelocity().getY() <= 0.01) {
                rescueTeleport(npc, owner, ownerLocation, state);
                return;
            }

            // Emergency teleport. The threshold and the landing spot are mode-specific:
            // FOLLOW catches up to the companion's own formation side, CLOSE lands in the
            // owner's reachable room. Both use their own configured distance.
            if (distance > emergencyTeleportDistance(state.mode)) {
                rescueTeleport(npc, owner, ownerLocation, state);
                return;
            }

            // Open doors ahead and close the ones already passed before steering.
            // The companion only touches doors while it is actually moving, and only the ones
            // that lie on the way to its own goal.
            double currentSpeed = Math.sqrt(npc.getVelocity().getX() * npc.getVelocity().getX()
                    + npc.getVelocity().getZ() * npc.getVelocity().getZ());
            boolean moving = currentSpeed > STILL_SPEED;
            updateDoors(npcLocation, state, moving, targetDx, targetDz);

            // While fighting, two independent distances apply. Resting is decided by the closer
            // stop distance, but a swing is allowed at the full vanilla reach — otherwise a
            // companion settled a whisker outside reach would stand there holding its sword and
            // never strike. Outside combat the normal formation distance decides.
            boolean inReach = attackTarget != null && combatDistance <= ATTACK_REACH;
            if (inReach) {
                // In reach: face the mob and swing on cooldown. The head stays on the target
                // whether the companion is still closing in or already standing its ground.
                float facing = yawToward(npcLocation, attackTarget.getLocation());
                state.lastWalkingYaw = facing;
                npc.setBodyYaw(facing);
                npc.setRotation(facing, 0);
                Location targetPosition = attackTarget.getLocation();
                npc.lookAt(targetPosition.getX(),
                        targetPosition.getY() + attackTarget.getHeight() * 0.85,
                        targetPosition.getZ(), io.papermc.paper.entity.LookAnchor.EYES);
            }

            if (attackTarget == null ? targetDistance <= standStill
                    : combatDistance <= arriveAt) {
                org.bukkit.util.Vector velocity = npc.getVelocity();
                npc.setVelocity(new org.bukkit.util.Vector(velocity.getX() * 0.35, velocity.getY(), velocity.getZ() * 0.35));
                if (attackTarget == null) {
                    applyGaze(npc, owner, state, state.lastWalkingYaw, true, true);
                } else if (inReach) {
                    // Standing its ground within reach: this is where the swing happens.
                    swingAtTarget(npc, owner, attackTarget, state, currentTick);
                }
                return;
            }

            boolean goalMoved = goalMovedFromPlan(state, targetSpot);
            boolean noPath = state.path.isEmpty();
            // A failed plan is retried quickly during a fight too. Waiting the normal full second
            // while a mob is bearing down leaves the companion standing and watching.
            long failedRetry = attackTarget == null
                    ? FAILED_PLAN_RETRY_TICKS : COMBAT_REPLAN_INTERVAL_TICKS;
            boolean retryFailedPlan = noPath && state.lastPlanTick != Long.MIN_VALUE
                    && currentTick - state.lastAttemptTick >= failedRetry;
            // While fighting, the target keeps moving, so the route has to be refreshed far more
            // often than the relaxed follow interval. Five seconds of stale route is far too slow
            // for a melee chase.
            long replanInterval = attackTarget == null ? REPLAN_INTERVAL_TICKS : COMBAT_REPLAN_INTERVAL_TICKS;
            boolean needsReplan = (noPath && (state.lastPlanTick == Long.MIN_VALUE || retryFailedPlan))
                    || (!noPath && (state.waypointIndex >= state.path.size()
                    || currentTick - state.lastPlanTick >= replanInterval
                    || goalMoved));

            if (needsReplan) {
                state.lastAttemptTick = currentTick;
                // Combat may route down onto a hostile up to three blocks below. Following keeps
                // the strict one-block descent so it never walks off a ravine while chasing.
                boolean combatDrop = attackTarget != null;
                state.path = PathPlanner.findPath(npc.getWorld(), npcLocation, targetSpot,
                        PATH_RADIUS, combatDrop);
                state.waypointIndex = 0;
                state.plannedGoal = targetSpot.clone();
                state.lastPlanTick = currentTick;
            }

            // If no A* route exists, do not steer straight through a wall towards the target.
            // Stay still and retry planning shortly; only a mode-specific distance rescue may move
            // the companion by teleporting.
            if (state.path.isEmpty()) {
                // No walkable route (commonly because the companion fell into a ravine while its
                // owner stayed above). Let it retry briefly to account for a transient path update,
                // then use the mode's safe landing target rather than waiting until the owner is
                // more than 24 blocks away. Do not rescue a companion that is currently swimming.
                if (state.noPathSinceTick == Long.MIN_VALUE) {
                    state.noPathSinceTick = currentTick;
                }
                if (!npc.isInWater() && currentTick - state.noPathSinceTick >= NO_PATH_RESCUE_TICKS) {
                    rescueTeleport(npc, owner, ownerLocation, state);
                    return;
                }
                org.bukkit.util.Vector velocity = npc.getVelocity();
                npc.setVelocity(new org.bukkit.util.Vector(velocity.getX() * 0.35, velocity.getY(), velocity.getZ() * 0.35));
                applyGaze(npc, owner, state, state.lastWalkingYaw, true, true);
                return;
            }
            // A route exists again, so the rescue timer is cleared.
            state.noPathSinceTick = Long.MIN_VALUE;

            // Advance past waypoints the companion has already reached.
            org.bukkit.util.Vector waypoint;
            while (state.waypointIndex < state.path.size()) {
                org.bukkit.util.Vector target = state.path.get(state.waypointIndex);
                double dxw = target.getX() - npcLocation.getX();
                double dzw = target.getZ() - npcLocation.getZ();
                if (dxw * dxw + dzw * dzw <= WAYPOINT_REACHED_DISTANCE_SQUARED) {
                    state.waypointIndex++;
                } else {
                    break;
                }
            }
            if (state.waypointIndex >= state.path.size()) {
                state.path = List.of();
                return;
            }
            waypoint = state.path.get(state.waypointIndex);

            double wx = waypoint.getX() - npcLocation.getX();
            double wz = waypoint.getZ() - npcLocation.getZ();
            double wy = waypoint.getY() - npcLocation.getY();
            double waypointDistance = Math.sqrt(wx * wx + wz * wz);
            if (waypointDistance < 1.0E-3) {
                return;
            }

            // Remember the direction of travel so doors somebody else opened can be adopted.
            state.lastGoalDx = wx;
            state.lastGoalDz = wz;
            adoptOpenDoors(npcLocation, state);

            // Track progress so a companion wedged against geometry can be rescued.
            if (state.lastProgressPosition == null || movedSince(state.lastProgressPosition, npcLocation)) {
                state.lastProgressPosition = npcLocation.toVector();
                state.lastProgressTick = currentTick;
                state.sideStepAttempts = 0;
            } else if (currentTick - state.lastProgressTick >= STUCK_TIMEOUT_TICKS) {
                // Give a jammed companion two sideways escape attempts. If both fail, stop pushing
                // against the obstacle and retry the route later. Do not teleport here: a blocked
                // route is not permission to cross a wall or a cliff. The distance rescue remains
                // the only routine catch-up teleport.
                if (trySideStep(npc, owner, ownerLocation, state)) {
                    state.lastProgressTick = currentTick;
                    return;
                }
                state.path = List.of();
                state.plannedGoal = null;
                state.lastPlanTick = Long.MIN_VALUE;
                state.lastAttemptTick = currentTick;
                state.lastProgressPosition = null;
                org.bukkit.util.Vector velocity = npc.getVelocity();
                npc.setVelocity(new org.bukkit.util.Vector(velocity.getX() * 0.2, velocity.getY(), velocity.getZ() * 0.2));
                return;
            }

            double speed = horizontalDistance > 5.0 ? 0.24 : 0.14;
            if (attackTarget != null) {
                // Sprint into melee range 1.5x faster, but only while actually chasing a hostile.
                speed *= 1.5;
            }
            double vx = (wx / waypointDistance) * speed;
            double velocityZ = (wz / waypointDistance) * speed;

            boolean inWater = isInWater(npc, npcLocation);
            updateMovementPose(npc, state, inWater);
            updateFootsteps(npc, state, inWater, currentSpeed, currentTick);
            if (inWater) {
                // updateMovementPose has already switched the mannequin into the player's swim
                // pose; this buoyancy velocity is only what keeps it near the surface instead of
                // sinking like a stone while the pose plays.
                double swimY = npc.getVelocity().getY();
                if (swimY < 0.06) {
                    swimY = 0.06;
                }
                if (wy > 0.4) {
                    swimY = 0.20;
                } else if (wy < -0.4) {
                    swimY = -0.10;
                }
                npc.setVelocity(new org.bukkit.util.Vector(vx, swimY, velocityZ));
            } else {
                // Jump when the next waypoint is a step up the companion can clear.
                double vy = npc.isOnGround() && wy > 0.4 ? 0.42 : npc.getVelocity().getY();
                npc.setVelocity(new org.bukkit.util.Vector(vx, vy, velocityZ));
            }

            float yaw = (float) Math.toDegrees(Math.atan2(-vx, velocityZ));
            state.lastWalkingYaw = yaw;
            // Pose is owned by updateMovementPose: writing STANDING here would flip a swimming
            // companion back upright every tick.
            // The body stays on course; when combat is active the head tracks the target instead
            // of glancing back at the owner. This keeps the companion visibly locked on while it
            // closes the distance.
            if (attackTarget != null) {
                npc.setBodyYaw(yaw);
                Location targetPosition = attackTarget.getLocation();
                npc.lookAt(targetPosition.getX(), targetPosition.getY() + attackTarget.getHeight() * 0.85,
                        targetPosition.getZ(), io.papermc.paper.entity.LookAnchor.EYES);
            } else {
                applyGaze(npc, owner, state, yaw, false, false);
            }
        }, 1L, 1L);
        followTasks.put(key, task);
    }

    /** Whether the mannequin is currently in/under water or occupies a water block. */
    private static boolean isInWater(Mannequin npc, Location location) {
        World world = npc.getWorld();
        return npc.isInWater() || npc.isUnderWater()
                || PathPlanner.isWaterAt(world, location.getBlockX(), location.getBlockY(), location.getBlockZ())
                || PathPlanner.isWaterAt(world, location.getBlockX(), location.getBlockY() - 1, location.getBlockZ());
    }

    private void updateMovementPose(Mannequin npc, FollowState state, boolean inWater) {
        if (state.swimmingPose == inWater) {
            return;
        }
        state.swimmingPose = inWater;
        npc.setPose(inWater ? org.bukkit.entity.Pose.SWIMMING : org.bukkit.entity.Pose.STANDING, true);
    }

    /**
     * Plays the step sound of the block beneath the mannequin, like a player's footstep.
     *
     * <p>Only plays while moving across solid ground: not while standing, swimming, or in the
     * air. The small cooldown keeps the sound from firing every tick and approximates a run.</p>
     */
    private static void updateFootsteps(Mannequin npc, FollowState state, boolean inWater,
                                        double horizontalSpeed, long currentTick) {
        if (inWater || !npc.isOnGround() || horizontalSpeed < FOOTSTEP_SPEED) {
            return;
        }
        if (state.lastFootstepTick != Long.MIN_VALUE
                && currentTick - state.lastFootstepTick < FOOTSTEP_INTERVAL_TICKS) {
            return;
        }
        Location location = npc.getLocation();
        Block ground = location.clone().subtract(0.0, 0.2, 0.0).getBlock();
        Sound stepSound = ground.getBlockData().getSoundGroup().getStepSound();
        if (stepSound == null || stepSound == Sound.UI_BUTTON_CLICK) {
            return;
        }
        state.lastFootstepTick = currentTick;
        float volume = ground.getBlockData().getSoundGroup().getVolume() * 0.35f;
        float pitch = ground.getBlockData().getSoundGroup().getPitch();
        npc.getWorld().playSound(location, stepSound, SoundCategory.PLAYERS, volume, pitch);
    }

    /**
     * Points the companion's head at whatever deserves its attention.
     *
     * <p>One function for both cases:</p>
     * <ul>
     *   <li><b>standing still</b> (by the owner): the head follows the owner, and briefly turns
     *       toward a nearby mob from time to time;</li>
     *   <li><b>walking</b>: the body keeps its walking yaw and the head keeps facing where the body
     *       walks, except when a glance at a mob is already underway or a new one is scheduled —
     *       and only if that mob stays within {@link #MAX_GLANCE_ANGLE_DEGREES} of the walking
     *       direction, so the head never twists past the neck's reach.</li>
     * </ul>
     *
     * <p>Call order inside is deliberate: {@code setBodyYaw} first, {@code lookAt} last — swapping
     * them reverts the gaze every tick, which is the very bug an earlier revision fixed.</p>
     *
     * @param standing  true when the companion is resting beside its owner
     * @param followBodyYaw true when the idle body yaw should follow the owner (never while walking)
     */
    private void applyGaze(Mannequin npc, Player owner, FollowState state,
                           float bodyYaw, boolean standing, boolean followBodyYaw) {
        long currentTick = Bukkit.getCurrentTick();

        // End a gaze that has run its course; pause new checks briefly so it does not hop.
        if (state.gazeTarget != null && currentTick >= state.gazeEndTick) {
            state.gazeTarget = null;
            state.nextGazeCheckTick = currentTick + GLANCE_CHECK_INTERVAL_TICKS;
        }

        // Consider a new mob to glance at when idle and no gaze is running.
        if (state.gazeTarget == null && currentTick >= state.nextGazeCheckTick) {
            Entity mob = nearestWatchableMob(npc, owner);
            if (mob != null && withinGlanceAngle(npc, mob, bodyYaw)) {
                state.gazeTarget = mob.getUniqueId();
                state.gazeEndTick = currentTick + GLANCE_DURATION_TICKS
                        + (long) (Math.random() * GLANCE_DURATION_VARIANCE_TICKS);
            } else {
                state.nextGazeCheckTick = currentTick + GLANCE_CHECK_INTERVAL_TICKS;
            }
        }

        double targetX;
        double targetY;
        double targetZ;
        Entity gaze = state.gazeTarget == null ? null : Bukkit.getEntity(state.gazeTarget);
        if (gaze != null && gaze.isValid() && !gaze.isDead()
                // A glance may outlive its angle: the mob may have walked behind the companion.
                && withinGlanceAngle(npc, gaze, bodyYaw)) {
            targetX = gaze.getLocation().getX();
            targetY = gaze.getLocation().getY() + gaze.getHeight() * 0.9;
            targetZ = gaze.getLocation().getZ();
        } else {
            state.gazeTarget = null;
            if (standing) {
                // Standing by the owner: look at the owner (the normal resting gaze).
                targetX = owner.getLocation().getX();
                targetY = owner.getLocation().getY() + owner.getEyeHeight();
                targetZ = owner.getLocation().getZ();
            } else {
                // Walking: no owner-gaze — the head simply stays with the body.
                npc.setBodyYaw(bodyYaw);
                npc.setRotation(bodyYaw, 0);
                return;
            }
        }

        if (!standing || followBodyYaw) {
            // While walking the body stays on course; only when standing may the body drift.
            npc.setBodyYaw(bodyYaw);
        }
        npc.lookAt(targetX, targetY, targetZ, io.papermc.paper.entity.LookAnchor.EYES);
    }

    /**
     * Gives the companion a natural idle gaze while it stands beside its owner.
     */
    private void applyIdleLook(Mannequin npc, Player owner, FollowState state) {
        applyGaze(npc, owner, state, state.lastWalkingYaw, true, true);
    }

    /**
     * Whether the mob lies within {@link #MAX_GLANCE_ANGLE_DEGREES} of the direction the companion
     * is walking/facing, measured on the horizontal plane.
     */
    private static boolean withinGlanceAngle(Mannequin npc, Entity mob, float bodyYaw) {
        double dx = mob.getLocation().getX() - npc.getLocation().getX();
        double dz = mob.getLocation().getZ() - npc.getLocation().getZ();
        if (dx * dx + dz * dz < 1.0E-4) {
            return true; // overlapping: any direction is fine
        }
        double mobAngle = Math.toDegrees(Math.atan2(-dx, dz));
        double diff = Math.abs(mobAngle - bodyYaw) % 360.0;
        if (diff > 180.0) {
            diff = 360.0 - diff;
        }
        return diff <= MAX_GLANCE_ANGLE_DEGREES;
    }

    /**
     * Returns the closest living mob worth glancing at, ignoring the owner and the companion itself.
     *
     * <p>Other companions are skipped too: Yui and Sandrone should notice the world around them,
     * not just each other. Every other living entity — hostile mobs, animals, villagers — counts.</p>
     *
     * <p>Scanned only about twice a second rather than every tick, so idle behaviour stays cheap.</p>
     */
    private Entity nearestWatchableMob(Mannequin npc, Player owner) {
        double radius = plugin.idleLookRadius();
        Entity closest = null;
        double closestDistanceSquared = radius * radius;
        for (Entity entity : npc.getNearbyEntities(radius, radius, radius)) {
            if (!(entity instanceof org.bukkit.entity.LivingEntity living)) {
                continue;
            }
            if (living.getUniqueId().equals(owner.getUniqueId())) {
                continue;
            }
            if (living.getUniqueId().equals(npc.getUniqueId())) {
                continue;
            }
            // Skip the other companion: they are part of the party, not part of the scenery.
            if (entity.getScoreboardTags().contains(TAG)) {
                continue;
            }
            if (living.isDead() || !living.isValid()) {
                continue;
            }
            double distanceSquared = living.getLocation().distanceSquared(npc.getLocation());
            if (distanceSquared < closestDistanceSquared) {
                closestDistanceSquared = distanceSquared;
                closest = living;
            }
        }
        return closest;
    }

    /**
     * Whether the target has moved far enough from the planned goal to justify a new route.
     *
     * <p>This compares the <b>goal</b> with the plan, not the owner with the plan. Comparing the
     * owner was wrong: in melee the goal is a spot that follows the mob around, so it sat more
     * than three blocks from the owner almost permanently. That made the companion replan on
     * every tick, resetting the waypoint index each time, so it kept restarting the route from
     * its first waypoint and never actually closed the distance.</p>
     */
    private static boolean goalMovedFromPlan(FollowState state, Location targetSpot) {
        if (state.plannedGoal == null) {
            return true;
        }
        double dx = targetSpot.getX() - state.plannedGoal.getX();
        double dz = targetSpot.getZ() - state.plannedGoal.getZ();
        return dx * dx + dz * dz >= GOAL_REPLAN_DISTANCE_SQUARED;
    }

    /** Whether the companion made meaningful progress since the last check. */
    private static boolean movedSince(org.bukkit.util.Vector previous, Location current) {
        double dx = current.getX() - previous.getX();
        double dz = current.getZ() - previous.getZ();
        return dx * dx + dz * dz > STUCK_PROGRESS_DISTANCE_SQUARED;
    }

    /**
     * Opens closed doors and fence gates on the companion's way, and closes any door it has
     * already walked through, so the route through a house is left as it was found.
     *
     * <p>Four rules keep this from becoming an open invitation for hostile mobs:</p>
     * <ol>
     *   <li>a door is only opened while the companion is actually <b>moving</b> — a companion
     *       standing still beside its owner never forces a door open, which is what used to let
     *       creepers walk into a house that was already sealed;</li>
     *   <li>the door must lie <b>between the companion and its owner</b>, so a door in the next
     *       room or behind the wall is left alone;</li>
     *   <li>only doors within {@link #DOOR_OPEN_DISTANCE_SQUARED} qualify — close enough to walk
     *       into, not merely nearby;</li>
     *   <li>doors the companion opened are closed again as soon as it stops, so a house the
     *       player is resting in ends up sealed.</li>
     * </ol>
     */
    private void updateDoors(Location npcLocation, FollowState state,
                             boolean moving, double goalDx, double goalDz) {
        World world = npcLocation.getWorld();
        if (world == null) {
            return;
        }

        // Close anything the companion already crossed. A door still within the open radius stays
        // open: the companion is standing at its threshold, and closing it there would only shut
        // onto the companion itself.
        if (!moving) {
            // Doors somebody else left open are still adopted, so a room the owner forgot to close
            // gets tidied up even while the companion is standing still.
            adoptOpenDoors(npcLocation, state);
            closeHeldDoors(world, npcLocation, state);
            return;
        }

        // Open closed doors or gates the companion is about to pass through.
        int bx = npcLocation.getBlockX();
        int by = npcLocation.getBlockY();
        int bz = npcLocation.getBlockZ();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    Block block = world.getBlockAt(bx + dx, by + dy, bz + dz);
                    if (!(block.getBlockData() instanceof Openable openable) || openable.isOpen()) {
                        continue;
                    }
                    double ddx = block.getX() + 0.5 - npcLocation.getX();
                    double ddz = block.getZ() + 0.5 - npcLocation.getZ();
                    if (ddx * ddx + ddz * ddz > DOOR_OPEN_DISTANCE_SQUARED) {
                        continue;
                    }
                    // Rule 2: the door has to be on the way to the companion's own goal — the spot
                    // it is heading for, not the owner. The two differ once the companion aims at
                    // its side of the formation, and using the owner's direction there left doors
                    // shut in the face of the second companion.
                    if (!isBetween(npcLocation, goalDx, goalDz, ddx, ddz)) {
                        continue;
                    }
                    setDoorState(block, true);
                    if (dy == 0) {
                        state.openedDoors.add(block.getLocation());
                        // Remember the direction of travel and which side the companion was on,
                        // so the door can be closed once it has genuinely crossed the threshold.
                        double axisLength = Math.sqrt(goalDx * goalDx + goalDz * goalDz);
                        double axisX = axisLength < 1.0E-3 ? 0.0 : goalDx / axisLength;
                        double axisZ = axisLength < 1.0E-3 ? 0.0 : goalDz / axisLength;
                        double side = (ddx * axisX + ddz * axisZ) >= 0.0 ? 1.0 : -1.0;
                        sharedOpenDoors.put(block.getLocation(), new DoorHold(axisX, axisZ, side,
                                npcLocation.getX(), npcLocation.getZ(), Bukkit.getCurrentTick()));
                    }
                }
            }
        }

        // Close doors once the companion has passed through them.
        closeHeldDoors(world, npcLocation, state);
    }

    /**
     * Closes doors the companion opened.
     *
     * <p>Doors are keyed per-position in a shared map rather than per-companion lists: the first
     * companion opens a door, the second walks through it, the first closing it from the far side
     * must not re-open it under the second's feet.</p>
     *
     * <p>A door is only shut once the companion has genuinely crossed it — the doorway is between
     * where the companion was and where it is now — and nobody is standing in the doorway. The
     * owner's distance is deliberately irrelevant: the owner is usually standing right beside the
     * door they just closed, and using that as a condition meant the door never shut at all.</p>
     */
    private List<Location> closeHeldDoors(World world, Location npcLocation, FollowState state) {
        List<Location> closed = new ArrayList<>();
        if (sharedOpenDoors.isEmpty()) {
            return closed;
        }
        long currentTick = Bukkit.getCurrentTick();
        for (Iterator<Map.Entry<Location, DoorHold>> iterator =
             sharedOpenDoors.entrySet().iterator(); iterator.hasNext(); ) {
            Map.Entry<Location, DoorHold> entry = iterator.next();
            Location door = entry.getKey();
            DoorHold hold = entry.getValue();
            if (!world.equals(door.getWorld())) {
                continue;
            }
            boolean expired = currentTick - hold.openedTick() >= DOOR_HOLD_TIMEOUT_TICKS;
            if (!expired && !crossedThreshold(door, hold, npcLocation)) {
                continue;
            }
            // A companion that has not crossed yet must not close its own door. Another party
            // companion may still be approaching it; keep it open until that companion passes or
            // the bounded timeout expires.
            if (!expired && isAnyCompanionApproachingDoor(door, hold)) {
                continue;
            }
            if (isStandingInDoorway(door, npcLocation)) {
                continue;
            }
            setDoorState(door.getBlock(), false);
            closed.add(door);
            iterator.remove();
            for (FollowState tracked : followStates.values()) {
                tracked.openedDoors.remove(door);
            }
        }
        return closed;
    }

    /**
     * Keep a door open while another live companion is still on the starting side and close enough
     * to be approaching it. Also preserve an already-open door somebody else is using; it will be
     * adopted by that companion and closed after it crosses the threshold.
     */
    private boolean isAnyCompanionApproachingDoor(Location door, DoorHold hold) {
        for (Map.Entry<CompanionKey, FollowState> entry : followStates.entrySet()) {
            CompanionKey key = entry.getKey();
            UUID npcId = ownerToNpc.get(key);
            Entity entity = npcId == null ? null : Bukkit.getEntity(npcId);
            if (!(entity instanceof Mannequin npc) || !npc.isValid() || npc.isDead()
                    || !door.getWorld().equals(npc.getWorld())) {
                continue;
            }
            Location position = npc.getLocation();
            double dx = position.getX() - (door.getX() + 0.5);
            double dz = position.getZ() - (door.getZ() + 0.5);
            double distanceSquared = dx * dx + dz * dz;
            if (distanceSquared > DOOR_PENDING_DISTANCE_SQUARED) {
                continue;
            }
            double along = dx * hold.axisX() + dz * hold.axisZ();
            // Only hold for a companion on the door's original approach side. A companion already
            // through the doorway should neither keep it open nor block the second companion.
            if (along * hold.side() < 0.0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the companion has crossed the doorway since it claimed it.
     *
     * <p>Two tests, because the companion can reach a doorway in two ways: it opened the door
     * itself and then walked through, or the door was already open and it merely passed by. In
     * the first case the companion ends up on the far side of the threshold; in the second it is
     * simply further along its path than when it claimed the door.</p>
     */
    private static boolean crossedThreshold(Location door, DoorHold hold, Location npcLocation) {
        double ddx = npcLocation.getX() - (door.getX() + 0.5);
        double ddz = npcLocation.getZ() - (door.getZ() + 0.5);
        double along = ddx * hold.axisX() + ddz * hold.axisZ();
        // On the opposite side of the threshold it started from.
        if (along * hold.side() > 1.0) {
            return true;
        }
        // Or simply further along its route than when it claimed the door.
        double movedX = npcLocation.getX() - hold.originX();
        double movedZ = npcLocation.getZ() - hold.originZ();
        double travelled = movedX * hold.axisX() + movedZ * hold.axisZ();
        return travelled > 1.2;
    }

    /**
     * Adopts doors that somebody else left open near the companion.
     *
     * <p>The owner opens doors all the time and does not always close them, and a companion that
     * walked through one of those leaves an open door behind that lets mobs in at night. So any
     * open door the companion walks past is claimed — it gets closed once the companion has moved
     * well beyond it, exactly like doors the companion opened itself.</p>
     */
    private void adoptOpenDoors(Location npcLocation, FollowState state) {
        World world = npcLocation.getWorld();
        if (world == null) {
            return;
        }
        int bx = npcLocation.getBlockX();
        int by = npcLocation.getBlockY();
        int bz = npcLocation.getBlockZ();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    Block block = world.getBlockAt(bx + dx, by + dy, bz + dz);
                    if (!(block.getBlockData() instanceof Openable openable) || !openable.isOpen()) {
                        continue;
                    }
                    Location door = block.getLocation();
                    if (sharedOpenDoors.containsKey(door) || state.openedDoors.contains(door)) {
                        continue; // already tracked — ours, or another companion's
                    }
                    double ddx = block.getX() + 0.5 - npcLocation.getX();
                    double ddz = block.getZ() + 0.5 - npcLocation.getZ();
                    if (ddx * ddx + ddz * ddz > DOOR_OPEN_DISTANCE_SQUARED) {
                        continue;
                    }
                    // Direction of travel is the direction to the companion's next waypoint.
                    double axisLength = Math.sqrt(state.lastGoalDx * state.lastGoalDx
                            + state.lastGoalDz * state.lastGoalDz);
                    if (axisLength < 1.0E-3) {
                        continue;
                    }
                    double axisX = state.lastGoalDx / axisLength;
                    double axisZ = state.lastGoalDz / axisLength;
                    double side = (ddx * axisX + ddz * axisZ) >= 0.0 ? 1.0 : -1.0;
                    state.openedDoors.add(door);
                    sharedOpenDoors.put(door, new DoorHold(axisX, axisZ, side,
                            npcLocation.getX(), npcLocation.getZ(), Bukkit.getCurrentTick()));
                }
            }
        }
    }

    /**
     * Whether anyone is standing in the doorway, which would make closing it dangerous.
     *
     * <p>Checks the owner and both companions. The door block itself and the block just in
     * front of it are the two places someone can stand to block it. Only entities close by are
     * looked at, so this stays cheap enough to run every tick.</p>
     */
    private boolean isStandingInDoorway(Location door, Location npcLocation) {
        Location[] spots = {
                door.clone(),
                door.clone().add(0.5, 0, 0.5),
                door.clone().add(-0.5, 0, -0.5)
        };
        // 2 blocks covers the door and both neighbouring spots.
        for (Entity entity : npcLocation.getNearbyEntities(2.0, 2.0, 2.0)) {
            if (!(entity instanceof org.bukkit.entity.LivingEntity living) || living.isDead()) {
                continue;
            }
            boolean isParty = entity instanceof Player
                    || entity.getScoreboardTags().contains(TAG);
            if (!isParty) {
                continue;
            }
            Location position = living.getLocation();
            for (Location spot : spots) {
                if (position.distanceSquared(spot) < 1.0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether an offset from the companion points towards the owner.
     *
     * <p>Uses a dot product against the direction to the owner: offsets in front score positive,
     * offsets behind score negative. A small sideways tolerance keeps a door exactly on the path
     * from being rejected.</p>
     */
    private static boolean isBetween(Location npcLocation, double ownerDx, double ownerDz,
                                    double offsetX, double offsetZ) {
        double ownerLength = Math.sqrt(ownerDx * ownerDx + ownerDz * ownerDz);
        double offsetLength = Math.sqrt(offsetX * offsetX + offsetZ * offsetZ);
        if (ownerLength < 1.0E-3 || offsetLength < 1.0E-3) {
            // Degenerate: no clear direction, so do not touch anything.
            return false;
        }
        double dot = (ownerDx * offsetX + ownerDz * offsetZ) / (ownerLength * offsetLength);
        return dot >= DOOR_DIRECTION_THRESHOLD;
    }

    /**
     * Nudges a jammed companion one block sideways to free itself.
     *
     * <p>Picks the side that is free and leads away from whatever it is wedged against, so the
     * companion steps around the obstacle instead of pushing into it. Gives up after
     * {@link #MAX_SIDE_STEP_ATTEMPTS}, letting the caller fall back to a teleport.</p>
     *
     * @return true when a sidestep was performed
     */
    private static boolean trySideStep(Mannequin npc, Player owner, Location ownerLocation,
                                       FollowState state) {
        if (state.sideStepAttempts >= MAX_SIDE_STEP_ATTEMPTS) {
            return false;
        }
        state.sideStepAttempts++;

        Location current = npc.getLocation();
        // Try both perpendicular directions relative to the owner's direction.
        double baseAngle = Math.toRadians(ownerLocation.getYaw());
        double[] offsets = {Math.PI / 2, -Math.PI / 2};
        for (double offset : offsets) {
            double angle = baseAngle + offset;
            Location target = new Location(current.getWorld(),
                    current.getX() - Math.sin(angle),
                    current.getY(),
                    current.getZ() + Math.cos(angle));
            if (!isFreeForCompanion(target)) {
                continue;
            }
            npc.teleport(target);
            npc.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
            state.path = List.of();
            state.plannedGoal = null;
            state.waypointIndex = 0;
            state.lastProgressPosition = null;
            return true;
        }
        return false;
    }

    /**
     * Sets the open state of a door or gate, keeping both halves of a two-block door in sync.
     *
     * <p>Vanilla stores {@code open} on each door half independently for rendering, so only flipping
     * one half would leave the other visually closed.</p>
     */
    private static void setDoorState(Block block, boolean open) {
        if (block.getBlockData() instanceof Openable openable && openable.isOpen() != open) {
            openable.setOpen(open);
            block.setBlockData(openable, false);
        }
        if (block.getBlockData() instanceof Bisected bisected) {
            Block other = bisected.getHalf() == Bisected.Half.BOTTOM
                    ? block.getRelative(BlockFace.UP)
                    : block.getRelative(BlockFace.DOWN);
            if (other.getBlockData() instanceof Openable otherOpenable && otherOpenable.isOpen() != open) {
                otherOpenable.setOpen(open);
                other.setBlockData(otherOpenable, false);
            }
        }
    }

    /**
     * Lowers a landing spot to the first safe floor near it, for an owner standing on a bed or
     * another raised block. The horizontal candidate is preserved, so a safety teleport does not
     * move through a wall to the owner's other side.
     *
     * @return a clear supported position, or {@code null} when this column has no safe landing
     */
    private static Location dropToFloor(Location preferred, Location ownerLocation) {
        if (preferred == null || preferred.getWorld() == null) {
            return null;
        }
        if (hasSafeFloor(preferred)) {
            return preferred;
        }
        for (int down = 1; down <= 2; down++) {
            Location lower = preferred.clone().subtract(0.0, down, 0.0);
            if (!isFreeForCompanion(lower)) {
                break;
            }
            if (hasSafeFloor(lower)) {
                return lower;
            }
        }
        return null;
    }

    /** Whether the candidate is clear and supported by a non-hazardous block. */
    private static boolean hasSafeFloor(Location candidate) {
        if (!isFreeForCompanion(candidate)) {
            return false;
        }
        Block floor = candidate.clone().subtract(0.0, 1.0, 0.0).getBlock();
        Material type = floor.getType();
        if (type == Material.LAVA || type == Material.FIRE || type == Material.SOUL_FIRE
                || type == Material.CACTUS || type == Material.MAGMA_BLOCK
                || type == Material.POWDER_SNOW || type == Material.SWEET_BERRY_BUSH
                || type == Material.POINTED_DRIPSTONE) {
            return false;
        }
        return !floor.getCollisionShape().getBoundingBoxes().isEmpty();
    }

    /** Teleport catch-up distance for the given standing mode. */
    private int emergencyTeleportDistance(FollowMode mode) {
        return mode == FollowMode.CLOSE ? plugin.closeTeleportDistance() : plugin.teleportDistance();
    }

    /**
     * Turns the companions' melee behaviour on or off.
     *
     * <p>This is a separate toggle rather than a third mode, so it can be combined with either
     * FOLLOW or CLOSE: a companion can stay close to the owner and still fight.</p>
     *
     * @param archetype "yui", "sandrone", or "semua" for both
     * @param enabled   true to let the companions fight, false to hold fire
     * @return how many companions were actually changed
     */
    public int setAttacking(Player owner, String archetype, boolean enabled) {
        int changed = 0;
        for (Map.Entry<CompanionKey, FollowState> entry : followStates.entrySet()) {
            CompanionKey key = entry.getKey();
            if (!key.ownerId().equals(owner.getUniqueId())) {
                continue;
            }
            if (!archetype.equalsIgnoreCase("semua") && !archetype.equalsIgnoreCase(key.archetype())) {
                continue;
            }
            FollowState state = entry.getValue();
            state.attackEnabled = enabled;
            if (!enabled) {
                state.attackTarget = null;
                state.attackTargetTick = Long.MIN_VALUE;
                state.lastSwingTick = Long.MIN_VALUE;
                clearDangerMarkers(state);
                state.lastMarkerAlertTick = Long.MIN_VALUE;
                // Drop the sword again and let the companion resume its formation spot.
                state.path = List.of();
                state.plannedGoal = null;
                state.lastPlanTick = Long.MIN_VALUE;
            }
            UUID npcId = ownerToNpc.get(key);
            Entity npc = npcId == null ? null : Bukkit.getEntity(npcId);
            if (npc instanceof Mannequin mannequin) {
                setSword(mannequin, enabled);
            }
            changed++;
        }
        return changed;
    }

    /**
     * Puts an iron sword in the companion's hand while it is fighting, and takes it away again.
     *
     * <p>The mannequin cannot wield a weapon the way a player does, so the sword is decoration:
     * it tells other players at a glance that the companion is armed and dangerous.</p>
     */
    private static void setSword(Mannequin npc, boolean armed) {
        org.bukkit.inventory.EntityEquipment equipment = npc.getEquipment();
        if (equipment == null) {
            return;
        }
        equipment.setItemInMainHand(armed
                ? new org.bukkit.inventory.ItemStack(Material.IRON_SWORD)
                : null);
    }

    /** Teleports the companion to its mode-specific target, closing its doors and resetting route state. */
    private void rescueTeleport(Mannequin npc, Player owner, Location ownerLocation, FollowState state) {
        // Only the doors this companion opened are closed. Doors the other companion is still
        // using must stay open, or that one gets shut inside a doorway.
        for (Location door : state.openedDoors) {
            if (sharedOpenDoors.remove(door) != null) {
                setDoorState(door.getBlock(), false);
            }
        }
        state.openedDoors.clear();
        // Never replace an unreachable mode target with an arbitrary side spot: that fallback was
        // the original way a close-mode rescue could land on the opposite side of a wall. If the
        // selected landing point is not clear, leave the companion where it is and retry later.
        Location spot = formationSpot(npc, owner, state, state.mode == FollowMode.CLOSE);
        if (spot == null || spot.getWorld() == null || !isFreeForCompanion(spot)) {
            return;
        }
        // Lower only within this same column, and only when the landing block remains clear.
        Location floorSpot = dropToFloor(spot, ownerLocation);
        if (floorSpot == null || !isFreeForCompanion(floorSpot)) {
            return;
        }
        npc.teleport(floorSpot);
        npc.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        state.path = List.of();
        state.plannedGoal = null;
        state.waypointIndex = 0;
        state.lastProgressPosition = null;
        state.sideStepAttempts = 0;
    }

    /** Closes the doors one companion still holds open. */
    private void closeOwnDoors(FollowState state) {
        for (Location door : state.openedDoors) {
            if (sharedOpenDoors.remove(door) != null) {
                setDoorState(door.getBlock(), false);
            }
        }
        state.openedDoors.clear();
    }

    /** Removes the companion of the given archetype owned by the given player, if any. */
    public void despawn(Player owner, String archetype) {
        CompanionKey key = new CompanionKey(owner.getUniqueId(), archetype.toLowerCase(Locale.ROOT));
        cancel(key);
        UUID npcId = ownerToNpc.remove(key);
        if (npcId == null) {
            return;
        }
        Entity npc = Bukkit.getEntity(npcId);
        if (npc != null) {
            npc.remove();
        }
    }

    /** Removes every companion owned by the given player, across all archetypes. */
    public void despawn(Player owner) {
        for (String archetype : List.of("yui", "sandrone")) {
            despawn(owner, archetype);
        }
    }

    /** Removes companions owned by the given players. */
    public void despawnAll(Collection<? extends Player> players) {
        for (Player player : players) {
            despawn(player);
        }
    }

    /**
     * Removes every companion in the world, including ones left behind by a crash.
     *
     * <p>Removal is by scoreboard tag rather than by bookkeeping, so strays from a previous run are
     * cleaned up too. This is used on disable and by the admin clear command.</p>
     */
    public void despawnEverything() {
        for (BukkitTask task : followTasks.values()) {
            task.cancel();
        }
        followTasks.clear();
        for (FollowState state : followStates.values()) {
            closeOwnDoors(state);
            clearDangerMarkers(state);
        }
        followStates.clear();
        ownerToNpc.clear();
        for (World world : Bukkit.getWorlds()) {
            List<Entity> tagged = world.getEntities().stream()
                    .filter(entity -> entity.getScoreboardTags().contains(TAG))
                    .toList();
            tagged.forEach(Entity::remove);
        }
    }

    /** Whether any companion is currently tracked for the given player. */
    public boolean hasCompanion(UUID ownerId) {
        return ownerToNpc.keySet().stream().anyMatch(key -> key.ownerId().equals(ownerId));
    }

    /** Returns the live companion entity of the given archetype, or null when absent. */
    public Entity getCompanion(UUID ownerId, String archetype) {
        UUID npcId = ownerToNpc.get(new CompanionKey(ownerId, archetype.toLowerCase(Locale.ROOT)));
        return npcId == null ? null : Bukkit.getEntity(npcId);
    }

    /**
     * Sets the owner's companions to CLOSE mode, which remains until /ikut is used.
     *
     * @param archetype "yui", "sandrone", or "semua" for both
     * @return how many companions were actually switched
     */
    public int callClose(Player owner, String archetype) {
        return setMode(owner, archetype, FollowMode.CLOSE);
    }

    /**
     * Sets the owner's companions to FOLLOW mode, restoring their normal formation.
     *
     * @param archetype "yui", "sandrone", or "semua" for both
     * @return how many companions were actually switched
     */
    public int callFollow(Player owner, String archetype) {
        return setMode(owner, archetype, FollowMode.FOLLOW);
    }

    private int setMode(Player owner, String archetype, FollowMode mode) {
        int changed = 0;
        for (Map.Entry<CompanionKey, FollowState> entry : followStates.entrySet()) {
            CompanionKey key = entry.getKey();
            if (!key.ownerId().equals(owner.getUniqueId())) {
                continue;
            }
            if (!archetype.equalsIgnoreCase("semua") && !archetype.equalsIgnoreCase(key.archetype())) {
                continue;
            }
            FollowState state = entry.getValue();
            state.mode = mode;
            savedModes.put(key, mode);
            state.closeTarget = null;
            state.closeTargetTick = Long.MIN_VALUE;
            // Force a fresh route immediately after switching modes.
            state.path = List.of();
            state.plannedGoal = null;
            state.lastPlanTick = Long.MIN_VALUE;
            state.lastAttemptTick = Long.MIN_VALUE;
            state.lastProgressPosition = null;
            state.sideStepAttempts = 0;
            changed++;
        }
        if (changed > 0) {
            saveSavedModes();
        }
        return changed;
    }

    /** Returns true when the entity UUID belongs to the given player's active companion. */
    public boolean isOwnedBy(Player owner, UUID companionId) {
        return ownerToNpc.entrySet().stream().anyMatch(entry ->
                entry.getKey().ownerId().equals(owner.getUniqueId())
                        && entry.getValue().equals(companionId));
    }

    /** Whether a companion of the given archetype is currently following the owner. */
    public boolean hasCompanion(UUID ownerId, String archetype) {
        return ownerToNpc.containsKey(new CompanionKey(ownerId, archetype.toLowerCase(Locale.ROOT)));
    }

    /**
     * The follow mode a companion is in right now, or null when it is not active.
     *
     * <p>Read-only view for the control panel: it shows which mode is in force without
     * changing anything, so the GUI can never disagree with the commands.</p>
     */
    public FollowMode modeOf(Player owner, String archetype) {
        FollowState state = followStates.get(
                new CompanionKey(owner.getUniqueId(), archetype.toLowerCase(Locale.ROOT)));
        return state == null ? null : state.mode;
    }

    /**
     * Whether a companion is currently allowed to attack, or false when it is not active.
     */
    public boolean isAttacking(Player owner, String archetype) {
        FollowState state = followStates.get(
                new CompanionKey(owner.getUniqueId(), archetype.toLowerCase(Locale.ROOT)));
        return state != null && state.attackEnabled;
    }

    private void cancel(CompanionKey key) {
        BukkitTask task = followTasks.remove(key);
        if (task != null) {
            task.cancel();
        }
        FollowState state = followStates.remove(key);
        if (state != null) {
            closeOwnDoors(state);
        }
    }
}
