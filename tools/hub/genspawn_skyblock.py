"""RelapsedMC Skyblock spawn (v2) -> plugins/lag-core/src/main/resources/spawn-skyblock.txt.gz

(0,0,0) = spawn floor centre, players spawn facing north (-Z). Floor is y=0, players stand at y=1.
  north : grand HUB portal arch on a raised platform
  west  : roofed kiosk holding the Getting Started panel (panels.yml: at [-11.92, 2.0, 0.5], facing east)
  east  : market corner (stalls, barrels)
  south : pond, stream and a waterfall off the island edge
  + cherry trees, rolling grass, ore-studded cliffs with dripstone and glow berries underneath, 4 floating islets.
Run: python3 tools/hub/genspawn_skyblock.py [--preview dir]
"""
import gzip, math, os, sys
from collections import deque

OUT = os.path.join(os.path.dirname(__file__), "..", "..", "plugins", "lag-core", "src", "main", "resources", "spawn-skyblock.txt.gz")
R = 27
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


def get(x, y, z):
    return B.get((x, y, z), "").replace("minecraft:", "").split("[")[0]


def free(x, y, z):
    return (x, y, z) not in B


def smooth(a, b, t):
    t = max(0.0, min(1.0, (t - a) / (b - a)))
    return t * t * (3 - 2 * t)


# ------------------------------------------------------------------ shapes / states
def stairs(mat, facing, half="bottom"):
    return f"{mat}_stairs[facing={facing},half={half},shape=straight,waterlogged=false]"


def slab(mat, typ="bottom"):
    return f"{mat}_slab[type={typ},waterlogged=false]"


WALL = "stone_brick_wall[east=none,north=none,south=none,up=true,waterlogged=false,west=none]"
FENCE = "spruce_fence[east=false,north=false,south=false,waterlogged=false,west=false]"
LANTERN = "lantern[hanging=false,waterlogged=false]"
HLANTERN = "lantern[hanging=true,waterlogged=false]"
CHAIN = "iron_chain[axis=y,waterlogged=false]"
LEAVES = "cherry_leaves[distance=1,persistent=true,waterlogged=false]"
AZ_LEAVES = "flowering_azalea_leaves[distance=1,persistent=true,waterlogged=false]"
GRASS = "grass_block[snowy=false]"


def edge_r(x, z):
    a = math.atan2(z, x)
    return R + (vn(a * 8 + 40, 3, 1.6, 21) - 0.5) * 5 + math.cos(2 * a) * 1.2


# ------------------------------------------------------------------ flat zones (plaza, paths, structures)
def flat_zone(x, z):
    d = math.hypot(x, z)
    if d <= 12.6:
        return True
    if abs(x) <= 3 and -24 <= z <= -10:  # north path + portal approach
        return True
    if abs(x) <= 9 and -25 <= z <= -17:  # portal platform
        return True
    if -16 <= x <= -9 and -6 <= z <= 7:  # kiosk
        return True
    if 9 <= x <= 21 and -9 <= z <= 10:  # market
        return True
    if abs(x) <= 3 and 10 <= z <= 14:  # south path
        return True
    if (x / 8.0) ** 2 + ((z - 18) / 5.2) ** 2 <= 1:  # pond + shore
        return True
    if abs(x) <= 2 and 18 <= z <= 30:  # stream
        return True
    return False


N = R + 5
inside = {(x, z) for x in range(-N, N + 1) for z in range(-N, N + 1) if math.hypot(x, z) <= edge_r(x, z)}
# distance (in blocks) from the flat zones, so hills ease in instead of stepping up at the path edge
dist = {}
q = deque()
for c in inside:
    if flat_zone(*c):
        dist[c] = 0
        q.append(c)
while q:
    x, z = q.popleft()
    for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        n = (x + dx, z + dz)
        if n in inside and n not in dist:
            dist[n] = dist[(x, z)] + 1
            q.append(n)

G = {}  # ground (top block) height
for (x, z) in inside:
    d = math.hypot(x, z)
    er = edge_r(x, z)
    raw = 0.4 + 3.2 * vn(x + 100, z, 9, 3) + 1.4 * vn(x, z, 4, 5)
    t = smooth(0, 5, dist.get((x, z), 9))
    g = raw * t
    g *= 1 - smooth(er - 3.5, er, d) * 0.65  # rounded lip at the edge
    G[(x, z)] = max(0, int(round(g)))

# ------------------------------------------------------------------ main island body
ORES = [("coal_ore", 34), ("iron_ore", 26), ("copper_ore", 16), ("gold_ore", 10), ("lapis_ore", 6), ("redstone_ore[lit=false]", 5),
        ("diamond_ore", 2.5), ("emerald_ore", 1.5)]


def ore(seed, deep):
    tot = sum(w for _, w in ORES)
    r = h(seed, 99) * tot
    for n, w in ORES:
        r -= w
        if r <= 0:
            return ("deepslate_" + n) if deep and not n.startswith("deepslate") else n
    return "coal_ore"


for (x, z) in inside:
    d = math.hypot(x, z)
    er = edge_r(x, z)
    k = 1 - (d / er)
    dep = int(3 + 26 * max(0, k) ** 1.15 + (vn(x * 1.7, z * 1.7, 4, 9) - 0.5) * 6)
    dep = max(2, dep)
    g = G[(x, z)]
    for y in range(-dep, g + 1):
        if y == g:
            b = GRASS
        elif y >= g - 3 + (1 if h(x, z, 4) < 0.4 else 0) and y > -4:
            b = "dirt" if h(x, y, z, 6) > 0.12 else "coarse_dirt"
        else:
            n = h(x, y, z, 3)
            deep = y < -15 + int(vn(x, z, 6, 2) * 5)
            if n < 0.045:
                b = ore(h(x, y, z, 8) * 1e6, deep)
            elif deep:
                b = "deepslate" if n < 0.7 else ("tuff" if n < 0.93 else "cobbled_deepslate")
            else:
                m = vn(x * 0.9 + y * 0.6, z * 0.9 - y * 0.4, 5, 13)
                b = "andesite" if m > 0.62 else ("stone" if m > 0.28 else "tuff" if n < 0.6 else "stone")
                if n > 0.985:
                    b = "calcite"
        put(x, y, z, b)
    # underside decor
    bot = -dep - 1
    r_ = h(x, z, 55)
    if dep > 10 and r_ < 0.07:
        put(x, -dep, z, "dripstone_block")
        ln = 1 + int(h(x, z, 56) * 3)
        names = {1: ["tip"], 2: ["frustum", "tip"], 3: ["base", "frustum", "tip"]}[ln]
        for i, th in enumerate(names):
            put(x, bot - i, z, f"pointed_dripstone[thickness={th},vertical_direction=down,waterlogged=false]")
    elif r_ < 0.15:
        ln = 2 + int(h(x, z, 57) * 4)
        for i in range(ln):
            last = i == ln - 1
            berries = "true" if h(x, i, z, 58) < 0.55 else "false"
            put(x, bot - i, z, f"cave_vines[age=25,berries={berries}]" if last else f"cave_vines_plant[berries={berries}]")
    elif r_ < 0.24:
        put(x, bot, z, "hanging_roots[waterlogged=false]")
    elif r_ < 0.255:
        put(x, bot, z, "spore_blossom")
    # moss creeping over the edge
    if d > er - 1.6 and h(x, z, 61) < 0.35:
        put(x, G[(x, z)] - 1, z, "moss_block")

# grass variety on top
for (x, z) in inside:
    g = G[(x, z)]
    if dist.get((x, z), 9) >= 1:
        n = vn(x, z, 5, 71)
        if n > 0.78:
            put(x, g, z, "moss_block")
        elif n < 0.12 and h(x, z, 72) < 0.6:
            put(x, g, z, "rooted_dirt")

# ------------------------------------------------------------------ plaza
for x in range(-13, 14):
    for z in range(-13, 14):
        d = math.hypot(x, z)
        if d > 12.6:
            continue
        ax, az = abs(x), abs(z)
        n = h(x, z, 2)
        if d <= 1.6:
            b = "chiseled_stone_bricks" if (x, z) == (0, 0) else "polished_andesite"
        elif d <= 2.7:
            b = "calcite"
        elif d <= 3.7:
            b = "polished_andesite"
        elif d <= 8.2 and min(ax, az) <= (8.2 - d) / 4.5 * 1.6:
            b = "calcite"  # cardinal points of the compass rose
        elif d <= 7.2 and abs(ax - az) <= (7.2 - d) / 3.5 * 1.2:
            b = "polished_andesite"  # diagonals
        elif d <= 8.6:
            b = "cracked_stone_bricks" if n < 0.06 else ("mossy_stone_bricks" if n < 0.1 else "stone_bricks")
        elif d <= 9.6:
            b = "polished_andesite"
        elif d <= 11.2:
            b = "cracked_stone_bricks" if n < 0.07 else ("mossy_stone_bricks" if n < 0.24 else "stone_bricks")
        else:
            b = "mossy_stone_bricks" if n < 0.45 else ("moss_block" if n < 0.75 else "mossy_cobblestone")
        put(x, 0, z, b)
for sx in (-4, 4):
    for sz in (-4, 4):
        put(sx, 0, sz, "pearlescent_froglight[axis=y]")


def path_tile(x, z, edge):
    n = h(x, z, 31)
    if edge:
        return "polished_andesite"
    return "mossy_stone_bricks" if n < 0.18 else ("cracked_stone_bricks" if n < 0.24 else "stone_bricks")


# north path
for z in range(-17, -10):
    for x in range(-2, 3):
        if math.hypot(x, z) > 11.2:
            put(x, 0, z, path_tile(x, z, abs(x) == 2))
# west path to the kiosk
for x in range(-12, -9):
    for z in range(-1, 3):
        if math.hypot(x, z) > 11.2:
            put(x, 0, z, path_tile(x, z, z in (-1, 2)))
# east path to the market
for x in range(10, 13):
    for z in range(-1, 3):
        if math.hypot(x, z) > 11.2:
            put(x, 0, z, path_tile(x, z, z in (-1, 2)))
# south path
for z in range(11, 14):
    for x in range(-2, 3):
        if math.hypot(x, z) > 11.2:
            put(x, 0, z, path_tile(x, z, abs(x) == 2))


def lamp(x, z, y0=1):
    put(x, y0, z, WALL)
    put(x, y0 + 1, z, WALL)
    put(x, y0 + 2, z, FENCE)
    put(x, y0 + 3, z, LANTERN)


# ------------------------------------------------------------------ north: portal platform + arch
PZ = -21  # portal plane
for x in range(-9, 10):
    for z in range(-25, -17):
        ex = abs(x) / 9.0
        ez = abs(z + 21) / 4.0
        if ex ** 2.4 + ez ** 2.4 > 1.05:
            continue
        rim = ex ** 2.4 + ez ** 2.4 > 0.62
        put(x, 0, z, "stone_bricks")
        put(x, 1, z, "polished_andesite" if rim else ("stone_bricks" if h(x, z, 41) > 0.15 else "mossy_stone_bricks"))
# steps up from the path
for x in range(-3, 4):
    put(x, 1, -18, stairs("stone_brick", "north"))
    put(x, 1, -17, path_tile(x, -17, abs(x) == 3) if abs(x) < 3 else "polished_andesite")
for x in (-3, 3):
    put(x, 0, -17, "polished_andesite")
# round portal: floor y=1, players walk in at y=2
CY = 6.0
for x in range(-6, 7):
    for y in range(2, 13):
        d = math.hypot(x, (y + 0.5) - (CY + 0.5))
        if d <= 3.3:
            put(x, y, PZ, "nether_portal[axis=x]")
        elif d <= 4.4:
            put(x, y, PZ, "amethyst_block" if (x + y) % 2 else "purpur_block")
            put(x, y, PZ - 1, "polished_deepslate")
        elif d <= 5.5:
            ang = math.degrees(math.atan2((y + 0.5) - (CY + 0.5), x))
            key = min(abs(ang - 90), abs(ang - 45), abs(ang - 135)) < 9
            put(x, y, PZ, "chiseled_stone_bricks" if key else ("mossy_stone_bricks" if h(x, y, 42) < 0.15 else "stone_bricks"))
            put(x, y, PZ - 1, "stone_bricks")
# base blocks the ring stands on
for x in range(-5, 6):
    if abs(x) >= 4:
        put(x, 2, PZ, "chiseled_stone_bricks")
put(0, 12, PZ, "end_rod[facing=up]")
# flanking pillars with lanterns
for sx in (-7, 7):
    put(sx, 2, PZ, "polished_andesite")
    for y in range(3, 9):
        put(sx, y, PZ, "stone_bricks" if y != 6 else "chiseled_stone_bricks")
    put(sx, 9, PZ, slab("stone_brick"))
    put(sx, 10, PZ, LANTERN)
    # small arm with a hanging lantern toward the path
    put(sx, 8, PZ + 1, stairs("stone_brick", "north", "top"))
    put(sx, 7, PZ + 1, CHAIN)
    put(sx, 6, PZ + 1, HLANTERN)
# planters on the platform
for sx in (-6, 6):
    for z in (-19, -18):
        put(sx, 2, z, "flowering_azalea" if z == -19 else "azalea")
lamp(-4, -14)
lamp(4, -14)

# ------------------------------------------------------------------ west: panel kiosk (wall face at x=-12)
KX = -13
for z in range(-4, 6):
    for y in range(1, 7):
        edge = z in (-4, 5)
        put(KX, y, z, "stripped_spruce_log[axis=y]" if edge else "polished_deepslate")
    put(KX - 1, 1, z, "stone_bricks")
    put(-12, 0, z, "polished_andesite")
    put(KX, 0, z, "stone_bricks")
# front posts + counter under the screen
for z in (-4, 5):
    for y in range(1, 7):
        put(-12, y, z, "stripped_spruce_log[axis=y]")
for z in range(-3, 5):
    put(-12, 1, z, slab("spruce", "top"))
# roof
for z in range(-5, 7):
    put(-15, 7, z, stairs("spruce", "west"))
    for x in (-14, -13, -12):
        put(x, 7, z, "spruce_planks")
    put(-11, 7, z, stairs("spruce", "east"))
    put(-14, 8, z, stairs("spruce", "west"))
    put(-13, 8, z, slab("spruce"))
    put(-12, 8, z, stairs("spruce", "east"))
for z in (-5, 6):  # gable trim
    put(-13, 8, z, stairs("spruce", "north" if z < 0 else "south"))
# hanging lanterns under the front of the roof
for z in (-4, 5):
    put(-11, 6, z, CHAIN)
    put(-11, 5, z, HLANTERN)
# vines on the back
for z in range(-4, 6):
    for y in range(2, 7):
        if h(z, y, 81) < 0.35:
            put(KX - 1, y, z, "vine[east=true,north=false,south=false,up=false,west=false]")
for z in (-3, 4):
    put(-11, 1, z, "flowering_azalea" if z < 0 else "azalea")

# ------------------------------------------------------------------ east: market
for x in range(12, 21):
    for z in range(-8, 10):
        if (x - 16) ** 2 / 25 + (z - 0.5) ** 2 / 90 > 1.0:
            continue
        n = h(x, z, 91)
        edge = (x - 16) ** 2 / 25 + (z - 0.5) ** 2 / 90 > 0.72
        put(x, 0, z, "polished_andesite" if edge else ("mossy_stone_bricks" if n < 0.12 else "stone_bricks"))


def stall(x0, z0, counter_z, colors):
    xs, zs = range(x0, x0 + 4), range(z0, z0 + 4)
    for x in (x0, x0 + 3):
        for z in (z0, z0 + 3):
            for y in (1, 2, 3):
                put(x, y, z, FENCE)
    for i, x in enumerate(xs):
        for z in zs:
            put(x, 4, z, colors[i % 2])
        # awning lip over the counter side
        put(x, 4, counter_z + (1 if counter_z > z0 else -1), colors[i % 2].replace("_wool", "_carpet"))
    for x in range(x0 + 1, x0 + 3):
        put(x, 1, counter_z, "barrel[facing=up,open=false]")
    put(x0 + 1, 2, counter_z, LANTERN)
    back = z0 if counter_z != z0 else z0 + 3
    put(x0 + 1, 1, back, "hay_block[axis=x]")
    put(x0 + 2, 1, back, "composter[level=0]")


stall(14, -7, -4, ["lime_wool", "white_wool"])
stall(14, 5, 5, ["cyan_wool", "white_wool"])
# barrels and crates around the market
for (x, y, z) in ((19, 1, -3), (19, 1, -2), (19, 2, -3), (19, 1, 4), (20, 1, 3)):
    put(x, y, z, "barrel[facing=up,open=false]" if (x + y + z) % 2 else "spruce_planks")
lamp(12, -5)
lamp(12, 6)

# ------------------------------------------------------------------ south: pond, stream, waterfall
for x in range(-8, 9):
    for z in range(13, 24):
        e = (x / 6.4) ** 2 + ((z - 18) / 3.8) ** 2
        if e <= 1:
            depth = 2 if e < 0.55 else 1
            for y in range(1, G[(x, z)] + 1):
                B.pop((x, y, z), None)
            put(x, 0, z, "water")
            if depth == 2:
                put(x, -1, z, "water")
                put(x, -2, z, "clay" if h(x, z, 3) < 0.5 else "gravel")
                if h(x, z, 5) < 0.3:
                    put(x, -1, z, "seagrass")
            else:
                put(x, -1, z, "sand" if h(x, z, 4) < 0.5 else "clay")
        elif e <= 1.55:
            put(x, 0, z, "mossy_cobblestone" if h(x, z, 6) < 0.25 else ("moss_block" if h(x, z, 7) < 0.5 else GRASS))
for (x, z) in ((-2, 17), (3, 19), (-4, 19), (1, 16)):
    put(x, 1, z, "lily_pad")
# stream to the edge, then a falling column off the cliff
fall = None
for z in range(21, N):
    if (0, z) not in inside:
        fall = z
        break
    for x in (-1, 0, 1):
        if (x, z) not in G:
            continue
        for y in range(1, G[(x, z)] + 2):
            B.pop((x, y, z), None)
        put(x, 0, z, "water")
        put(x, -1, z, "stone")
    for x in (-2, 2):
        if (x, z) in inside:
            put(x, 0, z, "mossy_cobblestone" if h(x, z, 8) < 0.5 else "moss_block")
if fall:
    for x in (-1, 0, 1):
        for y in range(0, -34, -1):
            if free(x, y, fall):
                put(x, y, fall, "water[level=8]")
# benches facing the pond
for bx in (-4, 3):
    put(bx, 1, 13, stairs("spruce", "north"))
    put(bx + 1, 1, 13, stairs("spruce", "north"))
    put(bx - 1, 1, 13, "spruce_trapdoor[facing=west,half=bottom,open=true,powered=false,waterlogged=false]")
    put(bx + 2, 1, 13, "spruce_trapdoor[facing=east,half=bottom,open=true,powered=false,waterlogged=false]")
lamp(-7, 12)
lamp(7, 12)

# plaza ring lamps on the diagonals
for a in (45, 135, 225, 315):
    x, z = round(10.1 * math.cos(math.radians(a))), round(10.1 * math.sin(math.radians(a)))
    lamp(x, z)


# ------------------------------------------------------------------ trees
def cherry(x0, z0, size, seed):
    y0 = G.get((x0, z0), 0) + 1
    put(x0, y0 - 1, z0, "podzol[snowy=false]")
    th = 4 + size
    lean = (h(seed, 1) - 0.5) * 1.2, (h(seed, 2) - 0.5) * 1.2
    top = None
    for i in range(th):
        x = x0 + round(lean[0] * i / th)
        z = z0 + round(lean[1] * i / th)
        put(x, y0 + i, z, "cherry_log[axis=y]")
        top = (x, y0 + i, z)
    blobs = [top]
    nb = 3 + size // 2
    for b in range(nb):
        a = (b / nb) * 2 * math.pi + h(seed, b) * 1.2
        L = 2 + size * 0.6 + h(seed, b, 3) * 1.4
        x, y, z = top
        steps = int(L) + 1
        for s in range(1, steps + 1):
            px = top[0] + round(math.cos(a) * s * L / steps)
            pz = top[2] + round(math.sin(a) * s * L / steps)
            py = top[1] + (1 if s >= steps // 2 else 0) + (1 if s == steps and size > 1 else 0)
            put(px, py, pz, "cherry_wood[axis=y]")
            x, y, z = px, py, pz
        blobs.append((x, y, z))
    for (bx, by, bz) in blobs:
        rx = 2.6 + size * 0.45
        ry = 1.7 + size * 0.2
        for x in range(-4, 5):
            for y in range(-2, 4):
                for z in range(-4, 5):
                    e = (x / rx) ** 2 + ((y - 0.6) / ry) ** 2 + (z / rx) ** 2
                    if e <= 1 and h(bx + x, by + y, bz + z, seed) < (0.97 if e < 0.6 else 0.72):
                        put(bx + x, by + y, bz + z, LEAVES, force=False)
    # drooping leaves under the canopy edge
    for (bx, by, bz) in blobs:
        for x in range(-4, 5):
            for z in range(-4, 5):
                if h(bx + x, bz + z, seed, 9) < 0.09:
                    for y in range(by + 3, by - 4, -1):
                        if (bx + x, y, bz + z) in B and "cherry_leaves" in B[(bx + x, y, bz + z)]:
                            for k in range(1, 1 + int(h(x, z, seed) * 3) + 1):
                                if free(bx + x, y - k, bz + z):
                                    put(bx + x, y - k, bz + z, LEAVES)
                            break
    # petals on the ground
    for x in range(-5, 6):
        for z in range(-5, 6):
            gx, gz = x0 + x, z0 + z
            if (gx, gz) not in G or math.hypot(x, z) > 5.2 or h(gx, gz, seed, 4) > 0.5:
                continue
            gy = G[(gx, gz)]
            if get(gx, gy, gz) in ("grass_block", "moss_block", "podzol") and free(gx, gy + 1, gz):
                f = ["north", "east", "south", "west"][int(h(gx, gz, 5) * 4)]
                put(gx, gy + 1, gz, f"pink_petals[facing={f},flower_amount={1 + int(h(gx, gz, 6) * 4)}]")


cherry(-17, -13, 3, 101)
cherry(17, -14, 2, 202)
cherry(-17, 12, 2, 303)
cherry(17, 15, 3, 404)
cherry(-9, 24, 0, 505)


def azalea_tree(x0, z0, seed):
    y0 = G.get((x0, z0), 0) + 1
    put(x0, y0 - 1, z0, "rooted_dirt")
    for i in range(3):
        put(x0, y0 + i, z0, "oak_log[axis=y]")
    for x in range(-2, 3):
        for y in range(2, 5):
            for z in range(-2, 3):
                if x * x + (y - 3) ** 2 * 1.6 + z * z <= 5.2 and h(x0 + x, y, z0 + z, seed) < 0.9:
                    put(x0 + x, y0 + y, z0 + z, AZ_LEAVES if h(x, y, z, seed) < 0.4 else "azalea_leaves[distance=1,persistent=true,waterlogged=false]", force=False)


azalea_tree(-23, -2, 1)
azalea_tree(22, -20, 2)
azalea_tree(8, 23, 3)

# boulders
for (x0, z0) in ((-21, 8), (22, 6), (-6, -25), (11, -23), (-22, -16)):
    if (x0, z0) not in G:
        continue
    gy = G[(x0, z0)]
    for x in range(-1, 2):
        for z in range(-1, 2):
            for y in range(1, 3):
                if x * x + z * z + y * y * 0.9 <= 2.2 and h(x0 + x, y, z0 + z, 3) < 0.85:
                    put(x0 + x, gy + y, z0 + z, "mossy_cobblestone" if h(x, y, z, x0) < 0.4 else "andesite")
            if free(x0 + x, gy + 3, z0 + z) and (x0 + x, gy + 2, z0 + z) in B and h(x, z, 9) < 0.5:
                put(x0 + x, gy + 3, z0 + z, "moss_carpet")

# ------------------------------------------------------------------ floating islets


def islet(cx, cy, cz, r, seed, top="grass"):
    for x in range(-r - 2, r + 3):
        for z in range(-r - 2, r + 3):
            d = math.hypot(x, z)
            rr = r + (vn(x + seed, z, 2, seed) - 0.5) * 2
            if d > rr:
                continue
            dep = int(1 + (r * 1.4) * (1 - d / rr) ** 1.2 + h(x, z, seed) * 2)
            for y in range(-dep, 1):
                if y == 0:
                    b = GRASS if top == "grass" else "stone"
                elif y >= -2 and top == "grass":
                    b = "dirt"
                else:
                    n = h(cx + x, y, cz + z, seed)
                    b = ore(n * 1e6, False) if n < 0.08 else ("andesite" if n > 0.7 else "stone")
                put(cx + x, cy + y, cz + z, b)
            if h(x, z, seed, 2) < 0.18:
                for i in range(2 + int(h(x, z, seed) * 3)):
                    put(cx + x, cy - dep - 1 - i, cz + z, "cave_vines_plant[berries=true]" if i < 1 + int(h(x, z, seed) * 3) else "cave_vines[age=25,berries=true]")
            elif h(x, z, seed, 3) < 0.15:
                put(cx + x, cy - dep - 1, cz + z, "hanging_roots[waterlogged=false]")


# ore islet: the top is a mosaic of exposed ores (the generator ores)
islet(-33, -5, -20, 5, 11, top="stone")
for x in range(-4, 5):
    for z in range(-4, 5):
        if (-33 + x, -5, -20 + z) in B and h(x, z, 12) < 0.3:
            put(-33 + x, -5, -20 + z, ["coal_ore", "iron_ore", "gold_ore", "diamond_ore", "emerald_ore", "lapis_ore"][int(h(x, z, 13) * 6)])
for (x, z) in ((-32, -19), (-34, -21)):
    put(x, -4, z, "amethyst_cluster[facing=up,waterlogged=false]")
# cherry islet
islet(34, 4, -14, 5, 22)
G[(34, -14)] = 3
cherry(34, -14, 0, 606)
del G[(34, -14)]
# cobble generator islet: water | cobble | lava in a stone trough (decor, physics off)
islet(30, -7, 25, 4, 33, top="stone")
for x in range(27, 34):
    put(x, -6, 24, "stone_bricks")
    put(x, -6, 26, "stone_bricks")
put(27, -6, 25, "stone_bricks")
put(33, -6, 25, "stone_bricks")
put(28, -6, 25, "water")
put(29, -6, 25, "water")
put(30, -6, 25, "cobblestone")
put(31, -6, 25, "lava")
put(32, -6, 25, "lava")
# azalea islet
islet(-30, 5, 23, 4, 44)
G[(-30, 23)] = 9
azalea_tree(-30, 23, 9)
del G[(-30, 23)]

# ------------------------------------------------------------------ flora on open grass
FLOWERS = ["allium", "azure_bluet", "oxeye_daisy", "cornflower", "lily_of_the_valley", "poppy"]
for (x, z), g in G.items():
    if dist.get((x, z), 9) < 1 or get(x, g, z) not in ("grass_block", "moss_block", "rooted_dirt"):
        continue
    if not free(x, g + 1, z):
        continue
    f = h(x, z, 77)
    patch = vn(x, z, 4, 78)
    if f < 0.16 + patch * 0.12:
        put(x, g + 1, z, "short_grass")
    elif f < 0.31 and patch > 0.55 and free(x, g + 2, z):
        put(x, g + 1, z, "tall_grass[half=lower]")
        put(x, g + 2, z, "tall_grass[half=upper]")
    elif f < 0.34:
        put(x, g + 1, z, "fern")
    elif f < 0.39 and patch > 0.35:
        put(x, g + 1, z, FLOWERS[int(vn(x, z, 6, 79) * len(FLOWERS)) % len(FLOWERS)])
    elif f < 0.405:
        put(x, g + 1, z, "azalea" if h(x, z, 80) < 0.6 else "flowering_azalea")

# ------------------------------------------------------------------ invisible light
for x in range(-N, N + 1, 6):
    for z in range(-N, N + 1, 6):
        if (x, z) in G:
            y = G[(x, z)] + 3
            if free(x, y, z):
                put(x, y, z, "light[level=12,waterlogged=false]")

# ------------------------------------------------------------------ write
palette, idx, lines = [], {}, []
for (x, y, z), b in sorted(B.items(), key=lambda kv: (kv[0][1], kv[0][0], kv[0][2])):
    if b not in idx:
        idx[b] = len(palette)
        palette.append(b)
    lines.append(f"{x} {y} {z} {idx[b]}")
with gzip.open(OUT, "wt") as f:
    f.write("|".join(palette) + "\n" + "\n".join(lines) + "\n")
print(f"skyblock: {len(lines)} blocks, {len(palette)} block types -> {os.path.getsize(OUT) // 1024} KB")

if "--preview" in sys.argv:
    sys.path.insert(0, os.path.dirname(__file__))
    from isorender import render
    d = sys.argv[sys.argv.index("--preview") + 1]
    render(B, os.path.join(d, "sky_se.png"), view="se")
    render(B, os.path.join(d, "sky_sw.png"), view="sw")
    render(B, os.path.join(d, "sky_nw.png"), view="nw")
