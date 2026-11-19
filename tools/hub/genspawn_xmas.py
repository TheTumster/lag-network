"""RelapsedMC hub, Christmas edition ("Midnight Christmas").

Takes the v4 hub (tools/hub/genspawn_v4.py: same gates, parkour, holograms, spawn) and dresses it for
Christmas, then writes plugins/lag-core/src/main/resources/hub-spawn-christmas.txt.gz. The plugin picks it
while laGCore config `hub.theme` is "christmas" (the default this season); set it to "default" to go back.

What changes:
  the Great Tree   a ~85-block decorated spruce on its own snowy island behind the gates, framed between the
                   two middle gates from spawn: drooping tiers with snow on every ledge, glowing baubles,
                   spiral string lights, a 3D gold star on top and a pile of presents round the base
  two snowmen      giant snowmen (top hat, striped scarf, carrot nose, stick arms, one waving) on the
                   east and west islets, turned to face the plaza
  snow             snow on the gardens, every ledge, gate tops and tree; light dusting on the paving
  frozen water     the Well is an ice rink over its lights, waterfalls are frozen falls
  candy canes      every lamp post is a candy cane with a lantern hanging from the hook
  winter garden    cherry trees become little decorated spruces with presents, hedges are spruce,
                   flower beds are red and white tulips, the crystal groves are ice
  festive lights   the plaza's outer ring and the terrace edge become red / gold / green / white bulbs,
                   wreaths with red bows hang round each gate's medallion, garlands on the colonnade
Run: python3 tools/hub/genspawn_xmas.py [--preview out.png]
"""
import math, os, sys

sys.argv = [a for a in sys.argv]  # genspawn_v4 reads --preview itself only in write()
sys.path.insert(0, os.path.dirname(__file__))
import genspawn_v4 as V
from genspawn_v4 import put, get, h, vnoise, CX, CZ, FLOOR, R, PLAZA, TERRACE_Y, TERRACE_Z0, PORTAL_Z, PORTALS

B = V.blocks
OUT = os.path.join(V.RES, "hub-spawn-christmas.txt.gz")
LEAF = "[distance=1,persistent=true,waterlogged=false]"
SPRUCE = "spruce_leaves" + LEAF
NO_SNOW = set()          # positions whose top must stay clear (presents, star, bulbs)


def name(b):
    return (b or "").replace("minecraft:", "")


def clear(cx, cz, r, y0, y1):
    for (x, y, z) in [k for k in B if y0 <= k[1] <= y1 and math.hypot(k[0] - cx, k[2] - cz) <= r]:
        del B[(x, y, z)]


# ======================================================================= make room
# the two north islets sit where the Great Tree goes; the east/west islets become the snowmen's islands
for sx in (-1, 1):
    clear(15 * sx, -97, 9, -30, 45)
    clear(62 * sx, -48, 10.5, -40, 40)

# the floating Crystal over the Well goes: the Great Tree is the centrepiece now
for k in [k for k in B if V.CR_Y - 2 <= k[1] <= V.CR_Y + V.CR_H + 1 and abs(k[0] - CX) <= 7 and abs(k[2] - CZ) <= 7]:
    del B[k]

# cherry trees: note where they stood, then take them out
cherry_bases = sorted({(x, y, z) for (x, y, z), b in B.items() if name(b).startswith("cherry_log") and not name(get(x, y - 1, z)).startswith("cherry")})
for k in [k for k, b in B.items() if name(b).startswith(("cherry_", "pink_petals"))]:
    del B[k]

# ======================================================================= re-dress what's there
FLOWERS = ("allium", "cornflower", "pink_tulip", "azure_bluet")
for k, b in list(B.items()):
    n = name(b)
    x, y, z = k
    if n == "grass_block[snowy=false]":
        B[k] = "minecraft:grass_block[snowy=true]"
    elif n in ("short_grass", "fern"):
        del B[k]
    elif n.startswith(("azalea_leaves", "flowering_azalea_leaves")):
        B[k] = "minecraft:" + SPRUCE
    elif n in ("azalea", "flowering_azalea"):
        B[k] = "minecraft:spruce_sapling[stage=0]"
    elif n == "moss_carpet":
        B[k] = "minecraft:snow[layers=1]"
    elif n in FLOWERS:
        B[k] = "minecraft:" + ("red_tulip" if (x + z) % 2 else "white_tulip")
    elif n == "water":
        B[k] = "minecraft:ice"              # the Well and the streams freeze; lights under the Well glow through
    elif n == "water[level=8]":
        B[k] = "minecraft:" + ("packed_ice" if h(x, y // 2, z, 5) < 0.55 else "blue_ice" if h(x, y, z, 6) < 0.5 else "ice")
    elif n == "dripstone_block" and y < FLOOR - 4:
        B[k] = "minecraft:packed_ice"
    elif n.startswith("pointed_dripstone"):
        B[k] = "minecraft:ice"

# frozen falls get a little width, so they read as ice and not a thin line
for k, b in list(B.items()):
    if name(b) in ("packed_ice", "blue_ice", "ice") and k[1] < FLOOR and math.hypot(k[0] - CX, k[2] - CZ) > R - 2:
        x, y, z = k
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            if get(x + dx, y, z + dz) is None and h(x + dx, y, z + dz, 9) < 0.45 and y > FLOOR - 40:
                put(x + dx, y, z + dz, "packed_ice")

# crystal groves -> ice groves
for gx in (31, -31):
    for (x, y, z), b in list(B.items()):
        if math.hypot(x - gx, z + 25) > 9 or y < FLOOR or y > FLOOR + 14:
            continue
        n = name(b)
        rep = {"amethyst_block": "packed_ice", "budding_amethyst": "blue_ice", "purple_stained_glass": "light_blue_stained_glass",
               "magenta_stained_glass": "white_stained_glass", "calcite": "snow_block", "smooth_basalt": "packed_ice"}.get(n)
        if y == FLOOR and n in ("amethyst_block", "budding_amethyst"):
            rep = "snow_block" if h(x, z, 3) < 0.7 else "packed_ice"
        if n.startswith(("amethyst_cluster", "large_amethyst_bud")) and y <= FLOOR + 1:
            rep = "air"
        if rep:
            B[(x, y, z)] = "minecraft:" + rep

# plaza outer inlay ring + terrace edge: Christmas bulbs (stained glass over the existing lights)
BULBS = ["red_stained_glass", "yellow_stained_glass", "lime_stained_glass", "white_stained_glass"]
for x in range(CX - PLAZA, CX + PLAZA + 1):
    for z in range(CZ - PLAZA, CZ + PLAZA + 1):
        if round(math.hypot(x - CX, z - CZ)) == 14 and name(get(x, FLOOR, z)).endswith("stained_glass"):
            a = (math.degrees(math.atan2(z - CZ, x - CX)) + 360) % 360
            put(x, FLOOR, z, BULBS[int(a / 7.5) % 4])
for x in range(-40, 41):
    if name(get(x, TERRACE_Y, TERRACE_Z0 - 1)).endswith("stained_glass"):
        put(x, TERRACE_Y, TERRACE_Z0 - 1, BULBS[(x // 2) % 4])

# lamp posts -> candy canes, lantern hanging from the hook
for (x, y, z), b in list(B.items()):
    if name(b) != "lantern[hanging=false,waterlogged=false]":
        continue
    if not (name(get(x, y - 1, z)).startswith("polished_blackstone_wall") and name(get(x, y - 2, z)).startswith("polished_blackstone_wall")):
        continue
    # hook points toward the nearest walkway (along the bigger offset from the plaza centre)
    vx, vz = CX - x, CZ - z
    if abs(x) <= 4 and z > 10:                      # south walk: hook over the walk
        dx, dz = (1 if x < 0 else -1), 0
    elif abs(vx) >= abs(vz):
        dx, dz = (1 if vx > 0 else -1), 0
    else:
        dx, dz = 0, (1 if vz > 0 else -1)
    if get(x + dx, y + 1, z + dz) is not None or get(x + dx, y + 2, z + dz) is not None or get(x + dx, y, z + dz) is not None:
        dx, dz = -dx, -dz
    if get(x + dx, y + 1, z + dz) is not None or get(x + dx, y + 2, z + dz) is not None:
        continue
    for i, yy in enumerate(range(y - 2, y + 3)):
        put(x, yy, z, "red_concrete" if i % 2 else "white_concrete")
    put(x + dx, y + 2, z + dz, "red_concrete")
    put(x + dx, y + 1, z + dz, "white_concrete")
    if get(x + dx, y, z + dz) is None:
        put(x + dx, y, z + dz, "lantern[hanging=true,waterlogged=false]")
    NO_SNOW.add((x, y + 2, z)); NO_SNOW.add((x + dx, y + 2, z + dz))

# wreaths round each gate's medallion, with a red bow at the bottom
y0g = TERRACE_Y + 1
for realm, px in PORTALS:
    my = y0g + 14
    for dx in range(-4, 5):
        for dy in range(-4, 5):
            d = math.hypot(dx, dy)
            if 2.45 <= d <= 3.45:
                x, y, z = px + dx, my + dy, PORTAL_Z + 1
                b = "red_concrete" if h(x, y, 71) < 0.13 else "ochre_froglight" if h(x, y, 72) < 0.08 else SPRUCE
                put(x, y, z, b)
                put(x, y, z + 1, "light[level=10,waterlogged=false]", force=False)
    for dx in (-1, 0, 1):
        put(px + dx, my - 3, PORTAL_Z + 2, "red_wool")
    put(px - 1, my - 4, PORTAL_Z + 2, "red_wool")
    put(px + 1, my - 4, PORTAL_Z + 2, "red_wool")

# garland on the colonnade (the back wall between the gates)
for (x, y, z), b in list(B.items()):
    if y == TERRACE_Y + 4 and z == V.TERRACE_Z1 + 1 and name(b).startswith("polished_blackstone_brick_slab"):
        put(x, y, z, SPRUCE)
        if x % 4 == 2:
            put(x, y, z + 1, ["red_concrete", "ochre_froglight"][(x // 4) % 2])

# ======================================================================= small decorated spruces (where the cherries were)
ORN = ["red_concrete", "gold_block", "red_concrete", "light_blue_concrete", "white_concrete"]


def present(x, y, z, size, seed):
    """A wrapped present: body colour, ribbon cross in another colour, a bow on top."""
    c, r = [("red", "yellow"), ("lime", "red"), ("light_blue", "white"), ("purple", "yellow"), ("white", "red"),
            ("green", "white"), ("magenta", "white")][int(h(seed, 1) * 7)]
    w = size
    hgt = size if size > 1 else 1
    mid = w // 2
    for dx in range(w):
        for dz in range(w):
            for dy in range(hgt):
                rib = w >= 3 and (dx == mid or dz == mid)
                put(x + dx, y + dy, z + dz, f"{r if rib else c}_concrete" if rib else f"{c}_wool")
                NO_SNOW.add((x + dx, y + dy, z + dz))
    if w >= 3:
        put(x + mid, y + hgt, z + mid, f"{r}_wool")
        put(x + mid - 1, y + hgt, z + mid, f"{r}_carpet")
        put(x + mid + 1, y + hgt, z + mid, f"{r}_carpet")
    else:
        put(x, y + hgt, z, f"{r}_carpet")


def little_tree(x0, z0, y0, size, seed):
    hgt = 8 + size * 3
    rmax = 2.6 + size * 0.9
    put(x0, y0, z0, "spruce_log[axis=y]")
    put(x0, y0 + 1, z0, "spruce_log[axis=y]")
    for y in range(2, hgt):
        u = (y - 2) / (hgt - 2)
        tier = (u * (2 + size)) % 1
        rr = rmax * (1 - u) * (0.7 + 0.3 * (1 - tier)) + 0.4
        for dx in range(-4, 5):
            for dz in range(-4, 5):
                d = math.hypot(dx, dz)
                if d <= rr:
                    x, z, yy = x0 + dx, z0 + dz, y0 + y
                    if d > rr - 1.1 and h(x, yy, z, seed) < 0.09:
                        put(x, yy, z, ORN[int(h(x, yy, z, seed + 1) * len(ORN))])
                    elif d > rr - 1.1 and h(x, yy, z, seed + 2) < 0.05:
                        put(x, yy, z, "ochre_froglight")
                    else:
                        put(x, yy, z, SPRUCE)
    put(x0, y0 + hgt, z0, "gold_block")
    put(x0, y0 + hgt + 1, z0, "end_rod[facing=up]")
    NO_SNOW.add((x0, y0 + hgt, z0))
    # presents at the foot
    for i in range(2 + size):
        a = h(seed, i, 3) * math.pi * 2
        px, pz = round(x0 + math.cos(a) * (rmax + 0.6)), round(z0 + math.sin(a) * (rmax + 0.6))
        if get(px, y0, pz) is None and get(px, y0 - 1, pz) is not None and not V.on_path(px, pz):
            present(px, y0, pz, 1, seed * 7 + i)


for i, (x, y, z) in enumerate(cherry_bases):
    if math.hypot(x - 62, z + 48) < 12 or math.hypot(x + 62, z + 48) < 12:
        continue
    little_tree(x, z, y, 2 if abs(x) > 30 or (abs(x) == 7 and z == 18) or abs(x) == 20 else 1, 300 + i * 13)

# ======================================================================= the Great Tree
TX, TZ, TTOP = 0, -103, 4         # island centre, island surface y


def snowy_island(cx, cz, r, depth, top, seed):
    V.island(cx, cz, r, depth, top + 1, seed, wob=4)
    for x in range(-r - 5, r + 6):
        for z in range(-r - 5, r + 6):
            if math.hypot(x, z) <= V.edge_r(x, z, r, seed, 4):
                put(cx + x, top, cz + z, "snow_block")
                put(cx + x, top - 1, cz + z, "dirt")


snowy_island(TX, TZ, 20, 34, TTOP, 77)
# the rim: a ring of packed ice with snow banks
for x in range(-26, 27):
    for z in range(-26, 27):
        d = math.hypot(x, z)
        e = V.edge_r(x, z, 20, 77, 4)
        if e - 1.5 < d <= e and get(TX + x, TTOP, TZ + z):
            put(TX + x, TTOP, TZ + z, "packed_ice" if h(x, z, 4) < 0.5 else "snow_block")

YB, YT, RMAX, TIERS = TTOP + 7, TTOP + 82, 17.5, 9


def tree_r(y, ang):
    u = (y - YB) / (YT - YB)
    k = min(TIERS - 1, int(u * TIERS))
    f = u * TIERS - k                                   # 0 at the tier's (wide) bottom, 1 at its top
    rr = RMAX * (1 - u) ** 0.92 * (0.6 + 0.4 * (1 - f) ** 1.3) + 0.8
    rr += (vnoise(ang * 7 + k * 5.3, y * 0.4, 1.0, 17) - 0.5) * (2.6 * (1 - u) + 0.6)
    return rr, f, k


shell = {}
for y in range(YB, YT + 1):
    for x in range(-22, 23):
        for z in range(-22, 23):
            d = math.hypot(x, z)
            ang = math.atan2(z, x)
            rr, f, k = tree_r(y, ang)
            if d > rr:
                continue
            bottom_row = f < 1.0 / ((YT - YB) / TIERS)   # first layer of each tier: a full disc closes the underside
            if d > rr - 2.6 or bottom_row:
                shell[(TX + x, y, TZ + z)] = (d, rr, f)
# drooping tips under each tier's rim
for (x, y, z), (d, rr, f) in list(shell.items()):
    if f < 0.15 and d > rr - 1.2 and h(x, y, z, 31) < 0.55:
        shell.setdefault((x, y - 1, z), (d, rr, 0.0))
for (x, y, z) in shell:
    put(x, y, z, SPRUCE)
# trunk
for y in range(TTOP + 1, YB + 6):
    for x in range(-2, 3):
        for z in range(-2, 3):
            if x * x + z * z <= 5:
                put(TX + x, y, TZ + z, "spruce_wood[axis=y]" if x * x + z * z >= 4 else "spruce_log[axis=y]")
# roots flaring into the snow
for i in range(6):
    a = i / 6 * math.pi * 2 + 0.4
    for t in range(3, 6):
        put(TX + round(math.cos(a) * t), TTOP + (1 if t < 5 else 0), TZ + round(math.sin(a) * t), "spruce_wood[axis=x]")

# baubles: 2x2x2 balls on the outside of the tiers
BAUBLE = ["red_concrete", "red_concrete", "ochre_froglight", "pearlescent_froglight", "light_blue_concrete", "white_concrete", "gold_block"]
outer = [(k, v) for k, v in shell.items() if v[0] > v[1] - 1.0]
for (x, y, z), (d, rr, f) in outer:
    if h(x, y, z, 51) < 0.035 and 0.15 < f < 0.85:
        c = BAUBLE[int(h(x, y, z, 52) * len(BAUBLE))]
        ox = 1 if x >= TX else -1
        oz = 1 if z >= TZ else -1
        for dx in (0, ox):
            for dy in (0, -1):
                for dz in (0, oz):
                    put(x + dx, y + dy, z + dz, c)
                    NO_SNOW.add((x + dx, y + dy, z + dz))
        put(x + ox * 2, y, z + oz * 2, "light[level=11,waterlogged=false]", force=False)

# string lights: three spirals of bulbs, a bulb every few blocks along each
LIGHTS = ["shroomlight", "ochre_froglight", "sea_lantern", "pearlescent_froglight"]
for s in range(3):
    turns, phase = 4.5, s * 2 * math.pi / 3
    n = 0
    for y in range(YB + 1, YT - 2):
        u = (y - YB) / (YT - YB)
        a = phase + u * turns * 2 * math.pi
        rr, f, k = tree_r(y, a)
        if f > 0.8:
            continue
        rr_out = rr + 0.2
        for dt in (0, 0.5):
            aa = a + dt * 0.12
            x, z = TX + round(math.cos(aa) * rr_out), TZ + round(math.sin(aa) * rr_out)
            n += 1
            if n % 2:
                continue
            put(x, y, z, LIGHTS[(n // 2 + s) % len(LIGHTS)])
            NO_SNOW.add((x, y, z))
# soft light all over the outside so the needles read at midnight
for (x, y, z), (d, rr, f) in outer:
    if h(x, y, z, 61) < 0.03:
        ox, oz = (x - TX) / max(d, 1), (z - TZ) / max(d, 1)
        p = (round(x + ox * 1.6), y, round(z + oz * 1.6))
        if p not in B:
            put(*p, "light[level=12,waterlogged=false]")


# the star: two crossed 5-point stars (reads from every side), gold with a glowing heart
def in_star(px, py, R_, r_):
    a = math.atan2(px, py)                  # 0 = straight up
    d = math.hypot(px, py)
    seg = (a % (2 * math.pi / 5)) / (2 * math.pi / 5)      # 0..1 across one point
    t = abs(seg - 0.5) * 2                   # 1 at a tip, 0 between tips
    edge = r_ + (R_ - r_) * t ** 1.6
    return d <= edge, d, edge


SY = YT + 6
SR, Sr = 8.5, 3.6
for u in range(-10, 11):
    for v in range(-10, 11):
        ok, d, edge = in_star(u, v, SR, Sr)
        if not ok:
            continue
        b = "ochre_froglight" if d < Sr - 0.6 else "glowstone" if d > edge - 1.0 else "gold_block"
        for t in (0, 1):
            put(TX + u, SY + v, TZ - t, b)                       # facing south (spawn)
            put(TX - t, SY + v, TZ + u, b, force=False)          # facing east/west
            NO_SNOW.add((TX + u, SY + v, TZ - t)); NO_SNOW.add((TX - t, SY + v, TZ + u))
for y in range(YT - 2, SY - 3):
    put(TX, y, TZ, "gold_block")
for dx, dz in ((0, 2), (0, -3), (2, 0), (-3, 0), (2, 2), (-3, -3)):
    put(TX + dx, SY, TZ + dz, "light[level=15,waterlogged=false]", force=False)

# presents round the base (under the lowest tier's rim and spilling out onto the snow)
pi_ = 0
for ring, count in ((11.5, 14), (16.5, 16)):
    for i in range(count):
        a = (i + h(i, ring, 3)) / count * 2 * math.pi
        px, pz = TX + round(math.cos(a) * ring), TZ + round(math.sin(a) * ring)
        size = 3 if h(i, ring, 4) < 0.35 else 2 if h(i, ring, 5) < 0.75 else 1
        if all(get(px + dx, TTOP + 1, pz + dz) is None for dx in range(size) for dz in range(size)):
            present(px, TTOP + 1, pz, size, 900 + pi_)
            pi_ += 1

# lights on the island so the presents and trunk are seen
for i in range(12):
    a = i / 12 * 2 * math.pi
    put(TX + round(math.cos(a) * 14), TTOP + 4, TZ + round(math.sin(a) * 14), "light[level=13,waterlogged=false]", force=False)

# two giant candy canes framing the tree on its island (2x2, diagonal stripes, hook curling inward)
for sx in (-1, 1):
    bx, bz = TX + sx * 17, TZ + 9
    top = TTOP + 18
    for y in range(TTOP + 1, top):
        for dx in (0, 1):
            for dz in (0, 1):
                put(bx + dx, y, bz + dz, "red_concrete" if ((y + dx + dz) // 2) % 2 else "white_concrete")
    # hook: a half circle curling toward the tree
    for i in range(0, 181, 6):
        a = math.radians(i)
        hx = bx + (-sx) * (2.5 - 2.5 * math.cos(a))
        hy = top + 2.5 * math.sin(a)
        for dx in (0, 1):
            for dz in (0, 1):
                xx, yy = round(hx) + dx, round(hy)
                put(xx, yy, bz + dz, "red_concrete" if ((yy + xx + dz) // 2) % 2 else "white_concrete")
    for y in range(top - 2, top + 1):
        for dx in (0, 1):
            for dz in (0, 1):
                put(bx + (-sx) * 5 + dx, y, bz + dz, "red_concrete" if ((y + dx + dz) // 2) % 2 else "white_concrete")
    put(bx + sx * -1, TTOP + 8, bz - 1, "light[level=13,waterlogged=false]", force=False)


# ======================================================================= the snowmen
def snowman(cx, cz, base_y, wave, seed):
    fx, fz = CX - cx, (CZ - 4) - cz
    fl = math.hypot(fx, fz)
    fx, fz = fx / fl, fz / fl                   # facing (toward the plaza)
    sx_, sz_ = -fz, fx                          # his left (from his view, right-handed)
    r1, r2, r3 = 6.6, 5.1, 3.9
    c1 = base_y + r1 - 1.0
    c2 = c1 + r1 + r2 - 2.4
    c3 = c2 + r2 + r3 - 1.9

    def dirv(az, el):
        """Unit vector: azimuth from the facing direction (degrees, + = toward his left), elevation (degrees)."""
        a, e = math.radians(az), math.radians(el)
        hx = fx * math.cos(a) + sx_ * math.sin(a)
        hz = fz * math.cos(a) + sz_ * math.sin(a)
        return (hx * math.cos(e), math.sin(e), hz * math.cos(e))

    def near(n, v, deg):
        return n[0] * v[0] + n[1] * v[1] + n[2] * v[2] > math.cos(math.radians(deg))

    eyes = [dirv(-24, 20), dirv(24, 20)]
    mouth = [dirv(az, -22 + (az / 30) ** 2 * 9) for az in (-30, -15, 0, 15, 30)]
    buttons = [dirv(0, el) for el in (34, 2, -30)]
    for (c, r, part) in ((c1, r1, 1), (c2, r2, 2), (c3, r3, 3)):
        ri = int(r) + 2
        for x in range(-ri, ri + 1):
            for y in range(-ri, ri + 1):
                for z in range(-ri, ri + 1):
                    d = math.sqrt(x * x + (y + (c - round(c))) ** 2 + z * z)
                    if d > r or d < r - 1.6:
                        continue
                    n = (x / max(d, .01), (y + (c - round(c))) / max(d, .01), z / max(d, .01))
                    b = "snow_block" if h(cx + x, y, cz + z, seed) > 0.07 else "white_concrete_powder"
                    if part == 3 and (any(near(n, e, 13) for e in eyes) or any(near(n, m, 7) for m in mouth)):
                        b = "coal_block"
                    if part == 2 and any(near(n, bt, 9.5) for bt in buttons):
                        b = "coal_block"
                    put(cx + x, round(c) + y, cz + z, b, force=part != 1 or True)
    # carrot nose: a cone along the facing direction
    for i in range(0, 50):
        t = i / 10
        rr = 1.1 * (1 - t / 5) + 0.15
        px, py, pz = cx + fx * (r3 - 0.6 + t), c3 - 0.3, cz + fz * (r3 - 0.6 + t)
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for dz in (-1, 0, 1):
                    if math.hypot(dx, dy, dz) <= rr:
                        put(round(px + dx), round(py + dy), round(pz + dz), "orange_terracotta" if (i // 6) % 2 else "orange_concrete")
    # scarf round the neck + a striped tail hanging off his left shoulder
    yn = round(c3 - r3 + 1.0)
    for y in (yn - 1, yn):
        rn = max(math.sqrt(max(0, r3 ** 2 - (y - c3) ** 2)), math.sqrt(max(0, r2 ** 2 - (y - c2) ** 2))) + 0.4
        for x in range(-8, 9):
            for z in range(-8, 9):
                d = math.hypot(x, z)
                if rn - 0.5 < d <= rn + 1.1:
                    a = math.degrees(math.atan2(z, x))
                    put(cx + x, y, cz + z, "red_wool" if int((a + 360) / 30) % 2 else "white_wool")
    # the knot sits at the front-left of the neck; the tail lies on the middle ball, following its curve down
    for i in range(10):
        y = yn - 1 - i
        rs = math.sqrt(max(0, r2 ** 2 - (y - c2) ** 2)) + 0.7
        for az in (30, 38):
            v = dirv(az, 0)
            p = (round(cx + v[0] * rs), y, round(cz + v[2] * rs))
            put(*p, "red_wool" if (i // 2) % 2 == 0 else "white_wool")
    for az in (26, 34, 42):
        v = dirv(az, 0)
        put(round(cx + v[0] * (r3 + 1.3)), yn, round(cz + v[2] * (r3 + 1.3)), "red_wool")
    # top hat: brim, crown, red band, a sprig of holly at the front
    yb = round(c3 + r3 * 0.72)
    for x in range(-6, 7):
        for z in range(-6, 7):
            d = math.hypot(x, z)
            if d <= r3 + 1.3:
                put(cx + x, yb, cz + z, "black_wool")
            if d <= r3 * 0.8:
                for y in range(yb + 1, yb + 7):
                    put(cx + x, y, cz + z, "red_wool" if y == yb + 1 else "black_wool")
    hx_, hz_ = cx + fx * (r3 * 0.8 + 0.6), cz + fz * (r3 * 0.8 + 0.6)
    put(round(hx_), yb + 1, round(hz_), "red_concrete")
    put(round(hx_ + sx_), yb + 2, round(hz_ + sz_), "green_concrete")
    put(round(hx_ - sx_), yb + 2, round(hz_ - sz_), "green_concrete")
    # stick arms out of the middle ball; one waves
    for side, (el, length) in ((1, (48 if wave else -18, 10)), (-1, (-18 if wave else 30, 10))):
        ax, az = sx_ * side, sz_ * side
        e = math.radians(el)
        sx0, sy0, sz0 = cx + ax * (r2 - 0.8), c2 + 0.5, cz + az * (r2 - 0.8)
        dx_, dy_, dz_ = ax * math.cos(e), math.sin(e), az * math.cos(e)
        end = None
        for i in range(0, length * 3):
            t = i / 3
            p = (round(sx0 + dx_ * t), round(sy0 + dy_ * t), round(sz0 + dz_ * t))
            put(*p, "spruce_wood[axis=y]")
            end = p
        # twig fingers
        for (fx2, fy2) in ((0.6, 1), (1, -0.4), (-0.2, 1)):
            for t in (1, 2):
                put(round(end[0] + dx_ * t * fx2 + fx * 0), round(end[1] + fy2 * t), round(end[2] + dz_ * t * fx2), "spruce_wood[axis=y]")
    # light all round him (strongest on the face) so he reads white at midnight
    for (c, r) in ((c3, r3), (c2, r2), (c1, r1)):
        for az in range(-150, 181, 45):
            for el in (-20, 25):
                v = dirv(az, el)
                put(round(cx + v[0] * (r + 2.5)), round(c + v[1] * (r + 2.5)), round(cz + v[2] * (r + 2.5)), "light[level=15,waterlogged=false]", force=False)
    for (el, dd) in ((0, 3), (25, 3), (-10, 4)):
        for (c, r) in ((c3, r3), (c2, r2), (c1, r1)):
            put(round(cx + fx * (r + dd)), round(c + math.sin(math.radians(el)) * r), round(cz + fz * (r + dd)), "light[level=14,waterlogged=false]", force=False)


for sx in (-1, 1):
    cx = 62 * sx
    snowy_island(cx, -48, 8, 22, 8, 400 + sx)
    snowman(cx, -48, 9, wave=sx > 0, seed=410 + sx)
    # a couple of presents and a little tree at his feet
    present(cx + (-6 if sx > 0 else 4), 9, -40, 2, 450 + sx)
    present(cx + (-3 if sx > 0 else 2), 9, -41, 1, 460 + sx)

# ======================================================================= snow over everything
WALKWAY_Y = {FLOOR, TERRACE_Y}
NOT_UNDER = ("glass", "slab", "stairs", "_wall", "fence", "lantern", "end_rod", "chain", "carpet", "pane", "bars", "tulip",
             "sapling", "banner", "trapdoor", "plate", "cluster", "bud", "portal", "ice", "light[", "froglight", "sea_lantern",
             "glowstone", "shroomlight", "beacon", "snow[", "petals", "cobweb", "rod", "concrete_powder", "candle", "gold_block",
             "coal_block", "orange_", "water", "dripstone[", "button")


def walkway(x, y, z):
    if y == FLOOR and (math.hypot(x - CX, z - CZ) <= PLAZA + 0.6 or V.on_path(x, z) or math.hypot(x, z) <= 4.5):
        return True
    if y == TERRACE_Y and V.on_terrace(x, z):
        return True
    return math.hypot(x - CX, z - CZ) > R + 6 and y < 40 and not (math.hypot(abs(x) - 62, z + 48) < 12 or math.hypot(x - TX, z - TZ) < 30 or (abs(x) < 4 and abs(z - 58) < 14))


tops = {}
for (x, y, z) in B:
    if y > tops.get((x, z), -999):
        tops[(x, z)] = y
for (x, z), y in tops.items():
    n = name(B[(x, y, z)])
    if (x, y, z) in NO_SNOW or any(t in n for t in NOT_UNDER):
        continue
    if math.hypot(x - CX, z - CZ) > R + 6 and not (math.hypot(abs(x) - 62, z + 48) < 12 or math.hypot(x - TX, z - TZ) < 30
                                                    or (abs(x) < 12 and abs(z - 58) < 14) or (math.hypot(abs(x) - 62, z + 48) < 14)):
        continue                                    # parkour and anything else out in the void stays clear
    if walkway(x, y, z):
        if h(x, z, 81) < 0.07 and math.hypot(x, z) > 4.5:
            put(x, y + 1, z, "snow[layers=1]")       # a light dusting on the paving
        continue
    layers = 1 + (h(x, z, 82) < 0.35) + (h(x, z, 83) < 0.12 and "leaves" not in n)
    put(x, y + 1, z, f"snow[layers={layers}]")

# drifts against the terrace wall and round the plaza edge
for x in range(-41, 42):
    z = TERRACE_Z0 + 1
    if V.in_island(x, z) and name(get(x, FLOOR + 1, z)).startswith("spruce_leaves") and get(x, FLOOR + 2, z) is None:
        put(x, FLOOR + 2, z, "snow[layers=2]")

if __name__ == "__main__":
    V.write(B, OUT, preview_view="se")
    print("great tree: star top y", SY + 9, " snowmen at x=+-62 z=-48")
