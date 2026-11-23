"""RELAPSEDMC realm spawns (factions, skyblock, cannons) -> plugins/lag-core/src/main/resources/spawn-<realm>.txt.gz.

Same format as the hub island (palette line, then "x y z idx"). (0,0,0) = spawn floor centre, player faces north (-Z).
Each: themed round plaza, a round HUB portal straight ahead (z=-13), a few lantern posts, realm decor, rocky underside.
Run: python3 tools/hub/genspawn_realms.py [--preview dir]
"""
import gzip, math, os, sys

OUTDIR = os.path.join(os.path.dirname(__file__), "..", "..", "plugins", "lag-core", "src", "main", "resources")
R = 21


def h(*a):
    v = 2166136261
    for n in a:
        v ^= (int(n) * 2654435761) & 0xFFFFFFFF
        v = (v * 16777619) & 0xFFFFFFFF
    v ^= v >> 13
    v = (v * 1274126177) & 0xFFFFFFFF
    v ^= v >> 16
    return v / 2**32


def vnoise(x, z, s, seed=7):
    x0, z0 = math.floor(x / s), math.floor(z / s)
    fx, fz = x / s - x0, z / s - z0
    fx, fz = fx * fx * (3 - 2 * fx), fz * fz * (3 - 2 * fz)
    a, b, c, d = h(x0, z0, seed), h(x0 + 1, z0, seed), h(x0, z0 + 1, seed), h(x0 + 1, z0 + 1, seed)
    return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fz


def edge_r(x, z, r, seed=0):
    ang = math.atan2(z, x)
    return r + (vnoise(ang * 9 + seed, 3, 1.7, seed + 11) - 0.5) * 4


THEMES = {
    "factions": dict(floor=["polished_blackstone_bricks", "deepslate_bricks"], ring="red_nether_bricks", inner="polished_blackstone",
                     accent="shroomlight", edge="polished_blackstone_bricks", rim="crimson_nylium", ring_frame=["red_nether_bricks", "polished_blackstone_bricks", "tnt[unstable=false]"],
                     lantern="soul_lantern", top="crimson_nylium", soil="netherrack"),
    "skyblock": dict(floor=["mossy_stone_bricks", "stone_bricks"], ring="moss_block", inner="polished_andesite",
                     accent="verdant_froglight", edge="mossy_stone_bricks", rim="grass_block[snowy=false]", ring_frame=["moss_block", "rooted_dirt", "mossy_cobblestone"],
                     lantern="lantern", top="grass_block[snowy=false]", soil="dirt"),
    "cannons": dict(floor=["polished_deepslate", "deepslate_tiles"], ring="obsidian", inner="polished_deepslate",
                    accent="redstone_lamp[lit=true]", edge="obsidian", rim="polished_blackstone", ring_frame=["obsidian", "crying_obsidian", "redstone_lamp[lit=true]"],
                    lantern="soul_lantern", top="polished_blackstone", soil="blackstone"),
}
GRAD = ["light_blue_concrete", "cyan_concrete", "blue_concrete", "purple_concrete", "purpur_block", "magenta_concrete"]


def build(realm):
    th = THEMES[realm]
    blocks = {}

    def put(x, y, z, b, force=True):
        if not force and (x, y, z) in blocks:
            return
        blocks[(x, y, z)] = b if b.startswith("minecraft:") else "minecraft:" + b

    # underside
    for x in range(-R - 3, R + 4):
        for z in range(-R - 3, R + 4):
            er = edge_r(x, z, R, len(realm))
            d = math.hypot(x, z)
            if d > er:
                continue
            dep = int(2 + 16 * (1 - (d / er) ** 1.5) ** 0.85 + (vnoise(x * 2, z * 2, 5) - 0.5) * 4)
            for dy in range(1, dep + 1):
                n = h(x, -dy, z, 3)
                b = th["soil"] if dy <= 2 else ("amethyst_block" if n < 0.05 else "deepslate" if n < 0.45 else "tuff" if n < 0.7 else "stone")
                put(x, -dy, z, b)
            if dep > 8 and h(x, z, 5) < 0.08:
                put(x, -dep - 1, z, "amethyst_cluster[facing=down,waterlogged=false]")
            elif h(x, z, 5) < 0.18:
                put(x, -dep - 1, z, "hanging_roots[waterlogged=false]")
    # top
    for x in range(-R - 3, R + 4):
        for z in range(-R - 3, R + 4):
            d = math.hypot(x, z)
            er = edge_r(x, z, R, len(realm))
            if d > er:
                continue
            if d <= 2.5:
                b = th["accent"] if (x, z) == (0, 0) else th["inner"]
            elif d <= 3.6:
                b = "amethyst_block"
            elif d <= 10:
                b = th["floor"][(x + z) % 2] if h(x, z, 2) > 0.06 else th["inner"]
            elif d <= 11.4:
                t = abs(((math.atan2(z, x) / math.pi + 1) / 2) * 2 - 1)
                b = GRAD[min(len(GRAD) - 1, int(t * len(GRAD)))]
            elif d <= 13:
                b = th["edge"]
            elif d <= er - 1.2 or abs(x) <= 2:
                b = th["top"]
            else:
                b = th["rim"] if realm != "cannons" else th["edge"]
            if abs(x) <= 2 and -16 <= z <= -11:
                b = th["floor"][0] if abs(x) < 2 else th["edge"]
            put(x, 0, z, b)
            # flora on the outer ring
            if d > 13.5 and d < er - 1.5 and abs(x) > 2:
                f = h(x, z, 77)
                if realm == "skyblock":
                    if f < 0.12:
                        put(x, 1, z, "short_grass")
                    elif f < 0.16:
                        put(x, 1, z, "poppy" if h(x, z) < 0.5 else "cornflower")
                    elif f < 0.18:
                        put(x, 1, z, "azalea_leaves[distance=1,persistent=true,waterlogged=false]")
                elif realm == "factions" and f < 0.10:
                    put(x, 1, z, "crimson_roots" if h(x, z) < 0.7 else "crimson_fungus")
                elif realm == "cannons" and f < 0.04:
                    put(x, 1, z, "dispenser[facing=up,triggered=false]")

    # hub portal (round, purple) straight ahead
    pz, pr = -15, 3
    cy = 1 + pr + 1
    for x in range(-pr - 2, pr + 3):
        for y in range(-pr - 2, pr + 3):
            d = math.hypot(x, y)
            if cy + y < 1:
                continue
            if d <= pr - 0.4:
                put(x, cy + y, pz, "nether_portal[axis=x]")
            elif d <= pr + 0.7:
                put(x, cy + y, pz, "amethyst_block" if (x + y) % 2 else "purpur_block")
                put(x, cy + y, pz - 1, "polished_blackstone_bricks")
    put(0, cy + pr + 2, pz, "end_rod[facing=up]")

    # lantern posts (8, around the plaza)
    for i in range(8):
        a = i * math.pi / 4 + math.pi / 8
        x, z = round(12.2 * math.cos(a)), round(12.2 * math.sin(a))
        if z < -9 and abs(x) < 6:
            continue
        for y in range(1, 4):
            put(x, y, z, "polished_blackstone_wall[east=none,north=none,south=none,up=true,waterlogged=false,west=none]")
        put(x, 4, z, th["lantern"] + "[hanging=false,waterlogged=false]")

    # realm decor behind the spawn (south) and on the sides
    if realm == "factions":
        for x in (-7, 7):
            for y in range(1, 9):
                put(x, y, 7, "polished_blackstone_bricks" if y % 3 else "red_nether_bricks")
            put(x, 9, 7, "shroomlight")
            put(x, 10, 7, "tnt[unstable=false]")
        for x in range(-6, 7):
            put(x, 8, 7, "polished_blackstone_brick_slab[type=bottom,waterlogged=false]")
        for x, z in ((-16, 4), (16, -4), (-12, 13), (13, 12)):
            for y in range(1, 4):
                put(x, y, z, "tnt[unstable=false]")
    elif realm == "skyblock":
        def tree(x0, z0, hgt):
            for y in range(1, hgt + 1):
                put(x0, y, z0, "cherry_log[axis=y]")
            for x in range(-3, 4):
                for y in range(-1, 3):
                    for z in range(-3, 4):
                        if x * x + (y * 1.6) ** 2 + z * z <= 9 and h(x0 + x, y, z0 + z) < 0.85:
                            put(x0 + x, hgt + y + 1, z0 + z, "cherry_leaves[distance=1,persistent=true,waterlogged=false]", force=False)
        for x0, z0 in ((-15, 6), (15, 8), (-10, 15), (12, -8)):
            tree(x0, z0, 4 + int(h(x0, z0) * 3))
        # pond
        for x in range(-4, 5):
            for z in range(14, 19):
                if (x / 4) ** 2 + ((z - 16) / 2.4) ** 2 <= 1:
                    put(x, 0, z, "water")
                    put(x, -1, z, "clay")
        put(0, 1, 16, "lily_pad")
    else:
        for x in (-7, 7):
            for y in range(1, 9):
                put(x, y, 7, "dispenser[facing=north,triggered=false]" if y % 3 == 0 else "obsidian")
            put(x, 9, 7, "redstone_lamp[lit=true]")
        for x in range(-6, 7):
            put(x, 8, 7, "obsidian")
        # a sample sand/tnt barrel on the side, just for looks
        for z in range(-6, 7):
            put(-17, 1, z, "obsidian")
            put(-17, 2, z, "white_carpet" if z % 2 else "obsidian")
    # invisible lights
    for x in range(-R, R + 1, 6):
        for z in range(-R, R + 1, 6):
            if math.hypot(x, z) < R - 3 and (x, 3, z) not in blocks:
                put(x, 3, z, "light[level=13,waterlogged=false]")

    palette, idx, lines = [], {}, []
    for (x, y, z), b in sorted(blocks.items(), key=lambda kv: (kv[0][1], kv[0][0], kv[0][2])):
        if b not in idx:
            idx[b] = len(palette)
            palette.append(b)
        lines.append(f"{x} {y} {z} {idx[b]}")
    out = os.path.join(OUTDIR, f"spawn-{realm}.txt.gz")
    with gzip.open(out, "wt") as f:
        f.write("|".join(palette) + "\n" + "\n".join(lines) + "\n")
    print(f"{realm}: {len(lines)} blocks -> {os.path.getsize(out) // 1024} KB")
    return blocks


if __name__ == "__main__":
    prev = sys.argv[sys.argv.index("--preview") + 1] if "--preview" in sys.argv else None
    for r in [t for t in THEMES if t not in ("skyblock", "factions")]:  # own generators: genspawn_skyblock.py, genspawn_factions.py
        b = build(r)
        if prev:
            import matplotlib
            matplotlib.use("Agg")
            import matplotlib.pyplot as plt
            COL = [("water", "#3a6ee8"), ("nether_portal", "#7a2ad8"), ("cherry_leaves", "#f4b6d8"), ("cherry", "#5a3040"), ("grass", "#5d9a3a"),
                   ("moss", "#5a7a2e"), ("light_blue", "#38E0FF"), ("cyan", "#16a0a8"), ("blue_concrete", "#3c44aa"), ("magenta", "#c050c0"),
                   ("purple", "#8932b8"), ("purpur", "#a77ba7"), ("amethyst", "#9a5cc6"), ("shroomlight", "#ffa040"), ("crimson", "#8a2030"),
                   ("red_nether", "#5a1010"), ("tnt", "#d03030"), ("obsidian", "#1a1426"), ("redstone_lamp", "#ffcf80"), ("dispenser", "#777"),
                   ("verdant", "#d8f0c0"), ("blackstone", "#2a2530"), ("deepslate", "#45454f"), ("stone", "#7a7a7a")]

            def col(x):
                x = x.split("[")[0]
                for k, v in COL:
                    if k in x:
                        return v
                return "#555"
            top = {}
            for (x, y, z), bb in b.items():
                if "light[" in bb:
                    continue
                if (x, z) not in top or y > top[(x, z)][0]:
                    top[(x, z)] = (y, bb)
            fig, ax = plt.subplots(figsize=(5, 5), facecolor="#0b0b14")
            for (x, z), (y, bb) in top.items():
                ax.add_patch(plt.Rectangle((x, z), 1, 1, color=col(bb)))
            ax.set_xlim(-26, 26); ax.set_ylim(26, -26); ax.set_aspect("equal"); ax.set_facecolor("#0b0b14"); ax.set_title(r, color="w")
            plt.savefig(os.path.join(prev, f"spawn-{r}.png"), dpi=70, facecolor="#0b0b14")
