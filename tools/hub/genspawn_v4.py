"""RELAPSEDMC hub spawn, v4 ("the Gallery").

Writes plugins/lag-core/src/main/resources/hub-spawn.txt.gz (palette line, then "x y z idx") and parkour.json.
(0,0,0) = top of the spawn dais. The hub places it at y=100. North = -Z; players spawn facing north.

What you see from spawn, front to back:
  spawn dais          small round dais, amethyst heart, stairs all round
  the Well            glowing pool ringed by light inlays around a beacon; the beam runs up through
  the Crystal         a big floating amethyst/glass crystal with orbiting shards, right in front of you
  the Gallery         a raised terrace with the four realm portals in one row, each themed, each lit
                      SKYBLOCK  FACTIONS  CANNONS  SMP   (left to right)
  the Sign            the giant RELAPSED wordmark in the sky behind them
Around: Discord pavilion (west), parkour arch + helix course (east), cherry terrace + lookout (south),
lamp posts on every path, waterfalls off the edges, a crystal-veined underside, a ring of floating islets.
Run: python3 tools/hub/genspawn_v4.py [--preview out.png]
"""
import gzip, json, math, os, sys

RES = os.path.join(os.path.dirname(__file__), "..", "..", "plugins", "lag-core", "src", "main", "resources")
OUT = os.path.join(RES, "hub-spawn.txt.gz")
FLOOR = -1                 # plaza / garden floor (the spawn dais top is 0)
CX, CZ = 0, -12            # centre of the plaza, the Well and the Crystal
R = 47                     # island radius (around the plaza centre)
PLAZA = 17
TERRACE_Z0, TERRACE_Z1 = -29, -45   # the Gallery terrace (north)
TERRACE_Y = FLOOR + 2      # terrace floor
PORTAL_Z = -38
PORTALS = [("skyblock", -30), ("factions", -12), ("cannons", 12), ("smp", 30)]
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


def edge_r(x, z, r, seed=0, wob=7):
    ang = math.atan2(z, x)
    return r + (vnoise(ang * 9 + seed, 3, 1.7, seed + 11) - 0.5) * wob + (vnoise(ang * 25 + seed, 9, 1.3, seed + 3) - 0.5) * 2.5


LEAF = "[distance=1,persistent=true,waterlogged=false]"
WALL = "polished_blackstone_wall[east=none,north=none,south=none,up=true,waterlogged=false,west=none]"
GLOW = ["light_blue_stained_glass", "cyan_stained_glass", "blue_stained_glass", "purple_stained_glass", "magenta_stained_glass", "pink_stained_glass"]


def glow_at(t):
    """Brand gradient (cyan -> purple -> pink) as stained glass, t in 0..1."""
    return GLOW[max(0, min(len(GLOW) - 1, int(t * len(GLOW))))]


def stairs(x, y, z, mat, facing, half="bottom"):
    put(x, y, z, f"{mat}_stairs[facing={facing},half={half},shape=straight,waterlogged=false]")


# ======================================================================= island body
def island(cx, cz, r, depth, top_y, seed, main=False, wob=7):
    for x in range(-r - 8, r + 9):
        for z in range(-r - 8, r + 9):
            er = edge_r(x, z, r, seed, wob)
            d = math.hypot(x, z)
            if d > er:
                continue
            k = 1 - (d / er) ** 1.5
            dep = int(2 + depth * k ** 0.85 + (vnoise(x * 2 + seed, z * 2, 5, seed) - 0.5) * 6)
            for dy in range(1, dep + 1):
                y = top_y - dy
                n = h(cx + x, y, cz + z, seed)
                if dy <= 2:
                    b = "dirt" if d > er - 4 else "deepslate"
                elif dy <= 4 and d > er - 3:
                    b = "stone"
                elif h(cx + x, y // 3, cz + z, 99 + seed) < 0.06 and dy > 4:
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
                    b = ("dripstone_block" if i < ln - 2 else
                         "pointed_dripstone[thickness=tip,vertical_direction=down,waterlogged=false]" if i == ln - 1 else
                         "pointed_dripstone[thickness=frustum,vertical_direction=down,waterlogged=false]")
                    put(cx + x, bottom - i, cz + z, b)
            elif r_ < 0.10:
                put(cx + x, bottom, cz + z, "hanging_roots[waterlogged=false]")
            elif r_ < 0.15:
                put(cx + x, bottom, cz + z, "amethyst_cluster[facing=down,waterlogged=false]")
            elif main and r_ < 0.158 and dep > 5:
                ln = 2 + int(h(x, z, 9) * 6)
                for i in range(ln):
                    put(cx + x, bottom - i, cz + z, "iron_chain[axis=y,waterlogged=false]")
                put(cx + x, bottom - ln, cz + z, "soul_lantern[hanging=true,waterlogged=false]")


def in_island(x, z):
    return math.hypot(x - CX, z - CZ) <= edge_r(x - CX, z - CZ, R, 0) - 0.5


island(CX, CZ, R, 36, FLOOR + 1, 0, main=True)


# ======================================================================= zones
def d_plaza(x, z):
    return math.hypot(x - CX, z - CZ)


def on_terrace(x, z):
    return TERRACE_Z1 <= z <= TERRACE_Z0 and abs(x) <= 40


def on_path(x, z):
    """Paved walks: the east-west cross path to the pavilion/parkour, and the south walk to the lookout."""
    if abs(z - (CZ + 0)) <= 2 and 15 <= abs(x - CX) <= 40:
        return True
    if abs(x) <= 2 and 14 <= z <= 34:
        return True
    return False


# ======================================================================= garden (default surface)
for x in range(-R - 10, R + 11):
    for z in range(CZ - R - 10, CZ + R + 11):
        if not in_island(x, z):
            continue
        put(x, FLOOR, z, "grass_block[snowy=false]")
        put(x, FLOOR - 1, z, "dirt")

# rim: a neat stone lip so the edge reads as designed, not ragged
for x in range(-R - 10, R + 11):
    for z in range(CZ - R - 10, CZ + R + 11):
        if not in_island(x, z):
            continue
        if any(not in_island(x + dx, z + dz) for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))):
            put(x, FLOOR, z, "polished_deepslate")
            put(x, FLOOR + 1, z, "polished_deepslate_slab[type=bottom,waterlogged=false]")

# ======================================================================= the plaza
for x in range(CX - PLAZA - 1, CX + PLAZA + 2):
    for z in range(CZ - PLAZA - 1, CZ + PLAZA + 2):
        d = d_plaza(x, z)
        if d > PLAZA + 0.5:
            continue
        a = (math.atan2(z - CZ, x - CX) / math.pi + 1) / 2   # 0..1 around
        b = "polished_deepslate"
        ring = round(d)
        if ring in (8, 14):
            # glowing inlay rings: stained glass over a light, brand gradient around the ring
            b = glow_at(abs(a * 2 - 1))
            put(x, FLOOR - 1, z, "sea_lantern")
        elif ring == PLAZA:
            b = "quartz_bricks"
        elif ring == PLAZA - 1:
            b = "polished_blackstone_bricks"
        elif any(abs(((math.degrees(math.atan2(z - CZ, x - CX)) + 360) % 45) - 22.5) > 21.2 for _ in [0]) and 7 < d < PLAZA - 1:
            b = "deepslate_tiles"            # 8 spokes
        elif (x + z) % 2 == 0 and d > 9:
            b = "polished_deepslate"
        elif d > 9:
            b = "deepslate_bricks"
        put(x, FLOOR, z, b)

# ---- the Well: a glowing pool around the beacon, a bridge across it on the spawn axis
for x in range(CX - 7, CX + 8):
    for z in range(CZ - 7, CZ + 8):
        d = d_plaza(x, z)
        if 3.5 < d <= 6.5:
            if abs(x - CX) <= 1:          # north-south bridge
                put(x, FLOOR, z, "polished_blackstone_bricks")
                continue
            put(x, FLOOR, z, "water")
            put(x, FLOOR - 1, z, "sea_lantern" if (x + z) % 3 == 0 else "prismarine_bricks")
            put(x, FLOOR - 2, z, "deepslate")
        elif 6.5 < d <= 7.5:
            put(x, FLOOR, z, "polished_blackstone_bricks")
            put(x, FLOOR + 1, z, "polished_blackstone_brick_slab[type=bottom,waterlogged=false]" if abs(x - CX) > 1 else "air")
        elif d <= 3.5:
            put(x, FLOOR, z, "polished_deepslate" if d > 1.5 else "deepslate_tiles")
# beacon at the centre, flush with the floor, iron pyramid below, beam goes up through the crystal glass
# (no beacon block: its beam stood like a pole right in front of spawn. The plugin's particle helix still runs here.)
put(CX, FLOOR, CZ, "pearlescent_froglight")
for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
    put(CX + dx, FLOOR, CZ + dz, "amethyst_block")

# ---- the spawn dais (0,0): top at y=0, stairs all round
for x in range(-4, 5):
    for z in range(-4, 5):
        d = math.hypot(x, z)
        if d <= 2.6:
            put(x, 0, z, "polished_deepslate")
            put(x, FLOOR, z, "polished_deepslate")
        elif d <= 3.6:
            fx = "east" if x < 0 and abs(x) >= abs(z) else "west" if x > 0 and abs(x) >= abs(z) else "south" if z < 0 else "north"
            stairs(x, 0, z, "polished_blackstone_brick", fx)
put(0, 0, 0, "amethyst_block")
put(0, FLOOR, 0, "sea_lantern")
for x, z in ((1, 0), (-1, 0), (0, 1), (0, -1)):
    put(x, 0, z, "purple_stained_glass")
    put(x, FLOOR, z, "sea_lantern")

# ---- lamp posts round the plaza
def lamp(x, y, z, top="lantern[hanging=false,waterlogged=false]", base="chiseled_polished_blackstone"):
    put(x, y, z, base)
    put(x, y + 1, z, WALL)
    put(x, y + 2, z, WALL)
    put(x, y + 3, z, top)


for i in range(16):
    a = math.radians(i * 22.5 + 11.25)
    x, z = round(CX + (PLAZA + 1.5) * math.cos(a)), round(CZ + (PLAZA + 1.5) * math.sin(a))
    if z < CZ - PLAZA + 3:       # leave the terrace stairs clear
        continue
    if abs(x) <= 3 and z > CZ:   # and the south walk
        continue
    if abs(z - CZ) <= 3:          # and the cross path
        continue
    lamp(x, FLOOR + 1, z)

# ======================================================================= the Crystal (floating over the Well)
CR_Y = 13
CR_H, CR_MID, CR_W = 27, 9, 5.6
for y in range(0, CR_H):
    rr = (y / CR_MID) * CR_W if y <= CR_MID else (CR_H - 1 - y) / (CR_H - 1 - CR_MID) * CR_W
    R_ = round(rr)
    for x in range(-R_, R_ + 1):
        for z in range(-R_, R_ + 1):
            d = abs(x) + abs(z)
            if d > R_:
                continue
            if d < R_ - 1 and 0 < y < CR_H - 1:
                continue                      # hollow: only the skin is built
            ridge = x == 0 or z == 0          # the four ridge lines of the gem glow
            put(CX + x, CR_Y + y, CZ + z, "pearlescent_froglight" if ridge else "amethyst_block")
put(CX, CR_Y - 1, CZ, "amethyst_cluster[facing=down,waterlogged=false]")
put(CX, CR_Y + CR_H, CZ, "amethyst_cluster[facing=up,waterlogged=false]")
# ======================================================================= the Gallery terrace + realm portals
for x in range(-41, 42):
    for z in range(TERRACE_Z1, TERRACE_Z0 + 1):
        if not in_island(x, z):
            continue
        for y in range(FLOOR, TERRACE_Y):
            put(x, y, z, "polished_deepslate")
        # mosaic: tiles with a diamond lattice of blackstone, glowing glass studs where the lattice crosses
        u, v = (x + z) % 8, (x - z) % 8
        if u == 0 and v == 0:
            b = "purple_stained_glass"
            put(x, TERRACE_Y - 1, z, "sea_lantern")
        elif u == 0 or v == 0:
            b = "polished_blackstone"
        else:
            b = "deepslate_tiles"
        put(x, TERRACE_Y, z, b)
# retaining wall face + wide stairs up in the middle and at each portal
for x in range(-41, 42):
    z = TERRACE_Z0
    if not in_island(x, z):
        continue
    stair_here = abs(x) <= 6 or any(abs(x - px) <= 3 for _, px in PORTALS)
    if stair_here:
        stairs(x, FLOOR + 1, z + 1, "polished_deepslate", "north")
        put(x, FLOOR, z + 1, "polished_deepslate")
        stairs(x, FLOOR + 2, z, "polished_deepslate", "north")
        put(x, FLOOR + 1, z, "polished_deepslate")
    else:
        put(x, FLOOR + 1, z, "polished_blackstone_bricks")
        put(x, FLOOR + 2, z, "polished_blackstone_bricks")
        put(x, FLOOR + 3, z, "polished_blackstone_brick_slab[type=bottom,waterlogged=false]")
        # planter in front of the wall
        if in_island(x, z + 1):
            put(x, FLOOR + 1, z + 1, ["flowering_azalea_leaves" + LEAF, "azalea_leaves" + LEAF][h(x, 3) < 0.5])
# a glowing strip along the terrace front edge
for x in range(-40, 41):
    if in_island(x, TERRACE_Z0 - 1):
        put(x, TERRACE_Y, TERRACE_Z0 - 1, glow_at((x + 40) / 80))
        put(x, TERRACE_Y - 1, TERRACE_Z0 - 1, "sea_lantern")

COLOR = {"skyblock": "lime", "factions": "red", "cannons": "orange", "smp": "light_blue"}
WALLP = "polished_blackstone_wall[east=none,north=none,south=none,up=true,waterlogged=false,west=none]"


def gate(px, realm):
    """A dark gothic lancet gate: polished blackstone, a thin realm-coloured neon line around the opening and
    around the whole silhouette (recessed behind a moulding), flanking pilasters with pinnacles, a medallion
    near the apex for the realm icon, banners and a runner. Dark mass + thin light = premium, not cottage."""
    c = COLOR[realm]
    neon = f"{c}_concrete"
    y0 = TERRACE_Y + 1
    PZ = PORTAL_Z
    TOP = [18, 18, 17, 16, 15, 14, 12]           # silhouette height (above y0) by |dx|

    def inS(dx, dy):
        return abs(dx) <= 6 and 0 <= dy <= TOP[abs(dx)]

    def opening(dx, dy):
        t = {0: 9, 1: 8, 2: 6}.get(abs(dx), -1)
        return abs(dx) <= 2 and 0 <= dy <= t

    def nb(f, dx, dy):
        return any(f(dx + a, dy + b) for a in (-1, 0, 1) for b in (-1, 0, 1) if a or b)

    ring1 = lambda dx, dy: inS(dx, dy) and not opening(dx, dy) and nb(opening, dx, dy)
    ring2 = lambda dx, dy: inS(dx, dy) and not opening(dx, dy) and not ring1(dx, dy) and nb(ring1, dx, dy)
    edge = lambda dx, dy: inS(dx, dy) and dy > 0 and any(not inS(dx + a, dy + b) for a in (-1, 0, 1) for b in (0, 1) if a or b)
    MED = (0, 14)                                # medallion centre (dx, dy)
    med = lambda dx, dy: 1.4 <= math.hypot(dx - MED[0], dy - MED[1]) <= 2.2

    for dx in range(-6, 7):
        for dy in range(0, 19):
            if not inS(dx, dy):
                continue
            x, y = px + dx, y0 + dy
            if opening(dx, dy):
                put(x, y, PZ, "nether_portal[axis=x]")
                put(x, y, PZ - 1, "obsidian")
                continue
            body = "polished_blackstone_bricks" if h(x, y, 77) > 0.12 else "cracked_polished_blackstone_bricks"
            put(x, y, PZ - 1, body)
            if ring1(dx, dy) or edge(dx, dy) or med(dx, dy):
                put(x, y, PZ, neon)                              # the neon line, recessed
                if not edge(dx, dy):
                    put(x, y, PZ + 1, "light[level=12,waterlogged=false]")
            elif ring2(dx, dy):
                put(x, y, PZ, body)
                put(x, y, PZ + 1, "polished_blackstone")         # moulding stands proud of the neon
            else:
                put(x, y, PZ, body)
    # medallion backplate (the icon floats in front of it)
    for dx in range(-1, 2):
        for dy in range(13, 16):
            put(px + dx, y0 + dy, PZ, "chiseled_polished_blackstone" if (dx, dy) == (0, 14) else "polished_blackstone")
    # pilasters: stand proud on both sides, carry banners, end in pinnacles
    for s_ in (-1, 1):
        for cx in (px + s_ * 5, px + s_ * 6):
            put(cx, y0, PZ + 1, "polished_blackstone")
            put(cx, y0, PZ + 2, "polished_blackstone_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]")
            for dy in range(1, 12):
                put(cx, y0 + dy, PZ + 1, "chiseled_polished_blackstone" if dy in (5, 11) else "polished_blackstone_bricks")
        put(px + s_ * 5, y0 + 9, PZ + 2, f"{c}_wall_banner[facing=south]")
        put(px + s_ * 6, y0 + 9, PZ + 2, f"{c}_wall_banner[facing=south]")
        pin = px + s_ * 6
        put(pin, y0 + 12, PZ + 1, "polished_blackstone_bricks")
        put(pin, y0 + 13, PZ + 1, WALLP)
        put(pin, y0 + 14, PZ + 1, WALLP)
        put(pin, y0 + 15, PZ + 1, neon)
        put(pin, y0 + 16, PZ + 1, "end_rod[facing=up]")
        put(pin, y0 + 12, PZ, "polished_blackstone_bricks")
    # apex finial
    put(px, y0 + 19, PZ, "chiseled_polished_blackstone")
    put(px, y0 + 20, PZ, WALLP)
    put(px, y0 + 21, PZ, "end_rod[facing=up]")
    # back face: plain blackstone so the gate reads solid from the colonnade side
    for dx in range(-6, 7):
        for dy in range(0, 19):
            if inS(dx, dy) and not opening(dx, dy):
                put(px + dx, y0 + dy, PZ - 2, "polished_blackstone_bricks") if abs(dx) <= 5 and dy <= TOP[abs(dx)] - 1 else None
    # runner: polished floor, realm carpet, dark slab kerbs, lamp posts
    for z in range(PZ + 1, PZ + 7):
        for dx in range(-3, 4):
            put(px + dx, TERRACE_Y, z, "polished_blackstone")
            if abs(dx) <= 1:
                put(px + dx, TERRACE_Y + 1, z, f"{c}_carpet")
            elif abs(dx) == 3 and z > PZ + 2:
                put(px + dx, TERRACE_Y + 1, z, "polished_blackstone_slab[type=bottom,waterlogged=false]")
        put(px, TERRACE_Y - 1, z, "sea_lantern")
    for s_ in (-1, 1):
        x = px + s_ * 4
        put(x, TERRACE_Y + 1, PZ + 6, "polished_blackstone")
        put(x, TERRACE_Y + 2, PZ + 6, WALLP)
        put(x, TERRACE_Y + 3, PZ + 6, WALLP)
        put(x, TERRACE_Y + 4, PZ + 6, "lantern[hanging=false,waterlogged=false]")


for realm, px in PORTALS:
    gate(px, realm)


def monolith():
    """Between the two middle gates, dead centre of the spawn view: a dark slab with a purple neon outline.
    The RELAPSEDMC logo hologram floats in front of it."""
    y0, PZ = TERRACE_Y + 1, PORTAL_Z - 1
    TOPM = [11, 11, 11, 10, 9]
    inM = lambda dx, dy: abs(dx) <= 4 and 0 <= dy <= TOPM[abs(dx)]
    for dx in range(-4, 5):
        for dy in range(0, 12):
            if not inM(dx, dy):
                continue
            x, y = dx, y0 + dy
            edge = dy > 0 and any(not inM(dx + a, dy + b) for a in (-1, 0, 1) for b in (0, 1) if a or b)
            put(x, y, PZ - 1, "polished_blackstone_bricks")
            put(x, y, PZ, "purple_concrete" if edge else "polished_blackstone")
    for dx in range(-5, 6):
        put(dx, y0, PZ + 1, "polished_blackstone_slab[type=bottom,waterlogged=false]")
    put(0, y0 + 12, PZ, WALLP)
    put(0, y0 + 13, PZ, "end_rod[facing=up]")


monolith()

# realm spotlights: a coloured beacon beam straight up behind every portal (seen from anywhere on the island)
BEAM = {"skyblock": "lime_stained_glass", "factions": "red_stained_glass", "cannons": "orange_stained_glass", "smp": "light_blue_stained_glass"}
for realm, px in PORTALS:
    bz = PORTAL_Z - 3
    for dx in range(-1, 2):
        for dz in range(-1, 2):
            put(px + dx, TERRACE_Y - 1, bz + dz, "iron_block")
    put(px, TERRACE_Y, bz, "beacon")
    put(px, TERRACE_Y + 1, bz, BEAM[realm])
    for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        put(px + dx, TERRACE_Y + 1, bz + dz, "polished_blackstone_brick_slab[type=bottom,waterlogged=false]")

# back wall of the Gallery: low colonnade with hanging lanterns between the portals
for x in range(-41, 42):
    z = TERRACE_Z1 + 1
    if not in_island(x, z) or any(abs(x - px) <= 6 for _, px in PORTALS):
        continue
    for y in range(TERRACE_Y + 1, TERRACE_Y + 4):
        put(x, y, z, "polished_blackstone_bricks" if x % 4 else "chiseled_polished_blackstone")
    put(x, TERRACE_Y + 4, z, "polished_blackstone_brick_slab[type=bottom,waterlogged=false]")
    if x % 4 == 0:
        put(x, TERRACE_Y + 5, z, "end_rod[facing=up]")

# ======================================================================= west: Discord pavilion
def pavilion(xc, zc):
    y0 = FLOOR
    for x in range(xc - 7, xc + 8):
        for z in range(zc - 7, zc + 8):
            d = math.hypot(x - xc, z - zc)
            if d <= 6.5:
                put(x, y0, z, "polished_deepslate" if d < 5 else "quartz_bricks")
                if round(d) == 3:
                    put(x, y0, z, glow_at((math.atan2(z - zc, x - xc) / math.pi + 1) / 2))
                    put(x, y0 - 1, z, "sea_lantern")
    # 8 slim columns + glass dome
    for i in range(8):
        a = i / 8 * math.pi * 2
        x, z = round(xc + 6 * math.cos(a)), round(zc + 6 * math.sin(a))
        for y in range(y0 + 1, y0 + 6):
            put(x, y, z, "quartz_pillar[axis=y]")
        put(x, y0 + 6, z, "chiseled_quartz_block")
    for y in range(0, 7):
        rr = math.sqrt(max(0, 6.6 ** 2 - (y * 1.2) ** 2))
        for x in range(xc - 7, xc + 8):
            for z in range(zc - 7, zc + 8):
                d = math.hypot(x - xc, z - zc)
                if rr - 1 < d <= rr:
                    put(x, y0 + 7 + y, z, "purple_stained_glass" if (x + z + y) % 4 else "magenta_stained_glass", force=False)
    put(xc, y0 + 14, zc, "end_rod[facing=up]")
    put(xc, y0 + 12, zc, "iron_chain[axis=y,waterlogged=false]")
    put(xc, y0 + 11, zc, "iron_chain[axis=y,waterlogged=false]")
    put(xc, y0 + 10, zc, "pearlescent_froglight")
    put(xc, y0 + 1, zc, "amethyst_block")
    put(xc, y0 + 2, zc, "amethyst_cluster[facing=up,waterlogged=false]")


DISCORD = (-33, CZ)
pavilion(*DISCORD)

# ======================================================================= paths (cross path + south walk) with lamps
for x in range(-R, R + 1):
    for z in range(CZ - R, CZ + R + 1):
        if not in_island(x, z) or d_plaza(x, z) <= PLAZA + 0.5:
            continue
        if on_path(x, z) and get(x, FLOOR + 1, z) is None:
            edge = abs(z - CZ) == 2 if abs(x) > 3 else abs(x) == 2
            put(x, FLOOR, z, "polished_blackstone_bricks" if edge else "polished_deepslate")
for x in range(19, 41, 5):
    for s in (-1, 1):
        for zz in (CZ - 3, CZ + 3):
            if in_island(s * x, zz) and get(s * x, FLOOR + 1, zz) is None:
                lamp(s * x, FLOOR + 1, zz)
for z in range(16, 33, 5):
    for xx in (-3, 3):
        if in_island(xx, z):
            lamp(xx, FLOOR + 1, z)

# ======================================================================= south: cherry terrace + lookout
def cherry(x0, z0, y0, size, seed):
    hgt = 4 + size
    for y in range(hgt):
        put(x0, y0 + y, z0, "cherry_log[axis=y]")
    for b in range(3):
        a = h(seed, b) * math.pi * 2
        bx, bz = round(math.cos(a) * 2), round(math.sin(a) * 2)
        put(x0 + bx // 2, y0 + hgt - 1, z0 + bz // 2, "cherry_wood[axis=y]")
        cx, cy, cz = x0 + bx, y0 + hgt, z0 + bz
        rr = 2.3 + size * 0.5
        for x in range(-4, 5):
            for y in range(-2, 3):
                for z in range(-4, 5):
                    if (x * x + (y * 1.7) ** 2 + z * z) <= rr * rr and h(cx + x, cy + y, cz + z, seed) < 0.88:
                        put(cx + x, cy + y, cz + z, "cherry_leaves" + LEAF, force=False)
    # petals under it
    for x in range(-3, 4):
        for z in range(-3, 4):
            if h(x0 + x, z0 + z, seed, 8) < 0.35 and get(x0 + x, y0, z0 + z) is None and (get(x0 + x, y0 - 1, z0 + z) or "").endswith("grass_block[snowy=false]"):
                put(x0 + x, y0, z0 + z, "pink_petals[facing=north,flower_amount=%d]" % (1 + int(h(x, z, seed) * 4)))


# symmetric cherry trees: along the south walk, and framing the gallery terrace corners
for i, (x, z, s) in enumerate(((7, 18, 2), (-7, 18, 2), (8, 28, 1), (-8, 28, 1), (20, 14, 2), (-20, 14, 2),
                                (36, -26, 2), (-36, -26, 2), (22, 26, 1), (-22, 26, 1))):
    if in_island(x, z):
        cherry(x, z, FLOOR + 1, s, i * 7 + 3)

# flower beds: tidy rectangles with a stone border, alternating colours
BEDS = [("allium", "purple"), ("cornflower", "blue"), ("pink_tulip", "pink"), ("azure_bluet", "white")]
for i, (bx, bz) in enumerate(((13, 6), (-13, 6), (26, 4), (-26, 4), (12, 34 - 6), (-12, 28), (30, -18), (-30, -18))):
    flower = BEDS[i % len(BEDS)][0]
    for x in range(bx - 3, bx + 4):
        for z in range(bz - 2, bz + 3):
            if not in_island(x, z) or get(x, FLOOR + 1, z) is not None or d_plaza(x, z) <= PLAZA + 1 or on_path(x, z):
                continue
            border = abs(x - bx) == 3 or abs(z - bz) == 2
            if border:
                put(x, FLOOR, z, "polished_deepslate")
                put(x, FLOOR + 1, z, "polished_deepslate_slab[type=bottom,waterlogged=false]")
            else:
                put(x, FLOOR, z, "grass_block[snowy=false]")
                put(x, FLOOR + 1, z, flower)

# hedges along the cross path
for x in range(16, 42):
    for s in (-1, 1):
        for zz in (CZ - 4, CZ + 4):
            xx = s * x
            if in_island(xx, zz) and get(xx, FLOOR + 1, zz) is None and x % 5 != 4:
                put(xx, FLOOR + 1, zz, "azalea_leaves" + LEAF if (x // 2) % 2 else "flowering_azalea_leaves" + LEAF)

# lookout at the south tip
LZ = 33
while not in_island(0, LZ) and LZ > 20:
    LZ -= 1
for x in range(-5, 6):
    for z in range(LZ - 4, LZ + 3):
        d = math.hypot(x, (z - LZ) * 1.2)
        if d <= 5.2:
            put(x, FLOOR, z, "quartz_bricks" if d > 4 else "polished_deepslate")
            if 4.3 < d <= 5.2 and z > LZ - 3:
                put(x, FLOOR + 1, z, "polished_blackstone_wall[east=low,north=low,south=low,up=true,waterlogged=false,west=low]")
for x in (-4, 4):
    lamp(x, FLOOR + 1, LZ - 3)
put(0, FLOOR, LZ, "sea_lantern")

# ======================================================================= crystal groves (fill the NE/NW lawns, echo the big crystal)
def spire(x0, z0, ht, w, seed):
    """A small faceted crystal growing out of the lawn, leaning a touch outward."""
    lean = 0.12 if x0 > 0 else -0.12
    for y in range(ht):
        rr = w * (1 - y / ht) + 0.35
        ox = round(lean * y)
        for dx in range(-3, 4):
            for dz in range(-3, 4):
                if abs(dx) + abs(dz) <= rr:
                    edge = abs(dx) + abs(dz) > rr - 1
                    b = ("amethyst_block" if (y + dx) % 3 else "purple_stained_glass") if edge else "magenta_stained_glass"
                    put(x0 + ox + dx, FLOOR + 1 + y, z0 + dz, b)
    put(x0 + round(lean * ht), FLOOR + 1 + ht, z0, "amethyst_cluster[facing=up,waterlogged=false]")


def grove(cx, cz, side, seed):
    # geode bed: calcite ring, smooth basalt fleck, budding amethyst + clusters
    for dx in range(-7, 8):
        for dz in range(-6, 7):
            d = math.hypot(dx, dz * 1.15)
            x, z = cx + dx, cz + dz
            if d > 6.6 or on_path(x, z) or on_terrace(x, z) or not in_island(x, z):
                continue
            if get(x, FLOOR, z) != "minecraft:grass_block[snowy=false]":
                continue
            r_ = h(x, z, seed)
            if d > 5.6:
                put(x, FLOOR, z, "calcite")
            elif d > 4.8:
                put(x, FLOOR, z, "smooth_basalt" if r_ < 0.6 else "calcite")
            else:
                put(x, FLOOR, z, "amethyst_block" if r_ < 0.7 else "budding_amethyst")
                if r_ > 0.82 and get(x, FLOOR + 1, z) is None:
                    put(x, FLOOR + 1, z, "amethyst_cluster[facing=up,waterlogged=false]")
                elif r_ > 0.74 and get(x, FLOOR + 1, z) is None:
                    put(x, FLOOR + 1, z, "large_amethyst_bud[facing=up,waterlogged=false]")
            if 4.6 < d < 5.0:
                put(x, FLOOR - 1, z, "pearlescent_froglight")
    spire(cx, cz, 11, 2.4, seed)
    spire(cx + 3 * side, cz + 3, 7, 1.7, seed + 1)
    spire(cx - 2 * side, cz - 3, 6, 1.5, seed + 2)
    spire(cx + 4 * side, cz - 2, 4, 1.1, seed + 3)
    put(cx, FLOOR - 1, cz, "sea_lantern")
    # a bench facing the plaza on the grove's inner side
    bx = cx - 6 * side
    for dz in (-1, 0, 1):
        put(bx, FLOOR + 1, cz + dz, f"spruce_stairs[facing={'east' if side < 0 else 'west'},half=bottom,shape=straight,waterlogged=false]")
    put(bx, FLOOR + 1, cz - 2, "spruce_trapdoor[facing=north,half=bottom,open=true,powered=false,waterlogged=false]")
    put(bx, FLOOR + 1, cz + 2, "spruce_trapdoor[facing=south,half=bottom,open=true,powered=false,waterlogged=false]")


grove(31, -25, 1, 501)
grove(-31, -25, -1, 503)

# small planters with azaleas between the plaza and the side beds (south-east / south-west lawns)
for sx in (-1, 1):
    for (px, pz) in ((22, -3), (36, -1), (20, 8)):
        x0, z0 = px * sx, pz
        for dx in (-1, 0, 1):
            for dz in (-1, 0, 1):
                x, z = x0 + dx, z0 + dz
                if on_path(x, z):
                    continue
                if dx == 0 and dz == 0:
                    put(x, FLOOR, z, "rooted_dirt")
                    put(x, FLOOR + 1, z, "flowering_azalea" if (px + pz) % 2 else "azalea")
                else:
                    put(x, FLOOR, z, "mud_bricks")
                    put(x, FLOOR + 1, z, "mud_brick_slab[type=bottom,waterlogged=false]" if dx and dz else "moss_carpet")

# ======================================================================= grass detail (sparse, tidy)
for x in range(-R, R + 1):
    for z in range(CZ - R, CZ + R + 1):
        if get(x, FLOOR, z) == "minecraft:grass_block[snowy=false]" and get(x, FLOOR + 1, z) is None:
            r_ = h(x, z, 41)
            if r_ < 0.05:
                put(x, FLOOR + 1, z, "short_grass")
            elif r_ < 0.065:
                put(x, FLOOR + 1, z, "fern")

# ======================================================================= waterfalls off the rim (east, west, south-east, south-west)
def surface_top(x, z):
    for y in range(FLOOR + 4, FLOOR - 1, -1):
        if get(x, y, z) is not None:
            return y
    return None


for a_deg in (0, 180, 60, 120, 225, 315):
    a = math.radians(a_deg)
    rr = R + 8
    while rr > R - 8:
        x, z = round(CX + rr * math.cos(a)), round(CZ + rr * math.sin(a))
        if in_island(x, z):
            break
        rr -= 1
    ox, oz = round(CX + (rr + 1) * math.cos(a)), round(CZ + (rr + 1) * math.sin(a))
    for y in range(FLOOR, FLOOR - 50, -1):
        if get(ox, y, oz) is None:
            put(ox, y, oz, "water[level=8]")
    # a little stream channel feeding it
    for t in range(1, 5):
        x, z = round(CX + (rr - t) * math.cos(a)), round(CZ + (rr - t) * math.sin(a))
        if get(x, FLOOR + 1, z) is None:
            put(x, FLOOR, z, "water")
            put(x, FLOOR - 1, z, "prismarine_bricks")

# ======================================================================= parkour (helix around the island, starts at the east arch)
PK_R, PK_A0, PK_SWEEP, PK_N, PK_RISE = 58, 0, -300, 80, 30
PK_MATS = ["polished_deepslate", "smooth_quartz", "polished_deepslate", "amethyst_block"]
parkour = {"start": None, "checkpoints": [], "finish": None, "entrance": None}


def pk_platform(x, y, z, top, plate, rr=1):
    for dx in range(-rr, rr + 1):
        for dz in range(-rr, rr + 1):
            put(x + dx, y, z + dz, top if (dx or dz) else "pearlescent_froglight")
    put(x, y + 1, z, plate)
    for dx, dz in ((-rr, -rr), (-rr, rr), (rr, -rr), (rr, rr)):
        put(x + dx, y + 1, z + dz, "end_rod[facing=up]")


def pc(r, deg, w=0):
    a = math.radians(deg)
    return round(CX + r * math.cos(a) - w * math.sin(a)), round(CZ + r * math.sin(a) + w * math.cos(a))


# east arch at the end of the cross path, bridge out to the start pad
ER = 41
ex, ez = pc(ER, PK_A0)
for w in (-3, 3):
    px, pz = pc(ER, PK_A0, w)
    for y in range(FLOOR + 1, FLOOR + 9):
        put(px, y, pz, "polished_blackstone_bricks" if y % 3 else "chiseled_polished_blackstone")
    put(px, FLOOR + 5, pz, "pearlescent_froglight")
    put(px, FLOOR + 9, pz, "amethyst_block")
    put(px, FLOOR + 10, pz, "end_rod[facing=up]")
for w in range(-3, 4):
    px, pz = pc(ER, PK_A0, w)
    put(px, FLOOR + 8, pz, glow_at((w + 3) / 6).replace("stained_glass", "concrete"))
    if abs(w) == 1:
        put(px, FLOOR + 7, pz, "iron_chain[axis=y,waterlogged=false]")
        put(px, FLOOR + 6, pz, "soul_lantern[hanging=true,waterlogged=false]")
parkour["entrance"] = [ex, FLOOR + 1, ez]
for t in range(ER, PK_R - 1):
    for w in (-1, 0, 1):
        x, z = pc(t, PK_A0, w)
        if get(x, FLOOR, z) is None:
            put(x, FLOOR, z, "polished_blackstone_bricks" if w else "polished_deepslate")
sx, sz = pc(PK_R, PK_A0)
pk_platform(sx, FLOOR, sz, "polished_blackstone_bricks", "light_weighted_pressure_plate[power=0]", rr=2)
parkour["start"] = [sx, FLOOR + 1, sz]

prev = (sx, FLOOR, sz)
pk_path = [prev]
prev_slab = False
for i in range(1, PK_N + 1):
    t = i / PK_N
    a_deg = PK_A0 + PK_SWEEP * t
    rr = PK_R + (h(i, 41) - 0.5) * 2.2
    x, z = pc(rr, a_deg)
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
            for kk in range(1, 4):
                put(x + dx, y + kk, z + dz, WALL)
            put(x + dx, y + 4, z + dz, "end_rod[facing=up]")
        parkour["finish"] = [x, y + 1, z]
    elif i % 15 == 0:
        pk_platform(x, y, z, "polished_deepslate", "polished_blackstone_pressure_plate[powered=false]")
        parkour["checkpoints"].append([x, y + 1, z])
    else:
        kind = h(i, 61)
        if i > 8 and kind < 0.14 and y <= prev[1] and dxz <= 3.7:
            put(x, y, z, "purple_stained_glass_pane[east=false,north=false,south=false,waterlogged=false,west=false]" if i % 2 else "iron_bars[east=false,north=false,south=false,waterlogged=false,west=false]")
            put(x, y - 1, z, "amethyst_block")
        elif i > 8 and kind < 0.24 and y <= prev[1] and i % 15 != 14 and i != PK_N - 1:
            put(x, y, z, "polished_blackstone_brick_slab[type=bottom,waterlogged=false]")
            prev = (x, y, z)
            pk_path.append(prev)
            prev_slab = True
            continue
        elif i > 8 and kind < 0.30 and y <= prev[1] and dxz <= 3.2:
            put(x, y, z, WALL)
        else:
            put(x, y, z, PK_MATS[i % len(PK_MATS)])
        if i % 9 == 4:
            put(x, y - 1, z, "amethyst_cluster[facing=down,waterlogged=false]")
    prev = (x, y, z)
    pk_path.append(prev)
    prev_slab = False
_j = [(math.hypot(b[0] - a[0], b[2] - a[2]), b[1] - a[1]) for a, b in zip(pk_path, pk_path[1:])]
print(f"parkour jumps: {len(_j)}, longest {max(d for d, _ in _j):.2f}, shortest {min(d for d, _ in _j):.2f}, max rise {max(r for _, r in _j)}")

# ======================================================================= floating islets (symmetric ring)
for i, (ang, dist, dy, r) in enumerate(((-150, 72, 8, 6), (-30, 72, 8, 6), (150, 66, -6, 5), (30, 66, -6, 5),
                                        (-100, 86, 18, 4), (-80, 86, 18, 4), (90, 70, -14, 7))):
    cx, cz = round(CX + dist * math.cos(math.radians(ang))), round(CZ + dist * math.sin(math.radians(ang)))
    island(cx, cz, r, r * 2 + 6, dy + 1, i * 31 + 5, wob=4)
    for x in range(-r - 4, r + 5):
        for z in range(-r - 4, r + 5):
            if math.hypot(x, z) <= edge_r(x, z, r, i * 31 + 5, 4):
                put(cx + x, dy, cz + z, "grass_block[snowy=false]")
    if r >= 6:
        cherry(cx, cz, dy + 1, 1, i)
    else:
        put(cx, dy + 1, cz, "amethyst_block")
        put(cx, dy + 2, cz, "amethyst_cluster[facing=up,waterlogged=false]")
    put(cx + 2, dy + 1, cz + 1, "lantern[hanging=false,waterlogged=false]")

# ======================================================================= invisible light blocks (night glow on the gardens)
for x in range(-R, R + 1, 5):
    for z in range(CZ - R, CZ + R + 1, 5):
        if not in_island(x, z):
            continue
        y = FLOOR + 3
        if get(x, y, z) is None and get(x, y - 1, z) is None:
            put(x, y, z, "light[level=12,waterlogged=false]")

# ======================================================================= the sky sign (same wordmark as before)
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
    sz = -126
    def word(text, scale, x0, ytop, face):
        cols = []
        x = x0
        for ch in text:
            for r, row in enumerate(FONT[ch]):
                for c, bit in enumerate(row):
                    if bit != "1":
                        continue
                    for dx in range(scale):
                        for dy in range(scale):
                            cols.append((x + c * scale + dx, ytop - r * scale - dy))
            x += 6 * scale
        for (px, py) in cols:
            out[(px, py, sz)] = "minecraft:" + face
            out[(px, py, sz - 1)] = "minecraft:" + face
            t = (px + 75) / 150
            sh = SIGN_GRAD[max(0, min(len(SIGN_GRAD) - 1, int(t * len(SIGN_GRAD))))]
            if (px + 1, py - 1, sz - 2) not in out:
                out[(px + 1, py - 1, sz - 2)] = "minecraft:" + sh
            out[(px + 1, py - 1, sz - 3)] = "minecraft:" + sh
        return cols, x - scale
    big = 3
    width = 8 * 6 * big - big
    x0 = -width // 2
    top = 104
    cols, xend = word("RELAPSED", big, x0, top, "white_concrete")
    bar_y = top - 7 * big - 2
    for x in range(x0, xend + 1):
        t = (x - x0) / max(1, xend - x0)
        b = SIGN_GRAD[min(len(SIGN_GRAD) - 1, int(t * len(SIGN_GRAD)))]
        for y in (bar_y, bar_y - 1):
            out[(x, y, sz)] = "minecraft:" + b
            out[(x, y, sz - 1)] = "minecraft:" + b
    mc_scale = 2
    mc_w = 2 * 6 * mc_scale - mc_scale
    mcols, _ = word("MC", mc_scale, xend - mc_w + 1, bar_y - 3, "white_concrete")
    for (px, py) in cols + mcols + [(x, bar_y) for x in range(x0, xend + 1)]:
        if px % 3 == 0 and py % 3 == 0:
            out[(px, py, sz + 2)] = "minecraft:light[level=15,waterlogged=false]"


# (sky sign removed in v14: the floating logo hologram is the brand now)

# ======================================================================= write
def write(blocks, out=OUT, preview_view="se"):
    blocks = {k: v for k, v in blocks.items() if v != "minecraft:air"}
    json.dump(parkour, open(os.path.join(RES, "parkour.json"), "w"))
    palette, idx, lines = [], {}, []
    for (x, y, z), b in sorted(blocks.items(), key=lambda kv: (kv[0][1], kv[0][0], kv[0][2])):
        if b not in idx:
            idx[b] = len(palette)
            palette.append(b)
        lines.append(f"{x} {y} {z} {idx[b]}")
    with gzip.open(out, "wt") as f:
        f.write("|".join(palette) + "\n" + "\n".join(lines) + "\n")
    xs = [k[0] for k in blocks]; ys = [k[1] for k in blocks]; zs = [k[2] for k in blocks]
    print(f"{len(lines)} blocks, {len(palette)} types, x {min(xs)}..{max(xs)} y {min(ys)}..{max(ys)} z {min(zs)}..{max(zs)} -> {os.path.getsize(out)//1024} KB")
    print("parkour:", parkour["start"], len(parkour["checkpoints"]), "checkpoints ->", parkour["finish"])
    if "--preview" in sys.argv:
        sys.path.insert(0, os.path.dirname(__file__))
        import isorender
        o = sys.argv[sys.argv.index("--preview") + 1]
        isorender.render(blocks, o, view=preview_view, S=4)
        print("preview", o)


if __name__ == "__main__":
    write(blocks)
    print("discord pavilion:", DISCORD, " portals:", [(r, x, PORTAL_Z) for r, x in PORTALS])
