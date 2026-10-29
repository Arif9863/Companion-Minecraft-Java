# Companion NPCs (Minecraft Java 26.3 / Paper 26.3)

This plugin spawns skin-bearing `Mannequin` entities, using only Paper's public Bukkit API. No fake-player packets, ProtocolLib, or NMS access required.

## Build and install

1. Stop the production Paper server with `stop` in its console and wait for `Done`/shutdown completion.
2. Build with `build-plugin.ps1` in `G:\GameServerJava` (compiles with Java 25 and copies the JAR into `Paper/Paper26.3/plugins/`). `mvn package` also works.
3. Make sure the resource pack is enabled on the client. For server auto-download, host the ZIP at HTTPS and configure `resource-pack` in `server.properties`; manual client activation also works.
4. Start Paper once. Plugin creates `plugins/CompanionNPCs/config.yml`.

## Commands

- `/yui-npc` (alias `/yui-chan`) — spawn or replace the Yui mannequin near you.
- `/yui-npc off` — dismiss Yui.
- `/sandrone-npc` (alias `/sandrone-chan`), `/sandrone-npc off` — same for Sandrone.
- `/kesini [yui|sandrone|semua]` — switch the companions to **CLOSE** mode.
- `/ikut [yui|sandrone|semua]` — switch them back to **FOLLOW** mode.
- `/companionnpc clear` — remove every tagged companion mannequin.
- `/companionnpc reload` — reload config.

Commands intentionally keep the `-npc` suffix so they do not replace the existing Skript `/yui` and `/sandrone` commands, which wrap these NPC commands and add the voice system.

## Behaviour modes

A companion has exactly one standing behaviour at a time. **The mode stays in force until another command replaces it** — there is no timeout, and nothing silently reverts it.

| | FOLLOW (`/ikut`) | CLOSE (`/kesini`) |
|---|---|---|
| Position | Formation: Yui on your left, Sandrone on your right | As close as the geometry allows, no formation |
| Standing distance | `comfort-distance` (3.0 blocks) | `close-distance` (1.8 blocks) |
| Emergency teleport target | Its own formation side | A spot in the room you can walk to |

- FOLLOW is the default: a freshly spawned companion starts in this mode.
- The chosen mode is saved per player in `plugins/CompanionNPCs/modes.yml` and restored when the companion is respawned or the server restarts.

## Movement rules

Mannequins are not `Mob` and have no vanilla pathfinder, so the plugin computes an A* route itself (`PathPlanner`) and walks it with velocity.

- **Never through walls.** A route only exists if every step is walkable. A node without solid ground beneath it is rejected, so a route cannot lead across the void of a ravine.
- **Never down a cliff.** A route descends at most one block per step.
- **Never into the void.** When the companion has no route for 5 seconds, or is more than 8 blocks below you while standing on ground, it is rescued to its mode's safe landing spot.
- **Emergency teleports are distance-based** (`teleport-distance`, `close-teleport-distance`, both 24 blocks by default). A blocked route alone never causes a teleport.
- **Doors are opened on the way** and closed again afterwards. When two companions pass through the same doorway, the first one holds the door open for the second, but for at most 6 seconds, so a house is never left open.
- **Swimming works.** Water is a valid route node, and a companion in water swims after you instead of teleporting.

## Skin methods

### Bundled resource-pack texture (recommended)

Drop Minecraft player skin PNG files (64x64 PNG, classic or slim model) here:

- `resource-pack/assets/companion/textures/yui.png`
- `resource-pack/assets/companion/textures/sandrone.png`

Use matching profile patch keys `companion:yui` and `companion:sandrone`. Rebuild `companion-voices-26.3.zip` after adding skins, then update the client's `.minecraft/resourcepacks/` copy. The plugin sets this pack key on each mannequin profile.

### Hosted URL fallback

Set `yui.skin-url` and/or `sandrone.skin-url` in `plugins/CompanionNPCs/config.yml` to a publicly reachable HTTPS skin PNG URL. Then run `/companionnpc reload` and respawn NPC. Mojang's `textures.minecraft.net` URLs are safest. This method requires client internet access.

## Config

`plugins/CompanionNPCs/config.yml`:

| Key | Default | Meaning |
|---|---|---|
| `comfort-distance` | 3.0 | Standing distance in FOLLOW mode |
| `close-distance` | 1.8 | Standing distance in CLOSE mode |
| `teleport-distance` | 24 | Emergency teleport radius in FOLLOW mode (16–32) |
| `close-teleport-distance` | 24 | Emergency teleport radius in CLOSE mode (16–32) |
| `idle-look-mob-radius` | 12 | How far an idle companion glances at a nearby mob (4–24) |
| `idle-look-standstill-distance` | 6 | How far a still companion may glance at mobs (2–12) |
| `pack-http-server` | true | Serve the resource pack over HTTP |
| `pack-http-port` | 8765 | Port for that HTTP server |
| `debug-self-test` | false | Spawn both NPCs at startup — test server only |

## Cleanup

NPCs despawn when the owner quits. The plugin cleans tagged strays on disable and on `/companionnpc clear`.

## Tests

Compile against Paper 26.3, then use an isolated server copy on port 25566. Set `debug-self-test: true` only there to spawn both mannequins at test-world spawn and log `SELF-TEST PASS`; default stays false. Do not enable debug self-test on production.
