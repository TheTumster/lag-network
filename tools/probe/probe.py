import json, urllib.request, urllib.parse, re
UA = {"User-Agent": "lag-network-probe"}
def gj(u):
    try: return json.load(urllib.request.urlopen(urllib.request.Request(u, headers=UA), timeout=30))
    except Exception as e: return {"err": str(e)}
def vers(s):
    q = urllib.parse.urlencode({"loaders": json.dumps(["paper","spigot","bukkit","purpur"]), "game_versions": json.dumps(["1.21.11"])})
    vs = gj(f"https://api.modrinth.com/v2/project/{s}/version?{q}")
    if isinstance(vs, dict): print(f"{s}: {vs}"); return
    print(f"{s}: {len(vs)} versions for 1.21.11")
    for v in vs[:2]:
        f = next((f for f in v["files"] if f["primary"]), v["files"][0])
        print(f"   {v['version_number']} {v['version_type']} {v['date_published'][:10]} loaders={v['loaders']} file={f['filename']} deps={[(d.get('project_id'),d['dependency_type']) for d in v['dependencies']]}")
for q in ["excellentshop","betterrtp","shop","economy shop","sell limit","dynamic price shop"]:
    r = gj("https://api.modrinth.com/v2/search?" + urllib.parse.urlencode({"query": q, "limit": 10, "facets": json.dumps([["versions:1.21.11"],["project_type:plugin"]])}))
    print("SEARCH", q)
    for h in r.get("hits", []): print(f"   {h['slug']} | {h['title']} | dl={h['downloads']} | upd={h['date_modified'][:10]} | {h['description'][:100]}")
for s in ["simple-rtp","donut-rtp-and-rtp-zone","griefpreventioneasygui","genius-shop","economyshopgui","dynamicshop","guishop","veinminer","nightcore","nightcore-lib"]:
    vers(s)
    p = gj(f"https://api.modrinth.com/v2/project/{s}")
    body = p.get("body","") if isinstance(p, dict) else ""
    hits = [m for m in re.findall(r"[^\n]{0,120}(?:[Ll]imit|[Cc]ap|[Cc]ooldown|[Ss]afe|[Pp]remium|[Tt]oggle|[Tt]ree|[Ll]og|[Mm]ax)[^\n]{0,120}", body)][:14]
    print("  ABOUT", s, p.get("license",{}).get("id") if isinstance(p,dict) else "", p.get("source_url") if isinstance(p,dict) else "")
    for h in hits: print("     >", h.strip()[:240])
for p in ["SuperRonanCraft/BetterRTP","NightExpress/ExcellentShop","nightexpress/ExcellentShop","ChestShop-authors/ChestShop","Ghost_chu/QuickShop-Hikari","2008Choco/VeinMiner","GriefPrevention/GriefPrevention"]:
    o, sl = p.split("/")
    d = gj(f"https://hangar.papermc.io/api/v1/projects/{o}/{sl}/versions?limit=2&offset=0&platform=PAPER")
    print("HANGAR", p, d if "err" in d else [(v["name"], v["createdAt"][:10], "1.21.11" in (v.get("platformDependencies",{}).get("PAPER") or [])) for v in d.get("result",[])])
for r in ["SuperRonanCraft/BetterRTP","nulli0n/ExcellentShop-spigot","nulli0n/NexEngine-spigot","nulli0n/nightcore-spigot"]:
    d = gj(f"https://api.github.com/repos/{r}/releases?per_page=3")
    print("GH", r, d if isinstance(d, dict) else [(x["tag_name"], x["published_at"][:10], [a["name"] for a in x["assets"]]) for x in d])
