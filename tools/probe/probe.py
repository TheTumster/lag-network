import json, urllib.request, urllib.parse, zipfile, io, re, sys
UA = {"User-Agent": "lag-network-probe"}
def gj(u): return json.load(urllib.request.urlopen(urllib.request.Request(u, headers=UA), timeout=60))
def jar_modrinth(s):
    q = urllib.parse.urlencode({"loaders": json.dumps(["paper","spigot","bukkit","purpur"]), "game_versions": json.dumps(["1.21.11"])})
    vs = gj(f"https://api.modrinth.com/v2/project/{s}/version?{q}")
    rel = [v for v in vs if v["version_type"] == "release"] or vs
    f = next((f for f in rel[0]["files"] if f["primary"]), rel[0]["files"][0])
    return f["url"], rel[0]["version_number"]
def jar_gh(r):
    for rel in gj(f"https://api.github.com/repos/{r}/releases?per_page=5"):
        if rel["prerelease"] or rel["draft"]: continue
        for a in rel["assets"]:
            if a["name"].endswith(".jar"): return a["browser_download_url"], rel["tag_name"]
def show(name, url, ver, full=(), grep=None, maxlen=9000):
    data = urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=120).read()
    z = zipfile.ZipFile(io.BytesIO(data))
    res = [n for n in z.namelist() if re.search(r"\.(yml|yaml|json|conf|toml|properties)$", n) and not n.startswith(("META-INF/maven","org/","com/","net/","kotlin","io/","dev/","de/","me/","assets/")) ]
    print(f"########## {name} {ver} ({len(data)} bytes)")
    print("  RESOURCES:", res[:80])
    try:
        pl = z.read("plugin.yml" if "plugin.yml" in z.namelist() else "paper-plugin.yml").decode()
        print("  PLUGIN.YML:", "\n".join(l for l in pl.splitlines() if re.match(r"^(name|version|main|api-version|depend|softdepend|loadbefore|folia)", l)))
    except Exception as e: print("  no plugin.yml", e)
    for n in res:
        if any(re.search(p, n) for p in full):
            t = z.read(n).decode("utf-8", "replace")
            print(f"===== FILE {n} ({len(t)} chars)\n{t[:maxlen]}\n===== END {n}")
    if grep:
        for n in res:
            t = z.read(n).decode("utf-8", "replace")
            for l in t.splitlines():
                if re.search(grep, l): print(f"  GREP {n}: {l.strip()[:200]}")
which = sys.argv[1] if len(sys.argv) > 1 else "all"
jobs = [
 ("economyshopgui", "m", [r"^config\.yml$", r"ExampleShop\.yml$", r"shops/Ores\.yml$", r"1-sections/Ores\.yml$", r"LanguageFiles/lang-en\.yml$"], None),
]
for name, src, full, grep in jobs:
    try:
        url, ver = jar_modrinth(name) if src == "m" else jar_gh(name)
        show(name, url, ver, full, grep, maxlen=60000)
    except Exception as e:
        print("##########", name, "FAILED", e)
