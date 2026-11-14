# laG 1.8.8 cannon lab: a plain 1.8 server (creative, flat, WorldEdit, /tntfill) to get cannons working on real 1.8.
# Join it directly at localhost:25570 with a 1.8.9 client. It's separate from the network (no proxy).
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Lab = "$Root\lab18"
function Say($msg, $color = "Cyan") { Write-Host "  $msg" -ForegroundColor $color }
Write-Host ""
Write-Host "  laG 1.8 Cannon Lab" -ForegroundColor Magenta
Write-Host ""
if (-not (Test-Path "$Lab\paper.jar")) {
    Say "The lab isn't downloaded yet. Run START.bat once (it auto-updates), then run this again." Yellow
    exit 1
}

# ---- Java 8 (1.8 servers need it; private copy in .\runtime8, doesn't touch your other Java)
$java = "$Root\runtime8\bin\java.exe"
if (-not (Test-Path $java)) {
    Say "Downloading Java 8 for the 1.8 server (one time, ~40 MB)..."
    $zip = "$env:TEMP\lag-jre8.zip"
    Invoke-WebRequest "https://api.adoptium.net/v3/binary/latest/8/ga/windows/x64/jre/hotspot/normal/eclipse" -OutFile $zip -UseBasicParsing
    $tmp = "$env:TEMP\lag-jre8"
    if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
    Expand-Archive $zip $tmp
    Move-Item (Get-ChildItem $tmp | Select-Object -First 1).FullName "$Root\runtime8"
    Remove-Item $zip -Force
    Say "Java 8 ready." Green
}

# ---- First-run config (never overwrites your changes except the TNT limit)
if (-not (Test-Path "$Lab\eula.txt")) { Set-Content "$Lab\eula.txt" "eula=true" }
if (-not (Test-Path "$Lab\server.properties")) {
    @(
        "server-port=25570", "online-mode=true", "motd=laG 1.8 Cannon Lab", "level-type=FLAT", "gamemode=1",
        "force-gamemode=true", "difficulty=0", "spawn-monsters=false", "spawn-animals=false", "spawn-npcs=false",
        "spawn-protection=0", "allow-flight=true", "max-players=10", "view-distance=10"
    ) | Set-Content "$Lab\server.properties"
}
if (-not (Test-Path "$Lab\spigot.yml")) {
    # Spigot fills in everything else on first start
    @("settings:", "  timeout-time: 600", "world-settings:", "  default:", "    max-tnt-per-tick: 20000") | Set-Content "$Lab\spigot.yml"
} else {
    $t = Get-Content "$Lab\spigot.yml" -Raw
    $n = $t -replace 'max-tnt-per-tick:\s*\d+', 'max-tnt-per-tick: 20000'
    if ($n -ne $t) { Set-Content "$Lab\spigot.yml" $n -NoNewline }
}
New-Item -ItemType Directory -Force -Path "$Lab\plugins\WorldEdit\schematics" | Out-Null

Say "Join with a 1.8.9 client at:  localhost:25570" Green
Say "First time: type  op YourName  in the lab window to get WorldEdit." White
Say "Schematics go in:  lab18\plugins\WorldEdit\schematics  then  //schem load name" White
Start-Process cmd -WorkingDirectory $Lab -ArgumentList "/k title laG 1.8 lab && `"$java`" -Xms3G -Xmx3G -XX:+UseG1GC -jar paper.jar"
