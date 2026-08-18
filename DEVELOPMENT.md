# Axecellent — Development

Everything needed to build, run and release the mod. For the working notes an
agent needs (verified API signatures, gotchas, the headless verification loop), see
[AGENTS.md](AGENTS.md). For what the mod does and how to configure it, see
[README.md](README.md).

## Requirements / toolchain

| Thing | Version | Notes |
|---|---|---|
| Minecraft | 1.21.1 | `minecraft_version` in `gradle.properties` |
| NeoForge | 21.1.242 | `neo_version` |
| Java | 21 | `java_version`; the Gradle toolchain resolves it |
| Gradle | 8.10.2 | via the committed wrapper — use `./gradlew`, never a system `gradle` |

You do **not** need to install or select a JDK by hand, even if your default `java`
is newer: `gradle/gradle-daemon-jvm.properties` pins the Gradle daemon to Java 21
and the toolchain compiles against it. (Gradle 8.10.2 cannot run on Java 25 at
all — that is what this file prevents.)

## Building

```bash
./gradlew :neoforge:build
```

Output: `neoforge/build/libs/Axecellent-neoforge-1.21.1-<mod_version>.jar` - fifteen
classes, three tag JSONs, a lang file and an icon. Nothing else.

The jar deliberately **excludes** the dev harness (`dev/**`), the datagen providers
(`datagen/**`) and the dev client helpers (`client/ClientScreenshot*`,
`client/DevClientWindow*`). They are build/test machinery, not shipped code.

### Versions

Every version lives in `gradle.properties` and nowhere else. The GitHub Actions
workflows read that file too, so bumping Minecraft or Java is a one-line change
plus a `neo_version` bump; nothing in CI is hardcoded.

`gradle.properties` must stay **pure ASCII** — Java reads it as ISO-8859-1, so a
non-ASCII character in `mod_description` reaches the in-game mod list as mojibake.

## Architecture

Root project builds nothing. The mod is the `neoforge/` subproject; the layout
leaves room for a `fabric/` sibling later without moving source.

```
neoforge/src/main/java/nl/ljack2k/axecellent/
  Axecellent.java              @Mod entry point — registers the config, nothing else
  chainsaw/
    ModTags.java               the three tags that drive everything
    Config.java                server config (limits, pace, durability)
    CutMode.java               PROGRESSIVE / HELD / INSTANT
    ChainsawHandler.java       the one event hook: player breaks a log
    Chainsaw.java              plans a cut (flood-fill, order, budget) and breaks blocks
    ChainsawCascade.java       releases a plan a bite at a time - PROGRESSIVE and HELD
    ConfigCommand.java         /axecellent config - op-only, derived from Config
  client/
    ChainsawTooltip.java        "Chainsaw" tooltip on tagged items
    ClientScreenshot.java      dev-only, excluded from the jar, self-registering
    DevClientWindow.java       dev-only, excluded from the jar, self-registering
  datagen/                     tag generation, excluded from the jar
  dev/                         RCON harness + payloads, excluded from the jar
neoforge/src/dev/resources/    dev-only datapack: world rules for test worlds
neoforge/src/generated/        datagen output (committed)
tools/Rcon.java                headless RCON client (single-file, no build step)
tools/axe.ps1                  wrapper so RCON works from any directory
tools/SnapDevWindow.ps1        puts the dev client window on the right monitor
branding/                      icon set (placeholder — regenerate from icon.svg)
```

### How the chainsaw works

Three modes share one planner. `Chainsaw.plan` decides *what* comes out and in what order;
who spends that plan, and how fast, is the mode:

| Mode | Origin block | Driven by | Order |
|---|---|---|---|
| `INSTANT` | vanilla breaks it now | nothing - all at once | n/a |
| `PROGRESSIVE` | kept, handed back last | server tick, `logsPerTick` | furthest first |
| `HELD` | kept, handed back last | the player finishing a chop | furthest first, or nearest first while crouching |

**HELD has no timer, deliberately.** Three earlier versions did, and all three felt wrong:
the cut either coasted after the player let go (reads as automatic) or waited and lurched
(one swing, twenty blocks). Driving it off completed break events removes the clock, so the
player's own tool speed is the rhythm and letting go stops it with nothing left running.
Do not reintroduce a tick-driven path for HELD.


The sequence:

1. `ChainsawHandler` listens for `BlockEvent.BreakEvent`. It bails out unless the break is
   server-side, on a block in `#axecellent:chainsaw_logs`, by a player holding an item in
   `#axecellent:chainsaw`, and not blocked by the sneak or creative rules.
2. `Chainsaw.plan` flood-fills connected logs (26-neighbour, because oak and jungle
   branches join diagonally), capped by `chainsaw.maxLogs`; optionally requires attached
   leaves; works out how many blocks the tool can pay for; then flood-fills the canopy,
   remembering which log each leaf hangs from. Logs are ordered by *depth through the log
   network*, not straight-line distance, so a branch curling back still falls early.
3. `INSTANT` walks the plan there and then. The animated modes cancel the event — keeping
   the player's block standing — and hand the plan to `ChainsawCascade`.
4. Each block goes through the same path a player break would: a per-block break event so
   claim/protection mods can veto it, then drops, then removal with the usual particles and
   sound.
5. When the plan empties, the player's block is handed back to vanilla by re-running
   `gameMode.destroyBlock` on it, so it keeps vanilla's drops, durability, enchantment
   handling, stats and advancements rather than an approximation.

Details worth knowing before changing this code:

- **Re-entrancy.** With `respectBlockProtection` on, the chainsaw fires a real break
  event per block — which lands straight back in `ChainsawHandler`. A thread-local
  guard (`Chainsaw.isActive()`) is what stops the recursion. Do not remove it.
- **A repeat break on a tree already being cut is not a new cut.** Both animated modes keep
  the player's block alive, so holding the button re-breaks it every couple of ticks.
  `ChainsawCascade.alreadyCutting` catches those; without it the same tree is planned and
  charged for several times a second. In HELD those repeats *are* the pacing.
- **`ORIGIN_REPLAY`** marks the block being handed back so the resulting break event is not
  mistaken for a fresh cut. A stuck marker silently disables cutting at that position.
- **Only non-persistent leaves are touched.** Player-placed leaves are
  `persistent`, so skipping them is what makes an uncapped leaf sweep safe near
  builds. This is the safety net for the leaf half of a cut, the way `maxLogs` is
  the safety net for the trunk half.

## Running

```bash
./gradlew :neoforge:runClient       # dev client (JEI included for item search / cheat-spawn)
./gradlew :neoforge:runServer       # dev server, RCON on :25575 (password 'axe')
./gradlew :neoforge:runClientJoin   # dev client that auto-joins localhost:25565
```

`runServer` and `runClientJoin` set `-Daxecellent.devHarness`, which enables the
`/axecellent` commands. `runClient` does not — it is a plain dev client.

First `runServer` also runs `:neoforge:prepareDevServer`, which seeds
`neoforge/run-server/` with:

- `eula.txt` (`eula=true`) — without it the server boots, refuses and exits.
- `server.properties` — RCON on 25575, offline mode, creative, flat world with
  explicit layers, peaceful, all three mob-spawn switches off, watchdog disabled.

It never overwrites an existing file, so local tweaks survive. To pick up a change
to the seed, delete the file and re-run.

### Dev worlds are always day, mob-free and weather-free

Both the dev server world and any single-player `runClient` world are pinned by a
dev-only datapack at
`neoforge/src/dev/resources/data/axecellent_dev/function/dev_world.mcfunction`,
which runs from `#minecraft:load` on every world load:

- `doDaylightCycle=false` + `time set day`
- `doWeatherCycle=false` + `weather clear`
- `doMobSpawning=false`, `doInsomnia=false`, `doPatrolSpawning=false`,
  `doTraderSpawning=false`
- kills anything world generation already spawned (players, items, item frames,
  armour stands and paintings excluded)

It lives in the `dev` source set, which is attached to the mod for dev runs only.
The `jar` task packs `main` alone, so **none of this ships**. Keep it that way: do
not move these files into `src/main/resources`.

### Where the dev client window opens

`dev_window_x` / `dev_window_y` in `gradle.properties` decide which monitor the dev
client opens on (virtual-desktop coordinates; a monitor left of the primary has
negative x). Blank them to let Minecraft place the window itself.

This takes two mechanisms, because Minecraft has **no window-position startup
parameter** and the first window you see is FML's early loading window, created
before any mod code exists:

- `tools/SnapDevWindow.ps1` — spawned by the `snapDevWindow` task before
  `runClient`/`runClientJoin`. Runs as a separate process, polls for the window,
  then hides / moves / shows it so it never gets painted on the wrong monitor.
  Windows-only (it calls user32); skipped silently elsewhere. It holds the position
  for ~20s afterwards, because Minecraft's `Window` constructor re-centres the
  window on whichever monitor it currently occupies.
- `client/DevClientWindow.java` — applies the same position from inside the game at
  client setup. This is the cross-platform fallback; on its own it can only move
  the window *after* it has already appeared, which is why the script exists.

Note `glfwSetWindowPos` positions the *content* area, so `DevClientWindow` offsets
by the frame borders — otherwise the title bar ends up above the screen edge where
it cannot be grabbed.

### Headless verification (RCON)

`tools/Rcon.java` is a dependency-free single-file program — run it straight from
source, no build step:

```bash
java tools/Rcon.java "time query daytime" "gamerule doMobSpawning"
java tools/Rcon.java "stop"
```

Defaults are `localhost:25575` / password `axe`; override with `-Drcon.host`,
`-Drcon.port`, `-Drcon.password`. Commands take no leading `/`.

A cut needs a real player break, which RCON cannot perform, so the harness
provides the pieces. With `runServer` **and** `runClientJoin` both up (the dev
player is called `Dev`):

```bash
java tools/Rcon.java \
  "gamemode survival Dev" \
  "item replace entity Dev weapon.mainhand with minecraft:diamond_axe" \
  "execute as Dev run axecellent tree 7" \
  "execute as Dev run axecellent break"
```

| Command | What it does |
|---|---|
| `axecellent tree [height]` | plants a test oak (trunk + crown) beside the player |
| `axecellent break [sneaking]` | breaks the nearest log through **vanilla's own** break path, so every gate in `ChainsawHandler` is exercised — including the item tag and sneak. Reports logs before/after and durability spent. Uses whatever the player is holding, so an untagged tool can be tested |
| `axecellent cut` | calls `Chainsaw` directly, bypassing the gates. Use to test the algorithm itself; it hands the player a diamond axe unless they already hold a tagged tool |
| `axecellent shot` | screenshots the joined client to `run-client/screenshots/axshot.png` |

Free ports **25565/25575** before relaunching; stale JVMs hold them.

## Data generation

```bash
./gradlew :neoforge:runData
```

Writes into `neoforge/src/generated/resources`, which is registered as a second
resource root and is **committed to git**. That is deliberate: `release.yml` only
runs `gradlew build` and never regenerates, so the repo has to hold the current
output.

The mod adds no items or blocks, so the only generated data is the three tags:

| Provider | Generates |
|---|---|
| `ModBlockTagsProvider` | `axecellent:chainsaw_logs`, `axecellent:chainsaw_leaves` — defaulted to `#minecraft:logs` / `#minecraft:leaves` |
| `ModItemTagsProvider` | `axecellent:chainsaw` — generated **empty on purpose**; a pack grants it |

**After changing a default tag: run `runData` and commit the diff.**

## Publishing (CurseForge + Modrinth)

Three workflows, deliberately split so one platform can be retried without a
rebuild:

1. **`release.yml`** — on a pushed `v*` tag: builds the jar, creates the GitHub
   Release.
2. **`publish-curseforge.yml`** / **`publish-modrinth.yml`** — on that workflow
   completing successfully: download the jar from the Release and upload it. Both
   also accept a manual run with a tag, to retry a single platform.

Release steps:

```bash
# 1. bump mod_version in gradle.properties
# 2. ./gradlew :neoforge:runData   (and commit any diff)
# 3. commit
git tag v0.1.0
git push origin v0.1.0
```

### Still to set up

Neither publish workflow works yet:

- `publish-curseforge.yml` needs the numeric **CurseForge project ID** (currently
  `000000`) and a `CURSEFORGE_TOKEN` secret.
- `publish-modrinth.yml` needs the **Modrinth base62 project ID** (currently
  `TODO_MODRINTH_ID`) and a `MODRINTH_TOKEN` secret. The slug cannot be used if it
  contains a hyphen — the API only accepts the base62 id.

Both fail loudly until then; `release.yml` is unaffected.

## Notes / limitations

- No mixins. If one becomes necessary, add a `<mod_id>.mixins.json` and declare it
  in `neoforge.mods.toml` under `[[mixins]]` — and note that a dev-only mixin is
  awkward, because the config has to be declared in a `mods.toml` that ships.
- No unit tests; verification is the build plus the RCON harness.
- `displayTest = "IGNORE_ALL_VERSION"` is set because all behaviour is server-side,
  so a client without Axecellent can join a server that has it. This has not been
  verified against a genuinely mod-less client in dev.
- The `branding/` icons and `neoforge/src/main/resources/icon.png` are generated
  placeholders; `branding/icon.svg` is the source of the design.
