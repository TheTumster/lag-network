"""Christmas hub: the market town.

  street lights   lane lamps become tall posts with lanterns, and a garland of bulbs crosses each lane between pairs
  market stalls   striped awnings, counters of goods, a lantern each (placed where they never cover a portal)
  clock tower     by the parkour arch at the end of the east lane: stone and timber, four lit clock faces, bell
  Santa's Workshop  its own snowy island at the end of the south street: walk in through the big doors to
                  workbenches, toy shelves, a fireplace with stockings, the Nice List, and the sleigh being loaded
"""
import math

# (x0, z0, facing of the counter) - 4 wide (x) by 3 deep (z); keep in sync with xmas_valley's flat pads
STALLS = [(18, -20, "S"), (23, -20, "S"), (-21, -20, "S"), (35, -7, "N")]
CLOCK = (36, -22)                 # clock tower footprint corner (5 x 5)
WORKSHOP_ISLAND = (0, 50, 14)     # centre x, z, radius


def build(V, NO_SNOW, SPRUCE, present, parkour_keepout):
    put, get, h = V.put, V.get, V.h
    B = V.blocks
    FLOOR, CZ = V.FLOOR, V.CZ

    def name(b):
        return (b or "").replace("minecraft:", "")

    # ------------------------------------------------------------------ street lights + garlands across the lanes
    BULB = ["shroomlight", "ochre_froglight", "pearlescent_froglight", "sea_lantern"]
    posts = []
    for (x, y, z), b in list(B.items()):
        if name(b) != "lantern[hanging=false,waterlogged=false]" or y != FLOOR + 3:
            continue
        lane = (abs(z - CZ) == 3 and abs(x) >= 15) or (abs(x) == 3 and z >= 14)
        if not lane or not name(get(x, y - 1, z)).startswith("polished_blackstone_wall"):
            continue
        for yy in range(FLOOR + 1, FLOOR + 4):
            B.pop((x, yy, z), None)
        put(x, FLOOR + 1, z, "chiseled_polished_blackstone")
        for yy in range(FLOOR + 2, FLOOR + 6):
            put(x, yy, z, "dark_oak_fence[east=false,north=false,south=false,waterlogged=false,west=false]")
        put(x, FLOOR + 6, z, "lantern[hanging=false,waterlogged=false]")
        NO_SNOW.add((x, FLOOR + 6, z))
        posts.append((x, z))
    pset = set(posts)
    n_g = 0
    for (x, z) in posts:
        # pair across the lane: same x on the other side (east/west lanes), same z (south walk)
        if abs(z - CZ) == 3 and z < CZ and (x, CZ + 3) in pset:
            a, b_ = (x, z), (x, CZ + 3)
        elif abs(x) == 3 and x < 0 and (3, z) in pset:
            a, b_ = (x, z), (3, z)
        else:
            continue
        span = math.hypot(b_[0] - a[0], b_[1] - a[1])
        cells = []
        last = None
        for k in range(1, int(span * 2)):
            u = k / int(span * 2)
            c = (round(a[0] + (b_[0] - a[0]) * u), round(FLOOR + 5 - 1.2 * (1 - (2 * u - 1) ** 2)), round(a[1] + (b_[1] - a[1]) * u))
            if c != last and (c[0], c[2]) != a and (c[0], c[2]) != b_:
                cells.append(c)
                last = c
        for j, c in enumerate(cells):
            put(*c, BULB[(j // 2 + n_g) % len(BULB)] if j % 2 else SPRUCE)
            NO_SNOW.add(c)
        n_g += 1

    # ------------------------------------------------------------------ market stalls
    WARES = [["cake[bites=0]", "candle_cake[lit=true]", "cake[bites=0]", "candle_cake[lit=true]"],
             ["barrel[facing=up,open=false]", "decorated_pot[cracked=false,facing=north,waterlogged=false]", "barrel[facing=up,open=false]", "pumpkin"],
             ["red_wool", "lime_wool", "yellow_wool", "light_blue_wool"],
             ["honey_block", "decorated_pot[cracked=false,facing=north,waterlogged=false]", "melon", "honey_block"]]
    for i, (x0, z0, face) in enumerate(STALLS):
        front_z = z0 + 2 if face == "S" else z0
        back_z = z0 if face == "S" else z0 + 2
        for x in range(x0 - 1, x0 + 5):
            for z in range(z0 - 1, z0 + 4):
                for y in range(FLOOR + 1, FLOOR + 9):
                    B.pop((x, y, z), None)
                put(x, FLOOR, z, "spruce_planks" if x0 <= x < x0 + 4 and z0 <= z < z0 + 3 else "polished_deepslate")
        for x in range(x0, x0 + 4):
            for z in range(z0, z0 + 3):
                corner = x in (x0, x0 + 3) and z in (z0, z0 + 2)
                if corner:
                    for y in range(FLOOR + 1, FLOOR + 5):
                        put(x, y, z, "spruce_fence[east=false,north=false,south=false,waterlogged=false,west=false]")
                if z == front_z and not corner:
                    put(x, FLOOR + 1, z, "spruce_planks")                 # the counter
                    put(x, FLOOR + 2, z, WARES[i % len(WARES)][(x - x0) % 4])
                    NO_SNOW.add((x, FLOOR + 2, z))
                if z == back_z and not corner:
                    put(x, FLOOR + 1, z, "barrel[facing=up,open=false]")
                # striped awning: two rows, sloping down to the front
                yy = FLOOR + 5 if z != front_z else FLOOR + 4
                put(x, yy, z, "red_wool" if (x - x0) % 2 == 0 else "white_wool")
            put(x, FLOOR + 4, front_z + (1 if face == "S" else -1), "red_carpet" if (x - x0) % 2 == 0 else "white_carpet")
        put(x0 + 1, FLOOR + 4, (z0 + 1), "lantern[hanging=true,waterlogged=false]")
        put(x0 + 2, FLOOR + 3, front_z + (2 if face == "S" else -2), "light[level=13,waterlogged=false]")

    # ------------------------------------------------------------------ the clock tower
    cx0, cz0 = CLOCK
    W = 5
    top_wall = FLOOR + 20
    for x in range(cx0 - 1, cx0 + W + 1):
        for z in range(cz0 - 1, cz0 + W + 1):
            for y in range(FLOOR + 1, FLOOR + 40):
                if not parkour_keepout(x, y, z):
                    B.pop((x, y, z), None)
            put(x, FLOOR, z, "stone_bricks")
    for x in range(cx0, cx0 + W):
        for z in range(cz0, cz0 + W):
            edge = x in (cx0, cx0 + W - 1) or z in (cz0, cz0 + W - 1)
            corner = x in (cx0, cx0 + W - 1) and z in (cz0, cz0 + W - 1)
            for y in range(FLOOR + 1, top_wall + 1):
                if not edge:
                    continue
                if y <= FLOOR + 8:
                    b = "stone_bricks" if h(x, y, z, 3) > 0.2 else "mossy_stone_bricks" if h(x, y, z, 4) < 0.3 else "cracked_stone_bricks"
                elif corner:
                    b = "stripped_dark_oak_log[axis=y]"
                elif y in (FLOOR + 9, top_wall):
                    b = "stripped_dark_oak_log[axis=x]" if z in (cz0, cz0 + W - 1) else "stripped_dark_oak_log[axis=z]"
                else:
                    b = "white_terracotta"
                put(x, y, z, b)
    # clock faces on all four sides, lit from inside
    mx, mz, my = cx0 + 2, cz0 + 2, FLOOR + 15
    for (nx, nz) in ((0, -1), (0, 1), (-1, 0), (1, 0)):
        fx, fz = mx + nx * 3, mz + nz * 3
        for a in range(-2, 3):
            for b in range(-2, 3):
                d = math.hypot(a, b)
                if d <= 2.3:
                    px_, pz_ = (fx + a, fz) if nz else (fx, fz + a)
                    put(px_, my + b, pz_, "gold_block" if d > 1.6 else "white_concrete")
        hx, hz = (fx, fz)
        put(hx, my, hz, "black_concrete")
        put(hx, my + 1, hz, "black_concrete")                               # hour hand at 12
        put(hx + (1 if nz else 0), my, hz + (0 if nz else 1), "black_concrete")   # minute hand at 3
        put(mx + nx * 1, my, mz + nz * 1, "light[level=15,waterlogged=false]")
        NO_SNOW.add((fx, my + 2, fz))
    # belfry and a steep spire
    for x in range(cx0, cx0 + W):
        for z in range(cz0, cz0 + W):
            put(x, top_wall + 1, z, "dark_oak_planks")
    for y in range(top_wall + 2, top_wall + 5):
        for (x, z) in ((cx0, cz0), (cx0 + W - 1, cz0), (cx0, cz0 + W - 1), (cx0 + W - 1, cz0 + W - 1)):
            put(x, y, z, "dark_oak_fence[east=false,north=false,south=false,waterlogged=false,west=false]")
    put(mx, top_wall + 4, mz, "bell[attachment=ceiling,facing=north,powered=false]")
    put(mx, top_wall + 2, mz, "lantern[hanging=false,waterlogged=false]")
    for i in range(0, 10):
        r = 3.6 * (1 - i / 10)
        for x in range(-4, 5):
            for z in range(-4, 5):
                d = max(abs(x), abs(z))
                if d <= r and d > r - 1.1:
                    put(mx + x, top_wall + 5 + i, mz + z, "dark_oak_planks" if i % 2 else "deepslate_tiles")
    put(mx, top_wall + 15, mz, "gold_block")
    put(mx, top_wall + 16, mz, "end_rod[facing=up]")
    NO_SNOW.update({(mx, top_wall + 15, mz), (mx, top_wall + 16, mz)})
    put(cx0 + 2, FLOOR + 1, cz0 + W - 1, "spruce_door[facing=south,half=lower,hinge=left,open=false,powered=false]")
    put(cx0 + 2, FLOOR + 2, cz0 + W - 1, "spruce_door[facing=south,half=upper,hinge=left,open=false,powered=false]")
    for dx in (-1, 1):
        put(cx0 + 2 + dx, FLOOR + 3, cz0 + W, "lantern[hanging=false,waterlogged=false]", force=False)

    # ------------------------------------------------------------------ Santa's Workshop island
    ix, iz, ir = WORKSHOP_ISLAND
    for k in [k for k in B if math.hypot(k[0] - 0, k[2] - 58) <= 11 and -45 <= k[1] <= 15]:
        del B[k]                                                            # the old south islet
    V.island(ix, iz, ir, 30, FLOOR + 1, 631, wob=3)
    for x in range(-ir - 4, ir + 5):
        for z in range(-ir - 4, ir + 5):
            if math.hypot(x, z) <= V.edge_r(x, z, ir, 631, 3):
                put(ix + x, FLOOR, iz + z, "snow_block" if h(x, z, 7) > 0.15 else "packed_ice")
    # join it to the lookout with a short paved landing
    for z in range(V.LZ - 2, iz - 6):
        for x in range(-3, 4):
            put(x, FLOOR, z, "polished_deepslate" if abs(x) <= 1 else "polished_blackstone_bricks")
            put(x, FLOOR - 1, z, "deepslate_bricks")
            for y in range(FLOOR + 1, FLOOR + 6):
                if abs(x) <= 2:
                    B.pop((x, y, z), None)
    workshop(V, put, get, h, NO_SNOW, present, ix, iz)


def workshop(V, put, get, h, NO_SNOW, present, ix, iz):
    FLOOR = V.FLOOR
    x0, x1 = ix - 8, ix + 8
    z0, z1 = iz - 8, iz + 6                      # front (door) wall at z0, facing the street
    yw = FLOOR + 9                               # wall top
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            put(x, FLOOR, z, "dark_oak_planks" if x0 < x < x1 and z0 < z < z1 else "stone_bricks")
            wall = x in (x0, x1) or z in (z0, z1)
            for y in range(FLOOR + 1, yw + 1):
                if not wall:
                    V.blocks.pop((x, y, z), None)
                    continue
                post = (x in (x0, x1) and (z - z0) % 4 == 0) or (z in (z0, z1) and (x - x0) % 4 == 0)
                if y <= FLOOR + 2:
                    b = "stone_bricks"
                elif post or y in (FLOOR + 3, yw):
                    b = "stripped_spruce_log[axis=y]" if post else ("stripped_spruce_log[axis=x]" if z in (z0, z1) else "stripped_spruce_log[axis=z]")
                else:
                    b = "bricks"
                put(x, y, z, b)
    # windows (warm, lit) along the sides and front
    for z in range(z0 + 2, z1 - 1, 4):
        for x in (x0, x1):
            for y in (FLOOR + 5, FLOOR + 6):
                put(x, y, z, "yellow_stained_glass")
    for x in (x0 + 2, x1 - 2):
        for y in (FLOOR + 5, FLOOR + 6):
            put(x, y, z0, "yellow_stained_glass")
    # big double doors (open: walk straight in), frame, wreath, candy canes
    for x in range(ix - 1, ix + 2):
        for y in range(FLOOR + 1, FLOOR + 5):
            V.blocks.pop((x, y, z0), None)
    for y in range(FLOOR + 1, FLOOR + 6):
        put(ix - 2, y, z0 - 1, "stripped_dark_oak_log[axis=y]")
        put(ix + 2, y, z0 - 1, "stripped_dark_oak_log[axis=y]")
    for x in range(ix - 2, ix + 3):
        put(x, FLOOR + 5, z0 - 1, "stripped_dark_oak_log[axis=x]")
    for a in range(-2, 3):
        for b in range(-2, 3):
            if 1.4 <= math.hypot(a, b) <= 2.3:
                put(ix + a, FLOOR + 8 + b, z0 - 1, "red_concrete" if (a, b) in ((0, -2),) else "spruce_leaves[distance=1,persistent=true,waterlogged=false]")
    for s in (-1, 1):
        for i, y in enumerate(range(FLOOR + 1, FLOOR + 5)):
            put(ix + s * 4, y, z0 - 2, "red_concrete" if i % 2 else "white_concrete")
        put(ix + s * 4 - s, FLOOR + 5, z0 - 2, "red_concrete")
        put(ix + s * 4 - s, FLOOR + 4, z0 - 2, "lantern[hanging=true,waterlogged=false]")
        NO_SNOW.add((ix + s * 4, FLOOR + 4, z0 - 2))
    put(ix, FLOOR + 3, z0 - 3, "light[level=15,waterlogged=false]")
    # steep red roof, gable to the street, snow on it (snow pass), a round lit window in the gable
    hw = (x1 - x0) // 2 + 1
    for i in range(0, hw + 1):
        y = yw + 1 + i
        for x in (ix - hw + i, ix + hw - i):
            for z in range(z0 - 1, z1 + 2):
                put(x, y, z, "red_terracotta" if (z + i) % 2 else "red_nether_bricks")
        for x in range(ix - hw + i + 1, ix + hw - i):
            for z in (z0, z1):
                put(x, y, z, "bricks")
    for a in range(-2, 3):
        for b in range(-2, 3):
            if math.hypot(a, b) <= 1.8:
                put(ix + a, yw + 4 + b, z0, "yellow_stained_glass")
                put(ix + a, yw + 4 + b, z0 + 1, "light[level=15,waterlogged=false]")
    # chimney with smoke
    for y in range(yw - 2, yw + hw + 4):
        put(x1 - 3, y, z1 - 2, "stone_bricks" if h(y, 5) > 0.3 else "cobblestone")
    put(x1 - 3, yw + hw + 4, z1 - 2, "campfire[facing=north,lit=true,signal_fire=false,waterlogged=false]")
    NO_SNOW.add((x1 - 3, yw + hw + 4, z1 - 2))

    # ---- the interior
    yi = FLOOR + 1
    for z in range(z0 + 1, z1):                                   # red runner to the fireplace
        for x in (ix - 1, ix, ix + 1):
            put(x, yi, z, "red_carpet" if x == ix else "lime_carpet")
    # workbenches down the left wall, toy shelves down the right
    benches = ["crafting_table", "smithing_table", "loom[facing=east]", "cartography_table", "fletching_table", "crafting_table"]
    for i, z in enumerate(range(z0 + 2, z1 - 1, 2)):
        put(x0 + 1, yi, z, benches[i % len(benches)])
        put(x0 + 2, yi, z, "red_wool" if i % 2 else "lime_wool")                       # a toy on the go
        put(x0 + 1, yi + 1, z, ["candle[candles=3,lit=true,waterlogged=false]", "lantern[hanging=false,waterlogged=false]"][i % 2])
    for z in range(z0 + 1, z1):
        for y in range(yi, yi + 4):
            if (z - z0) % 4 == 0:
                put(x1 - 1, y, z, "stripped_spruce_log[axis=y]")
            else:
                put(x1 - 1, y, z, ["bookshelf", "red_wool", "yellow_wool", "light_blue_wool", "chiseled_bookshelf[facing=west,slot_0_occupied=true,slot_1_occupied=true,slot_2_occupied=false,slot_3_occupied=true,slot_4_occupied=false,slot_5_occupied=true]"][int(h(z, y, 9) * 5)])
    # fireplace on the back wall with stockings on the mantel
    for x in range(ix - 3, ix + 4):
        for y in range(yi, yi + 6):
            if abs(x - ix) <= 1 and y <= yi + 1:
                put(x, y, z1 - 1, "campfire[facing=north,lit=true,signal_fire=false,waterlogged=false]" if y == yi else "air")
                continue
            put(x, y, z1 - 1, "stone_bricks" if y < yi + 3 or abs(x - ix) >= 2 else "chiseled_stone_bricks")
        put(x, yi + 3, z1 - 2, "spruce_slab[type=top,waterlogged=false]")                # mantel
    for i, x in enumerate((ix - 3, ix - 1, ix + 1, ix + 3)):
        put(x, yi + 2, z1 - 2, ["red_wool", "white_wool"][i % 2] if False else "red_wool")
        put(x, yi + 1, z1 - 2, "white_wool")                                            # stockings
    put(ix - 2, yi + 4, z1 - 2, "candle[candles=2,lit=true,waterlogged=false]")
    put(ix + 2, yi + 4, z1 - 2, "candle[candles=2,lit=true,waterlogged=false]")
    # Santa's armchair and the Nice List
    put(ix - 4, yi, z1 - 3, "red_wool"); put(ix - 4, yi + 1, z1 - 2, "red_wool")
    put(ix + 4, yi, z1 - 3, "lectern[facing=north,has_book=true,powered=false]")
    # the sleigh being loaded, front left
    sx, sz = ix - 5, z0 + 2
    for dz in range(0, 5):
        put(sx, yi, sz + dz, "gold_block" if dz in (0, 4) else "air")
        put(sx + 2, yi, sz + dz, "gold_block" if dz in (0, 4) else "air")
        for dx in range(0, 3):
            put(sx + dx, yi + 1, sz + dz, "red_concrete")
            if dx in (0, 2) or dz in (0, 4):
                put(sx + dx, yi + 2, sz + dz, "red_concrete")
    put(sx + 1, yi + 2, sz + 2, "brown_wool"); put(sx + 1, yi + 3, sz + 2, "brown_wool")   # the sack
    present(sx + 1, yi + 2, sz + 1, 1, 5501)
    # presents piled round the room, chandeliers
    for i in range(8):
        a = h(i, 61)
        px_, pz_ = ix - 4 + int(h(i, 62) * 9), z0 + 2 + int(h(i, 63) * (z1 - z0 - 4))
        if V.get(px_, yi, pz_) is None and abs(px_ - ix) > 1:
            present(px_, yi, pz_, 1, 5600 + i)
    for (cx, cz) in ((ix, z0 + 4), (ix, z1 - 4)):
        for y in range(yi + 5, yw):
            put(cx, y, cz, "iron_chain[axis=y,waterlogged=false]")
        put(cx, yi + 4, cz, "lantern[hanging=true,waterlogged=false]")
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            put(cx + dx, yi + 5, cz + dz, "light[level=15,waterlogged=false]")
    # roof inside: a dark plank ceiling
    for x in range(x0 + 1, x1):
        for z in range(z0 + 1, z1):
            put(x, yw, z, "dark_oak_planks")
