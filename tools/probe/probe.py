"""Decompile SmartSpawner's give command to see how it validates the mob type."""
import json, os, subprocess, urllib.request, zipfile

UA = {"User-Agent": "lag-network-probe"}
v = json.load(urllib.request.urlopen(urllib.request.Request(
    'https://api.modrinth.com/v2/project/smartspawner/version?loaders=["paper"]', headers=UA)))[0]
print("SmartSpawner", v["version_number"])
urllib.request.urlretrieve(v["files"][0]["url"], "ss.jar")
urllib.request.urlretrieve("https://repo1.maven.org/maven2/org/benf/cfr/0.152/cfr-0.152.jar", "cfr.jar")
z = zipfile.ZipFile("ss.jar")
names = [n for n in z.namelist() if n.endswith(".class") and "libs" not in n]
hits = [n for n in names if "give" in n.lower() or "SpawnerSettings" in n or "EntityLoot" in n or "LootConfig" in n]
print("\n".join(hits))
os.makedirs("out", exist_ok=True)
for n in hits:
    z.extract(n, "cls")
for n in hits:
    if "$" in n:
        continue
    src = subprocess.run(["java", "-jar", "cfr.jar", os.path.join("cls", n)], capture_output=True, text=True).stdout
    print("=" * 30, n)
    print(src[:12000])
