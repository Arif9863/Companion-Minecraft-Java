package com.arif.companion;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Whether a companion can actually hit what it is aiming at.
 *
 * <p>Columbina and Escoffier throw their trident. A throw does not stop at a wall, so a
 * companion that throws at a mob it cannot see sends the trident into the wall between them
 * and the shot is wasted. This checks the line between the thrower and the target before the
 * weapon leaves the hand.</p>
 *
 * <p>The check is done by walking the line in small steps rather than by a ray trace, because
 * a ray trace reports the first block it touches — a flower, a torch or a blade of grass on
 * the floor would count as a wall. Only blocks that really stand in the way count here.</p>
 */
final class CompanionLineOfSight {

    /**
     * How far apart the line is sampled, in blocks.
     *
     * <p>Half a block is fine enough to notice a wall and coarse enough to keep the check
     * cheap: it runs a couple of dozen block lookups at most.</p>
     */
    private static final double STEP = 0.5;

    /**
     * How far the line is allowed to reach past the target.
     *
     * <p>The target is a mob, not a point. Its body has a height, so the line is checked
     * against the middle of the body and a little beyond it: without that, a companion
     * standing right next to a mob would always be blocked by the mob's own legs.</p>
     */
    private static final double BEYOND_TARGET = 1.5;

    private CompanionLineOfSight() {
    }

    /**
     * Whether the companion can see the given target.
     *
     * @param companion the thrower
     * @param target    the mob it wants to hit
     * @return true when nothing solid stands between them
     */
    static boolean canSee(Mannequin companion, LivingEntity target) {
        if (companion == null || target == null) {
            return false;
        }
        World world = companion.getWorld();
        if (world == null || !world.equals(target.getWorld())) {
            return false;
        }
        Location eye = companion.getEyeLocation();
        Location targetLocation = target.getLocation();
        // The middle of the mob's body is what the shot is aimed at.
        double targetY = targetLocation.getY() + target.getHeight() * 0.5;
        return clearBetween(world, eye.getX(), eye.getY(), eye.getZ(), targetLocation.getX(),
                targetY, targetLocation.getZ());
    }

    /**
     * Whether the line between the companion's eye and the given point is free of blocks.
     *
     * @param world the world both points are in
     * @param fromX the thrower's eye X
     * @param fromY the thrower's eye Y
     * @param fromZ the thrower's eye Z
     * @param toX   the target X
     * @param toY   the target Y
     * @param toZ   the target Z
     * @return true when nothing solid stands in the way
     */
    static boolean clearBetween(World world, double fromX, double fromY, double fromZ,
                                double toX, double toY, double toZ) {
        double dx = toX - fromX;
        double dy = toY - fromY;
        double dz = toZ - fromZ;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance < 1.0E-3) {
            return true;
        }
        // How far past the target the line is still checked. The target's own body must not
        // count as a wall, so the walk stops a little short of it.
        double limit = distance + BEYOND_TARGET;
        int steps = (int) Math.ceil(limit / STEP);
        // The last block height the line was checked at. A line that runs through a gap
        // between two blocks has to be checked at both of them, or a window would be missed:
        // a level line sits in the lower half of its block, but a line that dips or rises
        // crosses into the next one.
        int lastY = Integer.MIN_VALUE;
        for (int step = 1; step < steps; step++) {
            double fraction = (double) step / steps * limit / distance;
            if (fraction > distance && fraction - distance > BEYOND_TARGET) {
                break;
            }
            double sampleX = fromX + dx * fraction;
            double sampleY = fromY + dy * fraction;
            double sampleZ = fromZ + dz * fraction;
            int x = (int) Math.floor(sampleX);
            int y = (int) Math.floor(sampleY);
            int z = (int) Math.floor(sampleZ);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            // Every block height the line passes through at this point is checked, so a
            // window opening is found even when the line runs along the top of its lower
            // block.
            for (int checkY = Math.min(lastY == Integer.MIN_VALUE ? y : lastY, y);
                    checkY <= Math.max(lastY == Integer.MIN_VALUE ? y : lastY, y);
                    checkY++) {
                if (blocksTheShot(world.getBlockAt(x, checkY, z))) {
                    return false;
                }
            }
            lastY = y;
        }
        return true;
    }

    /**
     * Whether a block stands in the way of a shot.
     *
     * <p>Blocks that do not fill their space are ignored: a flower, a torch, a blade of grass
     * or a carpet does not stop a trident in a way that matters, and treating them as walls
     * would leave a companion standing in the open with a clear target, refusing to throw.</p>
     */
    private static boolean blocksTheShot(Block block) {
        Material type = block.getType();
        if (type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR) {
            return false;
        }
        if (type == Material.WATER || type == Material.LAVA) {
            // A fluid does not stop a thrown trident.
            return false;
        }
        if (type == Material.TALL_GRASS || type == Material.SHORT_GRASS
                || type == Material.FERN || type == Material.LARGE_FERN
                || type == Material.DEAD_BUSH || type == Material.SNOW
                || type == Material.LIGHTNING_ROD || type == Material.TORCH
                || type == Material.WALL_TORCH || type == Material.SOUL_TORCH
                || type == Material.SOUL_WALL_TORCH || type == Material.REDSTONE_TORCH
                || type == Material.REDSTONE_WALL_TORCH
                || type == Material.SWEET_BERRY_BUSH || type == Material.VINE
                || type == Material.GLOW_LICHEN || type == Material.SEA_PICKLE
                || type == Material.LILY_PAD || type == Material.SUGAR_CANE
                || type == Material.FIRE || type == Material.SOUL_FIRE) {
            return false;
        }
        // A carpet lies flat on the ground and never stands between two points that are
        // both above the floor.
        if (PathPlanner.isCarpet(block)) {
            return false;
        }
        // A door or a fence gate is not a wall: the companion opens it on the way through, so
        // it does not stop the shot either. The companion may have to walk over and open it
        // first, but the line itself is passable.
        if (PathPlanner.isOpenable(block)) {
            return false;
        }
        // Anything that actually occupies space blocks the shot. The collision shape is the
        // honest answer: a slab, a stair and a fence all take up room, a flower does not.
        return !block.getCollisionShape().getBoundingBoxes().isEmpty();
    }

    /**
     * Whether a companion at the given spot could hit the target from there.
     *
     * <p>Used when the companion looks for a place to throw from: a spot that is free and
     * has a clear line is what it is looking for.</p>
     */
    static boolean canSeeFrom(World world, Location eye, LivingEntity target) {
        if (world == null || target == null || eye == null) {
            return false;
        }
        Location targetLocation = target.getLocation();
        double targetY = targetLocation.getY() + target.getHeight() * 0.5;
        return clearBetween(world, eye.getX(), eye.getY(), eye.getZ(), targetLocation.getX(),
                targetY, targetLocation.getZ());
    }

    /**
     * Whether the spot is free for the companion to stand on.
     *
     * <p>A spot that is inside a block is no use, even if the line from it would be clear.</p>
     */
    static boolean standable(Location spot) {
        if (spot == null || spot.getWorld() == null) {
            return false;
        }
        Block feet = spot.getBlock();
        Block head = spot.getWorld().getBlockAt(spot.getBlockX(), spot.getBlockY() + 1,
                spot.getBlockZ());
        return feet.isPassable() && head.isPassable();
    }
}
