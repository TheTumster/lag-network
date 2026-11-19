"""Draws the hub's Santa NPC skin (original pixel art, standard 64x64 classic-arm layout) into the resource pack:
resourcepack/assets/relapsed/textures/entity/santa.png, used by the Christmas hub's Santa mannequin
(laGCore skin patch "relapsed:entity/santa").
Run: python3 tools/pack/santa_skin.py
"""
import os, random
from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
OUT = os.path.join(ROOT, "resourcepack", "assets", "relapsed", "textures", "entity", "santa.png")

RED, RED_D, RED_L = (196, 28, 36), (150, 18, 26), (222, 52, 56)
WHITE, WHITE_D = (246, 246, 242), (214, 216, 220)
SKIN, SKIN_D, CHEEK = (241, 194, 160), (222, 163, 128), (236, 146, 140)
BLACK, BLACK_L = (28, 26, 30), (58, 54, 60)
GOLD, GOLD_D = (246, 200, 64), (196, 150, 40)
EYE = (46, 78, 160)
MOUTH = (176, 70, 70)

img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
rnd = random.Random(25)


def jit(c, a=7):
    return tuple(max(0, min(255, v + rnd.randint(-a, a))) for v in c) + (255,)


def face(x0, y0, w, h, fn):
    """fn(x, y) -> colour (or None) for a w*h face at (x0, y0) of the texture."""
    for y in range(h):
        for x in range(w):
            c = fn(x, y)
            if c is not None:
                img.putpixel((x0 + x, y0 + y), jit(c) if len(c) == 3 else c)


# ---------------------------------------------------------------- head (8x8 faces)
def head_front(x, y):
    if y <= 1:
        return RED
    if y == 2:
        return WHITE
    if y == 3:
        return WHITE if x in (1, 2, 5, 6) else SKIN                 # bushy eyebrows
    if y == 4:
        if x in (2, 5):
            return EYE
        if x in (1, 6):
            return WHITE_D if x == 1 else WHITE_D
        return SKIN_D if x in (3, 4) else SKIN
    if y == 5:
        if x in (3, 4):
            return SKIN_D                                           # nose
        return CHEEK if x in (1, 6) else WHITE if x in (0, 7) else SKIN
    if y == 6:
        return MOUTH if x in (3, 4) else WHITE                      # moustache round the mouth
    return WHITE if (x + y) % 3 else WHITE_D                        # beard


def head_side(x, y):
    if y <= 1:
        return RED
    if y == 2:
        return WHITE
    if y in (3, 4):
        return SKIN_D if x == 3 and y == 4 else SKIN if x >= 4 else WHITE   # hair behind, ear
    return WHITE if (x * 2 + y) % 4 else WHITE_D


def head_side_l(x, y):
    return head_side(7 - x, y)


def head_back(x, y):
    if y <= 1:
        return RED
    if y == 2:
        return WHITE
    return WHITE if (x + y) % 4 else WHITE_D


face(8, 0, 8, 8, lambda x, y: RED)                 # top
face(16, 0, 8, 8, lambda x, y: WHITE)              # bottom (beard)
face(0, 8, 8, 8, head_side)                        # right
face(8, 8, 8, 8, head_front)
face(16, 8, 8, 8, head_side_l)                     # left
face(24, 8, 8, 8, head_back)
# hat layer: the cap stands proud of the head with a fur trim; a bobble on top
face(40, 0, 8, 8, lambda x, y: WHITE if (x, y) in ((5, 1), (6, 1), (5, 2), (6, 2)) else RED_D if (x + y) % 5 == 0 else RED)
for x0 in (32, 40, 48, 56):
    face(x0, 8, 8, 8, lambda x, y: RED if y == 0 else WHITE if y == 1 else None)


# ---------------------------------------------------------------- body (8x12 front/back, 4x12 sides)
def body_front(x, y):
    if y <= 2 and 1 <= x <= 6:
        return WHITE if (x + y) % 3 else WHITE_D                    # beard over the chest
    if y in (7, 8):
        if x in (3, 4):
            return GOLD if y == 7 else GOLD_D                       # buckle
        return BLACK if y == 7 else BLACK_L
    if y == 11:
        return WHITE
    if x in (3, 4):
        return WHITE                                                # fur placket
    return RED_D if x in (0, 7) else RED


def body_side(x, y):
    if y in (7, 8):
        return BLACK
    if y == 11:
        return WHITE
    return RED_D if x == 0 else RED


def body_back(x, y):
    if y in (7, 8):
        return BLACK if y == 7 else BLACK_L
    if y == 11:
        return WHITE
    return RED_D if x in (0, 7) or y == 0 else RED


face(20, 16, 8, 4, lambda x, y: RED)
face(28, 16, 8, 4, lambda x, y: RED)
face(16, 20, 4, 12, body_side)
face(20, 20, 8, 12, body_front)
face(28, 20, 4, 12, body_side)
face(32, 20, 8, 12, body_back)


# ---------------------------------------------------------------- arms (4 wide): red sleeve, fur cuff, black mitten
def arm(x, y):
    if y >= 10:
        return BLACK if y == 11 or x % 3 else BLACK_L
    if y == 9 or y == 8:
        return WHITE
    return RED_D if x == 0 else RED


for (tx, ty) in ((44, 16), (36, 48)):              # tops
    face(tx, ty, 4, 4, lambda x, y: RED)
    face(tx + 4, ty, 4, 4, lambda x, y: BLACK)    # bottoms (mittens)
for x0 in (40, 44, 48, 52):
    face(x0, 20, 4, 12, arm)
for x0 in (32, 36, 40, 44):
    face(x0, 52, 4, 12, arm)


# ---------------------------------------------------------------- legs: red trousers, fur trim, black boots
def leg(x, y):
    if y >= 7:
        if y == 7:
            return WHITE
        return BLACK_L if (y == 8 and x in (1, 2)) else BLACK
    return RED_D if x == 0 else RED


for (tx, ty) in ((4, 16), (20, 48)):
    face(tx, ty, 4, 4, lambda x, y: RED)
    face(tx + 4, ty, 4, 4, lambda x, y: BLACK)
for x0 in (0, 4, 8, 12):
    face(x0, 20, 4, 12, leg)
for x0 in (16, 20, 24, 28):
    face(x0, 52, 4, 12, leg)

os.makedirs(os.path.dirname(OUT), exist_ok=True)
img.save(OUT)
print("santa skin ->", os.path.normpath(OUT))
