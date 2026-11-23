"""Tab-list filler head: a dark tile with a light-red gem (RelapsedMC brand).
Writes tools/pack/out/tab_head_skin.png (64x64 skin). Upload it to MineSkin, then put the
returned id into servers/proxy/plugins/tab/config.yml -> layout.default-skin ('mineskin:<id>')."""
import os
from PIL import Image

BG, EDGE = (27, 19, 22, 255), (44, 28, 33, 255)
PAL = {"a": (255, 201, 194, 255), "b": (255, 107, 107, 255), "c": (217, 67, 74, 255), "d": (150, 40, 48, 255)}
GEM = ["........",
       "...ab...",
       "..abbc..",
       ".abbbcc.",
       ".dbbbcc.",
       "..dbcc..",
       "...dc...",
       "........"]

def face(gem=False):
    f = Image.new("RGBA", (8, 8), BG)
    px = f.load()
    for i in range(8):
        for p in ((i, 0), (i, 7), (0, i), (7, i)):
            px[p] = EDGE
    if gem:
        for y, row in enumerate(GEM):
            for x, ch in enumerate(row):
                if ch in PAL:
                    px[x, y] = PAL[ch]
    return f

skin = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
# head: top (8,0) bottom (16,0) right (0,8) front (8,8) left (16,8) back (24,8)
for xy in ((8, 0), (16, 0), (0, 8), (16, 8), (24, 8)):
    skin.paste(face(), xy)
skin.paste(face(True), (8, 8))
# plain dark body/arms/legs so the skin is valid everywhere
for box in ((16, 16, 56, 32), (0, 16, 16, 32), (16, 48, 48, 64)):
    skin.paste(BG, box)
out = os.path.join(os.path.dirname(__file__), "out")
os.makedirs(out, exist_ok=True)
skin.save(os.path.join(out, "tab_head_skin.png"))
skin.crop((8, 8, 16, 16)).resize((128, 128), Image.NEAREST).save(os.path.join(out, "tab_head_preview.png"))
print("wrote", out)
