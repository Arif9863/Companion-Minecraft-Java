package com.arif.companion;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

/**
 * Reusable trident projectile mechanics, ready for a future ranged companion archetype.
 *
 * <p>Nothing in the plugin calls this yet: Yui, Sandrone, and Eris all fight in melee. The
 * helper exists so a future companion can opt into ranged combat without re-deriving the
 * projectile physics, which is where the first attempt at this went wrong.</p>
 *
 * <p>The trajectory is solved by simulation rather than by a closed-form approximation.
 * Vanilla applies, per tick and in this order: the projectile moves with its current velocity,
 * the velocity is damped by the arrow's air drag of {@code 0.99}, and only then gravity of
 * {@code 0.05} blocks per tick² is added. An earlier version of this code assumed a gravity of
 * {@code 0.25} and lifted the aim point by a quarter of a block per tick squared, which sent the
 * trident several blocks into the sky instead of at the target. The simulation below reproduces
 * the real order, so the aim point is where the trident actually arrives.</p>
 */
final class RangedTridentAttack {

    /** Scoreboard tag identifying a companion's thrown trident. */
    static final String PROJECTILE_TAG = "companion_ranged_trident";

    /** Vanilla arrow/trident air drag, applied to the velocity every tick. */
    private static final double AIR_DRAG = 0.99;

    /** Vanilla arrow/trident gravity in blocks per tick². */
    private static final double GRAVITY = 0.05;

    /** Ticks a thrown trident lives before it disappears on its own. */
    private static final int PROJECTILE_LIFETIME_TICKS = 60;

    /** Upper bound for the simulated flight, so a bad input can never loop for long. */
    private static final int MAX_SIMULATED_TICKS = 200;

    private RangedTridentAttack() {
    }

    /**
     * Launches a trident toward a living target.
     *
     * @param companion mannequin that plays the throw animation
     * @param owner player credited as the projectile's shooter, so the damage counts as his own
     * @param target the mob to hit
     * @param range maximum horizontal throw distance; a target beyond it is refused
     * @param speed initial projectile speed in blocks per tick
     * @param damage projectile base damage
     * @return the launched trident, or {@code null} when the shot is not possible
     */
    static Trident launch(Mannequin companion, Player owner, LivingEntity target,
                          double range, double speed, double damage) {
        if (companion == null || owner == null || target == null
                || !target.isValid() || target.isDead()
                || companion.getWorld() != target.getWorld()
                || owner.getWorld() != companion.getWorld()
                || speed <= 0 || range <= 0 || damage <= 0) {
            return null;
        }

        Location eye = companion.getEyeLocation();
        Location targetLocation = target.getLocation();
        double dx = targetLocation.getX() - eye.getX();
        double dz = targetLocation.getZ() - eye.getZ();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        if (horizontalDistance < 0.05 || horizontalDistance > range) {
            return null;
        }

        Vector velocity = solveVelocity(eye, targetLocation, target.getHeight(), speed);
        if (velocity == null) {
            return null;
        }

        companion.swingMainHand();
        Trident projectile = companion.getWorld().spawn(eye, Trident.class, spawned -> {
            spawned.setShooter(owner);
            spawned.setDamage(damage);
            // The weapon item is what the projectile carries: the trident reads it for its
            // enchantments, so a future archetype can hand out an enchanted trident. It is
            // also what would drop if the projectile were allowed to be picked up — and with
            // the pickup disabled below, it never does.
            spawned.setItemStack(new ItemStack(Material.TRIDENT));
            // A thrown weapon, not loot: nobody may pick it up, and it cannot drop an item
            // when it expires either.
            spawned.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
            // A thrown trident flies back to its thrower. A mannequin has no inventory to catch
            // it, so loyalty is switched off and the trident simply expires.
            spawned.setLoyaltyLevel(0);
            spawned.setLifetimeTicks(PROJECTILE_LIFETIME_TICKS);
            spawned.setVelocity(velocity);
        });
        projectile.addScoreboardTag(PROJECTILE_TAG);
        return projectile;
    }

    /**
     * Finds the launch velocity whose simulated flight lands on the target.
     *
     * <p>The vertical component is solved by trying every plausible pitch: for each one the
     * flight is simulated with vanilla's per-tick order (move, then drag, then gravity) until
     * it passes the target's distance, and the pitch whose flight ends closest to the target
     * wins. This keeps the aim honest without depending on a closed-form ballistic formula that
     * would have to mirror Minecraft's discrete integration exactly.</p>
     *
     * @return the launch velocity, or {@code null} when no pitch reaches the target
     */
    private static Vector solveVelocity(Location eye, Location targetLocation, double targetHeight,
                                        double speed) {
        double toTargetX = targetLocation.getX() - eye.getX();
        double toTargetY = targetLocation.getY() + targetHeight * 0.5 - eye.getY();
        double toTargetZ = targetLocation.getZ() - eye.getZ();
        double horizontal = Math.sqrt(toTargetX * toTargetX + toTargetZ * toTargetZ);
        if (horizontal < 1.0E-4) {
            return null;
        }
        // Horizontal direction of the shot; the pitch decides how high it arcs.
        double dirX = toTargetX / horizontal;
        double dirZ = toTargetZ / horizontal;
        // The height the projectile has to reach at the target's distance.
        double aimHeight = toTargetY;

        Vector best = null;
        double bestError = Double.MAX_VALUE;
        // One degree per step from a low lob up to a steep throw.
        for (int degrees = 0; degrees <= 80; degrees++) {
            double pitch = Math.toRadians(degrees);
            double horizontalSpeed = Math.cos(pitch) * speed;
            double verticalSpeed = Math.sin(pitch) * speed;
            if (horizontalSpeed < 1.0E-4) {
                continue;
            }
            if (horizontal / horizontalSpeed > MAX_SIMULATED_TICKS) {
                continue;
            }

            // Simulate the flight with vanilla's per-tick order: move, then air drag, then
            // gravity. The projectile is tracked until it has covered the target's distance.
            double x = eye.getX();
            double y = eye.getY();
            double z = eye.getZ();
            double vx = dirX * horizontalSpeed;
            double vy = verticalSpeed;
            double vz = dirZ * horizontalSpeed;
            double travelled = 0.0;
            for (int tick = 0; tick < MAX_SIMULATED_TICKS; tick++) {
                x += vx;
                y += vy;
                z += vz;
                travelled += horizontalSpeed;
                vx *= AIR_DRAG;
                vy *= AIR_DRAG;
                vz *= AIR_DRAG;
                vy -= GRAVITY;
                if (travelled >= horizontal) {
                    break;
                }
            }
            // The height error is what decides the shot. The horizontal error stays near zero
            // because the horizontal speed is chosen for this exact distance.
            double heightError = Math.abs(y - (eye.getY() + aimHeight));
            double overshoot = Math.max(0.0, travelled - horizontal);
            double error = heightError + overshoot * 0.5;
            if (error < bestError) {
                bestError = error;
                best = new Vector(dirX * horizontalSpeed, verticalSpeed, dirZ * horizontalSpeed);
            }
        }
        return best;
    }
}
