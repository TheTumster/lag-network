"""RelapsedMC Skyblock spawn v3: "the Shattered World" -> plugins/lag-core/src/main/resources/spawn-skyblock.txt.gz

Lore: when the Corrupted world collapsed, the pieces that didn't fall became islands. Spawn is the biggest piece left:
the Core, a cracked crystal still floating over what was the world's heart, with the districts that broke off around it.

(0,0,0) = spawn floor centre, players spawn facing north (-Z). Floor is y=0, players stand at y=1.
  centre : the Core - dais + floating cracked crystal, the Collector stands on the north edge of the dais
  west   : Getting Started kiosk on the core plaza (panels.yml skyblock: at [-13.92, 2.05, 0.5], facing east)
  N  (0,-58)    : HUB portal island
  E  (58,0)     : Cage Sanctum - 13 cages in a ring (laGSkyblock puts the caged mobs + the Cage Keeper in them)
  W  (-58,0)    : Gens Forge - 8 ore pedestals (gen tiers), the Gen Smith
  NW (-41,-41)  : Market - stalls, the Merchant (/market)
  NE (41,-41)   : Hall of Champions - leaderboard wall + podium (laGSkyblock writes island top / champions on it)
  SW (-41,41)   : Season Obelisk - /season, lore
  SE (41,41)    : Crate Vaults - 4 vault pedestals (crates, phase 4)
  S  (0,48)     : the Rift - a hole down to the Warzone (y -44), a water pool catches the drop
  below         : the Warzone - shattered PvP arena under the core (centre (0,-44,20)), KOTH hill in the middle
Everything stays inside z <= 60 (the old Pit at z 64..98 is removed by laGSkyblock) and |x| <= 76.
Run: python3 tools/hub/genspawn_skyblock.py [--preview dir]
"""
import gzip, math, os, sys

OUT = os.path.join(os.path.dirname(__file__), "..", "..", "plugins", "lag-core", "src", "main", "resources", "spawn-skyblock.txt.gz")
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
    B[(int(x), int(y), int(z))] = b if b.startswith("minecraft:") else "minecraft:" + b


def get(x, y, z):
    return B.get((x, y, z), "").replace("minecraft:", "").split("[")[0]


def free(x, y, z):
    return (x, y, z) not in B


def stairs(mat, facing, half="bottom"):
    return f"{mat}_stairs[facing={facing},half={half},shape=straight,waterlogged=false]"


def slab(mat, typ="bottom"):
    return f"{mat}_slab[type={typ},waterlogged=false]"


LANTERN = "lantern[hanging=false,waterlogged=false]"
SOUL_LANTERN = "soul_lantern[hanging=false,waterlogged=false]"
HSOUL = "soul_lantern[hanging=true,waterlogged=false]"
CHAIN = "iron_chain[axis=y,waterlogged=false]"
ROD = "end_rod[facing=up]"
BARS = "iron_bars[east=false,north=false,south=false,waterlogged=false,west=false]"
GRASS = "grass_block[snowy=false]"


def bars(e, n, s, w):
    t = lambda v: "true" if v else "false"
    return f"iron_bars[east={t(e)},north={t(n)},south={t(s)},waterlogged=false,west={t(w)}]"


def pane(color, e, n, s, w):
    t = lambda v: "true" if v else "false"
    return f"{color}_stained_glass_pane[east={t(e)},north={t(n)},south={t(s)},waterlogged=false,west={t(w)}]"


# ====================================================================== floating rock (any island)
ROCK = ["deepslate", "deepslate", "cobbled_deepslate", "tuff", "deepslate", "polished_deepslate", "blackstone"]


def island(cx, cy, cz, r, seed, top="plaza", depth=None, rough=2.5):
    """A floating fragment: jagged edge, inverted-cone underside of corrupted rock with crystal veins.
    Returns the set of (x, z) columns it covers. The top layer is laid by the caller (top='none') or here."""
    cols = set()
    depth = depth or int(r * 0.9) + 4
    for dx in range(-r - 4, r + 5):
        for dz in range(-r - 4, r + 5):
            a = math.atan2(dz, dx)
            er = r + (vn(a * 7 + seed, seed, 1.4, seed) - 0.5) * rough * 2
            d = math.hypot(dx, dz)
            if d > er:
                continue
            x, z = cx + dx, cz + dz
            cols.add((x, z))
            k = 1 - d / er
            dep = int(2 + depth * max(0.0, k) ** 1.3 + (vn(x * 1.3, z * 1.3, 3, seed + 1) - 0.5) * 4)
            dep = max(1, dep)
            for y in range(cy - dep, cy):
                n = h(x, y, z, seed)
                if n < 0.035:
                    b = "crying_obsidian"
                elif n < 0.06:
                    b = "amethyst_block"
                elif y > cy - 3 and d > er - 2.5 and h(x, z, seed, 9) < 0.5:
                    b = "sculk" if h(x, z, seed, 4) < 0.5 else "moss_block"
                else:
                    b = ROCK[int(vn(x + y * 0.7, z - y * 0.5, 4, seed + 3) * len(ROCK)) % len(ROCK)]
                put(x, y, z, b)
            # underside hangers
            bot = cy - dep - 1
            r_ = h(x, z, seed, 55)
            if dep > 6 and r_ < 0.05:
                put(x, bot + 1, z, "amethyst_block")
                put(x, bot, z, "amethyst_cluster[facing=down,waterlogged=false]")
            elif dep > 4 and r_ < 0.10:
                ln = 1 + int(h(x, z, seed, 56) * 3)
                names = {1: ["tip"], 2: ["frustum", "tip"], 3: ["base", "frustum", "tip"]}[ln]
                put(x, bot + 1, z, "dripstone_block")
                for i, th in enumerate(names):
                    put(x, bot - i, z, f"pointed_dripstone[thickness={th},vertical_direction=down,waterlogged=false]")
            elif r_ < 0.15:
                put(x, bot, z, "sculk_vein[down=false,east=false,north=false,south=false,up=true,waterlogged=false,west=false]")
            if top == "rock":
                put(x, cy, z, ROCK[int(h(x, z, seed, 2) * 3)])
    return cols


def plaza_floor(x, z, cx, cz, r, seed):
    """Deepslate tile plaza with brand inlay rings and a cracked, corrupted rim."""
    d = math.hypot(x - cx, z - cz)
    if d > r - 1.2 and h(x, z, seed, 21) < 0.45:
        return "sculk" if h(x, z, seed, 22) < 0.5 else "moss_block"
    n = h(x, z, seed, 23)
    if n < 0.05:
        return "cracked_deepslate_tiles"
    if n < 0.09:
        return "cracked_polished_blackstone_bricks"
    return "deepslate_tiles" if ((x + z) & 1) == 0 else "polished_deepslate"


def lamp_post(x, y, z, soul=True):
    put(x, y, z, "polished_blackstone_wall[east=none,north=none,south=none,up=true,waterlogged=false,west=none]")
    put(x, y + 1, z, "polished_blackstone_wall[east=none,north=none,south=none,up=true,waterlogged=false,west=none]")
    put(x, y + 2, z, "polished_blackstone_wall[east=none,north=none,south=none,up=true,waterlogged=false,west=none]")
    put(x, y + 3, z, SOUL_LANTERN if soul else LANTERN)


def shards_around(cx, cy, cz, r, n, seed, mats=("amethyst_block", "purple_stained_glass", "cyan_stained_glass", "crying_obsidian")):
    """Small floating crystal shards in a ring (decor)."""
    for i in range(n):
        a = i / n * math.tau + h(i, seed) * 0.5
        rr = r + h(i, seed, 2) * 3
        x, z = int(round(cx + math.cos(a) * rr)), int(round(cz + math.sin(a) * rr))
        y = cy + int(h(i, seed, 3) * 6)
        m = mats[int(h(i, seed, 4) * len(mats))]
        put(x, y, z, m)
        if h(i, seed, 5) < 0.5:
            put(x, y + 1, z, m)
        if h(i, seed, 6) < 0.3:
            put(x + 1, y, z, m)


# ====================================================================== the Core (centre island)
CORE_R = 27
core_cols = island(0, 0, 0, CORE_R, 11, top="none", depth=28, rough=3.0)
PLAZA_R = 18.5
for (x, z) in core_cols:
    d = math.hypot(x, z)
    if d <= PLAZA_R:
        put(x, 0, z, plaza_floor(x, z, 0, 0, PLAZA_R + 1.5, 11))
    else:
        # the old world that survived: grass and moss, with sculk creeping in from the edge
        n = vn(x, z, 5, 12)
        b = GRASS if n > 0.38 else ("moss_block" if n > 0.22 else "sculk")
        if d > CORE_R - 2.5 and h(x, z, 13) < 0.4:
            b = "sculk"
        put(x, 0, z, b)
        put(x, -1, z, "dirt" if b != "sculk" else "deepslate")
        if b in (GRASS, "moss_block") and free(x, 1, z):
            f = h(x, z, 14)
            if f < 0.18:
                put(x, 1, z, "short_grass")
            elif f < 0.22:
                put(x, 1, z, ["allium", "azure_bluet", "cornflower", "pink_petals[facing=north,flower_amount=3]"][int(h(x, z, 15) * 4)])
            elif f < 0.235:
                put(x, 1, z, "azalea")

# inlay rings: cyan ring at r 9, purple ring at r 17, both broken (cracked world)
for (x, z) in core_cols:
    d = math.hypot(x, z)
    if 8.5 <= d < 9.5 and h(x, z, 31) > 0.08:
        put(x, 0, z, "warped_planks" if False else "cyan_terracotta")
    if 16.5 <= d < 17.5 and h(x, z, 32) > 0.12:
        put(x, 0, z, "purple_terracotta")
    if 9.5 <= d < 10.3 and h(x, z, 33) < 0.18:
        put(x, 0, z, "light_blue_stained_glass")
# fault cracks running out from the dais (crying obsidian seams)
for k in range(5):
    a = k / 5 * math.tau + 0.4
    for t in range(7, 25):
        x = int(round(math.cos(a + math.sin(t * 0.4) * 0.08) * t))
        z = int(round(math.sin(a + math.sin(t * 0.4) * 0.08) * t))
        if (x, z) in core_cols and h(x, z, k, 34) < 0.8:
            put(x, 0, z, "crying_obsidian")

# the dais: two raised rings round the centre
for (x, z) in core_cols:
    d = math.hypot(x, z)
    if d <= 6.5:
        put(x, 1, z, "polished_blackstone_bricks" if d > 5.5 else ("chiseled_polished_blackstone" if d <= 1.5 else "polished_deepslate"))
        put(x, 0, z, "deepslate_tiles")
    if d <= 4.5:
        put(x, 1, z, "polished_deepslate" if d > 1.5 else "chiseled_polished_blackstone")
# dais steps (four sides)
for s in range(-2, 3):
    put(s, 1, -7, stairs("polished_blackstone_brick", "south"))
    put(s, 1, 7, stairs("polished_blackstone_brick", "north"))
    put(-7, 1, s, stairs("polished_blackstone_brick", "east"))
    put(7, 1, s, stairs("polished_blackstone_brick", "west"))
# glowing core under the floor centre (shows through the glass)
put(0, 1, 0, "purple_stained_glass")
put(0, 0, 0, "crying_obsidian")
put(0, -1, 0, "amethyst_block")
for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
    put(dx, 1, dz, "cyan_stained_glass")
    put(dx, 0, dz, "sea_lantern")
# rim lights on the dais
for k in range(12):
    a = k / 12 * math.tau
    x, z = int(round(math.cos(a) * 6)), int(round(math.sin(a) * 6))
    if abs(x) > 2 or abs(z) > 2:
        put(x, 2, z, ROD)

# the crystal: a floating, cracked octahedron (amethyst core, purple glass skin, crying obsidian crack band)
CY0, CY1 = 8, 30
mid = (CY0 + CY1) / 2
for y in range(CY0, CY1 + 1):
    t = 1 - abs(y - mid) / ((CY1 - CY0) / 2)
    rad = 0.6 + t * 6.2
    for x in range(-8, 9):
        for z in range(-8, 9):
            d = abs(x) + abs(z) * 0.85
            if d > rad:
                continue
            band = abs(y - (mid + 1)) < 0.6 and ((x + z + y) % 3 != 0)
            if d > rad - 1.1:
                b = "crying_obsidian" if band else ("purple_stained_glass" if h(x, y, z, 41) < 0.7 else "magenta_stained_glass")
            else:
                b = "amethyst_block" if h(x, y, z, 42) < 0.85 else "purple_stained_glass"
            put(x, y, z, b)
# the crack: a missing wedge with light leaking out
for y in range(int(mid) - 3, int(mid) + 5):
    for x in range(1, 7):
        if x - (y - mid) * 0.3 > 1.5:
            put(x, y, -x // 2, "air")
            if h(x, y, 43) < 0.5:
                put(x, y, -x // 2 + 1, "pearlescent_froglight")
B = {k: v for k, v in B.items() if v != "minecraft:air"}
# shards orbiting the crystal
shards_around(0, 10, 0, 11, 14, 44)
shards_around(0, 22, 0, 9, 8, 45)

# Getting Started kiosk (west of the dais, faces east): a framed arch, panel at [-13.92, 2.05, 0.5]
KX = -14
for z in range(-4, 6):
    for y in range(1, 8):
        edge = z in (-4, 5) or y == 7
        if edge:
            put(KX, y, z, "polished_blackstone_bricks")
    put(KX - 1, 1, z, "polished_blackstone_bricks")
for y in range(1, 7):
    for z in range(-3, 5):
        put(KX, y, z, "black_concrete")  # backing behind the panel (panel draws on its east face)
for z in range(-5, 7):
    put(KX, 8, z, slab("polished_blackstone_brick"))
put(KX + 1, 7, -4, HSOUL)
put(KX + 1, 7, 5, HSOUL)
for y in range(1, 8):
    put(KX, y, -5, "cyan_stained_glass" if y % 2 else "purple_stained_glass")
    put(KX, y, 6, "cyan_stained_glass" if y % 2 else "purple_stained_glass")

# crate vault pads in the plaza corners? (no: crates live on their own island, SE)

def cherry(cx, cz, seed, hgt=6):
    for y in range(1, hgt):
        put(cx, y, cz, "cherry_log[axis=y]")
    lean = (1 if h(seed, 1) < 0.5 else -1)
    for y in range(hgt - 2, hgt + 1):
        put(cx + lean, y, cz, "cherry_log[axis=y]")
    for x in range(cx - 4, cx + 5):
        for z in range(cz - 4, cz + 5):
            for y in range(hgt - 1, hgt + 4):
                d2 = (x - cx - lean * 0.5) ** 2 + (z - cz) ** 2 + ((y - hgt - 1) * 1.7) ** 2
                if d2 < 15 and h(x, y, z, seed) > 0.12 and free(x, y, z):
                    put(x, y, z, "cherry_leaves[distance=1,persistent=true,waterlogged=false]")
    for x in range(cx - 3, cx + 4):
        for z in range(cz - 3, cz + 4):
            if free(x, 1, z) and get(x, 0, z) in ("grass_block", "moss_block") and h(x, z, seed, 9) < 0.35:
                put(x, 1, z, "pink_petals[facing=north,flower_amount=4]")


for k, a in enumerate((0.42, 1.95, 3.55, 5.05)):
    cherry(int(round(math.cos(a) * 22.5)), int(round(math.sin(a) * 22.5)), 70 + k, 6 + k % 2)


def ruin(cx, cz, ang, seed):
    # a broken stretch of the old world's wall on the rim: 7 long, crumbling toward the ends, moss and vines
    tx, tz = -math.sin(ang), math.cos(ang)
    for i in range(-3, 4):
        x, z = int(round(cx + tx * i)), int(round(cz + tz * i))
        top = 5 - abs(i) - int(h(i, seed) * 2)
        for y in range(1, max(1, top) + 1):
            b = "stone_bricks" if h(x, y, z, seed) > 0.35 else ("mossy_stone_bricks" if h(x, y, z, seed, 2) < 0.6 else "cracked_stone_bricks")
            put(x, y, z, b)
        if top >= 3 and h(i, seed, 3) < 0.5:
            put(x, top + 1, z, "moss_carpet")


for k, a in enumerate((1.18, 2.75, 4.3, 5.85)):
    ruin(math.cos(a) * 25, math.sin(a) * 25, a, 90 + k)

# lamp posts round the plaza ring
for k in range(10):
    a = k / 10 * math.tau + 0.31
    x, z = int(round(math.cos(a) * 18)), int(round(math.sin(a) * 18))
    if (x, z) in core_cols and abs(x) > 4 and abs(z) > 4 and free(x, 1, z):
        lamp_post(x, 1, z)

for k in range(9):
    a = k / 9 * math.tau + 0.2
    rr = 33 + h(k, 61) * 6
    x, z = int(round(math.cos(a) * rr)), int(round(math.sin(a) * rr))
    if z < 55 and min(abs(math.atan2(z, x) - math.atan2(dz, dx)) for dx, dz in ((0, -1), (1, 0), (-1, 0), (-1, -1), (1, -1), (-1, 1), (1, 1), (0, 1))) > 0.2:
        island(x, -6 + int(h(k, 62) * 14), z, 2 + int(h(k, 63) * 2), 600 + k, top="rock", depth=5)

# ====================================================================== bridges
def bridge(x0, z0, x1, z1, w=2, seed=0):
    """A stone bridge (half-width w) between two points at y=0, with posts, chains and a few cracks."""
    L = math.hypot(x1 - x0, z1 - z0)
    ux, uz = (x1 - x0) / L, (z1 - z0) / L
    px, pz = -uz, ux
    steps = int(L * 2)
    for i in range(steps + 1):
        t = i / steps
        cx, cz = x0 + (x1 - x0) * t, z0 + (z1 - z0) * t
        for o in range(-w - 1, w + 2):
            x, z = int(round(cx + px * o)), int(round(cz + pz * o))
            if abs(o) <= w:
                if free(x, 0, z) or get(x, 0, z) in ("sculk", "moss_block", "grass_block"):
                    B.pop((x, 1, z), None)
                    n = h(x, z, seed)
                    put(x, 0, z, "cracked_polished_blackstone_bricks" if n < 0.1 else ("polished_blackstone_bricks" if abs(o) < w else "deepslate_tile_slab[type=top,waterlogged=false]".replace("deepslate_tile_slab[type=top,waterlogged=false]", "deepslate_tiles")))
                    if free(x, -1, z):
                        put(x, -1, z, "deepslate_brick_slab[type=top,waterlogged=false]")
            else:
                # rails: low wall with gaps
                if free(x, 0, z) and h(x, z, seed, 3) > 0.12:
                    put(x, 0, z, "polished_blackstone_bricks")
                    if free(x, 1, z) and h(x, z, seed, 4) > 0.25:
                        put(x, 1, z, "polished_blackstone_brick_wall[east=low,north=low,south=low,up=true,waterlogged=false,west=low]")
        # lamp posts every ~9 blocks on alternating sides
        if i % 18 == 9:
            side = 1 if (i // 18) % 2 else -1
            x, z = int(round(cx + px * (w + 1) * side)), int(round(cz + pz * (w + 1) * side))
            lamp_post(x, 1, z)
        # hanging chains under the bridge
        if i % 12 == 0:
            x, z = int(round(cx)), int(round(cz))
            for k in range(2, 2 + int(h(i, seed) * 4)):
                put(x, -k, z, CHAIN)


DIST = 58
DIAG = 41
DISTRICTS = {"hub": (0, -DIST), "sanctum": (DIST, 0), "forge": (-DIST, 0), "market": (-DIAG, -DIAG), "champions": (DIAG, -DIAG),
             "season": (-DIAG, DIAG), "crates": (DIAG, DIAG), "rift": (0, 48)}
for name, (dx, dz) in DISTRICTS.items():
    d = math.hypot(dx, dz)
    ux, uz = dx / d, dz / d
    start, end = CORE_R - 3, d - 11
    bridge(ux * start, uz * start, ux * end, uz * end, 2, hash(name) % 997)

# ====================================================================== N: Hub portal island
HX, HZ = DISTRICTS["hub"]
cols = island(HX, 0, HZ, 13, 101, top="none", depth=14)
for (x, z) in cols:
    put(x, 0, z, plaza_floor(x, z, HX, HZ, 13, 101))
# raised dais + steps, then the portal arch (faces south toward the core)
for x in range(HX - 7, HX + 8):
    for z in range(HZ - 6, HZ + 3):
        if (x, z) in cols:
            put(x, 1, z, "polished_blackstone_bricks" if abs(x - HX) == 7 or z == HZ - 6 else "polished_deepslate")
for x in range(HX - 3, HX + 4):
    put(x, 1, HZ + 3, stairs("polished_blackstone_brick", "north"))
PZ = HZ - 3
for x in range(HX - 4, HX + 5):
    for y in range(2, 13):
        ax = abs(x - HX)
        frame = ax == 4 or y == 12 or (y == 2)
        if frame:
            put(x, y, PZ, "crying_obsidian" if (y + ax) % 4 == 0 else "obsidian")
        else:
            put(x, y, PZ, "nether_portal[axis=x]")
# arch crown + pillars
for y in range(2, 15):
    for sx in (HX - 6, HX + 6):
        put(sx, y, PZ, "polished_blackstone_bricks" if y % 4 else "chiseled_polished_blackstone")
    if y >= 13:
        for x in range(HX - 6, HX + 7):
            if y == 13 or abs(x - HX) <= 6 - (y - 13) * 3:
                put(x, y, PZ, "polished_blackstone_bricks")
put(HX - 6, 15, PZ, SOUL_LANTERN)
put(HX + 6, 15, PZ, SOUL_LANTERN)
put(HX, 15, PZ, "amethyst_block")
put(HX, 16, PZ, ROD)
shards_around(HX, 4, PZ, 9, 6, 102)

# ====================================================================== E: Cage Sanctum
SX, SZ = DISTRICTS["sanctum"]
cols = island(SX, 0, SZ, 15, 201, top="none", depth=15)
for (x, z) in cols:
    put(x, 0, z, plaza_floor(x, z, SX, SZ, 15, 201))
CAGES = []
for k in range(13):
    a = math.radians(210) + k / 12 * math.radians(300)  # ring open toward the bridge (west)
    cx, cz = int(round(SX + math.cos(a) * 10)), int(round(SZ + math.sin(a) * 10))
    CAGES.append((cx, cz))
    # pedestal + 3x3 cage of bars, glass roof, soul lantern
    for dx in (-1, 0, 1):
        for dz in (-1, 0, 1):
            put(cx + dx, 0, cz + dz, "polished_blackstone_bricks")
            put(cx + dx, 1, cz + dz, "chiseled_tuff_bricks" if dx == 0 and dz == 0 else "polished_tuff")
            for y in (2, 3, 4):
                if abs(dx) == 1 or abs(dz) == 1:
                    e = dx == -1 or (dx == 0 and abs(dz) == 1)
                    w = dx == 1 or (dx == 0 and abs(dz) == 1)
                    n_ = dz == 1 or (dz == 0 and abs(dx) == 1)
                    s_ = dz == -1 or (dz == 0 and abs(dx) == 1)
                    if abs(dx) == 1 and abs(dz) == 1:
                        put(cx + dx, y, cz + dz, "polished_blackstone_wall[east=none,north=none,south=none,up=true,waterlogged=false,west=none]")
                    else:
                        put(cx + dx, y, cz + dz, bars(e, n_, s_, w))
            put(cx + dx, 5, cz + dz, "polished_blackstone_brick_slab[type=bottom,waterlogged=false]" if abs(dx) + abs(dz) else "purple_stained_glass")
    put(cx, 6, cz, SOUL_LANTERN)
# the Keeper's altar in the middle
for x in range(SX - 2, SX + 3):
    for z in range(SZ - 2, SZ + 3):
        put(x, 1, z, "polished_deepslate" if abs(x - SX) < 2 and abs(z - SZ) < 2 else "polished_blackstone_bricks")
put(SX + 1, 2, SZ, "spawner")
put(SX + 1, 3, SZ, "amethyst_cluster[facing=up,waterlogged=false]")

# ====================================================================== W: Gens Forge
FX, FZ = DISTRICTS["forge"]
cols = island(FX, 0, FZ, 15, 301, top="none", depth=15)
for (x, z) in cols:
    d = math.hypot(x - FX, z - FZ)
    put(x, 0, z, "stone_bricks" if h(x, z, 302) > 0.2 else ("cracked_stone_bricks" if h(x, z, 303) < 0.5 else "mossy_stone_bricks"))
GEN_ORES = ["cobblestone", "coal_ore", "iron_ore", "gold_ore", "lapis_ore", "diamond_ore", "emerald_ore", "ancient_debris"]
PEDESTALS = []
for k, ore in enumerate(GEN_ORES):
    a = math.radians(45) + k / 7 * math.radians(270)  # ring open toward the core (east)
    px_, pz_ = int(round(FX + math.cos(a) * 10)), int(round(FZ + math.sin(a) * 10))
    PEDESTALS.append((px_, pz_))
    hgt = 1 + k // 2
    for y in range(1, hgt + 1):
        put(px_, y, pz_, "polished_andesite" if y < hgt else "chiseled_stone_bricks")
    put(px_, hgt + 1, pz_, ore)
    put(px_, hgt + 2, pz_, LANTERN if k < 7 else SOUL_LANTERN)
# the forge: a lava trough with a water spout either side and a cobble wall (a cobble gen as a monument)
for x in range(FX - 3, FX + 4):
    for z in range(FZ - 2, FZ + 3):
        put(x, 1, z, "polished_blackstone_bricks" if abs(x - FX) == 3 or abs(z - FZ) == 2 else "air")
B = {k: v for k, v in B.items() if v != "minecraft:air"}
for x in range(FX - 2, FX + 3):
    put(x, 0, FZ - 1, "magma_block")
    put(x, 0, FZ, "lava")
    put(x, 0, FZ + 1, "magma_block")
for x in (FX - 2, FX + 2):
    put(x, 1, FZ, "cobblestone")
put(FX, 1, FZ - 2, "anvil[facing=east]")
put(FX - 3, 2, FZ - 2, "blast_furnace[facing=east,lit=true]")
put(FX + 3, 2, FZ + 2, "smithing_table")
for x in (FX - 3, FX + 3):
    for z in (FZ - 2, FZ + 2):
        put(x, 2, z, "polished_blackstone_brick_wall[east=none,north=none,south=none,up=true,waterlogged=false,west=none]") if free(x, 2, z) else None
# chimney
for y in range(1, 9):
    for x in (FX - 1, FX, FX + 1):
        for z in (FZ + 4, FZ + 5):
            edge = x != FX
            put(x, y, z, "bricks" if edge or y > 6 else "bricks")
put(FX, 9, FZ + 4, "campfire[facing=north,lit=true,signal_fire=true,waterlogged=false]")

# ====================================================================== NW: Market
MX, MZ = DISTRICTS["market"]
cols = island(MX, 0, MZ, 13, 401, top="none", depth=13)
for (x, z) in cols:
    put(x, 0, z, "spruce_planks" if abs(x - MX) <= 7 and abs(z - MZ) <= 7 and h(x, z, 402) > 0.1 else (GRASS if h(x, z, 403) > 0.3 else "moss_block"))
AWN = ["cyan_wool", "white_wool", "purple_wool", "white_wool"]
for k, (ox, oz) in enumerate(((-5, -5), (5, -5), (-5, 5), (5, 5))):
    sx_, sz_ = MX + ox, MZ + oz
    for dx in (-2, 2):
        for dz in (-2, 2):
            for y in range(1, 4):
                put(sx_ + dx, y, sz_ + dz, "spruce_fence[east=false,north=false,south=false,waterlogged=false,west=false]")
    for dx in range(-2, 3):
        for dz in range(-2, 3):
            put(sx_ + dx, 4, sz_ + dz, AWN[(dx + 2 + k) % 4] if abs(dz) < 2 else slab("spruce"))
    for dx in range(-1, 2):
        put(sx_ + dx, 1, sz_ - 1, "barrel[facing=up,open=false]")
    put(sx_, 2, sz_ - 1, ["hay_block[axis=y]", "melon", "pumpkin", "composter[level=7]"][k])
    put(sx_ + 1, 2, sz_ - 1, LANTERN)
# cherry tree in the middle of the market
for y in range(1, 6):
    put(MX, y, MZ, "cherry_log[axis=y]")
for x in range(MX - 3, MX + 4):
    for z in range(MZ - 3, MZ + 4):
        for y in range(5, 9):
            if (x - MX) ** 2 + (z - MZ) ** 2 + (y - 6.5) ** 2 * 2.2 < 12 and h(x, y, z, 404) > 0.15 and free(x, y, z):
                put(x, y, z, "cherry_leaves[distance=1,persistent=true,waterlogged=false]")

# ====================================================================== NE: Hall of Champions
CX_, CZ_ = DISTRICTS["champions"]
cols = island(CX_, 0, CZ_, 13, 501, top="none", depth=13)
for (x, z) in cols:
    put(x, 0, z, "polished_deepslate" if h(x, z, 502) > 0.15 else "deepslate_tiles")
# the board: a wall facing the core (south-west); built axis-aligned facing south for simple panels
WZ = CZ_ - 6
for x in range(CX_ - 8, CX_ + 9):
    for y in range(1, 13):
        edge = abs(x - CX_) == 8 or y in (1, 12)
        put(x, y, WZ, "quartz_pillar[axis=y]" if edge and abs(x - CX_) == 8 else ("smooth_quartz" if edge else "black_concrete"))
    put(x, 13, WZ, "quartz_slab[type=bottom,waterlogged=false]")
for y in (4, 8):
    put(CX_ - 8, y, WZ + 1, "gold_block")
    put(CX_ + 8, y, WZ + 1, "gold_block")
# podium: 2nd (left), 1st (middle), 3rd (right)
for x, hgt, mat in ((CX_ - 3, 2, "iron_block"), (CX_, 3, "gold_block"), (CX_ + 3, 1, "copper_block")):
    for dx in (-1, 0, 1):
        for y in range(1, hgt + 1):
            put(x + dx, y, CZ_ + 1, mat)
            put(x + dx, y, CZ_ + 2, mat)
for x in range(CX_ - 5, CX_ + 6):
    put(x, 1, CZ_ + 3, stairs("polished_blackstone_brick", "south")) if free(x, 1, CZ_ + 3) else None
for x in (CX_ - 6, CX_ + 6):
    lamp_post(x, 1, CZ_ + 2, soul=False)

# ====================================================================== SW: Season Obelisk
OX, OZ = DISTRICTS["season"]
cols = island(OX, 0, OZ, 12, 601, top="none", depth=12)
for (x, z) in cols:
    d = math.hypot(x - OX, z - OZ)
    put(x, 0, z, "end_stone_bricks" if d < 5 else ("purpur_block" if (int(d) % 3 == 0) else "polished_deepslate"))
for y in range(1, 17):
    w = 1 if y < 14 else 0
    for x in range(OX - w, OX + w + 1):
        for z in range(OZ - w, OZ + w + 1):
            put(x, y, z, "crying_obsidian" if (y % 5 == 0) else "obsidian")
put(OX, 17, OZ, "amethyst_block")
put(OX, 18, OZ, ROD)
for dx, dz in ((2, 0), (-2, 0), (0, 2), (0, -2)):
    put(OX + dx, 1, OZ + dz, "purpur_pillar[axis=y]")
    put(OX + dx, 2, OZ + dz, SOUL_LANTERN)
shards_around(OX, 6, OZ, 6, 8, 602)

# ====================================================================== SE: Crate Vaults
VX, VZ = DISTRICTS["crates"]
cols = island(VX, 0, VZ, 12, 701, top="none", depth=12)
for (x, z) in cols:
    put(x, 0, z, "polished_tuff" if h(x, z, 702) > 0.15 else "tuff_bricks")
VAULTS = []
for ox, oz in ((-4, -4), (4, -4), (-4, 4), (4, 4)):
    vx, vz = VX + ox, VZ + oz
    VAULTS.append((vx, vz))
    for dx in (-1, 0, 1):
        for dz in (-1, 0, 1):
            put(vx + dx, 1, vz + dz, "chiseled_tuff_bricks" if dx == 0 and dz == 0 else "tuff_brick_slab[type=bottom,waterlogged=false]")
    put(vx, 2, vz, "vault[facing=north,ominous=false,vault_state=inactive]")
    put(vx, 4, vz, "waxed_copper_bulb[lit=true,powered=false]")
put(VX, 1, VZ, "chiseled_copper")
put(VX, 2, VZ, "heavy_core")

# ====================================================================== S: the Rift (drop to the Warzone)
RX, RZ = DISTRICTS["rift"]
cols = island(RX, 0, RZ, 11, 801, top="none", depth=10)
for (x, z) in cols:
    put(x, 0, z, plaza_floor(x, z, RX, RZ, 11, 801))
for (x, z) in list(cols):
    d = math.hypot(x - RX, z - RZ)
    if d <= 4.6:
        for y in range(-14, 1):
            B.pop((x, y, z), None)  # the hole
    elif d <= 5.8:
        put(x, 0, z, "crying_obsidian")
        put(x, 1, z, "red_nether_brick_wall[east=low,north=low,south=low,up=true,waterlogged=false,west=low]" if h(x, z, 802) > 0.45 else "air")
        for y in range(-10, 0):
            if (x, y, z) in B:
                put(x, y, z, "crying_obsidian" if h(x, y, z, 803) < 0.3 else "blackstone")
B = {k: v for k, v in B.items() if v != "minecraft:air"}
# warning pillars
for dx in (-7, 7):
    for y in range(1, 6):
        put(RX + dx, y, RZ - 3, "red_nether_bricks" if y % 2 else "blackstone")
    put(RX + dx, 6, RZ - 3, "redstone_lamp[lit=true]")
    put(RX + dx, 7, RZ - 3, "magma_block")

# ====================================================================== the Warzone (below)
WY = -44
WCX, WCZ = 0, 20
wz_cols = island(WCX, WY, WCZ, 40, 901, top="none", depth=16, rough=5)
WH = {}
for (x, z) in wz_cols:
    d = math.hypot(x - WCX, z - WCZ)
    lift = int(max(0.0, vn(x, z, 9, 904) - 0.55) * 7) if d > 13 else 0
    WH[(x, z)] = WY + lift
    p = vn(x, z, 4, 902)
    q = vn(x + 50, z, 3, 905)
    if q > 0.8:
        b = "magma_block"
    elif p < 0.25:
        b = "sculk"
    elif p < 0.4:
        b = "blackstone"
    elif p < 0.6:
        b = "polished_blackstone_bricks" if h(x, z, 906) > 0.15 else "cracked_polished_blackstone_bricks"
    elif p < 0.75:
        b = "deepslate_bricks" if h(x, z, 906) > 0.2 else "cracked_deepslate_bricks"
    else:
        b = "tuff"
    if h(x, z, 907) < 0.02:
        b = "crying_obsidian"
    for y in range(WY, WY + lift + 1):
        put(x, y, z, b if y == WY + lift else "blackstone")
# landing pool under the Rift (3 deep), rimmed
for x in range(RX - 4, RX + 5):
    for z in range(RZ - 4, RZ + 5):
        if (x, z) in wz_cols:
            edge = abs(x - RX) == 4 or abs(z - RZ) == 4
            for y in range(WY - 3, WY + 1):
                put(x, y, z, "crying_obsidian" if edge and y == WY else ("blackstone" if edge or y == WY - 3 else "water"))
# KOTH hill in the middle: stepped platform, capture plate, purple beam
KX0, KZ0 = WCX, WCZ - 2
for step, (r, mat) in enumerate(((9, "polished_blackstone_bricks"), (7, "deepslate_tiles"), (5, "polished_blackstone_bricks"), (3, "crying_obsidian"))):
    for x in range(KX0 - r, KX0 + r + 1):
        for z in range(KZ0 - r, KZ0 + r + 1):
            if math.hypot(x - KX0, z - KZ0) <= r + 0.4:
                put(x, WY + 1 + step, z, mat)
for x in range(KX0 - 1, KX0 + 2):
    for z in range(KZ0 - 1, KZ0 + 2):
        put(x, WY + 4, z, "purple_stained_glass" if (x, z) != (KX0, KZ0) else "beacon")
put(KX0, WY + 3, KZ0, "iron_block")
for x in range(KX0 - 1, KX0 + 2):
    for z in range(KZ0 - 1, KZ0 + 2):
        put(x, WY + 2, z, "iron_block")
for k in range(8):
    a = k / 8 * math.tau
    x, z = int(round(KX0 + math.cos(a) * 9)), int(round(KZ0 + math.sin(a) * 9))
    for y in range(WY + 1, WY + 6):
        put(x, y, z, "blackstone" if y < WY + 5 else "crying_obsidian")
    put(x, WY + 6, z, SOUL_LANTERN)
# ruins: broken pillars, half arches and a raised walkway ring for height fights
for k in range(44):
    a = h(k, 911) * math.tau
    rr = 15 + h(k, 912) * 21
    x, z = int(round(WCX + math.cos(a) * rr)), int(round(WCZ + math.sin(a) * rr))
    if (x, z) not in wz_cols or math.hypot(x - RX, z - RZ) < 7:
        continue
    hg = 2 + int(h(k, 913) * 7)
    w = 1 if h(k, 914) < 0.6 else 0
    base_y = WH.get((x, z), WY)
    for y in range(base_y + 1, base_y + 1 + hg):
        for dx in range(0, w + 1):
            for dz in range(0, w + 1):
                if y < base_y + hg or h(x + dx, y, z + dz, 915) > 0.4:
                    put(x + dx, y, z + dz, ["deepslate_bricks", "cracked_deepslate_bricks", "blackstone", "polished_blackstone_bricks"][int(h(x + dx, y, z + dz, 916) * 4)])
    if h(k, 917) < 0.35:
        put(x, base_y + 1 + hg, z, SOUL_LANTERN)
    # some become low cover walls: a 5-long broken line beside the pillar
    if h(k, 918) < 0.5:
        ang = h(k, 919) * math.pi
        for i in range(1, 6):
            wx, wz_ = int(round(x + math.cos(ang) * i)), int(round(z + math.sin(ang) * i))
            if (wx, wz_) in wz_cols and h(i, k, 920) > 0.2:
                by = WH.get((wx, wz_), WY)
                for y in range(by + 1, by + 2 + int(h(i, k, 922) * 2)):
                    put(wx, y, wz_, "deepslate_bricks" if h(wx, y, wz_, 923) > 0.3 else "cracked_deepslate_bricks")
# two raised platforms (bridges over the arena) at WY+7
for (ax, az, bx, bz) in ((-30, 0, -10, 30), (30, 2, 12, 34)):
    L = math.hypot(bx - ax, bz - az)
    for i in range(int(L) + 1):
        t = i / L
        x, z = int(round(ax + (bx - ax) * t)), int(round(az + (bz - az) * t))
        for o in (-1, 0, 1):
            if h(i, o, 921) > 0.12:
                put(x + o, WY + 7, z, "polished_blackstone_bricks")
        if i % 7 == 0:
            for y in range(WY + 1, WY + 7):
                put(x, y, z, "blackstone")
    # stairs up: a stepped column at the start
    for s in range(1, 7):
        put(ax - s * (1 if ax > 0 else -1) * 0, WY + s, az + s, "polished_blackstone_bricks")
# corrupted light: shroomlights and soul fire under the arena floor glow through magma cracks
for (x, z) in wz_cols:
    top_y = WH.get((x, z), WY)
    if h(x, z, 931) < 0.008 and free(x, top_y + 1, z):
        put(x, top_y + 1, z, "soul_campfire[facing=north,lit=true,signal_fire=false,waterlogged=false]")

# ====================================================================== invisible light across walkable tops
for x in range(-80, 81, 6):
    for z in range(-75, 66, 6):
        for y0 in (0, WY):
            if (x, y0, z) in B and free(x, y0 + 3, z) and free(x, y0 + 1, z) and free(x, y0 + 2, z):
                put(x, y0 + 3, z, "light[level=13,waterlogged=false]")

# ------------------------------------------------------------------ write
assert max(z for (_, _, z) in B) <= 60, "spawn must stay north of the old Pit (z <= 60)"
palette, idx, lines = [], {}, []
for (x, y, z), b in sorted(B.items(), key=lambda kv: (kv[0][1], kv[0][0], kv[0][2])):
    if b not in idx:
        idx[b] = len(palette)
        palette.append(b)
    lines.append(f"{x} {y} {z} {idx[b]}")
with gzip.open(OUT, "wt") as f:
    f.write("|".join(palette) + "\n" + "\n".join(lines) + "\n")
print(f"skyblock: {len(lines)} blocks, {len(palette)} block types -> {os.path.getsize(OUT) // 1024} KB")
print("cages", CAGES)
print("pedestals", PEDESTALS)
print("vaults", VAULTS)
xs = [x for (x, _, _) in B]; zs = [z for (_, _, z) in B]; ys = [y for (_, y, _) in B]
print("extent x", min(xs), max(xs), "z", min(zs), max(zs), "y", min(ys), max(ys))

if "--preview" in sys.argv:
    sys.path.insert(0, os.path.dirname(__file__))
    from isorender import render
    d = sys.argv[sys.argv.index("--preview") + 1]
    os.makedirs(d, exist_ok=True)
    render(B, os.path.join(d, "sky_se.png"), view="se", S=4)
    render(B, os.path.join(d, "sky_nw.png"), view="nw", S=4)
    render({k: v for k, v in B.items() if k[1] > WY - 25 and k[1] < -10}, os.path.join(d, "warzone.png"), view="se", S=5)
