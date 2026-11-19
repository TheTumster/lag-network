"""First-person preview of a generated build: what a player actually sees standing somewhere.

render(blocks, out, eye=(0.5, 2.62, 0.5), yaw=180, pitch=0, night=True)
  yaw like Minecraft: 180 = looking north (-z), 0 = south, 90 = west, -90/270 = east.
Night mode floods light like the game (emissive blocks + light blocks, leaves dim it by 1, solids stop it),
so lamps, lights and dark corners read roughly as they would at the hub's midnight.
Usage: python3 tools/hub/viewrender.py <build.txt.gz> out.png [x y z yaw pitch] [--day]
"""
import gzip, math, sys
import numpy as np
from PIL import Image

sys.path.insert(0, __import__("os").path.dirname(__file__))
import isorender

EXTRA = [
    ("snow_block", (245, 248, 252)), ("snow[", (245, 248, 252)), ("powder_snow", (240, 244, 250)),
    ("packed_ice", (140, 175, 235)), ("blue_ice", (115, 160, 240)), ("ice", (160, 195, 245)),
    ("spruce_leaves", (52, 92, 52)), ("spruce_log", (70, 50, 30)), ("spruce_wood", (70, 50, 30)),
    ("glowstone", (255, 215, 120)), ("ochre_froglight", (250, 225, 140)), ("verdant_froglight", (200, 240, 170)),
    ("shroomlight", (245, 150, 70)), ("sea_lantern", (215, 235, 230)), ("coal_block", (22, 22, 24)),
    ("orange_terracotta", (190, 95, 40)), ("orange_concrete", (230, 110, 20)), ("black_wool", (22, 22, 26)),
    ("red_wool", (175, 40, 40)), ("white_wool", (240, 240, 240)), ("green_", (70, 110, 30)),
    ("yellow_", (245, 200, 40)), ("brown_", (110, 75, 45)), ("gold_block", (250, 210, 70)),
    ("raw_gold", (220, 170, 60)), ("candle", (240, 230, 200)), ("quartz", (235, 230, 225)),
    ("white_concrete_powder", (232, 234, 236)), ("pink_", (240, 150, 190)), ("gray_", (90, 90, 95)),
]
EMIT = {"sea_lantern": 15, "glowstone": 15, "froglight": 15, "shroomlight": 15, "lantern": 15, "end_rod": 14, "beacon": 15,
        "nether_portal": 11, "campfire": 15, "redstone_lamp[lit=true": 15, "jack_o": 15, "amethyst_cluster": 5, "candle": 6,
        "soul_lantern": 10, "torch": 14}
THIN = ("glass", "leaves", "slab", "stairs", "_wall", "fence", "lantern", "end_rod", "chain", "carpet", "snow[", "pane",
        "bars", "flower", "tulip", "petals", "grass[", "short_grass", "fern", "sapling", "banner", "trapdoor", "rail",
        "plate", "cluster", "bud", "portal", "water", "candle", "azalea[", "button", "ice", "dripstone[")


def color(n):
    b = n.split("[")[0] + ("[" if "[" in n else "")
    for k, c in EXTRA:
        if k in b or k in n:
            return c
    return isorender.col(n)


def emit(n):
    if n.startswith("light["):
        try:
            return int(n.split("level=")[1].split(",")[0].split("]")[0])
        except Exception:
            return 15
    for k, v in EMIT.items():
        if k in n:
            return v
    return 0


def load(path):
    with gzip.open(path, "rt") as f:
        pal = f.readline().strip().split("|")
        out = {}
        for line in f:
            if line.strip():
                x, y, z, i = line.split()
                out[(int(x), int(y), int(z))] = pal[int(i)]
    return out


def render(blocks, out, eye=(0.5, 2.62, 0.5), yaw=180.0, pitch=0.0, night=True, W=800, H=450, fov=75, far=200):
    names = sorted({b.replace("minecraft:", "") for b in blocks.values()})
    nid = {n: i + 1 for i, n in enumerate(names)}
    xs = [k[0] for k in blocks]; ys = [k[1] for k in blocks]; zs = [k[2] for k in blocks]
    x0, y0, z0 = min(xs) - 2, min(ys) - 2, min(zs) - 2
    G = np.zeros((max(xs) - x0 + 3, max(ys) - y0 + 3, max(zs) - z0 + 3), np.uint16)
    for (x, y, z), b in blocks.items():
        G[x - x0, y - y0, z - z0] = nid[b.replace("minecraft:", "")]
    ncol = np.zeros((len(names) + 1, 3), np.float32)
    nemit = np.zeros(len(names) + 1, np.float32)
    solid = np.zeros(len(names) + 1, bool)       # hit by rays
    opaque = np.zeros(len(names) + 1, np.int8)   # light cost: 15 = blocks, 1 = leaves, 0 = clear
    # shape: 0 full, 1 post (walls/fences/rods/chains/bars/panes), 2 lantern, 3 carpet, 4 snow layer (height in sh),
    #        5 bottom slab, 6 plant (thin cross), 7 top slab
    shp = np.zeros(len(names) + 1, np.int8)
    sh = np.ones(len(names) + 1, np.float32)
    for n, i in nid.items():
        b = n.split("[")[0]
        if b.endswith(("_wall", "_fence", "end_rod", "chain", "_bars", "_pane")) or b == "iron_chain":
            shp[i] = 1
        elif b.endswith("lantern"):
            shp[i] = 2
        elif b.endswith("carpet") or b.endswith("pressure_plate") or b.endswith("petals"):
            shp[i] = 3
        elif b == "snow":
            shp[i] = 4
            sh[i] = int(n.split("layers=")[1][0]) / 8 if "layers=" in n else 0.125
        elif b.endswith("_slab") and "type=bottom" in n:
            shp[i] = 5
        elif b.endswith("_slab") and "type=top" in n:
            shp[i] = 7
        elif b.endswith(("tulip", "sapling", "allium", "bluet", "cornflower", "grass", "fern", "cluster", "_bud", "campfire", "candle")):
            shp[i] = 6
    for n, i in nid.items():
        ncol[i] = color(n)
        nemit[i] = emit(n)
        solid[i] = not n.startswith("light[")
        opaque[i] = 1 if "leaves" in n else 0 if any(t in n for t in THIN) or n.startswith("light[") else 15
    # light flood (block light), like the game: -1 per step, solids stop it
    L = nemit[G]
    cost = opaque[G].astype(np.float32)
    if night:
        for _ in range(15):
            m = L.copy()
            for ax in range(3):
                for s in (1, -1):
                    m = np.maximum(m, np.roll(L, s, axis=ax) - 1)
            L = np.where(cost >= 15, nemit[G], np.maximum(nemit[G], m - cost))
    # rays
    yr, pr = math.radians(yaw), math.radians(pitch)
    fwd = np.array([-math.sin(yr) * math.cos(pr), -math.sin(pr), math.cos(yr) * math.cos(pr)])
    right = np.cross(fwd, [0, 1, 0]); right /= np.linalg.norm(right)
    up = np.cross(right, fwd)
    t = math.tan(math.radians(fov) / 2)
    u = (np.arange(W) + 0.5) / W * 2 - 1
    v = 1 - (np.arange(H) + 0.5) / H * 2
    U, V = np.meshgrid(u * t * W / H, v * t)
    D = fwd[None, None] + U[..., None] * right[None, None] - V[..., None] * (-up)[None, None]
    D = (D / np.linalg.norm(D, axis=2)[..., None]).reshape(-1, 3)
    N = D.shape[0]
    pos = np.tile(np.array(eye, float), (N, 1))
    hit = np.zeros(N, np.int32)
    face = np.zeros(N, np.int8)
    dist = np.full(N, far, np.float32)
    lit = np.zeros(N, np.float32)
    alive = np.arange(N)
    step = 0.15
    prev = np.floor(pos).astype(np.int64)
    for k in range(int(far / step)):
        if alive.size == 0:
            break
        p = pos[alive] + D[alive] * (k + 1) * step
        c = np.floor(p).astype(np.int64)
        gx, gy, gz = c[:, 0] - x0, c[:, 1] - y0, c[:, 2] - z0
        inb = (gx >= 0) & (gy >= 0) & (gz >= 0) & (gx < G.shape[0]) & (gy < G.shape[1]) & (gz < G.shape[2])
        idv = np.zeros(alive.size, np.int32)
        idv[inb] = G[gx[inb], gy[inb], gz[inb]]
        h = solid[idv] & (idv > 0)
        fr = p - c
        sp = shp[idv]
        cxz = np.maximum(np.abs(fr[:, 0] - 0.5), np.abs(fr[:, 2] - 0.5))
        thin_ok = np.where(sp == 1, cxz < 0.2, np.where(sp == 2, (cxz < 0.22) & (fr[:, 1] < 0.55), np.where(sp == 3, fr[:, 1] < 0.08,
                  np.where(sp == 4, fr[:, 1] < sh[idv], np.where(sp == 5, fr[:, 1] < 0.5, np.where(sp == 6, cxz < 0.18,
                  np.where(sp == 7, fr[:, 1] > 0.5, True)))))))
        h = h & thin_ok
        if h.any():
            a = alive[h]
            hit[a] = idv[h]
            pc_ = prev[alive][h]
            d_ = c[h] - pc_
            f = np.where(d_[:, 1] != 0, np.where(d_[:, 1] < 0, 0, 1), np.where(d_[:, 0] != 0, 2, 3))
            face[a] = f
            dist[a] = (k + 1) * step
            px, py, pz = pc_[:, 0] - x0, pc_[:, 1] - y0, pc_[:, 2] - z0
            ok = (px >= 0) & (py >= 0) & (pz >= 0) & (px < G.shape[0]) & (py < G.shape[1]) & (pz < G.shape[2])
            lv = np.zeros(a.size, np.float32)
            lv[ok] = L[px[ok], py[ok], pz[ok]]
            lit[a] = np.maximum(lv, nemit[idv[h]])
        nprev = prev.copy()
        nprev[alive] = c
        prev = nprev
        alive = alive[~h]
    col = ncol[hit]
    fshade = np.array([1.0, 0.5, 0.82, 0.68], np.float32)[face]
    if night:
        sky_amb = 0.22
        bright = np.maximum(sky_amb, 0.06 + 0.94 * (lit / 15.0) ** 1.4)
        bright = np.where(nemit[hit] > 0, 1.0, bright)
        img = col * (fshade * bright)[:, None]
        fogc = np.array([10, 12, 28], np.float32)
    else:
        img = col * fshade[:, None]
        fogc = np.array([170, 200, 235], np.float32)
    fog = np.clip((dist - 90) / (far - 90), 0, 1)[:, None] * 0.6
    img = img * (1 - fog) + fogc * fog
    vy = D[:, 1]
    sky = (np.array([8, 10, 26]) + np.clip(1 - vy, 0, 1)[:, None] * np.array([18, 20, 40])) if night else \
        (np.array([120, 165, 230]) + np.clip(1 - vy, 0, 1)[:, None] * np.array([60, 50, 20]))
    if night:
        rnd = np.random.default_rng(3).random(N)
        sky = np.where(((rnd < 0.002) & (vy > 0.05))[:, None], 200, sky)
    img = np.where((hit == 0)[:, None], sky, img)
    Image.fromarray(np.clip(img, 0, 255).astype(np.uint8).reshape(H, W, 3)).save(out)
    return out


if __name__ == "__main__":
    a = sys.argv
    b = load(a[1])
    kw = {}
    if len(a) >= 8:
        kw = dict(eye=(float(a[3]), float(a[4]), float(a[5])), yaw=float(a[6]), pitch=float(a[7]))
    render(b, a[2], night="--day" not in a, **kw)
    print(a[2])
