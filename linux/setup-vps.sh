#!/usr/bin/env bash
# One-time VPS setup for RelapsedMC (Ubuntu 22.04/24.04 or Debian 12). Run as root:
#   curl -fsSL https://github.com/TheTumster/lag-network/releases/latest/download/setup-vps.sh -o setup-vps.sh && bash setup-vps.sh
# What it does:
#   - installs tmux, zstd, rclone, python3, ufw
#   - firewall: SSH + 25565 only (the backend servers stay on localhost)
#   - creates the "relapsed" user and /opt/relapsedmc (the network folder, same layout as C:\laG-Network)
#   - fresh install: downloads the latest network package. Migrating from the PC: copy the PC folder in instead (see linux/README.md)
#   - systemd service: starts on boot, saves + stops cleanly on shutdown/reboot
#   - daily backup at 04:17 (server time) + a swapfile if the box has none
set -euo pipefail
ROOT=/opt/relapsedmc
REL=https://github.com/TheTumster/lag-network/releases/latest/download
[ "$(id -u)" -eq 0 ] || { echo "Run as root (sudo bash setup-vps.sh)"; exit 1; }

echo "== Packages"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq tmux zstd rclone python3 ufw curl unzip ca-certificates >/dev/null

echo "== Firewall (SSH + Minecraft only)"
ufw allow OpenSSH >/dev/null
ufw allow 25565/tcp >/dev/null
ufw --force enable >/dev/null
ufw status | sed 's/^/   /'

echo "== User + folder"
id relapsed >/dev/null 2>&1 || useradd -m -s /bin/bash relapsed
mkdir -p "$ROOT"
if [ ! -f "$ROOT/network.json" ]; then
  if [ "${1:-}" = "--fresh" ]; then
    echo "   fresh install: downloading the network package"
    curl -fsSL "$REL/laG-Network.zip" -o /tmp/lag.zip
    rm -rf /tmp/lag && unzip -q /tmp/lag.zip -d /tmp/lag
    src=$(dirname "$(find /tmp/lag -maxdepth 2 -name network.json | head -1)")
    cp -a "$src"/. "$ROOT"/
    rm -rf /tmp/lag /tmp/lag.zip
  else
    echo "   $ROOT is empty. Copy the PC's C:\\laG-Network contents into it (linux/README.md), then run this again."
    echo "   (or: bash setup-vps.sh --fresh  for a brand-new network with no worlds)"
    exit 0
  fi
fi
mkdir -p "$ROOT/tools"
curl -fsSL "$REL/lag.py" -o "$ROOT/tools/lag.py"
chmod +x "$ROOT/tools/lag.py"
ln -sf "$ROOT/tools/lag.py" /usr/local/bin/lag
[ -f "$ROOT/backup.conf" ] || cat > "$ROOT/backup.conf" <<'EOF'
# Local copies kept (days)
KEEP_DAYS=7
# Off-machine copy: any rclone remote (set one up with: sudo -u relapsed rclone config). Example: b2:relapsedmc-backups
REMOTE=
REMOTE_KEEP_DAYS=30
EOF
# PC-only bits aren't needed here
rm -f "$ROOT"/*.bat "$ROOT"/*/run-laG.cmd "$ROOT/.rcon-secret"
rm -rf "$ROOT/runtime"   # the PC's Windows Java; lag.py fetches the Linux one
chown -R relapsed:relapsed "$ROOT"

echo "== Swap"
if ! swapon --show | grep -q .; then
  fallocate -l 4G /swapfile && chmod 600 /swapfile && mkswap /swapfile >/dev/null && swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
  echo "   4 GB swapfile added"
fi

echo "== Service (start on boot, clean stop on shutdown)"
cat > /etc/systemd/system/relapsedmc.service <<EOF
[Unit]
Description=RelapsedMC network
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
RemainAfterExit=yes
User=relapsed
WorkingDirectory=$ROOT
ExecStart=/usr/bin/python3 $ROOT/tools/lag.py start
ExecStop=/usr/bin/python3 $ROOT/tools/lag.py stop
TimeoutStartSec=900
TimeoutStopSec=300

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable relapsedmc >/dev/null

echo "== Daily backup (04:17)"
echo "17 4 * * * relapsed /usr/bin/python3 $ROOT/tools/lag.py backup >> $ROOT/backups.log 2>&1" > /etc/cron.d/relapsedmc-backup

echo
echo "Done. Next:"
echo "   systemctl start relapsedmc          start the network"
echo "   sudo -u relapsed lag status         what's running"
echo "   sudo -u relapsed lag console hub    a server console (Ctrl+B then D to leave)"
echo "   sudo -u relapsed lag backup         backup now (set REMOTE in $ROOT/backup.conf for off-machine)"
