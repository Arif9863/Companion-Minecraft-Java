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
import org.bukkit.block.data.type.Gate;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
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

    /** Every companion archetype the plugin knows about. */
    private static final List<String> ARCHETYPES =
            List.of("yui", "sandrone", "eris", "furina", "ei", "columbina", "escoffier");

    /** Session kill/potion/dialogue state for Eris, keyed to her permanent owner. */
    private static final class ErisState {
        private final UUID ownerId;
        private int killCount;
        private boolean strengthGranted;
        private long lastSpeedTick = Long.MIN_VALUE;
        private long lastDialogueTick = Long.MIN_VALUE;
        private long lastIdleTick = Long.MIN_VALUE;
        private long lastKillTick = Long.MIN_VALUE;
        private long lastSupportTick = Long.MIN_VALUE;
        private long lastFollowupTick = Long.MIN_VALUE;
        /** Tick at which a support line was spoken, marking the start of the follow-up window. */
        private long lastSupportSequenceTick = Long.MIN_VALUE;
        private String lastIdleLine;
        private String lastKillLine;
        private String lastSupportLine;
        private String lastFollowupLine;

        private ErisState(UUID ownerId) {
            this.ownerId = ownerId;
        }
    }

    private static final long ERIS_SPEED_COOLDOWN_TICKS = 100L;
    private static final int ERIS_SPEED_DURATION_TICKS = 200;
    private static final int ERIS_STRENGTH_DURATION_TICKS = 300;
    private static final long ERIS_DIALOGUE_GLOBAL_COOLDOWN_TICKS = 100L;
    private static final long ERIS_IDLE_COOLDOWN_TICKS = 1200L;
    private static final long ERIS_KILL_COOLDOWN_TICKS = 200L;
    private static final long ERIS_SUPPORT_COOLDOWN_TICKS = 600L;
    private static final long ERIS_FOLLOWUP_COOLDOWN_TICKS = 600L;

    private static final List<String> ERIS_IDLE_LINES = List.of(
            "Ferdi-Kun, kalau cuma berdiri terus, musuhnya keburu bosan!",
            "Hmph. Lagi mikirin sesuatu, atau memang sengaja bikin aku menunggu?",
            "Kita sudah siap. Tinggal bilang mau ke mana.",
            "Jangan lengah. Dunia ini nggak bakal berhenti bergerak cuma karena kita diam.",
            "Kalau capek, bilang saja. Tapi jangan harap aku menggendongmu!",
            "Aku mencium bau monster... atau itu cuma bau makanan?",
            "Ayo, Ferdi-Kun. Aku ingin menguji pedangku!",
            "Kau aman di dekatku. Jadi, berhenti pasang wajah khawatir itu."
    );
    private static final List<String> ERIS_KILL_LINES = List.of(
            "Bagus! Begitu caranya—jangan beri mereka kesempatan!",
            "Hah! Lumayan, Ferdi-Kun. Tapi jangan sampai besar kepala!"
    );
    private static final List<String> ERIS_SUPPORT_LINES = List.of(
            "Minggir, Ferdi-Kun! Biar aku yang membuka jalan!",
            "Aku di sini! Jangan berani menyentuhnya!"
    );
    private static final List<String> ERIS_FOLLOWUP_LINES = List.of(
            "Jangan salah paham—aku cuma nggak mau kehilangan rekan bertarung yang lumayan!"
    );
    private static final String ERIS_DENY_LINE =
            "Hmph. Jangan panggil-panggil aku! Aku cuma ikut Ferdi-Kun—bukan milikmu!";

    /**
     * The standing behaviours a companion can have.
     *
     * <p>A mode stays in force until another command replaces it — there is no timeout. The
     * formation is therefore not a permanent behaviour but one of several choices, which is what
     * makes both a cramped room and open exploration work without a separate plugin.</p>
     */
    public enum FollowMode {
        /** Keeps the formation and a comfortable distance — good for exploring outdoors. */
        FOLLOW,
        /** Stays as close as the geometry allows — good for small rooms. */
        CLOSE,
        /**
         * Waits where it was told to stay instead of following the owner.
         *
         * <p>The companion stops chasing the owner and wanders around the spot it was given, but
         * it keeps watching the owner from there. No emergency teleport happens in this mode:
         * the companion is where it was asked to be, so being far away is not a problem.</p>
         */
        WAIT
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
    /** How far the companion may be from a route waypoint horizontally. */
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
     * How far ahead on its route the companion looks for a door to open.
     *
     * <p>Four waypoints is far enough to see a door that the companion is about to walk into,
     * and short enough that a door it has already walked past is not reopened.</p>
     */
    private static final int DOOR_LOOKAHEAD_WAYPOINTS = 4;
    /**
     * How close the companion has to be to a door before it works it.
     *
     * <p>2.5 blocks across the floor: far enough to reach out for the handle, tight enough that
     * a door around the corner stays shut until the companion is really there.</p>
     */
    private static final double DOOR_REACH_SQUARED = 6.25;
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
     * Maximum angle between the companion's body direction and the mob it glances at.
     *
     * <p>Beyond roughly 90 degrees the head would have to turn further than a neck allows, and
     * Minecraft compensates by twisting the whole body, which looks broken. A companion simply
     * does not notice something behind it.</p>
     */
    private static final double MAX_GLANCE_ANGLE_DEGREES = 90.0;
    /** Ticks a glance lasts: 2-3 seconds, so the behaviour varies between companions. */
    private static final long GLANCE_DURATION_TICKS = 40L;

    /** How far a companion that throws its weapon will throw, in blocks. */
    private static final double RANGED_THROW_RANGE = 18.0;

    /** Initial speed of a thrown trident, in blocks per tick. */
    private static final double RANGED_THROW_SPEED = 2.2;

    /** How close a ranged companion stays to its target, so it keeps throwing instead of closing in. */
    private static final double RANGED_KEEP_DISTANCE = 8.0;

    /**
     * How high above its feet a companion's eyes are, in blocks.
     *
     * <p>Used when the companion works out whether it could throw from a spot it is only
     * planning to walk to. A mannequin's own eye height is not known until it exists in the
     * world, so the usual player height is used instead.</p>
     */
    private static final double EYE_HEIGHT = 1.62;

    /**
     * How long a found throwing spot is kept before the search runs again, in ticks.
     *
     * <p>Two seconds is long enough that the companion does not search on every tick, and
     * short enough that it notices when the mob has moved somewhere else.</p>
     */
    private static final long FIRING_SPOT_INTERVAL_TICKS = 40L;

    /** How long Ei's spear thrust animation lasts, in ticks. */
    private static final int JAB_ANIMATION_TICKS = 4;

    /**
     * How long a companion leaves a mob alone after it was pulled off its post or out of its
     * owner's reach, in ticks.
     *
     * <p>Two seconds is enough for the companion to walk back onto its post and start its
     * wander again. Without the pause the same mob pulls it straight back out, and the
     * companion walks out and back for ever instead of holding its ground. The pause applies
     * to that one mob only: another one that comes close is still fought.</p>
     */
    private static final long LEASH_BREAK_TICKS = 40L;

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
    /**
     * Upward velocity of a parkour jump.
     *
     * <p>A player's own jump is about 0.42 and lifts them 1.25 blocks; the companion uses the
     * same kind of jump, so it rises just as high over a ledge.</p>
     */
    private static final double PARKOUR_JUMP_VELOCITY = 0.45;
    /**
     * Horizontal speed while crossing a gap.
     *
     * <p>A sprinting player covers about three blocks in a jump. The companion's walking pace is
     * far slower, so it sprints for the duration of the jump instead of walking over the gap.</p>
     */
    private static final double PARKOUR_CROSS_SPEED = 0.34;
    /**
     * How many ticks the climb jump should take to reach the ledge.
     *
     * <p>A jump of 0.50 is above one block of height from about the third tick until roughly the
     * tenth, and comes back down to that height around the eleventh tick. Six ticks sits in the
     * middle of that window, so the companion arrives above the ledge while it is still high
     * enough to land on it: any earlier and it sails over the top, any later and it drops back
     * into the gap.</p>
     */
    private static final int PARKOUR_CLIMB_TARGET_TICKS = 6;
    /**
     * Upward velocity of a jump onto a ledge one block higher.
     *
     * <p>A player's own jump of 0.42 lifts them 1.25 blocks, which is only just enough for one
     * block of height. 0.50 clears that height with room to spare in every jump arc, so the
     * companion reliably gets its feet onto the top of the ledge.</p>
     */
    private static final double PARKOUR_CLIMB_JUMP_VELOCITY = 0.50;
    /** Slowest horizontal speed used while climbing onto a ledge. */
    private static final double PARKOUR_CLIMB_MIN_SPEED = 0.28;
    /**
     * Fastest horizontal speed used while climbing onto a ledge.
     *
     * <p>Fast enough to cross the widest gap of three blocks before the jump carries the
     * companion back down to the height of the ledge.</p>
     */
    private static final double PARKOUR_CLIMB_MAX_SPEED = 0.60;
    /**
     * How close to the middle of the ledge the companion has to be before it stops moving forward.
     *
     * <p>Pushing on past the ledge makes the companion sail over the top and land far behind it,
     * so the push stops as soon as the companion is over the block.</p>
     */
    private static final double PARKOUR_LEDGE_STOP_DISTANCE = 0.30;
    /**
     * Upward velocity of the hop that lifts a companion onto the top of a ledge.
     *
     * <p>A player's own jump lifts 1.25 blocks, which is just enough for one block of height.</p>
     */
    private static final double PARKOUR_CLIMB_ASSIST = 0.42;
    /**
     * How far below the top of the ledge a companion may be and still be helped up.
     *
     * <p>A hop lifts it 1.25 blocks, so anything deeper than that is out of reach.</p>
     */
    private static final double PARKOUR_CLIMB_ASSIST_REACH = 1.25;
    /** How many ticks the companion keeps its parkour speed while airborne over a gap. */
    private static final int PARKOUR_AIR_TICKS = 14;
    /** Additional airborne window for downhill gap jumps, whose landing takes longer. */
    private static final int PARKOUR_DROP_AIR_TICKS = 28;
    /**
     * How far ahead the ground is checked for a gap before the companion jumps.
     *
     * <p>The check begins at the immediate route column, and the companion only commits when an
     * unsupported gap is actually there. It should not jump down a supported stair tread.</p>
     */
    private static final double GAP_LOOKAHEAD = 1.0;
    /**
     * How much higher a waypoint has to be before the companion jumps for it.
     *
     * <p>A carpet is a sixteenth of a block tall and a slab half a block, and the companion
     * walks over both of them without a jump. Only a real step needs one, so the threshold sits
     * above a slab and below a full block.</p>
     */
    private static final double STEP_UP_JUMP_HEIGHT = 0.6;
    /**
     * How close an elevated waypoint has to be before the companion hops up onto it.
     *
     * <p>A hop is only useful for a step right in front of the companion. Hopping towards a
     * ledge that is still several blocks away keeps the companion in the air over the gap it is
     * about to jump, and a jump cannot be started from there.</p>
     */
    private static final double PARKOUR_STEP_UP_REACH = 1.7;
    /** How many blocks ahead the landing of a parkour jump is looked for. */
    private static final int PARKOUR_LANDING_SEARCH = 4;
    /** Maximum supported gap length in blocks between takeoff and landing. */
    private static final int PARKOUR_GAP = 3;
    /** Maximum supported downward landing drop for a gap jump. */
    private static final int PARKOUR_DROP = 4;
    /** Target flight time when steering toward a lower parkour landing. */
    private static final int PARKOUR_DROP_TARGET_TICKS = 8;
    /** Slowest horizontal speed used for a downhill gap jump. */
    private static final double PARKOUR_DROP_MIN_SPEED = 0.28;
    /** Fastest horizontal speed used for a downhill gap jump. */
    private static final double PARKOUR_DROP_MAX_SPEED = 0.55;
    /** How far beside the owner a companion appears when summoned or rescued. */
    private static final double SIDE_PLACEMENT_DISTANCE = 1.2;
    /** How far to the owner's left or right a companion stands while following. */
    private static final double SIDE_FORMATION_OFFSET = 1.6;
    /**
     * How far apart two companions standing in the same line on one side are.
     *
     * <p>A little more than the width of a mannequin, so they stand shoulder to shoulder
     * without clipping into each other.</p>
     */
    private static final double FORMATION_RANK_SPACING = 1.4;
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

    /**
     * How far a waiting companion may wander from the spot it was told to stay.
     *
     * <p>5 blocks: enough for the companion to look alive — walking around, turning, coming back —
     * without wandering off into another room and losing the player.</p>
     */
    private static final double WAIT_WANDER_RADIUS = 5.0;

    /**
     * How far the companion may stray beyond the wander radius before it is pulled back.
     *
     * <p>A little slack on top of the radius, so a companion that overshoots a step does not get
     * yanked back mid-stride.</p>
     */
    private static final double WAIT_WANDER_SLACK = 1.5;

    /**
     * Ticks a waiting companion walks in one direction before it may pick a new one.
     *
     * <p>40 ticks (2 seconds) is short enough that the wander looks unhurried rather than like
     * the companion is walking in a straight line to a destination.</p>
     */
    private static final long WAIT_DIRECTION_MIN_TICKS = 40L;

    /** Ticks a waiting companion may walk in one direction before it has to turn. */
    private static final long WAIT_DIRECTION_MAX_TICKS = 110L;

    /**
     * Ticks a waiting companion stands still before it starts walking again.
     *
     * <p>Resting in between is what makes it read as a companion idling rather than a companion
     * pacing.</p>
     */
    private static final long WAIT_REST_MIN_TICKS = 40L;

    /** Upper bound for the idle pause between strolls. */
    private static final long WAIT_REST_MAX_TICKS = 130L;

    /** Walking speed of a waiting companion while it strolls, in blocks per tick. */
    private static final double WAIT_WANDER_SPEED = 0.12;

    /**
     * Hostile mobs that are really neutral: they only strike when provoked.
     *
     * <p>They count as a threat only once they actually come for the owner or a companion. A
     * waiting companion does not pick a fight with one that merely strolls past its post: an
     * Enderman that is hit teleports away and may well come back for the owner instead.</p>
     */
    private static final Set<EntityType> NEUTRAL_HOSTILE_MOBS = Set.of(EntityType.ENDERMAN);

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
        /** Chunk coordinates of the chunk this companion currently holds a ticket for. */
        private int heldChunkX = Integer.MIN_VALUE;
        private int heldChunkZ = Integer.MIN_VALUE;
        /** Sideways nudges already tried for the current jam. */
        private int sideStepAttempts;
        /** Tick at which a danger marker was last sent, to avoid chat spam. */
        private long lastMarkerAlertTick = Long.MIN_VALUE;
        /** UUIDs of hostile mobs this companion is currently marking. */
        private final Set<UUID> markedHostiles = new HashSet<>();
        /** UUIDs of hostile mobs the owner has already been warned about. */
        private final Set<UUID> warnedHostiles = new HashSet<>();
        /** The standing behaviour of this companion: follow in formation, or stay close. */
        private FollowMode mode = FollowMode.FOLLOW;
        /**
         * Tick at which the mode was last changed by a crouching right-click.
         *
         * <p>The listener already drops the off-hand copy of a click; this keeps any other
         * repeat from turning one decision into two steps of the mode cycle.</p>
         */
        private long lastCycleClickTick = Long.MIN_VALUE;
        /** The mob this companion is currently engaging, or null when none. */
        private UUID attackTarget;
        /** Tick at which the current attack target was picked, used for the grace period. */
        private long attackTargetTick = Long.MIN_VALUE;
        /**
         * Hostile mobs the companion just let go because they pulled it off its post or out
         * of its owner's reach. They are not picked up again while the companion returns,
         * but another mob can still be defended against.
         */
        private final Map<UUID, Long> releasedTargetsUntil = new HashMap<>();
        /** Tick of the last swing, used to space the melee hits out. */
        private long lastSwingTick = Long.MIN_VALUE;
        /** Tick of the last ground footstep sound. */
        private long lastFootstepTick = Long.MIN_VALUE;
        /** Tick until which the companion keeps its parkour sprint while airborne over a gap. */
        private long parkourUntilTick = Long.MIN_VALUE;
        /** Whether the far side of the current gap sits one block higher. */
        private boolean parkourClimb;
        /**
         * The block the companion is climbing onto, or null when it is crossing a flat gap.
         *
         * <p>The climb is aimed at that block: the companion has to arrive above it, not sail
         * over it, so the forward push is measured against the distance to this block.</p>
         */
        private PathPlanner.BlockPos parkourLedge;
        /** Horizontal speed chosen for the current climb, derived from the distance to the ledge. */
        private double parkourClimbSpeed;
        /**
         * Whether the climb has already been helped up by a short hop.
         *
         * <p>The hop may only be given once per jump: repeating it would keep the companion
         * bouncing above the ledge instead of landing on it.</p>
         */
        private boolean parkourAssisted;
        /** Whether the current parkour landing is below the takeoff point. */
        private boolean parkourDrop;
        /** Lower block to aim for while crossing a downward parkour gap, if any. */
        private PathPlanner.BlockPos parkourDropLanding;
        /** Horizontal speed chosen for the current drop jump. */
        private double parkourDropSpeed;
        /** Whether the mannequin's current pose is swimming, to avoid redundant pose writes. */
        private boolean swimmingPose;
        /** Shared close-mode target selected from a reachable, unblocked nearby spot. */
        private Location closeTarget;
        /** Tick at which the close target was last recomputed. */
        private long closeTargetTick = Long.MIN_VALUE;
        /**
         * The spot this companion found to throw its trident from, or null when none was found.
         *
         * <p>Searching for such a spot means trying a lot of places and checking every one of
         * them for a clear line, which is far too much work to redo on every tick. The spot is
         * kept until the target moves or the search is due again.</p>
         */
        private Location firingSpot;
        /** Tick at which the firing spot was last searched for. */
        private long firingSpotTick = Long.MIN_VALUE;
        /** The mob the firing spot was found for, so a new target invalidates it. */
        private UUID firingSpotTarget;
        /** The spot this companion was told to wait at, in WAIT mode. */
        private Location waitSpot;
        /** The direction the companion is currently strolling towards. */
        private Location waitWanderTarget;
        /** Tick at which the current stroll direction was picked. */
        private long waitSpotPickedTick = Long.MIN_VALUE;
        /** Tick at which the companion may next choose a new direction. */
        private long waitNextTurnTick = Long.MIN_VALUE;
        /** Whether the companion is currently resting between strolls. */
        private boolean waitSpotPaused;
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
     * A door a companion is looking after, together with what it needs to know to shut it again.
     *
     * <p>The way the door was crossed is kept in the form the companion actually experienced it:
     * the axis it was travelling along and the side of the threshold it started from. That is
     * what tells a door that has been walked through from one that is merely still ahead.</p>
     *
     * @param axisX horizontal component of the travel direction across the doorway
     * @param axisZ horizontal component of the travel direction across the doorway
     * @param side  which side of the doorway the companion stood on when it claimed the door
     * @param originX companion's horizontal position when the door was claimed
     * @param originZ companion's horizontal position when the door was claimed
     * @param openedTick tick at which the door was claimed
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
     * Eris's permanent owner.
     *
     * <p>Eris is a standalone companion: whoever calls her first keeps her, and the bond does not
     * expire. A second player calling {@code /eris} afterwards is refused with Eris's own deny
     * line, and only the owner can change her behaviour or open her pouch.</p>
     */
    private UUID erisOwnerId;
    /**
     * Whether the owner has actually called Eris.
     *
     * <p>A designated owner only reserves her: the testers keep access until he calls her, at
     * which point she is claimed for good.</p>
     */
    private boolean erisCalled;
    /**
     * The player Eris is reserved for, taken from the config.
     *
     * <p>Only this player may call her while she has not been claimed yet, which is how the
     * companion is handed to a specific friend without any player being able to take her by
     * calling {@code /eris} first.</p>
     */
    private UUID erisDesignatedOwner;
    /**
     * Players allowed to use Eris while she is still unclaimed.
     *
     * <p>They lose access the moment the owner calls her, so a tester can try the companion
     * out without being able to keep her.</p>
     */
    private final Set<UUID> erisTesters = new HashSet<>();
    /** Per-owner Eris session state: kills, potion cooldowns, and dialogue timing. */
    private final Map<UUID, ErisState> erisStates = new HashMap<>();
    /** The support abilities of the newer companions, one set of bookkeeping per owner. */
    private final SupportAbilities supportAbilities = new SupportAbilities();
    /** The voices of the newer companions: Furina, Ei, Columbina, and Escoffier. */
    private final CompanionDialogue dialogue = new CompanionDialogue();
    /**
     * Mobs a companion recently hit, mapped to the companion that hit them.
     *
     * <p>A mannequin's final blow is invisible to the death event, so the damage is remembered
     * here and confirmed when the mob dies.</p>
     */
    private final Map<UUID, CompanionKey> killTracker = new HashMap<>();
    /**
     * Doors any companion currently holds open, shared across all companions so the second one
     * walking through a doorway doesn't fight the first over who closes it.
     */
    private final Map<Location, DoorHold> sharedOpenDoors = new HashMap<>();

    public CompanionManager(CompanionNpcsPlugin plugin) {
        this.plugin = plugin;
        loadSavedModes();
        loadErisAccess();
        loadErisOwner();
        // When a companion's support ability fires, she says her own assist line about it.
        supportAbilities.onAbilityFired((owner, archetype, at, tick) ->
                dialogue.onSupport(owner, archetype, at, tick));
        supportAbilities.bindLegacySupport(new SupportAbilities.LegacySupport() {
            @Override
            public void yuiHeal(Player owner, Location at) {
                dialogue.announceYuiHeal(owner, at);
            }

            @Override
            public void sandroneAbsorption(Player owner, Location at) {
                dialogue.announceSandroneAbsorption(owner, at);
            }

            @Override
            public void followUp(Player owner, String archetype, Location at) {
                dialogue.playScheduledFollowUp(owner, archetype, at);
            }
        });
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long tick = Bukkit.getCurrentTick();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!hasAnyGroupCompanion(player)) {
                    continue;
                }
                supportAbilities.tickScheduled(player, tick);
                dialogue.playIdleTurn(player, tick, archetype -> idleSpeechLocation(player, archetype));
            }
        }, 20L, 20L);
    }

    /**
     * Reads the Eris owner and tester lists from the plugin config.
     *
     * <p>The designated owner is the only player who may claim Eris; the testers may use her
     * while she is still unclaimed.</p>
     */
    private void loadErisAccess() {
        erisDesignatedOwner = plugin.erisOwner();
        erisTesters.clear();
        erisTesters.addAll(plugin.erisTesters());
    }

    /**
     * Loads Eris's permanent owner from {@code plugins/CompanionNPCs/eris-owner.yml}.
     *
     * <p>The bond survives a restart: whoever called Eris first keeps her, so the owner is
     * written to disk as soon as she is claimed.</p>
     *
     * <p>A designated owner in the config wins over the stored one. Without that, an owner
     * recorded by an earlier test run would keep Eris forever and the configured player
     * could never call her — which is exactly what happened the first time.</p>
     */
    private void loadErisOwner() {
        if (erisDesignatedOwner != null) {
            if (!erisDesignatedOwner.equals(erisOwnerId)) {
                plugin.getLogger().info("Eris owner set to the configured player "
                        + erisDesignatedOwner + ".");
            }
            erisOwnerId = erisDesignatedOwner;
            saveErisOwner();
            erisStates.computeIfAbsent(erisOwnerId, ErisState::new);
            return;
        }
        File file = new File(plugin.getDataFolder(), "eris-owner.yml");
        if (!file.isFile()) {
            return;
        }
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        String value = config.getString("owner");
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            erisOwnerId = UUID.fromString(value.trim());
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Ignoring invalid Eris owner UUID: " + value);
        }
    }

    /**
     * Whether the given player is allowed to use Eris at all.
     *
     * <p>The owner always may. A tester may only while the owner has not called her yet —
     * once he does, the testers lose every Eris command, exactly as the owner asked. Everybody
     * else is refused.</p>
     */
    public boolean mayUseEris(UUID playerId) {
        if (erisOwnerId != null && erisOwnerId.equals(playerId)) {
            return true;
        }
        if (erisClaimed()) {
            // Somebody already owns her: only that player may still command her.
            return false;
        }
        if (erisDesignatedOwner != null && erisDesignatedOwner.equals(playerId)) {
            return true;
        }
        return erisTesters.contains(playerId);
    }

    /**
     * Whether the given player owns Eris right now.
     *
     * <p>This is the player who actually called her, not the designated owner from the config;
     * only the caller can change her behaviour and open her pouch.</p>
     */
    public boolean isErisOwner(UUID playerId) {
        return erisOwnerId != null && erisOwnerId.equals(playerId);
    }

    /**
     * Whether Eris has already been called by her owner.
     *
     * <p>A designated owner reserves her but does not own her yet: she is only claimed once
     * that player actually calls her. Until then the testers may still use her.</p>
     */
    public boolean erisClaimed() {
        return erisCalled;
    }

    /**
     * Claims Eris for a player, unless somebody else already owns her.
     *
     * <p>When a designated owner is configured, only that player takes ownership. A tester may
     * still call Eris while she is unclaimed — they simply do not take her with them, so the
     * owner can still claim her later.</p>
     *
     * @return true when the caller may have Eris, whether or not they claimed her
     */
    public boolean claimEris(Player owner) {
        UUID playerId = owner.getUniqueId();
        if (erisOwnerId != null) {
            return erisOwnerId.equals(playerId);
        }
        if (erisDesignatedOwner != null && !erisDesignatedOwner.equals(playerId)) {
            // A tester may spawn Eris without claiming her; she remains reserved for Ferdi.
            return erisTesters.contains(playerId);
        }
        erisOwnerId = playerId;
        saveErisOwner();
        erisStates.computeIfAbsent(playerId, ErisState::new);
        return true;
    }

    /** Persists Eris's owner so the bond survives a restart. */
    private void saveErisOwner() {
        File file = new File(plugin.getDataFolder(), "eris-owner.yml");
        FileConfiguration config = new YamlConfiguration();
        if (erisOwnerId != null) {
            config.set("owner", erisOwnerId.toString());
        }
        try {
            config.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not save Eris owner to " + file, ex);
        }
    }

    /** The Eris session state for a player, created on first use. */
    private ErisState erisState(UUID ownerId) {
        return erisStates.computeIfAbsent(ownerId, ErisState::new);
    }

    /**
     * Eris's special behaviour: the potion buffs she grants her owner, and her dialogue.
     *
     * <p>Eris is a support fighter. While she is actively helping — swinging at a hostile or
     * closing on one — she refreshes Speed I on her owner. Once she has taken two kills she grants
     * Strength I as well, once per session.</p>
     */
    private void updateErisBehaviour(Player owner, FollowState state, boolean helping,
                                     boolean idle, long currentTick) {
        if (!"eris".equals(state.archetype) || !owner.isOnline()) {
            return;
        }
        ErisState eris = erisState(owner.getUniqueId());
        if (helping && (eris.lastSpeedTick == Long.MIN_VALUE
                || currentTick - eris.lastSpeedTick >= ERIS_SPEED_COOLDOWN_TICKS)) {
            eris.lastSpeedTick = currentTick;
            owner.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,
                    ERIS_SPEED_DURATION_TICKS, 0, true, false, true));
            String line = speakEris(owner, eris, ERIS_SUPPORT_LINES, eris.lastSupportLine,
                    ERIS_SUPPORT_COOLDOWN_TICKS, eris.lastSupportTick, false);
            if (line != null) {
                eris.lastSupportTick = currentTick;
                eris.lastSupportLine = line;
                eris.lastSupportSequenceTick = currentTick;
            }
        }
        if (!helping && eris.lastSupportSequenceTick != Long.MIN_VALUE
                && currentTick - eris.lastSupportSequenceTick <= ERIS_FOLLOWUP_COOLDOWN_TICKS) {
            String line = speakEris(owner, eris, ERIS_FOLLOWUP_LINES, eris.lastFollowupLine,
                    ERIS_FOLLOWUP_COOLDOWN_TICKS, eris.lastFollowupTick, false);
            if (line != null) {
                eris.lastFollowupTick = currentTick;
                eris.lastFollowupLine = line;
                eris.lastSupportSequenceTick = Long.MIN_VALUE;
            }
        }
        if (idle && (eris.lastIdleTick == Long.MIN_VALUE
                || currentTick - eris.lastIdleTick >= ERIS_IDLE_COOLDOWN_TICKS)) {
            String line = speakEris(owner, eris, ERIS_IDLE_LINES, eris.lastIdleLine,
                    ERIS_IDLE_COOLDOWN_TICKS, eris.lastIdleTick, false);
            if (line != null) {
                eris.lastIdleTick = currentTick;
                eris.lastIdleLine = line;
            }
        }
    }

    /**
     * Sends a dialogue line only if both the category cooldown and the shared anti-spam
     * cooldown pass.
     *
     * @return the selected line, or null when a cooldown blocked it
     */
    private String speakEris(Player owner, ErisState eris, List<String> lines, String previous,
                             long categoryCooldown, long lastCategoryTick, boolean killLine) {
        long now = Bukkit.getCurrentTick();
        if (lastCategoryTick != Long.MIN_VALUE && now - lastCategoryTick < categoryCooldown) {
            return null;
        }
        if (eris.lastDialogueTick != Long.MIN_VALUE
                && now - eris.lastDialogueTick < ERIS_DIALOGUE_GLOBAL_COOLDOWN_TICKS) {
            return null;
        }
        String selected = lines.get((int) (Math.random() * lines.size()));
        if (lines.size() > 1 && selected.equals(previous)) {
            selected = lines.get((lines.indexOf(selected) + 1) % lines.size());
        }
        eris.lastDialogueTick = now;
        owner.sendMessage(Component.text("[Eris] " + selected, NamedTextColor.RED));
        return selected;
    }

    /**
     * Remembers that a companion damaged a mob, so the kill can be credited to her owner.
     *
     * <p>The killer of a mob is only ever a player, so a mannequin's final blow is not visible
     * through {@code getKiller()}. The damage event is the reliable place to notice that a
     * companion was the one who hit it; the death event then confirms it.</p>
     */
    public void noteCompanionDamage(UUID mobId, UUID damagerId) {
        for (Map.Entry<CompanionKey, UUID> entry : ownerToNpc.entrySet()) {
            if (damagerId.equals(entry.getValue())) {
                killTracker.put(mobId, entry.getKey());
                return;
            }
        }
    }

    /**
     * Remembers that a companion's thrown trident hit a mob, so the kill can be credited too.
     *
     * <p>A projectile is not a mannequin, so it never reaches the damage handler above. The
     * trident carries a tag naming the companion who threw it, which is how the kill is
     * credited to the right companion rather than to the owner who fired it.</p>
     */
    public void noteProjectileDamage(UUID mobId, String archetype) {
        for (Map.Entry<CompanionKey, UUID> entry : ownerToNpc.entrySet()) {
            if (entry.getKey().archetype().equalsIgnoreCase(archetype)) {
                killTracker.put(mobId, entry.getKey());
                return;
            }
        }
    }

    /**
     * A mob died: the kill is credited to the companion that struck it, and she says so.
     *
     * <p>Eris counts her kills, because her Strength depends on them. The newer companions
     * only have their kill line to say.</p>
     */
    public void recordCompanionKill(UUID mobId) {
        CompanionKey key = killTracker.remove(mobId);
        if (key == null) {
            return;
        }
        Player owner = Bukkit.getPlayer(key.ownerId());
        if (owner == null || !owner.isOnline()) {
            return;
        }
        if ("eris".equals(key.archetype())) {
            ErisState eris = erisState(owner.getUniqueId());
            eris.killCount++;
            String line = speakEris(owner, eris, ERIS_KILL_LINES, eris.lastKillLine,
                    ERIS_KILL_COOLDOWN_TICKS, eris.lastKillTick, false);
            if (line != null) {
                eris.lastKillTick = Bukkit.getCurrentTick();
                eris.lastKillLine = line;
            }
            if (eris.killCount >= 2 && !eris.strengthGranted) {
                eris.strengthGranted = true;
                owner.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH,
                        ERIS_STRENGTH_DURATION_TICKS, 0, true, false, true));
            }
            return;
        }
        Entity companion = getCompanion(owner.getUniqueId(), key.archetype());
        dialogue.onKill(owner, key.archetype(),
                companion == null ? owner.getLocation() : companion.getLocation(),
                Bukkit.getCurrentTick());
    }

    /** Send Eris's deny line to any non-owner who tries to invoke her. */
    public void denyEris(Player player) {
        player.sendMessage(Component.text("[Eris] " + ERIS_DENY_LINE, NamedTextColor.RED));
    }

    /**
     * Refuses a companion to a player who is not allowed to use her.
     *
     * <p>The newer companions say their own refusal; Yui and Sandrone fall back to the plain
     * message, because they have no voice of their own in the plugin.</p>
     */
    public void denyCompanion(Player player, String archetype) {
        if (CompanionDialogue.hasVoice(archetype)) {
            dialogue.onDeny(player, archetype);
        }
    }

    /**
     * Notes that the owner killed a mob, so Ei's strength can follow it.
     *
     * <p>Only Ei reacts to this: she grants Strength after the owner's first kill. The other
     * companions have no ability that is tied to a kill.</p>
     */
    public void noteOwnerKill(Player owner, LivingEntity victim) {
        if (!owner.isOnline()) {
            return;
        }
        long tick = Bukkit.getCurrentTick();
        supportAbilities.onOwnerKill(owner, "ei", tick);
        if (victim != null && isOwnerKillVoiceVictim(victim)) {
            dialogue.onOwnerKill(owner, tick);
        }
    }

    private static boolean isOwnerKillVoiceVictim(LivingEntity victim) {
        if (victim instanceof Player || victim instanceof org.bukkit.entity.ArmorStand) {
            return false;
        }
        if (victim.getScoreboardTags().contains(TAG)) {
            return false;
        }
        return isHostileMob(victim);
    }

    /** Returns Eris's permanent owner UUID, or null before she has been claimed. */
    public UUID erisOwnerId() {
        return erisOwnerId;
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
            for (String archetype : ARCHETYPES) {
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
     * <p>The permission is checked here as well as in the command, so the restriction holds even
     * if another plugin calls this method directly. Only the players listed as companion owners
     * may bring a companion into the world.</p>
     *
     * @param owner     player the companion belongs to
     * @param archetype the companion to call, for example {@code furina}
     * @return true when the companion was spawned
     */
    public boolean spawn(Player owner, String archetype) {
        return spawn(owner, archetype, false);
    }

    /**
     * Spawns a companion, optionally skipping the companion-owner check.
     *
     * <p>Eris is the one companion that does not belong to Arif's group: she is reserved for
     * her own owner and for her testers, and the {@code /eris} command has already decided
     * whether the player may have her. That decision is what the bypass stands for, so the
     * check is not simply turned off for everybody.</p>
     *
     * @param owner     player the companion belongs to
     * @param archetype the companion to call
     * @param skipOwnerCheck true when the caller has already checked access itself
     * @return true when the companion was spawned
     */
    public boolean spawn(Player owner, String archetype, boolean skipOwnerCheck) {
        if (owner == null || archetype == null) {
            return false;
        }
        if (!skipOwnerCheck && !plugin.mayUseYuiAndSandrone(owner.getUniqueId())) {
            denyCompanion(owner, archetype);
            return false;
        }
        String kind = archetype.toLowerCase(Locale.ROOT);
        CompanionKey key = new CompanionKey(owner.getUniqueId(), kind);
        despawn(owner, kind);

        float initialFormationYaw = owner.getLocation().getYaw();
        Location spot = spawnFormationSpot(owner.getLocation(), kind, initialFormationYaw);
        Mannequin npc = createAt(spot, kind, owner.getName());
        if (npc == null) {
            return false;
        }

        ownerToNpc.put(key, npc.getUniqueId());
        // Tell the owner that this companion really exists. The Skript scripts use this tag as
        // the authority for granting effects: the companion has to be on the world before the
        // owner may receive its buffs.
        owner.addScoreboardTag(activeTag(kind));
        startFollowing(key, owner, npc, initialFormationYaw);
        // The newer companions answer when they are called.
        dialogue.onCall(owner, kind, npc.getLocation());
        plugin.getLogger().info(() -> "Spawned " + kind + " mannequin for " + owner.getName());
        return true;
    }

    /**
     * Adds a plugin chunk ticket to the companion's chunk, so the chunk stays loaded while the
     * companion waits there.
     */
    private void holdChunk(Entity companion, FollowState state) {
        Location location = companion.getLocation();
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        try {
            companion.getWorld().getChunkAt(location).addPluginChunkTicket(plugin);
            state.heldChunkX = chunkX;
            state.heldChunkZ = chunkZ;
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not keep the companion's chunk loaded", ex);
        }
    }

    /**
     * Releases the plugin chunk ticket the companion was holding.
     */
    private void releaseChunk(Entity companion, FollowState state) {
        if (state.heldChunkX == Integer.MIN_VALUE) {
            return;
        }
        state.heldChunkX = Integer.MIN_VALUE;
        state.heldChunkZ = Integer.MIN_VALUE;
        try {
            companion.getWorld().getChunkAt(companion.getLocation())
                    .removePluginChunkTicket(plugin);
        } catch (RuntimeException ignored) {
            // The chunk may already be gone; there is no ticket left to release either way.
        }
    }

    /**
     * Moves the chunk ticket along with the companion when it walks into another chunk.
     *
     * <p>Only one chunk is held at a time: the old one is released and the new one is claimed,
     * so a companion that walks around does not pin every chunk it passes through.</p>
     */
    private void moveChunkTicket(Entity companion, FollowState state) {
        int chunkX = companion.getLocation().getBlockX() >> 4;
        int chunkZ = companion.getLocation().getBlockZ() >> 4;
        if (chunkX == state.heldChunkX && chunkZ == state.heldChunkZ) {
            return;
        }
        releaseChunk(companion, state);
        holdChunk(companion, state);
    }

    /**
     * The scoreboard tag marking that a player currently has this companion on the world.
     *
     * @param archetype "yui", "sandrone", or "eris"
     */
    static String activeTag(String archetype) {
        return "companion_active_" + archetype.toLowerCase(Locale.ROOT);
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
                // The companion is not written to the chunk data. A companion that was left
                // behind must not outlive the plugin: after a crash it would sit in the chunk
                // data, unowned and impossible to command. Its chunk is kept loaded by a plugin
                // chunk ticket instead, so the companion stays alive while the server runs and
                // is gone cleanly when the plugin shuts down.
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
        Key packKey = safePackSkinKey(archetype);
        PlayerTextures.SkinModel model = plugin.skinModelFor(archetype);

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

    /**
     * The resource-pack key for a companion's skin, or {@code null} when it cannot be built.
     *
     * <p>A pack key is a Minecraft resource location, so a file name that is not a legal one —
     * a space, for example — fails the moment the key is constructed. The mannequin is already
     * in the world by this point, and giving up on it half way would leave an untracked companion
     * behind, so the bad name is only logged here and the spawn carries on without its patch skin
     * instead.</p>
     */
    private Key safePackSkinKey(String archetype) {
        try {
            return plugin.packSkinKey(archetype);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Invalid resource-pack skin name for " + archetype
                    + " (" + ex.getMessage() + "): companion spawns without its patch.");
            return null;
        }
    }

    /** Builds the floating name shown above the companion. */
    private static Component nameOf(String archetype) {
        return switch (archetype) {
            case "yui" -> Component.text("Yui", NamedTextColor.LIGHT_PURPLE);
            case "eris" -> Component.text("Eris", NamedTextColor.RED);
            case "furina" -> Component.text("Furina", NamedTextColor.AQUA);
            case "ei" -> Component.text("Ei", NamedTextColor.GOLD);
            case "columbina" -> Component.text("Columbina", NamedTextColor.LIGHT_PURPLE);
            case "escoffier" -> Component.text("Escoffier", NamedTextColor.GRAY);
            default -> Component.text("Sandrone", NamedTextColor.AQUA);
        };
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

        // Eris is a standalone companion: she has no partner to pair up with, so she holds a
        // neutral spot behind the owner instead of claiming the left or right side.
        if ("eris".equalsIgnoreCase(state.archetype)) {
            double yaw = Math.toRadians(state.formationYaw);
            Location behind = new Location(world,
                    ownerLocation.getX() + Math.sin(yaw) * SIDE_FORMATION_OFFSET,
                    ownerLocation.getY(),
                    ownerLocation.getZ() - Math.cos(yaw) * SIDE_FORMATION_OFFSET);
            if (isFreeForCompanion(behind)) {
                state.formationSide = 0.0;
                state.formationIsFallback = false;
                return behind;
            }
            // Behind is blocked: fall back to either side rather than standing in the owner.
            Location left = sideSpotAt(ownerLocation, state.formationYaw, 1.0);
            if (isFreeForCompanion(left)) {
                state.formationSide = 1.0;
                state.formationIsFallback = true;
                return left;
            }
            Location right = sideSpotAt(ownerLocation, state.formationYaw, -1.0);
            if (isFreeForCompanion(right)) {
                state.formationSide = -1.0;
                state.formationIsFallback = true;
                return right;
            }
            state.formationSide = 0.0;
            return behind;
        }

        // The side this companion naturally owns: Yui left, Sandrone right, and the newer
        // companions take the remaining sides of the group.
        boolean naturalLeft = naturalSide(state.archetype) > 0.0;
        // Sticky: hold on to the current side while it still fits, so a cramped room does not
        // make the companion pace about. A side taken as a fallback does not become permanent —
        // the natural side is reclaimed as soon as there is room again.
        double held = state.formationSide;
        int rank = formationRank(state.archetype);
        if (held == 0.0 || state.formationIsFallback) {
            // (Re)claim the natural side when nothing is held or the hold was only a fallback.
            Location natural = sideSpotAt(ownerLocation, state.formationYaw,
                    naturalLeft ? 1.0 : -1.0, rank);
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
        candidates[0] = sideSpotAt(ownerLocation, state.formationYaw,
                wantLeft ? 1.0 : -1.0, rank);
        // ...then the opposite side...
        candidates[1] = sideSpotAt(ownerLocation, state.formationYaw,
                wantLeft ? -1.0 : 1.0, rank);
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
     * nearby is ignored, so the companion does not go on the offensive.</p>
     *
     * <p>The search is centred on the companion itself, never on the owner. A companion that was
     * told to wait defends its own surroundings: it does not leave the spot to rescue the owner,
     * which is what would otherwise drag it away and leave it standing where the last mob died.</p>
     *
     * <p>A waiting companion has a wider leash than a following one: it may step out to deal with
     * a hostile that comes inside the ground it holds, but it is never pulled towards the owner.
     * The leash is measured from the spot it was told to wait at, so the fight stays around that
     * spot instead of wandering off with the mob.</p>
     *
     * <p>The search radius is configurable (8 blocks by default), so the owner is never left
     * undefended for a distant fight.</p>
     *
     * @return the mob to engage, or null when there is nothing to fight
     */
    private LivingEntity findAttackTarget(Mannequin npc, Player owner, FollowState state) {
        Location npcLocation = npc.getLocation();
        double radius = plugin.attackSearchRadius();
        // A waiting companion watches a wider circle around its own spot, so a mob that walks
        // towards the spot is noticed before it arrives. The detection range is separate from
        // the idle wander radius: noticing a threat further out does not make the companion
        // wander further from its spot.
        if (state.mode == FollowMode.WAIT && state.waitSpot != null) {
            radius = plugin.waitThreatRadius() + WAIT_WANDER_RADIUS;
        }
        long currentTick = Bukkit.getCurrentTick();
        // A mob the companion has just let go is not picked up again for a moment, so the
        // companion can walk back before it is pulled out again. The pause is for that one
        // mob: another hostile that comes close is still fought, so the companion is never
        // standing next to a mob it refuses to touch.
        double radiusSquared = radius * radius;
        double ownerReach = plugin.attackSearchRadius() * 2.0;

        LivingEntity best = null;
        double bestDistance = radiusSquared;

        for (Entity entity : npc.getNearbyEntities(radius, radius, radius)) {
            if (!(entity instanceof LivingEntity living) || !isHostileMob(living)) {
                continue;
            }
            if (!living.isValid() || living.isDead()) {
                continue;
            }
            // The mob the companion just let go: it stays off the list for a moment.
            if (state.releasedTargetsUntil.containsKey(living.getUniqueId())) {
                continue;
            }
            // A mob that is actually coming for the owner or for a companion is a threat, in
            // every mode. This stays a requirement: it is what keeps the companion from
            // picking a fight with a mob that is merely walking past.
            boolean threatToParty = isThreatToParty(living, owner, npc);
            // In WAIT the companion also holds its ground: a hostile that walks into the
            // watched circle is a threat to the post, even if it has not picked a target yet.
            // Neutral hostiles are excluded from this, though — an Enderman that strolls past
            // is left alone, because it only strikes when provoked and it can turn on the owner.
            boolean territorial = false;
            if (state.mode == FollowMode.WAIT && state.waitSpot != null
                    && !NEUTRAL_HOSTILE_MOBS.contains(living.getType())) {
                double detectRadius = plugin.waitThreatRadius();
                territorial = living.getLocation().distanceSquared(state.waitSpot)
                        <= detectRadius * detectRadius;
            }
            if (!threatToParty && !territorial) {
                continue;
            }
            // A waiting companion is on a leash: it fights what comes inside the ground it
            // holds, but it does not chase a mob out of that ground. A mob that has already
            // pulled the companion away is not picked up again, so the companion does not
            // walk out, drop the fight at the leash, and turn straight back to the same mob.
            if (state.mode == FollowMode.WAIT && state.waitSpot != null
                    && !withinLeash(living, state.waitSpot, plugin.waitThreatRadius())) {
                continue;
            }
            // A companion does not chase a mob that has run out of its owner's reach. The
            // same rule is applied here as to the fight it is already in, so the companion
            // cannot ignore a mob that is right next to it and then chase it again: the mob
            // is out of reach either way, and picking it up would start the chase again
            // only to drop it once more.
            if (state.mode != FollowMode.WAIT
                    && isOutOfReach(living.getLocation().distanceSquared(owner.getLocation()),
                    ownerReach)) {
                continue;
            }
            double distance = living.getLocation().distanceSquared(npcLocation);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = living;
            }
        }
        return best;
    }

    /**
     * Whether the mob is still inside the ground a waiting companion holds.
     *
     * <p>The leash is measured from the spot the companion was told to wait at, not from the
     * companion itself. Measured from the companion, a mob that has already pulled it out of
     * its post would still count as a target, and the companion would walk out again, drop
     * the fight at the leash, and turn straight back to the same mob.</p>
     */
    private static boolean withinLeash(LivingEntity mob, Location waitSpot, double leash) {
        return mob.getLocation().distanceSquared(waitSpot) <= leash * leash;
    }

    /**
     * Whether the mob counts as hostile. Only the undead and the classic cave dwellers qualify;
     * animals, villagers and other peaceful creatures are never attacked.
     */
    static boolean isHostileMob(LivingEntity entity) {
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
     * <p>Yui marks skeletons, Sandrone marks creepers. Particles are spawned above each marked
     * hostile and therefore only appear when there is a line of sight.</p>
     *
     * <p>The search is centred on the companion, so a waiting companion only marks the threats
     * around the ground it is holding — not the ones besieging its owner somewhere else.</p>
     */
    private void updateDangerMarkers(Mannequin npc, Player owner, FollowState state,
                                    long currentTick) {
        if (!owner.isOnline()) {
            clearDangerMarkers(state);
            return;
        }
        // Only Yui and Sandrone keep watch for a dangerous mob. The four newer companions
        // have no marker duty of their own: without this they would all mark the same creeper
        // and warn about it, and the owner's chat would fill up with the same warning.
        boolean watches = "yui".equalsIgnoreCase(state.archetype)
                || "sandrone".equalsIgnoreCase(state.archetype);
        if (!watches) {
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
        Location centre = npc.getLocation();

        for (Entity entity : npc.getNearbyEntities(radius, radius, radius)) {
            if (!(entity instanceof LivingEntity hostile)
                    || hostile.getType() != wantedType
                    || !hostile.isValid() || hostile.isDead()) {
                continue;
            }
            if (!isThreatToParty(hostile, owner, npc)) {
                continue;
            }
            if (hostile.getLocation().distanceSquared(centre) > radiusSquared) {
                continue;
            }
            UUID hostileId = hostile.getUniqueId();
            foundNow.add(hostileId);
            if (!state.markedHostiles.contains(hostileId)) {
                state.markedHostiles.add(hostileId);
                sendMarkerAlert(owner, state, kind, hostileId, currentTick);
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

    /**
     * Sends a companion-name-prefixed alert about a newly spotted dangerous mob.
     *
     * <p>The warning is about the mob, not about the companion, so it is remembered per mob: a
     * second creeper is a genuinely new danger and is worth a word of its own, but the same
     * creeper is only warned about once. Without this bookkeeping a group of creepers would
     * produce one warning each, which is exactly the spam this guard exists to stop.</p>
     */
    private void sendMarkerAlert(Player owner, FollowState state, String kind, UUID hostileId,
                                 long currentTick) {
        if (!state.warnedHostiles.add(hostileId)) {
            // Already warned about this mob: it is still marked, but not announced again.
            return;
        }
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
                                             long currentTick, double heldReach) {
        if (state.attackTarget != null) {
            Entity existing = Bukkit.getEntity(state.attackTarget);
            // The grace check is skipped on the very first pick, because subtracting the
            // never-set sentinel from the tick would overflow to a negative number.
            boolean withinGrace = state.attackTargetTick == Long.MIN_VALUE
                    || currentTick - state.attackTargetTick <= ATTACK_TARGET_GRACE_TICKS;
            if (withinGrace && existing instanceof LivingEntity living
                    && living.isValid() && !living.isDead()) {
                // A mob that has drawn the companion away from its owner is let go. The
                // companion is not a stray that follows a mob across the map: it belongs to
                // the owner, and a chase that leads out of reach leaves it stuck far away,
                // unable to get back. A waiting companion is on its own leash instead.
                //
                // The check is not held back by the grace period. The grace period is there
                // to stop the companion from changing its mind every tick; a mob that has
                // run out of reach is not a change of mind but the end of the chase. Waiting
                // three seconds for it meant the companion kept walking after the mob and
                // only then let it go.
                if (state.mode != FollowMode.WAIT && isOutOfReach(owner, living)) {
                    state.attackTarget = null;
                    state.attackTargetTick = Long.MIN_VALUE;
                    // The mob is left alone for a moment, so the companion does not pick the
                    // very same fight up again on the next tick. Without this the companion
                    // ignored the mob and chased it again, over and over, while the mob stood
                    // right there in front of it.
                    state.releasedTargetsUntil.put(living.getUniqueId(),
                            currentTick + LEASH_BREAK_TICKS);
                    return null;
                }
                // A mob that is running away is still the one the companion is fighting. It
                // is held on to as long as it stays within reach of the companion, which is
                // further than the circle a new mob is spotted in. Looking for a new mob
                // instead would have the companion turn back the moment the runner stepped
                // out of the search radius, and pick the same runner up again as soon as it
                // came back — a chase that stopped and started over and over.
                double heldDistance = living.getLocation().distanceSquared(npc.getLocation());
                if (stillHeld(heldDistance, heldReach)) {
                    return living;
                }
                // The runner has got too far away to be worth chasing any more.
                state.attackTarget = null;
                state.attackTargetTick = Long.MIN_VALUE;
                state.releasedTargetsUntil.put(living.getUniqueId(),
                        currentTick + LEASH_BREAK_TICKS);
                return null;
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
     * Whether the mob has drawn the companion too far away from its owner.
     *
     * <p>The distance is measured from the owner, not from the companion. A companion that
     * chases a mob which keeps running walks away from the owner with it, and once it is
     * out of reach there is no route back — the companion is stuck wherever the chase ended.
     * Letting the mob go at that point and walking back to the owner is what keeps the
     * companion where it belongs.</p>
     */
    private boolean isOutOfReach(Player owner, LivingEntity mob) {
        return isOutOfReach(mob.getLocation().distanceSquared(owner.getLocation()),
                plugin.attackSearchRadius() * 2.0);
    }

    /**
     * Whether the squared distance between a mob and the owner is beyond the reach a
     * companion is willing to follow.
     *
     * <p>Kept separate from the world so the rule can be checked on its own.</p>
     *
     * @param squaredDistance the squared distance between the mob and the owner
     * @param reach           the squared distance the companion is willing to follow
     * @return true when the mob is too far away to be chased
     */
    static boolean isOutOfReach(double squaredDistance, double reach) {
        return squaredDistance > reach * reach;
    }

    /**
     * Whether a mob the companion is already fighting is still worth holding on to.
     *
     * <p>A mob that is running away is still the one the companion is fighting, so it is
     * followed as long as it stays within reach of the companion — further out than the
     * circle a new mob is spotted in. Looking for a new mob instead would have the
     * companion turn back the moment the runner stepped out of the search radius, and pick
     * the same runner up again as soon as it came back.</p>
     *
     * @param squaredDistance the squared distance between the mob and the companion
     * @param heldReach       the squared distance the companion will follow the mob to
     * @return true when the mob is still being fought
     */
    static boolean stillHeld(double squaredDistance, double heldReach) {
        return squaredDistance <= heldReach * heldReach;
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
        return approachSpot(npc, target, ATTACK_APPROACH_DISTANCE);
    }

    /**
     * A spot a given distance short of the target, on the side the companion is already on.
     *
     * <p>Stopping short rather than beyond the target is what makes the companion walk up to a
     * mob instead of having to walk around it. When the distance itself cannot be stood on — the
     * mob may be on a ledge — the mob's own block is used, which is always standable.</p>
     *
     * @param npc      the companion
     * @param target   the mob being fought
     * @param distance how far short of the mob to stand, in blocks
     */
    private static Location approachSpot(Mannequin npc, LivingEntity target, double distance) {
        Location targetLocation = target.getLocation();
        Location npcLocation = npc.getLocation();
        double dx = targetLocation.getX() - npcLocation.getX();
        double dz = targetLocation.getZ() - npcLocation.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 0.05) {
            // Overlapping the target: step aside so the swing has room to happen.
            return targetLocation.clone().add(distance, 0, 0);
        }
        // Stop short of the target rather than beyond it, so the companion walks up to the mob
        // instead of having to walk around it.
        double scale = distance / length;
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
     * The spot the companion walks to while it is fighting.
     *
     * <p>A melee companion walks up to the mob and stops just short of it. A companion that
     * throws — Columbina and Escoffier — keeps its distance instead: it steps back towards the
     * far side of the mob until it is about {@link #RANGED_KEEP_DISTANCE} blocks away, so the
     * trident has room to fly and the companion is not standing in the mob's face.</p>
     *
     * <p>A thrower also has to be able to <i>see</i> the mob. Standing eight blocks away on the
     * wrong side of a wall does not help: the trident would hit the wall. So the spot is one
     * the companion can actually throw from, and it looks for one around the mob — by a
     * doorway or a window, if the building has one.</p>
     *
     * @param npc    the companion
     * @param state  the companion's state, which says which weapon it carries
     * @param target the mob being fought
     */
    private static Location combatSpot(Mannequin npc, FollowState state, LivingEntity target,
                                       long currentTick) {
        CompanionWeapons.Weapon weapon = CompanionWeapons.of(state.archetype);
        if (weapon == null || !weapon.isRanged()) {
            return meleeSpot(npc, target);
        }
        Location targetLocation = target.getLocation();
        Location npcLocation = npc.getLocation();
        double dx = targetLocation.getX() - npcLocation.getX();
        double dz = targetLocation.getZ() - npcLocation.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        // The distance the companion wants to keep: far enough for the trident to fly, close
        // enough that the shot does not take forever to arrive.
        double wanted = RANGED_KEEP_DISTANCE;
        if (length < 0.05) {
            // Standing on top of the mob: step back so the throw has somewhere to come from.
            return targetLocation.clone().add(wanted, 0, 0);
        }
        // Where the companion is standing may already be a good place to throw from. If it is,
        // there is no reason to walk anywhere.
        if (CompanionLineOfSight.canSee(npc, target)) {
            state.firingSpot = null;
            state.firingSpotTarget = null;
            if (length >= wanted) {
                return approachSpot(npc, target, wanted);
            }
            return currentSpot(npc, target, length, wanted);
        }
        // The line is blocked from where the companion stands, so it has to look for a spot it
        // can throw from: a doorway, a window, the far side of the building.
        Location firingSpot = firingSpot(npc, state, target, currentTick);
        if (firingSpot != null) {
            return firingSpot;
        }
        // No spot with a clear line was found. Keeping the old behaviour means the companion
        // still holds its distance and waits for the mob to come into view.
        if (length >= wanted) {
            return approachSpot(npc, target, wanted);
        }
        return currentSpot(npc, target, length, wanted);
    }

    /**
     * The spot the companion last found to throw from, looked up again when it is due.
     *
     * <p>The search is expensive: every candidate spot has to be checked for a clear line and
     * for a route to it. Doing that on every tick would cost far more than it is worth, so
     * the result is kept for a while. It is thrown away as soon as the target changes, or
     * when the companion has moved away from the spot, since from there the shot may be
     * blocked again.</p>
     */
    private static Location firingSpot(Mannequin npc, FollowState state, LivingEntity target,
                                       long currentTick) {
        UUID targetId = target.getUniqueId();
        boolean staleTarget = !targetId.equals(state.firingSpotTarget);
        boolean expired = state.firingSpotTick == Long.MIN_VALUE
                || currentTick - state.firingSpotTick >= FIRING_SPOT_INTERVAL_TICKS;
        if (!staleTarget && !expired && state.firingSpot != null) {
            // The spot is only still good if the companion can see the mob from there.
            Location eye = state.firingSpot.clone().add(0.0, EYE_HEIGHT, 0.0);
            if (CompanionLineOfSight.canSeeFrom(npc.getWorld(), eye, target)) {
                return state.firingSpot;
            }
            state.firingSpot = null;
        }
        state.firingSpotTick = currentTick;
        state.firingSpotTarget = targetId;
        state.firingSpot = findFiringSpot(npc, target);
        return state.firingSpot;
    }

    /**
     * The spot the companion stands on while it backs away from the mob.
     *
     * <p>It backs away along the same line, so the companion never has to walk around the mob
     * to reach its throwing distance.</p>
     */
    private static Location currentSpot(Mannequin npc, LivingEntity target, double length,
                                        double wanted) {
        Location targetLocation = target.getLocation();
        Location npcLocation = npc.getLocation();
        double dx = targetLocation.getX() - npcLocation.getX();
        double dz = targetLocation.getZ() - npcLocation.getZ();
        double back = wanted - length;
        Location spot = new Location(targetLocation.getWorld(),
                npcLocation.getX() - dx / length * back,
                npcLocation.getY(),
                npcLocation.getZ() - dz / length * back);
        if (!isFreeForCompanion(spot)) {
            return targetLocation.clone();
        }
        return spot;
    }

    /**
     * Looks for a spot the companion can throw the trident from.
     *
     * <p>The spots are tried in rings around the mob. A spot that is close to the mob is
     * preferred, because the trident has less to travel and less to miss; a spot further away
     * is only used when nothing closer has a clear line.</p>
     *
     * <p>Every spot has to be free to stand on and to have an unobstructed line to the mob, so
     * the companion never walks into geometry and never picks a spot it still cannot throw
     * from.</p>
     *
     * @return the spot to walk to, or {@code null} when no spot with a clear line was found
     */
    private static Location findFiringSpot(Mannequin npc, LivingEntity target) {
        World world = npc.getWorld();
        Location targetLocation = target.getLocation();
        Location npcLocation = npc.getLocation();
        // The rings around the mob, from the closest outwards. The first ring is the mob's
        // own doorstep, the last is as far as the trident reaches.
        double[] radii = {2.0, 3.5, 5.0, 7.0, RANGED_KEEP_DISTANCE, RANGED_THROW_RANGE};
        // Where the companion is now, so the search starts from its own side and works
        // outwards: the nearest clear line is usually just around a corner.
        double baseAngle = Math.atan2(npcLocation.getZ() - targetLocation.getZ(),
                npcLocation.getX() - targetLocation.getX());
        // The line of sight is the cheap check and the route is the expensive one, so the
        // order matters: a spot that cannot see the mob is not worth a route check.
        List<Location> candidates = new ArrayList<>();
        for (double radius : radii) {
            if (radius > RANGED_THROW_RANGE) {
                break;
            }
            // Eight directions per ring, each tried a little above and below the mob's own
            // level, since a window is often a little higher or lower than the ground.
            for (int step = 0; step < 16; step++) {
                double angle = baseAngle + Math.PI * 2.0 * step / 16.0;
                double offsetX = Math.cos(angle) * radius;
                double offsetZ = Math.sin(angle) * radius;
                for (double height : new double[]{0.0, 1.0, -1.0}) {
                    Location spot = new Location(world,
                            targetLocation.getX() + offsetX,
                            targetLocation.getY() + height,
                            targetLocation.getZ() + offsetZ);
                    if (!CompanionLineOfSight.standable(spot)) {
                        continue;
                    }
                    // The eye is a little above the feet.
                    Location eye = spot.clone().add(0.0, EYE_HEIGHT, 0.0);
                    if (CompanionLineOfSight.canSeeFrom(world, eye, target)) {
                        candidates.add(spot);
                    }
                }
            }
            // The rings are tried from the closest outwards, and a spot close to the mob is
            // the better one: the trident has less to travel and less to miss. So the first
            // ring that offers a usable spot is enough.
            for (Location candidate : candidates) {
                // The spot has to be one the companion can walk to, not one it has to climb
                // through a wall to reach.
                if (hasWalkableRoute(world, npcLocation, candidate)) {
                    return candidate;
                }
            }
            if (!candidates.isEmpty()) {
                // The ring had spots with a clear line, but none of them can be walked to.
                // A wider ring will not be better on that count, so the search stops here.
                return null;
            }
        }
        return null;
    }

    /**
     * Releases a mob that was coming for a companion, so it looks for the owner again.
     *
     * <p>A mannequin is immortal, so a mob that fixed its target on the companion would keep
     * hitting it forever instead of coming back to the owner it came for.</p>
     */
    private static void releaseAggro(LivingEntity target, Player owner) {
        if (target instanceof org.bukkit.entity.Mob hostile
                && hostile.getTarget() instanceof Mannequin companion
                && companion.getScoreboardTags().contains(TAG)) {
            hostile.setTarget(owner);
        }
    }

    /**
     * Hands a mob hit by a companion's projectile back to the owner.
     *
     * <p>Called from the projectile listener: the projectile was fired by the owner, so the mob
     * should answer to him rather than to the immortal companion that threw it.</p>
     */
    public void releaseProjectileAggro(LivingEntity hit, org.bukkit.entity.Projectile projectile) {
        org.bukkit.projectiles.ProjectileSource shooter = projectile.getShooter();
        if (shooter instanceof Player owner) {
            releaseAggro(hit, owner);
        }
    }

    /**
     * Strikes at the target: the companion's arm animates like a player's, and the mob takes the
     * damage. A mob that had the companion as its target is released first, so it stops hitting
     * an immortal companion and looks for the owner again.
     *
     * <p>How the strike looks and where it comes from depends on the companion's weapon. A
     * sword is swung at the mob, a spear is jabbed forward, and a trident is thrown from a
     * distance.</p>
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

        CompanionWeapons.Weapon weapon = CompanionWeapons.of(state.archetype);
        if (weapon == null) {
            weapon = new CompanionWeapons.Weapon(state.archetype, Material.IRON_SWORD,
                    plugin.attackDamage(), CompanionWeapons.Style.SWING);
        }

        if (weapon.isRanged()) {
            if (!throwAtTarget(npc, owner, target, weapon)) {
                // The shot is blocked. The companion has to move somewhere it can see the
                // mob from, so the swing is not spent and the target is left alone.
                return;
            }
            state.lastSwingTick = currentTick;
            return;
        }
        state.lastSwingTick = currentTick;

        if (weapon.style() == CompanionWeapons.Style.JAB) {
            // A jab is a thrust, not a sweep: the arm goes straight forward at the mob. Paper's
            // STAB swing animation is exactly that motion, so the spear reads as a poke.
            npc.swingHand(org.bukkit.inventory.EquipmentSlot.HAND,
                    io.papermc.paper.datacomponent.item.SwingAnimation.swingAnimation()
                            .type(io.papermc.paper.datacomponent.item.SwingAnimation.Animation.STAB)
                            .duration(JAB_ANIMATION_TICKS)
                            .build());
        } else {
            // The swing animation is a player's own, so the hit reads like a real melee attack.
            npc.swingMainHand();
        }
        // Take the mob's attention off the companion: an immortal target would otherwise keep
        // the mob busy forever instead of letting it return to the owner.
        releaseAggro(target, owner);
        target.damage(weapon.damage(), npc);
    }

    /**
     * Throws the companion's trident at the target.
     *
     * <p>The companion keeps its distance: the trident is launched at the mob and the damage
     * comes from the projectile, not from a swing.</p>
     *
     * <p>A trident does not stop at a wall, so the shot is only taken when the companion can
     * actually see the mob. Throwing at a mob behind a wall sends the trident into the wall
     * and the mob is never touched — and damaging it directly from behind that wall would be
     * just as wrong, since the companion clearly cannot reach it. When the line is blocked the
     * companion looks for a place to throw from instead.</p>
     *
     * @return true when the trident was thrown
     */
    private boolean throwAtTarget(Mannequin npc, Player owner, LivingEntity target,
                                  CompanionWeapons.Weapon weapon) {
        if (!CompanionLineOfSight.canSee(npc, target)) {
            // Something stands in the way. The companion has to find a spot with a clear line
            // before it can throw; it does not waste its weapon on the wall.
            return false;
        }
        releaseAggro(target, owner);
        org.bukkit.entity.Trident thrown = RangedTridentAttack.launch(
                npc, owner, target, RANGED_THROW_RANGE, RANGED_THROW_SPEED, weapon.damage(),
                weapon.archetype());
        if (thrown == null) {
            // The shot was not possible — the target is out of range or in another world. Fall
            // back to a plain strike so the companion is never left standing there.
            target.damage(weapon.damage(), npc);
            return true;
        }
        return true;
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
        return sideSpotAt(ownerLocation, formationYaw, side, 0);
    }

    /**
     * The spot a companion stands on, at a given rank down the line on her side.
     *
     * <p>Three companions share each side of the owner. Without a rank they would all aim at
     * the same block and end up standing inside each other — which is exactly what a single
     * {@code /istri} used to produce. The first companion holds the owner's shoulder, and each
     * one after her steps back along the owner's facing, so the side reads as a short line
     * rather than a pile.</p>
     *
     * @param rank 0 for the companion nearest the owner, 1 for the next, and so on
     */
    private static Location sideSpotAt(Location ownerLocation, float formationYaw, double side,
                                       int rank) {
        double angle = Math.toRadians(formationYaw) + side * Math.PI / 2.0;
        double x = ownerLocation.getX() - Math.sin(angle) * SIDE_FORMATION_OFFSET;
        double z = ownerLocation.getZ() + Math.cos(angle) * SIDE_FORMATION_OFFSET;
        if (rank > 0) {
            // Step backwards along the owner's facing: +sin for x, -cos for z.
            double yaw = Math.toRadians(formationYaw);
            x += Math.sin(yaw) * rank * FORMATION_RANK_SPACING;
            z -= Math.cos(yaw) * rank * FORMATION_RANK_SPACING;
        }
        return new Location(ownerLocation.getWorld(), x, ownerLocation.getY(), z);
    }

    /**
     * How far down its side a companion stands.
     *
     * <p>The three on the left are Yui, Furina, Columbina; the three on the right are Sandrone,
     * Ei, Escoffier. Each group runs from the owner's shoulder outwards, so a companion keeps
     * the same place in the line whether she is called on her own or as part of {@code /istri}.</p>
     *
     * @return 0 for the companion nearest the owner, 1 for the next, 2 for the last
     */
    private static int formationRank(String archetype) {
        return switch (archetype.toLowerCase(Locale.ROOT)) {
            case "yui", "sandrone" -> 0;
            case "furina", "ei" -> 1;
            case "columbina", "escoffier" -> 2;
            default -> 0;
        };
    }

    /**
     * Where a companion appears when summoned: its side of the formation, with fallbacks.
     *
     * <p>At spawn the formation is anchored to the owner's facing at that moment. Later camera
     * movement does not change it.</p>
     */
    private static Location spawnFormationSpot(Location ownerLocation, String archetype, float formationYaw) {
        boolean wantLeft = naturalSide(archetype) > 0.0;
        int rank = formationRank(archetype);
        Location own = sideSpotAt(ownerLocation, formationYaw, wantLeft ? 1.0 : -1.0, rank);
        if (isFreeForCompanion(own)) {
            return own;
        }
        Location other = sideSpotAt(ownerLocation, formationYaw, wantLeft ? -1.0 : 1.0, rank);
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
        // Keep the companion's chunk loaded for as long as it exists. Without this the chunk is
        // unloaded as soon as the owner walks away, and the companion is dropped from the world
        // with it: the owner can no longer command it and it never comes back.
        holdChunk(npc, state);
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!owner.isOnline() || npc.isDead() || !npc.isValid()) {
                cancel(key);
                return;
            }
            // The list of mobs that were let go is cleaned here, so it cannot grow while the
            // companion has nothing to fight.
            long now = Bukkit.getCurrentTick();
            state.releasedTargetsUntil.entrySet().removeIf(entry -> now >= entry.getValue());
            if (!npc.getWorld().equals(owner.getWorld())) {
                // A waiting companion stays where it was left, even in another world: pulling it
                // back to the owner would undo the whole point of the command.
                if (state.mode != FollowMode.WAIT) {
                    rescueTeleport(npc, owner, owner.getLocation(), state);
                }
                return;
            }
            // A companion that walked into another chunk needs its ticket moved along with it.
            moveChunkTicket(npc, state);

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
            boolean waitMode = state.mode == FollowMode.WAIT;
            double standStill = closeMode
                    ? plugin.closeDistance()
                    : plugin.comfortDistance();

            // The companion heads for its own spot in the formation, not for the owner directly.
            // In CLOSE mode formationSpot picks a point within the owner's reachable room.
            long currentTick = Bukkit.getCurrentTick();

            // Waiting: the companion stays where it was told to wait and only wanders a little
            // around that spot. It never aims for the owner, so the owner's own position does not
            // matter here at all — and the formation spot is not even computed, because that
            // would only churn the route state for a target nobody is heading to.
            Location targetSpot = waitMode
                    ? waitSpot(npc, state, currentTick)
                    : formationSpot(npc, owner, state, closeMode);

            // Combat overrides the formation while it is active: the companion walks to the mob
            // instead of holding its side, and looks at what it is fighting.
            //
            // Defending is automatic: there is no toggle any more. A companion always reacts to
            // a hostile mob that is coming for its owner or for the companion itself. The search
            // is centred on the companion, so a waiting companion defends the ground it was told
            // to hold and never leaves to chase the owner across the map.
            // A mob the companion is already fighting is held on to as long as it stays
            // within reach of the companion. That reach is wider than the circle a new mob
            // is spotted in, so a mob that runs is followed instead of being dropped and
            // picked up again. A companion that throws keeps it for as long as the throw
            // could still reach.
            CompanionWeapons.Weapon heldWeapon = CompanionWeapons.of(state.archetype);
            double heldReach = heldWeapon != null && heldWeapon.isRanged()
                    ? RANGED_THROW_RANGE
                    : plugin.attackSearchRadius() * 2.0;
            LivingEntity attackTarget = resolveAttackTarget(npc, owner, state, currentTick,
                    heldReach);
            if (attackTarget != null) {
                targetSpot = combatSpot(npc, state, attackTarget, currentTick);
            }
            // A waiting companion is on a leash: it fights what comes inside the ground it holds,
            // but it does not follow a mob out of that ground. Once the mob pulls it too far from
            // its spot, the companion drops the fight and walks back to where it was told to wait.
            //
            // The fight is dropped when the companion itself has been pulled out of its post,
            // not when the mob has. A mob that stands just outside the leash is still worth
            // fighting from the edge of the post; a mob that has dragged the companion across
            // the map is not. Dropping it there is what made the companion walk out, let the
            // mob go, walk back, and be picked up by the same mob again.
            if (attackTarget != null && waitMode && state.waitSpot != null) {
                double leash = plugin.waitThreatRadius();
                if (npcLocation.distanceSquared(state.waitSpot) > leash * leash) {
                    // The mob is let go for a moment, so the companion can walk back to its post
                    // without being pulled out again by the same fight. Only that mob is
                    // paused: another hostile that comes close is still fought.
                    state.releasedTargetsUntil.put(attackTarget.getUniqueId(),
                            currentTick + LEASH_BREAK_TICKS);
                    attackTarget = null;
                    state.attackTarget = null;
                    state.attackTargetTick = Long.MIN_VALUE;
                    targetSpot = waitSpot(npc, state, currentTick);
                }
            }
            // The weapon is only drawn while there is something to fight, and put away again once
            // the fight is over.
            updateWeapon(npc, state.archetype, attackTarget != null);
            // Defensive markers show which hostile mobs are being watched.
            updateDangerMarkers(npc, owner, state, currentTick);
            // Eris is a support fighter: while she is fighting next to her owner she keeps
            // refreshing Speed on them, and she talks in her own voice.
            updateErisBehaviour(owner, state, attackTarget != null, attackTarget == null,
                    currentTick);
            // The newer companions each have a support ability of their own, keyed to what is
            // happening to the owner rather than to the fight in front of them.
            supportAbilities.tick(owner, state.archetype, npc);
            dialogue.onFollowUp(owner, state.archetype, npc.getLocation(),
                    attackTarget != null, currentTick);
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
            // The weapon in hand decides how the fight is fought. A melee companion has to get
            // within arm's length of the mob before it can strike; a companion that throws
            // strikes from further away, so its own reach is the throw's range.
            CompanionWeapons.Weapon weapon = CompanionWeapons.of(state.archetype);
            boolean ranged = weapon != null && weapon.isRanged();
            // A companion that throws its weapon settles at the distance it wants to keep, not
            // at the melee stop distance: it fights from there.
            double arriveAt = attackTarget == null
                    ? standStill
                    : (ranged ? RANGED_KEEP_DISTANCE : ATTACK_STOP_DISTANCE);

            // Emergency rescue on a vertical separation. When the owner stands on safe ground and
            // the companion is far below, waiting for the usual 24-block radius is wrong: the
            // owner can be directly above it while it lies at the bottom of a ravine, so their
            // straight-line distance stays small. A companion that is still rising — falling is
            // over and it swims upward — is left alone, so a swim towards the owner is not
            // interrupted. A waiting companion is not rescued either: it was told to stay where
            // it is, so being far from the owner is exactly what was asked for.
            if (!waitMode
                    && ownerLocation.getY() - npcLocation.getY() >= EMERGENCY_FALL_DISTANCE
                    && npc.getVelocity().getY() <= 0.01) {
                if (rescueTeleport(npc, owner, ownerLocation, state)) {
                    return;
                }
                // The rescue could not find a landing spot. Standing still would leave the
                // companion stuck at the bottom of the ravine for good, so it carries on with
                // what it was doing instead of giving up here.
            }

            // Emergency teleport. The threshold and the landing spot are mode-specific:
            // FOLLOW catches up to the companion's own formation side, CLOSE lands in the
            // owner's reachable room. Both use their own configured distance. A waiting
            // companion never needs catching up, so it is left alone entirely.
            if (!waitMode && distance > emergencyTeleportDistance(state.mode)) {
                if (rescueTeleport(npc, owner, ownerLocation, state)) {
                    return;
                }
                // No landing spot was free. The companion goes on with the fight it was in
                // rather than standing still: it is out of the owner's reach either way, and
                // a companion that keeps fighting is far better than one that is stuck.
            }

            // While fighting, two independent distances apply. Resting is decided by the closer
            // stop distance, but a swing is allowed at the full vanilla reach — otherwise a
            // companion settled a whisker outside reach would stand there holding its sword and
            // never strike. Outside combat the normal formation distance decides.
            boolean inReach = attackTarget != null
                    && combatDistance <= (ranged ? RANGED_THROW_RANGE : ATTACK_REACH);
            if (inReach) {
                // In reach: face the mob and strike on cooldown. The head stays on the target
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

            // A waiting companion never "arrives": it is not heading for the owner, so the
            // owner's distance says nothing about whether it should keep walking. Only a
            // fighting companion stops when it reaches its target.
            boolean arrived = attackTarget == null
                    ? !waitMode && targetDistance <= standStill
                    : combatDistance <= arriveAt;
            if (arrived) {
                org.bukkit.util.Vector velocity = npc.getVelocity();
                npc.setVelocity(new org.bukkit.util.Vector(velocity.getX() * 0.35, velocity.getY(), velocity.getZ() * 0.35));
                if (attackTarget == null) {
                    // A waiting companion keeps watching its owner even though it stays put.
                    applyGaze(npc, owner, state, state.lastWalkingYaw, true, true);
                } else if (inReach) {
                    // Standing its ground within reach: this is where the strike happens.
                    swingAtTarget(npc, owner, attackTarget, state, currentTick);
                }
                // A companion that has arrived still has to shut the door it just walked
                // through. The door it is standing in stays open, but a door further back
                // along the route is closed now that it is no longer on it.
                closePassedDoors(npcLocation, state);
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

            // Combat may route down onto a hostile up to three blocks below. Following keeps
            // the strict one-block descent so it never walks off a ravine while chasing.
            boolean combatDrop = attackTarget != null;
            // When the owner stands lower down, the companion comes down after him instead of
            // giving up: the descent is still checked for a clear shaft and a solid floor.
            boolean ownerBelow = ownerLocation.getY() < npcLocation.getY() - 1.0;

            if (needsReplan) {
                state.lastAttemptTick = currentTick;
                // Parkour is always allowed: a player clears a three-block gap with a jump, and
                // the companion should be able to follow across the same ravine.
                state.path = PathPlanner.findPath(npc.getWorld(), npcLocation, targetSpot,
                        PATH_RADIUS, combatDrop, ownerBelow, true);
                state.waypointIndex = 0;
                clearParkourState(state);
                state.plannedGoal = targetSpot.clone();
                state.lastPlanTick = currentTick;
            }

            // Open the doors on the route and close the ones already passed. This runs after the
            // route has been settled, so the doors it works on are the ones on the route the
            // companion is actually walking now, not the route of the previous tick.
            double currentSpeed = Math.sqrt(npc.getVelocity().getX() * npc.getVelocity().getX()
                    + npc.getVelocity().getZ() * npc.getVelocity().getZ());
            boolean moving = currentSpeed > STILL_SPEED;
            updateDoors(npcLocation, state, moving, targetDx, targetDz);

            // If no A* route exists, do not steer straight through a wall towards the target.
            // Stay still and retry planning shortly; only a mode-specific distance rescue may move
            // the companion by teleporting.
            if (state.path.isEmpty()) {
                // No walkable route (commonly because the companion fell into a ravine while its
                // owner stayed above). Let it retry briefly to account for a transient path update,
                // then use the mode's safe landing target rather than waiting until the owner is
                // more than 24 blocks away. Do not rescue a companion that is currently swimming.
                // A waiting companion is never rescued: it was told to stay where it is, so
                // pulling it back to the owner would defeat the command.
                if (state.noPathSinceTick == Long.MIN_VALUE) {
                    state.noPathSinceTick = currentTick;
                }
                if (!waitMode && !npc.isInWater()
                        && currentTick - state.noPathSinceTick >= NO_PATH_RESCUE_TICKS) {
                    if (rescueTeleport(npc, owner, ownerLocation, state)) {
                        return;
                    }
                    // No landing spot was free for the rescue. The companion is out of reach
                    // anyway, so it goes on with the fight it was in instead of standing still
                    // for good.
                }
                // There is no route to the spot the companion was aiming for. That spot is
                // usually the mob it is chasing, and the mob may have drawn it somewhere it
                // cannot get back from. Rather than standing still for ever, the companion
                // tries to make its way back towards the owner: a route to the owner exists
                // far more often than a route to a fleeing mob, and it puts the companion
                // back in reach instead of leaving it wedged where the fight left it.
                if (attackTarget != null && !waitMode) {
                    Location backToOwner = formationSpot(npc, owner, state, closeMode);
                    if (backToOwner != null && !backToOwner.equals(targetSpot)
                            && hasWalkableRoute(npc.getWorld(), npcLocation, backToOwner)) {
                        state.path = PathPlanner.findPath(npc.getWorld(), npcLocation, backToOwner,
                                PATH_RADIUS, combatDrop, ownerBelow, true);
                        state.waypointIndex = 0;
                        clearParkourState(state);
                        state.plannedGoal = backToOwner.clone();
                        state.lastPlanTick = currentTick;
                    }
                }
                if (state.path.isEmpty()) {
                    org.bukkit.util.Vector velocity = npc.getVelocity();
                    npc.setVelocity(new org.bukkit.util.Vector(velocity.getX() * 0.35, velocity.getY(),
                            velocity.getZ() * 0.35));
                    applyGaze(npc, owner, state, state.lastWalkingYaw, true, true);
                    return;
                }
            }
            // A route exists again, so the rescue timer is cleared.
            state.noPathSinceTick = Long.MIN_VALUE;

            // Advance past waypoints the companion has already reached.
            org.bukkit.util.Vector waypoint;
            while (state.waypointIndex < state.path.size()) {
                // Never consume a waypoint while airborne over a jump: the target is still the
                // block being flown towards, and advancing past it mid-flight would drop the
                // steering that keeps the companion from falling into the gap.
                if (currentTick < state.parkourUntilTick && !npc.isOnGround()) {
                    break;
                }
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
                clearParkourState(state);
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
            adoptOpenDoors(npc.getWorld(), state, npcLocation);

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
                clearParkourState(state);
                state.lastPlanTick = Long.MIN_VALUE;
                state.lastProgressPosition = null;
                org.bukkit.util.Vector velocity = npc.getVelocity();
                npc.setVelocity(new org.bukkit.util.Vector(velocity.getX() * 0.2, velocity.getY(), velocity.getZ() * 0.2));
                return;
            }

            double speed = horizontalDistance > 5.0 ? 0.24 : 0.14;
            if (attackTarget != null) {
                // Sprint into melee range 1.5x faster, but only while actually chasing a hostile.
                speed *= 1.5;
            } else if (waitMode) {
                // A waiting companion strolls rather than hurries; it is not going anywhere.
                speed = Math.min(speed, WAIT_WANDER_SPEED);
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
                double vy = npc.getVelocity().getY();
                boolean onGround = npc.isOnGround();
                if (onGround) {
                    // The jump has landed: discard every target before considering a new jump.
                    clearParkourState(state);
                }
                // Parkour is checked first and takes priority over the ordinary step-up jump.
                // This also prevents a one-block higher waypoint from being consumed in midair.
                boolean gapAhead = onGround && isGapAhead(npc, npcLocation, wx, wz);
                if (gapAhead) {
                    // Keep horizontal control through the fall so the companion reaches the
                    // selected supported lower landing instead of running past it.
                    PathPlanner.BlockPos dropLanding = findParkourDropLanding(
                            npc.getWorld(), npcLocation, state.path, state.waypointIndex);
                    clearParkourState(state);
                    state.parkourUntilTick = currentTick + (dropLanding != null
                            ? PARKOUR_DROP_AIR_TICKS : PARKOUR_AIR_TICKS);
                    PathPlanner.BlockPos ledge = dropLanding == null
                            ? findParkourLedge(npc.getWorld(), npcLocation, wx, wz) : null;
                    state.parkourDropLanding = dropLanding;
                    state.parkourDrop = dropLanding != null;
                    state.parkourLedge = ledge;
                    state.parkourAssisted = false;
                    if (dropLanding != null) {
                        state.parkourClimb = false;
                        double landingDistance = distanceToLedge(npcLocation, dropLanding);
                        state.parkourDropSpeed = Math.min(PARKOUR_DROP_MAX_SPEED,
                                Math.max(PARKOUR_DROP_MIN_SPEED,
                                        landingDistance / PARKOUR_DROP_TARGET_TICKS));
                        vy = PARKOUR_JUMP_VELOCITY;
                        speed = state.parkourDropSpeed;
                    } else if (ledge != null) {
                        state.parkourClimb = true;
                        double travel = Math.max(0.3, distanceToLedge(npcLocation, ledge));
                        state.parkourClimbSpeed = Math.min(PARKOUR_CLIMB_MAX_SPEED,
                                Math.max(PARKOUR_CLIMB_MIN_SPEED,
                                        travel / PARKOUR_CLIMB_TARGET_TICKS));
                        vy = PARKOUR_CLIMB_JUMP_VELOCITY;
                        speed = state.parkourClimbSpeed;
                    } else {
                        state.parkourClimb = false;
                        vy = PARKOUR_JUMP_VELOCITY;
                        speed = PARKOUR_CROSS_SPEED;
                    }
                    vx = (wx / waypointDistance) * speed;
                    velocityZ = (wz / waypointDistance) * speed;
                } else if (onGround && wy > STEP_UP_JUMP_HEIGHT && waypointDistance <= PARKOUR_STEP_UP_REACH) {
                    // Ordinary one-block step-up. The threshold is well above a carpet or a slab,
                    // which the companion walks up onto without jumping: only a real step needs
                    // a jump.
                    vy = 0.42;
                }
                // Maintain jump steering until landing, then let the next waypoint take over.
                if (currentTick < state.parkourUntilTick && !onGround) {
                    if (state.parkourClimb && state.parkourLedge != null) {
                        double lx = state.parkourLedge.x() + 0.5 - npcLocation.getX();
                        double lz = state.parkourLedge.z() + 0.5 - npcLocation.getZ();
                        double ledgeDistance = Math.sqrt(lx * lx + lz * lz);
                        if (ledgeDistance > PARKOUR_LEDGE_STOP_DISTANCE) {
                            speed = state.parkourClimbSpeed;
                            vx = (lx / ledgeDistance) * speed;
                            velocityZ = (lz / ledgeDistance) * speed;
                        } else {
                            vx = 0.0;
                            velocityZ = 0.0;
                        }
                        double shortfall = (state.parkourLedge.y() + 1) - npcLocation.getY();
                        if (!state.parkourAssisted && shortfall > 0.0
                                && shortfall <= PARKOUR_CLIMB_ASSIST_REACH
                                && ledgeDistance <= PARKOUR_CLIMB_ASSIST_REACH
                                && vy < PARKOUR_CLIMB_ASSIST) {
                            state.parkourAssisted = true;
                            vy = PARKOUR_CLIMB_ASSIST;
                        }
                    } else if (state.parkourDrop && state.parkourDropLanding != null) {
                        double lx = state.parkourDropLanding.x() + 0.5 - npcLocation.getX();
                        double lz = state.parkourDropLanding.z() + 0.5 - npcLocation.getZ();
                        double landingDistance = Math.sqrt(lx * lx + lz * lz);
                        if (landingDistance > PARKOUR_LEDGE_STOP_DISTANCE) {
                            speed = state.parkourDropSpeed;
                            vx = (lx / landingDistance) * speed;
                            velocityZ = (lz / landingDistance) * speed;
                        } else {
                            vx = 0.0;
                            velocityZ = 0.0;
                        }
                    } else {
                        speed = PARKOUR_CROSS_SPEED;
                        vx = (wx / waypointDistance) * speed;
                        velocityZ = (wz / waypointDistance) * speed;
                    }
                }
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

    /**
     * Whether there is a gap in the ground ahead of the companion.
     *
     * <p>Samples the columns directly ahead along the intended cardinal route. A supported
     * one-block step-down stays a normal walk; a missing support column is a gap, even if there
     * is a lower floor beyond it.</p>
     */
    static boolean isGapAhead(Mannequin npc, Location npcLocation, double wx, double wz) {
        double length = Math.sqrt(wx * wx + wz * wz);
        if (length < 1.0E-3) {
            return false;
        }
        double dirX = wx / length;
        double dirZ = wz / length;
        World world = npcLocation.getWorld();
        int originY = (int) Math.floor(npcLocation.getY());
        int originX = npcLocation.getBlockX();
        int originZ = npcLocation.getBlockZ();
        int axisX = Math.abs(dirX) >= Math.abs(dirZ) ? (int) Math.signum(dirX) : 0;
        int axisZ = axisX == 0 ? (int) Math.signum(dirZ) : 0;
        if (axisX == 0 && axisZ == 0) {
            return false;
        }
        int x = originX + axisX;
        int z = originZ + axisZ;
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return false;
        }
        // The ground continues beside the companion: nothing to jump over yet.
        if (hasFloorAt(world, x, originY - 1, z)) {
            return false;
        }
        // A one-block stair is walked down, not jumped: its tread sits exactly one block lower
        // and the column above it is free. A hole that is deeper than that is a real gap.
        if (hasFloorAt(world, x, originY - 2, z) && isClearColumn(world, x, originY - 1, z)) {
            return false;
        }
        // A solid block at foot height is a wall in the way, not something to jump over.
        return isClearColumn(world, x, originY, z);
    }

    /**
     * Whether there is a solid block to stand on at the given position.
     *
     * <p>A carpet counts as a floor, but only when there is a real floor underneath it. The
     * carpet is a sixteenth of a block tall and is laid on top of the ground, so the companion
     * walks over it without ever dropping into the block below. Treating it as a floor is what
     * keeps the companion from jumping along on every carpet it crosses, and from declaring a
     * gap in front of a door that has a carpet laid out in front of it. A carpet laid over a
     * hole is decoration, though, and does not make the ground solid — so the block below it
     * still has to hold it up.</p>
     */
    private static boolean hasFloorAt(World world, int x, int y, int z) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return true;
        }
        Block block = world.getBlockAt(x, y, z);
        if (block.getType() == Material.WATER) {
            return true;
        }
        if (PathPlanner.isCarpet(block)) {
            // The carpet is only a decoration: the ground under it is what holds the companion
            // up, so that is what has to be checked.
            return hasFloorAt(world, x, y - 1, z);
        }
        return !block.getCollisionShape().getBoundingBoxes().isEmpty();
    }

    /**
     * Finds a lower landing in the planned route across a gap.
     *
     * <p>The current waypoint may still be on the takeoff side: A* can use several ordinary
     * approach nodes before it emits the parkour edge. Search later route nodes for the first
     * lower, cardinal landing within jump range, then validate every gap and shaft column.</p>
     */
    private static PathPlanner.BlockPos findParkourDropLanding(World world, Location npcLocation,
                                                               List<org.bukkit.util.Vector> path,
                                                               int waypointIndex) {
        int originX = npcLocation.getBlockX();
        int originZ = npcLocation.getBlockZ();
        int originY = (int) Math.floor(npcLocation.getY());
        for (int index = waypointIndex; index < path.size(); index++) {
            org.bukkit.util.Vector routeNode = path.get(index);
            int targetX = routeNode.getBlockX();
            int targetZ = routeNode.getBlockZ();
            int dx = targetX - originX;
            int dz = targetZ - originZ;
            if (dx != 0 && dz != 0) {
                continue;
            }
            int horizontalSteps = Math.abs(dx) + Math.abs(dz);
            if (horizontalSteps < 2) {
                continue;
            }
            if (horizontalSteps > PARKOUR_GAP + 1) {
                return null;
            }
            int drop = originY - routeNode.getBlockY();
            if (drop <= 0 || drop > PARKOUR_DROP) {
                continue;
            }
            int stepX = Integer.compare(dx, 0);
            int stepZ = Integer.compare(dz, 0);
            boolean validGap = true;
            for (int step = 1; step < horizontalSteps; step++) {
                int x = originX + stepX * step;
                int z = originZ + stepZ * step;
                if (!world.isChunkLoaded(x >> 4, z >> 4)
                        || hasFloorAt(world, x, originY - 1, z)
                        || !isClearColumn(world, x, originY, z)) {
                    validGap = false;
                    break;
                }
            }
            if (!validGap || !world.isChunkLoaded(targetX >> 4, targetZ >> 4)) {
                continue;
            }
            boolean clearShaft = true;
            for (int y = originY - 1; y >= originY - drop; y--) {
                if (!isClearColumn(world, targetX, y, targetZ)
                        || PathPlanner.isWaterAt(world, targetX, y, targetZ)) {
                    clearShaft = false;
                    break;
                }
            }
            if (!clearShaft) {
                continue;
            }
            int feetY = originY - drop;
            if (!hasFloorAt(world, targetX, feetY - 1, targetZ)
                    || PathPlanner.isWaterAt(world, targetX, feetY - 1, targetZ)) {
                continue;
            }
            Block feet = world.getBlockAt(targetX, feetY, targetZ);
            Block head = world.getBlockAt(targetX, feetY + 1, targetZ);
            Block support = world.getBlockAt(targetX, feetY - 1, targetZ);
            if (feet.getType() != Material.LAVA && head.getType() != Material.LAVA
                    && support.getType() != Material.LAVA
                    && feet.getCollisionShape().getBoundingBoxes().isEmpty()
                    && head.getCollisionShape().getBoundingBoxes().isEmpty()) {
                return new PathPlanner.BlockPos(targetX, feetY, targetZ);
            }
        }
        return null;
    }



    /** Whether the far side of the gap sits one block higher than the companion. */
    private static PathPlanner.BlockPos findParkourLedge(World world, Location npcLocation,
                                                         double wx, double wz) {
        double length = Math.sqrt(wx * wx + wz * wz);
        if (length < 1.0E-3) {
            return null;
        }
        double dirX = wx / length;
        double dirZ = wz / length;
        int originY = (int) Math.floor(npcLocation.getY());
        int originX = npcLocation.getBlockX();
        int originZ = npcLocation.getBlockZ();
        int axisX = Math.abs(dirX) >= Math.abs(dirZ) ? (int) Math.signum(dirX) : 0;
        int axisZ = axisX == 0 ? (int) Math.signum(dirZ) : 0;
        if (axisX == 0 && axisZ == 0) {
            return null;
        }
        int gapX = originX + axisX;
        int gapZ = originZ + axisZ;
        if (!world.isChunkLoaded(gapX >> 4, gapZ >> 4)
                || hasFloorAt(world, gapX, originY - 1, gapZ)) {
            return null;
        }
        for (int step = 2; step <= PARKOUR_LANDING_SEARCH; step++) {
            int x = originX + axisX * step;
            int z = originZ + axisZ * step;
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                return null;
            }
            if (hasFloorAt(world, x, originY, z)
                    && isClearColumn(world, x, originY + 1, z)) {
                return new PathPlanner.BlockPos(x, originY, z);
            }
        }
        return null;
    }

    /** The horizontal distance from the companion to the middle of the ledge it is climbing onto. */
    private static double distanceToLedge(Location npcLocation, PathPlanner.BlockPos ledge) {
        double dx = ledge.x() + 0.5 - npcLocation.getX();
        double dz = ledge.z() + 0.5 - npcLocation.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Clears every parkour-only target and timer when its route is replaced or finished. */
    private static void clearParkourState(FollowState state) {
        state.parkourUntilTick = Long.MIN_VALUE;
        state.parkourClimb = false;
        state.parkourLedge = null;
        state.parkourClimbSpeed = 0.0;
        state.parkourAssisted = false;
        state.parkourDrop = false;
        state.parkourDropLanding = null;
        state.parkourDropSpeed = 0.0;
    }


    /**
     * Whether the given column is free for the companion to pass through.
     *
     * <p>A carpet counts as free. It is only a sixteenth of a block tall and the companion
     * walks over it, so it must not stop a jump or be read as an obstacle.</p>
     */
    private static boolean isClearColumn(World world, int x, int y, int z) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return false;
        }
        Block feet = world.getBlockAt(x, y, z);
        Block head = world.getBlockAt(x, y + 1, z);
        if (feet.getType() == Material.WATER || head.getType() == Material.WATER) {
            return false;
        }
        boolean feetFree = PathPlanner.isCarpet(feet)
                || feet.getCollisionShape().getBoundingBoxes().isEmpty();
        boolean headFree = PathPlanner.isCarpet(head)
                || head.getCollisionShape().getBoundingBoxes().isEmpty();
        return feetFree && headFree;
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
     * <p>When idle in FOLLOW/CLOSE mode, the head rests toward the owner and occasionally turns
     * toward a nearby living entity. In WAIT mode it rests facing the direction it is standing
     * toward, so it does not keep staring at the owner. While walking, the body keeps its walking
     * yaw and the head keeps facing where the body walks, except when a glance at a nearby entity
     * is underway and stays within {@link #MAX_GLANCE_ANGLE_DEGREES} of the walking direction.</p>
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
            Entity nearby = nearestWatchableEntity(npc, owner);
            if (nearby != null && withinGlanceAngle(npc, nearby, bodyYaw)) {
                state.gazeTarget = nearby.getUniqueId();
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
                if (state.mode == FollowMode.WAIT) {
                    // Waiting is not the same as standing beside the owner: do not stare at the
                    // owner. Let the idle gaze rest on the direction the companion faces instead.
                    targetX = npc.getLocation().getX() - Math.sin(Math.toRadians(bodyYaw));
                    targetY = npc.getEyeLocation().getY();
                    targetZ = npc.getLocation().getZ() + Math.cos(Math.toRadians(bodyYaw));
                } else {
                    // Standing by the owner: look at the owner (the normal resting gaze).
                    targetX = owner.getLocation().getX();
                    targetY = owner.getLocation().getY() + owner.getEyeHeight();
                    targetZ = owner.getLocation().getZ();
                }
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
     * Finds the nearest living entity worth a brief glance at, excluding the owner and self.
     *
     * <p>Companions are valid gaze targets too: in WAIT mode Yui and Sandrone can notice each
     * other rather than looking at the owner or treating their partner as scenery. Dead,
     * invalid, and self entities are ignored.</p>
     *
     * <p>Scanned only about twice a second rather than every tick, so idle behaviour stays cheap.</p>
     */
    private Entity nearestWatchableEntity(Mannequin npc, Player owner) {
        double radius = plugin.idleLookRadius();
        Entity closest = null;
        double closestDistanceSquared = radius * radius;
        for (Entity entity : npc.getNearbyEntities(radius, radius, radius)) {
            if (!(entity instanceof org.bukkit.entity.LivingEntity living)) {
                continue;
            }
            if (living.getUniqueId().equals(owner.getUniqueId())
                    || living.getUniqueId().equals(npc.getUniqueId())) {
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
     * Opens the doors on the companion's route and closes the ones it has already walked through,
     * so a house is left as it was found.
     *
     * <p>The door is found the way a villager finds it: on the route the companion is actually
     * walking, not merely nearby. A companion standing beside a door it has no intention of
     * walking through — a waiting companion wandering inside a house, for instance — never
     * touches it, because that door is not on its path. Previously the door was picked up by
     * proximity and a rough direction check, which opened doors the companion then walked away
     * from and left standing open.</p>
     *
     * <p>Three rules keep this from becoming an open invitation for hostile mobs:</p>
     * <ol>
     *   <li>a door is only opened while the companion is actually <b>moving</b> — a companion
     *       standing still beside its owner never forces a door open, which is what used to let
     *       creepers walk into a house that was already sealed;</li>
     *   <li>the door has to lie <b>on the route</b>, which means the companion really does walk
     *       through it rather than merely passing the doorway;</li>
     *   <li>doors the companion opened are closed again as soon as it has crossed them, so a
     *       house the player is resting in ends up sealed.</li>
     * </ol>
     */
    private void updateDoors(Location npcLocation, FollowState state,
                             boolean moving, double goalDx, double goalDz) {
        World world = npcLocation.getWorld();
        if (world == null) {
            return;
        }

        // A door that is still on the companion's route is left open — it is either still ahead
        // of it or the one it is standing in.
        List<Location> onRoute = doorsOnRoute(world, state);
        closeHeldDoors(world, npcLocation, state, onRoute);

        // A companion that is not moving has no route worth following: it is standing still, so
        // it is not about to walk through anything. It must not force a door open — that is what
        // used to let creepers walk into a house that was already sealed.
        if (!moving || state.path.isEmpty()) {
            return;
        }

        // Open closed doors or gates that lie on the route the companion is walking and that it
        // has actually reached. The route is read from the waypoints ahead of the companion, and
        // the companion has to be at the door as well: a door two rooms further down the route
        // is left alone until the companion gets there.
        for (Location door : doorsWithinReach(world, state.path, state.waypointIndex, npcLocation)) {
            Block block = door.getBlock();
            if (!(block.getBlockData() instanceof Openable openable) || openable.isOpen()) {
                continue;
            }
            setDoorState(block, true);
            state.openedDoors.add(door);
            // Remember the direction of travel and which side the companion was on, so the door
            // can be closed once it has genuinely crossed the threshold.
            double axisLength = Math.sqrt(goalDx * goalDx + goalDz * goalDz);
            double axisX = axisLength < 1.0E-3 ? 0.0 : goalDx / axisLength;
            double axisZ = axisLength < 1.0E-3 ? 0.0 : goalDz / axisLength;
            double ddx = door.getX() + 0.5 - npcLocation.getX();
            double ddz = door.getZ() + 0.5 - npcLocation.getZ();
            double side = (ddx * axisX + ddz * axisZ) >= 0.0 ? 1.0 : -1.0;
            sharedOpenDoors.put(door, new DoorHold(axisX, axisZ, side,
                    npcLocation.getX(), npcLocation.getZ(), Bukkit.getCurrentTick()));
        }
    }

    /**
     * The doors and fence gates that lie on the route the companion is walking.
     *
     * <p>The route is the list of waypoints ahead of the companion. A waypoint names the block
     * the companion stands on; a door is one block above it, so a wooden door standing in a
     * doorway shows up as the block above the waypoint. Only the waypoints the companion is
     * about to reach are looked at — far enough ahead to open a door in time, close enough that
     * a door the companion has already passed is not reopened.</p>
     *
     * <p>An already open door is reported as well, so a door the owner left open is remembered
     * and shut again once the companion has walked through it. That matches what a villager
     * does, and it is what keeps a house from being left open at night.</p>
     *
     * @return the doors on the route, in the order they are met
     */
    private List<Location> doorsOnRoute(World world, FollowState state) {
        return doorsOnRoute(world, state.path, state.waypointIndex);
    }

    /**
     * The doors and fence gates that lie on a route, found from the waypoints ahead.
     *
     * <p>Kept apart from the companion's own state so the rule can be checked on its own: a
     * companion standing beside a door it has no intention of walking through must leave it
     * alone, and the only thing that proves that is the route itself.</p>
     *
     * <p>A waypoint names the block the companion stands on. A door standing in a doorway is
     * its own block, so it is looked for at the waypoint's own height and one below it — the
     * waypoint height is the top of the door's shape, which is one block above the doorway.</p>
     *
     * @param world         the world the route is in
     * @param path          the route, as waypoints from the companion towards its goal
     * @param waypointIndex the waypoint the companion is heading for
     * @return the doors on the route, in the order they are met
     */
    static List<Location> doorsOnRoute(World world, List<org.bukkit.util.Vector> path,
                                       int waypointIndex) {
        List<Location> doors = new ArrayList<>();
        if (path == null || path.isEmpty()) {
            return doors;
        }
        int last = Math.min(path.size(), waypointIndex + DOOR_LOOKAHEAD_WAYPOINTS);
        for (int i = Math.max(0, waypointIndex); i < last; i++) {
            org.bukkit.util.Vector waypoint = path.get(i);
            int x = (int) Math.floor(waypoint.getX());
            int z = (int) Math.floor(waypoint.getZ());
            int feet = blockOfWaypoint(waypoint);
            // A door in a doorway is a block of its own, and the companion walks through it.
            // The door's lower half is the block the companion stands in and its upper half
            // sits one block above it.
            for (int dy = 0; dy <= 1; dy++) {
                Block block = world.getBlockAt(x, feet + dy, z);
                if (!isDoorOrGate(block)) {
                    continue;
                }
                Location door = new Location(world, x, feet + dy, z);
                if (!doors.contains(door)) {
                    doors.add(door);
                }
            }
        }
        return doors;
    }

    /**
     * The block the companion's feet are in when it stands on the given waypoint.
     *
     * <p>A waypoint carries the height the feet rest at, which is the top of whatever the
     * companion is standing on. A full block — and a closed door, whose shape fills its own
     * block — ends at a whole number, so the block it is is one below. A carpet or a slab ends
     * in between, and the block it is in is the one it starts in.</p>
     */
    static int blockOfWaypoint(org.bukkit.util.Vector waypoint) {
        double height = waypoint.getY();
        int whole = (int) Math.floor(height);
        if (height == whole) {
            return whole - 1;
        }
        return whole;
    }

    /**
     * The doors on the route that the companion is close enough to open.
     *
     * <p>A door is only opened when the companion has actually arrived at it. Without that a
     * companion saw a door two rooms away on its route and opened it early, which left a house
     * standing open with nobody near the door — and a waiting companion wandering inside a
     * house opened the door beside it although its route led elsewhere entirely.</p>
     *
     * @param companion where the companion is standing right now
     * @return the doors on the route that are within reach
     */
    static List<Location> doorsWithinReach(World world, List<org.bukkit.util.Vector> path,
                                           int waypointIndex, Location companion) {
        List<Location> reachable = new ArrayList<>();
        for (Location door : doorsOnRoute(world, path, waypointIndex)) {
            if (withinReach(door, companion)) {
                reachable.add(door);
            }
        }
        return reachable;
    }

    /**
     * Whether the companion is close enough to the door to work it.
     *
     * <p>Measured across the floor, the way a player reaches for a door. A door on the route
     * but still around the corner is left alone until the companion gets there.</p>
     */
    private static boolean withinReach(Location door, Location companion) {
        if (companion == null || companion.getWorld() == null) {
            return false;
        }
        double dx = door.getX() + 0.5 - companion.getX();
        double dz = door.getZ() + 0.5 - companion.getZ();
        return dx * dx + dz * dz <= DOOR_REACH_SQUARED;
    }

    /**
     * Closes the doors the companion has walked through, once it has stopped moving.
     *
     * <p>Used when the companion has arrived: it is no longer walking the route, so the door it
     * came through is behind it and can be shut. The door it is standing in stays open.</p>
     */
    private void closePassedDoors(Location npcLocation, FollowState state) {
        World world = npcLocation.getWorld();
        if (world == null) {
            return;
        }
        closeHeldDoors(world, npcLocation, state, List.of());
    }

    /**
     * Closes doors the companion has walked through.
     *
     * <p>Doors are keyed per-position in a shared map rather than per-companion lists: the first
     * companion opens a door, the second walks through it, the first closing it from the far side
     * must not re-open it under the second's feet.</p>
     *
     * <p>A door is shut once it is no longer on the companion's route — the companion has gone
     * past it — and nobody is standing in the doorway. The owner's distance is deliberately
     * irrelevant: the owner is usually standing right beside the door they just closed, and
     * using that as a condition meant the door never shut at all.</p>
     *
     * @param onRoute the doors the companion is still going to walk through
     */
    private List<Location> closeHeldDoors(World world, Location npcLocation, FollowState state,
                                          List<Location> onRoute) {
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
            // A door that is still on the companion's route is either ahead of it or the one it
            // is standing in. Either way it stays open.
            if (onRoute.contains(door)) {
                continue;
            }
            // A companion that has not crossed yet must not close its own door. Another party
            // companion may still be approaching it; keep it open until that companion passes or
            // the bounded timeout expires.
            boolean expired = currentTick - hold.openedTick() >= DOOR_HOLD_TIMEOUT_TICKS;
            if (!expired && !crossedThreshold(door, hold, npcLocation)) {
                continue;
            }
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
     * Keep a door open while another live companion is still going to walk through it.
     *
     * <p>The test is on that companion's route, not on how far away it happens to be. A
     * companion that is merely walking past in the same direction used to hold the door open
     * for as long as the timeout, which is why a door could stay open far longer than it
     * should. A companion that is actually going to walk through the door still gets it held
     * open for it.</p>
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
            FollowState other = entry.getValue();
            // The door is held open for a companion that is going to walk through it.
            if (doorsOnRoute(door.getWorld(), other).contains(door)) {
                return true;
            }
            // A companion that is already in the doorway still needs it open, even if its route
            // does not name the door any more: it is standing right there.
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
     * Adopts doors that somebody else left open and that the companion is about to walk through.
     *
     * <p>The owner opens doors all the time and does not always close them, and a companion that
     * walked through one of those leaves an open door behind that lets mobs in at night. So an
     * open door the companion reaches on its route is claimed — it gets closed once the
     * companion has moved well beyond it, exactly like doors the companion opened itself.</p>
     *
     * <p>Only doors on the route are adopted. A door the companion merely walks past in another
     * room is left exactly as it was found: tidying up the whole house is not the companion's
     * job, and a door it was never going to walk through is not its business.</p>
     */
    private void adoptOpenDoors(World world, FollowState state, Location npcLocation) {
        for (Location door : doorsWithinReach(world, state.path, state.waypointIndex, npcLocation)) {
            Block block = door.getBlock();
            if (!(block.getBlockData() instanceof Openable openable) || !openable.isOpen()) {
                continue;
            }
            if (sharedOpenDoors.containsKey(door) || state.openedDoors.contains(door)) {
                continue; // already tracked — ours, or another companion's
            }
            // Direction of travel is the direction to the companion's next waypoint.
            double axisLength = Math.sqrt(state.lastGoalDx * state.lastGoalDx
                    + state.lastGoalDz * state.lastGoalDz);
            if (axisLength < 1.0E-3) {
                continue;
            }
            double axisX = state.lastGoalDx / axisLength;
            double axisZ = state.lastGoalDz / axisLength;
            double ddx = door.getX() + 0.5 - npcLocation.getX();
            double ddz = door.getZ() + 0.5 - npcLocation.getZ();
            double side = (ddx * axisX + ddz * axisZ) >= 0.0 ? 1.0 : -1.0;
            state.openedDoors.add(door);
            sharedOpenDoors.put(door, new DoorHold(axisX, axisZ, side,
                    npcLocation.getX(), npcLocation.getZ(), Bukkit.getCurrentTick()));
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
            // The step must not cross a fence or a wall. A sidestep that lands on the far side
            // of a barrier would put the companion on the wrong side of it, which is the same
            // as walking through the fence.
            if (crossesBarrier(current, target)) {
                continue;
            }
            npc.teleport(target);
            npc.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
            state.path = List.of();
            state.plannedGoal = null;
            clearParkourState(state);
            state.waypointIndex = 0;
            state.lastProgressPosition = null;
            return true;
        }
        return false;
    }

    /**
     * Whether moving straight from one spot to another would pass through a barrier.
     *
     * <p>Only the blocks between the two spots are looked at. A fence, a fence wall or a glass
     * pane counts as a barrier: stepping over one of those is the same as squeezing through
     * it, so the companion has to go around instead.</p>
     */
    private static boolean crossesBarrier(Location from, Location to) {
        World world = from.getWorld();
        if (world == null || !world.equals(to.getWorld())) {
            return true;
        }
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        int steps = (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dz)) * 2.0);
        if (steps <= 0) {
            return false;
        }
        int y = (int) Math.floor(from.getY());
        for (int step = 1; step < steps; step++) {
            double fraction = (double) step / steps;
            int x = (int) Math.floor(from.getX() + dx * fraction);
            int z = (int) Math.floor(from.getZ() + dz * fraction);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            if (PathPlanner.isBarrier(world.getBlockAt(x, y, z))) {
                return true;
            }
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
     * Whether this block is a wooden door, a copper door, or a fence gate — the only things a
     * companion may open on its own.
     *
     * <p>The set matches what a villager is allowed to work in vanilla, plus the fence gate
     * that was asked for. Vanilla only operates doors tagged {@code mob_interactable_doors},
     * which is every wooden door and every copper door: not an iron door, not a trapdoor, and
     * not a fence gate. Fence gates are kept here because a companion that has to walk around
     * a closed gate to follow its owner is worse than one that opens it.</p>
     *
     * <p>Both door kinds implement {@code Openable}, but so do trapdoors, barrels and a few
     * other blocks that are meant to stay as they are. A companion that opened a trapdoor would
     * leave a house looking as if it had been ransacked, and one that closed a trapdoor behind
     * itself would trap the owner inside. So the check is on the concrete block type, not on the
     * interface.</p>
     */
    static boolean isDoorOrGate(Block block) {
        Material material = block.getType();
        if (material == Material.OAK_DOOR || material == Material.SPRUCE_DOOR
                || material == Material.BIRCH_DOOR || material == Material.JUNGLE_DOOR
                || material == Material.ACACIA_DOOR || material == Material.DARK_OAK_DOOR
                || material == Material.PALE_OAK_DOOR || material == Material.CRIMSON_DOOR
                || material == Material.WARPED_DOOR || material == Material.MANGROVE_DOOR
                || material == Material.BAMBOO_DOOR || material == Material.CHERRY_DOOR
                || material == Material.POPLAR_DOOR) {
            return true;
        }
        if (material == Material.COPPER_DOOR || material == Material.EXPOSED_COPPER_DOOR
                || material == Material.WEATHERED_COPPER_DOOR
                || material == Material.OXIDIZED_COPPER_DOOR || material == Material.WAXED_COPPER_DOOR
                || material == Material.WAXED_EXPOSED_COPPER_DOOR
                || material == Material.WAXED_WEATHERED_COPPER_DOOR
                || material == Material.WAXED_OXIDIZED_COPPER_DOOR) {
            return true;
        }
        return block.getBlockData() instanceof Gate;
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
     * Picks where a waiting companion strolls next.
     *
     * <p>The companion keeps the spot it was told to wait at as its anchor and free-roams around
     * it the way a mob does: it picks a direction, walks it for a while, then turns or rests
     * before picking another. It never marches to a fixed destination and stops there. The anchor
     * is set the first time the mode is used, which is where the companion was standing when the
     * command was given.</p>
     *
     * @return the point the companion should head for right now
     */
    private Location waitSpot(Mannequin npc, FollowState state, long currentTick) {
        Location npcLocation = npc.getLocation();
        if (state.waitSpot == null) {
            // The first time the mode is used, the current position becomes the anchor.
            state.waitSpot = npcLocation.clone();
        }
        // A companion that was pulled away from its spot — to fight a mob — heads straight back
        // there first. Walking back is not a stroll: the stroll only resumes once the companion
        // is home again.
        double anchorDx = npcLocation.getX() - state.waitSpot.getX();
        double anchorDz = npcLocation.getZ() - state.waitSpot.getZ();
        double anchorDistance = Math.sqrt(anchorDx * anchorDx + anchorDz * anchorDz);
        if (anchorDistance > WAIT_WANDER_RADIUS) {
            state.waitWanderTarget = null;
            state.waitSpotPaused = false;
            state.waitNextTurnTick = Long.MIN_VALUE;
            Location home = state.waitSpot.clone();
            // The way back has to be walked around, not through. A companion that was pulled
            // behind a fence walks around it, so it needs a route; without one it stays put
            // instead of shoving through the barrier on the way home.
            //
            // Several points on the way are tried, not just one. The spot just past the anchor
            // may be the one block that cannot be reached — a hole in the ground, a bed, a
            // fence — while the anchor itself is perfectly reachable. Stopping at the first
            // unreachable point left the companion standing where the fight dropped it,
            // for good.
            for (double overshoot : new double[]{1.5, 0.5, 0.0, 3.0}) {
                Location back = new Location(npc.getWorld(),
                        home.getX() - anchorDx / anchorDistance * overshoot,
                        home.getY(),
                        home.getZ() - anchorDz / anchorDistance * overshoot);
                if (hasWalkableRoute(npc.getWorld(), npcLocation, back)) {
                    return back;
                }
            }
            // The post itself cannot be walked back to from here. The companion stays where
            // it is rather than shoving through whatever is in the way, and it keeps watching
            // the ground it was told to hold.
            return npcLocation.clone();
        }
        // Resting between strolls: the companion stands still for a moment, the way a mob idles.
        if (state.waitSpotPaused) {
            if (currentTick < state.waitNextTurnTick) {
                return npcLocation.clone();
            }
            state.waitSpotPaused = false;
        }
        // A stroll that has run its course: pick a new direction.
        if (state.waitWanderTarget == null || currentTick >= state.waitNextTurnTick) {
            Location next = pickWanderPoint(npc, state);
            state.waitWanderTarget = next;
            long stroll = WAIT_DIRECTION_MIN_TICKS
                    + (long) (Math.random() * (WAIT_DIRECTION_MAX_TICKS - WAIT_DIRECTION_MIN_TICKS));
            state.waitNextTurnTick = currentTick + stroll;
            state.waitSpotPickedTick = currentTick;
            return next;
        }
        // The stroll continues: the target keeps moving away from the companion so it walks on
        // rather than stopping at a point and standing there.
        Location target = state.waitWanderTarget.clone();
        double dx = target.getX() - npcLocation.getX();
        double dz = target.getZ() - npcLocation.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 0.05) {
            // The stroll target was reached; extend it further along the same direction.
            double yaw = Math.toRadians(state.lastWalkingYaw);
            target = npcLocation.clone().add(
                    -Math.sin(yaw) * 3.0, 0, Math.cos(yaw) * 3.0);
            state.waitWanderTarget = target;
        }
        return target;
    }

    /**
     * Whether a route exists between two places that the companion can walk.
     *
     * <p>Used to check a stroll target before the companion heads for it. A point that is only
     * a block away can still be behind a fence, and walking at it would push the companion
     * through the barrier rather than around it.</p>
     */
    private static boolean hasWalkableRoute(World world, Location from, Location to) {
        List<org.bukkit.util.Vector> route = PathPlanner.findPath(world, from, to,
                PATH_RADIUS, false, false, false);
        return !route.isEmpty();
    }

    /**
     * Chooses where the companion strolls next.
     *
     * <p>The companion picks a direction and keeps walking it for a while, the way a mob wanders:
     * it does not pick a destination and march to it. The direction is chosen from the free space
     * around the companion, and it is gently pulled back towards the anchor when it strays too
     * far, so a waiting companion never wanders off and is lost.</p>
     *
     * <p>When no such point exists — a cramped corner, a one-block closet — the companion rests
     * where it is rather than walking into geometry.</p>
     */
    private Location pickWanderPoint(Mannequin npc, FollowState state) {
        Location npcLocation = npc.getLocation();
        Location anchor = state.waitSpot == null ? npcLocation : state.waitSpot;
        World world = npc.getWorld();

        // Too far from the anchor: head back instead of wandering further away.
        double offsetX = npcLocation.getX() - anchor.getX();
        double offsetZ = npcLocation.getZ() - anchor.getZ();
        double offsetDistance = Math.sqrt(offsetX * offsetX + offsetZ * offsetZ);
        double limit = WAIT_WANDER_RADIUS + WAIT_WANDER_SLACK;
        if (offsetDistance > limit) {
            // Walk back in, a little past the anchor so it does not hover on the edge.
            double backScale = Math.max(0.0, (offsetDistance - WAIT_WANDER_RADIUS * 0.5)) / offsetDistance;
            Location back = new Location(world,
                    npcLocation.getX() - offsetX * backScale,
                    npcLocation.getY(),
                    npcLocation.getZ() - offsetZ * backScale);
            if (isFreeForCompanion(back) && hasWalkableRoute(world, npcLocation, back)) {
                return back;
            }
        }

        // Otherwise pick a nearby point to head for. The distance is short so the companion
        // meanders rather than crossing the area in a straight line.
        for (int attempt = 0; attempt < 12; attempt++) {
            double angle = Math.random() * Math.PI * 2.0;
            double radius = 1.5 + Math.random() * 2.5;
            Location candidate = new Location(world,
                    npcLocation.getX() + Math.sin(angle) * radius,
                    npcLocation.getY(),
                    npcLocation.getZ() + Math.cos(angle) * radius);
            if (!isFreeForCompanion(candidate)) {
                continue;
            }
            // Stay inside the wander radius around the anchor.
            double candidateOffsetX = candidate.getX() - anchor.getX();
            double candidateOffsetZ = candidate.getZ() - anchor.getZ();
            if (candidateOffsetX * candidateOffsetX + candidateOffsetZ * candidateOffsetZ
                    > WAIT_WANDER_RADIUS * WAIT_WANDER_RADIUS) {
                continue;
            }
            // The point must be reachable from where the companion stands, so it does not try to
            // walk into a wall to get there.
            List<org.bukkit.util.Vector> route = PathPlanner.findPath(world, npcLocation, candidate,
                    PATH_RADIUS, false, false, false);
            if (!route.isEmpty()) {
                return candidate;
            }
        }
        // Nowhere to go: rest here for a while and try again later.
        state.waitSpotPaused = true;
        state.waitNextTurnTick = Bukkit.getCurrentTick() + WAIT_REST_MIN_TICKS
                + (long) (Math.random() * (WAIT_REST_MAX_TICKS - WAIT_REST_MIN_TICKS));
        return npcLocation.clone();
    }

    /**
     * Puts the companion's weapon into its hand, or takes it away.
     *
     * <p>The weapon is only drawn when the companion actually has a mob to fight. A companion
     * that is following the owner, idling, or waiting at its spot keeps its hands empty; it
     * draws its weapon when it decides to attack and puts it away once the fight is over.</p>
     */
    private static void updateWeapon(Mannequin npc, String archetype, boolean fighting) {
        CompanionWeapons.Weapon weapon = fighting ? CompanionWeapons.of(archetype) : null;
        CompanionWeapons.equip(npc, weapon);
    }

    /** Teleports the companion to its mode-specific target, closing its doors and resetting route state. */
    /**
     * Brings a companion back to its owner after it has fallen too far behind.
     *
     * <p>Only the doors this companion opened are closed. Doors the other companion is still
     * using must stay open, or that one gets shut inside a doorway.</p>
     *
     * <p>Never replace an unreachable mode target with an arbitrary side spot: that fallback
     * was the original way a close-mode rescue could land on the opposite side of a wall. If
     * the selected landing point is not clear, the companion is left where it is and the
     * caller carries on with the fight instead of standing still.</p>
     *
     * @return true when the companion was moved
     */
    private boolean rescueTeleport(Mannequin npc, Player owner, Location ownerLocation,
                                   FollowState state) {
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
            return false;
        }
        // Lower only within this same column, and only when the landing block remains clear.
        Location floorSpot = dropToFloor(spot, ownerLocation);
        if (floorSpot == null || !isFreeForCompanion(floorSpot)) {
            return false;
        }
        npc.teleport(floorSpot);
        npc.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        state.path = List.of();
        state.plannedGoal = null;
        state.waypointIndex = 0;
        state.lastProgressPosition = null;
        state.sideStepAttempts = 0;
        // The fight the companion was in is over: it has been pulled back to its owner, so it
        // must not keep aiming at a mob that is now far away. Leaving the target in place
        // made the companion walk straight back out to it, and the rescue repeated for ever.
        state.attackTarget = null;
        state.attackTargetTick = Long.MIN_VALUE;
        return true;
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

    /** Creates the Eris pouch inventory, or restores the owner's saved pouch. */
    public void openErisPouch(Player owner) {
        if (!isErisOwner(owner.getUniqueId())) {
            return;
        }
        File file = new File(plugin.getDataFolder(), "eris-pouch.yml");
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        String path = owner.getUniqueId().toString();
        ErisPouchHolder pouch = new ErisPouchHolder(owner.getUniqueId());
        Inventory inventory = pouch.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = config.getItemStack(path + "." + slot);
            if (item != null) {
                inventory.setItem(slot, item);
            }
        }
        owner.openInventory(inventory);
    }

    /** Saves only an ErisPouchHolder inventory, never another chest the owner happens to close. */
    public void saveErisPouch(Player owner, Inventory inventory) {
        if (!isErisOwner(owner.getUniqueId()) || inventory == null
                || !(inventory.getHolder() instanceof ErisPouchHolder holder)
                || !holder.belongsTo(owner)) {
            return;
        }
        File file = new File(plugin.getDataFolder(), "eris-pouch.yml");
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        String path = owner.getUniqueId().toString();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            config.set(path + "." + slot, item == null || item.getType().isAir() ? null : item);
        }
        try {
            config.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not save Eris pouch to " + file, ex);
        }
    }

    /** Whether a player may open Eris's pouch. */
    public boolean canOpenErisPouch(Player player) {
        return isErisOwner(player.getUniqueId());
    }

    /**
     * Right-click should only open the pouch for the permanent owner; designated owner UUID
     * does not count as ownership until that player actually calls Eris.
     */
    public boolean canInteractWithEris(Player player, UUID erisEntityId) {
        if (!isErisOwner(player.getUniqueId())) {
            return false;
        }
        Entity eris = Bukkit.getEntity(erisEntityId);
        return eris instanceof Mannequin mannequin
                && mannequin.getScoreboardTags().contains("companion_eris");
    }

    /**
     * Mutes or unmutes the companions' chatter for the whole server.
     *
     * @return whether the chatter is muted now
     */
    public boolean toggleCompanionChatter() {
        return dialogue.toggleMuted();
    }

    /** Whether the companions are muted for the whole server. */
    public boolean companionChatterMuted() {
        return dialogue.isMuted();
    }

    /**
     * The companions of Arif's group that carry a pouch.
     *
     * <p>Each of them has her own bag, saved separately, and only the owner of the companion may
     * open it.</p>
     */
    private static final List<String> POUCH_ARCHETYPES =
            List.of("yui", "sandrone", "furina", "ei", "columbina", "escoffier");

    /** Whether this archetype is one of Arif's companions that carries a pouch. */
    public static boolean hasPouch(String archetype) {
        return archetype != null
                && POUCH_ARCHETYPES.contains(archetype.toLowerCase(Locale.ROOT));
    }

    /**
     * Whether the given player owns the given companion, and may therefore open its pouch.
     *
     * <p>Ownership is decided by the companion itself, not by the archetype: a companion belongs
     * to the player who called her, so only that player's pouch opens. A player who is allowed to
     * use the companion group may call a companion, but the companion is then <em>his</em>, and
     * only his own bag is open to him. That keeps the bags of one owner from being opened by
     * another, whoever may call whom.</p>
     */
    public boolean canOpenCompanionPouch(Player player, UUID companionEntityId) {
        if (player == null) {
            return false;
        }
        Entity companion = Bukkit.getEntity(companionEntityId);
        if (!(companion instanceof Mannequin mannequin)) {
            return false;
        }
        for (String tag : mannequin.getScoreboardTags()) {
            if (!tag.startsWith("companion_")) {
                continue;
            }
            String archetype = tag.substring("companion_".length());
            if (!hasPouch(archetype)) {
                continue;
            }
            // The tag is on the companion, but only the owner knows which key it is filed under.
            CompanionKey key = new CompanionKey(player.getUniqueId(), archetype);
            UUID npcId = ownerToNpc.get(key);
            return npcId != null && npcId.equals(companionEntityId);
        }
        return false;
    }

    /**
     * Opens a companion's pouch for its owner.
     *
     * <p>The bag is saved in the plugin's own data folder, one file per companion, so the four
     * of them never share a single inventory or overwrite each other.</p>
     */
    public void openCompanionPouch(Player owner, String archetype) {
        if (owner == null || !hasPouch(archetype)) {
            return;
        }
        String kind = archetype.toLowerCase(Locale.ROOT);
        CompanionKey key = new CompanionKey(owner.getUniqueId(), kind);
        UUID npcId = ownerToNpc.get(key);
        if (npcId == null || !canOpenCompanionPouch(owner, npcId)) {
            return;
        }
        File file = pouchFile(kind);
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        String path = owner.getUniqueId() + "." + kind;
        CompanionPouchHolder pouch = new CompanionPouchHolder(owner.getUniqueId(), kind);
        Inventory inventory = pouch.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = config.getItemStack(path + "." + slot);
            if (item != null) {
                inventory.setItem(slot, item);
            }
        }
        owner.openInventory(inventory);
    }

    /**
     * Saves a companion's pouch when its window is closed.
     *
     * <p>Only an inventory whose holder is a pouch of that very companion is written back, so a
     * chest the owner happens to close is never touched.</p>
     */
    public void saveCompanionPouch(Player owner, Inventory inventory) {
        if (owner == null || inventory == null
                || !(inventory.getHolder() instanceof CompanionPouchHolder holder)
                || !holder.belongsTo(owner)) {
            return;
        }
        String kind = holder.archetype();
        File file = pouchFile(kind);
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        String path = owner.getUniqueId() + "." + kind;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            config.set(path + "." + slot, item == null || item.getType().isAir() ? null : item);
        }
        try {
            config.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not save " + kind + " pouch to " + file, ex);
        }
    }

    /** The file a companion's pouch is saved in. */
    private File pouchFile(String archetype) {
        return new File(plugin.getDataFolder(), archetype + "-pouch.yml");
    }

    /** Removes the companion of the given archetype owned by the given player, if any. */
    public void despawn(Player owner, String archetype) {
        String kind = archetype.toLowerCase(Locale.ROOT);
        CompanionKey key = new CompanionKey(owner.getUniqueId(), kind);
        // Take the id before cancel() clears the bookkeeping, so the entity itself can be removed.
        UUID npcId = ownerToNpc.remove(key);
        cancel(key, npcId == null ? null : Bukkit.getEntity(npcId));
        // The companion is gone, so its owner may no longer receive its effects.
        owner.removeScoreboardTag(activeTag(kind));
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
        for (String archetype : ARCHETYPES) {
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
     * Keeps {@link #activeTag} scoreboard tags aligned with live companions.
     *
     * <p>Skript uses these tags to decide who may receive Yui/Sandrone buffs and voice lines.
     * After a crash, reload, or {@link #despawnEverything()}, stale tags must not linger on the
     * owner.</p>
     */
    public void syncActiveCompanionTags(Player player) {
        UUID ownerId = player.getUniqueId();
        for (String archetype : ARCHETYPES) {
            String tag = activeTag(archetype);
            boolean live = ownerToNpc.containsKey(new CompanionKey(ownerId, archetype));
            if (live) {
                player.addScoreboardTag(tag);
            } else {
                player.removeScoreboardTag(tag);
            }
        }
    }

    /**
     * Removes every companion in the world, including ones left behind by a crash.
     *
     * <p>Removal is by scoreboard tag rather than by bookkeeping, so strays from a previous run are
     * cleaned up too. This is used on disable and by the admin clear command.</p>
     */
    public void despawnEverything() {
        // Release the chunk tickets first: a ticket left behind keeps a chunk pinned for the
        // rest of the server's life, long after the companion is gone.
        for (Map.Entry<CompanionKey, FollowState> entry : followStates.entrySet()) {
            Entity companion = companionOf(entry.getKey());
            if (companion != null) {
                releaseChunk(companion, entry.getValue());
            }
        }
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
        for (Player player : Bukkit.getOnlinePlayers()) {
            for (String archetype : ARCHETYPES) {
                player.removeScoreboardTag(activeTag(archetype));
            }
        }
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
     * The companions the shared {@code /ikut}, {@code /kesini} and {@code /tunggu}
     * commands may touch.
     *
     * <p>These are Arif's own companions — the pair plus the newer ones. Eris is deliberately
     * excluded. She is a standalone companion with her own commands, so the pair-wide commands
     * must never reach her.</p>
     */
    private static final List<String> PAIRED_ARCHETYPES =
            List.of("yui", "sandrone", "furina", "ei", "columbina", "escoffier");

    /**
     * Every companion Arif can call at once: the original pair and the four newer ones.
     *
     * <p>Eris is deliberately not here — she belongs to her own owner, not to this group.</p>
     *
     * @return the archetypes, in the order they are called
     */
    public static List<String> pairedArchetypes() {
        return PAIRED_ARCHETYPES;
    }

    private boolean hasAnyGroupCompanion(Player owner) {
        for (String archetype : PAIRED_ARCHETYPES) {
            if (owner.getScoreboardTags().contains(activeTag(archetype))) {
                return true;
            }
        }
        return false;
    }

    private Location idleSpeechLocation(Player owner, String archetype) {
        if (!owner.getScoreboardTags().contains(activeTag(archetype))) {
            return null;
        }
        FollowState state = followStates.get(new CompanionKey(owner.getUniqueId(), archetype));
        if (state == null || state.attackTarget != null || state.mode == FollowMode.WAIT) {
            return null;
        }
        Entity npc = getCompanion(owner.getUniqueId(), archetype);
        return npc == null ? null : npc.getLocation();
    }

    /**
     * The side of the owner a companion naturally stands on.
     *
     * <p>Yui holds the left, Sandrone the right. The newer companions take the other sides in
     * turn, so Arif's group spreads out instead of six of them fighting for the same spot: Furina
     * is left-front, Ei right-front, Columbina left-back, and Escoffier right-back.</p>
     *
     * @return {@code +1} for the owner's left, {@code -1} for the owner's right
     */
    private static double naturalSide(String archetype) {
        return switch (archetype.toLowerCase(Locale.ROOT)) {
            case "yui", "furina", "columbina" -> 1.0;
            default -> -1.0;
        };
    }

    /**
     * Turns the owner's companions to CLOSE mode, which remains until /ikut is used.
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

    /**
     * Tells the owner's companions to wait where they are instead of following.
     *
     * <p>The companion keeps the spot it is standing in as its anchor and only wanders a few
     * blocks around it, so it stays where it was left while still looking alive. It is not
     * rescued by teleport in this mode: being far from the owner is exactly what was asked
     * for.</p>
     *
     * @param archetype "yui", "sandrone", or "semua" for both
     * @return how many companions were actually switched
     */
    public int callWait(Player owner, String archetype) {
        return setMode(owner, archetype, FollowMode.WAIT);
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
            // "semua" means the Yui–Sandrone pair only; Eris has her own commands.
            if (archetype.equalsIgnoreCase("semua") && !PAIRED_ARCHETYPES.contains(key.archetype())) {
                continue;
            }
            FollowState state = entry.getValue();
            state.mode = mode;
            savedModes.put(key, mode);
            state.closeTarget = null;
            state.closeTargetTick = Long.MIN_VALUE;
            // A new standing behaviour means the old waiting spot is meaningless: the companion
            // has to be told where to wait again.
            state.waitSpot = null;
            state.waitWanderTarget = null;
            state.waitSpotPickedTick = Long.MIN_VALUE;
            state.waitSpotPaused = false;
            state.waitNextTurnTick = Long.MIN_VALUE;
            // Force a fresh route immediately after switching modes.
            state.path = List.of();
            state.plannedGoal = null;
            state.lastPlanTick = Long.MIN_VALUE;
            state.lastAttemptTick = Long.MIN_VALUE;
            state.lastProgressPosition = null;
            state.sideStepAttempts = 0;
            changed++;
            // A companion that was told to wait says so.
            if (mode == FollowMode.WAIT) {
                Entity npc = getCompanion(owner.getUniqueId(), key.archetype());
                dialogue.onWait(owner, key.archetype(),
                        npc == null ? owner.getLocation() : npc.getLocation());
            }
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
     * The name the owner knows a companion by.
     */
    public static String labelFor(String archetype) {
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

    /**
     * How long a repeated click on the same companion is ignored, in ticks.
     *
     * <p>The listener already drops the off-hand copy of a click. This is the second line of
     * defence: it covers any repeat that still slips through, so one decision can never turn
     * into two steps of the mode cycle.</p>
     */
    private static final long CYCLE_DEBOUNCE_TICKS = 4L;

    /**
     * Moves a companion to the next mode in the cycle and reports the mode she is in now.
     *
     * <p>The cycle runs Ikut, then Kesini, then Tunggu, and back to Ikut. It is what a
     * crouching right-click on the companion does, so the owner can change her behaviour
     * without typing a command.</p>
     *
     * @param owner     the player who owns the companion
     * @param archetype the companion's archetype
     * @return the mode the companion is in now, or null when she is not out or when this was
     *         a repeat of a click that has just been handled
     */
    public FollowMode cycleMode(Player owner, String archetype) {
        FollowState state = followStates.get(
                new CompanionKey(owner.getUniqueId(), archetype.toLowerCase(Locale.ROOT)));
        if (state == null) {
            return null;
        }
        long now = Bukkit.getCurrentTick();
        if (state.lastCycleClickTick != Long.MIN_VALUE
                && now - state.lastCycleClickTick < CYCLE_DEBOUNCE_TICKS) {
            return null;
        }
        state.lastCycleClickTick = now;
        FollowMode next = switch (state.mode) {
            case FOLLOW -> FollowMode.CLOSE;
            case CLOSE -> FollowMode.WAIT;
            case WAIT -> FollowMode.FOLLOW;
        };
        setMode(owner, archetype, next);
        return next;
    }

    private void cancel(CompanionKey key) {
        cancel(key, companionOf(key));
    }

    /**
     * Stops tracking a companion and releases everything it holds.
     *
     * @param companion the companion entity, when it is known. It is passed in because the
     *                 bookkeeping may already have been cleared by the caller.
     */
    private void cancel(CompanionKey key, Entity companion) {
        BukkitTask task = followTasks.remove(key);
        if (task != null) {
            task.cancel();
        }
        FollowState state = followStates.remove(key);
        if (state != null) {
            closeOwnDoors(state);
            // The chunk ticket has to go with the companion, or the chunk stays pinned for the
            // rest of the server's life.
            if (companion != null) {
                releaseChunk(companion, state);
            }
            // A companion that stopped being tracked is gone for good. The bookkeeping and the
            // owner's tag have to go with it, or the owner would still count as having this
            // companion: the buff and voice scripts would keep firing, and their sound would
            // fall back to the owner instead of the companion's own body.
            ownerToNpc.remove(key);
            Player owner = Bukkit.getPlayer(key.ownerId());
            if (owner != null) {
                owner.removeScoreboardTag(activeTag(key.archetype()));
            }
        }
    }

    /** The live companion entity of the given key, or null when it is gone. */
    private Entity companionOf(CompanionKey key) {
        UUID npcId = ownerToNpc.get(key);
        if (npcId == null) {
            return null;
        }
        return Bukkit.getEntity(npcId);
    }
}
