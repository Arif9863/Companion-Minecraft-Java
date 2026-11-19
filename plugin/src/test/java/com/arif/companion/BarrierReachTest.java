package com.arif.companion;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import static com.arif.companion.PlannerRulesTest.TestWorld;
import static com.arif.companion.PlannerRulesTest.block;
import static com.arif.companion.PlannerRulesTest.fenceData;
import static com.arif.companion.PlannerRulesTest.glassPaneData;

/**
 * Checks what a sword does and does not reach through.
 *
 * <p>The complaint: the companions walked past a mob that was right there, penned in behind a
 * fence, and went off to fight something far away instead. A mob behind a fence is not out of
 * reach — a player hits one through the fence without stepping into the pen, and so does a
 * companion. The line of sight was asking the wrong question: it asked whether the mob was
 * visible, which a fence answers no.</p>
 *
 * <p>So there are now two kinds of line. The shot line asks whether a trident can fly there,
 * and a fence blocks it. The melee line asks whether a sword can reach there, and a fence does
 * not — only a block that really fills its space stands in the way.</p>
 *
 * <p>Run it against a compiled plugin:</p>
 * <pre>
 * javac -cp "&lt;paper-api&gt;;plugin/build/classes" -d plugin/build plugin/src/test/java/com/arif/companion/BarrierReachTest.java
 * java -cp "&lt;paper-api&gt;;plugin/build/classes" com.arif.companion.BarrierReachTest
 * </pre>
 */
public final class BarrierReachTest {

    public static void main(String[] args) {
        System.out.println("=== a sword reaches through a fence ===");
        TestWorld testWorld = new TestWorld();
        World world = testWorld.proxy();
        testWorld.fill(0, 0, 0, 8, 0, 8, block(Material.STONE, 1.0, null));
        // A fence standing between the companion and the mob.
        testWorld.set(4, 1, 4, block(Material.OAK_FENCE, 1.5, fenceData()));
        check("the fence blocks a thrown trident", false,
                CompanionLineOfSight.clearBetween(world, 1.5, 1.62, 4.5, 7.5, 1.62, 4.5));
        check("the fence does not block a sword", true,
                CompanionLineOfSight.clearBetweenMelee(world, 1.5, 1.62, 4.5, 7.5, 1.62, 4.5));

        System.out.println();
        System.out.println("=== a sword reaches through glass and iron bars ===");
        TestWorld glassWorld = new TestWorld();
        World glass = glassWorld.proxy();
        glassWorld.fill(0, 0, 0, 8, 0, 8, block(Material.STONE, 1.0, null));
        glassWorld.set(4, 1, 4, block(Material.GLASS_PANE, 1.0, glassPaneData()));
        check("glass does not block a sword", true,
                CompanionLineOfSight.clearBetweenMelee(glass, 1.5, 1.62, 4.5, 7.5, 1.62, 4.5));

        TestWorld barsWorld = new TestWorld();
        World bars = barsWorld.proxy();
        barsWorld.fill(0, 0, 0, 8, 0, 8, block(Material.STONE, 1.0, null));
        barsWorld.set(4, 1, 4, block(Material.IRON_BARS, 1.0, null));
        check("iron bars do not block a sword", true,
                CompanionLineOfSight.clearBetweenMelee(bars, 1.5, 1.62, 4.5, 7.5, 1.62, 4.5));

        System.out.println();
        System.out.println("=== a solid wall still blocks a sword ===");
        TestWorld wallWorld = new TestWorld();
        World wall = wallWorld.proxy();
        wallWorld.fill(0, 0, 0, 8, 0, 8, block(Material.STONE, 1.0, null));
        for (int y = 1; y <= 3; y++) {
            wallWorld.set(4, y, 4, block(Material.STONE, 1.0, null));
        }
        check("a stone wall blocks a sword", false,
                CompanionLineOfSight.clearBetweenMelee(wall, 1.5, 1.62, 4.5, 7.5, 1.62, 4.5));
        check("a stone wall blocks a trident too", false,
                CompanionLineOfSight.clearBetween(wall, 1.5, 1.62, 4.5, 7.5, 1.62, 4.5));

        System.out.println();
        System.out.println("=== a slab on the floor is not a wall ===");
        // A slab fills half its block, so a level line above the floor passes over it.
        TestWorld slabWorld = new TestWorld();
        World slab = slabWorld.proxy();
        slabWorld.fill(0, 0, 0, 8, 0, 8, block(Material.STONE, 1.0, null));
        slabWorld.set(4, 1, 4, block(Material.OAK_SLAB, 0.5, null));
        check("a slab does not block a sword", true,
                CompanionLineOfSight.clearBetweenMelee(slab, 1.5, 1.62, 4.5, 7.5, 1.62, 4.5));

        System.out.println();
        System.out.println("=== the ground a companion stands on beside a pen ===");
        // The companion has to stand outside the pen and hit the mob through the fence. The
        // spot it stands on has to be real ground, not the fence itself.
        TestWorld penWorld = new TestWorld();
        World pen = penWorld.proxy();
        penWorld.fill(0, 0, 0, 10, 0, 10, block(Material.STONE, 1.0, null));
        // The fence runs along x = 5, from z = 0 to z = 10.
        for (int z = 0; z <= 10; z++) {
            penWorld.set(5, 1, z, block(Material.OAK_FENCE, 1.5, fenceData()));
        }
        // The spot on the near side of the fence, within a sword's reach of the pen.
        Location outside = new Location(pen, 4.5, 1.0, 5.5);
        check("the ground outside the pen can be stood on", true,
                WaitPostResolver.standingAt(pen, outside) != null);
        // The fence itself is not a floor to stand on.
        Location onFence = new Location(pen, 5.5, 1.0, 5.5);
        check("the fence is not a floor", false,
                WaitPostResolver.standingAt(pen, onFence) != null);
    }

    private static void check(String label, boolean expected, boolean actual) {
        String result = expected == actual ? "OK  " : "FAIL";
        System.out.println(String.format("%-4s %s", result, label));
    }
}
