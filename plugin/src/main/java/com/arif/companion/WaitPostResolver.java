package com.arif.companion;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;

/**
 * Works out where a companion may actually appear when it comes back to the post it was left at.
 *
 * <p>The post is stored as the position the companion was standing on. That position is not
 * always a place a companion can be put back: the height may point at the free space above the
 * floor, or at the block the companion was standing on — a slab, a stair or a carpet. The second
 * reading matters: a carpet is only a sixteenth of a block tall, but it still fills its own
 * block, so a companion left standing on one was recorded as being inside that block.</p>
 *
 * <p>When the stored place did not fit, the old answer was the highest block of the column. That
 * put a companion that had been left in a house on its roof, because the roof is the highest
 * block there. The search here stays on the ground the owner left the companion on: the same
 * column first, then a little lower, then sideways — and it only leaves the house when the
 * floor it stood on is really gone.</p>
 */
final class WaitPostResolver {

    /** How many blocks down the search may look for a floor. */
    private static final int SEARCH_DEPTH = 4;

    /** How many blocks sideways the search may look for a floor. */
    private static final int SEARCH_RADIUS = 2;

    private WaitPostResolver() {
    }

    /**
     * The place a companion may appear at, given the post it was left at.
     *
     * @param saved the post as it was saved; may be null
     * @return a standing position at or near the post, or null when nothing near it fits
     */
    static Location resolve(Location saved) {
        if (saved == null || saved.getWorld() == null) {
            return null;
        }
        World world = saved.getWorld();
        // The post itself: it is still the right place unless the floor under it changed.
        Location spot = standingAt(world, saved);
        if (spot != null) {
            return spot;
        }
        // Down the same column. The floor under the post may have been removed, and the
        // companion belongs on what is left of it — never on the roof above it.
        for (int down = 1; down <= SEARCH_DEPTH; down++) {
            spot = standingAt(world, saved.clone().subtract(0.0, down, 0.0));
            if (spot != null) {
                return spot;
            }
        }
        // Sideways, at the post's own height and one block lower.
        for (int radius = 1; radius <= SEARCH_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    for (int down = 0; down <= 1; down++) {
                        Location candidate = saved.clone().add(dx, -down, dz);
                        spot = standingAt(world, candidate);
                        if (spot != null) {
                            return spot;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * The position a companion stands at when it is put down at that spot.
     *
     * <p>The stored height may point at the free space the companion was standing in, or at the
     * block it was standing on. Both readings are tried: the first one is the usual case, the
     * second one is what a companion on a carpet or a slab needs.</p>
     *
     * @return the standing position, or null when the spot does not fit
     */
    static Location standingAt(World world, Location spot) {
        if (world == null || spot == null) {
            return null;
        }
        int x = spot.getBlockX();
        int z = spot.getBlockZ();
        int y = spot.getBlockY();
        Block feet = world.getBlockAt(x, y, z);
        // The height points at the free space the companion was standing in: its feet rest
        // there, so there has to be a floor below to rest on.
        if (isFree(feet) && supports(world.getBlockAt(x, y - 1, z))) {
            Location standing = standingOn(world, spot, y);
            if (standing != null) {
                return standing;
            }
        }
        // The height points at the block the companion was standing on: a slab, a stair or a
        // carpet. Its feet belong on top of that block, at the height the shape reaches.
        double top = shapeTop(feet, y);
        if (top > y) {
            Location standing = standingOn(world, spot, top);
            if (standing != null) {
                return standing;
            }
        }
        return null;
    }

    /**
     * A standing position whose feet are at the given height.
     *
     * @return the standing position, or null when the body does not fit there
     */
    private static Location standingOn(World world, Location spot, double feetY) {
        int x = spot.getBlockX();
        int z = spot.getBlockZ();
        int feetBlockY = (int) Math.floor(feetY);
        Block feet = world.getBlockAt(x, feetBlockY, z);
        // Nothing may stick up through the floor the feet rest on. A door is the exception:
        // the companion opens it and walks through, so its shape does not matter.
        if (!PathPlanner.isOpenable(feet)) {
            for (BoundingBox box : feet.getCollisionShape().getBoundingBoxes()) {
                if (absoluteTop(box, feetBlockY) > feetY + 0.0001) {
                    return null;
                }
            }
        }
        // The block above the feet has to be free for the head.
        Block head = world.getBlockAt(x, feetBlockY + 1, z);
        if (!isFree(head)) {
            return null;
        }
        Location standing = new Location(world, spot.getX(), feetY, spot.getZ());
        standing.setYaw(spot.getYaw());
        standing.setPitch(spot.getPitch());
        return standing;
    }

    /**
     * Whether a block is free for the companion to be in.
     *
     * <p>A door and a fence gate count as free: the companion opens them and walks through.
     * Water counts as free as well — a companion swims there. Lava does not: nothing stands
     * in lava.</p>
     */
    private static boolean isFree(Block block) {
        if (block.getType() == Material.LAVA) {
            return false;
        }
        if (block.getType() == Material.WATER) {
            return true;
        }
        if (PathPlanner.isOpenable(block)) {
            return true;
        }
        return block.getCollisionShape().getBoundingBoxes().isEmpty();
    }

    /**
     * Whether the companion could stand on this block.
     *
     * <p>A fence, a wall and a glass pane are only a barrier: they are not a floor, and a
     * companion that treated one as a floor would end up standing on top of the fence. Lava
     * is no floor either, and water is a floor the companion swims over.</p>
     */
    private static boolean supports(Block block) {
        if (block.getType() == Material.LAVA) {
            return false;
        }
        if (block.getType() == Material.WATER) {
            return true;
        }
        if (PathPlanner.isBarrier(block)) {
            return false;
        }
        return !block.getCollisionShape().getBoundingBoxes().isEmpty();
    }

    /**
     * The height of the top of a block, in world space.
     *
     * <p>A slab is half a block high, a carpet a sixteenth, and a full block a whole one. The
     * feet of a companion standing on them rest at exactly that height.</p>
     */
    private static double shapeTop(Block block, int blockY) {
        double top = 0.0;
        for (BoundingBox box : block.getCollisionShape().getBoundingBoxes()) {
            top = Math.max(top, absoluteTop(box, blockY));
        }
        return top;
    }

    /**
     * The top of a single shape, in world space.
     *
     * <p>The shape of a block may be given in the block's own space or in the world's, and the
     * two are told apart by where the shape starts: a shape in world space begins at the block
     * it belongs to, one in the block's own space begins at zero.</p>
     */
    private static double absoluteTop(BoundingBox box, int blockY) {
        boolean inWorldSpace = box.getMinY() >= blockY && box.getMaxY() <= blockY + 1.0;
        return inWorldSpace ? box.getMaxY() : blockY + box.getMaxY();
    }
}
