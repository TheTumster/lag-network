"""RelapsedMC resource pack generator.

Renders the wordmark logo, pixel icons and menu panels into resourcepack/ (committed; CI just zips it),
plus network/server-icon.png for the server list. Custom glyphs live in the font "relapsed:ui" so the default
font is untouched:
  \\uE000 logo, tall (tab header)        \\uE001 logo, line height (scoreboard / chat / boss bar)
  \\uE002 logo, title size                \\uE003 logo, hologram (big, high-res)
  \\uE010.. icons (8px): arrow, players, ping, tnt, sword, star, dot, bolt
  \\uE100 selector menu panel (3 rows)    \\uE101 cosmetics menu panel (4 rows)
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
    """RELAPSED (heavy italic, gradient, dark outline + glow) with a small MC tag."""
    big = ImageFont.truetype(os.path.join(FONT_DIR, "Inter-BlackItalic.otf"), 40 * scale)
    small = ImageFont.truetype(os.path.join(FONT_DIR, "Inter-ExtraBoldItalic.otf") if os.path.exists(os.path.join(FONT_DIR, "Inter-ExtraBoldItalic.otf")) else os.path.join(FONT_DIR, "Inter-BlackItalic.otf"), 17 * scale)
    word, tag = "RELAPSED", "MC"
    bb = big.getbbox(word)
    tb = small.getbbox(tag)
    pad = 6 * scale
    w = bb[2] - bb[0] + tb[2] - tb[0] + pad * 3 + 4 * scale
    h = bb[3] - bb[1] + pad * 2
    mask = Image.new("L", (w, h))
    d = ImageDraw.Draw(mask)
    d.text((pad - bb[0], pad - bb[1]), word, font=big, fill=255)
    wx = pad + bb[2] - bb[0] + 4 * scale
    tag_mask = Image.new("L", (w, h))
    td = ImageDraw.Draw(tag_mask)
    # MC tag sits top-right like a badge
    td.rounded_rectangle((wx - 2 * scale, pad, wx + tb[2] - tb[0] + 4 * scale, pad + tb[3] - tb[1] + 6 * scale), radius=3 * scale, fill=255)
    tag_text = Image.new("L", (w, h))
    ImageDraw.Draw(tag_text).text((wx + 1 * scale - tb[0], pad + 3 * scale - tb[1]), tag, font=small, fill=255)

    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    # glow
    glow_src = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    glow_src.paste(gradient(w, h, [CYAN, PURPLE, PINK]), (0, 0), mask)
    out = Image.alpha_composite(out, glow_src.filter(ImageFilter.GaussianBlur(5 * scale)))
    # outline
    outline = mask.filter(ImageFilter.MaxFilter(2 * scale + 1))
    dark = Image.new("RGBA", (w, h), (12, 6, 26, 255))
    o = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    o.paste(dark, (0, 0), outline)
    out = Image.alpha_composite(out, o)
    # gradient fill
    fill = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    fill.paste(gradient(w, h, [CYAN, PURPLE, PINK]), (0, 0), mask)
    out = Image.alpha_composite(out, fill)
    # MC badge: gradient pill with dark text cut out
    badge = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    badge.paste(gradient(w, h, [PURPLE, PINK]), (0, 0), tag_mask)
    out = Image.alpha_composite(out, badge)
    cut = Image.new("RGBA", (w, h), (12, 6, 26, 255))
    holes = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    holes.paste(cut, (0, 0), tag_text)
    out = Image.alpha_composite(out, holes)
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


def main():
    logo = wordmark()
    for f in os.listdir(TEX):
        if f.startswith("logo_"):
            os.remove(os.path.join(TEX, f))
    # name -> (texture height px, shown height, ascent, first char)
    sizes = {"tab": (64, 24, 8, 0xE020), "line": (32, 9, 8, 0xE040), "title": (48, 14, 11, 0xE060), "holo": (128, 32, 26, 0xE080)}
    glyphs, logo_providers = {}, []
    for key, (tex_h, shown, ascent, first) in sizes.items():
        files = save_logo_tiles(logo, "logo_" + key, tex_h)
        chars = [chr(first + i) for i in range(len(files))]
        for f, c in zip(files, chars):
            logo_providers.append({"type": "bitmap", "file": "relapsed:font/" + f, "height": shown, "ascent": ascent, "chars": [c]})
        glyphs["logo_" + key] = "\uF801".join(chars)  # each glyph advances width+1; pull back 1px so tiles touch
    for name, rows in ICONS.items():
        icon(rows).save(os.path.join(TEX, f"icon_{name}.png"))
    panel(3, {11, 15}, "Servers").save(os.path.join(TEX, "menu_selector.png"))
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
