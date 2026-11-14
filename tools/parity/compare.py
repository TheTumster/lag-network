"""Compare cannon traces: python compare.py <dir> ; each run is <dir>/<name>/trace.csv (+ server.log).

Prints a side-by-side timeline (ticks after the button press) of dispenses, piston moves, live TNT,
falling sand and explosions, then explosion spread and how much of the cannon survived.
"""
import collections, os, statistics, sys

ORDER = ["1.8.8", "lag-paper", "ours-nomerge", "ours-no-laGCannons"]


def load(path):
    d = {"D": collections.Counter(), "P": collections.Counter(), "T": collections.Counter(), "S": collections.Counter(),
         "X": collections.Counter(), "Xblocks": 0, "Xcancel": 0, "xs": [], "first": {}, "sand_last": {}, "integrity": None,
         "tnt_first": [], "paths": collections.defaultdict(list), "disp": collections.defaultdict(list),
         "pist": collections.defaultdict(list), "snap": {}, "rs": collections.defaultdict(dict)}
    seen_tnt = set()
    with open(path) as fh:
        for line in fh:
            p = line.rstrip("\n").split(",")
            k = p[0]
            if k == "S":
                d["snap"][(int(p[2]), int(p[3]), int(p[4]))] = p[5]
                continue
            if k == "R":
                d["rs"][int(p[1])][(int(p[2]), int(p[3]), int(p[4]))] = int(p[6])
                continue
            if k == "I":
                d["integrity"] = (int(p[1]), int(p[2]))
                if len(p) >= 9:
                    d["bounds"] = [int(v) for v in p[3:9]]
                continue
            t = int(p[1])
            if k == "D":
                d["D"][t] += 1
                d["disp"][(int(p[2]), int(p[3]), int(p[4]))].append(t)
            elif k == "P":
                d["P"][t] += 1
                d["pist"][(int(p[2]), int(p[3]), int(p[4]))].append((t, p[5]))
            elif k == "E":
                kind, eid = p[2], p[3]
                d[kind][t] += 1
                d["paths"][kind + eid].append((t, *(float(v) for v in p[4:10])))
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
    ref = runs.get("1.8.8")
    if ref:
        for n in names:
            if n != "1.8.8":
                redstone_diff(ref, runs[n], "1.8", n)
                divergence(ref, runs[n], "1.8", n)
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
        sand_paths = [v for k, v in d["paths"].items() if k.startswith("S")]
        if sand_paths:
            firsts = collections.Counter((p[0][0] // 10) * 10 for p in sand_paths)
            print("  sand starts falling (tick bucket: count): " + ", ".join(f"t{k}:{v}" for k, v in sorted(firsts.items())))
            spots = collections.Counter((int(p[0][1] // 1), int(p[0][2] // 1), int(p[0][3] // 1)) for p in sand_paths)
            cols = collections.Counter((int(p[0][1] // 1), int(p[0][3] // 1)) for p in sand_paths)
            print("  sand starts by column x,z (count): " + ", ".join(f"{k}:{v}" for k, v in cols.most_common(12)))
            ys = collections.Counter(int(p[0][2] // 1) for p in sand_paths)
            print("  sand starts by height y (count): " + ", ".join(f"{k}:{v}" for k, v in ys.most_common(10)))
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
            spots = collections.Counter((int(x[1] // 1), int(x[2] // 1), int(x[3] // 1)) for x in xs
                                        if ox <= x[1] < ox + sw and oz <= x[3] < oz + sl and oy <= x[2] < oy + sh)
            first = {}
            for x in xs:
                first.setdefault((int(x[1] // 1), int(x[2] // 1), int(x[3] // 1)), x[0])
            print("  where inside (block x,y,z: count, first tick): " + ", ".join(
                f"{k[0]},{k[1]},{k[2]}: {v} @t{first[k]}" for k, v in spots.most_common(15)))
            earliest = sorted(xs)[:5]
            print("  first 5 explosions: " + ", ".join(f"t{x[0]} ({x[1]:.1f},{x[2]:.1f},{x[3]:.1f})" for x in earliest))
        if n == "1.8.8":
            ok = d["integrity"] and d["integrity"][0] - d["integrity"][1] == 0 and len(xs) > 0
            print("  1.8 SANITY: " + ("PASS - fired and the cannon is intact, so the setup matches how it's used" if ok else
                  "FAIL - on real 1.8 it didn't fire cleanly, so the test setup (water/lever/button) is wrong; fix that before comparing"))
        log = os.path.join(root, n, "server.log")
        if os.path.exists(log):
            for line in open(log, errors="replace"):
                if "[PARITY]" in line or "Exception" in line:
                    print("  log:", line.strip()[-170:])


def redstone_diff(a, b, an, bn):
    """Which dispensers / pistons fire differently (count or timing)."""
    print(f"\n=== REDSTONE {an} vs {bn} ===")
    sa, sb = a["snap"], b["snap"]
    diff = [(p, sa.get(p), sb.get(p)) for p in sorted(set(sa) | set(sb)) if sa.get(p) != sb.get(p)]
    print(f"  state before the button: {len(diff)} of {len(set(sa) | set(sb))} redstone parts differ")
    for p, x, y in diff[:70]:
        print(f"    {p}: {an} {x} | {bn} {y}")
    # first ticks where redstone power changes differ
    shown = 0
    for t in sorted(set(a["rs"]) | set(b["rs"])):
        ra, rb = a["rs"].get(t, {}), b["rs"].get(t, {})
        dd = [(p, ra.get(p), rb.get(p)) for p in sorted(set(ra) | set(rb)) if ra.get(p) != rb.get(p)]
        if dd:
            print(f"  t{t}: {len(dd)} redstone changes differ (pos: {an} new power | {bn} new power): "
                  + ", ".join(f"{p}:{x}|{y}" for p, x, y in dd[:12]))
            shown += 1
            if shown >= 8:
                break
    for what, key in (("dispensers", "disp"), ("pistons", "pist")):
        da, db = a[key], b[key]
        diffs = []
        for pos in set(da) | set(db):
            ta = [x if isinstance(x, int) else x[0] for x in da.get(pos, [])]
            tb = [x if isinstance(x, int) else x[0] for x in db.get(pos, [])]
            if ta != tb:
                first = next((i for i, (x, y) in enumerate(zip(ta, tb)) if x != y), min(len(ta), len(tb)))
                diffs.append((ta[first] if first < len(ta) else (tb[first] if first < len(tb) else 0), pos, len(ta), len(tb), ta[:6], tb[:6]))
        print(f"  {what}: {len(diffs)} of {len(set(da) | set(db))} positions differ")
        for t, pos, na, nb, ta, tb in sorted(diffs)[:15]:
            print(f"    {pos}: {an} {na}x {ta} | {bn} {nb}x {tb}")


def divergence(a, b, an, bn):
    """Match TNT/sand by kind + spawn tick + spawn spot and report where they first split apart."""
    def keyed(d):
        out = collections.defaultdict(list)
        for k, path in d["paths"].items():
            t0, x, y, z = path[0][:4]
            out[(k[0], t0, round(x, 1), round(y, 1), round(z, 1))].append(path)
        return out
    ka, kb = keyed(a), keyed(b)
    rows = []
    matched = 0
    for key in ka:
        if key not in kb:
            continue
        for pa, pb in zip(ka[key], kb[key]):
            matched += 1
            byt = {r[0]: r for r in pb}
            prev = None
            for ra in pa:
                rb = byt.get(ra[0])
                if rb is None:
                    rows.append((ra[0], key, "gone in " + bn, ra, prev))
                    break
                if max(abs(ra[i] - rb[i]) for i in (1, 2, 3)) > 0.01:
                    rows.append((ra[0], key, "moved differently", ra, rb, prev))
                    break
                prev = (ra, rb)
            else:
                if len(pb) > len(pa):
                    rows.append((pa[-1][0], key, "gone in " + an, pa[-1], prev))
    print(f"\n=== DIVERGENCE {an} vs {bn}: {matched} entities matched by spawn; {len(rows)} split apart ===")
    hist = collections.Counter(r[2] for r in rows)
    print("  kinds:", dict(hist))
    for r in sorted(rows, key=lambda r: r[0])[:25]:
        t, key, why = r[0], r[1], r[2]
        f = lambda v: "(" + ",".join(f"{x:.3f}" for x in v[1:4]) + " v " + ",".join(f"{x:.3f}" for x in v[4:7]) + ")"
        line = f"  t{t} {key[0]} spawned t{key[1]} at {key[2:]}: {why}"
        if why == "moved differently":
            line += f" | {an} {f(r[3])} | {bn} {f(r[4])}"
            if r[5]:
                line += f" | tick before: {an} {f(r[5][0])} {bn} {f(r[5][1])}"
        print(line)


if __name__ == "__main__":
    main()
