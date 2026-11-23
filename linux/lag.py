#!/usr/bin/env python3
"""RelapsedMC network control for the Linux VPS (the Windows START/STOP.bat equivalent).

    lag start [--no-update]   update from the latest build, then start every server + the proxy (tmux session "lag")
    lag stop                  save + stop every server cleanly, then the proxy
    lag restart               stop, then start
    lag status                what's running
    lag console <server>      open a server's console (detach: Ctrl+B then D)
    lag cmd <server> <text>   type a command into a server's console
    lag update                pull changed jars/configs from the latest build only
    lag backup                consistent world/plugin-data backup (+ off-machine copy if backup.conf sets REMOTE)

Lives at <root>/tools/lag.py; <root> holds hub/, factions/, ..., proxy/, network.json (same layout as the PC).
Only python3 stdlib + tmux (+ zstd/rclone for backups). Worlds and configs we don't manage are never touched.
"""
import hashlib, json, os, re, secrets, shutil, subprocess, sys, tarfile, time, urllib.request
from datetime import datetime, timedelta

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REL = "https://github.com/TheTumster/lag-network/releases/latest/download/"
SESSION = "lag"
FLAGS = ("-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions "
         "-XX:+DisableExplicitGC -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M "
         "-XX:G1ReservePercent=20 -XX:+AlwaysPreTouch")
PLACEHOLDER = "LAG_CHANGE_ME_ON_FIRST_START"


def say(msg, c="36"):
    print(f"  \033[{c}m{msg}\033[0m", flush=True)


def net():
    return json.load(open(os.path.join(ROOT, "network.json")))


def fetch(url, timeout=30):
    req = urllib.request.Request(url, headers={"User-Agent": "RelapsedMC-updater"})
    return urllib.request.urlopen(req, timeout=timeout).read()


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


# ---------------------------------------------------------------- tmux
def tmux(*args, check=False):
    return subprocess.run(["tmux", *args], capture_output=True, text=True, check=check)


def running():
    r = tmux("list-windows", "-t", SESSION, "-F", "#{window_name}")
    return set(r.stdout.split()) if r.returncode == 0 else set()


def send(name, text):
    tmux("send-keys", "-t", f"{SESSION}:{name}", text, "Enter")


# ---------------------------------------------------------------- java
def java():
    local = os.path.join(ROOT, "runtime", "bin", "java")
    if os.path.exists(local):
        return local
    sysj = shutil.which("java")
    if sysj:
        v = subprocess.run([sysj, "-version"], capture_output=True, text=True).stderr
        m = re.search(r'"(\d+)', v)
        if m and int(m.group(1)) >= 25:
            return sysj
    arch = {"x86_64": "x64", "aarch64": "aarch64"}.get(os.uname().machine, "x64")
    say("Downloading Java 25 (one time)...")
    tgz = os.path.join(ROOT, "_jre.tar.gz")
    open(tgz, "wb").write(fetch(f"https://api.adoptium.net/v3/binary/latest/25/ga/linux/{arch}/jre/hotspot/normal/eclipse", 300))
    tmp = os.path.join(ROOT, "_jre")
    shutil.rmtree(tmp, ignore_errors=True)
    with tarfile.open(tgz) as t:
        t.extractall(tmp, filter="data")
    shutil.move(os.path.join(tmp, os.listdir(tmp)[0]), os.path.join(ROOT, "runtime"))
    shutil.rmtree(tmp); os.remove(tgz)
    say("Java ready.", "32")
    return local


# ---------------------------------------------------------------- update (same manifest the PC launcher uses)
def update():
    try:
        m = json.loads(fetch(REL + "update-manifest.json", 15))
    except Exception as e:
        say(f"Couldn't check for updates ({e}) - starting what you have.", "90")
        return
    bf = os.path.join(ROOT, "BUILD.txt")
    cur = open(bf).read().strip() if os.path.exists(bf) else "?"
    if str(m["build"]) == cur:
        say(f"Up to date (build {cur}).", "90")
        return
    say(f"Updating build {cur} -> {m['build']}...", "33")
    changed = 0
    wanted = set(net()["servers"]) | {"proxy", "."}   # not the 1.8 lab
    for srv, files in m["servers"].items():
        if srv not in wanted:
            continue
        files = [f for f in files if not f["path"].endswith((".bat", ".ps1", ".cmd"))]   # Windows launcher bits
        d = os.path.join(ROOT, srv)
        state = os.path.join(d, ".lag-managed.json")
        old = json.load(open(state)) if os.path.exists(state) else []
        new = []
        for f in files:
            new.append(f["path"])
            dest = os.path.join(d, f["path"])
            if os.path.exists(dest) and sha256(dest) == f["sha256"]:
                continue
            say(f"  {srv}: {f['path']}", "37")
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            data = fetch(f["url"], 300)
            if hashlib.sha256(data).hexdigest() != f["sha256"]:
                raise SystemExit(f"Checksum mismatch on {f['path']}")
            open(dest + ".download", "wb").write(data)
            os.replace(dest + ".download", dest)
            changed += 1
        for p in old:
            if p not in new and os.path.isfile(os.path.join(d, p)):
                os.remove(os.path.join(d, p)); say(f"  {srv}: removed old {p}", "90")
        json.dump(new, open(state, "w"))
    open(bf, "w").write(str(m["build"]))
    say(f"Updated ({changed} files).", "32")


def install_new_servers():
    """New servers in the network (like skyblock was): copy their folders from the package, register with the proxy."""
    try:
        nn = json.loads(fetch(REL + "network.json", 15))
    except Exception:
        return
    missing = [s for s in nn["servers"] if not os.path.exists(os.path.join(ROOT, s, "server.properties"))]
    if missing:
        say(f"New servers: {', '.join(missing)} - downloading the network package (one time)...", "33")
        import zipfile, io
        z = zipfile.ZipFile(io.BytesIO(fetch(REL + "laG-Network.zip", 900)))
        for s in missing:
            pre = next((n.split(s + "/")[0] + s + "/" for n in z.namelist() if f"/{s}/server.properties" in "/" + n), None)
            if not pre:
                continue
            for n in z.namelist():
                if n.startswith(pre) and not n.endswith("/"):
                    out = os.path.join(ROOT, s, n[len(pre):])
                    os.makedirs(os.path.dirname(out), exist_ok=True)
                    open(out, "wb").write(z.read(n))
            say(f"  added {s}", "32")
    json.dump(nn, open(os.path.join(ROOT, "network.json"), "w"), indent=1)
    vt = os.path.join(ROOT, "proxy", "velocity.toml")
    if os.path.exists(vt):
        t = open(vt, encoding="utf-8").read()
        n = t
        for s, cfg in nn["servers"].items():
            if not re.search(rf"(?m)^{s}\s*=", n):
                n = re.sub(r"(?m)^\[servers\][ \t]*$", f'[servers]\n{s} = "127.0.0.1:{cfg["port"]}"', n, count=1)
        if n != t:
            open(vt, "w", encoding="utf-8").write(n); say("Proxy now knows every server.", "32")


def secure():
    """First run: a real forwarding secret. Every start: backends listen on localhost only (only the proxy is public)."""
    sf = os.path.join(ROOT, "proxy", "forwarding.secret")
    secret = open(sf).read().strip()
    if secret == PLACEHOLDER:
        secret = secrets.token_hex(16)
        open(sf, "w").write(secret)
        say("Network secured (first run).", "32")
    for s in net()["servers"]:
        pg = os.path.join(ROOT, s, "config", "paper-global.yml")
        if os.path.exists(pg):
            t = open(pg, encoding="utf-8").read()
            if PLACEHOLDER in t:
                open(pg, "w", encoding="utf-8").write(t.replace(PLACEHOLDER, secret))
        sp = os.path.join(ROOT, s, "server.properties")
        if os.path.exists(sp):
            lines = open(sp, encoding="utf-8").read().splitlines()
            want = {"server-ip": "127.0.0.1", "enable-rcon": "false"}
            for k, v in want.items():
                idx = next((i for i, l in enumerate(lines) if l.startswith(k + "=")), None)
                if idx is None:
                    lines.append(f"{k}={v}")
                else:
                    lines[idx] = f"{k}={v}"
            open(sp, "w", encoding="utf-8").write("\n".join(lines) + "\n")


# ---------------------------------------------------------------- start / stop
def start(do_update=True):
    if running():
        say("The network is already running. Use: lag restart", "33")
        return
    if do_update:
        update()
        install_new_servers()
    secure()
    j = java()
    n = net()
    cmds = {s: f'"{j}" -Xms{c["memory"]} -Xmx{c["memory"]} {FLAGS} -jar paper.jar --nogui' for s, c in n["servers"].items()}
    cmds["proxy"] = f'"{j}" -Xms{n["proxy_memory"]} -Xmx{n["proxy_memory"]} -jar velocity.jar'
    first = True
    for s, cmd in cmds.items():
        d = os.path.join(ROOT, s)
        if first:
            tmux("new-session", "-d", "-s", SESSION, "-n", s, "-c", d, cmd, check=True)
            tmux("set-option", "-t", SESSION, "history-limit", "20000")
            first = False
        else:
            tmux("new-window", "-t", SESSION, "-n", s, "-c", d, cmd, check=True)
        say(f"Starting {s}...")
    say(f"All servers are starting. Consoles: lag console <{'|'.join(cmds)}>", "32")


def stop(timeout=120):
    up = running()
    if not up:
        say("Nothing is running.", "90")
        return
    backends = [s for s in net()["servers"] if s in up]
    for s in backends:
        send(s, "stop"); say(f"{s}  -> stopping", "37")
    deadline = time.time() + timeout
    while time.time() < deadline and any(s in running() for s in backends):
        time.sleep(1)
    left = [s for s in backends if s in running()]
    if left:
        say(f"Still running after {timeout}s: {', '.join(left)} - check with: lag console {left[0]}", "33")
        raise SystemExit(1)
    say("All servers saved and stopped.", "32")
    if "proxy" in running():
        send("proxy", "shutdown")
        for _ in range(15):
            if "proxy" not in running():
                break
            time.sleep(1)
    tmux("kill-session", "-t", SESSION)
    say("Proxy stopped. Network is off.", "32")


def status():
    up = running()
    bf = os.path.join(ROOT, "BUILD.txt")
    say(f"Build {open(bf).read().strip() if os.path.exists(bf) else '?'}", "90")
    for s in list(net()["servers"]) + ["proxy"]:
        say(f"{s:10} {'running' if s in up else 'stopped'}", "32" if s in up else "90")


# ---------------------------------------------------------------- backups
SKIP_DIRS = {"cache", "libraries", "versions", "logs", "crash-reports", ".paper-remapped", "bundler", "backups"}


def backup():
    """save-off + save-all flush on each running server, archive worlds + plugin data, save-on. Keeps KEEP_DAYS locally;
    copies to REMOTE (any rclone remote, e.g. b2:relapsed-backups) when backup.conf sets it."""
    conf = {"KEEP_DAYS": "7", "REMOTE": "", "REMOTE_KEEP_DAYS": "30"}
    cf = os.path.join(ROOT, "backup.conf")
    if os.path.exists(cf):
        for l in open(cf):
            if "=" in l and not l.lstrip().startswith("#"):
                k, v = l.split("=", 1)
                conf[k.strip()] = v.strip().strip('"')
    stamp = datetime.now().strftime("%Y-%m-%d_%H%M")
    out = os.path.join(ROOT, "backups", stamp)
    os.makedirs(out, exist_ok=True)
    up = running()
    zstd = shutil.which("zstd")
    for s in net()["servers"]:
        d = os.path.join(ROOT, s)
        if not os.path.isdir(d):
            continue
        live = s in up
        if live:
            send(s, "save-off"); send(s, "save-all flush"); time.sleep(8)
        try:
            items = [i for i in os.listdir(d) if i not in SKIP_DIRS and not i.endswith(".jar")]
            ext = ".tar.zst" if zstd else ".tar.gz"
            dest = os.path.join(out, s + ext)
            if zstd:
                tar = subprocess.Popen(["tar", "-C", d, "-cf", "-", *items], stdout=subprocess.PIPE)
                subprocess.run([zstd, "-q", "-T0", "-3", "-o", dest], stdin=tar.stdout, check=True)
                tar.wait()
            else:
                subprocess.run(["tar", "-C", d, "-czf", dest, *items], check=True)
            say(f"{s}: {os.path.getsize(dest) // (1 << 20)} MB", "37")
        finally:
            if live:
                send(s, "save-on")
    # proxy config (LuckPerms data lives on the servers / its DB; velocity.toml + secret are small)
    subprocess.run(["tar", "-C", ROOT, "-czf", os.path.join(out, "proxy.tar.gz"), "--exclude=proxy/logs", "proxy"], check=False)
    keep = datetime.now() - timedelta(days=int(conf["KEEP_DAYS"]))
    for old in os.listdir(os.path.join(ROOT, "backups")):
        try:
            if datetime.strptime(old, "%Y-%m-%d_%H%M") < keep:
                shutil.rmtree(os.path.join(ROOT, "backups", old)); say(f"removed old backup {old}", "90")
        except ValueError:
            pass
    if conf["REMOTE"]:
        if not shutil.which("rclone"):
            say("REMOTE is set but rclone isn't installed - backup kept locally only.", "33")
            raise SystemExit(1)
        r = subprocess.run(["rclone", "copy", out, f"{conf['REMOTE']}/{stamp}"])
        if r.returncode != 0:
            say("Off-machine copy FAILED - local backup is still there.", "31")
            raise SystemExit(1)
        subprocess.run(["rclone", "delete", conf["REMOTE"], "--min-age", f"{conf['REMOTE_KEEP_DAYS']}d"])
        subprocess.run(["rclone", "rmdirs", conf["REMOTE"], "--leave-root"])
        say(f"Copied off-machine to {conf['REMOTE']}/{stamp}", "32")
    say(f"Backup done: backups/{stamp}", "32")


def main(a):
    cmd = a[0] if a else "status"
    if cmd == "start":
        start("--no-update" not in a)
    elif cmd == "stop":
        stop()
    elif cmd == "restart":
        stop(); start("--no-update" not in a)
    elif cmd == "status":
        status()
    elif cmd == "update":
        if running():
            say("Updating while running: changes apply on the next restart.", "33")
        update(); install_new_servers()
    elif cmd == "console" and len(a) > 1:
        os.execvp("tmux", ["tmux", "attach", "-t", f"{SESSION}:{a[1]}"])
    elif cmd == "cmd" and len(a) > 2:
        send(a[1], " ".join(a[2:]))
    elif cmd == "backup":
        backup()
    else:
        print(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])
