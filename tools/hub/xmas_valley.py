"""The Christmas hub's landscape: snowy hills and cliffs rising from the plaza to the rim, a pine forest on
them, and a village of cottages lining the lanes. Called by genspawn_xmas.py on the v4 hub's blocks.

Everything walkable stays exactly where it was (plaza, lanes, gallery terrace, Discord pavilion, lookout,
parkour arch); the ground only rises away from them, so players walk through a valley and down village
streets instead of across a flat disc.
"""
import heapq, math

ROCK = [("stone", 0.34), ("andesite", 0.18), ("tuff", 0.12), ("cobblestone", 0.12), ("calcite", 0.12), ("packed_ice", 0.05), ("diorite", 0.07)]


def build(V, NO_SNOW, SPRUCE, present):
    put, get, h, vnoise = V.put, V.get, V.h, V.vnoise
    B = V.blocks
    CX, CZ, FLOOR, R, PLAZA = V.CX, V.CZ, V.FLOOR, V.R, V.PLAZA
    DX, DZ = V.DISCORD
    LZ = V.LZ

    def rock(x, y, z, s=0):
        r = h(x, y, z, 900 + s)
        a = 0
        for b, p in ROCK:
            a += p
            if r < a:
                return b
        return "stone"

    # ------------------------------------------------------------------ village lots
    # (x0, z0, w, d, door side, style). Doors face the lane; ridges run along it.
    # (no houses on the north side near spawn: they'd cover the outer gates from the spawn point)
    LOTS = [(27, -24, 7, 8, "S", 2), (18, -7, 7, 8, "N", 1), (27, -7, 7, 8, "N", 3),
            (-24, -7, 7, 8, "N", 2),
            (6, 14, 7, 7, "W", 2), (6, 23, 7, 6, "W", 0), (-12, 14, 7, 7, "E", 0), (-12, 23, 7, 6, "E", 1)]
    lots = []
    for (x0, z0, w, d, door, st) in LOTS:
        xw, zd = (w, d) if door in ("S", "N") else (d, w)
        cells = [(x, z) for x in range(x0 - 1, x0 + xw + 1) for z in range(z0 - 1, z0 + zd + 1)]
        if all(V.in_island(x, z) and V.in_island(x + 2 * (1 if x > CX else -1), z) for x, z in cells):
            lots.append((x0, z0, w, d, door, st, xw, zd))

    def in_lot(x, z, pad=1):
        for (x0, z0, w, d, door, st, xw, zd) in lots:
            # the lot plus its front yard down to the lane
            ex0, ex1, ez0, ez1 = x0 - pad, x0 + xw - 1 + pad, z0 - pad, z0 + zd - 1 + pad
            if door == "S": ez1 += 4
            if door == "N": ez0 -= 4
            if door == "W": ex0 -= 4
            if door == "E": ex1 += 4
            if ex0 <= x <= ex1 and ez0 <= z <= ez1:
                return True
        return False

    def flat(x, z):
        return flat_core(x, z) or in_lot(x, z)

    def flat_core(x, z):
        if math.hypot(x - CX, z - CZ) <= PLAZA + 2:
            return True
        if abs(z - CZ) <= 3 and 14 <= abs(x - CX) <= 44:
            return True
        if abs(x) <= 3 and 13 <= z <= LZ + 3:
            return True
        if z <= V.TERRACE_Z0 + 1 and abs(x) <= 43:
            return True
        if math.hypot(x - DX, z - DZ) <= 8.5:
            return True
        if math.hypot(x, (z - LZ) * 1.2) <= 6.8:
            return True
        if x >= 36 and abs(z - CZ) <= 5:
            return True
        return False

    cells = [(x, z) for x in range(-R - 10, R + 11) for z in range(CZ - R - 10, CZ + R + 11) if V.in_island(x, z)]
    cellset = set(cells)
    # distance from the walkable ground (8-way Dijkstra)
    D = {}
    pq = []
    for c in cells:
        if flat(*c):
            D[c] = 0.0
            pq.append((0.0, c))
    heapq.heapify(pq)
    while pq:
        dist, (x, z) = heapq.heappop(pq)
        if dist > D.get((x, z), 1e9):
            continue
        for dx, dz, w in ((1, 0, 1), (-1, 0, 1), (0, 1, 1), (0, -1, 1), (1, 1, 1.414), (1, -1, 1.414), (-1, 1, 1.414), (-1, -1, 1.414)):
            n = (x + dx, z + dz)
            if n in cellset and dist + w < D.get(n, 1e9):
                D[n] = dist + w
                heapq.heappush(pq, (dist + w, n))

    H = {}
    for (x, z) in cells:
        d = D.get((x, z), 0)
        if d <= 0.5:
            H[(x, z)] = 0
            continue
        slope = 1.05 + (vnoise(x, z, 7, 501) - 0.5) * 0.9
        cap = 8 + 10 * vnoise(x, z, 11, 502)
        hh = min(cap, max(0.0, d - 0.8) * slope)
        if hh > 2:
            hh += (vnoise(x * 1.7, z * 1.7, 4, 503) - 0.5) * 2.5
        H[(x, z)] = max(0, int(round(hh)))

    # clear the old lawn dressing wherever the ground rises (and over the yards)
    for k in [k for k in B if FLOOR + 1 <= k[1] <= FLOOR + 40 and (k[0], k[2]) in cellset
              and (H.get((k[0], k[2]), 0) > 0 or in_lot(k[0], k[2])) and not flat_core(k[0], k[2])]:
        del B[k]

    # raise the ground: snow on top, rock wherever a face is exposed
    for (x, z), hh in H.items():
        if hh <= 0:
            continue
        top = FLOOR + hh
        nb = [H.get((x + dx, z + dz), -99) if (x + dx, z + dz) in cellset else -99 for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))]
        steep = max(hh - n for n in nb) >= 3
        for y in range(FLOOR, top + 1):
            exposed = any(FLOOR + n < y for n in nb)
            if y == top:
                b = rock(x, y, z) if steep and h(x, z, 77) < 0.55 else "snow_block"
            elif exposed:
                b = rock(x, y, z) if h(x, y, z, 78) > 0.12 else "snow_block"
            else:
                b = "stone"
            put(x, y, z, b)

    def surf(x, z):
        return FLOOR + H.get((x, z), 0)

    # ------------------------------------------------------------------ pines
    def pine(x0, z0, y0, hgt, seed, big):
        rmax = hgt * (0.21 if big else 0.25)
        start = 3 if big else 2
        tiers = max(3, hgt // 4)
        for y in range(hgt):
            put(x0, y0 + y, z0, "spruce_log[axis=y]")
            if big and y < hgt - 6:
                put(x0 + 1, y0 + y, z0, "spruce_log[axis=y]")
                put(x0, y0 + y, z0 + 1, "spruce_log[axis=y]")
                put(x0 + 1, y0 + y, z0 + 1, "spruce_log[axis=y]")
        cx_, cz_ = (x0 + 0.5, z0 + 0.5) if big else (x0, z0)
        top = hgt + 1
        rr0 = int(rmax) + 2
        for y in range(start, top):
            u = (y - start) / (top - start)
            k = min(tiers - 1, int(u * tiers))
            f = u * tiers - k
            for dx in range(-rr0, rr0 + 2):
                for dz in range(-rr0, rr0 + 2):
                    x, z = x0 + dx, z0 + dz
                    d = math.hypot(x - cx_, z - cz_)
                    ang = math.atan2(z - cz_, x - cx_)
                    rr = rmax * (1 - u) * (0.55 + 0.45 * (1 - f) ** 1.2) + 0.6
                    rr += (vnoise(ang * 5 + k * 3.1, y * 0.5, 1.0, seed) - 0.5) * 1.6
                    if d <= rr and (d > rr - 2.2 or f < 0.2):
                        if get(x, y0 + y, z) is None:
                            put(x, y0 + y, z, SPRUCE)
                        if f < 0.12 and d > rr - 1 and h(x, y, z, seed) < 0.5 and get(x, y0 + y - 1, z) is None:
                            put(x, y0 + y - 1, z, SPRUCE)
        put(x0, y0 + top, z0, SPRUCE)
        put(x0, y0 + top + 1, z0, SPRUCE)
        # snow on every tier: anything with open sky above it on this tree gets a layer
        for dx in range(-rr0, rr0 + 2):
            for dz in range(-rr0, rr0 + 2):
                for y in range(y0 + top + 1, y0 + start - 2, -1):
                    b = get(x0 + dx, y, z0 + dz)
                    if b and b.endswith(SPRUCE):
                        if get(x0 + dx, y + 1, z0 + dz) is None:
                            put(x0 + dx, y + 1, z0 + dz, "snow[layers=%d]" % (2 + int(h(dx, y, dz, seed) * 3)))
                    # every ledge down the tree, not only the top: keep scanning below covered leaves

    trees = []
    order = sorted(cells, key=lambda c: h(c[0], c[1], 611))
    for (x, z) in order:
        hh = H.get((x, z), 0)
        d = D.get((x, z), 0)
        if hh < 2 or d < 3.5:
            continue
        if z < CZ - 1 and abs(x) < 46:
            continue                   # nothing tall between spawn and the gates' sides
        if not all(V.in_island(x + a, z + b) for a, b in ((3, 0), (-3, 0), (0, 3), (0, -3))):
            continue
        big = hh >= 4 and h(x, z, 612) < 0.6
        hgt = (17 + int(h(x, z, 613) * 11)) if big else (9 + int(h(x, z, 614) * 6))
        reach = hgt * (0.21 if big else 0.25) * 0.75
        if d < reach + 1.5:
            hgt = max(8, int((d - 1.5) / 0.19)) if big else max(7, int((d - 1.5) / 0.19))
            if hgt < 7:
                continue
        if any(math.hypot(x - tx, z - tz) < (9.5 if big or tb else 6.5) for tx, tz, tb in trees):
            continue
        trees.append((x, z, big))
        pine(x, z, surf(x, z) + 1, hgt, 700 + len(trees), big)

    # boulders with snow caps on the slopes
    nb_ = 0
    for (x, z) in sorted(cells, key=lambda c: h(c[0], c[1], 621)):
        if nb_ >= 14:
            break
        if H.get((x, z), 0) < 1 or D.get((x, z), 0) < 2.5 or any(math.hypot(x - tx, z - tz) < 4 for tx, tz, _ in trees):
            continue
        r = 1.6 + h(x, z, 622) * 1.6
        y0 = surf(x, z)
        for dx in range(-3, 4):
            for dy in range(-2, 4):
                for dz in range(-3, 4):
                    if (dx / r) ** 2 + (dy / (r * 0.8)) ** 2 + (dz / (r * 1.1)) ** 2 <= 1:
                        put(x + dx, y0 + dy, z + dz, rock(x + dx, y0 + dy, z + dz, 3))
        nb_ += 1

    # moonlight on the snow (the hub is at midnight)
    for (x, z) in cells:
        if x % 6 == 0 and z % 6 == 0 and H.get((x, z), 0) > 0:
            y = surf(x, z) + 4
            if get(x, y, z) is None:
                put(x, y, z, "light[level=9,waterlogged=false]")

    # ------------------------------------------------------------------ the village
    for (x0, z0, w, d, door, st, xw, zd) in lots:
        cottage(V, NO_SNOW, x0, z0, w, d, door, st)
    return len(trees), len(lots)


STYLES = [
    # frame, infill, roof wood, foundation
    dict(frame="stripped_dark_oak_log", infill="calcite", roof="dark_oak", found="cobblestone", trim=None),
    dict(frame="spruce_log", infill="stone_bricks", roof="spruce", found="mossy_cobblestone", trim=None),
    dict(frame="white_concrete", infill="terracotta", roof="dark_oak", found="brown_terracotta", trim="gingerbread"),
    dict(frame="stripped_spruce_log", infill="red_terracotta", roof="spruce", found="stone_bricks", trim=None),
]


def cottage(V, NO_SNOW, x0, z0, w, d, door, st):
    put, get, h = V.put, V.get, V.h
    FLOOR = V.FLOOR
    S = STYLES[st]
    xw, zd = (w, d) if door in ("S", "N") else (d, w)
    # local (u across the front, v back from the front) -> world
    if door == "S":
        W = lambda u, v: (x0 + u, z0 + zd - 1 - v); front, back = "south", "north"
    elif door == "N":
        W = lambda u, v: (x0 + xw - 1 - u, z0 + v); front, back = "north", "south"
    elif door == "W":
        W = lambda u, v: (x0 + v, z0 + u); front, back = "west", "east"
    else:
        W = lambda u, v: (x0 + xw - 1 - v, z0 + zd - 1 - u); front, back = "east", "west"
    side_axis = "x" if door in ("S", "N") else "z"
    depth_axis = "z" if door in ("S", "N") else "x"

    def P(u, v, y, b):
        x, z = W(u, v)
        put(x, y, z, b)
        return (x, y, z)

    y1, y_top = FLOOR + 1, FLOOR + 5
    mid = w // 2
    wins = {(1, 0), (w - 2, 0), (0, d // 2), (w - 1, d // 2)} if w >= 7 else {(1, 0), (w - 2, 0)}
    for u in range(w):
        for v in range(d):
            wall = u in (0, w - 1) or v in (0, d - 1)
            for y in range(y1, y_top + 1):
                if not wall:
                    if y == y1:
                        P(u, v, y, "spruce_planks")
                    elif y == y1 + 2 and (u, v) == (mid, d // 2):
                        P(u, v, y, "light[level=15,waterlogged=false]")
                    continue
                corner = u in (0, w - 1) and v in (0, d - 1)
                post = corner or (v in (0, d - 1) and u % 3 == 0) or (u in (0, w - 1) and v % 3 == 0)
                if y == y1:
                    b = S["found"]
                elif y == y_top:
                    b = S["frame"] + (f"[axis={side_axis if v in (0, d - 1) else depth_axis}]" if S["frame"].endswith("log") else "")
                elif post:
                    b = S["frame"] + ("[axis=y]" if S["frame"].endswith("log") else "")
                else:
                    b = S["infill"]
                    if S["infill"] == "stone_bricks" and h(u, v, y, 5) < 0.3:
                        b = "cobblestone" if h(u, v, y, 6) < 0.5 else "andesite"
                P(u, v, y, b)
    # windows (warm glass with a light behind) and the door
    for (u, v) in wins:
        if u == mid and v == 0:
            continue
        for y in (y1 + 2, y1 + 3):
            P(u, v, y, "yellow_stained_glass")
        iu = u + (1 if u == 0 else -1 if u == w - 1 else 0)
        iv = v + (1 if v == 0 else -1 if v == d - 1 else 0)
        P(iu, iv, y1 + 2, "light[level=15,waterlogged=false]")
        if S["trim"] == "gingerbread":
            P(u, v, y1 + 1, "white_concrete")
            P(u, v, y1 + 4, "white_concrete")
    P(mid, 0, y1 + 1, f"spruce_door[facing={front},half=lower,hinge=left,open=false,powered=false]")
    P(mid, 0, y1 + 2, f"spruce_door[facing={front},half=upper,hinge=left,open=false,powered=false]")
    P(mid, -1, y1, f"spruce_stairs[facing={back},half=bottom,shape=straight,waterlogged=false]")
    # a wreath over the door, lanterns (or candy canes) either side
    P(mid, -1, y1 + 3, "spruce_leaves[distance=1,persistent=true,waterlogged=false]")
    P(mid, -2, y1 + 3, "light[level=12,waterlogged=false]")
    for s in (-1, 1):
        u = mid + 2 * s
        if S["trim"] == "gingerbread":
            for i, y in enumerate(range(y1, y1 + 3)):
                P(u, -1, y, "red_concrete" if i % 2 else "white_concrete")
            NO_SNOW.add(P(u, -1, y1 + 3, "white_concrete"))
        else:
            P(u, -1, y1, "spruce_fence[east=false,north=false,south=false,waterlogged=false,west=false]")
            P(u, -1, y1 + 1, "lantern[hanging=false,waterlogged=false]")
    # roof: gable end to the street. Ridge runs front-to-back, slopes fall to the sides, overhang 1.
    rw = S["roof"]
    dirs = {"S": ("east", "west"), "N": ("west", "east"), "W": ("south", "north"), "E": ("north", "south")}[door]
    up_pos, up_neg = dirs                      # stairs on the -u side climb toward +u, and the reverse
    nrows = (w + 2 + 1) // 2
    for i in range(nrows):
        y = y_top + 1 + i
        ul, ur = -1 + i, w - i
        for v in range(-1, d + 1):
            if ul == ur:
                P(ul, v, y, f"{rw}_planks")
                P(ul, v, y + 1, "snow[layers=3]")
                if S["trim"] == "gingerbread" and v % 2 == 0 and 0 <= v < d:
                    P(ul, v, y + 1, ["red_stained_glass", "lime_stained_glass", "yellow_stained_glass", "purple_stained_glass"][v // 2 % 4])
                continue
            if ul > ur:
                continue
            P(ul, v, y, f"{rw}_stairs[facing={up_pos},half=bottom,shape=straight,waterlogged=false]")
            P(ur, v, y, f"{rw}_stairs[facing={up_neg},half=bottom,shape=straight,waterlogged=false]")
            P(ul, v, y + 1, "snow[layers=2]")
            P(ur, v, y + 1, "snow[layers=2]")
            if i == 0:
                P(ul, v, y - 1, f"{rw}_stairs[facing={up_neg},half=top,shape=straight,waterlogged=false]")
                P(ur, v, y - 1, f"{rw}_stairs[facing={up_pos},half=top,shape=straight,waterlogged=false]")
        # front and back gables: fill between the slopes; barge boards stand proud on the front
        for v in (0, d - 1):
            for u in range(max(0, ul + 1), min(w - 1, ur - 1) + 1):
                win = v == 0 and abs(u - (w - 1) / 2) < 0.6 and i in (1, 2)
                P(u, v, y, "yellow_stained_glass" if win else (S["frame"] + "[axis=y]" if S["frame"].endswith("log") and u == mid else S["infill"]))
                if win:
                    P(u, 1, y, "light[level=15,waterlogged=false]")
        if S["trim"] == "gingerbread" and ul < ur:
            P(ul, -1, y - 1 if i == 0 else y, "white_concrete")
            P(ur, -1, y - 1 if i == 0 else y, "white_concrete")
    # chimney at the back corner, smoke from a campfire
    cu, cv = w - 2, d - 3
    for y in range(y_top - 1, y_top + nrows + 3):
        P(cu, cv, y, "stone_bricks" if h(cu, y, 3) < 0.6 else "cobblestone")
    NO_SNOW.add(P(cu, cv, y_top + nrows + 3, "campfire[facing=north,lit=true,signal_fire=false,waterlogged=false]"))
