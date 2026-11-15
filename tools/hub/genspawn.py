"""RelapsedMC hub spawn generator.

Writes plugins/lag-core/src/main/resources/hub-spawn.txt.gz: a floating island the hub places on first start
(laGCore HubBuild). Coordinates are relative to the spawn floor center (0,0,0); the hub puts that at y=100.
Format: line 1 = palette (block data strings joined by '|'), then one "x y z paletteIndex" line per block.

Layout (north = -Z):
  center      beacon under purple glass (the spawn point), amethyst ring
  north z=-25 FACTIONS portal (obsidian frame, crying obsidian trim, red/purple pillars)
  south z=+25 SMP portal (obsidian frame, moss + azalea trim, green pillars)
  west/east   info stands with amethyst crystals (holograms are spawned by the plugin)
  rim         pillar ring with end rods, cyan->purple gradient bands
  around      small floating islands
Run: python3 tools/hub/genspawn.py [--preview out.png]
"""
import gzip, math, os, sys

R = 34            # island radius
DEPTH = 30        # max depth of the island underside
OUT = os.path.join(os.path.dirname(__file__), "..", "..", "plugins", "lag-core", "src", "main", "resources", "hub-spawn.txt.gz")

blocks = {}       # (x,y,z) -> block data string


def put(x, y, z, b):
    blocks[(x, y, z)] = b


def h(*a):
    """Deterministic hash -> [0,1)."""
    v = 2166136261
    for n in a:
        v ^= (int(n) * 2654435761) & 0xFFFFFFFF
        v = (v * 16777619) & 0xFFFFFFFF
    v ^= v >> 13
    v = (v * 1274126177) & 0xFFFFFFFF
    v ^= v >> 16
    return v / 2**32


def vnoise(x, z, s):
    """Smooth 2D value noise."""
    x0, z0 = math.floor(x / s), math.floor(z / s)
    fx, fz = x / s - x0, z / s - z0
    fx, fz = fx * fx * (3 - 2 * fx), fz * fz * (3 - 2 * fz)
    a, b = h(x0, z0, 7), h(x0 + 1, z0, 7)
    c, d = h(x0, z0 + 1, 7), h(x0 + 1, z0 + 1, 7)
    return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fz


# cyan -> purple, by angle around the island (matches the #38E0FF -> #B040FF brand gradient)
GRAD = ["light_blue_concrete", "cyan_concrete", "light_blue_glazed_terracotta".replace("_glazed_terracotta", "_concrete"),
        "blue_concrete", "purple_concrete", "magenta_concrete".replace("magenta", "purple"), "purpur_block"]
GRAD = ["light_blue_concrete", "cyan_concrete", "blue_concrete", "purple_concrete", "purpur_block"]


def grad_at(x, z):
    t = (math.atan2(z, x) / math.pi + 1) / 2          # 0..1 around the circle
    t = abs(t * 2 - 1)                                 # mirror so it loops smoothly
    return "minecraft:" + GRAD[min(len(GRAD) - 1, int(t * len(GRAD)))]


def underside(cx, cz, r, depth, y0=0, seed=0, amethyst=0.06):
    """Rocky inverted cone under a disc of radius r centred at (cx, y0, cz)."""
    for x in range(-r - 1, r + 2):
        for z in range(-r - 1, r + 2):
            d = math.hypot(x, z)
            edge = r + (vnoise(x + seed, z, 6) - 0.5) * 2.5
            if d > edge:
                continue
            k = 1 - (d / max(edge, 1)) ** 1.7
            dep = int(1 + depth * k ** 0.9 + (vnoise(x * 2 + seed, z * 2, 4) - 0.5) * 4)
            for dy in range(1, max(2, dep) + 1):
                y = y0 - dy
                n = h(cx + x, y, cz + z, seed)
                if dy <= 2:
                    b = "deepslate"
                elif h(cx + x, y // 3, cz + z, 99) < amethyst and dy > 3:
                    b = "amethyst_block"
                elif n < 0.40:
                    b = "deepslate"
                elif n < 0.62:
                    b = "tuff"
                elif n < 0.82:
                    b = "blackstone"
                else:
                    b = "cobbled_deepslate"
                put(cx + x, y, cz + z, "minecraft:" + b)
            # glittering tips
            if dep > 6 and h(x, z, seed, 3) < 0.07:
                put(cx + x, y0 - dep - 1, cz + z, "minecraft:amethyst_cluster[facing=down]")


# ------------------------------------------------------------------ main island
underside(0, 0, R, DEPTH)

for x in range(-R - 1, R + 2):
    for z in range(-R - 1, R + 2):
        d = math.hypot(x, z)
        if d > R + 0.5:
            continue
        on_ns_path = abs(x) <= 2 and 6 < abs(z) <= 31
        on_ew_path = abs(z) <= 2 and 6 < abs(x) <= 31
        if d <= 1.5:
            b = "minecraft:deepslate_tiles"
        elif d <= 2.6:
            b = "minecraft:amethyst_block"
        elif d <= 5.6:
            b = "minecraft:polished_deepslate"
        elif d <= 7.2:
            b = grad_at(x, z)
        elif on_ns_path or on_ew_path:
            if (on_ns_path and x == 0) or (on_ew_path and z == 0):
                lit = (abs(z) if on_ns_path else abs(x)) % 4 == 0
                if on_ns_path and z < 0:
                    b = "minecraft:pearlescent_froglight" if lit else "minecraft:purpur_block"
                elif on_ns_path:
                    b = "minecraft:sea_lantern" if lit else "minecraft:prismarine_bricks"
                else:
                    b = "minecraft:sea_lantern" if lit else "minecraft:dark_prismarine"
            else:
                b = "minecraft:polished_deepslate"
        elif d <= 30:
            chk = (x + z) % 2 == 0
            b = "minecraft:polished_blackstone_bricks" if chk else "minecraft:deepslate_tiles"
            if h(x, z, 5) < 0.08:
                b = "minecraft:cracked_polished_blackstone_bricks" if chk else "minecraft:cracked_deepslate_tiles"
        elif d <= 32.2:
            b = grad_at(x, z)
        else:
            b = "minecraft:polished_blackstone_bricks"
        put(x, 0, z, b)

# spawn beacon: base under the floor, purple glass at spawn colours the beam
for x in range(-1, 2):
    for z in range(-1, 2):
        put(x, -2, z, "minecraft:iron_block")
put(0, -1, 0, "minecraft:beacon")
put(0, 0, 0, "minecraft:purple_stained_glass")

# rim: low wall + pillar ring with end rods
for a in range(0, 360, 2):
    rad = math.radians(a)
    x, z = round(33.4 * math.cos(rad)), round(33.4 * math.sin(rad))
    put(x, 1, z, "minecraft:polished_blackstone_bricks")
for i in range(24):
    rad = math.radians(i * 15 + 7.5)
    x, z = round(33 * math.cos(rad)), round(33 * math.sin(rad))
    for y in (1, 2):
        put(x, y, z, "minecraft:polished_blackstone_bricks")
    put(x, 3, z, "minecraft:crying_obsidian" if i % 2 else "minecraft:sea_lantern")
    put(x, 4, z, "minecraft:end_rod[facing=up]")


def portal(zc, trim, pillar, pillar_top, accent):
    # obsidian frame (must be complete so the portal blocks stay), interior 5 wide x 7 tall
    for x in range(-3, 4):
        for y in range(0, 9):
            edge = abs(x) == 3 or y in (0, 8)
            if edge:
                put(x, y, zc, "minecraft:obsidian")
            else:
                put(x, y, zc, "minecraft:nether_portal[axis=x]")
    # trim ring around the frame
    for x in range(-4, 5):
        for y in range(0, 10):
            if abs(x) == 4 or y == 9:
                put(x, y, zc, trim(x, y))
    # crown
    for x in range(-2, 3):
        put(x, 10, zc, accent)
    put(0, 11, zc, accent)
    # side pillars
    for sx in (-6, 6):
        for y in range(1, 12):
            put(sx, y, zc, pillar)
        put(sx, 12, zc, pillar_top)
        put(sx, 13, zc, "minecraft:end_rod[facing=up]")
    # step platform in front and behind
    for x in range(-6, 7):
        for dz in (-2, -1, 1, 2):
            put(x, 0, zc + dz, "minecraft:polished_deepslate")


portal(-25,
       lambda x, y: "minecraft:crying_obsidian" if (x + y) % 3 else "minecraft:obsidian",
       "minecraft:polished_blackstone_bricks", "minecraft:pearlescent_froglight", "minecraft:crying_obsidian")
portal(25,
       lambda x, y: "minecraft:flowering_azalea_leaves[persistent=true]" if (x * 7 + y) % 3 == 0 else "minecraft:moss_block",
       "minecraft:mossy_stone_bricks", "minecraft:verdant_froglight", "minecraft:moss_block")


def stand(xc, zc):
    for x in range(-4, 5):
        for z in range(-4, 5):
            if math.hypot(x, z) <= 4.3:
                put(xc + x, 1, zc + z, "minecraft:polished_deepslate" if math.hypot(x, z) < 3.4 else "minecraft:polished_blackstone_bricks")
    for y in range(2, 5):
        put(xc, y, zc, "minecraft:amethyst_block")
    put(xc, 5, zc, "minecraft:budding_amethyst")
    put(xc, 6, zc, "minecraft:amethyst_cluster[facing=up]")
    for dx, dz, f in ((1, 0, "east"), (-1, 0, "west"), (0, 1, "south"), (0, -1, "north")):
        put(xc + dx, 3, zc + dz, f"minecraft:large_amethyst_bud[facing={f}]")
        put(xc + dx * 2, 2, zc + dz * 2, "minecraft:amethyst_block")
        put(xc + dx * 2, 3, zc + dz * 2, "minecraft:medium_amethyst_bud[facing=up]")


stand(-22, 0)
stand(22, 0)

# lamp posts along the plaza
for x, z in ((-12, -12), (12, -12), (-12, 12), (12, 12), (-20, -20), (20, -20), (-20, 20), (20, 20)):
    for y in (1, 2, 3):
        put(x, y, z, "minecraft:polished_blackstone_wall")
    put(x, 4, z, "minecraft:sea_lantern")
    put(x, 5, z, "minecraft:end_rod[facing=up]")

# ------------------------------------------------------------------ floating islands around
for i, (ang, dist, dy, r) in enumerate(((20, 58, 8, 6), (85, 64, -6, 5), (150, 55, 14, 4), (215, 66, 2, 7), (280, 60, -10, 5), (330, 70, 18, 4))):
    cx, cz = round(dist * math.cos(math.radians(ang))), round(dist * math.sin(math.radians(ang)))
    underside(cx, cz, r, r * 2 + 4, y0=dy, seed=i * 31 + 5, amethyst=0.12)
    for x in range(-r, r + 1):
        for z in range(-r, r + 1):
            if math.hypot(x, z) <= r + (vnoise(x + i * 31 + 5, z, 6) - 0.5) * 2.5:
                put(cx + x, dy, cz + z, "minecraft:moss_block" if h(x, z, i) < 0.7 else "minecraft:deepslate")
    put(cx, dy + 1, cz, "minecraft:amethyst_block")
    put(cx, dy + 2, cz, "minecraft:amethyst_cluster[facing=up]")
    put(cx + 1, dy + 1, cz, "minecraft:small_amethyst_bud[facing=up]")

# ------------------------------------------------------------------ write
palette, idx, lines = [], {}, []
for (x, y, z), b in sorted(blocks.items(), key=lambda kv: (kv[0][1], kv[0][0], kv[0][2])):
    if b not in idx:
        idx[b] = len(palette)
        palette.append(b)
    lines.append(f"{x} {y} {z} {idx[b]}")
os.makedirs(os.path.dirname(OUT), exist_ok=True)
with gzip.open(OUT, "wt") as f:
    f.write("|".join(palette) + "\n" + "\n".join(lines) + "\n")
print(f"{len(lines)} blocks, {len(palette)} block types -> {os.path.normpath(OUT)} ({os.path.getsize(OUT)//1024} KB)")

if "--preview" in sys.argv:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    COL = {"light_blue": "#38E0FF", "cyan": "#16a0a8", "blue": "#3c44aa", "purple": "#8932b8", "purpur": "#a77ba7",
           "amethyst": "#9a5cc6", "sea_lantern": "#d6efe9", "pearlescent": "#f0d0e8", "verdant": "#d8f0c0", "moss": "#5a7a2e",
           "azalea": "#6a8f3a", "obsidian": "#1a1426", "crying": "#4a1a8a", "portal": "#7a2ad8", "prismarine": "#5aa89a",
           "iron": "#d8d8d8", "glass": "#7f3fb2", "end_rod": "#ffffff", "beacon": "#9fe8e0", "tuff": "#6b6b60",
           "blackstone": "#2a2530", "deepslate": "#45454f", "mossy": "#6a7a5a"}

    def col(b):
        for k, v in COL.items():
            if k in b:
                return v
        return "#555"
    top = {}
    for (x, y, z), b in blocks.items():
        if (x, z) not in top or y > top[(x, z)][0]:
            top[(x, z)] = (y, b)
    fig, ax = plt.subplots(1, 2, figsize=(16, 8), facecolor="#0b0b14")
    for (x, z), (y, b) in top.items():
        ax[0].add_patch(plt.Rectangle((x, z), 1, 1, color=col(b)))
    ax[0].set_xlim(-80, 80); ax[0].set_ylim(80, -80); ax[0].set_aspect("equal"); ax[0].set_facecolor("#0b0b14")
    ax[0].set_title("top (north up)", color="w")
    side = {}
    for (x, y, z), b in blocks.items():
        if (x, y) not in side or z < side[(x, y)][0]:
            side[(x, y)] = (z, b)
    for (x, y), (z, b) in side.items():
        ax[1].add_patch(plt.Rectangle((x, y), 1, 1, color=col(b)))
    ax[1].set_xlim(-80, 80); ax[1].set_ylim(-45, 30); ax[1].set_aspect("equal"); ax[1].set_facecolor("#0b0b14")
    ax[1].set_title("side (looking north)", color="w")
    out = sys.argv[sys.argv.index("--preview") + 1]
    plt.savefig(out, dpi=90, bbox_inches="tight", facecolor="#0b0b14")
    print("preview", out)
