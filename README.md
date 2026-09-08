# tonic

minecraft: new nintendo 3ds edition, playing on a real java server. over the internet. with your 3ds skin.

it's two plugins that talk to each other:

- **tonic3ds** — a luma 3gx plugin that runs inside the game on the 3ds. the game only knows how to play over local wireless, so tonic fakes that whole service and tunnels the traffic out over your normal wifi instead. from the game's point of view there's a friend hosting a world nearby. that "friend" is your server
- **tonicjava** — a paper plugin on the server. every 3ds that connects becomes a real player (through floodgate) with its own name and its own skin. other players see it walk around, break and place blocks, pick stuff up. multiple 3ds at once is fine, they see each other too

no bot accounts, no proxy in between, no pc in the middle. the 3ds talks straight to the server.

## download

two files, from the [latest release](https://github.com/yorkedegg/tonic/releases/latest):

- [tonic.3gx](https://github.com/yorkedegg/tonic/releases/latest/download/tonic.3gx) — goes on the 3ds, at `sd:/luma/plugins/00040000001B8700/tonic.3gx`
- [TonicJava.jar](https://github.com/yorkedegg/tonic/releases/latest/download/TonicJava.jar) — goes on the server, in `plugins/`

details on both below.

## what you need

on the console side
- a new 3ds / new 2ds with luma3ds and the plugin loader turned on (rosalina menu → plugin loader)
- minecraft: new nintendo 3ds edition, **usa version, fully updated (the v9.12.0 update)**. tonic patches the game's code at fixed addresses that were worked out for exactly that binary. other regions or older updates will just crash at launch. most us copies are on the last update since the eshop closed, so this is usually fine
- wifi. the 3ds has one radio and "local play" normally kills your internet. tonic stops that from happening

on the server side
- paper 1.21.x
- floodgate — this is what lets a 3ds be a real player on an online-mode server
- viaversion + viabackwards — the 3ds side talks to the server in an old protocol version
- a tcp port you can open for the tunnel (default 27953)
- optional: a mineskin api key, if you want pc players to see the 3ds skins

## setting it up

**on the 3ds**
1. put [tonic.3gx](https://github.com/yorkedegg/tonic/releases/latest/download/tonic.3gx) at `sd:/luma/plugins/00040000001B8700/tonic.3gx` (it's also in `plugin/tonic/` if you build it yourself)
2. make a file `sd:/tonic.cfg` with one line in it: `1.2.3.4:27953` — your server's ip and the tunnel port. it has to be an ip, not a hostname (no dns yet)
3. connect to wifi, launch the game, hit play → the join list shows a world called "Tonic". join it like you'd join a friend

**on the server**
1. drop [TonicJava.jar](https://github.com/yorkedegg/tonic/releases/latest/download/TonicJava.jar) in `plugins/` (or build it: `gradle paperJar`, needs java 21 and gradle fetches that itself, jar lands in `build/libs/`)
2. start the server once so `plugins/TonicJava/config.yml` shows up, set `tunnel-port` to the port you opened
3. floodgate has to be installed and running. tonicjava reads `plugins/floodgate/key.pem` to log the 3ds players in

**skins (optional)**

the 3ds only sends the *name* of its built in skin. the actual textures live on the console, so you dump them once:
1. with godmode9, copy `romfs/resourcepacks/skins/skinpacks/` out of your game to the sd card
2. `python3 tools/3dst2png.py <that folder> plugins/TonicJava/skins` — converts all 600ish skins to pngs and writes an index. it also rebuilds the ones that use their own 3ds-only model so they fit java's model
3. put a mineskin api key in config.yml (`mineskin-key`). java clients only accept skins signed by mojang, and mineskin does the signing. each skin is signed once, the first time someone wears it, and cached forever after

## how it actually works

short version. the long version is [FINDINGS.md](FINDINGS.md).

- the game talks to the 3ds's local wireless service (nwm::UDS) through about 155 inlined syscall sites. tonic3ds patches every one of them with a small veneer, works out at runtime which handle is the UDS one, and answers the UDS commands itself: a scan gets a beacon back, a connect gets a connect-ok, and packets go over a tcp tunnel instead of the radio
- it also neutralises the one ndm call that would switch the radio to local mode and drop wifi
- tonicjava speaks the game's flavour of mcpe (raknet, zlib batches, an mcpe 0.x/1.0 era dialect) to the tunnel. for every 3ds it logs a java client into the server through floodgate's handshake, and that bot's view of the world is what the 3ds sees
- the 3ds's moves, digs and places are done server side (teleport, breakNaturally, setType). a real player is subject to spawn protection and movement checks, and being puppeted from a 3ds sets all of those off, so the plugin just does it itself
- drops, other players, and other 3ds players get mirrored back to the 3ds

## stuff that doesn't work (yet)

- only the usa v9.12.0 binary, see above
- `tonic.cfg` needs an ip, no dns
- two 3ds with the same player name can't both be on (the second one kicks the first)
- no mobs on the 3ds, only players and drops
- a few 3ds skins have extra bits on their own model (mario's nose, r2d2...). java only has the one model so those bits are lost, and the fully non-humanoid ones get transparent parts instead of garbage
- if the tunnel drops there's no reconnect, you rejoin from the join list

## notes

[FINDINGS.md](FINDINGS.md) is the story of figuring all this out: the protocol, the beacon checksum, the uds hook, the crashes, the stuff that didn't work. there's also the original pc-hosted bridge from before the plugin existed (`bridge.Bridge` plus the azahar shim in `shim/`), it still builds.

not affiliated with mojang, nintendo or other ocean.
