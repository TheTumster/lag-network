# laG Network: stop everything cleanly. Sends "stop" to every server over RCON (each one saves its worlds),
# waits for them to exit, then stops the proxy and closes the server tabs.
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
$net = Get-Content "$Root\network.json" -Raw | ConvertFrom-Json

function Say($msg, $color = "Cyan") { Write-Host "  $msg" -ForegroundColor $color }
Write-Host ""
Write-Host "  RelapsedMC - stopping" -ForegroundColor Magenta
Write-Host ""

$secretFile = "$Root\.rcon-secret"
$secret = if (Test-Path $secretFile) { (Get-Content $secretFile -Raw).Trim() } else { "" }

# ---- minimal RCON client (Source RCON protocol: little-endian length, id, type, body, two zero bytes)
function Send-Packet($stream, [int]$id, [int]$type, [string]$body) {
    $b = [Text.Encoding]::UTF8.GetBytes($body)
    $len = 10 + $b.Length
    $buf = New-Object byte[] (4 + $len)
    [BitConverter]::GetBytes([int]$len).CopyTo($buf, 0)
    [BitConverter]::GetBytes($id).CopyTo($buf, 4)
    [BitConverter]::GetBytes($type).CopyTo($buf, 8)
    $b.CopyTo($buf, 12)
    $stream.Write($buf, 0, $buf.Length)
    $stream.Flush()
}
function Read-Exact($stream, [int]$n) {
    $buf = New-Object byte[] $n
    $got = 0
    while ($got -lt $n) {
        $r = $stream.Read($buf, $got, $n - $got)
        if ($r -le 0) { throw "connection closed" }
        $got += $r
    }
    return ,$buf
}
function Read-Packet($stream) {
    $len = [BitConverter]::ToInt32((Read-Exact $stream 4), 0)
    $rest = Read-Exact $stream $len
    return [BitConverter]::ToInt32($rest, 0)  # request id (-1 = bad password)
}
function Send-Rcon([int]$port, [string]$command) {
    $client = New-Object Net.Sockets.TcpClient
    try {
        $client.ReceiveTimeout = 5000
        $client.SendTimeout = 5000
        $client.Connect("127.0.0.1", $port)
        $s = $client.GetStream()
        Send-Packet $s 1 3 $secret
        if ((Read-Packet $s) -eq -1) { throw "wrong RCON password" }
        Send-Packet $s 2 2 $command
        try { [void](Read-Packet $s) } catch { }  # 'stop' may close the connection before answering
    } finally {
        $client.Close()
    }
}

function Get-Servers {
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -match '-jar\s+"?paper\.jar' -and $_.CommandLine -match 'nogui' }
}
function Get-Proxy {
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -match '-jar\s+"?velocity\.jar' }
}

$running = @(Get-Servers)
if ($running.Count -eq 0 -and @(Get-Proxy).Count -eq 0) {
    Say "Nothing is running." DarkGray
    exit 0
}

# ---- 1. ask every server to stop (they save their worlds on the way out)
$failed = @()
foreach ($s in $net.servers.PSObject.Properties.Name) {
    $port = [int]$net.servers.$s.port + 10000
    if (-not $secret) { $failed += $s; continue }
    try {
        Send-Rcon $port "stop"
        Say "$s  -> stopping" White
    } catch {
        $failed += $s
        Say "$s  -> couldn't reach it ($($_.Exception.Message))" DarkGray
    }
}

# ---- 2. wait for them to finish saving
$deadline = (Get-Date).AddSeconds(90)
while (@(Get-Servers).Count -gt 0 -and (Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 1
}
$left = @(Get-Servers)
if ($left.Count -gt 0) {
    Write-Host ""
    Say "$($left.Count) server(s) are still running." Yellow
    if ($failed.Count -gt 0) {
        Say "Couldn't send 'stop' to: $($failed -join ', ') (started before STOP.bat existed? next START fixes that)." DarkGray
    }
    $ans = Read-Host "  Force-stop them? Unsaved progress since the last autosave is lost. (y/N)"
    if ($ans -match '^[yY]') {
        $left | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
        Say "Forced." Yellow
    } else {
        Say "Left them running. Type 'stop' in their tabs, then run STOP.bat again." White
        exit 1
    }
}
Say "All servers saved and stopped." Green

# ---- 3. proxy (no worlds to save) and the leftover server tabs
Get-Proxy | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Start-Sleep -Milliseconds 500
Get-CimInstance Win32_Process -Filter "Name='cmd.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -match 'run-laG\.cmd' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Say "Proxy stopped. Network is off." Green
Write-Host ""
