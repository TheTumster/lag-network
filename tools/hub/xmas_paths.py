"""The four realm runners of the Christmas hub: straight from the ice rink to each gate's steps.
Shared by xmas_valley.py (keeps the ground flat along them) and genspawn_xmas.py (lays them)."""
import math

RINK = (0, -12)          # the Well, frozen
RINK_R = 7.0             # runners start on the rink's wall ring
STEP_Z = -27             # last row before each gate's stairs
COLOR = {"skyblock": "lime", "factions": "red", "cannons": "orange", "smp": "light_blue"}
# realm, gate x, angle the runner leaves the rink at (degrees from straight north, + = east)
RUNNERS = [("skyblock", -30, -70), ("factions", -12, -33), ("cannons", 12, 33), ("smp", 30, 70)]


def segments():
    out = []
    for realm, px, a in RUNNERS:
        r = math.radians(a)
        sx, sz = RINK[0] + math.sin(r) * RINK_R, RINK[1] - math.cos(r) * RINK_R
        out.append((realm, (sx, sz), (px + 0.0, STEP_Z + 0.0)))
    return out


def seg_d(x, z, a, b):
    """Distance from (x, z) to segment a-b, and how far along it (0..1)."""
    ax, az = a
    bx, bz = b
    vx, vz = bx - ax, bz - az
    L2 = vx * vx + vz * vz
    t = max(0.0, min(1.0, ((x - ax) * vx + (z - az) * vz) / L2))
    return math.hypot(x - (ax + vx * t), z - (az + vz * t)), t


def nearest(x, z):
    best = (1e9, None, 0.0)
    for realm, a, b in segments():
        d, t = seg_d(x, z, a, b)
        if d < best[0]:
            best = (d, realm, t)
    return best
