package com.arif.companion;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.util.Vector;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.arif.companion.PlannerRulesTest.defaultValue;

/**
 * Finds the layouts where a companion in wait mode can no longer move.
 *
 * <p>The complaint: a companion in wait mode freezes while it stands on a slab that has ordinary
 * full blocks around it.</p>
 *
 * <p>This test builds those layouts in memory and then replays what the companion would actually
 * do: it plans a route towards a wander spot and then walks it the way the plugin walks it — the
 * waypoints it has already reached are dropped, and the first one that is left has to be far
 * enough away to be worth moving towards. A route whose every waypoint sits directly above the
 * companion leaves it standing still for good, because there is nothing to walk towards.</p>
 *
 * <p>Run it against a compiled plugin:</p>
 * <pre>
 * javac -cp "&lt;paper-api&gt;;plugin/build/classes" -d plugin/build plugin/src/test/java/com/arif/companion/SlabStuckTest.java
 * java -cp "&lt;paper-api&gt;;plugin/build/classes" com.arif.companion.SlabStuckTest
 * </pre>
 */
public final class SlabStuckTest {

    /** The plugin drops a waypoint once it is within this distance, squared. */
    private static final double WAYPOINT_REACHED_DISTANCE_SQUARED = 0.36;

    public static void main(String[] args) {
        System.out.println("=== the layouts where a waiting companion cannot move ===");
        System.out.println();

        List<String> stuck = new ArrayList<>();
        int tried = 0;

        // The layer the slab sits in. The floor is one layer below it.
        for (int layer = 1; layer <= 2; layer++) {
            for (boolean topSlab : new boolean[]{false, true}) {
                for (int ringRadius = 1; ringRadius <= 3; ringRadius++) {
                    for (int ringHeight = 1; ringHeight <= 2; ringHeight++) {
                        tried++;
                        String label = describe(layer, topSlab, ringRadius, ringHeight);
                        Layout layout = build(layer, topSlab, ringRadius, ringHeight);
                        // Only one-block-high walls are the reported bug: two blocks high trap the
                        // slab in a pocket the companion cannot step out of.
                        if (ringHeight == 1 && !canMove(layout)) {
                            stuck.add(label);
                        }
                    }
                }
            }
        }

        System.out.println("     " + tried + " layouts tried, " + stuck.size()
                + " one-block rings leave the companion standing still");
        for (String label : stuck) {
            System.out.println("     STUCK  " + label);
        }
        check("no one-block ring leaves the companion standing still", true, stuck.isEmpty());
    }

    /** A human-readable name for one layout. */
    private static String describe(int layer, boolean topSlab, int ringRadius, int ringHeight) {
        String slab = topSlab ? "a top slab" : "a bottom slab";
        return slab + " in layer " + layer + ", ringed by " + ringHeight
                + " full block(s) " + ringRadius + " block(s) out";
    }

    /**
     * Whether a companion standing on the slab can actually start walking.
     *
     * <p>This replays the plugin's own movement decision: plan a route towards a wander spot, drop
     * the waypoints the companion has already reached, and see whether one is left that it would
     * actually move towards. A waypoint directly above the companion is not one of them: the
     * companion has no direction to walk in.</p>
     */
    private static boolean canMove(Layout layout) {
        World world = layout.world.proxy();
        Location companion = new Location(world, 2.5, layout.feetY, 2.5);

        // The wander spots the plugin would try: twelve directions, a radius of 1.5 to 4 blocks.
        for (int step = 0; step < 24; step++) {
            double angle = step * Math.PI / 12.0;
            for (double radius = 1.5; radius <= 4.0; radius += 1.0) {
                Location candidate = new Location(world,
                        companion.getX() + Math.sin(angle) * radius,
                        companion.getY(),
                        companion.getZ() + Math.cos(angle) * radius);
                Location standing = WaitPostResolver.standingAt(world, candidate);
                if (standing == null) {
                    continue;
                }
                List<Vector> route = PathPlanner.findPath(world, companion, standing,
                        48, false, false, false);
                if (route.isEmpty()) {
                    continue;
                }
                if (walkable(route, companion)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether the route gives the companion a direction to walk in.
     *
     * <p>The waypoints already reached are dropped, exactly as the plugin drops them, and the
     * first one left has to be more than the reached distance away sideways. A waypoint straight
     * above the companion is dropped with the rest, and if that is all there is, the companion
     * has nothing to walk towards and stands still.</p>
     */
    private static boolean walkable(List<Vector> route, Location companion) {
        int index = 0;
        while (index < route.size()) {
            Vector waypoint = route.get(index);
            double dx = waypoint.getX() - companion.getX();
            double dz = waypoint.getZ() - companion.getZ();
            if (dx * dx + dz * dz <= WAYPOINT_REACHED_DISTANCE_SQUARED) {
                index++;
            } else {
                break;
            }
        }
        return index < route.size();
    }

    private static void check(String label, boolean expected, boolean actual) {
        String result = expected == actual ? "OK  " : "FAIL";
        System.out.println(String.format("%-4s %s", result, label));
    }

    /** One layout: a floor, a slab for the companion, and a ring of full blocks. */
    private static final class Layout {

        private final TestWorld world = new TestWorld();
        private double feetY;
    }

    /**
     * Builds one layout.
     *
     * @param layer       the layer the slab sits in
     * @param topSlab     whether the slab fills the upper half of its layer
     * @param ringRadius  how many blocks out the ring of full blocks reaches
     * @param ringHeight  how many blocks high that ring is
     */
    private static Layout build(int layer, boolean topSlab, int ringRadius, int ringHeight) {
        Layout layout = new Layout();
        TestWorld world = layout.world;

        // The floor: a full block under everything.
        for (int x = 0; x <= 12; x++) {
            for (int z = 0; z <= 12; z++) {
                world.set(x, layer - 1, z, 0.0, 1.0);
            }
        }
        // The companion's slab: the lower or the upper half of its layer.
        world.set(2, layer, 2, topSlab ? 0.5 : 0.0, topSlab ? 1.0 : 0.5);
        layout.feetY = layer + (topSlab ? 1.0 : 0.5);

        // The ring of ordinary full blocks around it, resting on the floor.
        for (int x = 0; x <= 12; x++) {
            for (int z = 0; z <= 12; z++) {
                int distance = Math.max(Math.abs(x - 2), Math.abs(z - 2));
                if (distance >= 1 && distance <= ringRadius) {
                    for (int height = 0; height < ringHeight; height++) {
                        world.set(x, layer + height, z, 0.0, 1.0);
                    }
                }
            }
        }
        return layout;
    }

    /** A world made of blocks, each with the height range it fills inside its own layer. */
    private static final class TestWorld {

        private final Map<String, double[]> blocks = new HashMap<>();
        private final World proxy;

        TestWorld() {
            proxy = (World) Proxy.newProxyInstance(
                    TestWorld.class.getClassLoader(),
                    new Class<?>[]{World.class},
                    (p, method, args) -> {
                        switch (method.getName()) {
                            case "getBlockAt": {
                                Object[] call = args;
                                int x;
                                int y;
                                int z;
                                if (call.length == 3) {
                                    x = (Integer) call[0];
                                    y = (Integer) call[1];
                                    z = (Integer) call[2];
                                } else {
                                    Location location = (Location) call[0];
                                    x = location.getBlockX();
                                    y = location.getBlockY();
                                    z = location.getBlockZ();
                                }
                                double[] range = blocks.get(x + "," + y + "," + z);
                                return range == null
                                        ? block(Material.AIR, 0.0, 0.0)
                                        : block(Material.STONE, range[0], range[1]);
                            }
                            case "isChunkLoaded":
                                return true;
                            case "getName":
                                return "test";
                            default:
                                return defaultValue(method.getReturnType());
                        }
                    });
        }

        World proxy() {
            return proxy;
        }

        /** Places a block filling the given height range inside its own layer. */
        void set(int x, int y, int z, double base, double top) {
            blocks.put(x + "," + y + "," + z, new double[]{base, top});
        }
    }

    /** A block whose collision shape fills the given height range inside its own layer. */
    private static Block block(Material type, double base, double top) {
        return (Block) Proxy.newProxyInstance(
                SlabStuckTest.class.getClassLoader(),
                new Class<?>[]{Block.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getType":
                            return type;
                        case "getBlockData":
                            return (BlockData) null;
                        case "getCollisionShape":
                            return top <= base ? emptyShape() : shapeBetween(base, top);
                        // The server answers this from the collision shape: a slab is not passable.
                        case "isPassable":
                            return top <= base;
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    /** A shape that fills the block between two heights. */
    private static org.bukkit.util.VoxelShape shapeBetween(double base, double top) {
        return (org.bukkit.util.VoxelShape) Proxy.newProxyInstance(
                SlabStuckTest.class.getClassLoader(),
                new Class<?>[]{org.bukkit.util.VoxelShape.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getBoundingBoxes")) {
                        return List.of(new org.bukkit.util.BoundingBox(0.0, base, 0.0, 1.0, top, 1.0));
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    /** A shape with no boxes at all: nothing sticks out of the block. */
    private static org.bukkit.util.VoxelShape emptyShape() {
        return (org.bukkit.util.VoxelShape) Proxy.newProxyInstance(
                SlabStuckTest.class.getClassLoader(),
                new Class<?>[]{org.bukkit.util.VoxelShape.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getBoundingBoxes")) {
                        return List.of();
                    }
                    return defaultValue(method.getReturnType());
                });
    }
}
