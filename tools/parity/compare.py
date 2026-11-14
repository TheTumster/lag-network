"""Compare cannon traces: python compare.py <dir> ; each run is <dir>/<name>/trace.csv (+ server.log).

Prints a side-by-side timeline (ticks after the button press) of dispenses, piston moves, live TNT,
falling sand and explosions, then explosion spread and how much of the cannon survived.
"""
import collections, os, statistics, sys

ORDER = ["1.8.8", "ours-as-pasted", "ours-fixredstone", "ours-no-laGCannons"]


def load(path):
    d = {"D": collections.Counter(), "P": collections.Counter(), "T": collections.Counter(), "S": collections.Counter(),
         "X": collections.Counter(), "Xblocks": 0, "Xcancel": 0, "xs": [], "first": {}, "sand_last": {}, "integrity": None,
         "tnt_first": []}
    seen_tnt = set()
    with open(path) as fh:
        for line in fh:
            p = line.rstrip("\n").split(",")
            k = p[0]
            if k == "I":
                d["integrity"] = (int(p[1]), int(p[2]))
                if len(p) >= 9:
                    d["bounds"] = [int(v) for v in p[3:9]]
                continue
            t = int(p[1])
            if k == "D":
                d["D"][t] += 1
            elif k == "P":
                d["P"][t] += 1
            elif k == "E":
                kind, eid = p[2], p[3]
                d[kind][t] += 1
                if kind == "T" and eid not in seen_tnt:
                    seen_tnt.add(eid)
                    d["tnt_first"].append(tuple(float(v) for v in p[4:10]))
                if kind == "S":
                    d["sand_last"][eid] = (t, float(p[4]), float(p[5]), float(p[6]))
            elif k == "X":
                d["X"][t] += 1
                d["xs"].append((t, float(p[2]), float(p[3]), float(p[4])))
                d["Xblocks"] += int(p[5])
                d["Xcancel"] += int(p[6])
            for kk in ("D", "P", "X"):
                if k == kk:
                    d["first"].setdefault(kk, t)
    return d


def stats(vals):
    if not vals:
        return "-"
    if len(vals) == 1:
        return f"{vals[0]:.2f}"
    return f"{statistics.mean(vals):.2f}±{statistics.pstdev(vals):.2f} [{min(vals):.2f}..{max(vals):.2f}]"


def main():
    root = sys.argv[1]
    runs = {}
    for name in ORDER + sorted(os.listdir(root)):
        tp = os.path.join(root, name, "trace.csv")
        if name not in runs and os.path.exists(tp):
            runs[name] = load(tp)
    if not runs:
        print("no traces found")
        return
    names = list(runs)
    w = 22
    print("\n=== PARITY: per-tick timeline (ticks after button press) — dispenses / pistons / TNT alive / sand alive / explosions ===")
    print("tick".rjust(5) + "".join(n[:w - 1].rjust(w) for n in names))
    ticks = set()
    for d in runs.values():
        for k in ("D", "P", "X"):
            ticks |= set(d[k])
        ticks |= {t for t in d["T"] if t % 5 == 0} | {t for t in d["S"] if t % 5 == 0}
    for t in sorted(ticks):
        if t < -25:
            continue
        cells = []
        for n in names:
            d = runs[n]
            cells.append(f"{d['D'][t]}/{d['P'][t]}/{d['T'][t]}/{d['S'][t]}/{d['X'][t]}".rjust(w))
        print(str(t).rjust(5) + "".join(cells))
    print("\n=== PARITY: summary ===")
    for n in names:
        d = runs[n]
        xs = d["xs"]
        print(f"--- {n}")
        print(f"  dispenses {sum(d['D'].values())}  piston moves {sum(d['P'].values())}  explosions {len(xs)}"
              f"  blocks hit {d['Xblocks']}  cancelled {d['Xcancel']}  first: {d['first']}")
        if xs:
            print(f"  explosion x {stats([x[1] for x in xs])}")
            print(f"  explosion y {stats([x[2] for x in xs])}")
            print(f"  explosion z {stats([x[3] for x in xs])}")
            print(f"  explosion tick {stats([float(x[0]) for x in xs])}")
        tf = d["tnt_first"]
        if tf:
            print(f"  TNT spawn |vx| {stats([abs(v[3]) for v in tf])}  vy {stats([v[4] for v in tf])}  |vz| {stats([abs(v[5]) for v in tf])}")
        sl = list(d["sand_last"].values())
        if sl:
            print(f"  sand entities {len(sl)}; last seen x {stats([s[1] for s in sl])} y {stats([s[2] for s in sl])} z {stats([s[3] for s in sl])}")
        if d["integrity"]:
            a, b = d["integrity"]
            print(f"  cannon blocks: {a} pasted -> {b} left ({a - b} destroyed)")
        b = d.get("bounds")
        if b and xs:
            ox, oy, oz, sw, sh, sl = b
            inside = sum(1 for x in xs if ox <= x[1] < ox + sw and oz <= x[3] < oz + sl and oy <= x[2] < oy + sh)
            print(f"  explosions inside the cannon footprint: {inside}/{len(xs)}")
        if n == "1.8.8":
            ok = d["integrity"] and d["integrity"][0] - d["integrity"][1] == 0 and len(xs) > 0
            print("  1.8 SANITY: " + ("PASS - fired and the cannon is intact, so the setup matches how it's used" if ok else
                  "FAIL - on real 1.8 it didn't fire cleanly, so the test setup (water/lever/button) is wrong; fix that before comparing"))
        log = os.path.join(root, n, "server.log")
        if os.path.exists(log):
            for line in open(log, errors="replace"):
                if "[PARITY]" in line or "Exception" in line:
                    print("  log:", line.strip()[-170:])


if __name__ == "__main__":
    main()
