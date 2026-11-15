"""RelapsedMC starter island -> plugins/lag-skyblock/src/main/resources/blueprints/relapsed.blu (BentoBox blueprint).

A blueprint is a zip holding one JSON file named after the blueprint. Coordinates are relative; (0,0,0) is the
island's top centre; the player spawns on the [spawn_here] sign (BentoBox removes it).
Run: python3 tools/skyblock/genisland.py [--preview out.png]
"""
import io, json, math, os, sys, zipfile

OUT = os.path.join(os.path.dirname(__file__), "..", "..", "plugins", "lag-skyblock", "src", "main", "resources", "blueprints", "relapsed.blu")
R = 10.5
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


cells = [(x, z) for x in range(-13, 14) for z in range(-13, 14) if math.hypot(x, z) <= edge(x, z)]
for (x, z) in cells:
    d = math.hypot(x, z)
    k = 1 - d / edge(x, z)
    dep = max(2, int(2 + 9 * k ** 1.2 + h(x, z) * 2))
    for y in range(-dep, 1):
        if y == 0:
            b = "grass_block[snowy=false]"
        elif y >= -2:
            b = "dirt"
        else:
            n = h(x, y, z, 4)
            b = "coal_ore" if n < 0.04 else ("iron_ore" if n < 0.06 else ("andesite" if n > 0.8 else "stone"))
        put(x, y, z, b)
put(0, -9, 0, "bedrock")
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


def item(t, n=1):
    return "is:\n  ==: org.bukkit.inventory.ItemStack\n  v: 1631\n  type: " + t + "\n" + (f"  amount: {n}\n" if n > 1 else "")


kit = {0: item("LAVA_BUCKET"), 1: item("ICE", 2), 2: item("OAK_SAPLING", 2), 3: item("BONE_MEAL", 8), 4: item("WHEAT_SEEDS", 4),
       5: item("SUGAR_CANE", 2), 6: item("CACTUS"), 7: item("MELON_SLICE"), 8: item("PUMPKIN_SEEDS"),
       9: item("BREAD", 8), 10: item("TORCH", 8), 11: item("RED_MUSHROOM"), 12: item("BROWN_MUSHROOM")}

attached = [
    [[0.0, 1.0, 1.0], {"blockData": "minecraft:oak_sign[rotation=8,waterlogged=false]", "signLines": ["[spawn_here]", "", "", ""]}],
]
chest = [[1.0, 1.0, -2.0], {"blockData": "minecraft:chest[facing=south,type=single,waterlogged=false]", "inventory": {str(k): v for k, v in kit.items()}}]
entities = [[[3.0, 1.0, 4.0], [{"type": "COW"}]]]

blist = [[[float(x), float(y), float(z)], {"blockData": b}] for (x, y, z), b in sorted(blocks.items())]
blist.append(chest)
xs = [p[0] for p in blocks]; ys = [p[1] for p in blocks]; zs = [p[2] for p in blocks]
bp = {"name": "relapsed", "attached": attached, "entities": entities, "blocks": blist,
      "xSize": max(xs) - min(xs) + 1, "ySize": max(ys) - min(ys) + 2, "zSize": max(zs) - min(zs) + 1, "bedrock": [0.0, -9.0, 0.0]}
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
