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
    /** Combat may descend up to three blocks towards a hostile target below the companion. */
    private static final int MAX_COMBAT_DROP = 3;
    /**
     * How far the companion may drop in one step while the owner is below it.
     *
     * <p>When the owner stands lower down, the companion is expected to come down after him
     * instead of giving up and teleporting. The descent is still checked for a clear shaft and a
     * solid floor, so this never turns into a fall into the void.</p>
     */
    private static final int MAX_OWNER_DESCENT = 12;
    /**
     * How many blocks a parkour jump may clear.
     *
     * <p>A player jumps about 1.25 blocks and covers roughly three blocks of ground, so a gap of
     * up to three blocks is something a player clears easily. The companion jumps the same way.</p>
     */
    private static final int PARKOUR_GAP = 3;
    /** Maximum vertical rise a single parkour jump can reach. */
    private static final int PARKOUR_RISE = 1;
    /**
     * How far a parkour jump may fall onto its landing.
     *
     * <p>A player running off a ledge clears a gap just as easily when the far side is lower —
     * the fall does the rest. Four blocks is a safe landing depth: the companion still lands on
     * solid ground rather than dropping into a ravine, and it stays well below the eight-block
     * distance at which a companion is rescued out of a fall.</p>
     */
    private static final int PARKOUR_DROP = 4;
    /** Extra route cost for a jump that ends lower than its takeoff point. */
    private static final double PARKOUR_DROP_COST = 1.2;
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
     * Computes a route from the companion to a goal.
     *
     * @param world       world both entities are in
     * @param start       current companion position
     * @param goal        desired goal position
     * @param radius      horizontal search radius in blocks
     * @param combatDrop  when true, allow up to three-block safe descents toward a mob
     * @param ownerBelow  when true, the owner is lower down, so the companion may descend freely
     * @param parkour     when true, gaps of up to three blocks may be jumped
     * @return waypoint centres from start towards the goal, or an empty list when no route exists
     */
    static List<Vector> findPath(World world, Location start, Location goal, int radius,
                                 boolean combatDrop, boolean ownerBelow, boolean parkour) {
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

            for (Node neighbour : neighbours(world, node, radius, false, combatDrop, ownerBelow, parkour)) {
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
            for (Node next : neighbours(world, current, radius, true, false, false, false)) {
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
            for (Node next : neighbours(world, current, radius, false, false, false, false)) {
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

    /**
     * Expands a node into reachable neighbours: flat moves, one-block step up, and drops.
     *
     * @param combatDrop when true, drops of up to three blocks are allowed, but only onto solid
     *                    ground — a fall into the void is still never a route
     * @param ownerBelow when true, the owner stands lower down, so the companion may descend
     *                   further in one step instead of giving up
     * @param parkour    when true, a gap of up to three blocks may be jumped over
     */
    private static List<Node> neighbours(World world, Node node, int radius, boolean relaxed,
                                         boolean combatDrop, boolean ownerBelow, boolean parkour) {
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

            // Falling down. Following never descends more than one block, but a fight may: a
            // skeleton two or three blocks below the companion is a legitimate target, and the
            // companion used to stand and watch it because no route existed. The search is for
            // the landing block — feet and head must be clear on the way down, and the landing
            // must have solid ground beneath it, so a fall into the void is still never a route.
            if (relaxed) {
                Node down = new Node(nx, node.y() - 1, nz);
                if (isOwnerStandable(world, down) && withinRadius(node, down, radius)) {
                    result.add(down);
                }
            } else {
                int maxDrop = ownerBelow ? MAX_OWNER_DESCENT
                        : (combatDrop ? MAX_COMBAT_DROP : MAX_DROP);
                for (int drop = 1; drop <= maxDrop; drop++) {
                    Node down = new Node(nx, node.y() - drop, nz);
                    if (!withinRadius(node, down, radius)) {
                        break;
                    }
                    // Something solid in the shaft means this column is not a route at all.
                    if (!isClearColumn(world, down)) {
                        break;
                    }
                    // Landed: this is the block the companion would end up standing on.
                    if (hasGroundSupport(world, down)) {
                        result.add(down);
                        break;
                    }
                    // Open air below — keep looking for the floor, up to the allowed depth.
                }
            }

            // Parkour: when the next block is a gap, the companion jumps it the way a player
            // would. The landing block must be standable and within a step or two of height,
            // and the space in between must be open air.
            if (parkour && !relaxed) {
                addParkourJumps(world, node, offset, radius, result);
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

    /**
     * Adds the landing blocks a parkour jump can reach across a gap.
     *
     * <p>A player jumps about 1.25 blocks high and covers roughly three blocks of ground, so
     * the companion does the same: a gap of up to {@link #PARKOUR_GAP} blocks is jumped when the
     * landing block is solid, reachable in height, and the space above the gap is open.</p>
     *
     * @param offset the horizontal direction being explored
     */
    private static void addParkourJumps(World world, Node node, int[] offset, int radius,
                                         List<Node> result) {
        int dx = offset[0];
        int dz = offset[1];
        // The gap is measured from the block in front of the companion. Every block of the gap
        // must be open air with no floor beneath it, and the landing one further along must be
        // solid. The scan keeps going for longer jumps instead of stopping at the first
        // unreachable landing: a three-block gap has no floor two blocks out either.
        for (int gap = 1; gap <= PARKOUR_GAP; gap++) {
            // The block in the gap must be open, otherwise this is a wall, not a gap.
            Node front = new Node(node.x() + dx * gap, node.y(), node.z() + dz * gap);
            if (!isClearColumn(world, front)) {
                return;
            }
            // The gap must have no floor: otherwise the companion could simply walk across it
            // and no jump is needed.
            Node gapFloor = new Node(front.x(), front.y() - 1, front.z());
            if (hasFloor(world, gapFloor)) {
                return;
            }
            // The landing block is one further along.
            Node landing = new Node(node.x() + dx * (gap + 1), node.y(), node.z() + dz * (gap + 1));
            if (!withinRadius(node, landing, radius)) {
                return;
            }
            if (!isStandable(world, landing)) {
                // The far side may sit one block higher: a player jumps up onto a ledge across
                // a gap, so the companion may as well. Check that before looking for a lower
                // landing or giving up on this gap.
                Node raised = new Node(landing.x(), landing.y() + PARKOUR_RISE, landing.z());
                if (isStandable(world, raised) && withinRadius(node, raised, radius)) {
                    result.add(raised);
                    return;
                }
                Node lower = findParkourDropLanding(world, node, landing, radius);
                if (lower != null) {
                    // The first gap that reaches a safe floor wins. Scanning farther would only
                    // aim the jump past the platform the companion is meant to land on.
                    result.add(lower);
                    return;
                }
                // Not a landing yet: a longer jump may still reach solid ground further on.
                continue;
            }
            result.add(landing);
            // A jump of up to one block up is also possible, so a ledge across the gap counts.
            Node raised = new Node(landing.x(), landing.y() + PARKOUR_RISE, landing.z());
            if (isStandable(world, raised) && withinRadius(node, raised, radius)) {
                result.add(raised);
            }
            return;
        }
    }

    /**
     * Finds the shallowest safe lower landing beneath the far-side column.
     *
     * <p>The fall is only accepted when both blocks of every shaft node are clear, chunks are
     * loaded, and the landing has safe ground support. Lava and water are not jump landings. The
     * horizontal gap columns are checked by the caller before this vertical scan.</p>
     */
    private static Node findParkourDropLanding(World world, Node origin, Node landing, int radius) {
        for (int drop = 1; drop <= PARKOUR_DROP; drop++) {
            Node candidate = new Node(landing.x(), origin.y() - drop, landing.z());
            if (!withinRadius(origin, candidate, radius) || !isClearColumn(world, candidate)) {
                return null;
            }
            if (isStandable(world, candidate)) {
                if (isWater(world, candidate)
                        || isWater(world, new Node(candidate.x(), candidate.y() - 1, candidate.z()))) {
                    return null;
                }
                return candidate;
            }
        }
        return null;
    }

    /** Whether the given block has something solid to stand on. */
    private static boolean hasFloor(World world, Node node) {
        if (!isChunkLoaded(world, node)) {
            return true;
        }
        Block block = world.getBlockAt(node.x(), node.y(), node.z());
        if (block.getType() == Material.WATER) {
            return true;
        }
        // Lava counts as ground here even though it is not solid: a fluid has no collision
        // shape, so without this a lava pool reads as a hole and the companion jumps across
        // it — into the lava. Treating the pool as floor keeps the route away from it.
        if (block.getType() == Material.LAVA) {
            return true;
        }
        return !hasNoCollision(block);
    }

    /** Step cost: horizontal moves cost 1, step up and drops cost a bit more. */
    private static double stepCost(Node from, Node to) {
        double base = 1.0;
        int horizontal = Math.abs(to.x() - from.x()) + Math.abs(to.z() - from.z());
        if (to.y() > from.y()) {
            return base + 0.6;
        }
        if (to.y() < from.y()) {
            double dropCost = Math.abs(from.y() - to.y()) * 0.25;
            if (horizontal > 1) {
                dropCost += PARKOUR_DROP_COST;
            }
            return base + dropCost;
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
     * Whether a companion could stand at the given place.
     *
     * <p>Exposed for the tests, which check that a companion is never placed over a lava pool.</p>
     */
    static boolean isStandableAt(World world, Location location) {
        if (world == null || location == null) {
            return false;
        }
        return isStandable(world, toNode(location));
    }

    /**
     * Whether the two-block-high column at the given node is clear, without requiring a floor.
     * Used only while checking a controlled fall down to a known landing block.
     */
    private static boolean isClearColumn(World world, Node node) {
        if (!isChunkLoaded(world, node)) {
            return false;
        }
        Block feet = world.getBlockAt(node.x(), node.y(), node.z());
        Block head = world.getBlockAt(node.x(), node.y() + 1, node.z());
        return feet.getType() != Material.LAVA
                && head.getType() != Material.LAVA
                && isPassable(world, node)
                && (isOpenable(head) || hasNoCollision(head));
    }

    /**
     * Whether there is something solid to stand on under the given node.
     *
     * <p>Water counts as support: a companion swims there and has to be able to surface, and a
     * swimming node has no solid block underneath it.</p>
     *
     * <p>A fence or a wall does <b>not</b> count. It is only a thin barrier, so a companion that
     * treated it as a floor would route straight over it and drop down on the far side — which is
     * what made the companions shove their way through fences instead of going around. Only a
     * surface a companion could really stand on counts here.</p>
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
        if (isBarrier(support)) {
            return false;
        }
        // Lava is not a floor a companion may stand over: standing on the block above a pool
        // would put the companion's feet in the lava. A fluid has no collision shape, so
        // without this check the pool would read as empty ground and the route would lead
        // straight over it.
        if (support.getType() == Material.LAVA) {
            return false;
        }
        return !hasNoCollision(support);
    }

    /**
     * Whether the block is a thin barrier a companion must not treat as a floor.
     *
     * <p>Fences, fence walls and glass panes are only a block or so tall and thin. A companion
     * that stands on one and steps over it is not walking through a gap — it is squeezing
     * through a fence, which is exactly what the owner complained about. Real walls and solid
     * blocks are unaffected: they are still solid and still block the route.</p>
     *
     * <p>The block data types are used rather than the material names, because the material
     * names differ from version to version while the types do not.</p>
     */
    static boolean isBarrier(Block block) {
        return block.getBlockData() instanceof org.bukkit.block.data.type.Fence
                || block.getBlockData() instanceof org.bukkit.block.data.type.Wall
                || block.getBlockData() instanceof org.bukkit.block.data.type.GlassPane;
    }

    /** Whether the block itself has no collision shape, ignoring fluids. */
    private static boolean isPassable(World world, Node node) {
        if (!isChunkLoaded(world, node)) {
            return false;
        }
        Block block = world.getBlockAt(node.x(), node.y(), node.z());
        // Closed doors and gates have collision, but the companion opens them on the way through,
        // so planning is allowed to route through them.
        if (isOpenable(block)) {
            return true;
        }
        // A carpet is only a sixteenth of a block tall. The companion walks over it, so a route
        // must be allowed to cross it — otherwise a carpet laid in front of a door ends the
        // route right there and the companion has to be rescued with a teleport.
        if (isCarpet(block)) {
            return true;
        }
        return hasNoCollision(block);
    }

    /**
     * Whether the block is a carpet.
     *
     * <p>Carpet has no block-data type of its own, so it is recognised by its material. Every
     * carpet colour is checked, because the plugin cannot pick one.</p>
     */
    static boolean isCarpet(Block block) {
        Material type = block.getType();
        return type == Material.WHITE_CARPET
                || type == Material.ORANGE_CARPET
                || type == Material.MAGENTA_CARPET
                || type == Material.LIGHT_BLUE_CARPET
                || type == Material.YELLOW_CARPET
                || type == Material.LIME_CARPET
                || type == Material.PINK_CARPET
                || type == Material.GRAY_CARPET
                || type == Material.LIGHT_GRAY_CARPET
                || type == Material.CYAN_CARPET
                || type == Material.PURPLE_CARPET
                || type == Material.BLUE_CARPET
                || type == Material.BROWN_CARPET
                || type == Material.GREEN_CARPET
                || type == Material.RED_CARPET
                || type == Material.BLACK_CARPET;
    }

    /**
     * Whether a block is a door or gate the companion can open by itself.
     *
     * <p>Only wooden doors, copper doors, and fence gates qualify. {@code Openable} also covers
     * trapdoors, and routing through one of those is what made the companion walk over the
     * decoration in the owner's house instead of around it.</p>
     */
    static boolean isOpenable(Block block) {
        Material type = block.getType();
        if (type == Material.OAK_DOOR || type == Material.SPRUCE_DOOR
                || type == Material.BIRCH_DOOR || type == Material.JUNGLE_DOOR
                || type == Material.ACACIA_DOOR || type == Material.DARK_OAK_DOOR
                || type == Material.PALE_OAK_DOOR || type == Material.CRIMSON_DOOR
                || type == Material.WARPED_DOOR || type == Material.MANGROVE_DOOR
                || type == Material.BAMBOO_DOOR || type == Material.CHERRY_DOOR
                || type == Material.POPLAR_DOOR) {
            return true;
        }
        if (type == Material.COPPER_DOOR || type == Material.EXPOSED_COPPER_DOOR
                || type == Material.WEATHERED_COPPER_DOOR || type == Material.OXIDIZED_COPPER_DOOR
                || type == Material.WAXED_COPPER_DOOR || type == Material.WAXED_EXPOSED_COPPER_DOOR
                || type == Material.WAXED_WEATHERED_COPPER_DOOR
                || type == Material.WAXED_OXIDIZED_COPPER_DOOR) {
            return true;
        }
        return block.getBlockData() instanceof org.bukkit.block.data.type.Gate;
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

    /**
     * Walks the parent chain backwards and returns waypoints ordered from start to goal.
     *
     * <p>A waypoint's height is the surface the companion stands on, not the block it belongs
     * to. A carpet or a slab is a fraction of a block tall, and using the block's own height
     * made the companion believe it had to climb a full block to reach the next step — so it
     * jumped along on every carpet it crossed.</p>
     */
    private static List<Vector> buildWaypoints(Map<Node, Node> cameFrom, Node goal, Location start) {
        List<Node> reversed = new ArrayList<>();
        Node cursor = goal;
        reversed.add(cursor);
        while (cameFrom.containsKey(cursor)) {
            cursor = cameFrom.get(cursor);
            reversed.add(cursor);
        }
        Collections.reverse(reversed);

        World world = start.getWorld();
        List<Vector> waypoints = new ArrayList<>(reversed.size());
        for (Node node : reversed) {
            waypoints.add(new Vector(node.x() + 0.5, surfaceHeight(world, node), node.z() + 0.5));
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

    /**
     * The height the companion's feet rest at when it stands on the given block.
     *
     * <p>This is the top of the block's collision shape. For a full block that is the block's
     * own height; for a carpet, a slab or a stair it is the part of the block that actually
     * sticks up.</p>
     *
     * <p>A door and a fence gate are the exception: the companion walks <b>through</b> them, so
     * its feet stay on the floor it is standing on rather than on top of the door. A closed door
     * fills its own block, and measuring the top of that shape put the waypoint a block too
     * high, which made the companion believe it had to climb over the doorway — it hopped up
     * and down in front of every door it opened.</p>
     */
    private static double surfaceHeight(World world, Node node) {
        if (!isChunkLoaded(world, node)) {
            return node.y();
        }
        Block block = world.getBlockAt(node.x(), node.y(), node.z());
        if (isOpenable(block)) {
            return node.y();
        }
        double top = node.y();
        for (org.bukkit.util.BoundingBox box : block.getCollisionShape().getBoundingBoxes()) {
            top = Math.max(top, node.y() + box.getMaxY());
        }
        return top;
    }

    private static Node toNode(Location location) {
        return new Node(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }
}
