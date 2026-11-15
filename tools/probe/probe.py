"""Print which plugin sources have a 1.21.11 build (run in CI, where Modrinth/Hangar/GitHub are reachable)."""
import json, os, sys, urllib.parse
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import bake

CANDIDATES = [
    ("modrinth", "bentobox", "paper"), ("modrinth", "bskyblock", "paper"), ("modrinth", "level", "paper"),
    ("modrinth", "challenges", "paper"), ("modrinth", "warps", "paper"), ("modrinth", "upgrades", "paper"),
    ("modrinth", "smartspawner", "paper"), ("modrinth", "rosestacker", "paper"), ("modrinth", "economyshopgui", "paper"),
    ("modrinth", "decentholograms", "paper"), ("modrinth", "plotsquared", "paper"), ("modrinth", "worldguard", "paper"),
    ("modrinth", "auctionhouse", "paper"), ("modrinth", "zauctionhouse", "paper"), ("modrinth", "shopkeepers", "paper"),
    ("modrinth", "fancyholograms", "paper"), ("modrinth", "fancynpcs", "paper"), ("modrinth", "ultimateshop", "paper"),
    ("hangar", "BentoBoxWorld/BentoBox", None), ("hangar", "IntellectualSites/PlotSquared", None),
    ("hangar", "Rosewood/RoseStacker", None), ("hangar", "NighterDevelopment/SmartSpawner", None),
    ("github", "BentoBoxWorld/BentoBox", None), ("github", "BentoBoxWorld/BSkyBlock", None), ("github", "BentoBoxWorld/Level", None),
    ("github", "BentoBoxWorld/Challenges", None), ("github", "BentoBoxWorld/Warps", None), ("github", "BentoBoxWorld/Upgrades", None),
    ("github", "IntellectualSites/PlotSquared", None), ("github", "NighterDevelopment/SmartSpawner", None),
    ("github", "Rosewood-Development/RoseStacker", None),
]
for kind, ref, loader in CANDIDATES:
    try:
        if kind == "modrinth":
            r = bake.modrinth(ref, loader)
        elif kind == "hangar":
            r = bake.hangar(ref)
        else:
            r = bake.github(ref)
        print(f"PROBE {kind:8} {ref:40} -> {r[1] if r else 'NONE'}")
    except Exception as e:
        print(f"PROBE {kind:8} {ref:40} -> ERROR {str(e)[:120]}")
