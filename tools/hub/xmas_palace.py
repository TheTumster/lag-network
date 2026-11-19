"""Christmas hub: the Ice Palace round the Great Tree, and the ice bridge to it.

The Great Tree's island grows into a courtyard ringed by a crenellated ice curtain wall and eight glowing
ice towers that climb toward the back (so from spawn their spires rise over the portal houses on both sides
of the tree). A gatehouse with a tall pointed arch faces the hub; a white bridge runs to it from the gap
between the middle portal houses, so players can walk from spawn straight to the tree. A toy-train track
rings the tree inside the walls (the train itself is a plugin animation).
"""
import math

WALL_R = 27.0
TRACK_R = 22.5
# towers: angle (degrees from straight toward the hub, + = east), body height, spire height
TOWERS = [(-32, 46, 22), (32, 46, 22), (-78, 54, 24), (78, 54, 24), (-122, 62, 25), (122, 62, 25), (-160, 70, 26), (160, 70, 26)]


def build(V, TX, TZ, TTOP, NO_SNOW, parkour_keepout):
    put, get, h = V.put, V.get, V.h

    def ok(x, y, z):
        return not parkour_keepout(x, y, z)

    def P(x, y, z, b, force=True):
        if ok(x, y, z):
            put(x, y, z, b, force=force)

    def at(ang_deg, r):
        a = math.radians(ang_deg)
        return TX + math.sin(a) * r, TZ + math.cos(a) * r      # angle 0 points +z (toward the hub)

    def ice(x, y, z):
        r = h(x, y, z, 501)
        return "packed_ice" if r < 0.62 else "blue_ice" if r < 0.86 else "snow_block"

    base = TTOP + 1
    gate_half = 6.5                                             # the gatehouse arch's half-width at the wall
    # ---------------------------------------------------------------- curtain wall
    for x in range(int(TX - WALL_R - 3), int(TX + WALL_R + 4)):
        for z in range(int(TZ - WALL_R - 3), int(TZ + WALL_R + 4)):
            d = math.hypot(x - TX, z - TZ)
            if not (WALL_R - 1.6 < d <= WALL_R + 0.4):
                continue
            ang = math.degrees(math.atan2(x - TX, z - TZ))
            if abs(ang) < 15 and abs(x - TX) <= gate_half + 4:
                continue                                        # the gatehouse goes here
            for y in range(TTOP - 2, base + 11):
                band = y in (base, base + 10)
                win = (y in (base + 5, base + 6)) and int((ang + 180) / 6) % 3 == 0 and d > WALL_R - 0.6
                P(x, y, z, "light_blue_stained_glass" if win else "blue_ice" if band else ice(x, y, z))
            if int((ang + 180) * WALL_R / 57.3) % 2 == 0 and d > WALL_R - 0.8:
                P(x, base + 11, z, ice(x, base + 11, z))       # crenellations
            if d <= WALL_R - 1.0 and int((ang + 180) * 2) % 9 == 0:
                P(x, base + 6, z, "light[level=13,waterlogged=false]")
    # ---------------------------------------------------------------- towers
    for (ang, hb, sh) in TOWERS:
        cx, cz = at(ang, WALL_R - 0.5)
        cx, cz = round(cx), round(cz)
        r = 4.6
        for y in range(TTOP - 6, base + hb):
            for dx in range(-6, 7):
                for dz in range(-6, 7):
                    d = math.hypot(dx, dz)
                    if r - 1.6 < d <= r + (0.6 if y < TTOP + 1 else 0):
                        side = math.degrees(math.atan2(dx, dz))
                        slot = (y - base) % 9 in (3, 4, 5) and min(abs(((side - s + 180) % 360) - 180) for s in (0, 90, 180, 270)) < 14
                        band = (y - base) % 12 == 0
                        P(cx + dx, y, cz + dz, "light_blue_stained_glass" if slot else "blue_ice" if band else ice(cx + dx, y, cz + dz))
            if (y - base) % 9 == 4:
                for dx, dz in ((0, 2), (0, -2), (2, 0), (-2, 0)):
                    P(cx + dx, y, cz + dz, "light[level=15,waterlogged=false]")
        # cornice ring and a balcony of crenellations
        yc = base + hb
        for dx in range(-7, 8):
            for dz in range(-7, 8):
                d = math.hypot(dx, dz)
                if d <= r + 1.2:
                    P(cx + dx, yc, cz + dz, "blue_ice" if d > r - 0.5 else "packed_ice")
                if r + 0.3 < d <= r + 1.2 and (dx + dz) % 2 == 0:
                    P(cx + dx, yc + 1, cz + dz, "packed_ice")
        # spire: a glass cone lit from inside, glowing ice-blue and white
        for i in range(sh):
            rr = (r + 0.4) * (1 - i / sh) ** 1.15
            for dx in range(-6, 7):
                for dz in range(-6, 7):
                    d = math.hypot(dx, dz)
                    if rr - 1.1 < d <= rr + 0.15:
                        P(cx + dx, yc + 1 + i, cz + dz, "light_blue_stained_glass" if (i // 2 + int(math.degrees(math.atan2(dx, dz)) // 45)) % 2 else "white_stained_glass")
            P(cx, yc + 1 + i, cz, "sea_lantern" if i % 3 == 0 else "light[level=15,waterlogged=false]")
        P(cx, yc + 1 + sh, cz, "packed_ice")
        P(cx, yc + 2 + sh, cz, "end_rod[facing=up]")
        NO_SNOW.add((cx, yc + 2 + sh, cz))
    # ---------------------------------------------------------------- gatehouse facing the hub
    gz = round(TZ + WALL_R - 0.5)
    for x in range(int(TX - gate_half - 4), int(TX + gate_half + 5)):
        dx = x - TX
        for y in range(TTOP - 2, base + 21):
            dy = y - base
            for z in (gz - 1, gz, gz + 1):
                arch = abs(dx) <= 4 and (dy <= 9 or math.hypot(abs(dx) + 4.5, dy - 9) <= 8.6 and dy <= 15)
                inner = abs(dx) <= 4 and (dy <= 10 or math.hypot(abs(dx) + 4.5, dy - 9) <= 9.5 and dy <= 16)
                if arch and dy >= 0:
                    continue
                if inner and dy >= 0:
                    P(x, y, z, "blue_ice" if z == gz + 1 else "packed_ice")    # arch moulding
                    continue
                top = 20 - max(0, abs(dx) - 6) * 2
                if dy > top:
                    continue
                P(x, y, z, ice(x, y, z))
        if abs(dx) % 2 == 0:
            top = 20 - max(0, abs(dx) - 6) * 2
            P(x, base + top + 1, gz, ice(x, base + top + 1, gz))
    # a snowflake over the arch, glowing
    for k in range(6):
        a = k * math.pi / 3
        for t in range(0, 4):
            P(round(TX + math.sin(a) * t), base + 18 + round(math.cos(a) * t), gz + 2, "sea_lantern" if t == 0 else "white_stained_glass")
            NO_SNOW.add((round(TX + math.sin(a) * t), base + 18 + round(math.cos(a) * t), gz + 2))
    for dx in (-3, 3):
        P(TX + dx, base + 4, gz + 3, "light[level=14,waterlogged=false]")
    # ---------------------------------------------------------------- the toy-train track round the tree
    for x in range(int(TX - TRACK_R - 2), int(TX + TRACK_R + 3)):
        for z in range(int(TZ - TRACK_R - 2), int(TZ + TRACK_R + 3)):
            d = math.hypot(x - TX, z - TZ)
            if TRACK_R - 1.3 <= d <= TRACK_R + 1.3:
                for y in range(base, base + 4):
                    if (x, y, z) in V.blocks:
                        del V.blocks[(x, y, z)]
                rail = abs(d - (TRACK_R - 0.9)) < 0.45 or abs(d - (TRACK_R + 0.9)) < 0.45
                P(x, TTOP, z, "dark_oak_planks" if not rail else "polished_blackstone")
                if rail:
                    P(x, base, z, "polished_blackstone_slab[type=bottom,waterlogged=false]")
                NO_SNOW.add((x, TTOP, z)); NO_SNOW.add((x, base, z))
    return gz


def bridge(V, z_from, z_to, y_from, y_to, NO_SNOW, parkour_keepout):
    """A white bridge, 5 wide, from the hub's terrace to the palace gate; ice parapets, lanterns, ice arches below."""
    put, h = V.put, V.h
    n = z_from - z_to
    for i, z in enumerate(range(z_from, z_to - 1, -1)):
        t = i / n
        yf = y_from + (y_to - y_from) * max(0.0, (t - 0.55) / 0.45)       # flat, then rises over the last part
        yb = int(math.floor(yf))
        half = (yf - yb) >= 0.5
        for x in range(-3, 4):
            for y in range(yb, yb + 7):
                if (x, y, z) in V.blocks and not parkour_keepout(x, y, z):
                    del V.blocks[(x, y, z)]
            if abs(x) <= 2:
                put(x, yb, z, "smooth_quartz" if abs(x) < 2 else "blue_ice")
                put(x, yb - 1, z, "packed_ice")
                if half:
                    put(x, yb + 1, z, "smooth_quartz_slab[type=bottom,waterlogged=false]" if abs(x) < 2 else "blue_ice")
                NO_SNOW.add((x, yb, z)); NO_SNOW.add((x, yb + 1, z))
            else:
                hy = yb + (1 if half else 0)
                put(x, hy, z, "packed_ice")
                put(x, hy + 1, z, "packed_ice" if z % 5 else "spruce_fence[east=false,north=false,south=false,waterlogged=false,west=false]")
                if z % 5 == 0:
                    put(x, hy + 2, z, "lantern[hanging=false,waterlogged=false]")
                    NO_SNOW.add((x, hy + 2, z))
        # hanging ice arches underneath every ten blocks
        if z % 10 == 0:
            for x in range(-3, 4):
                for k in range(1, 7 - abs(x)):
                    put(x, yb - 1 - k, z, "blue_ice" if k % 2 else "packed_ice", force=False)
