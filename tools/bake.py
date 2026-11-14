#!/usr/bin/env python3
"""
Builds the ready-to-run laG Network folder (dist/laG-Network) and checks it actually boots.

  1. Downloads Paper + Velocity (PaperMC Fill v3 API) and every plugin in tools/plugins.json
  2. Lays out proxy/ hub/ factions/ smp/ with their jars
  3. Starts each server once so it writes its default configs, then patches our settings in
  4. Starts the WHOLE network together and fails if any plugin errors on startup
  5. Adds the Windows start scripts

Usage: python tools/bake.py <local-jars-dir>
  local-jars-dir holds laGCore.jar, laGCannons.jar, FactionsUUID.jar (built earlier in CI)
"""
import hashlib
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NET = json.load(open(os.path.join(ROOT, "network", "network.json")))
PLUGINS = json.load(open(os.path.join(ROOT, "tools", "plugins.json")))
MC = NET["minecraft"]
DIST = os.path.join(ROOT, "dist", "laG-Network")
UA = {"User-Agent": "TheTumster/lag-network/1.0 (github.com/TheTumster/lag-network)"}
SERVERS = ["hub", "factions", "smp"]
SECRET_PLACEHOLDER = "LAG_CHANGE_ME_ON_FIRST_START"


def log(*a):
    print(*a, flush=True)


def get_json(url):
    headers = dict(UA)
    if "api.github.com" in url and os.environ.get("GITHUB_TOKEN"):
        headers["Authorization"] = "Bearer " + os.environ["GITHUB_TOKEN"]
    for attempt in range(6):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=30) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            if e.code == 429 or e.code >= 500:
                time.sleep(min(60, 2 ** attempt + 2))
                continue
            raise
    raise SystemExit(f"Gave up on {url}")


def download(url, dest, sha1=None):
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=120) as r, open(dest, "wb") as f:
        shutil.copyfileobj(r, f)
    if sha1:
        got = hashlib.sha1(open(dest, "rb").read()).hexdigest()
        if got != sha1:
            raise SystemExit(f"Checksum mismatch for {url}")


# ------------------------------------------------------------------ sources

def papermc(project, version=None, prefix=None):
    candidates = [version] if version else [v for group in get_json(f"https://fill.papermc.io/v3/projects/{project}")["versions"].values() for v in group]
    if prefix:
        candidates = [v for v in candidates if v.startswith(prefix)]
    b = None
    for ver in candidates:
        builds = get_json(f"https://fill.papermc.io/v3/projects/{project}/versions/{ver}/builds")
        stable = [x for x in builds if x.get("channel") == "STABLE"]
        if stable:
            b, version = stable[0], ver
            break
    if b is None:  # nothing stable anywhere: newest build of the first candidate
        version = candidates[0]
        b = get_json(f"https://fill.papermc.io/v3/projects/{project}/versions/{version}/builds")[0]
    dl = b["downloads"]["server:default"]
    return dl["url"], f"{project}-{version}-{b['id']}.jar"


def modrinth(slug, loader, mc=MC):
    q = {"loaders": json.dumps([loader])}
    if loader != "velocity":
        q["game_versions"] = json.dumps([mc])
    vs = get_json(f"https://api.modrinth.com/v2/project/{slug}/version?" + urllib.parse.urlencode(q))
    if not vs and loader == "paper":
        # many plugins label themselves spigot/bukkit; Paper runs those fine
        q["loaders"] = json.dumps(["paper", "spigot", "bukkit", "purpur"])
        vs = get_json(f"https://api.modrinth.com/v2/project/{slug}/version?" + urllib.parse.urlencode(q))
    if not vs:
        return None
    rel = [v for v in vs if v["version_type"] == "release"] or vs
    f = next((f for f in rel[0]["files"] if f["primary"]), rel[0]["files"][0])
    return f["url"], f["filename"], f["hashes"]["sha1"]


def hangar(owner_slug):
    owner, slug = owner_slug.split("/")
    data = get_json(f"https://hangar.papermc.io/api/v1/projects/{owner}/{slug}/versions?limit=25&offset=0&platform=PAPER")
    results = data.get("result", [])
    rel = [v for v in results if v.get("channel", {}).get("name", "").lower() in ("release", "stable")]
    for v in rel or results:
        dl = v.get("downloads", {}).get("PAPER", {})
        url = dl.get("downloadUrl") or dl.get("externalUrl")
        if url:
            name = dl.get("fileInfo", {}).get("name") or f"{slug}-{v['name']}.jar"
            return url, name, None
    return None


def github(repo, match=r"\.jar$"):
    for rel in get_json(f"https://api.github.com/repos/{repo}/releases?per_page=10"):
        if rel.get("prerelease") or rel.get("draft"):
            continue
        for a in rel.get("assets", []):
            if re.search(match, a["name"]) and not re.search(r"sources|javadoc", a["name"]):
                return a["browser_download_url"], a["name"], None
    return None


# ------------------------------------------------------------------ server lifecycle

def run_until_done(cwd, cmd, timeout=600, stop_cmd="stop", keep=False):
    """Start a server, wait for 'Done (', optionally stop it. Returns (proc, log lines)."""
    log(f"  starting {os.path.basename(cwd)} ...")
    p = subprocess.Popen(cmd, cwd=cwd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                         text=True, bufsize=1)
    lines = []
    done = threading.Event()

    def pump():
        for line in p.stdout:
            lines.append(line.rstrip())
            if "Done (" in line:
                done.set()

    threading.Thread(target=pump, daemon=True).start()
    start = time.time()
    while not done.is_set():
        if p.poll() is not None:
            raise SystemExit(f"{cwd} exited early:\n" + "\n".join(lines[-60:]))
        if time.time() - start > timeout:
            p.kill()
            raise SystemExit(f"{cwd} never finished starting:\n" + "\n".join(lines[-60:]))
        time.sleep(0.5)
    log(f"  {os.path.basename(cwd)} up in {time.time() - start:.0f}s")
    if keep:
        return p, lines
    stop(p, stop_cmd)
    return None, lines


def stop(p, stop_cmd="stop"):
    try:
        p.stdin.write(stop_cmd + "\n")
        p.stdin.flush()
        p.wait(timeout=120)
    except Exception:
        p.kill()


def java(mem):
    return ["java", f"-Xms{mem}", f"-Xmx{mem}", "-jar"]


# ------------------------------------------------------------------ config patching

def set_props(path, values):
    lines = open(path).read().splitlines() if os.path.exists(path) else []
    seen = set()
    out = []
    for l in lines:
        k = l.split("=", 1)[0] if "=" in l and not l.startswith("#") else None
        if k in values:
            out.append(f"{k}={values[k]}")
            seen.add(k)
        else:
            out.append(l)
    for k, v in values.items():
        if k not in seen:
            out.append(f"{k}={v}")
    open(path, "w").write("\n".join(out) + "\n")


def yaml_set(path, dotted, value):
    import yaml
    data = yaml.safe_load(open(path)) if os.path.exists(path) else {}
    cur = data
    keys = dotted.split(".")
    for k in keys[:-1]:
        cur = cur.setdefault(k, {})
    cur[keys[-1]] = value
    yaml.safe_dump(data, open(path, "w"), sort_keys=False)


def toml_set(text, key, value_literal):
    pat = re.compile(rf"^{re.escape(key)}\s*=.*$", re.M)
    if pat.search(text):
        return pat.sub(f"{key} = {value_literal}", text, count=1)
    return text


SERVER_PROPS = {
    "hub":      dict(difficulty="peaceful", gamemode="adventure", pvp="false", **{"level-type": "minecraft\\:flat"},
                     **{"generate-structures": "false", "spawn-monsters": "false", "allow-nether": "false",
                        "view-distance": "8", "simulation-distance": "4"}),
    "factions": dict(difficulty="normal", gamemode="survival", pvp="true",
                     **{"view-distance": "10", "simulation-distance": "8"}),
    "smp":      dict(difficulty="normal", gamemode="survival", pvp="false",
                     **{"view-distance": "10", "simulation-distance": "8"}),
}


def patch_backend(name):
    d = os.path.join(DIST, name)
    port = NET["servers"][name]["port"]
    set_props(os.path.join(d, "server.properties"), {
        "server-ip": "127.0.0.1", "server-port": port, "online-mode": "false", "enforce-secure-profile": "false",
        "motd": NET["name"], "spawn-protection": "0", "max-players": "100", "allow-flight": "true",
        **SERVER_PROPS[name]})
    pg = os.path.join(d, "config", "paper-global.yml")
    yaml_set(pg, "proxies.velocity.enabled", True)
    yaml_set(pg, "proxies.velocity.online-mode", True)
    yaml_set(pg, "proxies.velocity.secret", SECRET_PLACEHOLDER)
    # laG Core role + name
    core_dir = os.path.join(d, "plugins", "laGCore")
    os.makedirs(core_dir, exist_ok=True)
    src = open(os.path.join(ROOT, "plugins", "lag-core", "src", "main", "resources", "config.yml")).read()
    src = src.replace("role: hub", f"role: {name}").replace('network-name: "laG Network"', f'network-name: "{NET["name"]}"')
    open(os.path.join(core_dir, "config.yml"), "w").write(src)


def patch_proxy():
    d = os.path.join(DIST, "proxy")
    path = os.path.join(d, "velocity.toml")
    t = open(path).read()
    t = toml_set(t, "bind", f'"0.0.0.0:{NET["public_port"]}"')
    t = toml_set(t, "motd", '"<gradient:#38E0FF:#B040FF><bold>' + NET["name"] + '</bold></gradient> <gray>• Factions • SMP"')
    t = toml_set(t, "player-info-forwarding-mode", '"modern"')
    t = toml_set(t, "online-mode", "true")
    t = toml_set(t, "bungee-plugin-message-channel", "true")
    servers = "\n".join(f'{s} = "127.0.0.1:{NET["servers"][s]["port"]}"' for s in SERVERS)
    t = re.sub(r"\[servers\].*?(?=\n\[)", "[servers]\n" + servers + '\ntry = ["hub"]\n', t, flags=re.S)
    t = re.sub(r"\[forced-hosts\].*?(?=\n\[)", "[forced-hosts]\n", t, flags=re.S)
    open(path, "w").write(t)
    open(os.path.join(d, "forwarding.secret"), "w").write(SECRET_PLACEHOLDER)


# ------------------------------------------------------------------ main

def main():
    local_dir = sys.argv[1]
    shutil.rmtree(DIST, ignore_errors=True)
    os.makedirs(DIST)

    log("== Server software")
    vurl, vname = papermc("velocity", prefix=NET.get("velocity_line", "3."))
    purl, pname = papermc("paper", MC)
    log(f"  {vname}\n  {pname}")
    download(vurl, os.path.join(DIST, "proxy", "velocity.jar"))
    download(purl, os.path.join(DIST, "_paper.jar"))
    for s in SERVERS:
        os.makedirs(os.path.join(DIST, s, "plugins"), exist_ok=True)
        shutil.copy(os.path.join(DIST, "_paper.jar"), os.path.join(DIST, s, "paper.jar"))
        open(os.path.join(DIST, s, "eula.txt"), "w").write("eula=true\n")
    os.remove(os.path.join(DIST, "_paper.jar"))

    log("== Plugins")
    manifest = {"velocity": vname, "paper": pname, "plugins": {}}
    missing = []
    for server, items in PLUGINS.items():
        if server.startswith("_"):
            continue
        pdir = os.path.join(DIST, server, "plugins")
        os.makedirs(pdir, exist_ok=True)
        for it in items:
            if "local" in it:
                shutil.copy(os.path.join(local_dir, it["local"] + ".jar"), os.path.join(pdir, it["local"] + ".jar"))
                manifest["plugins"].setdefault(server, []).append(it["local"] + " (built)")
                continue
            try:
                if "modrinth" in it:
                    res = modrinth(it["modrinth"], it["loader"])
                elif "hangar" in it:
                    res = hangar(it["hangar"])
                else:
                    res = github(it["github"], it.get("match", r"\.jar$"))
            except Exception as e:
                res = None
                log(f"  ! {it}: {e}")
            if not res:
                (log if it.get("optional") else missing.append)(f"  ! no {MC} build for {it}")
                continue
            url, fname, sha1 = res
            download(url, os.path.join(pdir, fname), sha1)
            manifest["plugins"].setdefault(server, []).append(fname)
            log(f"  {server:9} + {fname}")
    if missing:
        raise SystemExit("Required plugins missing:\n" + "\n".join(map(str, missing)))

    log("== First start (generate configs)")
    for s in SERVERS:
        run_until_done(os.path.join(DIST, s), java("2G") + ["paper.jar", "--nogui"])
    run_until_done(os.path.join(DIST, "proxy"), java("512M") + ["velocity.jar"], stop_cmd="shutdown")

    log("== Patching configs")
    for s in SERVERS:
        patch_backend(s)
    patch_proxy()

    log("== Full network boot test")
    secret = secrets.token_hex(16)
    _write_secret(secret)
    procs, logs = {}, {}
    try:
        for s in SERVERS:
            procs[s], logs[s] = run_until_done(os.path.join(DIST, s), java("2G") + ["paper.jar", "--nogui"], keep=True)
        procs["proxy"], logs["proxy"] = run_until_done(os.path.join(DIST, "proxy"), java("512M") + ["velocity.jar"], keep=True)
        time.sleep(10)
    finally:
        for name, p in procs.items():
            if p:
                stop(p, "shutdown" if name == "proxy" else "stop")
    _write_secret(SECRET_PLACEHOLDER)

    bad = re.compile(r"Error occurred while enabling|Could not load|Could not pass event|Exception in thread|"
                     r"Ambiguous plugin name|UnknownDependencyException|Failed to load plugin|plugin .* failed", re.I)
    problems = []
    for name, lines in logs.items():
        for l in lines:
            if bad.search(l):
                problems.append(f"[{name}] {l}")
    report = {"manifest": manifest, "problems": problems}
    json.dump(report, open(os.path.join(ROOT, "dist", "bake-report.json"), "w"), indent=2)
    for name, lines in logs.items():
        log(f"----- {name} (last 25 lines)")
        for l in lines[-25:]:
            log("  " + l)
    if problems:
        log("== PROBLEMS")
        for p in problems:
            log("  " + p)
        raise SystemExit(1)

    log("== Cleaning test worlds/logs")
    for s in SERVERS + ["proxy"]:
        for junk in ("world", "world_nether", "world_the_end", "logs", "cache", "usercache.json",
                     "banned-ips.json", "banned-players.json", "ops.json", "whitelist.json", "libraries", "versions"):
            pth = os.path.join(DIST, s, junk)
            if os.path.isdir(pth):
                shutil.rmtree(pth)
            elif os.path.isfile(pth):
                os.remove(pth)

    log("== Start scripts")
    for f in os.listdir(os.path.join(ROOT, "windows")):
        shutil.copy(os.path.join(ROOT, "windows", f), os.path.join(DIST, f))
    os.makedirs(os.path.join(DIST, "tools"), exist_ok=True)
    for f in os.listdir(os.path.join(ROOT, "windows_tools")):
        shutil.copy(os.path.join(ROOT, "windows_tools", f), os.path.join(DIST, "tools", f))
    shutil.copy(os.path.join(ROOT, "network", "network.json"), os.path.join(DIST, "network.json"))
    log("All good.")


def _write_secret(secret):
    open(os.path.join(DIST, "proxy", "forwarding.secret"), "w").write(secret)
    for s in SERVERS:
        yaml_set(os.path.join(DIST, s, "config", "paper-global.yml"), "proxies.velocity.secret", secret)


if __name__ == "__main__":
    main()
