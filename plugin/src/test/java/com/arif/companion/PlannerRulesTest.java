package com.arif.companion;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Fence;
import org.bukkit.block.data.type.Gate;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.util.VoxelShape;
import org.bukkit.util.Vector;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks the planner against a small world built in memory.
 *
 * <p>Three problems came up: the companions jumped along on carpets, they pushed through
 * fences instead of going around them, and they got stuck at a door with a carpet in front
 * of it. The planner is exercised here on a hand-built world, so the answers are the real
 * ones and not a restatement of the rules.</p>
 *
 * <p>Run it against a compiled plugin:</p>
 * <pre>
 * javac -cp "&lt;paper-api&gt;;plugin/build/classes" -d plugin/build plugin/src/test/java/com/arif/companion/PlannerRulesTest.java
 * java -cp "&lt;paper-api&gt;;plugin/build/classes" com.arif.companion.PlannerRulesTest
 * </pre>
 */
public final class PlannerRulesTest {

    public static void main(String[] args) {
        System.out.println("=== a carpet is walked over, not jumped over ===");
        TestWorld testWorld = new TestWorld();
        World world = testWorld.proxy();
        testWorld.fill(0, 0, 0, 4, 0, 4, block(Material.STONE, 1.0, null));
        testWorld.set(1, 1, 1, block(Material.WHITE_CARPET, 0.0625, null));
        testWorld.set(2, 1, 1, block(Material.WHITE_CARPET, 0.0625, null));
        testWorld.set(3, 1, 1, block(Material.WHITE_CARPET, 0.0625, null));
        Location start = new Location(world, 0.5, 1.0, 1.5);
        Location goal = new Location(world, 3.5, 1.0, 1.5);
        List<Vector> route = PathPlanner.findPath(world, start, goal, 16, false, false, false);
        check("a route over the carpet exists", true, !route.isEmpty());
        if (!route.isEmpty()) {
            // The waypoint has to sit on the carpet's own surface. A waypoint at the full
            // block height above it is what made the companion believe it had to climb a
            // block, and jump on every step of the carpet.
            double top = route.get(route.size() - 1).getY();
            System.out.println("     the route ends at height " + top);
            check("the waypoint sits on the carpet's surface", true,
                    Math.abs(top - 1.0625) < 0.001);
        }

        System.out.println();
        System.out.println("=== a carpet in front of a door does not end the route ===");
        TestWorld doorTest = new TestWorld();
        World doorWorld = doorTest.proxy();
        doorTest.fill(0, 0, 0, 6, 0, 4, block(Material.STONE, 1.0, null));
        doorTest.set(2, 1, 2, block(Material.RED_CARPET, 0.0625, null));
        doorTest.set(3, 1, 2, block(Material.RED_CARPET, 0.0625, null));
        doorTest.set(4, 1, 2, block(Material.OAK_DOOR, 1.0, doorData()));
        doorTest.set(4, 2, 2, block(Material.OAK_DOOR, 1.0, doorData()));
        Location doorStart = new Location(doorWorld, 0.5, 1.0, 2.5);
        Location doorGoal = new Location(doorWorld, 5.5, 1.0, 2.5);
        List<Vector> doorRoute = PathPlanner.findPath(doorWorld, doorStart, doorGoal, 16,
                false, false, false);
        check("the route reaches the far side of the door", true, !doorRoute.isEmpty());

        System.out.println();
        System.out.println("=== a fence is a barrier, not a floor ===");
        TestWorld fenceTest = new TestWorld();
        World fenceWorld = fenceTest.proxy();
        fenceTest.fill(0, 0, 0, 8, 0, 4, block(Material.STONE, 1.0, null));
        for (int z = 0; z <= 4; z++) {
            fenceTest.set(4, 1, z, block(Material.OAK_FENCE, 1.5, fenceData()));
        }
        Location fenceStart = new Location(fenceWorld, 1.5, 1.0, 2.5);
        Location fenceGoal = new Location(fenceWorld, 7.5, 1.0, 2.5);
        List<Vector> fenceRoute = PathPlanner.findPath(fenceWorld, fenceStart, fenceGoal, 16,
                false, false, false);
        boolean overFence = false;
        for (Vector point : fenceRoute) {
            if (point.getBlockX() == 4) {
                overFence = true;
            }
        }
        System.out.println("     the route has " + fenceRoute.size() + " points");
        check("the route does not climb over the fence", false, overFence);

        System.out.println();
        System.out.println("=== only doors and fence gates may be opened ===");
        check("a door may be opened", true,
                PathPlanner.isOpenable(block(Material.OAK_DOOR, 1.0, doorData())));
        check("a fence gate may be opened", true,
                PathPlanner.isOpenable(block(Material.OAK_FENCE_GATE, 1.0, gateData())));
        check("a trapdoor may not", false,
                PathPlanner.isOpenable(block(Material.OAK_TRAPDOOR, 1.0, trapData())));
        check("a barrel may not", false,
                PathPlanner.isOpenable(block(Material.BARREL, 1.0, null)));
        check("a fence is a barrier", true,
                PathPlanner.isBarrier(block(Material.OAK_FENCE, 1.5, fenceData())));
        check("a carpet is not a barrier", false,
                PathPlanner.isBarrier(block(Material.WHITE_CARPET, 0.0625, null)));
        check("a carpet is a floor to walk over", true,
                PathPlanner.isCarpet(block(Material.WHITE_CARPET, 0.0625, null)));
    }

    private static void check(String label, boolean expected, boolean actual) {
        String result = expected == actual ? "OK  " : "FAIL";
        System.out.println(String.format("%-4s %s", result, label));
    }

    private static BlockData proxy(Class<?> type, boolean open) {
        return (BlockData) Proxy.newProxyInstance(
                PlannerRulesTest.class.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> {
                    if (method.getName().equals("isOpen")) {
                        return open;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static BlockData doorData() {
        return proxy(Door.class, false);
    }

    private static BlockData gateData() {
        return proxy(Gate.class, false);
    }

    private static BlockData trapData() {
        return proxy(TrapDoor.class, false);
    }

    private static BlockData fenceData() {
        return proxy(Fence.class, false);
    }

    static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == double.class) {
            return 0.0;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }

    /** A block with a material, a height and optional block data. */
    static Block block(Material type, double height, BlockData data) {
        return (Block) Proxy.newProxyInstance(
                PlannerRulesTest.class.getClassLoader(),
                new Class<?>[]{Block.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getType":
                            return type;
                        case "getBlockData":
                            return data;
                        case "getCollisionShape":
                            return height <= 0.0 ? emptyShape() : shapeUpTo(height);
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    /** A shape that fills the block from the bottom up to the given height. */
    static VoxelShape shapeUpTo(double height) {
        return (VoxelShape) Proxy.newProxyInstance(
                PlannerRulesTest.class.getClassLoader(),
                new Class<?>[]{VoxelShape.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getBoundingBoxes")) {
                        return List.of(new org.bukkit.util.BoundingBox(
                                0.0, 0.0, 0.0, 1.0, height, 1.0));
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    /** A shape with no boxes at all: nothing sticks out of the block. */
    static VoxelShape emptyShape() {
        return (VoxelShape) Proxy.newProxyInstance(
                PlannerRulesTest.class.getClassLoader(),
                new Class<?>[]{VoxelShape.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getBoundingBoxes")) {
                        return List.of();
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    /** A world made of blocks, addressed by their coordinates. */
    static final class TestWorld {

        private final Map<String, Block> blocks = new HashMap<>();
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
                                Block found = blocks.get(x + "," + y + "," + z);
                                return found != null ? found
                                        : block(Material.AIR, 0.0, null);
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

        void set(int x, int y, int z, Block block) {
            blocks.put(x + "," + y + "," + z, block);
        }

        void fill(int x1, int y1, int z1, int x2, int y2, int z2, Block block) {
            for (int x = x1; x <= x2; x++) {
                for (int y = y1; y <= y2; y++) {
                    for (int z = z1; z <= z2; z++) {
                        set(x, y, z, block);
                    }
                }
            }
        }
    }
}
