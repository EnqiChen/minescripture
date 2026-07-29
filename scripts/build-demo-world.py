#!/usr/bin/env python3
"""Builds (or rebuilds) the MineScripture demo set over RCON.

Re-runnable on purpose: a botched take should cost one command, not an evening
of rebuilding by hand. Everything is placed relative to BASE, so moving the set
is a one-line change.

    scripts/build-demo-world.py            # build the set
    scripts/build-demo-world.py --reset    # rebuild + restock + clear weather

Set pieces, matching docs/demo-script.md:
  vista spawn · bed hut · cactus (levity) · wolf + bones (taming)
  · supply chest (bread) · mine shaft down to diamonds beside a lava pool
"""
import argparse
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from rcon import Rcon  # noqa: E402

BASE_X, BASE_Y, BASE_Z = 100, 116, 100      # mountaintop, grass
PLATEAU = 13                                 # half-width of the cleared area
MINE_X, MINE_Z = BASE_X + 9, BASE_Z + 9
CAVE_Y = 44                                  # deep enough to read as "underground"


def build(c, reset):
    say = lambda m: print("  " + m)

    say("setting the stage")
    for cmd in [
        "gamerule doWeatherCycle false",     # storms happen when we want them
        "gamerule doMobSpawning true",
        "gamerule showDeathMessages true",
        "difficulty easy",
        "weather clear",
        "time set day",
    ]:
        c.run(cmd)

    say("levelling the plateau")
    x0, x1 = BASE_X - PLATEAU, BASE_X + PLATEAU
    z0, z1 = BASE_Z - PLATEAU, BASE_Z + PLATEAU
    c.run(f"fill {x0} {BASE_Y} {z0} {x1} {BASE_Y + 12} {z1} minecraft:air")
    # Solid fill beneath, not a one-block sheet: the mountain is uneven, and a
    # sheet laid over the low spots leaves voids that gravity blocks fall into.
    c.run(f"fill {x0} {BASE_Y - 6} {z0} {x1} {BASE_Y - 2} {z1} minecraft:dirt")
    c.run(f"fill {x0} {BASE_Y - 1} {z0} {x1} {BASE_Y - 1} {z1} minecraft:grass_block")
    c.run(f"setworldspawn {BASE_X} {BASE_Y} {BASE_Z}")

    say("bed hut")
    hx, hz = BASE_X + 5, BASE_Z - 6
    c.run(f"fill {hx} {BASE_Y} {hz} {hx + 6} {BASE_Y + 3} {hz + 6} minecraft:oak_planks")
    c.run(f"fill {hx + 1} {BASE_Y} {hz + 1} {hx + 5} {BASE_Y + 2} {hz + 5} minecraft:air")
    c.run(f"fill {hx + 3} {BASE_Y} {hz} {hx + 3} {BASE_Y + 1} {hz} minecraft:air")  # doorway
    c.run(f"setblock {hx + 2} {BASE_Y} {hz + 4} minecraft:red_bed[facing=south,part=head]")
    c.run(f"setblock {hx + 2} {BASE_Y} {hz + 3} minecraft:red_bed[facing=south,part=foot]")
    for tx, tz in [(hx + 1, hz + 1), (hx + 5, hz + 5)]:
        c.run(f"setblock {tx} {BASE_Y + 2} {tz} minecraft:torch")

    say("cactus (the levity beat)")
    cx, cz = BASE_X - 7, BASE_Z + 5
    c.run(f"fill {cx} {BASE_Y - 1} {cz} {cx + 4} {BASE_Y - 1} {cz + 2} minecraft:sand")
    for dx in (0, 2, 4):
        c.run(f"setblock {cx + dx} {BASE_Y} {cz + 1} minecraft:cactus")

    say("supply chest")
    sx, sz = BASE_X + 1, BASE_Z + 1
    c.run(f"setblock {sx} {BASE_Y} {sz} minecraft:chest")
    for slot, item in enumerate([
        "minecraft:bread 16", "minecraft:torch 64", "minecraft:iron_sword",
        "minecraft:iron_pickaxe", "minecraft:bone 16", "minecraft:oak_planks 64",
        "minecraft:cooked_beef 16", "minecraft:shield",
    ]):
        c.run(f"item replace block {sx} {BASE_Y} {sz} container.{slot} with {item}")

    say("wolf")
    c.run(f"kill @e[type=minecraft:wolf,distance=..40,x={BASE_X},y={BASE_Y},z={BASE_Z}]")
    c.run(f"summon minecraft:wolf {BASE_X - 4} {BASE_Y} {BASE_Z - 4}")

    say("mine shaft down to the diamonds")
    c.run(f"fill {MINE_X} {CAVE_Y} {MINE_Z} {MINE_X} {BASE_Y + 1} {MINE_Z} minecraft:air")
    c.run(f"fill {MINE_X + 1} {CAVE_Y} {MINE_Z} {MINE_X + 1} {BASE_Y} {MINE_Z} minecraft:stone")
    c.run(f"fill {MINE_X} {CAVE_Y} {MINE_Z} {MINE_X} {BASE_Y} {MINE_Z} "
          f"minecraft:ladder[facing=west]")
    # a lip so nobody walks into the shaft by accident
    c.run(f"setblock {MINE_X} {BASE_Y + 1} {MINE_Z} minecraft:air")

    say("diamond chamber + lava pool")
    c.run(f"fill {MINE_X - 4} {CAVE_Y} {MINE_Z - 4} {MINE_X + 4} {CAVE_Y + 3} {MINE_Z + 4} "
          f"minecraft:air")
    c.run(f"fill {MINE_X - 5} {CAVE_Y - 1} {MINE_Z - 5} {MINE_X + 5} {CAVE_Y - 1} {MINE_Z + 5} "
          f"minecraft:deepslate")
    for dx, dz in [(-2, -2), (-2, -1), (-3, -2), (-1, -3), (-3, -1)]:
        c.run(f"setblock {MINE_X + dx} {CAVE_Y} {MINE_Z + dz} minecraft:deepslate_diamond_ore")
    c.run(f"setblock {MINE_X - 2} {CAVE_Y + 2} {MINE_Z - 2} minecraft:torch")
    # lava a few blocks clear of the ore: reachable for the death beat, not instant
    c.run(f"fill {MINE_X + 2} {CAVE_Y} {MINE_Z + 2} {MINE_X + 3} {CAVE_Y} {MINE_Z + 3} "
          f"minecraft:lava")

    if reset:
        say("restocking players")
        c.run("clear @a")
        for item in ["minecraft:bread 8", "minecraft:torch 32",
                     "minecraft:iron_sword", "minecraft:iron_pickaxe", "minecraft:bone 8"]:
            c.run(f"give @a {item}")
        c.run(f"tp @a {BASE_X} {BASE_Y} {BASE_Z}")
        c.run("time set day")
        c.run("weather clear")

    print(f"\nSet built around spawn ({BASE_X}, {BASE_Y}, {BASE_Z}).")
    print(f"  bed hut     ~ {BASE_X + 5}, {BASE_Z - 6}")
    print(f"  cactus      ~ {BASE_X - 7}, {BASE_Z + 5}")
    print(f"  supply chest~ {BASE_X + 1}, {BASE_Z + 1}")
    print(f"  mine shaft  ~ {MINE_X}, {MINE_Z}  (ladders down to y={CAVE_Y})")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--reset", action="store_true",
                    help="also restock and teleport players — use between takes")
    args = ap.parse_args()
    con = Rcon()
    try:
        build(con, args.reset)
    finally:
        con.close()
