package com.arif.companion;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import static com.arif.companion.PlannerRulesTest.TestWorld;
import static com.arif.companion.PlannerRulesTest.block;

/**
 * Checks the rules that keep a companion from being dragged away and stuck.
 *
 * <p>A companion that chases a mob which keeps running walks away from its owner with it.
 * Once it is out of reach there is no route back, so the companion is stuck wherever the
 * chase ended. A waiting companion has its own leash: it holds its post and lets a mob go
 * once the mob pulls it out of the ground it was told to hold.</p>
 *
 * <p>Run it against a compiled plugin:</p>
 * <pre>
 * javac -cp "&lt;paper-api&gt;;plugin/build/classes" -d plugin/build plugin/src/test/java/com/arif/companion/ChaseRulesTest.java
 * java -cp "&lt;paper-api&gt;;plugin/build/classes" com.arif.companion.ChaseRulesTest
 * </pre>
 */
public final class ChaseRulesTest {

    public static void main(String[] args) {
        System.out.println("=== a mob within reach is still chased ===");
        // The reach is the attack search radius doubled: sixteen blocks by default.
        double reach = 16.0;
        check("a mob four blocks away is chased", false,
                CompanionManager.isOutOfReach(4.0 * 4.0, reach));
        check("a mob right next to the owner is chased", false,
                CompanionManager.isOutOfReach(0.0, reach));

        System.out.println();
        System.out.println("=== a mob that leads the companion away is let go ===");
        check("a mob twenty blocks away is let go", true,
                CompanionManager.isOutOfReach(20.0 * 20.0, reach));
        check("a mob forty blocks away is let go", true,
                CompanionManager.isOutOfReach(40.0 * 40.0, reach));

        System.out.println();
        System.out.println("=== exactly at the reach is still chased ===");
        check("a mob exactly at the reach is chased", false,
                CompanionManager.isOutOfReach(reach * reach, reach));

        System.out.println();
        System.out.println("=== the leash is measured from the post, not from the companion ===");
        TestWorld testWorld = new TestWorld();
        World world = testWorld.proxy();
        testWorld.fill(0, 0, 0, 20, 0, 20, block(Material.STONE, 1.0, null));
        // The post the companion was told to hold, and a mob that has run off with it.
        Location post = new Location(world, 2.5, 1.0, 2.5);
        Location mobNear = new Location(world, 8.5, 1.0, 2.5);
        Location mobFar = new Location(world, 19.5, 1.0, 2.5);
        double leash = 12.0;
        check("a mob near the post is inside the leash", true,
                withinLeash(mobNear, post, leash));
        check("a mob that ran off with the companion is outside the leash", false,
                withinLeash(mobFar, post, leash));
    }

    private static boolean withinLeash(Location mob, Location post, double leash) {
        return mob.distanceSquared(post) <= leash * leash;
    }

    private static void check(String label, boolean expected, boolean actual) {
        String result = expected == actual ? "OK  " : "FAIL";
        System.out.println(String.format("%-4s %s", result, label));
    }
}
