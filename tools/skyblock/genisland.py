"""RELAPSEDMC starter island -> plugins/lag-skyblock/src/main/resources/blueprints/relapsed.blu (BentoBox blueprint).

A blueprint is a zip holding one JSON file named after the blueprint. Coordinates are relative; (0,0,0) is the
island's top centre; the player spawns on the [spawn_here] sign (BentoBox removes it).
Run: python3 tools/skyblock/genisland.py [--preview out.png]
"""
import io, json, math, os, sys, zipfile

OUT = os.path.join(os.path.dirname(__file__), "..", "..", "plugins", "lag-skyblock", "src", "main", "resources", "blueprints", "relapsed.blu")
R = 12.5
blocks = {}


def h(*a):
    v = 2166136261
    for n in a:
        v ^= (int(n) * 2654435761) & 0xFFFFFFFF
        v = (v * 16777619) & 0xFFFFFFFF
    v ^= v >> 13
    v = (v * 1274126177) & 0xFFFFFFFF
    v ^= v >> 16
    return v / 2**32


def put(x, y, z, b):
    blocks[(x, y, z)] = b if b.startswith("minecraft:") else "minecraft:" + b


def edge(x, z):
    a = math.atan2(z, x)
    return R + math.sin(a * 3 + 1) * 1.1 + math.cos(a * 5) * 0.7


cells = [(x, z) for x in range(-16, 17) for z in range(-16, 17) if math.hypot(x, z) <= edge(x, z)]
for (x, z) in cells:
    d = math.hypot(x, z)
    k = 1 - d / edge(x, z)
    dep = max(2, int(2 + 11 * k ** 1.2 + h(x, z) * 2))
    for y in range(-dep, 1):
        if y == 0:
            b = "grass_block[snowy=false]"
        elif y >= -2:
            b = "dirt"
        else:
            n = h(x, y, z, 4)
            b = "coal_ore" if n < 0.04 else ("iron_ore" if n < 0.06 else ("andesite" if n > 0.8 else "stone"))
            if y < -5:   # the underside is the old world's rock: deepslate with crystal veins, like spawn
                b = "amethyst_block" if n < 0.05 else ("crying_obsidian" if n < 0.08 else ("cobbled_deepslate" if n > 0.7 else "deepslate"))
        put(x, y, z, b)
put(0, -9, 0, "bedrock")
# a crystal shard hanging under the island (every island is a piece of the Shattered World)
for y in range(-19, -11):
    w = 1 if y > -16 else 0
    for x in range(-w, w + 1):
        for z in range(-w, w + 1):
            if abs(x) + abs(z) <= w:
                put(x, y, z, "amethyst_block" if h(x, y, z, 7) < 0.7 else "purple_stained_glass")
# sand beach on the east side, clay under it
for (x, z) in cells:
    if x >= 6 and abs(z) <= 3 and math.hypot(x, z) <= edge(x, z) - 0.5:
        put(x, 0, z, "sand")
        put(x, -1, z, "sand")
        put(x, -2, z, "clay")
# oak tree (north-west)
tx, tz = -4, -4
for y in range(1, 6):
    put(tx, y, tz, "oak_log[axis=y]")
for x in range(-2, 3):
    for z in range(-2, 3):
        for y in (4, 5):
            if abs(x) + abs(z) < 4 or h(x, z, y) < 0.5:
                if (x, z) != (0, 0):
                    put(tx + x, y, tz + z, "oak_leaves[distance=1,persistent=false,waterlogged=false]")
for x in range(-1, 2):
    for z in range(-1, 2):
        put(tx + x, 6, tz + z, "oak_leaves[distance=1,persistent=false,waterlogged=false]")
put(tx, 7, tz, "oak_leaves[distance=1,persistent=false,waterlogged=false]")
# flowers / grass
for (x, z) in cells:
    if (x, 1, z) in blocks or blocks.get((x, 0, z), "").endswith("sand"):
        continue
    f = h(x, z, 9)
    if f < 0.14 and math.hypot(x, z) > 2.5:
        put(x, 1, z, "short_grass")
    elif f < 0.17 and math.hypot(x, z) > 2.5:
        put(x, 1, z, ["poppy", "dandelion", "cornflower"][int(h(x, z, 3) * 3)])


# ---- ore generator trench (south of spawn): water on blue, lava on red, cobble forms in the middle
GZ = 5
for x in range(-5, 4):
    for z in (GZ - 1, GZ, GZ + 1):
        blocks.pop((x, 1, z), None)
        put(x, 0, z, "stone_bricks")
        put(x, -1, z, "stone_bricks")
for x in (-3, -2, -1, 0, 1):
    blocks.pop((x, 0, GZ), None)
blocks.pop((-1, -1, GZ), None)                 # the hole the water drops into
put(-1, -2, GZ, "stone_bricks")
put(-3, -1, GZ, "blue_concrete")
put(1, -1, GZ, "red_concrete")
put(-5, 1, GZ, "lantern[hanging=false,waterlogged=false]")
put(3, 1, GZ, "lantern[hanging=false,waterlogged=false]")

# ---- a ruined arch of the old world (north-east) with an amethyst outcrop
for (x, top) in ((5, 4), (8, 2)):
    for y in range(1, top + 1):
        put(x, y, -6, "mossy_stone_bricks" if h(x, y, 21) < 0.4 else ("cracked_stone_bricks" if h(x, y, 22) < 0.3 else "stone_bricks"))
put(6, 4, -6, "stone_brick_stairs[facing=west,half=top,shape=straight,waterlogged=false]")
put(5, 5, -6, "moss_carpet")
for (x, y, z) in ((7, 1, -8), (7, 2, -8), (8, 1, -8), (7, 1, -9)):
    put(x, y, z, "amethyst_block")
put(7, 3, -8, "amethyst_cluster[facing=up,waterlogged=false]")
put(8, 2, -8, "amethyst_cluster[facing=up,waterlogged=false]")


def item(t, n=1):
    return "is:\n  ==: org.bukkit.inventory.ItemStack\n  v: 1631\n  type: " + t + "\n" + (f"  amount: {n}\n" if n > 1 else "")


kit = {0: item("LAVA_BUCKET"), 1: item("WATER_BUCKET"), 2: item("OAK_SAPLING", 4), 3: item("BONE_MEAL", 16), 4: item("WHEAT_SEEDS", 8),
       5: item("SUGAR_CANE", 2), 6: item("CACTUS"), 7: item("MELON_SLICE"), 8: item("PUMPKIN_SEEDS"),
       9: item("BREAD", 16), 10: item("TORCH", 16), 11: item("RED_MUSHROOM"), 12: item("BROWN_MUSHROOM"), 13: item("STONE_PICKAXE"),
       14: item("ICE", 2), 15: item("HOPPER", 2), 16: item("CHEST", 2)}

attached = [
    [[0.0, 1.0, 1.0], {"blockData": "minecraft:oak_sign[rotation=8,waterlogged=false]", "signLines": ["[spawn_here]", "", "", ""]}],
    [[-1.0, 1.0, float(GZ + 1)], {"blockData": "minecraft:oak_sign[rotation=8,waterlogged=false]", "signLines": ["Ore Generator", "Water on BLUE", "Lava on RED", "Mine the middle"]}],
]
chest = [[1.0, 1.0, -2.0], {"blockData": "minecraft:chest[facing=south,type=single,waterlogged=false]", "inventory": {str(k): v for k, v in kit.items()}}]
entities = [[[3.0, 1.0, 4.0], [{"type": "COW"}]]]

blist = [[[float(x), float(y), float(z)], {"blockData": b}] for (x, y, z), b in sorted(blocks.items())]
blist.append(chest)
xs = [p[0] for p in blocks]; ys = [p[1] for p in blocks]; zs = [p[2] for p in blocks]
bp = {"name": "relapsed", "attached": attached, "entities": entities, "blocks": blist,
      "xSize": max(xs) - min(xs) + 1, "ySize": max(ys) - min(ys) + 2, "zSize": max(zs) - min(zs) + 1, "bedrock": [0.0, -9.0, 0.0]}
assert all(not b.endswith(("water", "lava")) for b in blocks.values())
os.makedirs(os.path.dirname(OUT), exist_ok=True)
buf = io.BytesIO()
with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("relapsed", json.dumps(bp))
open(OUT, "wb").write(buf.getvalue())
print(f"relapsed.blu: {len(blist)} blocks, {bp['xSize']}x{bp['ySize']}x{bp['zSize']}")
if "--preview" in sys.argv:
    sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "hub"))
    from isorender import render
    render(blocks, sys.argv[sys.argv.index("--preview") + 1], view="se", S=14)
