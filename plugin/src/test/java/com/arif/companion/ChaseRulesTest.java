package com.arif.companion;

import com.arif.companion.CompanionManager.FollowMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import static com.arif.companion.PlannerRulesTest.TestWorld;
import static com.arif.companion.PlannerRulesTest.block;

/**
 * Checks the rules that decide when a chase ends and when the companion is caught up.
 *
 * <p>A fight is held until the mob dies: distance never ends a chase. The two things that can
 * end it are the mob dying (the live-entity check in {@code resolveAttackTarget}) and ten
 * seconds without a route to it. The routine catch-up teleport never fires mid-chase — it
 * would stop the companion from ever finishing the mob — and never fires in WAIT mode at all.</p>
 *
 * <p>A waiting companion watches its own ground only. The circle around the owner is not
 * watched in WAIT mode, so a companion left at home does not walk out of the house to chase a
 * mob the owner met on the road.</p>
 *
 * <p>Run it against a compiled plugin:</p>
 * <pre>
 * javac -cp "&lt;paper-api&gt;;plugin/build/classes" -d plugin/build plugin/src/test/java/com/arif/companion/ChaseRulesTest.java
 * java -cp "&lt;paper-api&gt;;plugin/build/classes" com.arif.companion.ChaseRulesTest
 * </pre>
 */
public final class ChaseRulesTest {

    /** The configured catch-up distance used throughout these checks (the default). */
    private static final int TELEPORT_DISTANCE = 24;

    public static void main(String[] args) {
        System.out.println("=== a fight is never ended by distance ===");
        // The chase rules themselves: the only way a held target ends before the mob dies is
        // ten seconds without a route to it. A mob that runs forty blocks away is still the
        // one being fought.
        long now = 100_000L;
        check("a chase with a route is never abandoned", false,
                CompanionManager.chaseAbandoned(now, Long.MIN_VALUE));
        check("a chase nine seconds without a route keeps going", false,
                CompanionManager.chaseAbandoned(now, now - 180L));
        check("a chase ten seconds without a route is abandoned", true,
                CompanionManager.chaseAbandoned(now, now - 200L));
        check("a chase much longer without a route is abandoned", true,
                CompanionManager.chaseAbandoned(now, now - 999L));

        System.out.println();
        System.out.println("=== the catch-up teleport never fires mid-chase ===");
        // Yanking the companion home every time it steps past the radius would stop it from
        // ever finishing the mob, so the catch-up waits until the fight is over. A waiting
        // companion is never caught up at all: it was told to stay where it is.
        check("a following companion with no fight is caught up far from the owner", true,
                CompanionManager.catchUpTeleportAllowed(FollowMode.FOLLOW, false, 30.0,
                        TELEPORT_DISTANCE));
        check("a following companion mid-chase is not caught up", false,
                CompanionManager.catchUpTeleportAllowed(FollowMode.FOLLOW, true, 30.0,
                        TELEPORT_DISTANCE));
        check("a following companion inside the radius is not caught up", false,
                CompanionManager.catchUpTeleportAllowed(FollowMode.FOLLOW, false, 10.0,
                        TELEPORT_DISTANCE));
        check("exactly at the catch-up distance is not caught up", false,
                CompanionManager.catchUpTeleportAllowed(FollowMode.FOLLOW, false, 24.0,
                        TELEPORT_DISTANCE));
        check("a closing companion with no fight is caught up far from the owner", true,
                CompanionManager.catchUpTeleportAllowed(FollowMode.CLOSE, false, 30.0,
                        TELEPORT_DISTANCE));
        check("a closing companion mid-chase is not caught up", false,
                CompanionManager.catchUpTeleportAllowed(FollowMode.CLOSE, true, 30.0,
                        TELEPORT_DISTANCE));
        check("a waiting companion is never caught up", false,
                CompanionManager.catchUpTeleportAllowed(FollowMode.WAIT, false, 30.0,
                        TELEPORT_DISTANCE));
        check("a waiting companion mid-chase is never caught up", false,
                CompanionManager.catchUpTeleportAllowed(FollowMode.WAIT, true, 30.0,
                        TELEPORT_DISTANCE));

        System.out.println();
        System.out.println("=== auto-spawn chooses wait posts only in WAIT mode ===");
        Location spot = new Location(new TestWorld("world").proxy(), 1, 64, 2);
        check("WAIT mode uses a saved wait post", true,
                CompanionManager.shouldSpawnAtWaitPost(FollowMode.WAIT, spot));
        check("FOLLOW mode ignores a saved wait post", false,
                CompanionManager.shouldSpawnAtWaitPost(FollowMode.FOLLOW, spot));
        check("WAIT mode without a post falls back", false,
                CompanionManager.shouldSpawnAtWaitPost(FollowMode.WAIT, null));

        System.out.println();
        System.out.println("=== a waiting companion watches its own ground only ===");
        // The circle around the owner is what answers a distant attacker. A companion holding
        // a post is not pulled out of its own ground by a fight that happens elsewhere, so it
        // does not watch that circle at all.
        check("a following companion watches the owner's circle", true,
                CompanionManager.ownerThreatSearchEnabled(FollowMode.FOLLOW));
        check("a closing companion watches the owner's circle", true,
                CompanionManager.ownerThreatSearchEnabled(FollowMode.CLOSE));
        check("a waiting companion does not watch the owner's circle", false,
                CompanionManager.ownerThreatSearchEnabled(FollowMode.WAIT));

        System.out.println();
        System.out.println("=== the ground a waiting companion holds ===");
        TestWorld testWorld = new TestWorld();
        World world = testWorld.proxy();
        testWorld.fill(0, 0, 0, 30, 0, 30, block(Material.STONE, 1.0, null));
        Location post = new Location(world, 2.5, 1.0, 2.5);
        Location near = new Location(world, 8.5, 1.0, 2.5);
        Location far = new Location(world, 20.5, 1.0, 2.5);
        double leash = 12.0;
        check("a mob near the post is on its ground", true,
                CompanionManager.withinWaitTerritory(near, post, leash));
        check("a mob far from the post is not on its ground", false,
                CompanionManager.withinWaitTerritory(far, post, leash));
        check("a mob right at the edge of the ground still counts", true,
                CompanionManager.withinWaitTerritory(new Location(world, 14.5, 1.0, 2.5), post,
                        leash));
        check("without a post there is no ground to hold", false,
                CompanionManager.withinWaitTerritory(near, null, leash));
    }

    private static void check(String label, boolean expected, boolean actual) {
        String result = expected == actual ? "OK  " : "FAIL";
        System.out.println(String.format("%-4s %s", result, label));
    }
}
