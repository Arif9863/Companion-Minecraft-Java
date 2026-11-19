package com.arif.companion;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.util.Vector;

import java.util.List;

import static com.arif.companion.PlannerRulesTest.TestWorld;
import static com.arif.companion.PlannerRulesTest.block;

/**
 * Checks that a companion never walks or jumps into lava.
 *
 * <p>The complaint: raiding a bastion in the Nether, the companions kept leaping into the lava
 * pools — sometimes while chasing a hostile, sometimes just walking past one.</p>
 *
 * <p>The cause is that a fluid has no collision shape. A lava pool therefore looks exactly like
 * open air: to the gap detector it was a hole in the floor, so the companion jumped across it,
 * and to the ground check it was solid ground, so a route led straight over it. Both readings
 * are wrong in the same direction, and both put the companion in the lava.</p>
 *
 * <p>Four things are checked here: the route never crosses a pool, the companion never stands
 * over one, the column in front of it is recognised as blocked, and the pool is treated as
 * ground rather than as a hole — so it is stepped around instead of jumped over.</p>
 *
 * <p>Run it against a compiled plugin:</p>
 * <pre>
 * javac -cp "&lt;paper-api&gt;;plugin/build/classes" -d plugin/build plugin/src/test/java/com/arif/companion/LavaRulesTest.java
 * java -cp "&lt;paper-api&gt;;plugin/build/classes" com.arif.companion.LavaRulesTest
 * </pre>
 */
public final class LavaRulesTest {

    public static void main(String[] args) {
        System.out.println("=== a route never crosses a lava pool ===");
        TestWorld testWorld = new TestWorld();
        World world = testWorld.proxy();
        // A floor with a pool of lava cutting across it at x = 4. The pool leaves a lane of
        // ground at either end, so the companion has a way round — which is the point: it must
        // take that lane rather than jump the pool.
        testWorld.fill(0, 0, 0, 8, 0, 6, block(Material.STONE, 1.0, null));
        for (int z = 1; z <= 5; z++) {
            testWorld.set(4, 1, z, block(Material.LAVA, 0.0, null));
        }
        Location start = new Location(world, 1.5, 1.0, 3.5);
        Location goal = new Location(world, 7.5, 1.0, 3.5);
        List<Vector> route = PathPlanner.findPath(world, start, goal, 16, false, false, true);
        boolean crossedPool = false;
        for (Vector point : route) {
            // Only a waypoint over the lava itself counts. The route passes x = 4 on its way
            // round the pool, at the clear lane beyond the pool's edge.
            if (point.getBlockX() == 4 && point.getBlockZ() >= 1 && point.getBlockZ() <= 5) {
                crossedPool = true;
            }
        }
        System.out.println("     the route has " + route.size() + " points");
        check("the route does not cross the lava pool", false, crossedPool);
        check("the route goes around the pool", true, !route.isEmpty());

        System.out.println();
        System.out.println("=== a companion never stands over a lava pool ===");
        // The block above the pool would look standable: the air there is empty and the lava
        // below has no collision shape, so it used to read as solid ground.
        Location overPool = new Location(world, 4.5, 1.0, 3.5);
        check("the ground over the pool is not standable", false,
                PathPlanner.isStandableAt(world, overPool));

        System.out.println();
        System.out.println("=== lava in front is recognised as blocked ===");
        // Walking towards the pool, the column ahead holds lava.
        Location besidePool = new Location(world, 3.5, 1.0, 3.5);
        check("lava one block ahead is seen", true,
                CompanionManager.lavaAhead(world, besidePool, 1.0, 0.0));
        check("lava is not seen behind the companion", false,
                CompanionManager.lavaAhead(world, besidePool, -1.0, 0.0));
        // Standing well away from the pool, nothing is in the way.
        Location farAway = new Location(world, 7.5, 1.0, 3.5);
        check("no lava is seen from the far side", false,
                CompanionManager.lavaAhead(world, farAway, -1.0, 0.0));

        System.out.println();
        System.out.println("=== a lava pool is ground, not a hole ===");
        // A pool read as a hole would make the companion jump across it. It has to read as
        // ground instead, so the companion steps around the edge.
        check("the pool is not a gap to jump over", false,
                CompanionManager.isGapAheadAt(world, besidePool, 1.0, 0.0));
    }

    private static void check(String label, boolean expected, boolean actual) {
        String result = expected == actual ? "OK  " : "FAIL";
        System.out.println(String.format("%-4s %s", result, label));
    }
}
