# Companion NPCs (Minecraft Java 26.3 / Paper 26.3)

This plugin spawns skin-bearing `Mannequin` entities, using only Paper's public Bukkit API. No fake-player packets, ProtocolLib, or NMS access required.

## Build and install

The build scripts are location-independent: they resolve every path from their own
location, find a JDK through `JAVA_HOME` or the usual install folders, and locate a
Paper server folder by looking for its `libraries/io/papermc/paper/paper-api`.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\build-plugin.ps1
```

- Stops nothing; build the JAR, then restart the server to load it.
- If the server folder cannot be found the script falls back to `mvn package`
  (`plugin/pom.xml` declares the PaperMC repository, so Maven downloads the API).
- `-ServerDir <path>` points at a server elsewhere, `-SkipDeploy` builds without
  copying into `plugins/`.
- `.\rebuild-pack.ps1` rebuilds the voice resource pack the same way; pass a server
  folder as its first argument when there is more than one.

### What the build script does, and what it does not

The build script and the in-game config updater are two separate things:

| | Build script (`build-plugin.ps1`) | Config updater (in the plugin) |
|---|---|---|
| Produces | The plugin JAR | The `config.yml` on the server |
| Copies into `plugins/` | Yes, unless `-SkipDeploy` | No |
| Updates `config.yml` | No | Yes, adds missing options only |
| Where it runs | Your development machine | On the server, on startup |

So a normal release is: run the build script, restart the server, and the config on that
server gains the new options by itself. You do **not** copy a `config.yml` from
`plugin/build/classes/` onto a server — that file is the packaged default, and replacing a
server's config with it would throw away the owner's own settings.

With `-SkipDeploy` the JAR is only written to `plugin/build/`; the server keeps running the
old version until the JAR is copied into its `plugins/` folder by hand.

1. Stop the production Paper server with `stop` in its console and wait for `Done`/shutdown completion.
2. Run `build-plugin.ps1` as above, or `mvn package` from `plugin/`.
3. Make sure the resource pack is enabled on the client. For server auto-download, host the ZIP at HTTPS and configure `resource-pack` in `server.properties`; manual client activation also works.
4. Start Paper once. Plugin creates `plugins/CompanionNPCs/config.yml` if it is missing, and
   adds any options a newer plugin version knows about to an existing one.

### Moving to another machine

```bash
git clone https://github.com/Arif9863/Companion-Minecraft-Java.git
```

The clone carries the plugin sources, both companion Skript scripts, the voice audio,
the companion skins, `pack.mcmeta`, and both build scripts. It does not carry the
world, `server.properties`, or the Paper server's `libraries/` — install Paper
there yourself, or rely on the Maven fallback above.

## Commands

- `/yui-npc` (alias `/yui-chan`) — spawn or replace the Yui mannequin near you.
- `/yui-npc off` — dismiss Yui.
- `/sandrone-npc` (alias `/sandrone-chan`), `/sandrone-npc off` — same for Sandrone.
- `/kesini [yui|sandrone|semua]` — switch the companions to **CLOSE** mode.
- `/ikut [yui|sandrone|semua]` — switch them back to **FOLLOW** mode.
- `/tunggu [yui|sandrone|semua]` — switch them to **WAIT** mode: they stay where they are.

These commands cover **Yui and Sandrone only**. `semua` means the pair, never Eris — she has
her own commands below.
- `/companionnpc clear` — remove every tagged companion mannequin.
- `/companionnpc reload` — reload config.
- `/companionnpc config` — add the options this plugin version knows about to the config file
  on disk, without touching the values that are already set.
- `/eris` (alias `/eris-chan`) — call Eris, the standalone companion.
- `/eris ikut`, `/eris kesini`, `/eris tunggu`, `/eris off` — control her.

**Yui and Sandrone are UUID-locked.** Only UUIDs listed in `companion-owners` in
`plugins/CompanionNPCs/config.yml` may call them or use their behaviour commands; OP status does
not bypass the restriction. The default list holds Arif's UUID. Add another player's UUID on its
own line under `companion-owners` to grant access, then restart the server or run
`/companionnpc reload`. Commands never touch another player's companion.

Commands intentionally keep the `-npc` suffix so they do not replace the existing Skript `/yui` and `/sandrone` commands, which wrap these NPC commands and add the voice system. Companion behavior stays command-driven; no extra GUI or control item is required.

The Skript `/yui` and `/sandrone` commands check the same list before they say anything. The plugin marks every allowed player with the `companion_npc_user` scoreboard tag, and the scripts refuse outsiders with their own line ("Hati dan kasih Yui hanya untuk Arif") instead of the friendly greeting. The tag is set on join and refreshed by `/companionnpc reload`.

While a companion mannequin is alive, the plugin also sets `companion_active_yui`, `companion_active_sandrone`, or `companion_active_eris` on that companion's owner. Skript uses those tags (not the older `{-yui-active::...}` flags alone) for heal/absorption, DuoVoice, and 3D sound routing, so another player never receives your companion's buffs or hears their lines from the wrong direction.

## Eris (standalone companion)

Eris is a companion of her own, not a third member of the Yui–Sandrone pair.

- **One permanent owner.** `eris-owner` in the config names the player Eris is reserved for, and
  only that player can call her and control her. The configured UUID wins over anything stored
  earlier, so changing it really moves her. Anybody else who calls her gets her refusal:
  *"Hmph. Jangan panggil-panggil aku! Aku cuma ikut Ferdi-Kun—bukan milikmu!"*
- **Testers lose access.** `eris-testers` lists players who may use Eris while the owner has not
  called her yet, so a tester can try her out. As soon as the owner calls her, every tester
  command stops working and only the owner is left.
- **Neutral formation.** She has no partner to pair up with, so in FOLLOW mode she stands behind
  the owner instead of claiming the left or right side.
- **Support fighter.** While she is fighting next to her owner she refreshes **Speed I** on them
  every 5 seconds, and the buff lasts 10 seconds. After her **second kill** she also grants
  **Strength I** for 15 seconds, once per session.
- **Her own pouch.** Right-click Eris to open a private 27-slot bag, saved in
  `plugins/CompanionNPCs/eris-pouch.yml`. Only the owner can open it; anybody else is refused.
- **Her own voice.** Eris has 13 dialogue lines (8 idle, 2 kill, 2 support, 1 follow-up), sent
  only to her owner. There are no voice files — the text alone carries her character.
- **No danger markers.** The skeleton/creeper markers belong to Yui and Sandrone; Eris marks
  nothing, so she never duplicates a partner's alert.

## Defensive melee (automatic)

Defending is automatic: there is no command for it any more. A companion always reacts to a
hostile mob that is coming for its owner or for the companion itself, in every mode.

- **Defensive only.** A companion engages a hostile mob only once that mob is coming for the
  owner or for a companion. A mob that is merely standing nearby is ignored.
- **A waiting companion also holds its ground.** In WAIT it engages a hostile that walks into
  `wait-threat-radius` blocks of the spot it was told to wait at, even if that mob has not
  chosen a target yet. The threat requirement still stands on its own — the two are combined,
  not swapped: a mob that comes for the party counts in every mode, and the territorial rule
  adds the ones that merely intrude. A mob further out is left alone rather than chased.
- **Neutral hostiles are left alone.** An Enderman counts as a threat only once it actually
  comes for the owner or a companion — a waiting companion does not pick a fight with one that
  merely strolls past, since it only strikes when provoked and can turn on the owner instead.
- **The search is centred on the companion**, never on the owner. This is what makes a waiting
  companion stay where it was told to wait instead of running off to rescue the owner across the
  map — and then standing where the last mob died.
- **A waiting companion fights on its own ground.** It engages a hostile that comes within
  `wait-threat-radius` blocks of the spot it was told to wait at. It is on a leash there: if the
  mob drags it further than that radius from its spot, it drops the fight and walks back. Once the
  fight is over it returns to its spot and resumes its wander. Raising this radius widens the
  threats it reacts to; it does not change the 5-block idle wander.
- **It walks up to the mob and looks at it** while closing in, then keeps facing it while it
  strikes — so the fight reads like a player fighting, not like a turret.
- **It swings like a player.** The arm animation is the player's own swing, played every
  `attack-cooldown-ticks` ticks while within the vanilla melee reach of 3 blocks; the swing deals
  `attack-damage`. The companion closes to about 1.4 blocks and stops there, so it settles
  instead of shoving into the mob, and it keeps its head on the target the whole way.
- **Danger markers** use visible-but-not-wall-penetrating particles: Yui marks skeletons;
  Sandrone marks creepers, each within its configured radius around the companion.
  The companion announces a newly detected threat in chat (maximum one alert every 10 seconds).
  Peaceful mobs are never marked.
- **It is never a target.** Any mob that would choose a companion is refused, so it keeps coming
  for you instead. A companion that had drawn aggro has it handed back to you on the next swing.
- **Armed only while fighting.** The companion holds an iron sword only while it actually has a
  mob to fight, and puts it away again when the fight is over.
- Combats take precedence over the formation spot: a companion leaves its side while chasing, and
  returns as soon as the fight ends.

## Behaviour modes

A companion has exactly one standing behaviour at a time. **The mode stays in force until another command replaces it** — there is no timeout, and nothing silently reverts it.

| | FOLLOW (`/ikut`) | CLOSE (`/kesini`) | WAIT (`/tunggu`) |
|---|---|---|---|
| Position | Formation: Yui on your left, Sandrone on your right | As close as the geometry allows, no formation | Stays where it was told to wait |
| Standing distance | `comfort-distance` (3.0 blocks) | `close-distance` (1.8 blocks) | Free-roams up to 5 blocks around its own spot |
| Fights | Hostile mobs coming for you or a companion | Same | Hostile mobs coming inside `wait-threat-radius` of its own spot — it stays on its ground |
| Emergency teleport target | Its own formation side | A spot in the room you can walk to | None — it is never pulled back to you |

- FOLLOW is the default: a freshly spawned companion starts in this mode.
- WAIT anchors the spot the companion was standing in when the command was given. It free-roams
  around that spot the way a mob does — picking a direction, walking it for a while, then turning
  or resting — rather than marching to a fixed point and standing there. It keeps watching you
  from where it stands, and it is never rescued by teleport: being far from you is exactly what
  you asked for.
- The chosen mode is saved per player in `plugins/CompanionNPCs/modes.yml` and restored when the companion is respawned or the server restarts.

## Movement rules

All three companions — Yui, Sandrone, and Eris — share the same A* navigation and jump behavior. A parkour fix therefore applies to all three.

Mannequins are not `Mob` and have no vanilla pathfinder, so the plugin computes an A* route itself (`PathPlanner`) and walks it with velocity.

- **Never through walls.** A route only exists if every step is walkable. A node without solid ground beneath it is rejected, so a route cannot lead across the void of a ravine.
- **Never down a cliff.** A walking route descends at most one block per step — except when you are standing below the companion, where it may come down after you (up to 12 blocks). A parkour jump across a gap may land up to four blocks lower. Either way, the fall shaft must be clear and the landing must have safe solid ground.
- **Parkour.** All three companions — Yui, Sandrone, and Eris — share the same jump behavior. A gap of up to three blocks can be crossed; the companion can also land on a far side one block higher or up to four blocks lower. Lower jumps require an open gap, a clear fall shaft, and a solid safe landing floor.
- **Climbing onto a ledge.** A far side that sits one block higher is jumped onto rather than merely jumped at. The companion aims at the middle of that block and matches its speed to the distance, so it arrives while the jump is still high enough to land on top — arriving too early would sail over the ledge, and too late would drop back into the gap. A single short hop covers the last fraction of a block.
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

### Keeping the config file up to date

Bukkit only writes the bundled config when there is none yet, so a config created by an older
version of the plugin keeps its old shape: new options never appear in the file, and the plugin
silently uses its built-in defaults for them.

The plugin therefore merges the missing options into the file on disk — on startup and on
`/companionnpc config`. The merge only adds what is missing:

- values that are already set keep their value and their place in the file
- missing options are appended, together with the comment that explains them
- comments that are already in the file are left alone

So a config from an old version grows the new options on its own, and hand-edited values are
never overwritten. `/companionnpc reload` re-reads the file without restarting the server.

| Key | Default | Meaning |
|---|---|---|
| `comfort-distance` | 3.0 | Standing distance in FOLLOW mode |
| `close-distance` | 1.8 | Standing distance in CLOSE mode |
| `teleport-distance` | 24 | Emergency teleport radius in FOLLOW mode (16–32) |
| `close-teleport-distance` | 24 | Emergency teleport radius in CLOSE mode (16–32) |
| `idle-look-mob-radius` | 12 | How far an idle companion glances at a nearby mob (4–24) |
| `idle-look-standstill-distance` | 6 | How far a still companion may glance at mobs (2–12) |
| `attack-search-radius` | 8.0 | How far a hostile mob may be from a companion for it to defend (2–16) |
| `wait-threat-radius` | 12.0 | How far a waiting companion reacts to hostile mobs around its spot (4–48) |
| `attack-damage` | 5.0 | Damage per melee swing (1–40) |
| `attack-cooldown-ticks` | 16 | Ticks between two swings, 20 = one second (4–40) |
| `yui-skeleton-mark-radius` | 16.0 | How far Yui marks skeletons around itself (10–24) |
| `sandrone-creeper-mark-radius` | 16.0 | How far Sandrone marks creepers around itself (10–24) |
| `pack-http-server` | true | Serve the resource pack over HTTP |
| `pack-http-port` | 8765 | Port for that HTTP server |
| `debug-self-test` | false | Spawn both NPCs at startup — test server only |

## Cleanup

NPCs despawn when the owner quits. The plugin cleans tagged strays on disable and on `/companionnpc clear`.

## Tests

Compile against Paper 26.3, then use an isolated server copy on port 25566. Set `debug-self-test: true` only there to spawn both mannequins at test-world spawn and log `SELF-TEST PASS`; default stays false. Do not enable debug self-test on production.
