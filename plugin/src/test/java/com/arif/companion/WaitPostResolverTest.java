package com.arif.companion;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.util.BoundingBox;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.arif.companion.PlannerRulesTest.defaultValue;

/**
 * Checks where a companion may appear when it comes back to the post it was left at.
 *
 * <p>The bug this guards against: a companion left on the floor of a house came back on the
 * roof. The old fallback took the highest block of the column, and the roof is the highest
 * block there. The resolver must stay on the ground the companion was left on.</p>
 *
 * <p>Three things are checked: a post on an ordinary floor is kept exactly, a post whose floor
 * has gone is lowered onto what is left instead of being lifted to the roof, and a companion
 * left on a carpet is put back on the carpet rather than in it.</p>
 *
 * <p>Run it against a compiled plugin:</p>
 * <pre>
 * javac -cp "&lt;paper-api&gt;;plugin/build/classes" -d plugin/build plugin/src/test/java/com/arif/companion/WaitPostResolverTest.java
 * java -cp "&lt;paper-api&gt;;plugin/build/classes" com.arif.companion.WaitPostResolverTest
 * </pre>
 */
public final class WaitPostResolverTest {

    public static void main(String[] args) {
        System.out.println("=== a post on an ordinary floor is kept ===");
        TestWorld testWorld = new TestWorld();
        World world = testWorld.proxy();
        // A ground floor, a wall on one side, and a roof two blocks above it.
        testWorld.fill(0, 0, 0, 8, 0, 8, block(Material.STONE, 1.0, null));
        testWorld.fill(0, 4, 0, 8, 4, 8, block(Material.STONE, 1.0, null));
        testWorld.set(2, 1, 2, block(Material.OAK_PLANKS, 1.0, null));
        testWorld.set(2, 2, 2, block(Material.OAK_PLANKS, 1.0, null));

        Location post = new Location(world, 2.5, 1.0, 2.5);
        Location resolved = WaitPostResolver.resolve(post);
        check("the post resolves to a place", true, resolved != null);
        if (resolved != null) {
            System.out.println("     the post resolves to y " + resolved.getY());
            check("the companion stays on the floor it was left on", true,
                    Math.abs(resolved.getY() - 1.0) < 0.001);
        }

        System.out.println();
        System.out.println("=== a post whose floor is gone is lowered, not lifted ===");
        // The same house, but the floor block the companion stood on has been removed and
        // the roof is still up there. The companion must come back down to the ground
        // floor, never up onto the roof.
        TestWorld broken = new TestWorld();
        World brokenWorld = broken.proxy();
        broken.fill(0, 0, 0, 8, 0, 8, block(Material.STONE, 1.0, null));
        broken.fill(0, 4, 0, 8, 4, 8, block(Material.STONE, 1.0, null));
        // A second floor with a hole in it where the companion was standing.
        broken.fill(0, 1, 0, 8, 1, 8, block(Material.OAK_PLANKS, 1.0, null));
        broken.set(2, 1, 2, block(Material.AIR, 0.0, null));

        Location upperPost = new Location(brokenWorld, 2.5, 2.0, 2.5);
        Location lowered = WaitPostResolver.resolve(upperPost);
        check("the post resolves to a place", true, lowered != null);
        if (lowered != null) {
            System.out.println("     the post resolves to y " + lowered.getY());
            check("the companion comes back down to the ground floor", true,
                    lowered.getY() < 2.0);
            check("the companion never ends up on the roof", true,
                    lowered.getY() < 4.0);
        }

        System.out.println();
        System.out.println("=== a companion left on a carpet is put back on it ===");
        TestWorld carpetWorld = new TestWorld();
        World carpet = carpetWorld.proxy();
        carpetWorld.fill(0, 0, 0, 8, 0, 8, block(Material.STONE, 1.0, null));
        carpetWorld.set(2, 1, 2, block(Material.WHITE_CARPET, 0.0625, null));

        Location carpetPost = new Location(carpet, 2.5, 1.0, 2.5);
        Location onCarpet = WaitPostResolver.resolve(carpetPost);
        check("the post resolves to a place", true, onCarpet != null);
        if (onCarpet != null) {
            System.out.println("     the post resolves to y " + onCarpet.getY());
            check("the companion stands on the carpet, not in it", true,
                    Math.abs(onCarpet.getY() - 1.0625) < 0.001);
        }

        System.out.println();
        System.out.println("=== a post with nothing to stand on stays unresolved ===");
        TestWorld empty = new TestWorld();
        World emptyWorld = empty.proxy();
        Location floating = new Location(emptyWorld, 2.5, 64.0, 2.5);
        check("a post in mid air does not resolve", false,
                WaitPostResolver.resolve(floating) != null);
    }

    private static void check(String label, boolean expected, boolean actual) {
        String result = expected == actual ? "OK  " : "FAIL";
        System.out.println(String.format("%-4s %s", result, label));
    }

    /** A block with a material and a collision height. */
    private static Block block(Material type, double height, BlockData data) {
        return (Block) Proxy.newProxyInstance(
                WaitPostResolverTest.class.getClassLoader(),
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
    private static org.bukkit.util.VoxelShape shapeUpTo(double height) {
        return (org.bukkit.util.VoxelShape) Proxy.newProxyInstance(
                WaitPostResolverTest.class.getClassLoader(),
                new Class<?>[]{org.bukkit.util.VoxelShape.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getBoundingBoxes")) {
                        return List.of(new BoundingBox(0.0, 0.0, 0.0, 1.0, height, 1.0));
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    /** A shape with no boxes at all: nothing sticks out of the block. */
    private static org.bukkit.util.VoxelShape emptyShape() {
        return (org.bukkit.util.VoxelShape) Proxy.newProxyInstance(
                WaitPostResolverTest.class.getClassLoader(),
                new Class<?>[]{org.bukkit.util.VoxelShape.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getBoundingBoxes")) {
                        return List.of();
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    /** A world made of blocks, addressed by their coordinates. */
    private static final class TestWorld {

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
