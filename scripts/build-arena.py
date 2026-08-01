#!/usr/bin/env python3
"""
Adds two things the demo set was missing, without touching what already works.

1. A supply chest stocked for building: the hut is oak planks, so the chest
   carries oak planks by the stack, plus sand and cactus so the levity beat can
   be re-staged anywhere. Iron armour matters more than it looks — the fight
   below has to bring the player to three hearts and leave them STANDING, and an
   unarmoured player just dies.

2. A walled arena on its own pad north of the plateau. The plateau is full: hut,
   cactus, wolf and mine shaft between them leave no room for a fight that won't
   trample the set. Mobs are summoned on cue rather than pre-placed, because a
   ravager left standing around wanders off or despawns before the camera rolls.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from rcon import Rcon  # noqa: E402

BASE_X, BASE_Y, BASE_Z = 100, 116, 100
CHEST_X, CHEST_Z = BASE_X + 1, BASE_Z + 1

# Own pad, clear of the plateau's north edge (z=87).
AR_X0, AR_X1 = 88, 104
AR_Z0, AR_Z1 = 76, 88
AR_CX = (AR_X0 + AR_X1) // 2
FLOOR = BASE_Y - 1          # 115, grass level
WALL_TOP = BASE_Y + 4


def build(c):
    say = lambda m: print("  " + m)

    say("stocking the supply chest")
    stock = [
        "minecraft:oak_planks 64", "minecraft:oak_planks 64",
        "minecraft:oak_planks 64", "minecraft:oak_planks 64",
        "minecraft:oak_log 32", "minecraft:sand 64", "minecraft:cactus 16",
        "minecraft:torch 64", "minecraft:bread 16", "minecraft:cooked_beef 16",
        "minecraft:golden_apple 4", "minecraft:bone 16",
        "minecraft:iron_sword", "minecraft:shield", "minecraft:iron_pickaxe",
        "minecraft:iron_helmet", "minecraft:iron_chestplate",
        "minecraft:iron_leggings", "minecraft:iron_boots",
    ]
    c.run(f"setblock {CHEST_X} {BASE_Y} {CHEST_Z} minecraft:chest")
    for slot, item in enumerate(stock):
        c.run(f"item replace block {CHEST_X} {BASE_Y} {CHEST_Z} container.{slot} with {item}")

    say("levelling the arena pad")
    c.run(f"fill {AR_X0 - 1} {BASE_Y} {AR_Z0 - 1} {AR_X1 + 1} {BASE_Y + 14} {AR_Z1 + 1} minecraft:air")
    # Solid beneath, never a one-block sheet: the mountain is uneven and a sheet
    # laid over the low spots leaves voids that sand and gravel fall into.
    c.run(f"fill {AR_X0 - 1} {FLOOR - 6} {AR_Z0 - 1} {AR_X1 + 1} {FLOOR - 1} {AR_Z1 + 1} minecraft:dirt")
    c.run(f"fill {AR_X0 - 1} {FLOOR} {AR_Z0 - 1} {AR_X1 + 1} {FLOOR} {AR_Z1 + 1} minecraft:grass_block")

    say("arena walls")
    for x0, y0, z0, x1, y1, z1 in [
        (AR_X0, BASE_Y, AR_Z0, AR_X1, WALL_TOP, AR_Z0),   # north
        (AR_X0, BASE_Y, AR_Z1, AR_X1, WALL_TOP, AR_Z1),   # south
        (AR_X0, BASE_Y, AR_Z0, AR_X0, WALL_TOP, AR_Z1),   # west
        (AR_X1, BASE_Y, AR_Z0, AR_X1, WALL_TOP, AR_Z1),   # east
    ]:
        c.run(f"fill {x0} {y0} {z0} {x1} {y1} {z1} minecraft:stone_bricks")

    say("doorway facing spawn, and a walkway to it")
    c.run(f"fill {AR_CX - 1} {BASE_Y} {AR_Z1} {AR_CX + 1} {BASE_Y + 2} {AR_Z1} minecraft:air")
    c.run(f"fill {AR_CX - 1} {FLOOR} {AR_Z1 + 1} {AR_CX + 1} {FLOOR} {AR_Z1 + 6} minecraft:grass_block")
    c.run(f"fill {AR_CX - 1} {BASE_Y} {AR_Z1 + 1} {AR_CX + 1} {BASE_Y + 3} {AR_Z1 + 6} minecraft:air")

    say("lighting — lit so nothing spawns until we ask it to")
    for x in range(AR_X0 + 2, AR_X1, 4):
        for z in (AR_Z0 + 1, AR_Z1 - 1):
            c.run(f"setblock {x} {BASE_Y + 3} {z} minecraft:torch")
    for z in range(AR_Z0 + 3, AR_Z1 - 1, 4):
        for x in (AR_X0 + 1, AR_X1 - 1):
            c.run(f"setblock {x} {BASE_Y + 3} {z} minecraft:torch")

    say("clearing any strays")
    c.run(f"kill @e[type=minecraft:ravager,distance=..60,x={AR_CX},y={BASE_Y},z={AR_Z0}]")
    c.run(f"kill @e[type=minecraft:pillager,distance=..60,x={AR_CX},y={BASE_Y},z={AR_Z0}]")

    print()
    print("  arena floor  ~ x {}..{}, z {}..{}, y {}".format(AR_X0 + 1, AR_X1 - 1, AR_Z0 + 1, AR_Z1 - 1, BASE_Y))
    print("  doorway      ~ {}, {}, {}  (faces spawn)".format(AR_CX, BASE_Y, AR_Z1))
    print("  spawn the fight in the middle:")
    print("    /summon minecraft:ravager {} {} {}".format(AR_CX, BASE_Y, AR_Z0 + 4))


if __name__ == "__main__":
    con = Rcon()
    try:
        build(con)
    finally:
        con.close()
    print("\n  done.")
