"""RelapsedMC Factions spawn: "the Fault" -> plugins/lag-core/src/main/resources/spawn-factions.txt.gz

(0,0,0) = spawn floor centre, players spawn facing north (-Z). Floor is y=0, players stand at y=1.
The citadel is split down the middle by a glowing fault line:
  west  (x<0): CLASSIC  - intact blackstone, red trims, gold, warm light, grass rim
  east  (x>0): CORRUPTED - cracked deepslate, sculk, crying obsidian, craters, soul light, dead rim
  north : grand HUB portal on a raised dais (stairs up)
  south : the Fractured Obelisk (half classic, half corrupted), spinning TNT above it
  west  : Getting Started kiosk (panels.yml factions: at [-17.92, 2.05, 0.5], facing east)
  east  : Top Factions podium (laGCannons leaderboard hologram above it)
  ring  : battlement wall with 4 corner towers (ruined on the corrupted side), 4 diagonal gates with stairs down
  under : a deep stone foundation so it sits like a mesa on any terrain
Run: python3 tools/hub/genspawn_factions.py [--preview dir]
"""
import gzip, math, os, sys

OUT = os.path.join(os.path.dirname(__file__), "..", "..", "plugins", "lag-core", "src", "main", "resources", "spawn-factions.txt.gz")
R = 38          # plateau radius (old builds are cleared within 48)
WALL = 34       # wall ring radius
B = {}


def h(*a):
    v = 2166136261
    for n in a:
        v ^= (int(n) * 2654435761) & 0xFFFFFFFF
        v = (v * 16777619) & 0xFFFFFFFF
    v ^= v >> 13
    v = (v * 1274126177) & 0xFFFFFFFF
    v ^= v >> 16
    return v / 2**32


def vn(x, z, s, seed=7):
    x0, z0 = math.floor(x / s), math.floor(z / s)
    fx, fz = x / s - x0, z / s - z0
    fx, fz = fx * fx * (3 - 2 * fx), fz * fz * (3 - 2 * fz)
    a, b, c, d = h(x0, z0, seed), h(x0 + 1, z0, seed), h(x0, z0 + 1, seed), h(x0 + 1, z0 + 1, seed)
    return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fz


def put(x, y, z, b, force=True):
    if not force and (x, y, z) in B:
        return
    B[(x, y, z)] = b if b.startswith("minecraft:") else "minecraft:" + b


def air(x, y, z):
    B.pop((x, y, z), None)


def edge(x, z):
    return R + (vn(math.atan2(z, x) * 7, 3, 1.3, 5) - 0.5) * 5


def side(x):
    """-1 classic (west), +1 corrupted (east), 0 = on the fault."""
    return 0 if abs(x) <= 1 else (-1 if x < 0 else 1)


def corrupt(x, z):
    """How decayed this column is, 0..1 (fades in across the fault so it isn't a ruler-straight line)."""
    w = x + (vn(z, 0, 4, 31) - 0.5) * 6
    return max(0.0, min(1.0, (w + 2) / 6))


CLASSIC_FLOOR = ["polished_blackstone_bricks", "polished_blackstone_bricks", "polished_blackstone", "chiseled_polished_blackstone"]
CORRUPT_FLOOR = ["cracked_deepslate_bricks", "deepslate_bricks", "cracked_deepslate_tiles", "deepslate_tiles", "sculk", "tuff_bricks"]
GRAD = ["light_blue_concrete", "cyan_concrete", "blue_concrete", "purple_concrete", "purpur_block", "magenta_concrete"]
WALLS = "[east=none,north=none,south=none,up=true,waterlogged=false,west=none]"


def floor_block(x, z):
    c = corrupt(x, z)
    n = h(x, z, 2)
    if n < c:
        return CORRUPT_FLOOR[int(h(x, z, 9) * len(CORRUPT_FLOOR))]
    d = math.hypot(x, z)
    ang = math.degrees(math.atan2(z, x)) % 360
    if abs(d - 20) < 0.6 or abs(d - 29) < 0.6:
        return "red_nether_bricks"
    if 12 < d < 33 and min(ang % 22.5, 22.5 - ang % 22.5) < 0.9 * 12 / d:
        return "polished_blackstone"
    if h(x, z, 41) < 0.03:
        return "gilded_blackstone"
    return CLASSIC_FLOOR[int(h(x, z, 8) * len(CLASSIC_FLOOR))]


# ---------------------------------------------------------------- foundation + plateau
def plateau():
    for x in range(-R - 4, R + 5):
        for z in range(-R - 4, R + 5):
            d = math.hypot(x, z)
            er = edge(x, z)
            if d > er:
                continue
            c = corrupt(x, z)
            # foundation: deep in the middle, shallower at the rim, ragged bottom
            dep = int(6 + 22 * (1 - (d / er) ** 2) ** 0.7 + (vn(x * 2, z * 2, 5) - 0.5) * 6)
            for dy in range(1, dep + 1):
                n = h(x, -dy, z, 3)
                if dy <= 2:
                    b = "mud" if c > 0.5 else "dirt"
                elif c > 0.5:
                    b = "sculk" if n < 0.06 else "crying_obsidian" if n < 0.08 else "deepslate" if n < 0.6 else "cobbled_deepslate" if n < 0.8 else "tuff"
                else:
                    b = "gilded_blackstone" if n < 0.02 else "blackstone" if n < 0.35 else "stone" if n < 0.75 else "andesite"
                put(x, -dy, z, b)
            # top surface
            if d <= er - 3.2:
                b = floor_block(x, z)
            elif c > 0.5:
                b = "podzol" if h(x, z, 4) < 0.5 else "coarse_dirt"
            else:
                b = "grass_block[snowy=false]"
            put(x, 0, z, b)
            # rim flora (outside the wall)
            if WALL + 1.5 < d < er - 1:
                f = h(x, z, 77)
                if c > 0.5:
                    if f < 0.10:
                        put(x, 1, z, "dead_bush")
                    elif f < 0.13:
                        put(x, 1, z, "brown_mushroom")
                else:
                    if f < 0.18:
                        put(x, 1, z, "short_grass")
                    elif f < 0.22:
                        put(x, 1, z, "poppy" if h(x, z) < 0.6 else "cornflower")


def plaza():
    # inner plaza: brand ring + compass inlays
    for x in range(-12, 13):
        for z in range(-12, 13):
            d = math.hypot(x, z)
            if d <= 2.5:
                put(x, 0, z, "crying_obsidian" if (x, z) == (0, 0) else "polished_blackstone")
            elif d <= 3.6:
                put(x, 0, z, "amethyst_block")
            elif 9.6 < d <= 11:
                t = abs(((math.atan2(z, x) / math.pi + 1) / 2) * 2 - 1)
                put(x, 0, z, GRAD[min(len(GRAD) - 1, int(t * len(GRAD)))])
            elif 11 < d <= 12:
                put(x, 0, z, "polished_blackstone_bricks" if x < 0 else "cracked_deepslate_bricks")
    # compass spokes
    for i in range(4, 10):
        for (x, z) in ((0, -i), (0, i), (-i, 0), (i, 0)):
            put(x, 0, z, "gilded_blackstone" if x <= 0 else "sculk_catalyst")


def fault():
    """The glowing crack running north-south through everything."""
    for z in range(-R - 2, R + 3):
        wob = round((vn(z, 0, 6, 51) - 0.5) * 2)
        for x in (wob, wob + (1 if h(z, 1) < 0.5 else -1)):
            d = math.hypot(x, z)
            if d > edge(x, z) - 0.5:
                continue
            if 3.6 < d < 9.6 or d > 12:
                n = h(x, z, 3)
                put(x, 0, z, "pearlescent_froglight" if n < 0.3 else "crying_obsidian" if n < 0.75 else "amethyst_block")
                put(x, -1, z, "pearlescent_froglight")
                if h(x, z, 13) < 0.12 and d > 13:
                    put(x, 1, z, "amethyst_cluster[facing=up,waterlogged=false]")
        # the crack splits the foundation too: a glowing vein down the cliff face
        for dy in range(2, 16):
            for x in (wob,):
                if (x, -dy, z) in B and abs(math.hypot(x, z) - edge(x, z)) < 2.5:
                    put(x, -dy, z, "crying_obsidian" if h(x, dy, z) < 0.6 else "pearlescent_froglight")


def craters():
    """Blast scars on the corrupted half (shallow, walkable)."""
    for cx, cz, r in ((15, -14, 3.2), (27, -13, 2.6), (12, 24, 2.8), (27, -6, 2.2), (6, -26, 2.4)):
        for x in range(cx - 4, cx + 5):
            for z in range(cz - 4, cz + 5):
                d = math.hypot(x - cx, z - cz)
                if d <= r and (x, 0, z) in B:
                    put(x, 0, z, "cobbled_deepslate" if d > r - 1 else ("magma_block" if False else "blackstone"))
                    put(x, -1, z, "deepslate")
                    if d <= r - 1.2:
                        put(x, 0, z, "gravel" if h(x, z) < 0.5 else "tuff")
                        put(x, -1, z, "crying_obsidian" if h(x, z, 2) < 0.15 else "deepslate")
                elif d <= r + 1 and (x, 0, z) in B and h(x, z, 6) < 0.35:
                    put(x, 1, z, "cobbled_deepslate_slab[type=bottom,waterlogged=false]", force=False)


# ---------------------------------------------------------------- landmarks
def hub_portal():
    pz, pr = -22, 4
    # dais
    for x in range(-8, 9):
        for z in range(pz - 4, pz + 6):
            if abs(x) <= 8 - max(0, z - (pz + 2)):
                for y in range(1, 3):
                    if z <= pz + 2 or y <= 2 - (z - (pz + 2)):
                        put(x, y, z, "polished_blackstone_bricks" if x < 0 else "deepslate_tiles")
    for x in range(-3, 4):  # stairs up from the plaza
        put(x, 1, pz + 5, "polished_blackstone_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]")
        put(x, 2, pz + 4, "polished_blackstone_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]")
        put(x, 1, pz + 4, "polished_blackstone")
    base = 3
    cy = base + pr + 1
    for x in range(-pr - 3, pr + 4):
        for y in range(-pr - 3, pr + 4):
            d = math.hypot(x, y)
            if cy + y < base:
                continue
            if d <= pr - 0.4:
                put(x, cy + y, pz, "nether_portal[axis=x]")
            elif d <= pr + 0.8:
                put(x, cy + y, pz, "amethyst_block" if (x + y) % 2 else "purpur_block")
                put(x, cy + y, pz - 1, "polished_blackstone_bricks" if x <= 0 else "cracked_deepslate_bricks")
            elif d <= pr + 1.8 and h(x, y, 7) < (0.55 if x > 0 else 0.0):
                put(x, cy + y, pz - 1, "crying_obsidian")  # corrupted half of the frame is "leaking"
    put(0, cy + pr + 2, pz, "end_rod[facing=up]")
    put(0, cy + pr + 3, pz, "end_rod[facing=up]")
    # flanking braziers
    for x in (-7, 7):
        for y in range(3, 7):
            put(x, y, pz + 1, "polished_blackstone_wall" + WALLS if x < 0 else "cobbled_deepslate_wall" + WALLS)
        put(x, 7, pz + 1, "lantern[hanging=false,waterlogged=false]" if x < 0 else "soul_lantern[hanging=false,waterlogged=false]")


def obelisk():
    oz = 17
    top = 24
    for y in range(1, top + 1):
        for x in range(-2, 3):
            for z in range(oz - 2, oz + 3):
                w = 2 if y <= 7 else 1
                if max(abs(x), abs(z - oz)) > w:
                    continue
                # the top is broken: corrupted half crumbles away
                if x > 0 and y > top - 6 + int(h(x, z, 3) * 4):
                    continue
                if x == 0 and y > top - 2:
                    continue
                if x < 0:
                    b = "red_nether_bricks" if y % 5 == 0 else ("chiseled_polished_blackstone" if y % 5 == 1 else "polished_blackstone_bricks")
                elif x > 0:
                    b = "crying_obsidian" if h(x, y, z) < 0.12 else ("sculk" if h(x, y, z, 2) < 0.15 else "cracked_deepslate_bricks")
                else:
                    b = "amethyst_block" if y % 3 else "pearlescent_froglight"
                put(x, y, z, b)
    # base steps
    for x in range(-4, 5):
        for z in range(oz - 4, oz + 5):
            if max(abs(x), abs(z - oz)) == 3:
                put(x, 1, z, "polished_blackstone_brick_slab[type=bottom,waterlogged=false]" if x <= 0 else "cobbled_deepslate_slab[type=bottom,waterlogged=false]")
    # chains + falling debris on the broken side
    for (x, y, z) in ((2, top - 3, oz), (3, top - 7, oz + 1), (2, top - 9, oz - 2)):
        put(x, y, z, "cracked_deepslate_bricks")
    for y in range(top - 1, top + 2):
        put(-1, y, oz, "iron_chain[axis=y,waterlogged=false]")
    put(-1, top + 2, oz, "lantern[hanging=true,waterlogged=false]")


def kiosk():
    """Classic side: roofed 'Getting Started' kiosk, open to the east. Panel at x=-17.92 facing east."""
    x0 = -18
    for z in range(-5, 6):
        for x in range(x0 - 3, x0 + 3):
            put(x, 0, z, "polished_blackstone" if (x + z) % 2 else "polished_blackstone_bricks")
    # back wall (panel sits in front of it), posts, roof
    for z in range(-4, 5):
        for y in range(1, 7):
            put(x0 - 1, y, z, "red_nether_bricks" if z in (-4, 4) or y in (1, 6) else "black_concrete")
    for z in (-5, 5):
        for y in range(1, 7):
            put(x0 + 2, y, z, "polished_blackstone_brick_wall" + WALLS)
            put(x0 - 2, y, z, "polished_blackstone_brick_wall" + WALLS)
    for x in range(x0 - 3, x0 + 4):
        for z in range(-6, 7):
            put(x, 7, z, "polished_blackstone_brick_slab[type=bottom,waterlogged=false]")
        put(x, 7, -6, "polished_blackstone_brick_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]")
        put(x, 7, 6, "polished_blackstone_brick_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]")
    for z in range(-6, 7):
        put(x0 + 3, 7, z, "red_nether_brick_stairs[facing=west,half=bottom,shape=straight,waterlogged=false]")
    for z in (-3, 0, 3):
        put(x0 + 1, 6, z, "lantern[hanging=true,waterlogged=false]")
    # gold trim + planters out front
    for z in (-5, 5):
        put(x0 + 3, 1, z, "gilded_blackstone")
        put(x0 + 3, 2, z, "crimson_fungus")


def podium():
    """Corrupted side: Top Factions podium facing the plaza (west). Leaderboard hologram floats above."""
    x0 = 19
    for x in range(x0 - 3, x0 + 5):
        for z in range(-7, 8):
            put(x, 0, z, "deepslate_tiles" if (x + z) % 2 else "polished_deepslate")
    steps = {0: ("gold_block", 3), -3: ("iron_block", 2), 3: ("copper_block", 1)}
    for zc, (mat, hgt) in steps.items():
        for z in range(zc - 1, zc + 2):
            for x in range(x0, x0 + 3):
                for y in range(1, hgt + 1):
                    put(x, y, z, "polished_deepslate" if y < hgt else mat)
    # backdrop: broken corrupted arch
    for z in range(-7, 8):
        top = 9 if abs(z) < 6 else 6
        for y in range(1, top + 1):
            if abs(z) <= 5 and 3 < y < 9 and abs(z) < 5:
                continue
            if y > 7 and h(z, y, 9) < 0.35:
                continue
            put(x0 + 4, y, z, "crying_obsidian" if h(z, y) < 0.15 else "cracked_deepslate_bricks")
    for z in (-6, 6):
        put(x0 + 3, 1, z, "sculk_shrieker[can_summon=false,shrieking=false,waterlogged=false]")
        put(x0 + 3, 7, z, "soul_lantern[hanging=false,waterlogged=false]")
        for y in range(2, 7):
            put(x0 + 3, y, z, "cobbled_deepslate_wall" + WALLS)


def walls():
    gates = [math.radians(a) for a in (45, 135, 225, 315)]
    for x in range(-WALL - 2, WALL + 3):
        for z in range(-WALL - 2, WALL + 3):
            d = math.hypot(x, z)
            if not (WALL - 0.7 <= d <= WALL + 0.7):
                continue
            ang = math.atan2(z, x)
            if any(abs((ang - g + math.pi) % (2 * math.pi) - math.pi) < 0.11 for g in gates):
                continue  # gate gap
            if abs(x) <= 6 and z < 0 and False:
                continue
            c = corrupt(x, z)
            hgt = 3 if (x + z) % 2 else 4
            if c > 0.5:
                hgt = max(0, int(4.5 * vn(ang * 30, 0, 2.2, 21) ** 1.3 + (h(x, z, 21) < 0.15)))  # ruined on the corrupted side
            for y in range(1, hgt + 1):
                if c > 0.5:
                    b = "cracked_deepslate_bricks" if h(x, y, z) < 0.6 else ("crying_obsidian" if h(x, y, z, 2) < 0.2 else "cobbled_deepslate")
                else:
                    b = "red_nether_bricks" if y == hgt else "polished_blackstone_bricks"
                put(x, y, z, b)
    # gate stairs down the cliff (outward), with lantern posts
    for g in gates:
        ux, uz = math.cos(g), math.sin(g)
        for step in range(0, 14):
            r = WALL + 1 + step
            for w in (-1, 0, 1):
                x = round(ux * r - uz * w)
                z = round(uz * r + ux * w)
                y = -step // 2
                put(x, y, z, "polished_blackstone_bricks" if x < 0 else "deepslate_bricks")
                for yy in range(y + 1, y + 4):
                    air(x, yy, z)
        for w in (-2, 2):
            x, z = round(ux * (WALL) - uz * w), round(uz * (WALL) + ux * w)
            for y in range(1, 6):
                put(x, y, z, "polished_blackstone_bricks" if x < 0 else "deepslate_tiles")
            put(x, 6, z, "lantern[hanging=false,waterlogged=false]" if x < 0 else "soul_lantern[hanging=false,waterlogged=false]")


def towers():
    for i, a in enumerate((0, 90, 180, 270)):
        g = math.radians(a)
        tx, tz = round(math.cos(g) * WALL), round(math.sin(g) * WALL)
        if a == 270:  # north: the hub portal is the landmark there; tower sits further out
            tz -= 0
        cor = tx > 1
        top = 14 if not cor else 10
        for x in range(tx - 3, tx + 4):
            for z in range(tz - 3, tz + 4):
                d = math.hypot(x - tx, z - tz)
                if d > 3.2:
                    continue
                for y in range(1, top + 1):
                    if cor and y > top - 4 + int(h(x, z, 4) * 5):
                        continue
                    if d > 2.2 or y == top:
                        if cor:
                            b = "crying_obsidian" if h(x, y, z) < 0.12 else "cracked_deepslate_bricks"
                        else:
                            b = "red_nether_bricks" if y % 6 == 0 else "polished_blackstone_bricks"
                        put(x, y, z, b)
                if d > 2.2 and not cor and h(x, z) < 0.6:
                    put(x, top + 1, z, "polished_blackstone_brick_wall" + WALLS)
        # brazier on top
        put(tx, top + 1, tz, "soul_campfire[facing=north,lit=true,signal_fire=false,waterlogged=false]" if cor else "campfire[facing=north,lit=true,signal_fire=false,waterlogged=false]")
        for y in range(top + 2, top + 4):
            air(tx, y, tz)


def lamps():
    for i in range(12):
        a = i * math.pi / 6 + math.pi / 12
        x, z = round(15.5 * math.cos(a)), round(15.5 * math.sin(a))
        if (x, 1, z) in B:
            continue
        cl = x <= 0
        for y in range(1, 4):
            put(x, y, z, ("polished_blackstone_wall" if cl else "cobbled_deepslate_wall") + WALLS)
        put(x, 4, z, "lantern[hanging=false,waterlogged=false]" if cl else "soul_lantern[hanging=false,waterlogged=false]")


def classic_decor():
    # crimson trees in raised planters
    for tx, tz in ((-26, -12), (-26, 13), (-11, 26), (-12, -27)):
        for x in range(tx - 2, tx + 3):
            for z in range(tz - 2, tz + 3):
                edge_ = max(abs(x - tx), abs(z - tz)) == 2
                put(x, 1, z, "polished_blackstone_bricks" if edge_ else "crimson_nylium")
        hgt = 6 + int(h(tx, tz) * 3)
        for y in range(2, 2 + hgt):
            put(tx, y, tz, "crimson_stem[axis=y]")
        for x in range(-3, 4):
            for y in range(-1, 3):
                for z in range(-3, 4):
                    if x * x + (y * 1.7) ** 2 + z * z <= 10 and h(tx + x, y, tz + z) < 0.88:
                        b = "shroomlight" if h(x, y, z, tx) < 0.08 else "nether_wart_block"
                        put(tx + x, 2 + hgt + y, tz + z, b, force=False)
        for x in range(-2, 3):
            put(tx + x, 1 + hgt, tz, "weeping_vines_plant", force=False) if h(tx + x, tz) < 0.3 else None
    # benches along the plaza ring
    for i in range(6):
        a = math.radians(100 + i * 32)
        x, z = round(13.5 * math.cos(a)), round(13.5 * math.sin(a))
        if x > -2 or (x, 1, z) in B:
            continue
        put(x, 1, z, "dark_oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]" if abs(x) > abs(z) else
            ("dark_oak_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]" if z < 0 else "dark_oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]"))
    # red carpet from the plaza to the kiosk
    for x in range(-15, -11):
        for z in (-1, 0, 1):
            put(x, 1, z, "red_carpet", force=False)


def corrupt_decor():
    # giant fossil ribs breaking out of the ground (south-east)
    fx, fz = 22, 20
    for i in range(7):
        zz = fz - 9 + i * 3
        r = 6 - abs(i - 3) * 0.9
        for t in range(0, 19):
            a = math.pi * t / 18
            x = fx + round(math.cos(a) * r * 0.55)
            y = round(math.sin(a) * r)
            if y >= 1 and math.hypot(x, zz) < WALL - 2:
                put(x, y, zz, "bone_block[axis=z]")
    for z in range(fz - 10, fz + 11):  # spine
        if math.hypot(fx, z) < WALL - 2:
            put(fx, 1, z, "bone_block[axis=z]")
    # dead trees
    for tx, tz in ((26, -14), (10, -31), (29, 4)):
        if math.hypot(tx, tz) > WALL - 2:
            continue
        hgt = 5 + int(h(tx, tz) * 3)
        for y in range(1, hgt + 1):
            put(tx, y, tz, "stripped_dark_oak_log[axis=y]")
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            if h(tx, tz, dx, dz) < 0.7:
                L = 2 + int(h(dx, dz, tx) * 2)
                for k in range(1, L + 1):
                    put(tx + dx * k, hgt - 1 + k // 2, tz + dz * k, "stripped_dark_oak_log[axis=" + ("x" if dx else "z") + "]")
    # sculk creeping off the fault
    for x in range(2, WALL):
        for z in range(-WALL, WALL):
            if (x, 0, z) in B and vn(x, z, 5, 61) > 0.68 and math.hypot(x, z) < WALL - 1:
                put(x, 0, z, "sculk")
                if h(x, z, 62) < 0.05 and (x, 1, z) not in B:
                    put(x, 1, z, "sculk_sensor[power=0,sculk_sensor_phase=inactive,waterlogged=false]")


def lights():
    for x in range(-R, R + 1, 5):
        for z in range(-R, R + 1, 5):
            if math.hypot(x, z) < R - 3 and (x, 3, z) not in B:
                put(x, 3, z, "light[level=14,waterlogged=false]")


def build():
    plateau()
    plaza()
    craters()
    fault()
    walls()
    towers()
    hub_portal()
    obelisk()
    kiosk()
    podium()
    classic_decor()
    corrupt_decor()
    lamps()
    lights()
    # keep the spawn point and the walk to the portal clear
    for y in (1, 2):
        air(0, y, 0)
    return B


def save():
    palette, idx, lines = [], {}, []
    for (x, y, z), b in sorted(B.items(), key=lambda kv: (kv[0][1], kv[0][0], kv[0][2])):
        if b not in idx:
            idx[b] = len(palette)
            palette.append(b)
        lines.append(f"{x} {y} {z} {idx[b]}")
    with gzip.open(OUT, "wt") as f:
        f.write("|".join(palette) + "\n" + "\n".join(lines) + "\n")
    print(f"factions: {len(lines)} blocks, {len(palette)} block types -> {os.path.getsize(OUT) // 1024} KB")


if __name__ == "__main__":
    build()
    save()
    if "--preview" in sys.argv:
        sys.path.insert(0, os.path.dirname(__file__))
        from isorender import render
        d = sys.argv[sys.argv.index("--preview") + 1]
        os.makedirs(d, exist_ok=True)
        top = {k: v for k, v in B.items() if k[1] >= -3}
        for view in ("se", "nw"):
            print(render(top, os.path.join(d, f"spawn-factions-{view}.png"), view=view, S=7))
