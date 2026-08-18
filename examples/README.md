# Examples

Copy-paste starting points. Nothing in here is loaded by the mod - these are files
for a **pack** to use.

| File | What it is |
|---|---|
| `kubejs/axecellent_chainsaw.js` | One way to grant the chainsaw: a KubeJS server script. Goes in a pack’s `kubejs/server_scripts/`. Illustration, not a requirement — any tag source works. |
| `resourcepack/` | Rewords the tooltip line. Drop it in `resourcepacks/`, or merge its lang file into a pack you already ship. |

If you would rather not use KubeJS, the same thing as a datapack file at
`data/axecellent/tags/item/chainsaw.json`:

```json
{
  "values": [
    "minecraft:iron_axe"
  ]
}
```

Swap `chainsaw` for `chainsaw_held`, `chainsaw_progressive` or `chainsaw_instant` to pin
that tool to one cut mode instead of following the config.
