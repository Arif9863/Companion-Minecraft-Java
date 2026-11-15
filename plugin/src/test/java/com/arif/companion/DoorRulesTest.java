package com.arif.companion;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Gate;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.util.Vector;

import java.lang.reflect.Proxy;
import java.util.List;

import static com.arif.companion.PlannerRulesTest.TestWorld;
import static com.arif.companion.PlannerRulesTest.block;

/**
 * Checks how a companion deals with doors.
 *
 * <p>Two problems came up: a waiting companion wandering inside a house opened a door it had no
 * intention of walking through and left it standing open, and a door sometimes stayed open for
 * far longer than it should. The door is now found the way a villager finds it — on the route
 * the companion is actually walking — so the rules are exercised here on a hand-built world.</p>
 *
 * <p>Run it against a compiled plugin:
 * <pre>
 * javac -cp "&lt;paper-api&gt;;plugin/build/classes" -d plugin/build plugin/src/test/java/com/arif/companion/DoorRulesTest.java
 * java -cp "&lt;paper-api&gt;;plugin/build/classes" com.arif.companion.DoorRulesTest
 * </pre>
 */
public final class DoorRulesTest {

    public static void main(String[] args) {
        System.out.println("=== only wooden doors, copper doors and fence gates are operated ===");
        check("a wooden door may be operated", true,
                CompanionManager.isDoorOrGate(block(Material.OAK_DOOR, 1.0, doorData())));
        check("a copper door may be operated", true,
                CompanionManager.isDoorOrGate(block(Material.COPPER_DOOR, 1.0, doorData())));
        check("a waxed copper door may be operated", true,
                CompanionManager.isDoorOrGate(block(Material.WAXED_COPPER_DOOR, 1.0, doorData())));
        check("a fence gate may be operated", true,
                CompanionManager.isDoorOrGate(block(Material.OAK_FENCE_GATE, 1.0, gateData())));
        check("a trapdoor may not", false,
                CompanionManager.isDoorOrGate(block(Material.OAK_TRAPDOOR, 1.0, trapData())));
        check("an iron door may not", false,
                CompanionManager.isDoorOrGate(block(Material.IRON_DOOR, 1.0, doorData())));
        check("a barrel may not", false,
                CompanionManager.isDoorOrGate(block(Material.BARREL, 1.0, null)));

        System.out.println();
        System.out.println("=== a door on the route is found ===");
        TestWorld testWorld = new TestWorld();
        World world = testWorld.proxy();
        testWorld.fill(0, 0, 0, 8, 0, 4, block(Material.STONE, 1.0, null));
        // A door standing in a doorway, two blocks along the route.
        testWorld.set(4, 1, 2, block(Material.OAK_DOOR, 1.0, doorData()));
        testWorld.set(4, 2, 2, block(Material.OAK_DOOR, 1.0, doorData()));
        List<Vector> route = PathPlanner.findPath(world, new Location(world, 0.5, 1.0, 2.5),
                new Location(world, 7.5, 1.0, 2.5), 16, false, false, false);
        check("a route through the door exists", true, !route.isEmpty());
        // The route reaches the doorway as its fourth waypoint. The door is looked for a few
        // waypoints ahead of the companion, so it comes into view once the companion is
        // walking towards it — which is the only moment it is opened anyway.
        List<Location> doors = CompanionManager.doorsOnRoute(world, route, 3);
        check("the door on the route is found", true,
                doors.contains(new Location(world, 4.0, 1.0, 2.0))
                        || doors.contains(new Location(world, 4.0, 2.0, 2.0)));
        // While the companion is still at the far end of the room, the door is on its route but
        // out of reach, so it stays shut even though it has been spotted.
        check("the door is spotted from the start", true,
                !CompanionManager.doorsOnRoute(world, route, 0).isEmpty());

        System.out.println();
        System.out.println("=== a door is only opened once the companion is there ===");
        // The companion is still at the far end of the room: the door is on its route, but it
        // has not reached it yet, so it must stay shut.
        Location farAway = new Location(world, 0.5, 1.0, 2.5);
        check("a door that is still far away is not opened", true,
                CompanionManager.doorsWithinReach(world, route, 0, farAway).isEmpty());
        // Standing in the doorway, the door is within reach and is opened.
        Location atTheDoor = new Location(world, 4.5, 1.0, 2.5);
        check("a door the companion has reached is opened", true,
                !CompanionManager.doorsWithinReach(world, route, 3, atTheDoor).isEmpty());
        // Just short of the doorway, the door is within reach too.
        Location almostThere = new Location(world, 2.5, 1.0, 2.5);
        check("a door a step away is opened", true,
                !CompanionManager.doorsWithinReach(world, route, 2, almostThere).isEmpty());

        System.out.println();
        System.out.println("=== a door the companion is not walking through is left alone ===");
        // The same world, but the companion's route turns away from the door. A waiting
        // companion wandering inside a house used to open the door beside it and leave it
        // standing open.
        List<Vector> awayRoute = List.of(
                new Vector(0.5, 1.0, 0.5),
                new Vector(0.5, 1.0, 1.5),
                new Vector(0.5, 1.0, 2.5),
                new Vector(1.5, 1.0, 2.5));
        List<Location> awayDoors = CompanionManager.doorsOnRoute(world, awayRoute, 0);
        check("no door is found on a route that misses it", true, awayDoors.isEmpty());
        check("and nothing is opened there", true,
                CompanionManager.doorsWithinReach(world, awayRoute, 0,
                        new Location(world, 0.5, 1.0, 2.5)).isEmpty());

        System.out.println();
        System.out.println("=== a route with no door finds nothing ===");
        check("an empty route finds nothing", true,
                CompanionManager.doorsOnRoute(world, List.of(), 0).isEmpty());
        check("a route without doors finds nothing", true,
                CompanionManager.doorsOnRoute(world, awayRoute, 0).isEmpty());

        System.out.println();
        System.out.println("=== a fence gate on the route is found ===");
        TestWorld gateWorld2 = new TestWorld();
        World gateWorld = gateWorld2.proxy();
        gateWorld2.fill(0, 0, 0, 8, 0, 4, block(Material.STONE, 1.0, null));
        gateWorld2.set(4, 1, 2, block(Material.OAK_FENCE_GATE, 1.0, gateData()));
        List<Vector> gateRoute = PathPlanner.findPath(gateWorld, new Location(gateWorld, 0.5, 1.0, 2.5),
                new Location(gateWorld, 7.5, 1.0, 2.5), 16, false, false, false);
        check("a route through the gate exists", true, !gateRoute.isEmpty());
        List<Location> gateDoors = CompanionManager.doorsOnRoute(gateWorld, gateRoute, 3);
        check("the gate on the route is found", true,
                gateDoors.contains(new Location(gateWorld, 4.0, 1.0, 2.0)));

        System.out.println();
        System.out.println("=== a door behind the companion is not reopened ===");
        // Only the waypoints ahead of the companion are looked at, so a door it has already
        // walked through is not opened again.
        check("the door behind is not found", true,
                CompanionManager.doorsOnRoute(world, route, route.size() - 1).isEmpty()
                        || !CompanionManager.doorsOnRoute(world, route, route.size() - 1)
                        .contains(new Location(world, 4.0, 1.0, 2.0)));

        System.out.println();
        System.out.println("=== the block a waypoint stands on is worked out correctly ===");
        // A waypoint at a whole number is the top of a full block, so the block it is in is
        // the one below. This is what puts the door of a doorway at the right height.
        check("a waypoint at a whole number is the block below it", true,
                CompanionManager.blockOfWaypoint(new Vector(4.5, 2.0, 2.5)) == 1);
        // A waypoint on a carpet sits inside the block, so the block it is in is that one.
        check("a waypoint in between is the block it starts in", true,
                CompanionManager.blockOfWaypoint(new Vector(4.5, 1.0625, 2.5)) == 1);
        check("a waypoint on the floor is the block it starts in", true,
                CompanionManager.blockOfWaypoint(new Vector(4.5, 1.0, 2.5)) == 0);
    }

    private static BlockData proxy(Class<?> type, boolean open) {
        return (BlockData) Proxy.newProxyInstance(
                DoorRulesTest.class.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> {
                    if (method.getName().equals("isOpen")) {
                        return open;
                    }
                    return PlannerRulesTest.defaultValue(method.getReturnType());
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

    private static void check(String label, boolean expected, boolean actual) {
        String result = expected == actual ? "OK  " : "FAIL";
        System.out.println(String.format("%-4s %s", result, label));
    }
}
