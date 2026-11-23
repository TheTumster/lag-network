# RelapsedMC on a Linux VPS

Same network, same folder layout as `C:\laG-Network` on the PC. `lag` replaces START.bat / STOP.bat.

## Moving from the PC (Nov 26–27)

1. **On the PC:** run STOP.bat (every world saves). Zip `C:\laG-Network` (skip `runtime\` and any `backups\`).
2. **Copy it up:** `scp laG-Network.zip root@<vps-ip>:/tmp/` (or WinSCP).
3. **On the VPS, as root:**
   ```
   mkdir -p /opt/relapsedmc && cd /opt/relapsedmc && unzip -q /tmp/laG-Network.zip
   # if the zip has a top folder: mv laG-Network/* . && rmdir laG-Network
   curl -fsSL https://github.com/TheTumster/lag-network/releases/latest/download/setup-vps.sh -o setup-vps.sh
   bash setup-vps.sh
   systemctl start relapsedmc
   ```
4. Point DNS `play.RelapsedMC.com` (A record) at the VPS IP. Join, check every realm.
5. Off-machine backups: `sudo -u relapsed rclone config` (Backblaze B2 / Cloudflare R2 / any S3), then set `REMOTE=` in `/opt/relapsedmc/backup.conf` and run `sudo -u relapsed lag backup` once to test.

Brand-new network with no worlds instead: `bash setup-vps.sh --fresh`.

## Day to day

| | |
|---|---|
| `systemctl start relapsedmc` / `stop` / `restart` | whole network (restart = pull the latest build) |
| `sudo -u relapsed lag status` | what's running + build number |
| `sudo -u relapsed lag console factions` | live console (leave with **Ctrl+B, then D** — don't type `stop` unless you mean it) |
| `sudo -u relapsed lag cmd hub "lp user X parent set owner"` | one command without opening the console |
| `sudo -u relapsed lag backup` | backup now (also runs daily at 04:17) |
| `sudo -u relapsed lag update` | download the latest build without restarting |

Updates work exactly like the PC: push to `main` → CI builds `latest` → restart pulls changed jars/configs (sha256-checked). Worlds and configs we don't manage are never touched.

## What's different from the PC

- Only **25565** (and SSH) is open. Backend servers are forced to `server-ip=127.0.0.1`, so nobody can skip the proxy.
- No RCON: `lag stop` types `stop` into each console through tmux and waits for the save.
- Backups: `backups/<date>/<server>.tar.zst`, worlds + plugin data (no jars/logs/cache), taken with `save-off` + `save-all flush` so they're consistent. 7 days kept locally, 30 off-machine.
- The service stops the network cleanly on shutdown/reboot.
