"""Quick isometric preview of a {(x,y,z): 'minecraft:block[...]'} build, for checking layouts without a client."""
import math
from PIL import Image, ImageDraw

COLORS = [
    ("nether_portal", (122, 42, 216)), ("water", (52, 98, 214)), ("lava", (240, 110, 20)), ("cherry_leaves", (240, 172, 206)),
    ("flowering_azalea_leaves", (110, 150, 60)), ("azalea_leaves", (90, 130, 45)), ("cherry_log", (60, 30, 40)), ("cherry_wood", (60, 30, 40)),
    ("pink_petals", (245, 160, 200)), ("grass_block", (98, 152, 60)), ("short_grass", (110, 170, 70)), ("tall_grass", (110, 170, 70)),
    ("fern", (80, 140, 60)), ("moss_carpet", (95, 125, 45)), ("moss_block", (95, 125, 45)), ("flowering_azalea", (120, 150, 80)),
    ("azalea", (95, 135, 55)), ("lily_pad", (40, 120, 40)), ("seagrass", (40, 110, 60)), ("podzol", (90, 63, 30)),
    ("rooted_dirt", (140, 100, 70)), ("coarse_dirt", (110, 80, 55)), ("dirt", (125, 88, 60)), ("allium", (190, 110, 230)),
    ("azure_bluet", (230, 230, 240)), ("oxeye", (240, 240, 220)), ("cornflower", (80, 110, 230)), ("lily_of", (240, 240, 240)),
    ("poppy", (220, 40, 40)), ("pearlescent_froglight", (250, 220, 240)), ("calcite", (225, 225, 220)),
    ("chiseled_stone_bricks", (130, 130, 130)), ("mossy_stone_brick", (110, 122, 95)), ("mossy_cobblestone", (100, 115, 90)),
    ("cracked_stone", (112, 112, 112)), ("stone_brick", (122, 122, 122)), ("polished_andesite", (140, 143, 143)),
    ("andesite", (132, 134, 133)), ("polished_deepslate", (70, 70, 74)), ("deepslate", (80, 80, 86)), ("tuff", (108, 109, 102)),
    ("cobbled", (78, 78, 82)), ("stone", (126, 126, 126)), ("diamond_ore", (90, 200, 200)), ("emerald_ore", (60, 200, 100)),
    ("gold_ore", (230, 200, 70)), ("iron_ore", (200, 170, 150)), ("copper_ore", (190, 120, 80)), ("lapis_ore", (40, 70, 180)),
    ("redstone_ore", (180, 30, 30)), ("coal_ore", (50, 50, 50)), ("amethyst", (160, 110, 210)), ("purpur", (170, 125, 170)),
    ("end_rod", (250, 250, 250)), ("lantern", (255, 200, 110)), ("iron_chain", (60, 60, 70)), ("stripped_spruce", (115, 90, 55)),
    ("spruce", (110, 80, 50)), ("oak_log", (100, 80, 50)), ("barrel", (125, 90, 55)), ("hay", (210, 180, 40)), ("composter", (120, 80, 40)),
    ("lime_", (110, 200, 30)), ("cyan_", (20, 140, 150)), ("white_", (235, 235, 235)), ("dripstone", (135, 105, 85)),
    ("cave_vines", (90, 120, 40)), ("hanging_roots", (150, 110, 90)), ("spore_blossom", (230, 120, 190)), ("vine", (60, 110, 30)),
    ("clay", (160, 165, 180)), ("gravel", (130, 125, 125)), ("sand", (220, 210, 160)), ("dispenser", (110, 110, 110)),
]
SMALL = ("short_grass", "tall_grass", "fern", "allium", "azure_bluet", "oxeye", "cornflower", "lily_of", "poppy", "pink_petals", "lantern",
         "iron_chain", "fence", "_wall", "end_rod", "lily_pad", "seagrass", "cave_vines", "hanging_roots", "spore_blossom", "vine",
         "azalea[", "flowering_azalea[", "carpet", "pointed_dripstone", "amethyst_cluster", "trapdoor")


def col(name):
    name = name.split("[")[0]
    for k, c in COLORS:
        if k in name:
            return c
    return (150, 60, 150)


def shade(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c)


def render(blocks, out, view="se", S=9):
    rot = {"se": lambda x, z: (x, z), "sw": lambda x, z: (z, -x), "nw": lambda x, z: (-x, -z), "ne": lambda x, z: (-z, x)}[view]
    B = {}
    for (x, y, z), b in blocks.items():
        n = b.replace("minecraft:", "")
        if n.startswith("light[") or n == "air":
            continue
        rx, rz = rot(x, z)
        B[(rx, y, rz)] = n
    def base(n):
        return n.split("[")[0]
    solid = {k for k, n in B.items() if not any(s in base(n) + "[" for s in SMALL) and base(n) != "water" and "portal" not in n and "leaves" not in n
             and "slab" not in n and "stairs" not in n}
    c30, s30 = math.cos(math.radians(30)), math.sin(math.radians(30))

    def P(x, y, z):
        return ((x - z) * c30 * S, (x + z) * s30 * S - y * S)

    pts = [P(x, y, z) for (x, y, z) in B]
    minx = min(p[0] for p in pts) - S * 2
    miny = min(p[1] for p in pts) - S * 3
    maxx = max(p[0] for p in pts) + S * 2
    maxy = max(p[1] for p in pts) + S * 2
    img = Image.new("RGB", (int(maxx - minx), int(maxy - miny)), (12, 11, 22))
    dr = ImageDraw.Draw(img, "RGBA")

    def Q(x, y, z):
        px, py = P(x, y, z)
        return (px - minx, py - miny)

    for (x, y, z) in sorted(B, key=lambda k: (k[0] + k[1] + k[2], k[1])):
        n = B[(x, y, z)]
        c = col(n)
        base = n.split("[")[0]
        a = 150 if (base == "water" or "portal" in base) else 255
        small = any(s in base + "[" for s in SMALL)
        i0, i1 = (0.3, 0.7) if small else (0.0, 1.0)
        top = 0.7 if small else 1.0
        if "slab" in n and "type=bottom" in n:
            top = 0.5
        x0, x1, z0, z1, y0, y1 = x + i0, x + i1, z + i0, z + i1, y, y + top
        if (x, y + 1, z) not in solid:
            dr.polygon([Q(x0, y1, z0), Q(x1, y1, z0), Q(x1, y1, z1), Q(x0, y1, z1)], fill=c + (a,))
        if (x + 1, y, z) not in solid:
            dr.polygon([Q(x1, y0, z0), Q(x1, y1, z0), Q(x1, y1, z1), Q(x1, y0, z1)], fill=shade(c, 0.78) + (a,))
        if (x, y, z + 1) not in solid:
            dr.polygon([Q(x0, y0, z1), Q(x0, y1, z1), Q(x1, y1, z1), Q(x1, y0, z1)], fill=shade(c, 0.6) + (a,))
    img.save(out)
    return out
