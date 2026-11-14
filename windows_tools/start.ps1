# laG Network launcher: makes sure Java is there, secures the network on first run, then starts all 4 servers.
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
$net = Get-Content "$Root\network.json" -Raw | ConvertFrom-Json

function Say($msg, $color = "Cyan") { Write-Host "  $msg" -ForegroundColor $color }
Write-Host ""
Write-Host "  laG Network" -ForegroundColor Magenta
Write-Host ""

# ---- Java 21+ (uses a private copy in .\runtime if your PC doesn't have one)
$java = "$Root\runtime\bin\java.exe"
if (-not (Test-Path $java)) {
    $sys = Get-Command java -ErrorAction SilentlyContinue
    $ok = $false
    if ($sys) {
        $v = (& java -version 2>&1 | Select-Object -First 1) -as [string]
        if ($v -match '"(\d+)') { $ok = [int]$Matches[1] -ge 21 }
    }
    if ($ok) {
        $java = $sys.Source
    } else {
        Say "Downloading Java 21 (one time, ~45 MB)..."
        $zip = "$env:TEMP\lag-jre21.zip"
        Invoke-WebRequest "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jre/hotspot/normal/eclipse" -OutFile $zip -UseBasicParsing
        $tmp = "$env:TEMP\lag-jre21"
        if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
        Expand-Archive $zip $tmp
        Move-Item (Get-ChildItem $tmp | Select-Object -First 1).FullName "$Root\runtime"
        Remove-Item $zip -Force
        Say "Java ready." Green
    }
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
