# laG Network launcher: makes sure Java is there, secures the network on first run, then starts all 4 servers.
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
$net = Get-Content "$Root\network.json" -Raw | ConvertFrom-Json

function Say($msg, $color = "Cyan") { Write-Host "  $msg" -ForegroundColor $color }
Write-Host ""
Write-Host "  laG Network" -ForegroundColor Magenta
Write-Host ""

# ---- Java 25+ (uses a private copy in .\runtime if your PC doesn't have one)
$java = "$Root\runtime\bin\java.exe"
if (-not (Test-Path $java)) {
    $sys = Get-Command java -ErrorAction SilentlyContinue
    $ok = $false
    if ($sys) {
        $v = (& java -version 2>&1 | Select-Object -First 1) -as [string]
        if ($v -match '"(\d+)') { $ok = [int]$Matches[1] -ge 25 }
    }
    if ($ok) {
        $java = $sys.Source
    } else {
        Say "Downloading Java 25 (one time, ~50 MB)..."
        $zip = "$env:TEMP\lag-jre25.zip"
        Invoke-WebRequest "https://api.adoptium.net/v3/binary/latest/25/ga/windows/x64/jre/hotspot/normal/eclipse" -OutFile $zip -UseBasicParsing
        $tmp = "$env:TEMP\lag-jre25"
        if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
        Expand-Archive $zip $tmp
        Move-Item (Get-ChildItem $tmp | Select-Object -First 1).FullName "$Root\runtime"
        Remove-Item $zip -Force
        Say "Java ready." Green
    }
}


# ---- Auto-update: pull any new server/plugin jars from the latest build. Worlds and configs are never touched.
function Update-Network {
    try {
        $m = Invoke-RestMethod "https://github.com/TheTumster/lag-network/releases/latest/download/update-manifest.json" -TimeoutSec 15
    } catch {
        Say "Couldn't check for updates (offline or repo private) - starting what you have." DarkGray
        return
    }
    $current = if (Test-Path "$Root\BUILD.txt") { (Get-Content "$Root\BUILD.txt" -Raw).Trim() } else { "?" }
    if ("$($m.build)" -eq $current) { Say "Up to date (build $current)." DarkGray; return }
    Say "Updating build $current -> $($m.build)..." Yellow
    $changed = 0
    foreach ($srv in $m.servers.PSObject.Properties) {
        $dir = "$Root\$($srv.Name)"
        $stateFile = "$dir\.lag-managed.json"
        $old = if (Test-Path $stateFile) { @(Get-Content $stateFile -Raw | ConvertFrom-Json) } else { @() }
        $new = @()
        foreach ($f in $srv.Value) {
            $new += $f.path
            $dest = Join-Path $dir ($f.path -replace '/', '\')
            $have = if (Test-Path $dest) { (Get-FileHash $dest -Algorithm SHA256).Hash.ToLower() } else { "" }
            if ($have -ne $f.sha256) {
                Say "  $($srv.Name): $($f.path)" White
                New-Item -ItemType Directory -Force -Path (Split-Path $dest) | Out-Null
                Invoke-WebRequest $f.url -OutFile "$dest.download" -UseBasicParsing -Headers @{ "User-Agent" = "laG-Network-updater" }
                if ((Get-FileHash "$dest.download" -Algorithm SHA256).Hash.ToLower() -ne $f.sha256) {
                    Remove-Item "$dest.download" -Force
                    throw "Checksum mismatch on $($f.path)"
                }
                Move-Item "$dest.download" $dest -Force
                $changed++
            }
        }
        # remove old versions of plugins the network manages (your own extra plugins are left alone)
        foreach ($p in $old) {
            if ($new -notcontains $p) {
                $gone = Join-Path $dir ($p -replace '/', '\')
                if (Test-Path $gone) { Remove-Item $gone -Force; Say "  $($srv.Name): removed old $p" DarkGray }
            }
        }
        $new | ConvertTo-Json | Set-Content $stateFile
    }
    Set-Content "$Root\BUILD.txt" "$($m.build)" -NoNewline
    Say "Updated ($changed files)." Green
}
Update-Network

# ---- Cannon settings for the factions server (safe to run every start; only touches these two values)
$sp = "$Root\factions\spigot.yml"
if (Test-Path $sp) {
    $t = Get-Content $sp -Raw
    $n = $t -replace 'max-tnt-per-tick:\s*\d+', 'max-tnt-per-tick: 20000'
    if ($n -ne $t) { Set-Content $sp $n -NoNewline; Say "Raised TNT-per-tick limit for cannons." Green }
}
$pw = "$Root\factions\config\paper-world-defaults.yml"
if (Test-Path $pw) {
    $t = Get-Content $pw -Raw
    $n = $t -replace 'optimize-explosions:\s*false', 'optimize-explosions: true'
    if ($n -ne $t) { Set-Content $pw $n -NoNewline; Say "Turned on explosion optimizer." Green }
}

$lc = "$Root\factions\plugins\laGCannons\config.yml"
if (Test-Path $lc) {
    $t = Get-Content $lc -Raw
    $n = $t -replace 'radius-max:\s*25\b', 'radius-max: 64'
    if ($n -ne $t) { Set-Content $lc $n -NoNewline; Say "Raised /tntfill radius to 64." Green }
}

# ---- First run: replace the placeholder secret so only YOUR proxy can talk to the servers
$secretFile = "$Root\proxy\forwarding.secret"
if ((Get-Content $secretFile -Raw).Trim() -eq "LAG_CHANGE_ME_ON_FIRST_START") {
    $bytes = New-Object byte[] 16
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $secret = ($bytes | ForEach-Object { $_.ToString("x2") }) -join ""
    Set-Content $secretFile $secret -NoNewline
    foreach ($s in "hub", "factions", "smp") {
        $pg = "$Root\$s\config\paper-global.yml"
        (Get-Content $pg -Raw) -replace "LAG_CHANGE_ME_ON_FIRST_START", $secret | Set-Content $pg -NoNewline
    }
    Say "Network secured (first run)." Green
}

# ---- Start everything, each in its own window
$flags = "-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20"
foreach ($s in "hub", "factions", "smp") {
    $mem = $net.servers.$s.memory
    Say "Starting $s ($mem)..."
    Start-Process cmd -WorkingDirectory "$Root\$s" -ArgumentList "/k title laG $s && `"$java`" -Xms$mem -Xmx$mem $flags -jar paper.jar --nogui"
}
Start-Sleep -Seconds 3
Say "Starting proxy..."
Start-Process cmd -WorkingDirectory "$Root\proxy" -ArgumentList "/k title laG proxy && `"$java`" -Xms$($net.proxy_memory) -Xmx$($net.proxy_memory) -jar velocity.jar"

Write-Host ""
Say "All 4 windows are starting. When they say 'Done', join:" Green
Say "   You:      localhost" White
Say "   Friends:  run TUNNEL.bat and send them the address it gives you" White
Say "To stop: type 'stop' in each server window (and 'end' in the proxy)." DarkGray
Write-Host ""
