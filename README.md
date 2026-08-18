# Axecellent

Chop the base of a tree and watch it come apart — branch tips first, working back to the
block in your hand.

Which tool can do it is decided by an **item tag**, not an enchantment. The tag ships
**empty**, so a pack grants the chainsaw to whichever tool it likes.

**Minecraft 1.21.1 · NeoForge**

---

## What it does

Break a log with a tool carrying `#axecellent:chainsaw` and the whole connected tree comes
down — but not in a blink. The cut starts at the log furthest away *through the tree's own
branches*, works back along them, and finishes on the very block you hit. Each log takes its
leaves with it, so the canopy dissolves along with the branch holding it up.

That progression is the point. Most tree-fellers make a tree vanish; this one lets you watch
it fall, and gives you a mode where **you** drive it.

> **On its own it does nothing.** No tool has the chainsaw until it is granted — see
> [Granting the chainsaw](#granting-the-chainsaw). The mod is the mechanism; the pack decides
> which tool gets it.

## Cut modes

### `PROGRESSIVE` — the default

One break brings the whole tree down over the next moment or two, a couple of logs per tick.
A big spruce takes a few seconds; a birch is over almost at once.

### `HELD` — your axe, your pace

The tree only comes apart while you keep chopping. **Every chop you finish takes one more
log** — and between chops, nothing happens at all. Stop swinging and the tree stops falling,
right where it is. Your tool sets the rhythm: a stone axe grinds through, a netherite axe
tears.

**Crouch** to reverse it. Normally the far end goes first and the cut travels toward you;
crouched, logs peel away *from* your own block instead — so a single chop takes just the log
in front of you, and carrying on eats into the tree from there. Precise control when you want
it, spectacle when you don't.

Durability is charged per block as it falls, so stopping early only costs what you cut.

### `INSTANT` — the ordinary way

The whole tree disappears in the same tick. For servers that would rather not have the
flourish, or packs automating tree farms.

## Requirements

| | |
|---|---|
| Minecraft | 1.21.1 |
| Loader | NeoForge 21.1.181 or newer |
| Side | **Server** — single-player counts as the server |

Everything happens server-side, so clients do not need the mod. A client that *does* have it
shows a **"Chainsaw"** line on the tooltip of any tool carrying the tag, so players can tell
which tool is the special one.

## Installing

1. Install NeoForge for 1.21.1.
2. Drop the jar into `mods/`.
3. On a multiplayer server, the server needs it. Clients do not.
4. Grant the chainsaw to a tool — nothing happens until you do.

## Granting the chainsaw

Axecellent asks one question of an item: is it in `#axecellent:chainsaw`? **How it got there
is up to you.** Anything that can add to an item tag works — a datapack, KubeJS, another mod,
a pack-generation script. Two common ways:

### A KubeJS server script

In `kubejs/server_scripts/`, applied with `/reload`:

```js
ServerEvents.tags('item', event => {
    event.add('axecellent:chainsaw', 'minecraft:iron_axe')
})
```

### A datapack

At `data/axecellent/tags/item/chainsaw.json`:

```json
{
  "values": [
    "minecraft:iron_axe"
  ]
}
```

Check it took with `/kubejs list-tag item axecellent:chainsaw`, or simply look at the tool's
tooltip. In plain vanilla, `/clear @s #axecellent:chainsaw 0` counts matching items without
removing anything.

### Two things that catch people out

- **Tags apply to the item type, not one stack.** Tagging `minecraft:iron_axe` gives the
  chainsaw to *every* iron axe, not one particular one.
- **Removing an entry only works if it was added as an entry.** If something is in the tag
  through a nested tag reference — say another pack added `#minecraft:axes` — you have to
  remove the reference, not the item:
  `event.remove('axecellent:chainsaw', '#minecraft:axes')`. Removing `minecraft:wooden_axe`
  in that situation fails *silently*.

## What counts as a tree

Two more tags, and unlike the chainsaw tag these **do** have defaults, so modded trees
generally work with no configuration at all.

| Tag | Type | Default | Controls |
|---|---|---|---|
| `axecellent:chainsaw` | item | *(empty)* | tools that cut a whole tree |
| `axecellent:chainsaw_logs` | block | `#minecraft:logs` | what counts as trunk |
| `axecellent:chainsaw_leaves` | block | `#minecraft:leaves` | what gets cleared |

`#minecraft:logs` also covers crimson and warped stems, so nether "trees" are cut too. Drop
them from `axecellent:chainsaw_logs` if you would rather they were not.

Only **naturally grown** leaves are ever cleared. Leaves a player placed are `persistent` and
always survive, which is what keeps an uncapped leaf sweep safe around builds.

## Configuration

`config/axecellent-server.toml`. It is a server config, so in multiplayer the server's copy
decides and clients need nothing.

| Option | Default | Meaning |
|---|---|---|
| `chainsaw.mode` | `PROGRESSIVE` | `PROGRESSIVE`, `HELD` or `INSTANT` — see above |
| `chainsaw.heldSneakStartsAtYou` | `true` | HELD: crouch reverses the cut instead of disabling the chainsaw |
| `chainsaw.heldLogsPerChop` | `1` | HELD: logs taken per completed chop |
| `chainsaw.heldResumeWindow` | `10` | HELD: seconds a part-cut tree waits for your next chop |
| `chainsaw.logsPerTick` | `2` | PROGRESSIVE pace. Leaves ride along free |
| `chainsaw.maxConcurrentCuts` | `32` | trees part-way down at once, server-wide |
| `chainsaw.maxLogs` | `64` | most logs one break can remove |
| `chainsaw.clearLeaves` | `true` | clear the leaves too |
| `chainsaw.maxLeaves` | `0` | leaf limit; `0` means no limit |
| `chainsaw.requireLeaves` | `false` | only cut log groups with leaves attached — turn on to protect log-built structures |
| `chainsaw.dropsAtBreakPosition` | `true` | drops at your feet, or where each block stood |
| `chainsaw.sneakToDisable` | `true` | sneak to break a single log (HELD overrides this) |
| `chainsaw.enabledInCreative` | `false` | use the chainsaw in creative mode |
| `chainsaw.respectBlockProtection` | `true` | let claim and protection mods veto individual blocks |
| `durability.perLog` | `1` | durability per extra log; `0` is free |
| `durability.chargeForLeaves` | `false` | charge for leaves as well |
| `durability.stopBeforeBreak` | `true` | stop rather than break the tool mid-tree |

If your tool runs out of durability partway through a tree, the cut stops and the rest stays
standing.

### Changing settings in game

Requires op. Changes are written to the config file immediately, so they survive a restart.

```
/axecellent config                       list every option and its value
/axecellent config <option>              show one: value, default, range, description
/axecellent config <option> <value>      change it
/axecellent config reset                 put everything back to defaults
```

Option names are the dotted paths from the file, for example
`/axecellent config chainsaw.maxLogs 32`. Values outside an option's range are rejected
before anything is written, and anything differing from its default is highlighted.

### For pack makers

Put your settings in `defaultconfigs/axecellent-server.toml`. Two things worth knowing:

- **List only the keys you want to change.** Anything left out is filled in from the mod's own
  defaults, comments and all, so your file stays short and does not go stale when a new option
  is added.
- **It seeds a fresh install only.** NeoForge copies it into `config/` when that file is
  absent, so on its own it will not push changed values to a server that has already run. To
  make a pack update land, your updater has to replace `config/axecellent-server.toml`.

## Multiplayer

Cutting is server-authoritative, and each removed block goes through the same path an
ordinary player break would — including a block-break event per block, so land claim and
protection mods can veto them individually.

Only one chainsaw cut runs on a tree at a time. If somebody else is already cutting the tree
you swing at, your break is an ordinary one and their cut carries on undisturbed.

## Licence

[MIT](LICENSE).
