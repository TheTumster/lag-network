"""RelapsedMC resource pack generator.

Renders the wordmark logo, pixel icons and menu panels into resourcepack/ (committed; CI just zips it),
plus network/server-icon.png for the server list. Custom glyphs live in the font "relapsed:ui" so the default
font is untouched:
  \\uE000 logo, tall (tab header)        \\uE001 logo, line height (scoreboard / chat / boss bar)
  \\uE002 logo, title size                \\uE003 logo, hologram (big, high-res)
  \\uE010.. icons (8px): arrow, players, ping, tnt, sword, star, dot, bolt
  \\uE100 selector menu panel (5 rows: a strip per realm + a bottom bar)    \\uE101 cosmetics menu panel (4 rows)
  items/blank.json: item model "relapsed:blank" draws nothing (invisible clickable slots over menu art)
  \\uF801 = -1px, \\uF808 = -8px, \\uF8B0 = -176px (negative space for the menu panels)
Run: python3 tools/pack/genpack.py   (needs Pillow and the Inter font: /usr/share/fonts/opentype/inter)
"""
import json, os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
PACK = os.path.join(ROOT, "resourcepack")
TEX = os.path.join(PACK, "assets", "relapsed", "textures", "font")
FONT_DIR = "/usr/share/fonts/opentype/inter"
CYAN, PURPLE, PINK = (0x38, 0xE0, 0xFF), (0xB0, 0x40, 0xFF), (0xFF, 0x4D, 0xD2)
os.makedirs(TEX, exist_ok=True)


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def gradient(w, h, stops):
    g = Image.new("RGBA", (w, h))
    px = g.load()
    for x in range(w):
        t = x / max(1, w - 1)
        seg = min(len(stops) - 2, int(t * (len(stops) - 1)))
        lt = t * (len(stops) - 1) - seg
        c = lerp(stops[seg], stops[seg + 1], lt)
        for y in range(h):
            # slight vertical sheen: brighter top
            k = 1.12 - 0.28 * (y / max(1, h - 1))
            px[x, y] = (min(255, int(c[0] * k)), min(255, int(c[1] * k)), min(255, int(c[2] * k)), 255)
    return g


def wordmark(scale=4):
    """Logo B: white spaced RELAPSED with a purple glow, cyan->purple->pink bar underneath, MC tucked bottom-right."""
    f = ImageFont.truetype(os.path.join(FONT_DIR, "InterDisplay-Black.otf"), 38 * scale)
    word, spacing, pad = "RELAPSED", 5 * scale, 6 * scale
    bb = f.getbbox(word)
    w = int(bb[2] - bb[0] + spacing * len(word) + pad * 2)
    h = bb[3] - bb[1] + pad * 2
    m = Image.new("L", (w, h))
    d = ImageDraw.Draw(m)
    x = pad
    for ch in word:
        d.text((x - bb[0], pad - bb[1]), ch, font=f, fill=255)
        x += f.getlength(ch) + spacing
    W, H = w + 4 * scale, h + 24 * scale
    out = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    glow.paste((176, 64, 255, 255), (2 * scale, 0), m)
    out = Image.alpha_composite(out, glow.filter(ImageFilter.GaussianBlur(5 * scale)))
    face = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    face.paste((255, 255, 255, 255), (2 * scale, 0), m)
    out = Image.alpha_composite(out, face)
    # MC, bottom right, gradient, tucked under the end of RELAPSED
    f2 = ImageFont.truetype(os.path.join(FONT_DIR, "InterDisplay-Black.otf"), 14 * scale)
    mb = f2.getbbox("MC")
    mw = int(mb[2] - mb[0] + 3 * scale * 2)
    mm = Image.new("L", (mw + 2, mb[3] - mb[1] + 2))
    md = ImageDraw.Draw(mm)
    mx = 0
    for ch in "MC":
        md.text((mx - mb[0], -mb[1]), ch, font=f2, fill=255)
        mx += f2.getlength(ch) + 3 * scale
    mcx, mcy = W - mm.width - 3 * scale, h - 1 * scale
    mcl = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    mcl.paste(gradient(mm.width, mm.height, [PURPLE, PINK]), (mcx, mcy), mm)
    out = Image.alpha_composite(out, mcl.filter(ImageFilter.GaussianBlur(3 * scale)))
    out = Image.alpha_composite(out, mcl)
    # gradient bar from the left edge up to the MC
    bar = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    bm = Image.new("L", (W, H))
    by = mcy + mm.height // 2 - 1 * scale
    ImageDraw.Draw(bm).rounded_rectangle((4 * scale, by, mcx - 4 * scale, by + 3 * scale), radius=2 * scale, fill=255)
    bar.paste(gradient(W, H, [CYAN, PURPLE, PINK]), (0, 0), bm)
    out = Image.alpha_composite(out, bar.filter(ImageFilter.GaussianBlur(2 * scale)))
    out = Image.alpha_composite(out, bar)
    return out.crop(out.getbbox())


def save_logo_tiles(img, base, target_h, max_w=240):
    """Minecraft can't draw a font image wider than 256px, so the logo is cut into tiles (one char each)."""
    r = target_h / img.height
    im = img.resize((max(1, int(img.width * r)), target_h), Image.LANCZOS)
    n = -(-im.width // max_w)
    tw = -(-im.width // n)
    files = []
    for i in range(n):
        tile = im.crop((i * tw, 0, min(im.width, (i + 1) * tw), target_h))
        name = f"{base}_{i}.png"
        tile.save(os.path.join(TEX, name))
        files.append(name)
    return files


# ------------------------------------------------------------------ pixel icons (8x8)
ICONS = {
    "arrow": ["........", ".##.....", "..##....", "...##...", "...##...", "..##....", ".##.....", "........"],
    "players": ["..##....", ".####...", ".####.##", "..##.###", ".####.#.", "######..", "######..", "........"],
    "ping": ["........", "......##", "......##", "....####", "....####", "..######", "..######", "########"],
    "tnt": ["########", "#RRRRRR#", "#WWWWWW#", "#W#W#WW#", "#WWWWWW#", "#RRRRRR#", "########", "........"],
    "sword": [".......#", "......##", ".....##.", "#...##..", ".#.##...", "..##....", ".#.#....", "#.......", ],
    "star": ["...#....", "...#....", "#######.", ".#####..", "..###...", ".##.##..", "##...##.", "........"],
    "dot": ["........", "........", "...##...", "..####..", "..####..", "...##...", "........", "........"],
    "bolt": ["....##..", "...##...", "..##....", ".######.", "....##..", "...##...", "..##....", ".##....."],
}


def icon(rows):
    im = Image.new("RGBA", (8, 8), (0, 0, 0, 0))
    px = im.load()
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch == "#":
                px[x, y] = lerp(CYAN, PURPLE, (x + y) / 14) + (255,)
            elif ch == "R":
                px[x, y] = (220, 40, 60, 255)
            elif ch == "W":
                px[x, y] = (235, 235, 235, 255)
    return im


# ------------------------------------------------------------------ menu panels
def panel(rows, highlight_slots, title):
    """Covers the chest part of a container GUI (176 x 17+rows*18+4). Slot squares are left see-through
    with a glowing frame, so items always show. The player inventory below keeps the vanilla look."""
    W, H = 176, 17 + rows * 18 + 4
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    # body
    body = gradient(W, H, [(16, 10, 34), (26, 12, 48), (16, 10, 34)])
    mask = Image.new("L", (W, H))
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, W - 1, H - 1), radius=5, fill=235)
    im.paste(body, (0, 0), mask)
    # gradient border
    border = gradient(W, H, [CYAN, PURPLE, PINK])
    bm = Image.new("L", (W, H))
    bd = ImageDraw.Draw(bm)
    bd.rounded_rectangle((0, 0, W - 1, H - 1), radius=5, outline=255, width=2)
    bd.line((8, 15, W - 9, 15), fill=160, width=1)
    im.paste(border, (0, 0), bm)
    # slots
    for s in range(rows * 9):
        r, c = divmod(s, 9)
        x, y = 7 + c * 18, 17 + r * 18
        hl = s in highlight_slots
        d.rectangle((x, y, x + 17, y + 17), fill=(0, 0, 0, 0))
        d.rectangle((x, y, x + 17, y + 17), outline=(0x38, 0xE0, 0xFF, 255) if hl else (70, 50, 110, 200), width=1)
        if hl:
            d.rectangle((x + 1, y + 1, x + 16, y + 16), outline=(0xB0, 0x40, 0xFF, 140), width=1)
    # cut slot interiors fully transparent (items render on top regardless)
    px = im.load()
    for s in range(rows * 9):
        r, c = divmod(s, 9)
        x, y = 7 + c * 18, 17 + r * 18
        for yy in range(y + 2, y + 16):
            for xx in range(x + 2, x + 16):
                px[xx, yy] = (0, 0, 0, 0)
    return im


# ------------------------------------------------------------------ realm selector (5 rows)
PIX = {  # 5x7 pixel letters for the menu art
    "A": [" ### ", "#   #", "#   #", "#####", "#   #", "#   #", "#   #"],
    "B": ["#### ", "#   #", "#   #", "#### ", "#   #", "#   #", "#### "],
    "C": [" ####", "#    ", "#    ", "#    ", "#    ", "#    ", " ####"],
    "F": ["#####", "#    ", "#    ", "#### ", "#    ", "#    ", "#    "],
    "I": ["#####", "  #  ", "  #  ", "  #  ", "  #  ", "  #  ", "#####"],
    "K": ["#   #", "#  # ", "# #  ", "##   ", "# #  ", "#  # ", "#   #"],
    "L": ["#    ", "#    ", "#    ", "#    ", "#    ", "#    ", "#####"],
    "M": ["#   #", "## ##", "# # #", "# # #", "#   #", "#   #", "#   #"],
    "N": ["#   #", "##  #", "# # #", "#  ##", "#   #", "#   #", "#   #"],
    "O": [" ### ", "#   #", "#   #", "#   #", "#   #", "#   #", " ### "],
    "P": ["#### ", "#   #", "#   #", "#### ", "#    ", "#    ", "#    "],
    "S": [" ####", "#    ", "#    ", " ### ", "    #", "    #", "#### "],
    "T": ["#####", "  #  ", "  #  ", "  #  ", "  #  ", "  #  ", "  #  "],
    "Y": ["#   #", "#   #", " # # ", "  #  ", "  #  ", "  #  ", "  #  "],
    ">": ["#    ", "##   ", "###  ", "#### ", "###  ", "##   ", "#    "],
    " ": ["     "] * 7,
}
SELECTOR_REALMS = [("SKYBLOCK", (0x7C, 0xFF, 0x6B)), ("FACTIONS", (0xFF, 0x4D, 0x6D)), ("CANNONS", (0xFF, 0x9F, 0x4D)), ("SMP", (0x38, 0xE0, 0xFF))]


def pix_text(px, x, y, text, col, bold=False, shadow=None):
    for ch in text:
        g = PIX.get(ch, PIX[" "])
        for gy, row in enumerate(g):
            for gx, c in enumerate(row):
                if c != "#":
                    continue
                for bx in ((0, 1) if bold else (0,)):
                    if shadow:
                        px[x + gx + bx + 1, y + gy + 1] = shadow
                for bx in ((0, 1) if bold else (0,)):
                    px[x + gx + bx, y + gy] = col
        x += (7 if bold else 6)
    return x


def selector_panel():
    """5 rows: rows 0-3 are one strip per realm (icon slot, name, PLAY pill), row 4 a bar with Discord / Close / Store.
    Drawn over the chest area; slot holes are left only where real items show (icons and the bar buttons)."""
    rows = 5
    W, H = 176, 17 + rows * 18 + 4
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    body = gradient(W, H, [(14, 9, 30), (24, 11, 44), (14, 9, 30)])
    mask = Image.new("L", (W, H))
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, W - 1, H - 1), radius=5, fill=240)
    im.paste(body, (0, 0), mask)
    bm = Image.new("L", (W, H))
    bd = ImageDraw.Draw(bm)
    bd.rounded_rectangle((0, 0, W - 1, H - 1), radius=5, outline=255, width=2)
    bd.line((8, 15, W - 9, 15), fill=170, width=1)
    im.paste(gradient(W, H, [CYAN, PURPLE, PINK]), (0, 0), bm)
    holes = []
    for r, (name, col) in enumerate(SELECTOR_REALMS):
        y0 = 17 + r * 18
        x0, x1 = 7, 7 + 9 * 18 - 1
        dark = (18, 11, 34)
        strip = Image.new("RGBA", (x1 - x0 + 1, 16))
        sp = strip.load()
        for xx in range(strip.size[0]):
            t = xx / (strip.size[0] - 1)
            k = max(0.0, 1 - t / 0.62)
            c = lerp(dark, lerp(dark, col, 0.55), k)
            for yy in range(16):
                sh = 1.1 - 0.25 * yy / 15
                sp[xx, yy] = (min(255, int(c[0] * sh)), min(255, int(c[1] * sh)), min(255, int(c[2] * sh)), 255)
        sm = Image.new("L", strip.size)
        ImageDraw.Draw(sm).rounded_rectangle((0, 0, strip.size[0] - 1, 15), radius=3, fill=255)
        im.paste(strip, (x0, y0 + 1), sm)
        ImageDraw.Draw(im).rounded_rectangle((x0, y0 + 1, x1, y0 + 16), radius=3, outline=col + (255,), width=1)
        # icon box (hole) with a glow frame
        d = ImageDraw.Draw(im)
        d.rectangle((x0 + 1, y0 + 1, x0 + 17, y0 + 16), outline=lerp(col, (255, 255, 255), 0.35) + (255,), width=1)
        holes.append((x0 + 2, y0 + 2, x0 + 16, y0 + 15))
        px = im.load()
        shadow = lerp(col, (0, 0, 0), 0.65) + (255,)
        pix_text(px, x0 + 24, y0 + 5, name, (255, 255, 255, 255), bold=True, shadow=shadow)
        # PLAY pill on the right
        pl, pr = x1 - 39, x1 - 3
        d.rounded_rectangle((pl, y0 + 4, pr, y0 + 13), radius=4, fill=col + (255,))
        tx = pix_text(px, pl + 4, y0 + 6 - 1, "PLAY", (20, 10, 30, 255))
        pix_text(px, tx + 1, y0 + 5, ">", (20, 10, 30, 255))
    # bottom bar: three button boxes (holes) and a fine line between
    y0 = 17 + 4 * 18
    d = ImageDraw.Draw(im)
    d.line((26, y0 + 9, 7 + 4 * 18 - 3, y0 + 9), fill=(80, 60, 130, 255))
    d.line((7 + 5 * 18 + 2, y0 + 9, 7 + 8 * 18 - 3, y0 + 9), fill=(80, 60, 130, 255))
    for c, col in ((0, PURPLE), (4, (200, 70, 90)), (8, (0xFF, 0xD2, 0x4D))):
        x = 7 + c * 18
        d.rectangle((x, y0, x + 17, y0 + 17), outline=col + (255,), width=1)
        holes.append((x + 2, y0 + 2, x + 15, y0 + 15))
    px = im.load()
    for (a, b, c2, e) in holes:
        for yy in range(b, e + 1):
            for xx in range(a, c2 + 1):
                px[xx, yy] = (0, 0, 0, 0)
    return im


def main():
    logo = wordmark()
    for f in os.listdir(TEX):
        if f.startswith("logo_"):
            os.remove(os.path.join(TEX, f))
    # name -> (texture height px, shown height, ascent, first char)
    sizes = {"tab": (64, 24, 8, 0xE020), "line": (32, 9, 8, 0xE040), "title": (48, 14, 11, 0xE060), "holo": (128, 32, 26, 0xE080),
             # big chat wordmark: 26px tall, drawn upward over the two blank lines sent before it
             "chat": (104, 26, 24, 0xE0A0)}
    glyphs, logo_providers = {}, []
    for key, (tex_h, shown, ascent, first) in sizes.items():
        files = save_logo_tiles(logo, "logo_" + key, tex_h)
        chars = [chr(first + i) for i in range(len(files))]
        for f, c in zip(files, chars):
            logo_providers.append({"type": "bitmap", "file": "relapsed:font/" + f, "height": shown, "ascent": ascent, "chars": [c]})
        glyphs["logo_" + key] = "\uF801".join(chars)  # each glyph advances width+1; pull back 1px so tiles touch
    for name, rows in ICONS.items():
        icon(rows).save(os.path.join(TEX, f"icon_{name}.png"))
    selector_panel().save(os.path.join(TEX, "menu_selector.png"))
    idir = os.path.join(PACK, "assets", "relapsed", "items")
    os.makedirs(idir, exist_ok=True)
    json.dump({"model": {"type": "minecraft:empty"}}, open(os.path.join(idir, "blank.json"), "w"), indent=1)
    panel(4, {10, 11, 12, 13, 14, 15, 16, 20, 22, 24}, "Cosmetics").save(os.path.join(TEX, "menu_cosmetics.png"))

    providers = [
        {"type": "space", "advances": {"\uF801": -1, "\uF808": -8, "\uF8B0": -176, "\uF8B1": -169, "\uF820": 4}},
        {"type": "bitmap", "file": "relapsed:font/menu_selector.png", "height": 17 + 3 * 18 + 4, "ascent": 13, "chars": [""]},
        {"type": "bitmap", "file": "relapsed:font/menu_cosmetics.png", "height": 17 + 4 * 18 + 4, "ascent": 13, "chars": [""]},
    ]
    providers[1:1] = logo_providers
    for i, name in enumerate(ICONS):
        providers.append({"type": "bitmap", "file": f"relapsed:font/icon_{name}.png", "height": 8, "ascent": 7, "chars": [chr(0xE010 + i)]})
    fdir = os.path.join(PACK, "assets", "relapsed", "font")
    os.makedirs(fdir, exist_ok=True)
    json.dump({"providers": providers}, open(os.path.join(fdir, "ui.json"), "w", encoding="utf-8"), ensure_ascii=True, indent=1)
    json.dump({"pack": {"pack_format": 75, "supported_formats": [46, 120], "min_format": 46, "max_format": 120,
                        "description": "§bRelapsed§dMC §7server pack"}},
              open(os.path.join(PACK, "pack.mcmeta"), "w"), indent=1)
    # pack icon + server-list icon: "R" monogram
    for path, size in ((os.path.join(PACK, "pack.png"), 128), (os.path.join(ROOT, "network", "server-icon.png"), 64)):
        s = size * 4
        im = Image.new("RGBA", (s, s), (0, 0, 0, 0))
        m = Image.new("L", (s, s))
        ImageDraw.Draw(m).rounded_rectangle((0, 0, s - 1, s - 1), radius=s // 5, fill=255)
        im.paste(gradient(s, s, [(20, 10, 40), (40, 14, 70)]), (0, 0), m)
        bm = Image.new("L", (s, s))
        ImageDraw.Draw(bm).rounded_rectangle((0, 0, s - 1, s - 1), radius=s // 5, outline=255, width=s // 28)
        im.paste(gradient(s, s, [CYAN, PURPLE, PINK]), (0, 0), bm)
        f = ImageFont.truetype(os.path.join(FONT_DIR, "Inter-BlackItalic.otf"), int(s * 0.78))
        tm = Image.new("L", (s, s))
        bb = f.getbbox("R")
        ImageDraw.Draw(tm).text(((s - (bb[2] - bb[0])) // 2 - bb[0], (s - (bb[3] - bb[1])) // 2 - bb[1]), "R", font=f, fill=255)
        glow = Image.new("RGBA", (s, s), (0, 0, 0, 0))
        glow.paste(gradient(s, s, [CYAN, PURPLE, PINK]), (0, 0), tm)
        im = Image.alpha_composite(im, glow.filter(ImageFilter.GaussianBlur(s // 30)))
        im = Image.alpha_composite(im, glow)
        im.resize((size, size), Image.LANCZOS).save(path)
    # glyph strings for the plugin (laGCore reads this from its jar)
    gpath = os.path.join(ROOT, "plugins", "lag-core", "src", "main", "resources", "glyphs.json")
    json.dump(glyphs, open(gpath, "w", encoding="utf-8"), ensure_ascii=True, indent=1)
    print("glyphs:", {k: len(v) for k, v in glyphs.items()})
    print("pack written to", os.path.normpath(PACK))


if __name__ == "__main__":
    main()
