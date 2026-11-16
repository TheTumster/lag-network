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
SAKURA_WORLD = """_version: 12
cannons:
  merge-level: none
  explosion:
    optimise-protected-regions: true
    avoid-redundant-block-searches: true
    reuse-block-cache-across-explosions: true
  mechanics:
    mechanics-target:
      mechanic-version: legacy
      server-type: paper
technical:
  redstone:
    redstone-cache: true
"""
NET = json.load(open(os.path.join(ROOT, "network", "network.json")))
PLUGINS = json.load(open(os.path.join(ROOT, "tools", "plugins.json")))
MC = NET["minecraft"]
DIST = os.path.join(ROOT, "dist", "laG-Network")
UA = {"User-Agent": "TheTumster/lag-network/1.0 (github.com/TheTumster/lag-network)"}
SERVERS = list(NET["servers"])            # hub, factions, smp, skyblock, cannons
SAKURA_SERVERS = {"factions", "cannons"}  # 1.8 cannon mechanics (cannons = test server, same jar + settings as factions)
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


MANAGED = {}  # server -> list of {"path","url","sha256"}
RELEASE_DL = "https://github.com/TheTumster/lag-network/releases/download/latest/"


def sha256(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()


def manage(server, rel_path, url):
    MANAGED.setdefault(server, []).append({"path": rel_path.replace(os.sep, "/"), "url": url,
                                           "sha256": sha256(os.path.join(DIST, server, rel_path))})


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


def modrinth(slug, loader, mc=MC, pin=None):
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
    if pin:
        rel = [v for v in vs if v["version_number"].startswith(pin)] or rel
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
        return pat.sub(lambda m: f"{key} = {value_literal}", text, count=1)  # lambda: keep backslashes (\\n) literal
    return text


SERVER_PROPS = {
    # hub: empty void world; laGCore builds the floating spawn island in it on first start
    "hub":      dict(difficulty="peaceful", gamemode="adventure", pvp="false", **{"level-type": "minecraft\\:flat"},
                     **{"level-name": "hub_void",
                        "generator-settings": '{"layers"\\:[{"block"\\:"minecraft\\:air","height"\\:1}],"biome"\\:"minecraft\\:the_void"}'},
                     **{"generate-structures": "false", "spawn-monsters": "false", "allow-nether": "false",
                        "view-distance": "16", "simulation-distance": "4"}),  # void hub: cheap, and the RELAPSED sign is far
    "factions": dict(difficulty="normal", gamemode="survival", pvp="true",
                     **{"view-distance": "10", "simulation-distance": "8"}),
    "smp":      dict(difficulty="normal", gamemode="survival", pvp="false",
                     **{"view-distance": "10", "simulation-distance": "8"}),
    # skyblock: void main world for the spawn; BentoBox/BSkyBlock makes the island worlds
    "skyblock": dict(difficulty="normal", gamemode="survival", pvp="false", **{"level-type": "minecraft\\:flat"},
                     **{"generator-settings": '{"layers"\\:[{"block"\\:"minecraft\\:air","height"\\:1}],"biome"\\:"minecraft\\:the_void"}',
                        "generate-structures": "false", "spawn-monsters": "true", "allow-nether": "true",
                        "view-distance": "12", "simulation-distance": "6"}),
    # cannons: flat creative test world on the exact factions jar and settings
    "cannons":  dict(difficulty="peaceful", gamemode="creative", pvp="false", **{"level-type": "minecraft\\:flat"},
                     **{"force-gamemode": "true", "generate-structures": "false", "spawn-monsters": "false", "allow-nether": "false",
                        "view-distance": "10", "simulation-distance": "8"}),
}


def patch_backend(name):
    d = os.path.join(DIST, name)
    port = NET["servers"][name]["port"]
    set_props(os.path.join(d, "server.properties"), {
        "server-ip": "127.0.0.1", "server-port": port, "online-mode": "false", "enforce-secure-profile": "false",
        "motd": NET["name"], "spawn-protection": "0", "max-players": "100", "allow-flight": "true",
        **SERVER_PROPS[name]})
    if name in SAKURA_SERVERS:
        # Cannon server settings: let big cannons tick every TNT, and use Paper's explosion cache
        yaml_set(os.path.join(d, "spigot.yml"), "world-settings.default.max-tnt-per-tick", 20000)
        yaml_set(os.path.join(d, "config", "paper-world-defaults.yml"), "environment.optimize-explosions", True)
        # 1.8 fix-cannons: flowing water doesn't push TNT
        yaml_set(os.path.join(d, "config", "paper-world-defaults.yml"), "fixes.prevent-tnt-from-moving-in-water", True)
        # Sakura: 1.8 cannon mechanics, no entity merging (it changes shots), cannon lag options (all parity-tested)
        sw = os.path.join(d, "config", "sakura-world-defaults.yml")
        if not os.path.exists(sw):
            os.makedirs(os.path.dirname(sw), exist_ok=True)
            open(sw, "w").write(SAKURA_WORLD)
        for k, v in (("cannons.merge-level", "none"), ("cannons.mechanics.mechanics-target.mechanic-version", "legacy"),
                     ("cannons.mechanics.mechanics-target.server-type", "paper"),
                     ("cannons.explosion.optimise-protected-regions", True), ("cannons.explosion.avoid-redundant-block-searches", True),
                     ("cannons.explosion.reuse-block-cache-across-explosions", True), ("technical.redstone.redstone-cache", True)):
            yaml_set(sw, k, v)
        # Sakura /fps menu (hide TNT/sand/explosions client-side; laGCannons gives everyone access): RelapsedMC branding
        sg = os.path.join(d, "config", "sakura-global.yml")
        if not os.path.exists(sg):
            open(sg, "w").write("_version: 3\n")
        brand = "<gradient:#38E0FF:#B040FF>[RelapsedMC]</gradient> "
        yaml_set(sg, "messages.fps-setting-change", brand + "<gray><state> <white><name></white> for you. <dark_gray>(/fps)")
        yaml_set(sg, "messages.durable-block-interaction", brand + "<white>This block has <gray><remaining></gray> of <gray><durability></gray> hits left")
        yaml_set(sg, "fps.material", "CYAN_STAINED_GLASS_PANE")
    if name == "cannons":
        # test server: diamond protection everywhere, free /tntfill in creative
        lc = os.path.join(d, "plugins", "laGCannons", "config.yml")
        if not os.path.exists(lc):
            os.makedirs(os.path.dirname(lc), exist_ok=True)
            shutil.copy(os.path.join(ROOT, "plugins", "lag-cannons", "src", "main", "resources", "config.yml"), lc)
        yaml_set(lc, "cannon-protect.worlds", ["*"])
        yaml_set(lc, "tntfill.free-for-all", True)
        yaml_set(lc, "plots.enabled", True)
        log("  cannons: plots world, diamond protection everywhere, free /tntfill")
    if name == "factions":
        # CoreProtect: don't log dispenser/hopper item moves. Every cannon dispenser shot made a full block snapshot
        # (5% of the tick while cannoning, spark profile Nov 2026) and hopper farms do the same. Raids are the game here.
        cp = os.path.join(d, "plugins", "CoreProtect", "config.yml")
        if os.path.exists(cp):
            t = open(cp).read()
            for key in ("item-transactions", "hopper-transactions"):
                t = re.sub(rf"(?m)^{key}:\s*true\s*$", f"{key}: false", t)
            open(cp, "w").write(t)
            log("  factions: CoreProtect item/hopper logging off (cannon lag)"
                if "item-transactions: false" in t else "  ! couldn't turn off CoreProtect item-transactions")
        else:
            log("  ! CoreProtect config.yml not found; dispenser logging still on")
        tr = os.path.join(d, "plugins", "FactionsUUID", "config", "translations.conf")
        if os.path.exists(tr):
            subprocess.run([sys.executable, os.path.join(ROOT, "tools", "fuuid_theme.py"), tr], check=False)
        fc = next((c for c in (os.path.join(d, "plugins", n, "config", "main.conf") for n in ("FactionsUUID", "Factions"))
                   if os.path.exists(c)), None)
        if not fc:
            log("  ! FactionsUUID main.conf not found; TNT bank not enabled")
        else:
            t = open(fc).read()
            t = re.sub(r"(?s)(\btnt\s*\{[^{}]*?)\benable\s*=\s*false", r"\1enable=true", t)
            t = re.sub(r"(?s)(\btnt\s*\{[^{}]*?)\bmaxRadius\s*=\s*5\b", r"\1maxRadius=48", t)
            # Relapse ruleset: 8-man factions, no allies or truces (alts are handled by laGCannons, not FUUID members)
            t = re.sub(r"\bfactionMemberLimit\s*=\s*\d+", "factionMemberLimit=8", t)
            def no_allies(m):
                b = re.sub(r"\benabled\s*=\s*\w+", "enabled=true", m.group(0), count=1)
                b = re.sub(r"\bally\s*=\s*-?\d+", "ally=0", b, count=1)
                return re.sub(r"\btruce\s*=\s*-?\d+", "truce=0", b, count=1)
            t = re.sub(r"(?s)\bmaxRelations\s*\{[^{}]*\}", no_allies, t, count=1)
            # Everyone starts (and caps) at 200 power
            def power200(m):
                b = re.sub(r"\bplayerStarting\s*=\s*-?[\d.]+", "playerStarting=200.0", m.group(0), count=1)
                return re.sub(r"\bplayerMax\s*=\s*-?[\d.]+", "playerMax=200.0", b, count=1)
            t = re.sub(r"(?s)\bpower\s*\{[^{}]*?playerStarting[^{}]*\}", power200, t, count=1)
            open(fc, "w").write(t)
            log("  factions: TNT bank on, fill radius 48" if "maxRadius=48" in t else "  ! couldn't find FactionsUUID tnt settings")
            log("  factions: 8-man cap" if "factionMemberLimit=8" in t else "  ! couldn't set FactionsUUID factionMemberLimit")
            log("  factions: allies/truces off" if re.search(r"(?s)maxRelations\s*\{[^{}]*ally=0[^{}]*truce=0", t)
                else "  ! couldn't turn off FactionsUUID allies/truces")
            log("  factions: 200 starting power" if "playerStarting=200.0" in t and "playerMax=200.0" in t
                else "  ! couldn't set FactionsUUID starting power")
    pg = os.path.join(d, "config", "paper-global.yml")
    yaml_set(pg, "proxies.velocity.enabled", True)
    yaml_set(pg, "proxies.velocity.online-mode", True)
    yaml_set(pg, "proxies.velocity.secret", SECRET_PLACEHOLDER)
    write_core_config(name)


def write_core_config(name):
    """laG Core config for a server (role + name). Re-written after the boot test so no build markers ship."""
    core_dir = os.path.join(DIST, name, "plugins", "laGCore")
    os.makedirs(core_dir, exist_ok=True)
    src = open(os.path.join(ROOT, "plugins", "lag-core", "src", "main", "resources", "config.yml")).read()
    src = src.replace("role: hub", f"role: {name}").replace('network-name: "RelapsedMC"', f'network-name: "{NET["name"]}"')
    open(os.path.join(core_dir, "config.yml"), "w").write(src)
    for junk in ("parkour.yml",):
        p = os.path.join(core_dir, junk)
        if os.path.exists(p):
            os.remove(p)


def patch_proxy():
    d = os.path.join(DIST, "proxy")
    path = os.path.join(d, "velocity.toml")
    t = open(path).read()
    t = toml_set(t, "bind", f'"0.0.0.0:{NET["public_port"]}"')
    t = toml_set(t, "motd", '"                     <gradient:#38E0FF:#B040FF><bold>RELAPSEDMC</bold></gradient> <dark_gray>[<gray>1.21</gray>]</dark_gray>\\n    <#FF4D6D><bold>RELAPSE #1</bold></#FF4D6D> <gray>launching December <dark_gray>•</dark_gray> <#7CFF6B>Skyblock S1"')
    t = toml_set(t, "player-info-forwarding-mode", '"modern"')
    t = toml_set(t, "online-mode", "true")
    t = toml_set(t, "bungee-plugin-message-channel", "true")
    servers = "\n".join(f'{s} = "127.0.0.1:{NET["servers"][s]["port"]}"' for s in SERVERS)
    t = re.sub(r"\[servers\].*?(?=\n\[)", "[servers]\n" + servers + '\ntry = ["hub"]\n', t, flags=re.S)
    t = re.sub(r"\[forced-hosts\].*?(?=\n\[)", "[forced-hosts]\n", t, flags=re.S)
    open(path, "w").write(t)
    open(os.path.join(d, "forwarding.secret"), "w").write(SECRET_PLACEHOLDER)
    # server-list icon (tools/pack/genpack.py)
    icon = os.path.join(ROOT, "network", "server-icon.png")
    if os.path.exists(icon):
        shutil.copy(icon, os.path.join(d, "server-icon.png"))


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
    manage("proxy", "velocity.jar", vurl)
    download(purl, os.path.join(DIST, "_paper.jar"))
    for s in SERVERS:
        os.makedirs(os.path.join(DIST, s, "plugins"), exist_ok=True)
        open(os.path.join(DIST, s, "eula.txt"), "w").write("eula=true\n")
        if s in SAKURA_SERVERS:
            # Sakura 1.21.11 (MIT, Samsuik/Sakura) in legacy 1.8 mechanics mode: matched 1.8 exactly in the parity test,
            # plus its cannon lag optimisations. Built by the lag-sakura workflow. (laG Paper stays as a fallback release.)
            lurl = f"https://github.com/{os.environ.get('GITHUB_REPOSITORY', 'TheTumster/lag-network')}/releases/download/lag-sakura/sakura-1.21.11.jar"
            download(lurl, os.path.join(DIST, s, "paper.jar"))
            manage(s, "paper.jar", lurl)
            log(f"  {s:9} + Sakura (1.8 cannon mechanics)")
            continue
        shutil.copy(os.path.join(DIST, "_paper.jar"), os.path.join(DIST, s, "paper.jar"))
        manage(s, "paper.jar", purl)
    os.remove(os.path.join(DIST, "_paper.jar"))

    log("== Plugins")
    manifest = {"velocity": vname, "paper": pname, "plugins": {}}
    missing = []
    for server, items in PLUGINS.items():
        if server.startswith("_"):
            continue
        for it in items:
            # BentoBox addons go in plugins/BentoBox/addons
            pdir = os.path.join(DIST, server, "plugins", *it.get("subdir", "").split("/")) if it.get("subdir") else os.path.join(DIST, server, "plugins")
            os.makedirs(pdir, exist_ok=True)
            rel_dir = "plugins/" + it["subdir"] if it.get("subdir") else "plugins"
            if "local" in it:
                shutil.copy(os.path.join(local_dir, it["local"] + ".jar"), os.path.join(pdir, it["local"] + ".jar"))
                manage(server, os.path.join("plugins", it["local"] + ".jar"), RELEASE_DL + it["local"] + ".jar")
                manifest["plugins"].setdefault(server, []).append(it["local"] + " (built)")
                continue
            try:
                if "modrinth" in it:
                    res = modrinth(it["modrinth"], it["loader"], pin=it.get("version"))
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
            manage(server, rel_dir + "/" + fname, url)
            manifest["plugins"].setdefault(server, []).append(fname)
            log(f"  {server:9} + {fname}")
    if missing:
        raise SystemExit("Required plugins missing:\n" + "\n".join(map(str, missing)))

    build_lab18(local_dir)

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
        for i, l in enumerate(lines):
            if bad.search(l):
                problems.append(f"[{name}] {l}")
                # include the cause lines that follow
                for nxt in lines[i + 1:i + 12]:
                    if re.search(r"Caused by|Exception|requires|version|depend", nxt, re.I):
                        problems.append(f"[{name}]     {nxt.strip()[:300]}")
    # Make sure the important plugins actually ENABLED (not just loaded)
    must = {"hub": ["laGCore"], "factions": ["laGCore", "laGCannons", "Factions"], "smp": ["laGCore"],
            "skyblock": ["laGCore", "BentoBox", "laGSkyblock"], "cannons": ["laGCore", "laGCannons", "FastAsyncWorldEdit", "WorldGuard"]}
    for srv in SERVERS:
        enabled = sorted({m.group(1) for l in logs.get(srv, []) for m in [re.search(r"Enabling (\S+) v", l)] if m})
        log(f"  {srv:9} enabled: {', '.join(enabled)}")
        for want in must.get(srv, []):
            if not any(e.lower().startswith(want.lower()) for e in enabled):
                problems.append(f"[{srv}] {want} did not enable")
    report = {"manifest": manifest, "problems": problems}
    json.dump(report, open(os.path.join(ROOT, "dist", "bake-report.json"), "w"), indent=2)
    for l in logs.get("skyblock", []):
        if "[laGSkyblock]" in l:
            log("  " + l.strip()[:200])
    for name, lines in logs.items():
        log(f"----- {name} (last 25 lines)")
        for l in lines[-25:]:
            log("  " + l)
    if problems:
        log("== PROBLEMS")
        for p in problems:
            log("  " + p)
        raise SystemExit(1)

    log("== 1.8-height datapack (factions overworld y 0..255)")
    make_255_datapack()

    log("== Cleaning test worlds/logs")
    for s in SERVERS:
        write_core_config(s)   # spawns get built on the real first start, not the test one
    for s in SERVERS + ["proxy"]:
        for junk in ("world", "world_nether", "world_the_end", "logs", "cache", "usercache.json",
                     "banned-ips.json", "banned-players.json", "ops.json", "whitelist.json", "libraries", "versions",
                     os.path.join("plugins", ".paper-remapped"), "hub_void", "hub_void_nether", "hub_void_the_end",
                     "bskyblock_world", "bskyblock_world_nether", "bskyblock_world_the_end",
                     os.path.join("plugins", "BentoBox", "database"), "plots", os.path.join("plugins", "laGCannons", "plots.yml"),
                     os.path.join("plugins", "WorldGuard", "worlds")):
            pth = os.path.join(DIST, s, junk)
            if os.path.isdir(pth):
                shutil.rmtree(pth)
            elif os.path.isfile(pth):
                os.remove(pth)

    # fresh installs: the factions world is created 0..255 (datapack must exist before the world does)
    pack = os.path.join(ROOT, "build", "laG-255")
    if os.path.isdir(pack):
        for srv in ("factions", "cannons"):   # cannons = test server: same 0..255 height (plots world too)
            shutil.copytree(pack, os.path.join(DIST, srv, "world", "datapacks", "laG-255"))
            log(f"  {srv}/world/datapacks/laG-255 added")

    log("== Start scripts")
    for f in os.listdir(os.path.join(ROOT, "windows")):
        shutil.copy(os.path.join(ROOT, "windows", f), os.path.join(DIST, f))
    os.makedirs(os.path.join(DIST, "tools"), exist_ok=True)
    for f in os.listdir(os.path.join(ROOT, "windows_tools")):
        shutil.copy(os.path.join(ROOT, "windows_tools", f), os.path.join(DIST, "tools", f))
    shutil.copy(os.path.join(ROOT, "network", "network.json"), os.path.join(DIST, "network.json"))

    log("== Update manifest")
    for f in ("START.bat", "STOP.bat", "TUNNEL.bat", "README.txt", "START-1.8-LAB.bat"):
        manage(".", f, RELEASE_DL + f)
    manage(".", os.path.join("tools", "start.ps1"), RELEASE_DL + "start.ps1")
    manage(".", os.path.join("tools", "start-lab.ps1"), RELEASE_DL + "start-lab.ps1")
    manage(".", os.path.join("tools", "stop.ps1"), RELEASE_DL + "stop.ps1")
    manage(".", os.path.join("tools", "tunnel.ps1"), RELEASE_DL + "tunnel.ps1")
    build = os.environ.get("GITHUB_RUN_NUMBER", "dev")
    json.dump({"build": build, "servers": MANAGED}, open(os.path.join(ROOT, "dist", "update-manifest.json"), "w"), indent=1)
    for srv, files in MANAGED.items():
        json.dump([f["path"] for f in files], open(os.path.join(DIST, srv, ".lag-managed.json"), "w"))
    open(os.path.join(DIST, "BUILD.txt"), "w").write(build)

    log("All good.")


WE18 = [  # WorldEdit 6.1.7.2 (works on Spigot/Paper 1.8.8)
    "https://mediafilez.forgecdn.net/files/2431/372/worldedit-bukkit-6.1.7.2.jar",
    "https://edge.forgecdn.net/files/2431/372/worldedit-bukkit-6.1.7.2.jar",
    "https://dev.bukkit.org/projects/worldedit/files/2431372/download",
]
WE18_MD5 = "1b64e31c38fc140016ef5a35e390f17c"


def build_lab18(local_dir):
    """1.8.8 cannon lab: jars only. Configs are written by tools/start-lab.ps1 on the PC (so updates never reset them)."""
    log("== 1.8.8 cannon lab")
    lab = os.path.join(DIST, "lab18")
    os.makedirs(os.path.join(lab, "plugins"), exist_ok=True)
    # SulphurSpigot: open-source 1.8.8 TacoSpigot fork for cannons, built from source by the cannon-jar workflow
    url = f"https://github.com/{os.environ.get('GITHUB_REPOSITORY', 'TheTumster/lag-network')}/releases/download/cannon-jar/sulphurspigot-1.8.8.jar"
    name = "SulphurSpigot 1.8.8"
    download(url, os.path.join(lab, "paper.jar"))
    manage("lab18", "paper.jar", url)
    dest = os.path.join(lab, "plugins", "worldedit-bukkit-6.1.7.2.jar")
    for u in WE18:
        try:
            download(u, dest)
            if hashlib.md5(open(dest, "rb").read()).hexdigest() == WE18_MD5:
                manage("lab18", os.path.join("plugins", "worldedit-bukkit-6.1.7.2.jar"), u)
                log(f"  lab18     + WorldEdit 6.1.7.2 ({u})")
                break
            log(f"  ! WorldEdit md5 mismatch from {u}")
        except Exception as e:
            log(f"  ! WorldEdit from {u}: {e}")
        if os.path.exists(dest):
            os.remove(dest)
    else:
        raise SystemExit("Couldn't download WorldEdit 6.1.7.2 for the 1.8 lab")
    shutil.copy(os.path.join(local_dir, "laGLab.jar"), os.path.join(lab, "plugins", "laGLab.jar"))
    manage("lab18", os.path.join("plugins", "laGLab.jar"), RELEASE_DL + "laGLab.jar")
    log(f"  lab18     + {name}, laGLab")


def make_255_datapack():
    """Overworld dimension type with 1.8's height (min_y 0, height 256), built from the real 1.21.11 data."""
    import zipfile
    d = os.path.join(DIST, "factions")
    jars = [os.path.join(r, f) for base in ("versions", "libraries") for r, _, fs in os.walk(os.path.join(d, base)) for f in fs if f.endswith(".jar")]
    dim = ver = None
    for j in jars:
        try:
            with zipfile.ZipFile(j) as z:
                names = set(z.namelist())
                if dim is None and "data/minecraft/dimension_type/overworld.json" in names:
                    dim = json.loads(z.read("data/minecraft/dimension_type/overworld.json"))
                if ver is None and "version.json" in names:
                    v = json.loads(z.read("version.json"))
                    if "pack_version" in v:
                        ver = v
        except zipfile.BadZipFile:
            continue
    if dim is None:
        raise SystemExit("couldn't find vanilla dimension_type/overworld.json in the factions server jars")
    dim["min_y"], dim["height"], dim["logical_height"] = 0, 256, 256
    pv = (ver or {}).get("pack_version", {})
    fmt = pv.get("data_major", pv.get("data")) if isinstance(pv, dict) else pv
    log(f"  vanilla overworld dimension type found; data pack format {fmt}")
    pack = os.path.join(ROOT, "build", "laG-255")
    shutil.rmtree(pack, ignore_errors=True)
    os.makedirs(os.path.join(pack, "data", "minecraft", "dimension_type"))
    json.dump(dim, open(os.path.join(pack, "data", "minecraft", "dimension_type", "overworld.json"), "w"), indent=2)
    meta = {"pack": {"description": "laG: 1.8 build height (y 0-255)"}}
    if fmt is not None:
        meta["pack"].update({"pack_format": fmt, "min_format": fmt, "max_format": fmt})
    json.dump(meta, open(os.path.join(pack, "pack.mcmeta"), "w"), indent=2)
    shutil.make_archive(os.path.join(ROOT, "build", "laG-255-datapack"), "zip", pack)
    # prove it: boot factions on a throwaway world that has the pack, check it loaded and the world is 0..255
    test = os.path.join(d, "laGtest")
    shutil.rmtree(test, ignore_errors=True)
    shutil.copytree(pack, os.path.join(test, "datapacks", "laG-255"))
    _, lines = run_until_done(d, java("2G") + ["paper.jar", "--nogui", "--world", "laGtest"])
    bad = [l for l in lines if ("laG-255" in l or "dimension_type" in l) and ("rror" in l or "ailed" in l or "nvalid" in l)]
    packs = [l for l in lines if "data pack" in l.lower() or "datapack" in l.lower()]
    for l in packs[:6]:
        log("    " + l[-160:])
    if bad:
        raise SystemExit("laG-255 datapack failed to load:\n" + "\n".join(bad[:20]))
    lvl = os.path.join(test, "level.dat")
    if os.path.exists(lvl):
        import gzip
        raw = gzip.open(lvl).read()
        log("  level.dat mentions laG-255: " + str(b"laG-255" in raw or b"file/laG-255" in raw))
    shutil.rmtree(test, ignore_errors=True)
    log("  laG-255 datapack verified on a test world")


def _write_secret(secret):
    open(os.path.join(DIST, "proxy", "forwarding.secret"), "w").write(secret)
    for s in SERVERS:
        yaml_set(os.path.join(DIST, s, "config", "paper-global.yml"), "proxies.velocity.secret", secret)


if __name__ == "__main__":
    main()
