package com.arif.companion;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;

/**
 * Lightweight A* pathfinder over the block grid.
 *
 * <p>Companions are {@code Mannequin} entities, which are not mobs and therefore have no vanilla
 * pathfinder, so routes are computed here and then walked with velocity. The search is deliberately
 * small: a bounded node budget and search radius keep it cheap enough to run on the server thread
 * for a couple of companions at a time.</p>
 *
 * <p>Occupancy is tested with collision shapes rather than {@code isPassable()} so partial blocks
 * such as stairs and slabs are handled correctly, and chunks that are not loaded are never touched —
 * planning never forces a chunk load.</p>
 */
final class PathPlanner {

    /** Maximum number of nodes expanded in a single search. */
    static final int MAX_NODES = 2048;

    /** Maximum horizontal distance considered from the search origin. */
    static final int MAX_RADIUS = 48;

    /** Maximum safe drop while following: never descend more than one block in a single step. */
    private static final int MAX_DROP = 1;
    /** Maximum vertical swimming neighbors explored per horizontal step. */
    private static final int MAX_SWIM_VERTICAL_STEP = 1;

    private PathPlanner() {
    }

    /** A grid node, identified by the block the companion would stand on. */
    private record Node(int x, int y, int z) {}

    /**
     * A block coordinate in plain form, so callers can compare positions outside this class.
     */
    record BlockPos(int x, int y, int z) {}

    /** An open-set candidate, ordered by its heuristic estimate so A* expands the best node first. */
    private record Entry(Node node, double f, double g) implements Comparable<Entry> {
        @Override
        public int compareTo(Entry other) {
            return Double.compare(this.f, other.f);
        }
    }

    /**
     * Computes a route from the companion to the owner.
     *
     * @param world    world both entities are in
     * @param start    current companion position
     * @param goal     desired owner position
     * @param radius   horizontal search radius in blocks
     * @return waypoint centres from start towards the goal, or an empty list when no route exists
     */
    static List<Vector> findPath(World world, Location start, Location goal, int radius) {
        Node startNode = toNode(start);
        Node goalNode = toNode(goal);

        if (startNode.equals(goalNode)) {
            return List.of();
        }
        // A goal inside a solid block cannot be reached directly; aim for the closest free spot
        // below it, which is where the owner normally stands.
        Node effectiveGoal = goalNode;
        if (!isStandable(world, goalNode)) {
            Node below = findNearestStandable(world, goalNode, 2);
            if (below == null) {
                return List.of();
            }
            effectiveGoal = below;
        }

        Map<Node, Double> gScore = new HashMap<>();
        Map<Node, Node> cameFrom = new HashMap<>();
        Set<Node> closed = new HashSet<>();

        gScore.put(startNode, 0.0);
        // Priority queue ordered by f: A* must expand the lowest-estimate node first.
        Queue<Entry> open = new PriorityQueue<>();
        open.add(new Entry(startNode, heuristic(startNode, effectiveGoal), 0.0));

        int expanded = 0;
        while (!open.isEmpty() && expanded < MAX_NODES) {
            Entry current = open.poll();
            Node node = current.node();
            if (closed.contains(node)) {
                continue;
            }
            closed.add(node);
            expanded++;

            if (node.equals(effectiveGoal)) {
                return buildWaypoints(cameFrom, node, start);
            }

            for (Node neighbour : neighbours(world, node, radius, false)) {
                if (closed.contains(neighbour)) {
                    continue;
                }
                double tentativeG = current.g() + stepCost(node, neighbour);
                Double known = gScore.get(neighbour);
                if (known == null || tentativeG < known) {
                    gScore.put(neighbour, tentativeG);
                    cameFrom.put(neighbour, node);
                    open.add(new Entry(neighbour,
                            tentativeG + heuristic(neighbour, effectiveGoal), tentativeG));
                }
            }
        }
        return List.of();
    }

    /**
     * Computes the small area the owner can walk to, ignoring locked doors and beds.
     *
     * <p>Used to constrain a close-mode target to the owner's side of walls. The search does not
     * touch unloaded chunks and has a bounded node budget because it runs on the server thread.</p>
     *
     * <p>Beds count as walkable here: the owner can step onto a bed, so the space around it
     * belongs to the room they are in. Without this, a companion summoned while its owner stands
     * on a bed would see an empty world and fall back to no target at all.</p>
     *
     * @param origin  where the owner is standing
     * @param radius  horizontal search radius in blocks
     * @param nodeBudget upper bound on expanded nodes, keeping the search cheap
     */
    static Set<BlockPos> ownerSpace(World world, Location origin, int radius, int nodeBudget) {
        Node start = toNode(origin);
        if (!isOwnerStandable(world, start)) {
            Node adjusted = findNearestStandable(world, start, 2);
            if (adjusted == null) {
                return Set.of();
            }
            start = adjusted;
        }
        Set<Node> visited = new HashSet<>();
        Queue<Node> queue = new ArrayDeque<>();
        visited.add(start);
        queue.add(start);
        int expanded = 0;
        while (!queue.isEmpty() && expanded < nodeBudget) {
            Node current = queue.remove();
            expanded++;
            for (Node next : neighbours(world, current, radius, true)) {
                if (visited.add(next)) {
                    queue.add(next);
                }
            }
        }
        Set<BlockPos> result = new HashSet<>();
        for (Node node : visited) {
            result.add(new BlockPos(node.x(), node.y(), node.z()));
        }
        return result;
    }

    /**
     * Whether a block counts as walkable for the owner-space search.
     *
     * <p>Beds are included because the owner can step onto them; everything else follows the
     * normal rules, so walls still divide the space.</p>
     */
    private static boolean isOwnerStandable(World world, Node node) {
        if (!isChunkLoaded(world, node)) {
            return false;
        }
        Block feet = world.getBlockAt(node.x(), node.y(), node.z());
        if (feet.getType() == Material.LAVA) {
            return false;
        }
        if (isBed(feet)) {
            return true;
        }
        if (!isPassable(world, node)) {
            return false;
        }
        Block head = world.getBlockAt(node.x(), node.y() + 1, node.z());
        if (head.getType() == Material.LAVA) {
            return false;
        }
        return isOpenable(head) || hasNoCollision(head);
    }

    /**
     * Computes the blocks a companion can actually reach from its current position.
     *
     * <p>Used to judge a close-mode target without running a separate A* search per candidate:
     * one flood fill replaces dozens of searches, which matters because the search runs on the
     * server thread. The budget is generous but bounded.</p>
     *
     * @param origin  where the companion stands now
     * @param radius  horizontal search radius in blocks
     * @param nodeBudget upper bound on expanded nodes
     */
    static Set<BlockPos> reachableArea(World world, Location origin, int radius, int nodeBudget) {
        Node start = toNode(origin);
        if (!isStandable(world, start)) {
            Node adjusted = findNearestStandable(world, start, 2);
            if (adjusted == null) {
                return Set.of();
            }
            start = adjusted;
        }
        Set<Node> visited = new HashSet<>();
        Queue<Node> queue = new ArrayDeque<>();
        visited.add(start);
        queue.add(start);
        int expanded = 0;
        while (!queue.isEmpty() && expanded < nodeBudget) {
            Node current = queue.remove();
            expanded++;
            for (Node next : neighbours(world, current, radius, false)) {
                if (visited.add(next)) {
                    queue.add(next);
                }
            }
        }
        Set<BlockPos> result = new HashSet<>();
        for (Node node : visited) {
            result.add(new BlockPos(node.x(), node.y(), node.z()));
        }
        return result;
    }

    /** Whether the block is a bed, which the owner may step onto. */
    private static boolean isBed(Block block) {
        return block.getBlockData() instanceof org.bukkit.block.data.type.Bed;
    }

    /** Expands a node into reachable neighbours: flat moves, one-block step up, and drops. */
    private static List<Node> neighbours(World world, Node node, int radius, boolean relaxed) {
        List<Node> result = new ArrayList<>(12);
        int[][] horizontal = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

        for (int[] offset : horizontal) {
            int nx = node.x() + offset[0];
            int nz = node.z() + offset[1];

            // Walk one block up (a step the companion can jump).
            Node stepUp = new Node(nx, node.y() + 1, nz);
            if ((relaxed ? isOwnerStandable(world, stepUp) : isStandable(world, stepUp)) && withinRadius(node, stepUp, radius)) {
                result.add(stepUp);
            }

            // Same level.
            Node flat = new Node(nx, node.y(), nz);
            if ((relaxed ? isOwnerStandable(world, flat) : isStandable(world, flat)) && withinRadius(node, flat, radius)) {
                result.add(flat);
            }

            // Falling down one block at most. A deeper drop is never treated as a walkable route.
            Node down = new Node(nx, node.y() - 1, nz);
            if ((relaxed ? isOwnerStandable(world, down) : isStandable(world, down)) && withinRadius(node, down, radius)) {
                result.add(down);
            }

            // Swimming: while submerged, allow gentle vertical movement so the companion can
            // follow the owner to the surface or dive a little, instead of being stuck.
            if (isWater(world, node)) {
                Node swimUp = new Node(nx, node.y() + MAX_SWIM_VERTICAL_STEP, nz);
                if ((relaxed ? isOwnerStandable(world, swimUp) : isStandable(world, swimUp)) && withinRadius(node, swimUp, radius)) {
                    result.add(swimUp);
                }
                Node swimDown = new Node(nx, node.y() - MAX_SWIM_VERTICAL_STEP, nz);
                if ((relaxed ? isOwnerStandable(world, swimDown) : isStandable(world, swimDown)) && withinRadius(node, swimDown, radius)) {
                    result.add(swimDown);
                }
            }
        }

        // Vertical column movement while fully in water: surface or sink in place.
        if (isWater(world, node)) {
            Node surfaceUp = new Node(node.x(), node.y() + 1, node.z());
            if ((relaxed ? isOwnerStandable(world, surfaceUp) : isStandable(world, surfaceUp)) && withinRadius(node, surfaceUp, radius)) {
                result.add(surfaceUp);
            }
            Node sinkDown = new Node(node.x(), node.y() - 1, node.z());
            if ((relaxed ? isOwnerStandable(world, sinkDown) : isStandable(world, sinkDown)) && withinRadius(node, sinkDown, radius)) {
                result.add(sinkDown);
            }
        }
        return result;
    }

    /** Step cost: horizontal moves cost 1, step up and drops cost a bit more. */
    private static double stepCost(Node from, Node to) {
        double base = 1.0;
        if (to.y() > from.y()) {
            return base + 0.6;
        }
        if (to.y() < from.y()) {
            return base + Math.abs(from.y() - to.y()) * 0.25;
        }
        return base;
    }

    private static double heuristic(Node from, Node to) {
        double dx = from.x() - to.x();
        double dy = from.y() - to.y();
        double dz = from.z() - to.z();
        return Math.sqrt(dx * dx + dz * dz) + Math.abs(dy) * 0.5;
    }

    /**
     * Whether a companion could occupy the given block, either standing on solid ground or
     * swimming through it.
     *
     * <p>Two block heights must be free (feet and head). Water is a valid node so companions can
     * swim after their owner instead of failing to plan and resorting to teleporting; lava is still
     * rejected, so hot fluids fall back to teleport rather than cooking the companion.</p>
     *
     * <p>Doors and fence gates count as passable: the companion opens them on the way through and
     * closes them again afterwards, so the route must be allowed to lead through one.</p>
     */
    private static boolean isStandable(World world, Node node) {
        if (!isChunkLoaded(world, node)) {
            return false;
        }
        Block feet = world.getBlockAt(node.x(), node.y(), node.z());
        if (feet.getType() == Material.LAVA) {
            return false;
        }
        if (!isPassable(world, node)) {
            return false;
        }
        Block head = world.getBlockAt(node.x(), node.y() + 1, node.z());
        if (head.getType() == Material.LAVA) {
            return false;
        }
        // The node must have ground under it. Without this check a route happily leads across the
        // void of a ravine — the air above a chasm looks perfectly standable — and the companion
        // walks into it and falls.
        if (!hasGroundSupport(world, node)) {
            return false;
        }
        // A wooden door occupies two blocks, so the upper half counts as clear as well.
        return isOpenable(head) || hasNoCollision(head);
    }

    /**
     * Whether there is something solid to stand on under the given node.
     *
     * <p>Water counts as support: a companion swims there and has to be able to surface, and a
     * swimming node has no solid block underneath it.</p>
     */
    private static boolean hasGroundSupport(World world, Node node) {
        if (isWater(world, node)) {
            return true;
        }
        Node below = new Node(node.x(), node.y() - 1, node.z());
        if (isWater(world, below)) {
            return true;
        }
        if (!isChunkLoaded(world, below)) {
            return false;
        }
        Block support = world.getBlockAt(below.x(), below.y(), below.z());
        return !hasNoCollision(support);
    }

    /** Whether the block itself has no collision shape, ignoring fluids. */
    private static boolean isPassable(World world, Node node) {
        if (!isChunkLoaded(world, node)) {
            return false;
        }
        Block block = world.getBlockAt(node.x(), node.y(), node.z());
        // Closed doors and gates have collision, but the companion opens them on the way through,
        // so planning is allowed to route through them.
        return isOpenable(block) || hasNoCollision(block);
    }

    /**
     * Whether a block is a door or gate the companion can open by itself.
     *
     * <p>{@code Openable} covers both wooden and iron doors as well as fence gates.</p>
     */
    static boolean isOpenable(Block block) {
        return block.getBlockData() instanceof org.bukkit.block.data.Openable;
    }

    /** Whether a node is submerged in water, so movement should swim rather than walk. */
    static boolean isWater(World world, Node node) {
        if (!isChunkLoaded(world, node)) {
            return false;
        }
        return world.getBlockAt(node.x(), node.y(), node.z()).getType() == Material.WATER;
    }

    /** Public water probe used by the follower to switch between walking and swimming. */
    static boolean isWaterAt(World world, int x, int y, int z) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return false;
        }
        return world.getBlockAt(x, y, z).getType() == Material.WATER;
    }

    /**
     * Whether a block has no collision volume.
     *
     * <p>{@code VoxelShape} exposes no emptiness test, so an empty bounding-box collection is used
     * instead: air and other non-colliding blocks report no boxes at all.</p>
     */
    private static boolean hasNoCollision(Block block) {
        return block.getCollisionShape().getBoundingBoxes().isEmpty();
    }

    /** Never touches unloaded chunks; planning must not force chunk loads. */
    private static boolean isChunkLoaded(World world, Node node) {
        return world.isChunkLoaded(node.x() >> 4, node.z() >> 4);
    }

    /** Searches a small area for a standable block, used when the goal is inside geometry. */
    private static Node findNearestStandable(World world, Node goal, int depth) {
        for (int dy = 0; dy >= -depth; dy--) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Node candidate = new Node(goal.x() + dx, goal.y() + dy, goal.z() + dz);
                    if (isStandable(world, candidate)) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    private static boolean withinRadius(Node origin, Node candidate, int radius) {
        int dx = candidate.x() - origin.x();
        int dz = candidate.z() - origin.z();
        return dx * dx + dz * dz <= radius * radius;
    }

    /** Walks the parent chain backwards and returns waypoints ordered from start to goal. */
    private static List<Vector> buildWaypoints(Map<Node, Node> cameFrom, Node goal, Location start) {
        List<Node> reversed = new ArrayList<>();
        Node cursor = goal;
        reversed.add(cursor);
        while (cameFrom.containsKey(cursor)) {
            cursor = cameFrom.get(cursor);
            reversed.add(cursor);
        }
        Collections.reverse(reversed);

        List<Vector> waypoints = new ArrayList<>(reversed.size());
        for (Node node : reversed) {
            waypoints.add(new Vector(node.x() + 0.5, node.y(), node.z() + 0.5));
        }
        // The first waypoint is the block the companion already stands on; drop it so movement
        // immediately heads towards the next useful waypoint.
        if (!waypoints.isEmpty()) {
            Vector first = waypoints.get(0);
            if (Math.abs(first.getX() - start.getX()) < 1.0 && Math.abs(first.getZ() - start.getZ()) < 1.0) {
                waypoints.remove(0);
            }
        }
        return waypoints;
    }

    private static Node toNode(Location location) {
        return new Node(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }
}
