"""RelapsedMC hub spawn generator, v3 ("citadel").

Writes plugins/lag-core/src/main/resources/hub-spawn.txt.gz: palette line, then "x y z idx" per block.
Coordinates are relative to the spawn point floor (0,0,0) = top of the dais over the beacon; the hub places it
at y=100. North = -Z. (v2, the simple island, is genspawn_v2.py.)

  centre      raised dais (beacon under purple glass = spawn), amethyst ring, stairs all round
  r=15        colonnade: 12 columns, arch ring, hanging soul lanterns
  above       giant floating amethyst/glass crystal, beacon beam runs through it, orbiting shards
  plaza       patterned stone, 4 lit avenues with launch pads
  garden      organic grass/moss terrain with hills, cherry trees, azalea bushes, flowers
  north z=-38 FACTIONS gateway (blackstone / crimson / shroomlight / TNT)
  south z=+38 SMP gateway (mossy stone / spruce / azalea / glow berries)
  west/east   domed glass pavilions with crystal stands (holograms by the plugin)
  edges       waterfalls; underside stalactites, hanging roots, chains + lanterns
  around      floating islands with trees; invisible light blocks for the night glow
Run: python3 tools/hub/genspawn.py [--preview out.png]
"""
import gzip, math, os, sys

OUT = os.path.join(os.path.dirname(__file__), "..", "..", "plugins", "lag-core", "src", "main", "resources", "hub-spawn.txt.gz")
R = 46            # island radius
PLAZA = 22        # paved plaza radius
FLOOR = -1        # plaza / garden floor level (dais top is 0)
blocks = {}


def put(x, y, z, b, force=True):
    if not force and (x, y, z) in blocks:
        return
    blocks[(x, y, z)] = b if b.startswith("minecraft:") else "minecraft:" + b


def get(x, y, z):
    return blocks.get((x, y, z))


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
    return r + (vnoise(ang * 9 + seed, 3, 1.7, seed + 11) - 0.5) * 7 + (vnoise(ang * 25 + seed, 9, 1.3, seed + 3) - 0.5) * 2.5


GRAD = ["light_blue_concrete", "cyan_concrete", "blue_concrete", "purple_concrete", "purpur_block", "magenta_concrete"]


def grad_at(x, z):
    t = (math.atan2(z, x) / math.pi + 1) / 2
    t = abs(t * 2 - 1)
    return GRAD[min(len(GRAD) - 1, int(t * len(GRAD)))]


def stairs(x, y, z, mat, facing, half="bottom"):
    put(x, y, z, f"{mat}_stairs[facing={facing},half={half},shape=straight,waterlogged=false]")


def facing_in(x, z):
    """Stair facing toward the centre (you walk up them toward the centre)."""
    if abs(x) > abs(z):
        return "west" if x > 0 else "east"
    return "north" if z > 0 else "south"


WALL_POST = "polished_blackstone_wall[east=none,north=none,south=none,up=true,waterlogged=false,west=none]"


# ======================================================================= island body
def island(cx, cz, r, depth, top_y, seed, main=False):
    for x in range(-r - 6, r + 7):
        for z in range(-r - 6, r + 7):
            er = edge_r(x, z, r, seed)
            d = math.hypot(x, z)
            if d > er:
                continue
            k = 1 - (d / er) ** 1.5
            dep = int(2 + depth * k ** 0.85 + (vnoise(x * 2 + seed, z * 2, 5, seed) - 0.5) * 6)
            for dy in range(1, dep + 1):
                y = top_y - dy
                n = h(cx + x, y, cz + z, seed)
                if dy <= 2:
                    b = "dirt" if not main or d > PLAZA else "deepslate"
                elif dy <= 4 and d > er - 3:
                    b = "stone"
                elif h(cx + x, y // 3, cz + z, 99 + seed) < 0.05 and dy > 4:
                    b = "amethyst_block"
                elif n < 0.34:
                    b = "deepslate"
                elif n < 0.54:
                    b = "tuff"
                elif n < 0.72:
                    b = "stone"
                elif n < 0.86:
                    b = "cobbled_deepslate"
                else:
                    b = "calcite"
                put(cx + x, y, cz + z, b)
            bottom = top_y - dep - 1
            r_ = h(x, z, seed, 5)
            if dep > 8 and r_ < 0.045:
                ln = 3 + int(h(x, z, seed, 6) * 8)
                for i in range(ln):
                    if i < ln - 2:
                        b = "dripstone_block"
                    elif i == ln - 1:
                        b = "pointed_dripstone[thickness=tip,vertical_direction=down,waterlogged=false]"
                    else:
                        b = "pointed_dripstone[thickness=frustum,vertical_direction=down,waterlogged=false]"
                    put(cx + x, bottom - i, cz + z, b)
            elif r_ < 0.12:
                put(cx + x, bottom, cz + z, "hanging_roots[waterlogged=false]")
            elif r_ < 0.16:
                put(cx + x, bottom, cz + z, "amethyst_cluster[facing=down,waterlogged=false]")
            elif main and r_ < 0.168 and dep > 5:
                ln = 2 + int(h(x, z, 9) * 5)
                for i in range(ln):
                    put(cx + x, bottom - i, cz + z, "iron_chain[axis=y,waterlogged=false]")
                put(cx + x, bottom - ln, cz + z, "soul_lantern[hanging=true,waterlogged=false]")


island(0, 0, R, 34, FLOOR, 0, main=True)

# ======================================================================= surface
for x in range(-R - 6, R + 7):
    for z in range(-R - 6, R + 7):
        d = math.hypot(x, z)
        er = edge_r(x, z, R)
        if d > er:
            continue
        avenue = False
        if False:
            ring = int(d) % 4
            if PLAZA - 1.5 < d <= PLAZA:
                b = "polished_blackstone_bricks"
            elif PLAZA - 3 < d <= PLAZA - 1.5:
                b = grad_at(x, z)
            elif avenue:
                b = "polished_deepslate"
            elif ring == 0:
                b = "chiseled_deepslate" if (x * 3 + z) % 5 == 0 else "deepslate_tiles"
            else:
                chk = (x + z) % 2 == 0
                b = "polished_blackstone_bricks" if chk else "deepslate_bricks"
                if h(x, z, 5) < 0.07:
                    b = "cracked_polished_blackstone_bricks" if chk else "cracked_deepslate_bricks"
            put(x, FLOOR, z, b)
        else:
            hill = int(max(0, (vnoise(x, z, 9, 21) - 0.45) * 7))
            if avenue:
                hill = 0
            top = FLOOR + hill
            for y in range(FLOOR, top):
                put(x, y, z, "dirt")
            n = h(x, z, 31)
            if avenue:
                put(x, FLOOR, z, "dirt_path" if (abs(x) == 2 or abs(z) == 2) else "polished_deepslate")
                continue
            surf = "grass_block[snowy=false]" if n < 0.62 else "moss_block" if n < 0.82 else "podzol[snowy=false]" if n < 0.92 else "coarse_dirt"
            if d > er - 1.5:
                surf = "mossy_cobblestone" if n < 0.5 else "stone"
            put(x, top, z, surf)
            f = h(x, z, 77)
            if surf.startswith(("grass", "moss")) and d < er - 2:
                if f < 0.10:
                    put(x, top + 1, z, "short_grass")
                elif f < 0.13:
                    put(x, top + 1, z, f"pink_petals[facing=north,flower_amount={1 + int(h(x, z, 8) * 4)}]")
                elif f < 0.145:
                    put(x, top + 1, z, "allium" if h(x, z, 9) < 0.5 else "blue_orchid")
                elif f < 0.152:
                    put(x, top + 1, z, "lily_of_the_valley")
                elif f < 0.158:
                    put(x, top + 1, z, "small_amethyst_bud[facing=up,waterlogged=false]")
                elif f < 0.165:
                    put(x, top + 1, z, "moss_carpet")

# ======================================================================= layout (island coords; spawn is moved to (0,0,0) on write)
SPAWN_Z = 32          # arrival platform, facing north
PLAZA_C = (0, -12)    # choice plaza centre (beacon + crystal)
PLAZA_R = 13
GATE_Z = -27          # portal plane of the three gates
GATE_X = {"smp": -21, "factions": 0, "soon": 21}
SIDE_Z = 10           # side paths: west -> Discord pavilion, east -> parkour


def paved(x, z):
    """Areas kept clear of trees/terrain bumps."""
    if abs(x) <= 8 and -4 <= z <= SPAWN_Z + 7:
        return True
    if math.hypot(x - PLAZA_C[0], z - PLAZA_C[1]) <= PLAZA_R + 3:
        return True
    if z <= GATE_Z + 7 and abs(x) <= 33:
        return True
    if abs(z - SIDE_Z) <= 4 and abs(x) <= 42:
        return True
    if math.hypot(x + 30, z - SIDE_Z) <= 9:
        return True
    return False


def floor_tile(x, z, d):
    chk = (x + z) % 2 == 0
    b = "polished_blackstone_bricks" if chk else "deepslate_bricks"
    if h(x, z, 5) < 0.07:
        b = "cracked_polished_blackstone_bricks" if chk else "cracked_deepslate_bricks"
    return b


# clear terrain bumps/flora on paved areas, then pave
for x in range(-R - 6, R + 7):
    for z in range(-R - 6, R + 7):
        if not paved(x, z) or math.hypot(x, z) > edge_r(x, z, R) - 1:
            continue
        for y in range(FLOOR + 1, FLOOR + 9):
            blocks.pop((x, y, z), None)
        put(x, FLOOR, z, floor_tile(x, z, 0))

# --- arrival platform (spawn)
for x in range(-7, 8):
    for z in range(SPAWN_Z - 7, SPAWN_Z + 8):
        d = math.hypot(x, z - SPAWN_Z)
        if d <= 5.5:
            put(x, 0, z, "amethyst_block" if 3.5 < d <= 4.5 else "polished_deepslate" if d > 4.5 else "deepslate_tiles")
            put(x, FLOOR, z, "deepslate")
        elif d <= 6.6:
            stairs(x, 0, z, "polished_deepslate", facing_in(x, z - SPAWN_Z))
put(0, 0, SPAWN_Z, "pearlescent_froglight")
for x, z in ((4, SPAWN_Z + 4), (-4, SPAWN_Z + 4)):
    for y in range(1, 5):
        put(x, y, z, WALL_POST)
    put(x, 5, z, "pearlescent_froglight")
    put(x, 6, z, "end_rod[facing=up]")

# --- grand avenue north from the spawn to the plaza
AV_N = PLAZA_C[1] + PLAZA_R - 2
for z in range(AV_N, SPAWN_Z - 5):
    for x in range(-4, 5):
        if abs(x) == 4:
            b = "polished_blackstone_bricks"
        elif x == 0:
            b = "sea_lantern" if z % 4 == 0 else "pearlescent_froglight" if z % 4 == 2 else grad_at(1, -1 + (z % 7) * 0.3)
        elif abs(x) == 1:
            b = "amethyst_block" if z % 4 == 0 else "polished_deepslate"
        else:
            b = "polished_deepslate"
        put(x, FLOOR, z, b)
for zc in range(SPAWN_Z - 9, AV_N + 2, -7):
    for sx in (-7, 7):
        for dx in (0, 1 if sx > 0 else -1):
            for y in range(FLOOR + 1, FLOOR + 9):
                put(sx + dx, y, zc, "chiseled_polished_blackstone" if y in (FLOOR + 1, FLOOR + 8) else ("pearlescent_froglight" if y == FLOOR + 5 else "polished_deepslate"))
    # arch across the avenue
    for x in range(-8, 9):
        put(x, FLOOR + 9, zc, "polished_blackstone_bricks" if abs(x) >= 6 else "polished_blackstone_brick_slab[type=bottom,waterlogged=false]")
    for x in (-3, 3):
        put(x, FLOOR + 8, zc, "iron_chain[axis=y,waterlogged=false]")
        put(x, FLOOR + 7, zc, "soul_lantern[hanging=true,waterlogged=false]")
    put(0, FLOOR + 10, zc, "amethyst_block")
    put(0, FLOOR + 11, zc, "amethyst_cluster[facing=up,waterlogged=false]")

# --- choice plaza: beacon + crystal
cx0, cz0 = PLAZA_C
for x in range(-PLAZA_R, PLAZA_R + 1):
    for z in range(-PLAZA_R, PLAZA_R + 1):
        d = math.hypot(x, z)
        if d > PLAZA_R:
            continue
        if d > PLAZA_R - 1.3:
            b = "polished_blackstone_bricks"
        elif d > PLAZA_R - 2.8:
            b = grad_at(x, z)
        elif int(d) % 4 == 0:
            b = "chiseled_deepslate" if (x * 3 + z) % 5 == 0 else "deepslate_tiles"
        else:
            b = floor_tile(x, z, d)
        put(cx0 + x, FLOOR, cz0 + z, b)
for x in range(-1, 2):
    for z in range(-1, 2):
        put(cx0 + x, FLOOR - 2, cz0 + z, "iron_block")
        put(cx0 + x, FLOOR - 1, cz0 + z, "deepslate")
put(cx0, FLOOR - 1, cz0, "beacon")
put(cx0, FLOOR, cz0, "purple_stained_glass")
for d in range(2, 4):
    for x, z in ((d, 0), (-d, 0), (0, d), (0, -d)):
        put(cx0 + x, FLOOR, cz0 + z, "amethyst_block")
CY0 = 16
for y in range(0, 17):
    rr = (8 - abs(y - 7)) * 0.75 if y <= 7 else (8 - (y - 7) * 0.85) * 0.75
    rr = max(0, rr)
    for x in range(-7, 8):
        for z in range(-7, 8):
            d = abs(x) + abs(z)
            if d <= rr:
                if x == 0 and z == 0:
                    b = "magenta_stained_glass"
                elif d > rr - 1.2:
                    b = "amethyst_block" if (x + y + z) % 3 else "purple_stained_glass"
                else:
                    b = "purple_stained_glass"
                put(cx0 + x, CY0 + y, cz0 + z, b)
for (dx, dz), f in (((1, 0), "east"), ((-1, 0), "west"), ((0, 1), "south"), ((0, -1), "north")):
    put(cx0 + dx * 2, CY0 - 1, cz0 + dz * 2, "amethyst_cluster[facing=down,waterlogged=false]")
    put(cx0 + dx * 6, CY0 + 7, cz0 + dz * 6, f"end_rod[facing={f}]")
put(cx0, CY0 + 17, cz0, "end_rod[facing=up]")
for i in range(8):
    a = i * math.pi / 4
    x, z = round(10 * math.cos(a)), round(10 * math.sin(a))
    y = CY0 + 3 + (i % 2) * 4
    put(cx0 + x, y, cz0 + z, "amethyst_block")
    put(cx0 + x, y + 1, cz0 + z, "amethyst_cluster[facing=up,waterlogged=false]")
    put(cx0 + x, y - 1, cz0 + z, "amethyst_cluster[facing=down,waterlogged=false]")
# lamp ring around the plaza
for i in range(10):
    a = i * math.pi / 5 + math.pi / 10
    x, z = round(cx0 + (PLAZA_R + 1.5) * math.cos(a)), round(cz0 + (PLAZA_R + 1.5) * math.sin(a))
    if z < cz0 - 6 or abs(x) <= 5:
        continue
    for y in range(FLOOR + 1, FLOOR + 4):
        put(x, y, z, WALL_POST)
    put(x, FLOOR + 4, z, "pearlescent_froglight" if i % 2 else "sea_lantern")
    put(x, FLOOR + 5, z, "end_rod[facing=up]")


# --- the three gates
def gateway(xc, zc, theme):
    s = -1
    if theme == "factions":
        brick, stair_mat, accent, glow, trim, roof = "polished_blackstone_bricks", "polished_blackstone_brick", "red_nether_bricks", "shroomlight", "crimson_hyphae[axis=y]", "crimson_planks"
    elif theme == "smp":
        brick, stair_mat, accent, glow, trim, roof = "mossy_stone_bricks", "mossy_stone_brick", "moss_block", "verdant_froglight", "spruce_log[axis=y]", "spruce_planks"
    else:
        brick, stair_mat, accent, glow, trim, roof = "end_stone_bricks", "end_stone_brick", "purpur_pillar[axis=y]", "pearlescent_froglight", "purpur_pillar[axis=y]", "purpur_block"

    def P(x, y, z, b):
        put(xc + x, y, z, b)

    for x in range(-9, 10):
        for dz in range(-4, 5):
            P(x, FLOOR + 1, zc + dz, brick)
    for x in range(-3, 4):
        stairs(xc + x, FLOOR + 1, zc - s * 5, stair_mat, "north")
    for x in range(-3, 4):
        for y in range(0, 9):
            yy = FLOOR + 2 + y
            if abs(x) == 3 or y in (0, 8):
                P(x, yy, zc, "obsidian" if theme != "soon" else "crying_obsidian")
            elif theme == "soon":
                P(x, yy, zc, "purple_stained_glass_pane[east=true,north=false,south=false,waterlogged=false,west=true]")
            else:
                P(x, yy, zc, "nether_portal[axis=x]")
    for x in range(-6, 7):
        for y in range(0, 14):
            yy = FLOOR + 2 + y
            if abs(x) <= 3 and y <= 8:
                continue
            if abs(x) >= 4 and y <= 11:
                P(x, yy, zc, brick if (x + y) % 4 else accent)
                P(x, yy, zc + s, brick)
            elif y in (9, 10) and abs(x) <= 5:
                P(x, yy, zc, accent if y == 9 else brick)
            elif y == 11 and abs(x) <= 5:
                P(x, yy, zc, "chiseled_polished_blackstone" if theme == "factions" else "chiseled_stone_bricks" if theme == "smp" else "purpur_block")
            elif y == 12 and abs(x) <= 3:
                P(x, yy, zc, glow if x == 0 else brick)
            elif y == 13 and abs(x) <= 1:
                P(x, yy, zc, roof)
    for y in range(0, 10):
        P(-4, FLOOR + 2 + y, zc, trim)
        P(4, FLOOR + 2 + y, zc, trim)
    tall = 16 if theme == "factions" else 13
    for sx in (-8, 8):
        for x in range(sx - 1, sx + 2):
            for dz in (-1, 0, 1):
                for y in range(0, tall):
                    edge = abs(x - sx) == 1 or abs(dz) == 1
                    if edge:
                        P(x, FLOOR + 2 + y, zc + dz, brick if (y % 5) else accent)
                    elif y % 5 == 4:
                        P(x, FLOOR + 2 + y, zc + dz, glow)
        for i, y in enumerate(range(tall, tall + 4)):
            w = 1 if i < 2 else 0
            for x in range(sx - w, sx + w + 1):
                for dz in range(-w, w + 1):
                    P(x, FLOOR + 2 + y, zc + dz, roof if i < 3 else "end_rod[facing=up]")
        ix = sx - (1 if sx > 0 else -1) * 2
        P(ix, FLOOR + 12, zc - s, "iron_chain[axis=y,waterlogged=false]")
        P(ix, FLOOR + 11, zc - s, ("soul_lantern" if theme != "smp" else "lantern") + "[hanging=true,waterlogged=false]")
    if theme == "factions":
        for x in (-6, 6):
            P(x, FLOOR + 2, zc - s * 3, "tnt[unstable=false]")
            P(x, FLOOR + 3, zc - s * 3, "tnt[unstable=false]")
            P(x, FLOOR + 4, zc - s * 3, "redstone_torch[lit=true]")
    elif theme == "smp":
        for x in range(-9, 10):
            if h(x, zc, 4) < 0.5:
                P(x, FLOOR + 2, zc + s * 4, "flowering_azalea_leaves[distance=1,persistent=true,waterlogged=false]" if h(x, 1) < 0.5 else "azalea_leaves[distance=1,persistent=true,waterlogged=false]")
        for x in (-5, 5):
            for i in range(2):
                P(x, FLOOR + 12 - i, zc - s, "cave_vines_plant[berries=true]" if i == 0 else "cave_vines[age=25,berries=true]")
    else:
        for x in (-6, 6):
            P(x, FLOOR + 2, zc - s * 3, "end_rod[facing=up]")
            P(x, FLOOR + 3, zc - s * 3, "end_rod[facing=up]")


for theme, xc in GATE_X.items():
    gateway(xc, GATE_Z, theme)


# --- side paths: west to the Discord pavilion, east to the parkour arch
for x in range(-36, 41):
    if abs(x) <= 4:
        continue
    for dz in range(-2, 3):
        z = SIDE_Z + dz
        if math.hypot(x, z) > edge_r(x, z, R) - 1:
            continue
        put(x, FLOOR, z, "polished_blackstone_bricks" if abs(dz) == 2 else "polished_deepslate" if dz else grad_at(x, -1))
    if x % 6 == 0 and abs(x) >= 8:
        for side in (-3, 3):
            put(x, FLOOR + 1, SIDE_Z + side, WALL_POST)
            put(x, FLOOR + 2, SIDE_Z + side, "sea_lantern" if x % 12 else "pearlescent_froglight")


def pavilion(xc, zc, theme_glow):
    for x in range(-7, 8):
        for z in range(-7, 8):
            d = math.hypot(x, z)
            if d <= 6.5:
                put(xc + x, FLOOR + 1, zc + z, "polished_deepslate" if d < 5 else "polished_blackstone_bricks")
            elif d <= 7.4:
                stairs(xc + x, FLOOR + 1, zc + z, "polished_blackstone_brick", facing_in(x, z))
    for i in range(8):
        a = i * math.pi / 4
        x, z = round(5.5 * math.cos(a)), round(5.5 * math.sin(a))
        for y in range(FLOOR + 2, FLOOR + 8):
            put(xc + x, y, zc + z, WALL_POST if y < FLOOR + 7 else "chiseled_polished_blackstone")
    for x in range(-7, 8):
        for z in range(-7, 8):
            for y in range(0, 6):
                d = math.sqrt(x * x + z * z + (y * 1.4) ** 2)
                if 5.6 <= d <= 6.7:
                    put(xc + x, FLOOR + 8 + y, zc + z, "purple_stained_glass" if (x + z + y) % 3 else "amethyst_block")
    put(xc, FLOOR + 15, zc, "end_rod[facing=up]")
    put(xc, FLOOR + 13, zc, "iron_chain[axis=y,waterlogged=false]")
    put(xc, FLOOR + 12, zc, "soul_lantern[hanging=true,waterlogged=false]")
    for y in range(FLOOR + 2, FLOOR + 5):
        put(xc, y, zc, "amethyst_block")
    put(xc, FLOOR + 5, zc, "budding_amethyst")
    put(xc, FLOOR + 6, zc, "amethyst_cluster[facing=up,waterlogged=false]")
    for dx, dz, f in ((1, 0, "east"), (-1, 0, "west"), (0, 1, "south"), (0, -1, "north")):
        put(xc + dx, FLOOR + 3, zc + dz, f"large_amethyst_bud[facing={f},waterlogged=false]")
        put(xc + dx * 2, FLOOR + 2, zc + dz * 2, theme_glow)


pavilion(-30, SIDE_Z, "pearlescent_froglight")


# ======================================================================= trees
def cherry(x0, z0, y0, size, seed):
    hgt = 4 + size
    for y in range(hgt):
        put(x0, y0 + y, z0, "cherry_log[axis=y]")
    for b in range(3):
        a = h(seed, b) * math.pi * 2
        bx, bz = round(math.cos(a) * 2), round(math.sin(a) * 2)
        put(x0 + bx // 2, y0 + hgt - 1, z0 + bz // 2, "cherry_wood[axis=y]")
        cx, cy, cz = x0 + bx, y0 + hgt, z0 + bz
        rr = 2.2 + size * 0.5
        for x in range(-4, 5):
            for y in range(-2, 3):
                for z in range(-4, 5):
                    if (x * x + (y * 1.7) ** 2 + z * z) <= rr * rr and h(cx + x, cy + y, cz + z, seed) < 0.86:
                        put(cx + x, cy + y, cz + z, "cherry_leaves[distance=1,persistent=true,waterlogged=false]", force=False)


def bush(x0, z0, y0):
    for x in range(-1, 2):
        for z in range(-1, 2):
            for y in range(0, 2):
                if abs(x) + abs(z) + y <= 2:
                    put(x0 + x, y0 + y, z0 + z, "flowering_azalea_leaves[distance=1,persistent=true,waterlogged=false]" if h(x0 + x, y, z0 + z) < 0.45 else "azalea_leaves[distance=1,persistent=true,waterlogged=false]", force=False)


def surface_y(x, z):
    for y in range(FLOOR + 8, FLOOR - 1, -1):
        b = get(x, y, z)
        if b and any(k in b for k in ("grass_block", "moss_block", "podzol", "coarse_dirt")):
            return y
    return None


for i in range(110):
    a = h(i, 1) * math.pi * 2
    d = 8 + h(i, 2) * (R - 14)
    x, z = round(d * math.cos(a)), round(d * math.sin(a))
    if paved(x, z) or paved(x + 3, z) or paved(x - 3, z) or paved(x, z + 3) or paved(x, z - 3):
        continue
    sy = surface_y(x, z)
    if sy is None:
        continue
    if i % 3 == 0:
        cherry(x, z, sy + 1, int(h(i, 3) * 3), i)
    else:
        bush(x, z, sy + 1)

# ======================================================================= waterfalls off the rim
for a_deg in (55, 125, 235, 305):
    a = math.radians(a_deg)
    rr = R + 6
    while rr > R - 10:
        x, z = round(rr * math.cos(a)), round(rr * math.sin(a))
        if surface_y(x, z) is not None:
            break
        rr -= 1
    for t in range(0, 6):
        xx, zz = round((rr - t) * math.cos(a)), round((rr - t) * math.sin(a))
        top = surface_y(xx, zz)
        if top is not None:
            put(xx, top, zz, "water")
    ox, oz = round((rr + 1) * math.cos(a)), round((rr + 1) * math.sin(a))
    sy = surface_y(round(rr * math.cos(a)), round(rr * math.sin(a))) or FLOOR
    for y in range(sy, sy - 45, -1):
        if get(ox, y, oz) is None:
            put(ox, y, oz, "water[level=8]")

# ======================================================================= parkour (helix around the island)
PK_R, PK_A0, PK_SWEEP, PK_N, PK_RISE = 57, 14, 300, 80, 30
PK_MATS = ["purpur_block", "amethyst_block", "light_blue_stained_glass", "sea_lantern", "purple_stained_glass",
           "polished_blackstone_bricks", "pearlescent_froglight", "cyan_concrete", "magenta_stained_glass"]
parkour = {"start": None, "checkpoints": [], "finish": None, "entrance": None}


def pk_platform(x, y, z, top, plate, rr=1):
    for dx in range(-rr, rr + 1):
        for dz in range(-rr, rr + 1):
            put(x + dx, y, z + dz, top if (dx or dz) else "pearlescent_froglight")
    put(x, y + 1, z, plate)
    for dx, dz in ((-rr, -rr), (-rr, rr), (rr, -rr), (rr, rr)):
        put(x + dx, y + 1, z + dz, "end_rod[facing=up]")


# bridge from the garden out to the start pad
a0 = math.radians(PK_A0)
for t in range(36, PK_R - 1):
    for w in (-1, 0, 1):
        x = round(t * math.cos(a0) - w * math.sin(a0))
        z = round(t * math.sin(a0) + w * math.cos(a0))
        sy = surface_y(x, z)
        if sy is not None and sy > FLOOR:
            continue
        put(x, FLOOR, z, "polished_blackstone_bricks" if w else "polished_deepslate")
    if t % 4 == 0:
        for w in (-2, 2):
            x = round(t * math.cos(a0) - w * math.sin(a0))
            z = round(t * math.sin(a0) + w * math.cos(a0))
            put(x, FLOOR + 1, z, WALL_POST)
            put(x, FLOOR + 2, z, "sea_lantern" if t % 8 else "pearlescent_froglight")
# entrance arch at the island edge
ER = 40
ex, ez = round(ER * math.cos(a0)), round(ER * math.sin(a0))
for w in (-3, 3):
    px, pz = round(ER * math.cos(a0) - w * math.sin(a0)), round(ER * math.sin(a0) + w * math.cos(a0))
    for y in range(FLOOR + 1, FLOOR + 9):
        put(px, y, pz, "polished_blackstone_bricks" if y % 3 else "chiseled_polished_blackstone")
    put(px, FLOOR + 5, pz, "pearlescent_froglight")
    put(px, FLOOR + 9, pz, "amethyst_block")
    put(px, FLOOR + 10, pz, "end_rod[facing=up]")
for w in range(-3, 4):
    px, pz = round(ER * math.cos(a0) - w * math.sin(a0)), round(ER * math.sin(a0) + w * math.cos(a0))
    put(px, FLOOR + 8, pz, GRAD[(w + 3) % len(GRAD)])
    if abs(w) <= 2:
        put(px, FLOOR + 9, pz, "polished_blackstone_brick_slab[type=bottom,waterlogged=false]")
    if abs(w) == 1:
        put(px, FLOOR + 7, pz, "iron_chain[axis=y,waterlogged=false]")
        put(px, FLOOR + 6, pz, "soul_lantern[hanging=true,waterlogged=false]")
parkour["entrance"] = [ex, FLOOR + 1, ez]
sx, sz = round(PK_R * math.cos(a0)), round(PK_R * math.sin(a0))
pk_platform(sx, FLOOR, sz, "polished_blackstone_bricks", "light_weighted_pressure_plate[power=0]", rr=2)
parkour["start"] = [sx, FLOOR + 1, sz]

prev = (sx, FLOOR, sz)
pk_path = [prev]
prev_slab = False
for i in range(1, PK_N + 1):
    t = i / PK_N
    a = math.radians(PK_A0 + PK_SWEEP * t)
    rr = PK_R + (h(i, 41) - 0.5) * 2.2
    x, z = round(rr * math.cos(a)), round(rr * math.sin(a))
    # keep every jump makeable: at most +1 up, ~2.5 block gap going up, ~3 block gap flat
    dxz = math.hypot(x - prev[0], z - prev[2])
    if dxz < 3.0 and (x, z) != (prev[0], prev[2]):
        k = 3.2 / max(dxz, 0.1)
        x, z = round(prev[0] + (x - prev[0]) * k), round(prev[2] + (z - prev[2]) * k)
        dxz = math.hypot(x - prev[0], z - prev[2])
    k = 1.0
    while dxz > 4.0 and k > 0.3:
        k -= 0.05
        x, z = round(prev[0] + (x - prev[0]) * k), round(prev[2] + (z - prev[2]) * k)
        dxz = math.hypot(x - prev[0], z - prev[2])
    y = min(FLOOR + round(PK_RISE * t), prev[1] + (0 if prev_slab else 1))
    if y > prev[1] and dxz > 3.3:
        y = prev[1]
    if (x, z) == (prev[0], prev[2]):
        continue
    if i == PK_N:
        for dx in range(-3, 4):
            for dz in range(-3, 4):
                if dx * dx + dz * dz <= 10:
                    put(x + dx, y, z + dz, "polished_deepslate" if dx * dx + dz * dz < 5 else "amethyst_block")
        put(x, y, z, "pearlescent_froglight")
        put(x, y + 1, z, "light_weighted_pressure_plate[power=0]")
        for dx, dz in ((-3, 0), (3, 0), (0, -3), (0, 3)):
            for k in range(1, 4):
                put(x + dx, y + k, z + dz, WALL_POST)
            put(x + dx, y + 4, z + dz, "end_rod[facing=up]")
        parkour["finish"] = [x, y + 1, z]
    elif i % 15 == 0:
        pk_platform(x, y, z, "polished_deepslate", "polished_blackstone_pressure_plate[powered=false]")
        parkour["checkpoints"].append([x, y + 1, z])
    else:
        kind = h(i, 61)
        if i > 8 and kind < 0.14 and y <= prev[1] and dxz <= 3.7:
            # thin post landing (glass pane / iron bars), no rise
            put(x, y, z, "purple_stained_glass_pane[east=false,north=false,south=false,waterlogged=false,west=false]" if i % 2 else "iron_bars[east=false,north=false,south=false,waterlogged=false,west=false]")
            put(x, y - 1, z, "amethyst_block")
        elif i > 8 and kind < 0.24 and y <= prev[1] and i % 15 != 14 and i != PK_N - 1:
            put(x, y, z, "polished_blackstone_brick_slab[type=bottom,waterlogged=false]")
            prev = (x, y, z)
            pk_path.append(prev)
            prev_slab = True
            continue
        elif i > 8 and kind < 0.30 and y <= prev[1] and dxz <= 3.2:
            put(x, y, z, WALL_POST)   # 1.5 tall: lands half a block up
        else:
            put(x, y, z, PK_MATS[i % len(PK_MATS)])
        if i % 9 == 4:
            put(x, y - 1, z, "amethyst_cluster[facing=down,waterlogged=false]")
    prev = (x, y, z)
    pk_path.append(prev)
    prev_slab = False

_j = [(math.hypot(b[0] - a[0], b[2] - a[2]), b[1] - a[1]) for a, b in zip(pk_path, pk_path[1:])]
print(f"parkour jumps: {len(_j)}, longest {max(d for d, _ in _j):.2f}, shortest {min(d for d, _ in _j):.2f}, longest going up {max([d for d, r in _j if r > 0] or [0]):.2f}, max rise {max(r for _, r in _j)}")

import json as _json
_json.dump(parkour, open(os.path.join(os.path.dirname(OUT), "parkour.json"), "w"))
print("parkour:", parkour["start"], len(parkour["checkpoints"]), "checkpoints ->", parkour["finish"])

# ======================================================================= invisible light blocks (night glow)
for x in range(-R, R + 1, 6):
    for z in range(-R, R + 1, 6):
        if math.hypot(x, z) > R - 3:
            continue
        sy = surface_y(x, z)
        y = (sy if sy is not None else FLOOR) + 3
        if get(x, y, z) is None:
            put(x, y, z, "light[level=13,waterlogged=false]")

# ======================================================================= floating islands
for i, (ang, dist, dy, r) in enumerate(((20, 70, 10, 7), (85, 78, -8, 6), (150, 66, 16, 5), (205, 80, 2, 8), (260, 72, -12, 6), (330, 84, 20, 5), (110, 95, 26, 4))):
    cx, cz = round(dist * math.cos(math.radians(ang))), round(dist * math.sin(math.radians(ang)))
    island(cx, cz, r, r * 2 + 6, dy, i * 31 + 5)
    for x in range(-r - 4, r + 5):
        for z in range(-r - 4, r + 5):
            if math.hypot(x, z) <= edge_r(x, z, r, i * 31 + 5):
                put(cx + x, dy, cz + z, "grass_block[snowy=false]" if h(x, z, i) < 0.7 else "moss_block")
                if h(x, z, i, 2) < 0.12:
                    put(cx + x, dy + 1, cz + z, "short_grass")
    if r >= 6:
        cherry(cx, cz, dy + 1, 1, i)
    else:
        put(cx, dy + 1, cz, "amethyst_block")
        put(cx, dy + 2, cz, "amethyst_cluster[facing=up,waterlogged=false]")
    put(cx + 2, dy + 1, cz + 1, "lantern[hanging=false,waterlogged=false]")

# ======================================================================= write
shifted = {(x, y, z - SPAWN_Z): b for (x, y, z), b in blocks.items()}
blocks.clear()
blocks.update(shifted)
for k in ("start", "finish", "entrance"):
    if parkour.get(k):
        parkour[k][2] -= SPAWN_Z
for c in parkour["checkpoints"]:
    c[2] -= SPAWN_Z
_json.dump(parkour, open(os.path.join(os.path.dirname(OUT), "parkour.json"), "w"))
palette, idx, lines = [], {}, []
for (x, y, z), b in sorted(blocks.items(), key=lambda kv: (kv[0][1], kv[0][0], kv[0][2])):
    if b not in idx:
        idx[b] = len(palette)
        palette.append(b)
    lines.append(f"{x} {y} {z} {idx[b]}")
os.makedirs(os.path.dirname(OUT), exist_ok=True)
with gzip.open(OUT, "wt") as f:
    f.write("|".join(palette) + "\n" + "\n".join(lines) + "\n")
xs = [k[0] for k in blocks]; ys = [k[1] for k in blocks]; zs = [k[2] for k in blocks]
print(f"{len(lines)} blocks, {len(palette)} block types, bounds x {min(xs)}..{max(xs)} y {min(ys)}..{max(ys)} z {min(zs)}..{max(zs)} -> {os.path.getsize(OUT)//1024} KB")

if "--preview" in sys.argv:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    COL = [("water", "#3a6ee8"), ("nether_portal", "#7a2ad8"), ("cherry_leaves", "#f4b6d8"), ("cherry", "#5a3040"), ("flowering_azalea", "#8fae3e"),
           ("azalea", "#5f8a2e"), ("grass_block", "#5d9a3a"), ("moss", "#5a7a2e"), ("podzol", "#6a4a28"), ("path", "#9a8050"), ("dirt", "#7a5432"),
           ("light_blue", "#38E0FF"), ("cyan", "#16a0a8"), ("blue_concrete", "#3c44aa"), ("magenta", "#c050c0"), ("purple_stained", "#8a3ad0"),
           ("purple", "#8932b8"), ("purpur", "#a77ba7"), ("amethyst", "#9a5cc6"), ("sea_lantern", "#d6efe9"), ("pearlescent", "#f0d0e8"),
           ("verdant", "#d8f0c0"), ("shroomlight", "#ffa040"), ("crimson", "#8a2030"), ("red_nether", "#5a1010"), ("tnt", "#d03030"),
           ("obsidian", "#1a1426"), ("spruce", "#4a3420"), ("mossy", "#6a7a5a"), ("iron", "#d8d8d8"), ("end_rod", "#ffffff"),
           ("tuff", "#6b6b60"), ("calcite", "#e0e0dc"), ("dripstone", "#8a6a50"), ("blackstone", "#2a2530"), ("deepslate", "#45454f"), ("stone", "#7a7a7a")]

    def col(b):
        b = b.split("[")[0]
        for k, v in COL:
            if k in b:
                return v
        return "#555"
    top, side = {}, {}
    for (x, y, z), b in blocks.items():
        if "light[" in b:
            continue
        if (x, z) not in top or y > top[(x, z)][0]:
            top[(x, z)] = (y, b)
        if (x, y) not in side or z < side[(x, y)][0]:
            side[(x, y)] = (z, b)
    fig, ax = plt.subplots(1, 2, figsize=(20, 10), facecolor="#0b0b14")
    for (x, z), (y, b) in top.items():
        ax[0].add_patch(plt.Rectangle((x, z), 1, 1, color=col(b)))
    ax[0].set_xlim(-110, 110); ax[0].set_ylim(70, -125); ax[0].set_aspect("equal"); ax[0].set_facecolor("#0b0b14"); ax[0].set_title("top (north up)", color="w")
    for (x, y), (z, b) in side.items():
        ax[1].add_patch(plt.Rectangle((x, y), 1, 1, color=col(b)))
    ax[1].set_xlim(-105, 105); ax[1].set_ylim(-80, 45); ax[1].set_aspect("equal"); ax[1].set_facecolor("#0b0b14"); ax[1].set_title("side (looking north)", color="w")
    out = sys.argv[sys.argv.index("--preview") + 1]
    plt.savefig(out, dpi=80, bbox_inches="tight", facecolor="#0b0b14")
    print("preview", out)
