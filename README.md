# tonic
this is a alpha. a minimum viable product. expect alotta bugs and missing features.
dm eg.o on discord for things

mc3ds 

the two plugins

- **tonic3ds** — luma 3gx plugin, 
- **tonicjava** — a paper plugin on the server. connections become players thru floodgate



## download

two files, from the [latest release](https://github.com/yorkedegg/tonic/releases/latest):

- [tonic.3gx](https://github.com/yorkedegg/tonic/releases/latest/download/tonic.3gx) — goes on the 3ds, at `sd:/luma/plugins/00040000001B8700/tonic.3gx`
- [TonicJava.jar](https://github.com/yorkedegg/tonic/releases/latest/download/TonicJava.jar) — goes on the server, in `plugins/`

details on both below.

## what you need

on the console side
a new nintendo 3ds with luma.
a usa copy of mc3ds with the update
a brain

on the server side
- paper 1.21.x
- floodgate 
- viaversion + viabackwards  
- a tcp port you can open for the tunnel (default 27953)
- optional: a mineskin api key, 

## setting it up

**on the 3ds**
1. put [tonic.3gx](https://github.com/yorkedegg/tonic/releases/latest/download/tonic.3gx) at `sd:/luma/plugins/00040000001B8700/tonic.3gx` (it's also in `plugin/tonic/` if you build it yourself)
2. make a file `sd:/tonic.cfg` with one line in it: `1.2.3.4:27953` — your server's public ip and the tunnel port. it has to be an ip, not a hostname (no dns yet). no file = tonic does nothing and says so in `sd:/tonic.log`
3. connect to wifi, launch the game, hit play → the join list shows a world called "Tonic". join it like you'd join a friend

**on the server**
1. drop [TonicJava.jar](https://github.com/yorkedegg/tonic/releases/latest/download/TonicJava.jar) in `plugins/` (or build it: `gradle paperJar`, needs java 21 and gradle fetches that itself, jar lands in `build/libs/`)
2. start the server once so `plugins/TonicJava/config.yml` shows up, set `tunnel-port` to the port you opened
3. floodgate has to be installed and running. tonicjava reads `plugins/floodgate/key.pem` to log the 3ds players in

**on azahar (the emulator), instead of a real 3ds**

the same `tonic.3gx` works in stock azahar — no custom emulator build, no shim. azahar has luma's plugin loader built in, it's just off:
1. install the **v9.12.0 update cia** in azahar as well (file → install cia). the base game on its own is v0.1.0, which is a different binary — tonic will refuse to touch it and tell you why in `sdmc/tonic.log`
2. with azahar closed, in `~/Library/Application Support/Azahar/config/qt-config.ini` (or the equivalent on your os) set `plugin_loader=true` **and** `plugin_loader\default=false` — if the `\default` line stays true, azahar ignores the value and keeps the loader off
3. put `tonic.3gx` at `sdmc/luma/plugins/00040000001B8700/tonic.3gx` and `tonic.cfg` at the sdmc root, same as the console. the cfg isn't optional
4. boot the game and join "Tonic". `sdmc/tonic.log` tells you what the plugin's doing — no ftp needed

**skins (optional)**

the 3ds only sends the *name* of its built in skin. the actual textures live on the console, so you dump them once:
1. with godmode9, copy `romfs/resourcepacks/skins/skinpacks/` out of your game to the sd card
2. `python3 tools/3dst2png.py <that folder> plugins/TonicJava/skins` — converts all 600ish skins to pngs and writes an index. it also rebuilds the ones that use their own 3ds-only model so they fit java's model
3. put a mineskin api key in config.yml (`mineskin-key`). java clients only accept skins signed by mojang, and mineskin does the signing. each skin is signed once, the first time someone wears it, and cached forever after


## stuff that doesn't work (yet)

- only the usa v9.12.0 binary, see above
- two 3ds with the same player name can't both be on (the second one kicks the first)
- no mobs on the 3ds, only players and drops
- a few 3ds skins have extra bits on their own model (mario's nose, r2d2...). java only has the one model so those bits are lost, and the fully non-humanoid ones get transparent parts instead of garbage
- if the tunnel drops there's no reconnect, you rejoin from the join list

## notes

[FINDINGS.md](FINDINGS.md) how it got done

not affiliated with mojang, nintendo or other ocean.
