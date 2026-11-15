package com.arif.companion;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import static com.arif.companion.PlannerRulesTest.TestWorld;
import static com.arif.companion.PlannerRulesTest.block;
import static com.arif.companion.PlannerRulesTest.doorData;

/**
 * Checks that a companion only throws at a mob it can actually see.
 *
 * <p>Columbina and Escoffier throw their trident, and a trident does not stop at a wall. A
 * companion that throws at a mob behind a wall sends the trident into the wall, so the line
 * between the thrower and the mob has to be clear before the weapon leaves the hand.</p>
 *
 * <p>Run it against a compiled plugin:</p>
 * <pre>
 * javac -cp "&lt;paper-api&gt;;plugin/build/classes" -d plugin/build plugin/src/test/java/com/arif/companion/LineOfSightTest.java
 * java -cp "&lt;paper-api&gt;;plugin/build/classes" com.arif.companion.LineOfSightTest
 * </pre>
 */
public final class LineOfSightTest {

    public static void main(String[] args) {
        System.out.println("=== a wall between them blocks the shot ===");
        TestWorld testWorld = new TestWorld();
        World world = testWorld.proxy();
        // A floor, and a wall standing between the thrower and the mob.
        testWorld.fill(0, 0, 0, 8, 0, 8, block(Material.STONE, 1.0, null));
        for (int y = 1; y <= 3; y++) {
            testWorld.set(4, y, 4, block(Material.STONE, 1.0, null));
        }
        check("a wall blocks the shot", false, clear(world, 1.5, 1.62, 4.5, 7.5, 1.62, 4.5));

        System.out.println();
        System.out.println("=== an open field does not ===");
        TestWorld openWorld = new TestWorld();
        World open = openWorld.proxy();
        openWorld.fill(0, 0, 0, 8, 0, 8, block(Material.STONE, 1.0, null));
        check("an open line is clear", true, clear(open, 1.5, 1.62, 4.5, 7.5, 1.62, 4.5));

        System.out.println();
        System.out.println("=== an opening in the wall lets the shot through ===");
        TestWorld windowWorld = new TestWorld();
        World window = windowWorld.proxy();
        windowWorld.fill(0, 0, 0, 8, 0, 8, block(Material.STONE, 1.0, null));
        for (int y = 1; y <= 3; y++) {
            windowWorld.set(4, y, 4, block(Material.STONE, 1.0, null));
        }
        // The block the line actually passes through is taken out, so the line can go through.
        windowWorld.set(4, 1, 4, block(Material.AIR, 0.0, null));
        check("an opening in the wall lets the shot through", true,
                clear(window, 1.5, 1.62, 4.5, 7.5, 1.62, 4.5));

        System.out.println();
        System.out.println("=== a shot that rises into a wall is blocked ===");
        // The same wall, but the mob stands well below the thrower, so the line climbs and
        // runs into the block above the opening.
        check("a line that climbs into the wall is blocked", false,
                clear(window, 1.5, 1.62, 4.5, 7.5, 3.5, 4.5));
        check("a level line through the opening is clear", true,
                clear(window, 1.5, 1.62, 4.5, 7.5, 1.62, 4.5));

        System.out.println();
        System.out.println("=== a closed door blocks it, an open one does not ===");
        TestWorld doorWorld = new TestWorld();
        World door = doorWorld.proxy();
        doorWorld.fill(0, 0, 0, 8, 0, 8, block(Material.STONE, 1.0, null));
        for (int y = 1; y <= 2; y++) {
            doorWorld.set(4, y, 4, block(Material.OAK_DOOR, 1.0, doorData()));
        }
        // A door has a collision shape, but the companion opens it on the way through, so it
        // does not stop the shot.
        check("a door does not block the shot", true,
                clear(door, 1.5, 1.62, 4.5, 7.5, 1.62, 4.5));

        System.out.println();
        System.out.println("=== a trapdoor does block it ===");
        TestWorld trapWorld = new TestWorld();
        World trap = trapWorld.proxy();
        trapWorld.fill(0, 0, 0, 8, 0, 8, block(Material.STONE, 1.0, null));
        trapWorld.set(4, 1, 4, block(Material.OAK_TRAPDOOR, 1.0, null));
        check("a trapdoor blocks the shot", false,
                clear(trap, 1.5, 1.62, 4.5, 7.5, 1.62, 4.5));

        System.out.println();
        System.out.println("=== things that do not fill their space do not block it ===");
        TestWorld grassWorld = new TestWorld();
        World grass = grassWorld.proxy();
        grassWorld.fill(0, 0, 0, 8, 0, 8, block(Material.STONE, 1.0, null));
        // A carpet on the floor, right in the line.
        grassWorld.set(4, 1, 4, block(Material.WHITE_CARPET, 0.0625, null));
        check("a carpet in the line does not block the shot", true,
                clear(grass, 1.5, 1.62, 4.5, 7.5, 1.62, 4.5));

        System.out.println();
        System.out.println("=== a solid block in the line does block it ===");
        TestWorld solidWorld = new TestWorld();
        World solid = solidWorld.proxy();
        solidWorld.fill(0, 0, 0, 8, 0, 8, block(Material.STONE, 1.0, null));
        solidWorld.set(4, 1, 4, block(Material.STONE, 1.0, null));
        check("a stone block in the line blocks the shot", false,
                clear(solid, 1.5, 1.62, 4.5, 7.5, 1.62, 4.5));

        System.out.println();
        System.out.println("=== a fence blocks it ===");
        TestWorld fenceWorld = new TestWorld();
        World fence = fenceWorld.proxy();
        fenceWorld.fill(0, 0, 0, 8, 0, 8, block(Material.STONE, 1.0, null));
        fenceWorld.set(4, 1, 4, block(Material.OAK_FENCE, 1.5, null));
        check("a fence blocks the shot", false, clear(fence, 1.5, 1.62, 4.5, 7.5, 1.62, 4.5));
    }

    private static boolean clear(World world, double fromX, double fromY, double fromZ,
                                 double toX, double toY, double toZ) {
        return CompanionLineOfSight.clearBetween(world, fromX, fromY, fromZ, toX, toY, toZ);
    }

    private static void check(String label, boolean expected, boolean actual) {
        String result = expected == actual ? "OK  " : "FAIL";
        System.out.println(String.format("%-4s %s", result, label));
    }
}
