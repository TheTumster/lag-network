# laG Network launcher: makes sure Java is there, secures the network on first run, then starts all 4 servers.
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
$net = Get-Content "$Root\network.json" -Raw | ConvertFrom-Json

function Say($msg, $color = "Cyan") { Write-Host "  $msg" -ForegroundColor $color }
Write-Host ""
Write-Host "  RelapsedMC" -ForegroundColor Magenta
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
                if ($f.path -eq "tools/start.ps1") { $script:selfUpdated = $true }
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
# ---- New servers in the network (e.g. skyblock, cannons): install their folders, register them with the proxy
function Install-NewServers {
    try {
        $netNew = Invoke-RestMethod "https://github.com/TheTumster/lag-network/releases/latest/download/network.json" -TimeoutSec 15
    } catch { return }
    $missing = @($netNew.servers.PSObject.Properties.Name | Where-Object { -not (Test-Path "$Root\$_\server.properties") })
    if ($missing.Count -gt 0) {
        Say "New servers: $($missing -join ', ') - downloading the network package (one time)..." Yellow
        $zip = "$Root\_new-servers.zip"; $tmp = "$Root\_new-servers"
        Invoke-WebRequest "https://github.com/TheTumster/lag-network/releases/latest/download/laG-Network.zip" -OutFile $zip -UseBasicParsing
        if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
        Expand-Archive $zip $tmp -Force
        foreach ($s in $missing) {
            $src = Get-ChildItem $tmp -Directory | ForEach-Object { Join-Path $_.FullName $s } | Where-Object { Test-Path $_ } | Select-Object -First 1
            if ($src) {
                New-Item -ItemType Directory -Force -Path "$Root\$s" | Out-Null
                Copy-Item (Join-Path $src "*") "$Root\$s" -Recurse -Force
                Say "  added $s" Green
            }
        }
        Remove-Item $zip -Force; Remove-Item $tmp -Recurse -Force
    }
    $netNew | ConvertTo-Json -Depth 6 | Set-Content "$Root\network.json"
    $script:net = $netNew
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    $vt = "$Root\proxy\velocity.toml"
    if (Test-Path $vt) {
        $t = [IO.File]::ReadAllText($vt, $utf8)
        $n = $t
        foreach ($p in $netNew.servers.PSObject.Properties) {
            if ($n -notmatch "(?m)^$($p.Name)\s*=") {
                $n = [regex]::Replace($n, '(?m)^\[servers\][ \t]*\r?$', "[servers]`n$($p.Name) = `"127.0.0.1:$($p.Value.port)`"")
            }
        }
        if ($n -ne $t) { [IO.File]::WriteAllText($vt, $n, $utf8); Say "Proxy now knows every server." Green }
    }
    $secret = (Get-Content "$Root\proxy\forwarding.secret" -Raw).Trim()
    if ($secret -ne "LAG_CHANGE_ME_ON_FIRST_START") {
        foreach ($s in $netNew.servers.PSObject.Properties.Name) {
            $pg = "$Root\$s\config\paper-global.yml"
            if ((Test-Path $pg) -and ((Get-Content $pg -Raw) -match "LAG_CHANGE_ME_ON_FIRST_START")) {
                (Get-Content $pg -Raw) -replace "LAG_CHANGE_ME_ON_FIRST_START", $secret | Set-Content $pg -NoNewline
            }
        }
    }
}
$script:selfUpdated = $false
Update-Network
if ($script:selfUpdated -and -not $env:LAG_RELAUNCHED) {
    # this launcher itself was updated: run the new version right away
    $env:LAG_RELAUNCHED = "1"
    & powershell -NoProfile -ExecutionPolicy Bypass -File "$Root\tools\start.ps1"
    exit $LASTEXITCODE
}
Install-NewServers

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
    $n = $t -replace 'optimize-explosions:\s*false', 'optimize-explosions: true' `
            -replace 'prevent-tnt-from-moving-in-water:\s*false', 'prevent-tnt-from-moving-in-water: true'
    if ($n -ne $t) { Set-Content $pw $n -NoNewline; Say "Applied cannon settings (explosion optimizer, water doesn't push TNT)." Green }
}

# ---- Cannons send a flood of client packets: raise ViaVersion's (proxy) and Paper's (factions) packet limits
foreach ($pair in @(@("$Root\proxy\plugins\viaversion\config.yml", '(?m)^(packet-limiter:\r?\n  enabled:) true', '$1 false'),
                    @("$Root\factions\config\paper-global.yml", '(?m)^(    max-packet-rate:) 500\.0\b', '$1 2000.0'))) {
    if (Test-Path $pair[0]) {
        $t = Get-Content $pair[0] -Raw
        $n = [regex]::Replace($t, $pair[1], $pair[2])
        if ($n -ne $t) { Set-Content $pair[0] $n -NoNewline; Say "Raised packet limit (cannons): $(Split-Path $pair[0] -Leaf)" Green }
    }
}

# ---- Rename: laG Network -> RelapsedMC (player-facing names only; one time, safe to re-run). UTF-8 in and out.
$utf8 = New-Object System.Text.UTF8Encoding($false)
$dot = [string][char]0x2022
$renamed = $false
foreach ($f in @("$Root\hub\plugins\laGCore\config.yml", "$Root\factions\plugins\laGCore\config.yml", "$Root\smp\plugins\laGCore\config.yml",
                 "$Root\hub\server.properties", "$Root\factions\server.properties", "$Root\smp\server.properties", "$Root\proxy\velocity.toml")) {
    if (Test-Path $f) {
        $t = [IO.File]::ReadAllText($f, $utf8)
        if ($t -match 'laG Network') {
            $n = $t -replace 'laG cannon physics', '1.8 cannon physics'
            if ($f -like '*velocity.toml') {
                $n = [regex]::Replace($n, '(?m)^motd\s*=.*$', ('motd = "<gradient:#38E0FF:#B040FF><bold>RelapsedMC</bold></gradient>\n<gray>Factions ' + $dot + ' SMP ' + $dot + ' 1.8 Cannons"'))
            }
            $n = $n -replace 'laG Network', 'RelapsedMC'
            [IO.File]::WriteAllText($f, $n, $utf8); $renamed = $true
        }
    }
}
if ($renamed) { Say "Network is now RelapsedMC." Green }

# ---- Server-list MOTD (proxy): centered wordmark + current map. Re-applied until it has the Relapse line.
$vt = "$Root\proxy\velocity.toml"
if (Test-Path $vt) {
    $t = [IO.File]::ReadAllText($vt, $utf8)
    if ($t -notmatch 'RELAPSE #1') {
        $motd = 'motd = ' + '"                     <gradient:#38E0FF:#B040FF><bold>RELAPSEDMC</bold></gradient> <dark_gray>[<gray>1.21</gray>]</dark_gray>\n    <#FF4D6D><bold>RELAPSE #1</bold></#FF4D6D> <gray>launching December <dark_gray>' + $dot + '</dark_gray> <#7CFF6B>Skyblock S1"'
        $n = [regex]::Replace($t, '(?m)^motd\s*=.*$', { param($m) $motd })
        if ($n -ne $t) { [IO.File]::WriteAllText($vt, $n, $utf8); Say "Updated the server-list MOTD." Green }
    }
}

# ---- Sakura (factions jar): 1.8 cannon mechanics, no entity merging, cannon lag options (all parity-tested)
$sw = "$Root\factions\config\sakura-world-defaults.yml"
if (-not (Test-Path $sw)) {
    New-Item -ItemType Directory -Force (Split-Path $sw) | Out-Null
    $sakura = "_version: 12`ncannons:`n  merge-level: none`n  explosion:`n    optimise-protected-regions: true`n    avoid-redundant-block-searches: true`n    reuse-block-cache-across-explosions: true`n  mechanics:`n    mechanics-target:`n      mechanic-version: legacy`n      server-type: paper`ntechnical:`n  redstone:`n    redstone-cache: true`n"
    Set-Content $sw $sakura -NoNewline
    Say "Set up Sakura cannon settings (1.8 mechanics)." Green
} else {
    $t = Get-Content $sw -Raw
    $n = $t -replace 'merge-level:\s*\w+', 'merge-level: none' `
            -replace 'mechanic-version:\s*\S+', 'mechanic-version: legacy' `
            -replace 'optimise-protected-regions:\s*false', 'optimise-protected-regions: true' `
            -replace 'avoid-redundant-block-searches:\s*false', 'avoid-redundant-block-searches: true' `
            -replace 'reuse-block-cache-across-explosions:\s*false', 'reuse-block-cache-across-explosions: true' `
            -replace 'redstone-cache:\s*false', 'redstone-cache: true'
    if ($n -ne $t) { Set-Content $sw $n -NoNewline; Say "Applied Sakura cannon settings (1.8 mechanics)." Green }
}

# ---- FactionsUUID: turn on faction TNT banks (/f tnt, /f tntfill) and allow a bigger fill radius
$fc = @("$Root\factions\plugins\FactionsUUID\config\main.conf", "$Root\factions\plugins\Factions\config\main.conf") | Where-Object { Test-Path $_ } | Select-Object -First 1
if ($fc) {
    $t = Get-Content $fc -Raw
    $n = [regex]::Replace($t, '(?s)(\btnt\s*\{[^{}]*?)\benable\s*=\s*false', '${1}enable=true')
    $n = [regex]::Replace($n, '(?s)(\btnt\s*\{[^{}]*?)\bmaxRadius\s*=\s*5\b', '${1}maxRadius=48')
    if ($n -ne $t) { Set-Content $fc $n -NoNewline; Say "Turned on faction TNT banks (/f tnt, /f tntfill)." Green }
}

# ---- Relapse ruleset: 8-man factions, no allies/truces, everyone starts (and caps) at 200 power
if ($fc) {
    $t = Get-Content $fc -Raw
    $n = [regex]::Replace($t, '\bfactionMemberLimit\s*=\s*\d+', 'factionMemberLimit=8')
    $n = [regex]::Replace($n, '(?s)\bmaxRelations\s*\{[^{}]*\}', {
        param($m)
        $b = [regex]::Replace($m.Value, '\benabled\s*=\s*\w+', 'enabled=true')
        $b = [regex]::Replace($b, '\bally\s*=\s*-?\d+', 'ally=0')
        [regex]::Replace($b, '\btruce\s*=\s*-?\d+', 'truce=0')
    })
    $n = [regex]::Replace($n, '(?s)\bpower\s*\{[^{}]*?playerStarting[^{}]*\}', {
        param($m)
        $b = [regex]::Replace($m.Value, '\bplayerStarting\s*=\s*-?[\d.]+', 'playerStarting=200.0')
        [regex]::Replace($b, '\bplayerMax\s*=\s*-?[\d.]+', 'playerMax=200.0')
    })
    if ($n -ne $t) { Set-Content $fc $n -NoNewline; Say "Applied Relapse rules (8-man, no allies, 200 power)." Green }
}

# ---- Cannon lag: CoreProtect stops logging every dispenser shot / hopper move on factions (raids are the game)
$cpc = "$Root\factions\plugins\CoreProtect\config.yml"
if (Test-Path $cpc) {
    $t = Get-Content $cpc -Raw
    $n = $t -replace '(?m)^item-transactions:\s*true', 'item-transactions: false' `
            -replace '(?m)^hopper-transactions:\s*true', 'hopper-transactions: false'
    if ($n -ne $t) { Set-Content $cpc $n -NoNewline; Say "CoreProtect: stopped logging dispenser/hopper items (cannon lag)." Green }
}

# ---- /fps menu (everyone can hide TNT/sand/explosions on their own screen): RelapsedMC look
# Sakura wraps long messages onto indented continuation lines, so each pattern replaces the whole value.
foreach ($srv in @("factions", "cannons")) {
    $sg = "$Root\$srv\config\sakura-global.yml"
    if (Test-Path $sg) {
        $t = [IO.File]::ReadAllText($sg, $utf8)
        $brand = '<gradient:#38E0FF:#B040FF>[RelapsedMC]</gradient> '
        $n = [regex]::Replace($t, '(?m)^(  fps-setting-change:)[^\r\n]*(?:\r?\n    [^\r\n]*)*', ('${1} ''' + $brand + '<gray><state> <white><name></white> for you. <dark_gray>(/fps)'''))
        $n = [regex]::Replace($n, '(?m)^(  durable-block-interaction:)[^\r\n]*(?:\r?\n    [^\r\n]*)*', ('${1} ''' + $brand + '<white>This block has <gray><remaining></gray> of <gray><durability></gray> hits left'''))
        $n = [regex]::Replace($n, '(?m)^(\s+material:)\s*PINK_STAINED_GLASS_PANE', '${1} CYAN_STAINED_GLASS_PANE')
        if ($n -ne $t) { [IO.File]::WriteAllText($sg, $n, $utf8); Say "Styled the /fps menu ($srv)." Green }
    }
}

# ---- First run: replace the placeholder secret so only YOUR proxy can talk to the servers
$secretFile = "$Root\proxy\forwarding.secret"
if ((Get-Content $secretFile -Raw).Trim() -eq "LAG_CHANGE_ME_ON_FIRST_START") {
    $bytes = New-Object byte[] 16
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $secret = ($bytes | ForEach-Object { $_.ToString("x2") }) -join ""
    Set-Content $secretFile $secret -NoNewline
    foreach ($s in $net.servers.PSObject.Properties.Name) {
        $pg = "$Root\$s\config\paper-global.yml"
        (Get-Content $pg -Raw) -replace "LAG_CHANGE_ME_ON_FIRST_START", $secret | Set-Content $pg -NoNewline
    }
    Say "Network secured (first run)." Green
}

# ---- Already running? (a second copy can't use the same ports)
function Get-LagJava {
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.ExecutablePath -and $_.ExecutablePath.StartsWith("$Root\runtime", [StringComparison]::OrdinalIgnoreCase) }
}
$old = @(Get-LagJava)
if ($old.Count -gt 0) {
    Write-Host ""
    Say "The network is already running ($($old.Count) servers from an earlier start)." Yellow
    $ans = Read-Host "  Stop it cleanly (saves worlds) and restart? (Y/n)"
    if ($ans -match '^[nN]') { exit 0 }
    & powershell -NoProfile -ExecutionPolicy Bypass -File "$PSScriptRoot\stop.ps1"
    if (@(Get-LagJava).Count -gt 0) { Say "Some servers are still running - not starting a second copy." Yellow; exit 1 }
}

# ---- RCON on every server (local password, never shipped) so STOP.bat can stop them cleanly
function Enable-Rcon {
    $secretFile = "$Root\.rcon-secret"
    if (-not (Test-Path $secretFile)) {
        $bytes = New-Object byte[] 24
        [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
        $pw = [Convert]::ToBase64String($bytes) -replace '[^A-Za-z0-9]', ''
        Set-Content $secretFile $pw -NoNewline
    }
    $pw = (Get-Content $secretFile -Raw).Trim()
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    foreach ($s in $net.servers.PSObject.Properties.Name) {
        $sp = "$Root\$s\server.properties"
        if (-not (Test-Path $sp)) { continue }
        $want = [ordered]@{ "enable-rcon" = "true"; "rcon.port" = "$([int]$net.servers.$s.port + 10000)"; "rcon.password" = $pw; "broadcast-rcon-to-ops" = "false" }
        $lines = [Collections.Generic.List[string]]([IO.File]::ReadAllLines($sp, $utf8))
        foreach ($k in $want.Keys) {
            $i = -1
            for ($j = 0; $j -lt $lines.Count; $j++) { if ($lines[$j].StartsWith("$k=")) { $i = $j; break } }
            if ($i -ge 0) { $lines[$i] = "$k=$($want[$k])" } else { $lines.Add("$k=$($want[$k])") }
        }
        [IO.File]::WriteAllLines($sp, $lines, $utf8)
    }
}
Enable-Rcon

# ---- Start everything: one Windows Terminal window with a tab per server (separate windows if Terminal isn't installed)
$flags = "-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20"
$runs = [ordered]@{}
foreach ($s in $net.servers.PSObject.Properties.Name) {
    $mem = $net.servers.$s.memory
    $runs[$s] = "`"$java`" -Xms$mem -Xmx$mem $flags -jar paper.jar --nogui"
}
$runs["proxy"] = "`"$java`" -Xms$($net.proxy_memory) -Xmx$($net.proxy_memory) -jar velocity.jar"
foreach ($s in $runs.Keys) {
    # tiny per-server launcher so the tab/window command stays simple (no quoting headaches)
    Set-Content "$Root\$s\run-laG.cmd" "@echo off`r`ntitle laG $s`r`n$($runs[$s])" -Encoding ASCII
}
$tabs = @()
foreach ($s in $runs.Keys) { $tabs += "new-tab --title `"laG $s`" --suppressApplicationTitle -d `"$Root\$s`" cmd /k run-laG.cmd" }
$tabbed = $false
try {
    # Start-Process finds Windows Terminal through its app alias even when it isn't on PATH
    Start-Process wt.exe -ArgumentList ("-w laG " + ($tabs -join " ; ")) -ErrorAction Stop
    $tabbed = $true
    Say "Started $(($net.servers.PSObject.Properties.Name) -join ', ') and proxy in one window (tabs)." Green
} catch { }
if (-not $tabbed) {
    foreach ($s in $runs.Keys) {
        Say "Starting $s..."
        Start-Process cmd -WorkingDirectory "$Root\$s" -ArgumentList "/k run-laG.cmd"
        if ($s -eq "smp") { Start-Sleep -Seconds 3 }
    }
    Say "Tip: install Windows Terminal (Microsoft Store) to get everything in one window with tabs." DarkGray
}

Write-Host ""
Say "All servers are starting. When each tab says 'Done', join:" Green
Say "   You:      localhost" White
Say "   Friends:  run TUNNEL.bat and send them the address it gives you" White
Say "To stop everything: double-click STOP.bat (saves every world first)." DarkGray
Write-Host ""
