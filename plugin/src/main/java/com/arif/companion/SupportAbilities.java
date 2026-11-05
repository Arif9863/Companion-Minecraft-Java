package com.arif.companion;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The support abilities of Arif's newer companions.
 *
 * <p>Each companion has one ability of its own, triggered by something happening to the owner
 * rather than by a command:</p>
 * <ul>
 *   <li><b>Furina</b> — grants Water Breathing while the owner is swimming, and Fire Resistance
 *       while the owner is burning.</li>
 *   <li><b>Ei</b> — grants Strength after the owner takes their first kill.</li>
 *   <li><b>Columbina</b> — grants Swiftness while a hostile mob is targeting the owner.</li>
 *   <li><b>Escoffier</b> — slows the hostile mobs that are targeting the owner.</li>
 *   <li><b>Yui</b> — heals the owner slowly while hurt.</li>
 *   <li><b>Sandrone</b> — grants Absorption on a fixed interval.</li>
 * </ul>
 *
 * <p>Every ability has its own duration and cooldown, so a companion does not spam its effect.
 * The state is kept per owner and companion, and is thrown away when the plugin shuts down: the
 * abilities are a session convenience, not something that has to survive a restart.</p>
 */
final class SupportAbilities {

    /** Furina: Water Breathing while the owner is in water, for 10 seconds. */
    private static final int FURINA_WATER_BREATHING_TICKS = 200;
    /** Furina: Fire Resistance while the owner is burning, for 10 seconds. */
    private static final int FURINA_FIRE_RESISTANCE_TICKS = 200;
    /** Furina: 5 seconds between two grants of the same effect. */
    private static final long FURINA_COOLDOWN_TICKS = 100L;

    /** Ei: Strength for 20 seconds after the owner's first kill. */
    private static final int EI_STRENGTH_TICKS = 400;
    /** Ei: how long after the kill the grant may still happen. */
    private static final long EI_GRANT_WINDOW_TICKS = 100L;

    /** Columbina: Swiftness for 15 seconds while a hostile is coming for the owner. */
    private static final int COLUMBINA_SWIFTNESS_TICKS = 300;
    /** Columbina: 5 seconds between two grants. */
    private static final long COLUMBINA_COOLDOWN_TICKS = 100L;

    /** Escoffier: Slowness on the threatening mobs, for 15 seconds. */
    private static final int ESCOFFIER_SLOWNESS_TICKS = 300;
    /** Escoffier: 5 seconds between two passes over the threatening mobs. */
    private static final long ESCOFFIER_COOLDOWN_TICKS = 100L;

    /** Yui: heal 1 heart every 10 seconds while the owner is hurt. */
    private static final long YUI_HEAL_INTERVAL_TICKS = 200L;
    /** Sandrone: Absorption III every 45 seconds. */
    private static final long SANDRONE_ABSORB_INTERVAL_TICKS = 900L;
    private static final int SANDRONE_ABSORB_DURATION_TICKS = 1200;
    private static final int SANDRONE_ABSORB_AMPLIFIER = 2;
    /** Sandrone: at most one absorption voice line per minute. */
    private static final long SANDRONE_VOICE_COOLDOWN_TICKS = 1200L;
    private static final int MESSAGE_EVERY = 2;
    private static final int FOLLOWUP_EVERY = 3;
    private static final long FOLLOWUP_DELAY_TICKS = 100L;
    /** How close a hostile has to be to count as a threat to the owner. */
    private static final double THREAT_RADIUS = 16.0;

    /** Per owner and archetype bookkeeping. */
    private static final class State {
        private long lastWaterBreathingTick = Long.MIN_VALUE;
        private long lastFireResistanceTick = Long.MIN_VALUE;
        private long lastStrengthTick = Long.MIN_VALUE;
        private long lastSwiftnessTick = Long.MIN_VALUE;
        private long lastSlowTick = Long.MIN_VALUE;
        /** Tick at which the owner's first kill happened, for Ei's strength. */
        private long firstKillTick = Long.MIN_VALUE;
        /** Whether Ei's strength was already granted for that kill. */
        private boolean strengthGranted;
    }

    private final Map<String, State> states = new HashMap<>();

    private static final class YuiSession {
        private long nextHealTick = Long.MIN_VALUE;
        private int messageCounter;
        private int followCounter;
        private long followAt = Long.MIN_VALUE;
    }

    private static final class SandroneSession {
        private long nextAbsorbTick = Long.MIN_VALUE;
        private int messageCounter;
        private int followCounter;
        private long lastVoiceTick = Long.MIN_VALUE;
        private long followAt = Long.MIN_VALUE;
    }

    private final Map<UUID, YuiSession> yuiSessions = new HashMap<>();
    private final Map<UUID, SandroneSession> sandSessions = new HashMap<>();

    /** Yui/Sandrone presentation hooks wired from {@link CompanionDialogue}. */
    interface LegacySupport {
        void yuiHeal(Player owner, Location at);

        void sandroneAbsorption(Player owner, Location at);

        void followUp(Player owner, String archetype, Location at);
    }

    private LegacySupport legacySupport;

    void bindLegacySupport(LegacySupport legacySupport) {
        this.legacySupport = legacySupport;
    }

    private static String key(Player owner, String archetype) {
        return owner.getUniqueId() + ":" + archetype;
    }

    private State state(Player owner, String archetype) {
        return states.computeIfAbsent(key(owner, archetype), k -> new State());
    }

    /**
     * Runs the abilities of the given companion for this tick.
     *
     * @param owner    the player the companion belongs to
     * @param archetype the companion's archetype
     * @param npc      the companion itself, used for the messages it speaks
     */
    void tick(Player owner, String archetype, Mannequin npc) {
        long now = Bukkit.getCurrentTick();
        boolean fired = switch (archetype) {
            case "furina" -> tickFurina(owner, now);
            case "ei" -> tickEi(owner, now);
            case "columbina" -> tickColumbina(owner, now);
            case "escoffier" -> tickEscoffier(owner, now);
            case "yui" -> {
                tickYui(owner, npc, now);
                yield false;
            }
            case "sandrone" -> {
                tickSandrone(owner, npc, now);
                yield false;
            }
            default -> {
                // The other companions have no support ability of their own.
                yield false;
            }
        };
        if (fired && onAbilityFired != null) {
            onAbilityFired.fire(owner, archetype, npc.getLocation(), now);
        }
    }

    /**
     * Called when a companion's support ability actually did something.
     *
     * <p>Used to let the companion announce her own assist line at the moment her ability
     * fires, rather than every tick the condition happens to hold.</p>
     */
    @FunctionalInterface
    interface AbilityFired {
        /**
         * @param owner     the player the companion belongs to
         * @param archetype the companion's archetype
         * @param at        where the companion stands, so its voice comes from its own body
         * @param tick      the tick the ability fired on
         */
        void fire(Player owner, String archetype, org.bukkit.Location at, long tick);
    }

    /** The callback that is run when an ability fires. */
    private AbilityFired onAbilityFired;

    /**
     * Sets the callback that is run when an ability fires.
     *
     * @param callback receives the owner, the archetype, and the current tick
     */
    void onAbilityFired(AbilityFired callback) {
        this.onAbilityFired = callback;
    }

    /**
     * Furina: Water Breathing while the owner is swimming, Fire Resistance while the owner
     * is burning.
     *
     * <p>Both are granted for 10 seconds and may be granted again once 5 seconds have passed.
     * The effect is refreshed rather than stacked, so a long swim keeps the owner comfortable
     * without renewing the potion every tick.</p>
     *
     * @return true when an effect was granted on this tick
     */
    private boolean tickFurina(Player owner, long now) {
        State state = state(owner, "furina");
        boolean fired = false;
        if (owner.isInWater()) {
            if (state.lastWaterBreathingTick == Long.MIN_VALUE
                    || now - state.lastWaterBreathingTick >= FURINA_COOLDOWN_TICKS) {
                state.lastWaterBreathingTick = now;
                owner.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING,
                        FURINA_WATER_BREATHING_TICKS, 0, true, false, true));
                fired = true;
            }
        }
        if (owner.getFireTicks() > 0) {
            if (state.lastFireResistanceTick == Long.MIN_VALUE
                    || now - state.lastFireResistanceTick >= FURINA_COOLDOWN_TICKS) {
                state.lastFireResistanceTick = now;
                owner.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE,
                        FURINA_FIRE_RESISTANCE_TICKS, 0, true, false, true));
                fired = true;
            }
        }
        return fired;
    }

    /**
     * Ei: Strength after the owner takes their first kill.
     *
     * <p>The grant happens once per kill, not once per session: the strength is refreshed after
     * it wears off, but only when the owner has killed something again in the meantime.</p>
     *
     * @return true when the strength was granted on this tick
     */
    private boolean tickEi(Player owner, long now) {
        State state = state(owner, "ei");
        if (state.firstKillTick == Long.MIN_VALUE) {
            return false;
        }
        if (state.strengthGranted) {
            return false;
        }
        if (now - state.firstKillTick > EI_GRANT_WINDOW_TICKS) {
            return false;
        }
        state.strengthGranted = true;
        state.lastStrengthTick = now;
        owner.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH,
                EI_STRENGTH_TICKS, 0, true, false, true));
        return true;
    }

    /**
     * Columbina: Swiftness while a hostile mob is coming for the owner.
     *
     * <p>The effect is granted the moment such a mob is near, and it lasts 15 seconds. It is
     * granted again 5 seconds later, so a longer fight keeps the owner quick.</p>
     *
     * @return true when the effect was granted on this tick
     */
    private boolean tickColumbina(Player owner, long now) {
        State state = state(owner, "columbina");
        if (!hasThreateningMob(owner)) {
            return false;
        }
        if (state.lastSwiftnessTick == Long.MIN_VALUE
                || now - state.lastSwiftnessTick >= COLUMBINA_COOLDOWN_TICKS) {
            state.lastSwiftnessTick = now;
            owner.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,
                    COLUMBINA_SWIFTNESS_TICKS, 0, true, false, true));
            return true;
        }
        return false;
    }

    /**
     * Escoffier: Slowness on the hostile mobs that are coming for the owner.
     *
     * <p>The slow is applied to the mobs themselves rather than to the owner, which is what
     * makes her different from Columbina: she takes the fight away from her owner instead of
     * helping the owner run.</p>
     *
     * @return true when the slow was applied on this tick
     */
    private boolean tickEscoffier(Player owner, long now) {
        State state = state(owner, "escoffier");
        if (state.lastSlowTick != Long.MIN_VALUE
                && now - state.lastSlowTick < ESCOFFIER_COOLDOWN_TICKS) {
            return false;
        }
        java.util.List<LivingEntity> mobs = threateningMobs(owner);
        if (mobs.isEmpty()) {
            return false;
        }
        state.lastSlowTick = now;
        for (LivingEntity mob : mobs) {
            mob.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,
                    ESCOFFIER_SLOWNESS_TICKS, 0, true, false, true));
        }
        return true;
    }

    /**
     * Records the owner's first kill, so Ei can grant her strength.
     *
     * <p>Called when the owner kills a mob. The first kill starts the window in which Ei grants
     * Strength; later kills do nothing on their own, because the strength is only ever granted
     * once for that kill.</p>
     */
    void onOwnerKill(Player owner, String archetype, long currentTick) {
        if (!"ei".equalsIgnoreCase(archetype)) {
            return;
        }
        State state = state(owner, "ei");
        if (state.firstKillTick != Long.MIN_VALUE) {
            // The owner has killed before. The strength is refreshed only after it has worn off
            // and another kill comes in, so the effect is not repeated for a single kill.
            if (state.strengthGranted && state.lastStrengthTick != Long.MIN_VALUE
                    && currentTick - state.lastStrengthTick < EI_STRENGTH_TICKS) {
                return;
            }
            state.firstKillTick = currentTick;
            state.strengthGranted = false;
            return;
        }
        state.firstKillTick = currentTick;
        state.strengthGranted = false;
    }

    /** Whether a hostile mob near the owner is targeting the owner. */
    private static boolean hasThreateningMob(Player owner) {
        return !threateningMobs(owner).isEmpty();
    }

    /** The hostile mobs near the owner that are targeting the owner. */
    private static java.util.List<LivingEntity> threateningMobs(Player owner) {
        java.util.List<LivingEntity> result = new java.util.ArrayList<>();
        for (Entity entity : owner.getNearbyEntities(THREAT_RADIUS, THREAT_RADIUS, THREAT_RADIUS)) {
            if (!(entity instanceof LivingEntity living) || living.isDead() || !living.isValid()) {
                continue;
            }
            if (!(living instanceof org.bukkit.entity.Mob mob)) {
                continue;
            }
            if (!CompanionManager.isHostileMob(living)) {
                continue;
            }
            LivingEntity target = mob.getTarget();
            if (target != null && target.getUniqueId().equals(owner.getUniqueId())) {
                result.add(living);
            }
        }
        return result;
    }

    /** Runs Yui/Sandrone follow-up timers once per second per owner. */
    void tickScheduled(Player owner, long now) {
        if (owner == null || !owner.isOnline()) {
            return;
        }
        UUID id = owner.getUniqueId();
        YuiSession yui = yuiSessions.get(id);
        if (yui != null && yui.followAt != Long.MIN_VALUE && now >= yui.followAt) {
            yui.followAt = Long.MIN_VALUE;
            if (legacySupport != null) {
                legacySupport.followUp(owner, "yui", null);
            }
        }
        SandroneSession sand = sandSessions.get(id);
        if (sand != null && sand.followAt != Long.MIN_VALUE && now >= sand.followAt) {
            sand.followAt = Long.MIN_VALUE;
            if (legacySupport != null) {
                legacySupport.followUp(owner, "sandrone", null);
            }
        }
    }

    private void tickYui(Player owner, Mannequin npc, long now) {
        if (!owner.getScoreboardTags().contains(CompanionManager.activeTag("yui"))) {
            return;
        }
        // While the old Skript script is still loaded it does this job too: it heals the owner,
        // plays the heal sound and says the line. Doing both at once would heal twice as fast
        // and say every line twice, so the plugin keeps quiet until the script is gone.
        if (LegacySkriptRetirement.legacyStillRunning()) {
            return;
        }
        YuiSession session = yuiSessions.computeIfAbsent(owner.getUniqueId(), ignored -> new YuiSession());
        if (session.nextHealTick == Long.MIN_VALUE) {
            session.nextHealTick = now + YUI_HEAL_INTERVAL_TICKS;
            return;
        }
        if (now < session.nextHealTick || owner.isDead()) {
            return;
        }
        session.nextHealTick = now + YUI_HEAL_INTERVAL_TICKS;
        double max = owner.getAttribute(Attribute.MAX_HEALTH).getValue();
        if (owner.getHealth() >= max) {
            return;
        }
        owner.setHealth(Math.min(max, owner.getHealth() + 2.0));
        owner.playSound(owner.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        session.messageCounter++;
        session.followCounter++;
        boolean full = owner.getHealth() >= max;
        boolean say = full || session.messageCounter >= MESSAGE_EVERY;
        if (say) {
            session.messageCounter = 0;
            if (legacySupport != null) {
                legacySupport.yuiHeal(owner, npc.getLocation());
            }
        }
        boolean follow = full || session.followCounter >= FOLLOWUP_EVERY;
        if (follow) {
            session.followCounter = 0;
            session.followAt = now + FOLLOWUP_DELAY_TICKS;
        }
    }

    private void tickSandrone(Player owner, Mannequin npc, long now) {
        if (!owner.getScoreboardTags().contains(CompanionManager.activeTag("sandrone"))) {
            return;
        }
        // The old Skript script grants the absorption too while it is still loaded, so the plugin
        // waits for it to be retired rather than granting it twice.
        if (LegacySkriptRetirement.legacyStillRunning()) {
            return;
        }
        SandroneSession session = sandSessions.computeIfAbsent(owner.getUniqueId(), ignored -> new SandroneSession());
        if (session.nextAbsorbTick == Long.MIN_VALUE) {
            session.nextAbsorbTick = now + SANDRONE_ABSORB_INTERVAL_TICKS;
            return;
        }
        if (now < session.nextAbsorbTick || owner.isDead()) {
            return;
        }
        session.nextAbsorbTick = now + SANDRONE_ABSORB_INTERVAL_TICKS;
        owner.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION,
                SANDRONE_ABSORB_DURATION_TICKS, SANDRONE_ABSORB_AMPLIFIER, true, false, true));
        session.messageCounter++;
        session.followCounter++;
        boolean say = session.lastVoiceTick == Long.MIN_VALUE
                || now - session.lastVoiceTick >= SANDRONE_VOICE_COOLDOWN_TICKS;
        if (say && legacySupport != null) {
            session.messageCounter = 0;
            legacySupport.sandroneAbsorption(owner, npc.getLocation());
            session.lastVoiceTick = now;
        }
        boolean follow = session.followCounter >= FOLLOWUP_EVERY;
        if (follow) {
            session.followCounter = 0;
            session.followAt = now + FOLLOWUP_DELAY_TICKS;
        }
    }

    /** Forgets every owner's state, so a fresh session starts clean. */
    void clear() {
        states.clear();
        yuiSessions.clear();
        sandSessions.clear();
    }
}
