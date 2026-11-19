"""Christmas hub: the four realm gates become themed portal houses.

  Skyblock  the Gingerbread House      gingerbread walls, white icing drips, candy-cane corners, gumdrops, lollipops
  Factions  the Nutcracker Fortress    black and red gatehouse, gold trim, two drum towers, two nutcracker soldiers
  Cannons   the Toy Workshop           timber-frame two storey workshop, giant toy blocks, a toy cannon on the ridge
  SMP       the Frost Gate             ice gatehouse, two glowing ice spires, icicles, snowflake emblem

Every house keeps the old gate's lancet portal at the same plane (x = px-2..px+2, z = PORTAL_Z, 10 high), the
realm-icon spot (centre dx 0, dy 14, floating in front) and the label spot (dy 11-13 in front of the portal), so
the plugin's portals, holograms and NPCs line up unchanged. Local coords: dx from px, dy from the terrace
top (y0), dz from the portal plane (+ = toward the plaza).
"""
import math

ANCHOR = (7, 11, 1)        # eave corners: the garlands between the houses hang from (+-7, 11, +1)


def build(V, realm, px, NO_SNOW):
    put, get, h = V.put, V.get, V.h
    y0 = V.TERRACE_Y + 1
    PZ = V.PORTAL_Z

    def P(dx, dy, dz, b, force=True):
        put(px + dx, y0 + dy, PZ + dz, b, force=force)
        return (px + dx, y0 + dy, PZ + dz)

    def opening(dx, dy):
        t = {0: 9, 1: 8, 2: 6}.get(abs(dx), -1)
        return abs(dx) <= 2 and 0 <= dy <= t

    def around(dx, dy, r=1):
        return not opening(dx, dy) and any(opening(dx + a, dy + b) for a in range(-r, r + 1) for b in range(-r, r + 1))

    def portal(frame_a, frame_b):
        for dx in range(-3, 4):
            for dy in range(0, 11):
                if opening(dx, dy):
                    P(dx, dy, 0, "nether_portal[axis=x]")
                    P(dx, dy, -1, "obsidian")
                elif around(dx, dy):
                    P(dx, dy, 0, frame_a)
                    P(dx, dy, 1, "light[level=12,waterlogged=false]", force=False)
                elif around(dx, dy, 2):
                    P(dx, dy, 0, frame_b)

    def emblem(ring, core, back):
        for dx in range(-3, 4):
            for dy in range(11, 18):
                d = math.hypot(dx, dy - 14)
                if d <= 1.3:
                    P(dx, dy, 0, back)
                elif d <= 2.3:
                    P(dx, dy, 0, ring)
        P(0, 14, 0, core)

    def light(dx, dy, dz, lv=13):
        P(dx, dy, dz, f"light[level={lv},waterlogged=false]", force=False)

    if realm == "skyblock":
        gingerbread(V, P, h, portal, emblem, light, NO_SNOW)
    elif False:
        pass
    elif realm == "factions":
        nutcracker_fort(V, P, h, portal, emblem, light, NO_SNOW)
    elif realm == "cannons":
        toy_workshop(V, P, h, portal, emblem, light, NO_SNOW)
    else:
        frost_gate(V, P, h, portal, emblem, light, NO_SNOW)
    # the realm's beacon beam still rises behind the portal: glass of its colour wherever the house is solid
    c = V.COLOR[realm]
    for dy in range(0, 45):
        b = get(px, y0 + dy, PZ - 3)
        if b and "light[" not in b:
            P(0, dy, -3, f"{c}_stained_glass")


# ------------------------------------------------------------------------------------------------ Skyblock
def gingerbread(V, P, h, portal, emblem, light, NO_SNOW):
    GB, GB_D = "terracotta", "brown_terracotta"
    ICE = "white_concrete"
    ROOF = lambda dx: 10 + (8 - abs(dx)) * 9 // 8          # roof line by |dx|: 10 at the eaves, 19 at the ridge
    # walls + gable: a solid gingerbread body 15 wide, 7 deep
    for dx in range(-7, 8):
        for dz in range(-6, 1):
            top = ROOF(dx) - 1
            for dy in range(0, top + 1):
                side = dz in (0, -6) or abs(dx) == 7
                if not side and dy > 0:
                    continue
                b = GB if h(dx, dy, dz, 11) > 0.18 else GB_D
                P(dx, dy, dz, b)
    # roof: chocolate shingles with thick white icing on top and drips down the gable edge
    for dx in range(-8, 9):
        ry = ROOF(dx)
        for dz in range(-7, 2):
            P(dx, ry, dz, "brown_concrete" if (dx + dz) % 2 else "brown_terracotta")
            P(dx, ry + 1, dz, ICE if h(dx, dz, 12) > 0.15 else "snow_block")
        # drips on the front and back gable edges
        for dz in (1, -7):
            for k in range(1, 1 + int(h(dx, 13, dz) * 3)):
                if dz == -7 or ry - k >= 14 or abs(dx) >= 6:
                    P(dx, ry - k, dz, ICE)
        # gumdrops along the ridge line and eaves
        if dx % 2 == 0:
            P(dx, ry + 2, 1, ["lime_concrete", "red_concrete", "yellow_concrete", "purple_concrete"][(dx // 2) % 4])
            NO_SNOW.add(P(dx, ry + 2, 1, ["lime_concrete", "red_concrete", "yellow_concrete", "purple_concrete"][(dx // 2) % 4]))
    P(0, 21, 1, "red_concrete"); P(0, 22, 1, "white_concrete"); NO_SNOW.add(P(0, 23, 1, "red_concrete"))   # a cherry on top
    # candy-cane corner posts, standing proud
    for s in (-1, 1):
        for dy in range(0, 12):
            P(s * 8, dy, 1, "red_concrete" if (dy // 2) % 2 else "white_concrete")
        P(s * 8, 12, 1, "white_concrete"); P(s * 8 - s, 12, 1, "red_concrete"); P(s * 8 - s, 11, 1, "white_concrete")
    # icing trim: a white band at the eaves line and round the windows; candy buttons up the facade
    for dx in range(-7, 8):
        P(dx, 0, 1, ICE)
    portal("lime_concrete", ICE)
    for s in (-1, 1):
        cx = s * 5
        for dx in (-1, 0, 1):
            for dy in (4, 5, 6):
                edge = abs(dx) == 1 or dy in (4, 6)
                P(cx + dx, dy, 0, ICE if edge and not (abs(dx) == 1 and dy in (4, 6)) else "yellow_stained_glass" if not edge else GB)
        P(cx, 5, -1, "light[level=15,waterlogged=false]")
        P(cx, 3, 1, "lime_concrete"); P(cx - 1, 3, 1, "white_concrete"); P(cx + 1, 3, 1, "white_concrete")   # window box
        for dy in (1, 8, 10):
            P(s * 4, dy, 1, ["red_concrete", "lime_concrete", "yellow_concrete"][dy % 3])            # candy buttons
    emblem("lime_concrete", "pearlescent_froglight", ICE)
    # lollipops either side: white sticks, red/white swirl discs
    for s in (-1,):
        lx = s * 10
        for dy in range(0, 7):
            P(lx, dy, 2, "white_concrete")
        for a in range(-3, 4):
            for b in range(-3, 4):
                d = math.hypot(a, b)
                if d <= 2.9:
                    ang = math.atan2(b, a) + d * 0.9
                    P(lx + a, 9 + b, 2, "red_concrete" if math.sin(ang * 2) > 0 else "white_concrete")
        NO_SNOW.update({(0, 0, 0)})
        light(lx, 9, 3, 12)
    for dx in (-6, 6):
        light(dx, 2, 3, 14)


# ------------------------------------------------------------------------------------------------ Factions
def nutcracker_fort(V, P, h, portal, emblem, light, NO_SNOW):
    BLK = "polished_blackstone_bricks"
    RED = "red_concrete"
    GOLD = "gold_block"
    # central gatehouse block: dx -4..4, up to dy 16, crenellated
    for dx in range(-5, 6):
        for dz in range(-6, 1):
            for dy in range(0, 17):
                side = dz in (0, -6) or abs(dx) == 5
                if not side and dy not in (0, 16):
                    continue
                b = RED if dy in (12, 13) else GOLD if dy == 11 and dz == 0 else BLK
                P(dx, dy, dz, b)
        if dx % 2 == 0:
            for dz in (0, -6):
                P(dx, 17, dz, BLK)
                P(dx, 18, dz, "polished_blackstone_brick_wall[east=none,north=none,south=none,up=true,waterlogged=false,west=none]")
    # drum towers either side with red cone roofs and gold finials
    for s in (-1, 1):
        cx, cz = s * 6, -2
        for dy in range(0, 21):
            for a in range(-3, 4):
                for b in range(-3, 4):
                    d = math.hypot(a, b)
                    if 1.3 < d <= 2.4:
                        bb = RED if dy in (6, 7, 14, 15) else GOLD if dy in (8, 16) and d > 2.4 else BLK
                        if dy in (3, 4, 10, 11, 18) and abs(b) <= 0 and a == s * 0 and False:
                            pass
                        P(cx + a, dy, cz + b, bb)
        # arrow-slit windows glowing
        for dy in (3, 4, 10, 11):
            P(cx, dy, cz + 3, "orange_stained_glass")
            P(cx, dy, cz + 2, "light[level=14,waterlogged=false]")
        for i in range(9):
            r = 2.9 * (1 - i / 9)
            for a in range(-4, 5):
                for b in range(-4, 5):
                    if math.hypot(a, b) <= r + 0.3 and math.hypot(a, b) > r - 1.2:
                        P(cx + a, 21 + i, cz + b, RED if (i % 3) else "red_terracotta")
        P(cx, 30, cz, GOLD); NO_SNOW.add(P(cx, 31, cz, "end_rod[facing=up]"))
    portal("red_concrete", GOLD)
    emblem(GOLD, "shroomlight", BLK)
    # gold trim band across the front at the battlements
    for dx in range(-5, 6):
        P(dx, 16, 1, GOLD if dx % 2 == 0 else BLK)
    # the nutcracker soldiers standing guard in front of the towers
    for s in (-1, 1):
        nutcracker(P, s * 6, 2, light, NO_SNOW)


def nutcracker(P, cx, cz, light, NO_SNOW):
    """A 14-high nutcracker soldier, 3 wide, facing the plaza."""
    def L(dx, dy, dz, b):
        P(cx + dx, dy, cz + dz, b)
    for dx in (-1, 0, 1):
        for dz in (0, 1):
            L(dx, 0, dz, "black_concrete")
            L(dx, 1, dz, "black_concrete" if dx != 0 else "black_concrete")
            for dy in (2, 3):
                L(dx, dy, dz, "white_concrete" if dx != 0 else "black_concrete")     # white trousers, black stripe
            for dy in range(4, 8):
                L(dx, dy, dz, "red_concrete")
            L(dx, 6, dz, "white_concrete" if dz == 1 else "red_concrete")            # belt
            for dy in (8, 9):
                L(dx, dy, dz, "white_terracotta")                                     # face
            for dy in (10, 11, 12):
                L(dx, dy, dz, "black_concrete")                                       # tall hat
    L(0, 6, 1, "gold_block")                                                          # buckle
    for dy in (4, 5, 7):
        L(0, dy, 2, "gold_block")                                                     # buttons
    L(-1, 9, 2, "black_concrete"); L(1, 9, 2, "black_concrete")                       # eyes
    L(0, 8, 2, "white_concrete")                                                      # moustache / jaw
    L(-1, 8, 2, "white_concrete"); L(1, 8, 2, "white_concrete")
    L(0, 9, 2, "red_terracotta")                                                      # nose
    for dx in (-1, 0, 1):
        L(dx, 10, 2, "gold_block")                                                    # hat band
    L(0, 13, 1, "red_wool"); L(0, 14, 1, "red_wool"); NO_SNOW.add((0, 0, 0))          # plume
    for s in (-1, 1):                                                                 # arms
        for dy in (5, 6, 7):
            L(s * 2, dy, 1, "red_concrete")
        L(s * 2, 4, 1, "white_concrete")
    light(cx, 6, cz + 3, 15)
    light(cx, 10, cz + 3, 15)


# ------------------------------------------------------------------------------------------------ Cannons
def toy_workshop(V, P, h, portal, emblem, light, NO_SNOW):
    FRAME = "stripped_dark_oak_log"
    LOWER, UPPER = "orange_terracotta", "white_terracotta"
    for dx in range(-7, 8):
        for dz in range(-6, 1):
            for dy in range(0, 15):
                jetty = 0
                side = dz in (0 + jetty * 0, -6) or abs(dx) == 7
                if not side and dy not in (0, 7):
                    continue
                post = abs(dx) in (7, 4) or dy in (0, 6, 7, 14)
                b = (FRAME + "[axis=y]") if post and abs(dx) in (7, 4) else (FRAME + "[axis=x]") if post else (LOWER if dy < 7 else UPPER)
                P(dx, dy, dz, b)
    # steep roof, red and green tiles, gable to the front
    for i in range(0, 9):
        dy = 15 + i
        w = 8 - i
        for dx in range(-w, w + 1):
            for dz in range(-7, 3):
                edge = abs(dx) == w
                if edge or dz in (-7, 2):
                    P(dx, dy, dz, ("red_terracotta" if (dz + i) % 2 else "green_terracotta") if edge else UPPER)
    # windows: lower shop windows either side of the portal, upper diamond windows
    for s in (-1, 1):
        for dx in (s * 5, s * 6):
            for dy in (2, 3, 4):
                P(dx, dy, 0, "yellow_stained_glass")
                P(dx, dy, -1, "light[level=15,waterlogged=false]")
        for dy in (9, 10, 11):
            P(s * 5, dy, 0, "yellow_stained_glass")
            P(s * 5, dy, -1, "light[level=15,waterlogged=false]")
    portal("orange_concrete", FRAME + "[axis=y]")
    emblem("orange_concrete", "ochre_froglight", UPPER)        # the workshop's big clock face
    for k in range(4):
        a = k * math.pi / 2
        P(round(math.cos(a) * 1.0), 14 + round(math.sin(a) * 1.0), 0, "black_concrete")
    # a toy cannon on the ridge, pointing up at the sky
    for t in range(0, 7):
        y = 24 + t * 0.55
        z = -2 + t * 0.85
        for a in (-1, 0, 1):
            for b in (-1, 0, 1):
                if abs(a) + abs(b) <= 1:
                    P(a, round(y) + b, round(z), "red_concrete" if t % 3 else "gold_block")
    for s in (-1, 1):
        for a in range(-2, 3):
            for b in range(-2, 3):
                if 1.2 < math.hypot(a, b) <= 2.4:
                    P(s * 2, 24 + b, -2 + a, "yellow_concrete" if (a + b) % 2 else "orange_concrete")   # wheels
    light(0, 26, 2, 14)
    # giant toy blocks stacked beside the door
    blocks = [(9, 0, 1, "lime"), (9, 2, 0, "red"), (9, 0, -1, "blue"), (11, 0, 0, "yellow")]
    for (bx, by, bz, c) in blocks:
        for a in (0, 1):
            for b in (0, 1):
                for e in (0, 1):
                    edge = (a + b + e) % 2 == 0
                    P(bx + a, by + b, bz + e, f"{c}_concrete" if edge else "white_concrete")
                    NO_SNOW.add((0, 0, 0))
    for dx in (-6, 6):
        light(dx, 2, 3, 14)


# ------------------------------------------------------------------------------------------------ SMP
def frost_gate(V, P, h, portal, emblem, light, NO_SNOW):
    def ice(dx, dy, dz):
        r = h(dx, dy, dz, 21)
        return "packed_ice" if r < 0.55 else "blue_ice" if r < 0.85 else "snow_block"
    for dx in range(-5, 6):
        for dz in range(-5, 1):
            top = 16 - max(0, abs(dx) - 2)
            for dy in range(0, top + 1):
                side = dz in (0, -5) or abs(dx) == 5
                if not side and dy not in (0, top):
                    continue
                P(dx, dy, dz, ice(dx, dy, dz))
    # pointed gable peak
    for i in range(0, 6):
        for dx in range(-(3 - i // 2), 4 - i // 2):
            P(dx, 17 + i, 0, ice(dx, 17 + i, 0))
            P(dx, 17 + i, -1, ice(dx, 17 + i, -1))
    # twin glowing spires: glass cones lit from inside, on ice towers
    for s in (-1, 1):
        cx = s * 7
        for dy in range(0, 18):
            for a in range(-2, 3):
                for b in range(-2, 3):
                    d = math.hypot(a, b)
                    if 1.0 < d <= 2.2:
                        P(cx + a, dy, -2 + b, "light_blue_stained_glass" if dy % 5 == 2 and d > 1.8 else ice(cx + a, dy, b))
            P(cx, dy, -2, "sea_lantern" if dy % 4 == 0 else "light[level=15,waterlogged=false]")
        for i in range(0, 12):
            r = 2.6 * (1 - i / 12)
            for a in range(-3, 4):
                for b in range(-3, 4):
                    d = math.hypot(a, b)
                    if d <= r + 0.2 and d > r - 1.0:
                        P(cx + a, 18 + i, -2 + b, "light_blue_stained_glass" if i % 2 else "white_stained_glass")
            P(cx, 18 + i, -2, "sea_lantern")
        NO_SNOW.add(P(cx, 30, -2, "end_rod[facing=up]"))
    portal("light_blue_concrete", "blue_ice")
    emblem("light_blue_concrete", "sea_lantern", "packed_ice")
    for k in range(6):                                     # a snowflake in the emblem
        a = k * math.pi / 3
        P(round(math.cos(a) * 1.0), 14 + round(math.sin(a) * 1.0), 0, "white_stained_glass")
    # icicles hanging along the front eaves
    for dx in range(-5, 6):
        top = 16 - max(0, abs(dx) - 2)
        for k in range(1, 1 + int(h(dx, 31) * 3)):
            P(dx, top - k + 1, 1, "ice")
    for dx in (-4, 4):
        light(dx, 2, 3, 14)
