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
SPAWN_Z = 30
# realm portals (round), left to right as you face north from the spawn
PORTALS = {"skyblock": (-34, -6), "factions": (-12, -18), "cannons": (12, -18), "smp": (34, -6)}
RING_R = 6                      # portal ring radius
reserved = set()                # paved cells (trees/flora stay off them)


def clear_above(x, z, up=10):
    for y in range(FLOOR + 1, FLOOR + up):
        blocks.pop((x, y, z), None)


def pave(x, z, b):
    clear_above(x, z)
    put(x, FLOOR, z, b)
    reserved.add((x, z))


THEME = {
    "skyblock": dict(edge="mossy_stone_bricks", fill="moss_block", line="verdant_froglight", alt="moss_block", lamp="verdant_froglight"),
    "factions": dict(edge="red_nether_bricks", fill="polished_blackstone_bricks", line="shroomlight", alt="polished_blackstone", lamp="shroomlight"),
    "cannons": dict(edge="obsidian", fill="polished_deepslate", line="redstone_lamp[lit=true]", alt="deepslate_tiles", lamp="redstone_lamp[lit=true]"),
    "smp": dict(edge="spruce_planks", fill="packed_mud", line="pearlescent_froglight", alt="mud_bricks", lamp="lantern[hanging=false,waterlogged=false]"),
}


def bezier(p0, p1, p2, t):
    return ((1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t * t * p2[0],
            (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t * t * p2[1])


# --- arrival platform (open, no walls): the whole island is in view
for x in range(-8, 9):
    for z in range(SPAWN_Z - 8, SPAWN_Z + 9):
        d = math.hypot(x, z - SPAWN_Z)
        if d <= 6.5:
            clear_above(x, z)
            put(x, 0, z, "amethyst_block" if 4.5 < d <= 5.5 else "polished_deepslate" if d > 5.5 else ("deepslate_tiles" if (x + z) % 2 else "polished_deepslate"))
            put(x, FLOOR, z, "deepslate")
            reserved.add((x, z))
        elif d <= 7.6:
            clear_above(x, z)
            stairs(x, 0, z, "polished_deepslate", facing_in(x, z - SPAWN_Z))
            reserved.add((x, z))
put(0, 0, SPAWN_Z, "pearlescent_froglight")
for i in range(8):
    a = i * math.pi / 4 + math.pi / 8
    x, z = round(6 * math.cos(a)), round(SPAWN_Z + 6 * math.sin(a))
    put(x, 1, z, "end_rod[facing=up]")

# --- four realm paths fanning out from the spawn
for realm, (px, pz) in PORTALS.items():
    th = THEME[realm]
    p0 = (px * 0.12, SPAWN_Z - 7)
    p2 = (px, pz + RING_R + 3)
    p1 = (px * 0.85, (p0[1] + p2[1]) / 2 + 6)
    steps = 160
    last_lamp = -99
    for k in range(steps + 1):
        t = k / steps
        cx, cz = bezier(p0, p1, p2, t)
        # tangent for the side offset
        tx, tz = bezier(p0, p1, p2, min(1, t + 0.01))
        dx, dz = tx - cx, tz - cz
        ln = math.hypot(dx, dz) or 1
        nx, nz = -dz / ln, dx / ln
        for w in range(-3, 4):
            x, z = round(cx + nx * w), round(cz + nz * w)
            if (x, z) in reserved and abs(w) == 3:
                continue
            if abs(w) == 3:
                b = th["edge"]
            elif w == 0:
                b = th["line"] if k % 6 == 0 else th["alt"]
            else:
                b = th["fill"]
            pave(x, z, b)
        if k - last_lamp >= 50 and 0.2 < t < 0.85:
            # a few slim lantern posts, alternating sides
            last_lamp = k
            side = 4.5 if (k // 50) % 2 else -4.5
            x, z = round(cx + nx * side), round(cz + nz * side)
            for y in range(FLOOR + 1, FLOOR + 4):
                put(x, y, z, WALL_POST)
            put(x, FLOOR + 4, z, ("soul_lantern" if realm in ("factions", "cannons") else "lantern") + "[hanging=false,waterlogged=false]")
            reserved.add((x, z))


# --- round portals
def ring_portal(cx, cz, realm):
    th = THEME[realm]
    cy = FLOOR + 2 + RING_R          # ring centre height
    # round pedestal with steps
    for x in range(-9, 10):
        for z in range(-5, 6):
            d = math.hypot(x, z * 1.6)
            if d <= 8.5:
                clear_above(cx + x, cz + z, 4)
                put(cx + x, FLOOR + 1, cz + z, th["fill"] if d < 7 else th["edge"])
                reserved.add((cx + x, cz + z))
    for x in range(-3, 4):
        stairs(cx + x, FLOOR + 1, cz + 6, "polished_blackstone_brick" if realm != "smp" else "spruce", "north")
    # the ring (XY plane) and the portal inside it
    for x in range(-RING_R - 2, RING_R + 3):
        for y in range(-RING_R - 2, RING_R + 3):
            d = math.hypot(x, y)
            yy = cy + y
            if yy < FLOOR + 2:
                continue
            if d <= RING_R - 0.6:
                put(cx + x, yy, cz, "nether_portal[axis=x]")
            elif d <= RING_R + 0.7:
                ang = math.atan2(y, x)
                seg = int((ang + math.pi) / (2 * math.pi) * 16)
                if realm == "factions":
                    b = "tnt[unstable=false]" if seg % 4 == 0 else ("red_nether_bricks" if seg % 2 else "polished_blackstone_bricks")
                elif realm == "cannons":
                    b = "redstone_lamp[lit=true]" if seg % 4 == 0 else ("obsidian" if seg % 2 else "crying_obsidian")
                elif realm == "skyblock":
                    b = "grass_block[snowy=false]" if y > 1 else ("moss_block" if seg % 2 else "rooted_dirt")
                else:
                    b = "oak_log[axis=z]" if seg % 2 else "stripped_oak_log[axis=z]"
                put(cx + x, yy, cz, b)
                put(cx + x, yy, cz + 1, th["edge"] if realm != "skyblock" else "dirt")
            elif d <= RING_R + 1.6 and h(x, y, cz) < 0.5 and realm in ("smp", "skyblock"):
                put(cx + x, yy, cz, "flowering_azalea_leaves[distance=1,persistent=true,waterlogged=false]" if h(x, y) < 0.5 else "azalea_leaves[distance=1,persistent=true,waterlogged=false]", force=False)
    top = cy + RING_R + 1
    # theme crown / details
    if realm == "skyblock":
        # mini floating island on top with a tree, vines hanging off the ring
        for x in range(-4, 5):
            for z in range(-2, 3):
                if x * x / 16 + z * z / 4 <= 1:
                    put(cx + x, top, cz + z, "grass_block[snowy=false]")
                    put(cx + x, top - 1, cz + z, "dirt")
                    if abs(x) <= 2 and abs(z) <= 1:
                        put(cx + x, top - 2, cz + z, "dirt")
        for y in range(1, 5):
            put(cx, top + y, cz, "oak_log[axis=y]")
        for x in range(-2, 3):
            for z in range(-2, 3):
                for y in range(4, 7):
                    if abs(x) + abs(z) + (y - 4) <= 3 and (x or z or y > 4):
                        put(cx + x, top + y, cz + z, "oak_leaves[distance=1,persistent=true,waterlogged=false]", force=False)
        for x in (-5, -3, 3, 5):
            for i in range(3):
                put(cx + x, cy + 2 - i, cz - 1, "vine[east=false,north=false,south=true,up=false,west=false]")
    elif realm == "factions":
        for x in (-8, 8):
            for y in range(FLOOR + 2, FLOOR + 14):
                put(cx + x, y, cz, "polished_blackstone_bricks" if y % 4 else "red_nether_bricks")
            put(cx + x, FLOOR + 14, cz, "shroomlight")
            put(cx + x, FLOOR + 15, cz, "tnt[unstable=false]")
        put(cx, top, cz, "crimson_hyphae[axis=y]")
        put(cx, top + 1, cz, "shroomlight")
    elif realm == "cannons":
        for x in (-8, 8):
            for y in range(FLOOR + 2, FLOOR + 14):
                if y % 3 == 0:
                    put(cx + x, y, cz, "dispenser[facing=" + ("west" if x > 0 else "east") + ",triggered=false]")
                else:
                    put(cx + x, y, cz, "obsidian")
            put(cx + x, FLOOR + 14, cz, "redstone_lamp[lit=true]")
        put(cx, top, cz, "observer[facing=up,powered=false]")
        put(cx, top + 1, cz, "redstone_lamp[lit=true]")
    else:
        for x in (-8, 8):
            for y in range(FLOOR + 2, FLOOR + 13):
                put(cx + x, y, cz, "spruce_log[axis=y]")
            put(cx + x, FLOOR + 13, cz, "lantern[hanging=false,waterlogged=false]")
            for dz in (-1, 1):
                put(cx + x, FLOOR + 11, cz + dz, "oak_leaves[distance=1,persistent=true,waterlogged=false]")
        for x in range(-7, 8, 2):
            put(cx + x, FLOOR + 2, cz + 3, "poppy" if x % 4 else "cornflower")
        put(cx, top, cz, "oak_leaves[distance=1,persistent=true,waterlogged=false]")


for realm, (px, pz) in PORTALS.items():
    ring_portal(px, pz, realm)

# --- beacon + crystal over the island centre (between the paths)
cx0, cz0 = 0, 2
for x in range(-3, 4):
    for z in range(-3, 4):
        d = math.hypot(x, z)
        if d <= 3.3:
            pave(cx0 + x, cz0 + z, "amethyst_block" if d > 2.3 else "polished_deepslate")
for x in range(-1, 2):
    for z in range(-1, 2):
        put(cx0 + x, FLOOR - 2, cz0 + z, "iron_block")
        put(cx0 + x, FLOOR - 1, cz0 + z, "deepslate")
put(cx0, FLOOR - 1, cz0, "beacon")
put(cx0, FLOOR, cz0, "purple_stained_glass")
CY0 = 20
for y in range(0, 17):
    rr = (8 - abs(y - 7)) * 0.75 if y <= 7 else (8 - (y - 7) * 0.85) * 0.75
    rr = max(0, rr)
    for x in range(-7, 8):
        for z in range(-7, 8):
            d = abs(x) + abs(z)
            if d <= rr:
                b = "magenta_stained_glass" if x == 0 and z == 0 else ("amethyst_block" if (x + y + z) % 3 else "purple_stained_glass") if d > rr - 1.2 else "purple_stained_glass"
                put(cx0 + x, CY0 + y, cz0 + z, b)
put(cx0, CY0 + 17, cz0, "end_rod[facing=up]")
for i in range(8):
    a = i * math.pi / 4
    x, z = round(10 * math.cos(a)), round(10 * math.sin(a))
    y = CY0 + 3 + (i % 2) * 4
    put(cx0 + x, y, cz0 + z, "amethyst_block")
    put(cx0 + x, y + 1, cz0 + z, "amethyst_cluster[facing=up,waterlogged=false]")
    put(cx0 + x, y - 1, cz0 + z, "amethyst_cluster[facing=down,waterlogged=false]")


# --- side paths off the spawn: west to the Discord pavilion, east to the parkour arch
def straight(x0, z0, x1, z1, edge, fill):
    n = int(max(abs(x1 - x0), abs(z1 - z0)))
    for k in range(n + 1):
        t = k / max(n, 1)
        cx, cz = x0 + (x1 - x0) * t, z0 + (z1 - z0) * t
        for w in range(-2, 3):
            x, z = round(cx), round(cz + w)
            if (x, z) in reserved:
                continue
            pave(x, z, edge if abs(w) == 2 else fill)


straight(-8, SPAWN_Z, -20, SPAWN_Z - 4, "polished_blackstone_bricks", "polished_deepslate")
straight(8, SPAWN_Z, 30, SPAWN_Z - 4, "polished_blackstone_bricks", "polished_deepslate")


def pavilion(xc, zc, theme_glow):
    for x in range(-7, 8):
        for z in range(-7, 8):
            d = math.hypot(x, z)
            if d <= 6.5:
                clear_above(xc + x, zc + z)
                put(xc + x, FLOOR + 1, zc + z, "polished_deepslate" if d < 5 else "polished_blackstone_bricks")
                reserved.add((xc + x, zc + z))
            elif d <= 7.4:
                clear_above(xc + x, zc + z)
                stairs(xc + x, FLOOR + 1, zc + z, "polished_blackstone_brick", facing_in(x, z))
                reserved.add((xc + x, zc + z))
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


pavilion(-27, SPAWN_Z - 4, "pearlescent_froglight")


def paved(x, z):
    for dx in (-2, 0, 2):
        for dz in (-2, 0, 2):
            if (x + dx, z + dz) in reserved:
                return True
    return False


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
PK_R, PK_A0, PK_SWEEP, PK_N, PK_RISE = 57, 38, 300, 80, 30
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
# ---- the sky sign: giant RELAPSED wordmark north of the island (white letters, purple drop shadow, gradient bar,
#      small MC stacked under the bar on the right, per the logo). Placed relative to the final spawn, so it is added
#      after the shift below. Lit by invisible light blocks so it reads at night.
FONT = {
    "R": ["11110", "10001", "10001", "11110", "10100", "10010", "10001"],
    "E": ["11111", "10000", "10000", "11110", "10000", "10000", "11111"],
    "L": ["10000", "10000", "10000", "10000", "10000", "10000", "11111"],
    "A": ["01110", "10001", "10001", "11111", "10001", "10001", "10001"],
    "P": ["11110", "10001", "10001", "11110", "10000", "10000", "10000"],
    "S": ["01111", "10000", "10000", "01110", "00001", "00001", "11110"],
    "D": ["11110", "10001", "10001", "10001", "10001", "10001", "11110"],
    "M": ["10001", "11011", "10101", "10101", "10001", "10001", "10001"],
    "C": ["01111", "10000", "10000", "10000", "10000", "10000", "01111"],
}
SIGN_GRAD = ["light_blue_concrete", "cyan_concrete", "blue_concrete", "purple_concrete", "purpur_block", "magenta_concrete"]


def sky_sign(out):
    sz = -130                       # face plane (north of everything else)
    def word(text, scale, x0, ytop, face):
        cols = []
        x = x0
        for ch in text:
            g = FONT[ch]
            for r, row in enumerate(g):
                for c, bit in enumerate(row):
                    if bit != "1":
                        continue
                    for dx in range(scale):
                        for dy in range(scale):
                            px, py = x + c * scale + dx, ytop - r * scale - dy
                            cols.append((px, py))
            x += 5 * scale + scale
        for (px, py) in cols:
            out[(px, py, sz)] = "minecraft:" + face
            out[(px, py, sz - 1)] = "minecraft:" + face
            # drop shadow one block down-right, behind: brand gradient left to right
            t = (px + 75) / 150
            sh = SIGN_GRAD[max(0, min(len(SIGN_GRAD) - 1, int(t * len(SIGN_GRAD))))]
            k = (px + 1, py - 1, sz - 2)
            if k not in out:
                out[k] = "minecraft:" + sh
            out[(px + 1, py - 1, sz - 3)] = "minecraft:" + sh
        return cols, x - scale
    big = 3
    width = 8 * 6 * big - big
    x0 = -width // 2
    top = 44
    cols, xend = word("RELAPSED", big, x0, top, "white_concrete")
    # gradient bar under the word
    bar_y = top - 7 * big - 2
    for x in range(x0, xend + 1):
        t = (x - x0) / max(1, xend - x0)
        b = SIGN_GRAD[min(len(SIGN_GRAD) - 1, int(t * len(SIGN_GRAD)))]
        for y in (bar_y, bar_y - 1):
            out[(x, y, sz)] = "minecraft:" + b
            out[(x, y, sz - 1)] = "minecraft:" + b
    # MC, small, right-aligned under the bar
    mc_scale = 2
    mc_w = 2 * 6 * mc_scale - mc_scale
    mcols, _ = word("MC", mc_scale, xend - mc_w + 1, bar_y - 3, "white_concrete")
    # light it: a sparse grid of invisible lights just in front of the face
    pts = cols + mcols + [(x, bar_y) for x in range(x0, xend + 1)]
    for (px, py) in pts:
        if px % 3 == 0 and py % 3 == 0:
            out[(px, py, sz + 2)] = "minecraft:light[level=15,waterlogged=false]"


shifted = {(x, y, z - SPAWN_Z): b for (x, y, z), b in blocks.items()}
blocks.clear()
blocks.update(shifted)
for k in ("start", "finish", "entrance"):
    if parkour.get(k):
        parkour[k][2] -= SPAWN_Z
for c in parkour["checkpoints"]:
    c[2] -= SPAWN_Z
_json.dump(parkour, open(os.path.join(os.path.dirname(OUT), "parkour.json"), "w"))
sky_sign(blocks)
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
