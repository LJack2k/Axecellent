# AGENTS.md — guide for AI agents working on Axecellent

Read this before touching code. It records the pinned stack, the verification loop,
and the traps that have already cost time. Keep it current: when you learn
something non-obvious, add it here rather than re-deriving it next session.

## What this is

Axecellent fells trees: break a log with a tool carrying the
`#axecellent:chainsaw` item tag and the connected tree comes down. Tag-driven,
**not** an enchantment.

It is a **behaviour mod**, not a content mod: no items, no blocks, no enchantments,
no recipes. Nine top-level classes, three tags, one server config, one event hook. Cutting is
entirely server-side; the only client-side behaviour is a tooltip.

**Three cut modes, one planner.** `Chainsaw.plan` decides what comes out and in what
order; the mode decides who spends that plan:

| Mode | Origin block | Driven by |
|---|---|---|
| `INSTANT` | vanilla breaks it now | nothing, all at once |
| `PROGRESSIVE` | kept, handed back last | server tick, `logsPerTick` |
| `HELD` | kept, handed back last | the player finishing a chop |

**HELD must not have a timer.** Three versions did, and all three were rejected in
testing: a timer either coasts after the player lets go (reads as automatic - "feels
like I have no control") or waits and then lurches ("one break breaks like 20 blocks
very sudden"). It is driven off completed break events instead, so the player's tool
speed is the rhythm and letting go stops it with nothing left running. Do not
reintroduce a tick-driven path for HELD, and do not try to infer "is still holding"
from left-click packets (they only arrive every ~15 ticks) or `PlayerEvent.BreakSpeed`
(per-tick, but still needs a timer to consume it).

**Keeping the player's block alive is load-bearing in HELD**, not cosmetic: it is what
they keep chopping. An earlier crouch implementation let vanilla break it immediately,
and the mode became indistinguishable from plain vanilla chopping, because the
crosshair then sits on air and no further chops ever land.

**Scope is a feature, not an omission.** It was asked for as an early-game
alternative to enchantment-based choppers by someone who dislikes vein-miners
because they "ruin modpacks". Do not grow it into a general vein-miner (ores,
stone, arbitrary block tags) without being asked.

**The chainsaw tag ships EMPTY, and that is deliberate.** `#axecellent:chainsaw` has
no default entries - not axes, not any list - so a fresh install does nothing at all.
How the tag ends up on an item is entirely the pack's business - datapack, KubeJS,
another mod, something else. Do **not** assume, recommend or hard-code a particular
route; the mod asks one question ("is this item in the tag?") and nothing more.

Do **not** "helpfully" default the tag to `#minecraft:axes` either: that was the
original design and the maintainer explicitly rejected it. `ModItemTagsProvider`
generates the empty file on purpose so the tag still shows up in tooling rather than
looking like a typo.

**It replaces an enchantment, so the ability has to be visible.** Apotheosis'
"Chainsaw" enchant showed a tooltip line and a glint; a tag shows nothing, and with
an empty default exactly one tool in a pack behaves differently from every other axe.
`client/ChainsawTooltip` is what keeps a player able to tell which tool that is - it
is a requirement, not decoration. If a change makes the chainsaw harder to identify
in-game, that is a regression.

**Removing a tag entry only works if it was added as an entry.** An item present via
a nested tag reference (`#minecraft:axes`) cannot be removed item-by-item - the
reference has to go instead (`event.remove('axecellent:chainsaw', '#minecraft:axes')`).
Verified: removing `minecraft:wooden_axe` from a tag containing `#minecraft:axes`
fails **silently**, no error.

(The repo layout mirrors AE2-Organizer, which is a client-only mod that mixes into
other mods. Do not copy its mixin plumbing here — there are no mixins in this
project.)

## Stack (don't guess — these are pinned)

`gradle.properties` is the single source of truth; the release workflows read it
too, so each version lives in exactly one place.

| Thing | Version | Where |
|---|---|---|
| Minecraft | 1.21.1 | `minecraft_version` |
| NeoForge | 21.1.242 | `neo_version` |
| Java toolchain | 21 | `java_version` |
| Gradle | 8.10.2 | `gradle/wrapper/gradle-wrapper.properties` |
| ModDevGradle | 1.0.20 | `neoforge/build.gradle` |
| JEI (dev only) | via CurseMaven | `jei_curse_file_id` |

- **The machine's default JDK is 25**, which Gradle 8.10.2 refuses to run on
  (`Unsupported class file major version 69`). `gradle/gradle-daemon-jvm.properties`
  pins the daemon to `toolchainVersion=21`, so `./gradlew` just works — do **not**
  "fix" a JDK error by exporting `JAVA_HOME`; check that file still exists.
- `mod_name` (`Axecellent`) is the technical name and forms the jar filename;
  `mod_display_name` is player-facing. Never build a filename from the latter.

## Build / run / test

```bash
./gradlew :neoforge:compileJava     # fast API check — run after every edit
./gradlew :neoforge:build           # -> neoforge/build/libs/Axecellent-neoforge-1.21.1-<ver>.jar
./gradlew :neoforge:runData         # tag datagen -> src/generated/resources (COMMIT the result)
./gradlew :neoforge:runClient       # dev client (+ JEI), opens a real window
./gradlew :neoforge:runServer       # dev server, RCON on :25575, harness on
./gradlew :neoforge:runClientJoin   # dev client that quick-joins localhost:25565
```

- **Always `compileJava` after edits.** Seconds, and it catches Mojang/NeoForge API
  drift immediately.
- **CI does not run datagen.** `release.yml` only runs `gradlew build`, which is why
  `neoforge/src/generated/resources` is committed. Change a default tag → run
  `runData` → **commit the generated diff**.
- A clean boot means: the mod appears in the `Mod List` block and the log has no
  `ERROR` / `Exception`. Two `VanillaPackResourcesBuilder ... unexpected schema`
  WARNs are normal in dev.

## Verifying the chainsaw headlessly

A cut needs a real player break, which RCON cannot perform — so the harness
splits the problem. Start **both** `runServer` and `runClientJoin`; the dev player
is called `Dev`.

```bash
java tools/Rcon.java \
  "gamemode survival Dev" \
  "item replace entity Dev weapon.mainhand with minecraft:diamond_axe" \
  "execute as Dev run axecellent tree 7" \
  "execute as Dev run axecellent break"
```

| Command | Use |
|---|---|
| `axecellent tree [height]` | plant a test oak next to the player |
| `axecellent break [sneaking]` | break the nearest log through **vanilla's own** path (`ServerPlayerGameMode#destroyBlock`), so the real `BreakEvent` fires and every gate in `ChainsawHandler` is exercised. Uses the held item, so an untagged tool tests the tag gate. Reports logs before/after + durability |
| `axecellent cut` | call `Chainsaw` directly, bypassing the gates — tests the algorithm only |
| `axecellent chop [count]` | **stand in for completed chops**, the only way to exercise `HELD` headlessly. Calls the same entry point the real break event does, so the live path is what runs |
| `axecellent count` | logs still standing nearby + how many trees are mid-fall. Needed because animated cuts finish over several ticks, so `break` returns before the tree is down |
| `axecellent shot` | screenshot the joined client to `run-client/screenshots/axshot.png` |

**Verifying an animated cut takes two steps.** `break` returns immediately having
removed nothing, so a single command proves nothing. Break, then `count` a moment
later — and mind that each `java tools/Rcon.java` invocation costs a JVM start of a
second or two, which is enough to blow past `heldResumeWindow` between two calls. A
"cut vanished early" result is usually that, not a bug; `[cascade]` debug lines say
which it was.

`tools/Rcon.java` is a single-file JDK program (no build step); defaults
`localhost:25575`, password `axe`. Commands take **no** leading `/`.

**Use `break`, not `cut`, to verify a behaviour change** — `cut` skips exactly the
logic most likely to be wrong. Free ports **25565/25575** before relaunching; stale
JVMs hold them.

Config can be changed three ways, all verified:

- `/axecellent config <path> <value>` — the shipped op-only command (see below).
  Fastest way to flip a setting during a test.
- editing `run-server/config/axecellent-server.toml` then `/reload`.
- `java tools/Rcon.java "axecellent config"` to read the whole set at once.

**`ConfigCommand` derives itself from `Config`.** Option names come from each
value's config path; the accepted range, default and in-game help text come from
its `ValueSpec` (`getRange()`, `getDefault()`, `getComment()`). So adding a config
option means adding it to `Config` and to `ConfigCommand.OPTIONS` — never hardcode
a bound or a description in the command, or the two will drift.

Two details there worth keeping:

- NeoForge **appends its own `Default:` / `Range:` lines** to `getComment()`, so
  `describe` skips those to avoid printing them twice.
- The command registers the `axecellent` root literal too, and so does `DevHarness`.
  That is fine — **Brigadier merges literals of the same name**, so `config` and the
  harness subcommands coexist in dev, and only `config` exists in a normal install.

## Dev worlds are pinned: day, no weather, no mobs

`neoforge/src/dev/resources/data/axecellent_dev/function/dev_world.mcfunction` runs
from the `#minecraft:load` tag on **every** world load: `doDaylightCycle=false`,
`time set day`, `doWeatherCycle=false`, `weather clear`, `doMobSpawning=false`
(plus insomnia / patrol / trader off), then kills any mob world generation spawned.

- It lives in the **`dev` source set**, attached to the mod for dev runs only. The
  `jar` task packs `main` alone, so it can never reach players. **Never move this
  datapack into `src/main/resources`** — that would rewrite real players' gamerules.
- It covers single-player `runClient` worlds too, which is why the rules are *not*
  in `server.properties`: that file has no notion of time or weather and does not
  apply to single-player at all.
- Confirm it ran: `[Axecellent dev] world pinned: day, no weather, no mobs.`

## The dev client window must open on the LEFT monitor

The maintainer works on the middle (primary) monitor. A dev client that appears
there — even briefly before jumping away — is disruptive, and this has been raised
twice. `dev_window_x=-1920` / `dev_window_y=0` in `gradle.properties` is the target.

What does **not** work, so don't re-litigate it:

- There is **no window-position startup parameter.** GLFW 3.3 (bundled with MC
  1.21) has no position window hint, Minecraft has no such argument, and FML's
  early-window options cover width/height/maximized/provider but **not** position.
- **"Start minimized" is not available.** GLFW calls `ShowWindow(SW_SHOW)`
  explicitly, which overrides the show-state a process was launched with
  (`STARTUPINFO.wShowWindow`, i.e. `Start-Process -WindowStyle Minimized`).
- **An in-game hook is far too late.** The first window shown is FML's early
  loading window (`Minecraft: NeoForge Loading...`, the red one), created before
  any mod code exists; Minecraft then *takes over that same handle*. The earliest
  mod hook (`FMLClientSetupEvent`) fires ~7s later.

What works: `tools/SnapDevWindow.ps1`, spawned by the `snapDevWindow` task before
`runClient`/`runClientJoin`, races the game from outside and hides → moves → shows
the window.

- It scans with **`EnumWindows` for the `GLFW30` window class**. Measured: ~21
  ms/pass, so worst-case exposure is about one frame. The first version polled with
  `Get-CimInstance Win32_Process` — hundreds of ms per query, which loses the race.
  **Do not put a WMI/CIM query in that loop.**
- `client/DevClientWindow.java` applies the same position in-game as a
  cross-platform fallback. `glfwSetWindowPos` positions the **content** area, so it
  offsets by `glfwGetWindowFrameSize` — without that the title bar lands above the
  screen edge and can't be grabbed.
- Minecraft's `Window` constructor re-centres on whichever monitor the window is
  already on, so once it is left it stays left (measured: 0 corrections needed).
  The script's hold loop is cheap insurance.

## THE GOLDEN RULE: verify APIs with `javap` before writing code

```bash
javap -cp neoforge/build/moddev/artifacts/neoforge-21.1.242-minecraft-merged.jar net.minecraft.world.level.block.Block
```

The merged jar appears after any build and contains Minecraft **and** most
`net.neoforged.neoforge.*` classes. FML loader classes (`net.neoforged.fml.*`) are
**not** in it — they live in
`~/.gradle/caches/modules-2/files-2.1/net.neoforged.fancymodloader/loader/*.jar`.
Minecraft sources are available too, which is how the early-window behaviour above
was established:

```bash
unzip -p neoforge/build/moddev/artifacts/neoforge-21.1.242-minecraft-sources.jar com/mojang/blaze3d/platform/Window.java
```

## Verified on this line (confirmed via javap / a real run)

- `BlockEvent.BreakEvent` — `getPlayer()`, `setCanceled(boolean)`; `getPos()` /
  `getState()` / `getLevel()` come from the `BlockEvent` parent, and `getLevel()`
  returns **`LevelAccessor`**, not `Level` (pattern-match to `ServerLevel`).
- `Block.dropResources(BlockState, Level, BlockPos, BlockEntity, Entity, ItemStack)`
  drops at the block's **own** position and offers no override. To relocate drops
  use `Block.getDrops(...)` + `Block.popResource(Level, BlockPos, ItemStack)` —
  `popResource` still honours the `doTileDrops` gamerule.
- `ItemStack.hurtAndBreak(int, ServerLevel, ServerPlayer, Consumer<Item>)` on 1.21.1
  (there is also a `(int, LivingEntity, EquipmentSlot)` overload).
- `CommonHooks.fireBlockBreak(Level, GameType, ServerPlayer, BlockPos, BlockState)`
  is how you fire a break event per block so claim mods can veto. `GameType` comes
  from `player.gameMode.getGameModeForPlayer()`.
- `Level.destroyBlock(BlockPos, boolean, Entity, int)`; `removeBlock(BlockPos, boolean)`.
- `ModContainer.registerConfig(ModConfig.Type, IConfigSpec)`; the `@Mod` constructor
  can take `(IEventBus, ModContainer, Dist)`.
- `@EventBusSubscriber`'s **`bus` attribute is deprecated for removal** — omit it.
  The bus is inferred per `@SubscribeEvent` method from the event type; confirmed by
  `GatherDataEvent` and `FMLClientSetupEvent` both firing without it.
- Loot tables (if ever needed) use **vanilla** `net.minecraft.data.loot.LootTableProvider`;
  `net.neoforged.neoforge.common.data.LootTableProvider` **does not exist** in 21.1.242.
- Datapack directory names on 1.21 are **singular**: `data/<ns>/function/*.mcfunction`,
  `data/<ns>/tags/block/*.json`, tag at `data/minecraft/tags/function/load.json`.

## Non-obvious gotchas

- **Both animated modes keep the origin block alive**, which means a player holding the
  attack button re-breaks it every couple of ticks and fires a fresh break event each
  time. `ChainsawCascade.alreadyCutting` is what stops each of those planning and
  charging for the same tree again. Removing it would look fine in a harness test (one
  RCON break) and be badly broken with a real mouse.
- **The origin block is handed back to vanilla**, not reimplemented: the cascade calls
  `player.gameMode.destroyBlock(origin)` at the end, so that block keeps vanilla's
  drops, durability, stats and enchantment handling. `ORIGIN_REPLAY` marks it so the
  resulting break event is not mistaken for a new cut. Never leave that marker set — a
  stuck marker silently disables cutting at that position.
- **Price logs and leaves separately.** `HELD` charges per block as it falls, and the
  first version used one rate for both: a 20-log oak cost **85** durability instead of
  20, because ~65 free leaves were charged at the log rate. `chargeForLeaves` is off by
  default, and any per-block charging has to honour that.
- **Re-entrancy in `Chainsaw`.** With `respectBlockProtection` on, the chainsaw fires a
  real break event per block, which lands straight back in `ChainsawHandler`. The
  thread-local `ACTIVE` guard is what prevents infinite recursion. Do not remove it.
- **The `BreakEvent` is deliberately not cancelled.** Vanilla breaks the origin
  block (drops, durability, stats); the chainsaw only handles the rest. Keep it that way
  rather than reimplementing a player break.
- **Only non-persistent leaves are ever touched** — that is what makes an uncapped
  leaf sweep safe near builds. `maxLeaves = 0` really means unlimited.
- **`requireLeaves` defaults to `false` on purpose.** The maintainer was asked and
  chose any-connected-logs, accepting that log-built structures fall. Do not
  quietly re-default it.
- **`gradle.properties` must stay pure ASCII.** Java reads it as ISO-8859-1, so an
  em dash in `mod_description` reaches the mod list as mojibake. Check with
  `LC_ALL=C grep -n '[^ -~]' gradle.properties` — no output is good.
- **Groovy string traps in `neoforge/build.gradle`:** `\:` inside a single-quoted
  string is an invalid escape and fails at configuration time, and a `"""` block in
  this file has misparsed before. The seeded `server.properties` is built as a
  `List` of single-quoted lines joined with `System.lineSeparator()` — keep it so.
- **Comparator inference:** `Comparator.comparingInt(BlockPos::getY)` does not infer
  — needs `Comparator.<BlockPos>comparingInt(...)`. Likewise
  `DeferredRegister`-style `getEntries()` streams need an explicit type witness.
- **The jar excludes** `dev/**`, `datagen/**`, `client/ClientScreenshot*` and
  `client/DevClientWindow*` — and **shipped code must not reference any of them at
  all.** Not even behind a `System.getProperty` guard: a guarded call still leaves an
  `invokestatic` to a missing class in the shipped bytecode, so anyone who set the
  dev property on a real install would crash with `NoClassDefFoundError`. (This was
  the case for a while — `Axecellent` called `DevHarness.init`, and
  `AxecellentClient` called `DevClientWindow.place`. Both are gone.)

  Instead, **dev classes self-register with `@EventBusSubscriber`** and check their
  own enabling property. Annotation scanning finds them in dev, where they exist,
  and finds nothing in production, where they don't. `DevHarness`, `DevClientWindow`
  and `DataGenerators` all work this way.

  Verify after touching any of this:
  ```bash
  cd <tmp> && unzip -q <the jar> && javap -c -p -cp . $(find nl -name '*.class' | sed 's|/|.|g; s|\.class$||') | grep -iE 'devHarness|DevClientWindow|ClientScreenshot'
  ```
  Expected output: nothing.
- **`RegisterCommandsEvent` is a game-bus event**, so `DevHarness` cannot subscribe
  to it by annotation alongside its mod-bus handler. It adds that listener from
  `FMLCommonSetupEvent` instead, which runs during mod loading — long before any
  world starts, so it is always in place in time.
- **Datagen's `.cache/` must stay out of the jar.** `src/generated/resources` is a
  resource root, so the hash index datagen writes there gets packed into the
  published jar unless excluded — it is gitignored, which hides the problem. The
  `sourceSets.main.resources { exclude '.cache/**' }` block is load-bearing; check
  the jar contents after touching resource wiring.
- **`gradlew clean` fails while a dev client or server is running**
  (`Unable to delete directory ...\build` — Windows file locks). Stop the game
  first; it is not a build problem.
- **`cd` does not persist between shell calls** in this harness. Use absolute paths
  or `cd` in every command, or Gradle runs against the wrong repo (this happened).
- **Long heredocs through the Bash tool truncate or mis-quote.** Write large files
  (docs especially) with the file-write tool instead of `cat <<EOF`.

## Release & git workflow

1. Bump `mod_version` in `gradle.properties`.
2. Run `:neoforge:runData`; commit any generated diff.
3. Commit, then `git tag vX.Y.Z && git push origin vX.Y.Z`.
4. `release.yml` builds the jar and creates the GitHub Release;
   `publish-curseforge.yml` / `publish-modrinth.yml` run off that.

**Not wired up yet:** both publish workflows carry placeholder project IDs
(`curseforge-id: 000000`, `modrinth-id: TODO_MODRINTH_ID`) and need the
`CURSEFORGE_TOKEN` / `MODRINTH_TOKEN` secrets. They fail until the projects exist —
expected; `release.yml` succeeds regardless.

## Working with the maintainer

- Ask before adding a dependency or a new dev-runtime mod.
- Do not commit or push unless asked.
- Report honestly: if something was not verified in a running game, say so.
