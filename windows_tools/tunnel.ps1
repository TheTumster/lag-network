# Friend tunnel (playit.gg): lets friends join without port forwarding. Finds or downloads the playit agent, then runs it.
$ErrorActionPreference = "Continue"
$Root = Split-Path -Parent $PSScriptRoot
function Say($msg, $color = "Cyan") { Write-Host "  $msg" -ForegroundColor $color }
Write-Host ""
Write-Host "  RelapsedMC - friend tunnel" -ForegroundColor Magenta
Write-Host ""

function Find-Playit {
    $local = "$Root\tools\playit.exe"
    if (Test-Path $local) { return $local }
    $cmd = Get-Command playit -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    foreach ($p in @("$env:ProgramFiles\playit_gg\bin\playit.exe", "${env:ProgramFiles(x86)}\playit_gg\bin\playit.exe",
                     "$env:LOCALAPPDATA\Programs\playit_gg\bin\playit.exe", "$env:ProgramFiles\playit\playit.exe")) {
        if ($p -and (Test-Path $p)) { return $p }
    }
    return $null
}

$exe = Find-Playit
if (-not $exe) {
    Say "Downloading playit.gg (one time)..."
    $base = "https://github.com/playit-cloud/playit-agent/releases/latest/download/"
    # playit has renamed its Windows download a few times: try each name
    foreach ($name in @("playit-windows-x86_64-signed.exe", "playit-windows-x86-signed.exe", "playit-windows-x86_64.exe", "playit-windows-x86.exe")) {
        try {
            Invoke-WebRequest ($base + $name) -OutFile "$Root\tools\playit.exe" -UseBasicParsing -ErrorAction Stop
            if ((Get-Item "$Root\tools\playit.exe").Length -gt 1MB) { $exe = "$Root\tools\playit.exe"; break }
        } catch { }
        Remove-Item "$Root\tools\playit.exe" -Force -ErrorAction SilentlyContinue
    }
}
if (-not $exe -and (Get-Command winget -ErrorAction SilentlyContinue)) {
    Say "Installing playit.gg with winget..."
    winget install --id DevelopedMethods.playit -e --accept-package-agreements --accept-source-agreements
    $exe = Find-Playit
}
if (-not $exe) {
    Say "Couldn't get playit automatically." Yellow
    Say "Download it from https://playit.gg/download, install it, then run TUNNEL.bat again." White
    Start-Process "https://playit.gg/download"
    exit 1
}

Say "First time: open the link playit shows, sign in, and add a 'Minecraft Java' tunnel to port 25565." White
Say "It gives you an address like  something.joinmc.link  - that's what your friends type in." White
Say "Keep this window open while they play." DarkGray
Write-Host ""
& $exe
