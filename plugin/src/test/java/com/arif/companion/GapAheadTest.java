package com.arif.companion;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Mannequin;

import java.lang.reflect.Proxy;

import static com.arif.companion.PlannerRulesTest.TestWorld;
import static com.arif.companion.PlannerRulesTest.block;
import static com.arif.companion.PlannerRulesTest.defaultValue;

/**
 * Checks whether the companion sees a gap where there is none.
 *
 * <p>A carpet in the road made the companion jump along on it. The companion stands on the
 * carpet, so the block it is standing on is the carpet's own block, and the floor it rests
 * on is the block below that. The gap check has to look at the same place the companion is
 * actually standing, or it decides there is a hole and jumps it.</p>
 *
 * <p>Run it against a compiled plugin:</p>
 * <pre>
 * javac -cp "&lt;paper-api&gt;;plugin/build/classes" -d plugin/build plugin/src/test/java/com/arif/companion/GapAheadTest.java
 * java -cp "&lt;paper-api&gt;;plugin/build/classes" com.arif.companion.GapAheadTest
 * </pre>
 */
public final class GapAheadTest {

    public static void main(String[] args) {
        System.out.println("=== a carpet under the companion is not a gap ===");
        TestWorld testWorld = new TestWorld();
        World world = testWorld.proxy();
        // A stone floor at y=0 with a carpet laid on it at y=1.
        testWorld.fill(0, 0, 0, 6, 0, 6, block(Material.STONE, 1.0, null));
        testWorld.set(1, 1, 1, block(Material.WHITE_CARPET, 0.0625, null));
        testWorld.set(2, 1, 1, block(Material.WHITE_CARPET, 0.0625, null));
        testWorld.set(3, 1, 1, block(Material.WHITE_CARPET, 0.0625, null));

        // The companion stands on the carpet: its feet rest at 1.0625.
        Location onCarpet = new Location(world, 1.5, 1.0625, 1.5);
        Mannequin npc = mannequin();
        check("no gap is seen while walking over the carpet", false,
                CompanionManager.isGapAhead(npc, onCarpet, 1.0, 0.0));

        System.out.println();
        System.out.println("=== the same holds for a slab ===");
        TestWorld slabWorld = new TestWorld();
        World slabProxy = slabWorld.proxy();
        slabWorld.fill(0, 0, 0, 6, 0, 6, block(Material.STONE, 1.0, null));
        slabWorld.set(1, 1, 1, block(Material.STONE_SLAB, 0.5, null));
        slabWorld.set(2, 1, 1, block(Material.STONE_SLAB, 0.5, null));
        Location onSlab = new Location(slabProxy, 1.5, 1.5, 1.5);
        check("no gap is seen while walking over the slab", false,
                CompanionManager.isGapAhead(npc, onSlab, 1.0, 0.0));

        System.out.println();
        System.out.println("=== a real gap is still a gap ===");
        TestWorld holeWorld = new TestWorld();
        World holeProxy = holeWorld.proxy();
        holeWorld.fill(0, 0, 0, 6, 0, 6, block(Material.STONE, 1.0, null));
        // A hole in the floor right in front of the companion.
        holeWorld.set(2, 0, 1, block(Material.AIR, 0.0, null));
        Location beforeHole = new Location(holeProxy, 1.5, 1.0, 1.5);
        check("a hole ahead is a gap", true,
                CompanionManager.isGapAhead(npc, beforeHole, 1.0, 0.0));

        System.out.println();
        System.out.println("=== a carpet in front, over a hole, is still a gap ===");
        TestWorld holeCarpetWorld = new TestWorld();
        World holeCarpetProxy = holeCarpetWorld.proxy();
        holeCarpetWorld.fill(0, 0, 0, 6, 0, 6, block(Material.STONE, 1.0, null));
        holeCarpetWorld.set(2, 0, 1, block(Material.AIR, 0.0, null));
        // A carpet laid over the hole: it does not make the ground solid.
        holeCarpetWorld.set(2, 1, 1, block(Material.WHITE_CARPET, 0.0625, null));
        Location beforeHoleCarpet = new Location(holeCarpetProxy, 1.5, 1.0, 1.5);
        check("a carpet over a hole does not hide the gap", true,
                CompanionManager.isGapAhead(npc, beforeHoleCarpet, 1.0, 0.0));

        System.out.println();
        System.out.println("=== a wall ahead is not a gap ===");
        TestWorld wallWorld = new TestWorld();
        World wallProxy = wallWorld.proxy();
        wallWorld.fill(0, 0, 0, 6, 0, 6, block(Material.STONE, 1.0, null));
        wallWorld.set(2, 1, 1, block(Material.STONE, 1.0, null));
        Location beforeWall = new Location(wallProxy, 1.5, 1.0, 1.5);
        check("a wall ahead is not a gap", false,
                CompanionManager.isGapAhead(npc, beforeWall, 1.0, 0.0));
    }

    private static void check(String label, boolean expected, boolean actual) {
        String result = expected == actual ? "OK  " : "FAIL";
        System.out.println(String.format("%-4s %s", result, label));
    }

    private static Mannequin mannequin() {
        return (Mannequin) Proxy.newProxyInstance(
                GapAheadTest.class.getClassLoader(),
                new Class<?>[]{Mannequin.class},
                (proxy, method, args) -> defaultValue(method.getReturnType()));
    }
}
