"""Prepare a cannon schematic for the parity harness.

  python prep.py <schematic or --test> <out_dir> [originX originY originZ]

Writes:
  out_dir/cannon.schematic   the MCEdit schematic (FAWE pastes this on 1.21)
  out_dir/blocks.txt         "x y z id data" world coords, for the 1.8 harness
  out_dir/harness.properties origin, bounds and where the lever/button/ice are
"""
import os, sys, shutil
import nbtlib
from nbtlib import tag

LEVER, BUTTON, WOOD_BUTTON, ICE = 69, 77, 143, 79


def make_test(path):
    # tiny contraption so the pipeline can be tested without the real cannon:
    # a dispenser facing up, a button on its west side, a lever-driven dispenser, and an ice block on obsidian
    W, H, L = 6, 4, 4
    blocks = bytearray(W * H * L)
    data = bytearray(W * H * L)
    i = lambda x, y, z: (y * L + z) * W + x
    for x in range(W):
        for z in range(L):
            blocks[i(x, 0, z)] = 49
    blocks[i(1, 1, 1)], data[i(1, 1, 1)] = 23, 1   # dispenser facing up
    blocks[i(0, 1, 1)], data[i(0, 1, 1)] = 77, 2   # stone button on its west side
    blocks[i(4, 1, 2)], data[i(4, 1, 2)] = 23, 1   # second dispenser, driven by the lever
    blocks[i(5, 1, 2)], data[i(5, 1, 2)] = 69, 1   # lever on its east side (wall lever, facing east)
    blocks[i(3, 1, 3)] = 79                        # ice
    te = [tag.Compound({"id": tag.String("Trap"), "x": tag.Int(x), "y": tag.Int(1), "z": tag.Int(z), "Items": tag.List[tag.Compound]([])})
          for x, z in ((1, 1), (4, 2))]
    root = tag.Compound({
        "Width": tag.Short(W), "Height": tag.Short(H), "Length": tag.Short(L), "Materials": tag.String("Alpha"),
        "Blocks": tag.ByteArray([b if b < 128 else b - 256 for b in blocks]),
        "Data": tag.ByteArray(list(data)), "Entities": tag.List[tag.Compound]([]), "TileEntities": tag.List[tag.Compound](te),
        "WEOriginX": tag.Int(0), "WEOriginY": tag.Int(0), "WEOriginZ": tag.Int(0),
        "WEOffsetX": tag.Int(0), "WEOffsetY": tag.Int(0), "WEOffsetZ": tag.Int(0),
    })
    nbtlib.File({"Schematic": root}, root_name="Schematic").save(path, gzipped=True)


def main():
    src, out = sys.argv[1], sys.argv[2]
    ox, oy, oz = (int(v) for v in (sys.argv[3:6] if len(sys.argv) >= 6 else (0, 1, 0)))
    os.makedirs(out, exist_ok=True)
    dst = os.path.join(out, "cannon.schematic")
    if src == "--test":
        make_test(dst)
    else:
        shutil.copy(src, dst)
    f = nbtlib.load(dst)
    r = f if "Width" in f else f[next(iter(f.keys()))]
    W, H, L = int(r["Width"]), int(r["Height"]), int(r["Length"])
    B = [b & 0xFF for b in r["Blocks"]]
    D = [b & 0xFF for b in r["Data"]]
    found = {}
    with open(os.path.join(out, "blocks.txt"), "w") as fh:
        for idx, b in enumerate(B):
            x, y, z = idx % W, idx // (W * L), (idx // W) % L
            wx, wy, wz = x + ox, y + oy, z + oz
            if b:
                fh.write(f"{wx} {wy} {wz} {b} {D[idx]}\n")
            if b == LEVER and "lever" not in found:
                found["lever"] = (wx, wy, wz)
            if b in (BUTTON, WOOD_BUTTON) and "button" not in found:
                found["button"] = (wx, wy, wz)
            if b == ICE:
                found.setdefault("ice", []).append((wx, wy, wz))
    props = {
        "origin": f"{ox},{oy},{oz}", "size": f"{W},{H},{L}",
        "lever": ",".join(map(str, found["lever"])) if "lever" in found else "",
        "button": ",".join(map(str, found["button"])) if "button" in found else "",
        "ice": ";".join(",".join(map(str, p)) for p in found.get("ice", [])),
    }
    with open(os.path.join(out, "harness.properties"), "a") as fh:
        for k, v in props.items():
            fh.write(f"{k}={v}\n")
    print("prepared", W, H, L, props)


if __name__ == "__main__":
    main()
