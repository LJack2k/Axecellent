// Axecellent - grant the chainsaw to one specific tool.
//
// Drop this in your pack's  kubejs/server_scripts/  folder and edit the item id.
// Apply it in-game with /reload - no restart needed.
//
// The chainsaw tag ships EMPTY: no tool cuts whole trees until it is named here.

ServerEvents.tags('item', event => {
    // The tool that gets the chainsaw. Change this to your pack's item.
    event.add('axecellent:chainsaw', 'minecraft:iron_axe')
})

// --- Notes -------------------------------------------------------------------
//
// Check it worked:
//   /kubejs list-tag item axecellent:chainsaw
// The tool's tooltip will also read "Chainsaw" once it is in the tag.
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
