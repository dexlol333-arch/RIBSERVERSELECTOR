# RibSelector (Paper 1.21.11)

Server selector compass -> "Pick a server to play." menu -> transfers players to Rib FFA / Rib SMP.

## Build
In IntelliJ: open this folder, then Maven -> Lifecycle -> package (or run `mvn package`).
The jar appears at `target/RibSelector.jar`.

## Install (do this on BOTH servers)
1. Put `RibSelector.jar` in `plugins/` and start the server once.
2. Edit `plugins/RibSelector/config.yml` and set `this-server`:
   - FFA server: `this-server: ffa`
   - SMP server: `this-server: smp`
3. In `server.properties` on BOTH servers set: `accept-transfers=true`
4. Restart (or `/ribselector reload`).

## Commands
- `/servers` - open the menu (same as right-clicking the compass)
- `/ribserver <ffa|smp>` - join a server directly
- `/ribselector reload` / `/ribselector give [player]` - admin (permission `ribselector.admin`)
