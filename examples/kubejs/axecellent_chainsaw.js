// Axecellent - grant the chainsaw to your tools.
//
// Drop this in your pack's  kubejs/server_scripts/  folder and edit the item ids.
// Apply it in-game with /reload - no restart needed.
//
// All four tags ship EMPTY: no tool cuts whole trees until it is named here.

ServerEvents.tags('item', event => {
    // The simple version: this tool cuts trees, in whatever mode chainsaw.mode
    // says in the config.
    event.add('axecellent:chainsaw', 'minecraft:iron_axe')

    // Or make the mode part of the tool, so the way a tree falls is something the
    // player upgrades into. A tool listed here does NOT also need the plain tag.
    event.add('axecellent:chainsaw_held', 'minecraft:wooden_axe')        // you drive it, chop by chop
    event.add('axecellent:chainsaw_progressive', 'minecraft:diamond_axe') // one chop, tree falls
    event.add('axecellent:chainsaw_instant', 'minecraft:netherite_axe')   // gone in a blink
})

// --- Notes -------------------------------------------------------------------
//
// Check it worked:
//   /kubejs list-tag item axecellent:chainsaw_held
// The tool's tooltip will also name its mode once it is in a tag.
//
// Do not put one tool in two mode tags. If you do, the most hands-on mode wins
// (held, then progressive, then instant), but that is a tie-break, not a feature.
//
// Removing entries: a single item can only be removed if it was added as a single
// item. If something is in the tag via a nested tag reference (e.g. #minecraft:axes
// added by another pack), remove the reference, not the item:
//   event.remove('axecellent:chainsaw', '#minecraft:axes')
// Removing 'minecraft:wooden_axe' in that situation fails silently.
//
// Tags apply to the item TYPE, not to one stack. Tagging minecraft:iron_axe gives
// the chainsaw to every iron axe in the world, not just one particular one.
//
// What counts as a tree is separately configurable, and those two tags DO have
// sensible defaults (#minecraft:logs and #minecraft:leaves):
//   event.add('axecellent:chainsaw_logs', 'yourmod:weird_log')
// (that one goes in a ServerEvents.tags('block', ...) handler)
