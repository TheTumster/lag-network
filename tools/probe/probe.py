import json, urllib.request, urllib.parse
UA = {"User-Agent": "lag-network-probe"}
def gj(u):
    try: return json.load(urllib.request.urlopen(urllib.request.Request(u, headers=UA), timeout=30))
    except Exception as e: return {"err": str(e)}
for s in ["gsit","betterrtp","veinminer","excellentshop","nightcore","griefprevention","essentialsx","chestshop","quickshop-hikari",
          "ultimatetimber","timber","zshop","economyshopgui","huskhomes","rtp","huskrtp","treecapitator","veinminer-enchantment",
          "griefprevention-gui","gpflags","vault","vaultunlocked","luckperms","placeholderapi","shopkeepers","dynamicshop","itemshop",
          "simple-shop","guishop","sellgui","axshops","axgens","simplertp","rtpqueue","wild","towny","lands","fastasyncworldedit","coreprotect",
          "timbermc","treeassist","lumberjack","oreveinminer","veinminerplus","sit","bettersit","coreprotect-tnt"]:
    q = urllib.parse.urlencode({"loaders": json.dumps(["paper","spigot","bukkit","purpur"]), "game_versions": json.dumps(["1.21.11"])})
    vs = gj(f"https://api.modrinth.com/v2/project/{s}/version?{q}")
    if isinstance(vs, dict): print(f"{s}: {vs}"); continue
    print(f"{s}: {len(vs)} versions for 1.21.11")
    for v in vs[:2]:
        f = next((f for f in v["files"] if f["primary"]), v["files"][0])
        print(f"   {v['version_number']} {v['version_type']} {v['date_published'][:10]} loaders={v['loaders']} file={f['filename']} deps={[(d.get('project_id'),d['dependency_type']) for d in v['dependencies']]}")
for q in ["timber","veinminer","rtp","shop sell limit","chest shop","sit","claim trust gui","griefprevention"]:
    r = gj("https://api.modrinth.com/v2/search?" + urllib.parse.urlencode({"query": q, "limit": 8, "facets": json.dumps([["categories:paper"],["versions:1.21.11"],["project_type:plugin"]])}))
    print("SEARCH", q)
    for h in r.get("hits", []): print(f"   {h['slug']} | {h['title']} | dl={h['downloads']} | upd={h['date_modified'][:10]} | {h['description'][:90]}")
for p in ["Gecolay/GSit","SuperRonanCraft/BetterRTP","HelpChat/PlaceholderAPI","nightexpress/ExcellentShop","ChestShop/ChestShop","Ghost_chu/QuickShop-Hikari","2008Choco/VeinMiner"]:
    o, sl = p.split("/")
    d = gj(f"https://hangar.papermc.io/api/v1/projects/{o}/{sl}/versions?limit=2&offset=0&platform=PAPER")
    if "err" in d: print("HANGAR", p, d); continue
    for v in d.get("result", [])[:2]:
        print("HANGAR", p, v["name"], v["createdAt"][:10], v.get("channel",{}).get("name"), v.get("platformDependencies",{}).get("PAPER"))
