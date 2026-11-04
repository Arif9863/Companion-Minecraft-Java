package com.arif.companion;

import org.bukkit.Bukkit;
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

    /** Forgets every owner's state, so a fresh session starts clean. */
    void clear() {
        states.clear();
    }
}
