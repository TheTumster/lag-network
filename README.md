# laG Network

Velocity proxy + Hub + Factions + SMP on Paper 1.21.11, with two custom plugins:

- **laG Core** (`plugins/lag-core`): hub (selector compass, double jump, protection), `/hub`, scoreboard, proxy messaging
- **laG Cannons** (`plugins/lag-cannons`): predictable TNT (no random ignition kick), multi-hit obsidian with water protection, `/tntfill`

Everything else is pulled automatically (see `tools/plugins.json`). FactionsUUID is built from its GPL source;
buy it on SpigotMC ($15) once the server makes money, as its author asks.

CI (`.github/workflows/build.yml`) builds the plugins, downloads Paper/Velocity/plugins, boots the whole network to
make sure nothing errors, and publishes `laG-Network.zip`. Extract it on Windows and run `START.bat`.

Network name, ports and memory: `network/network.json`.
