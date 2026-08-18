# Dev-world setup. Runs on every world load via the #minecraft:load tag, in BOTH
# runClient single-player worlds and the runServer dev world.
#
# Why a datapack and not server.properties: server.properties has no notion of
# time or weather, and does not apply to a single-player world at all. A load
# function is the only thing that covers both.
#
# This file lives in src/dev/resources, which is attached to the mod for dev runs
# only (see neoforge/build.gradle) - it is NOT in the published jar, so a real
# player's world rules are never touched.

# --- Always day -------------------------------------------------------------
gamerule doDaylightCycle false
time set day

# --- No weather -------------------------------------------------------------
gamerule doWeatherCycle false
weather clear

# --- No mobs, hostile or passive --------------------------------------------
gamerule doMobSpawning false
gamerule doInsomnia false
gamerule doPatrolSpawning false
gamerule doTraderSpawning false
# Anything that spawned during world generation, before this ran. Players, and
# the entity types a test setup actually cares about, are excluded.
kill @e[type=!minecraft:player,type=!minecraft:item,type=!minecraft:item_frame,type=!minecraft:armor_stand,type=!minecraft:glow_item_frame,type=!minecraft:painting]

# Visible confirmation in the log that this datapack is active.
say [Axecellent dev] world pinned: day, no weather, no mobs.
