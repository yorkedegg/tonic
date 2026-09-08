# findings

this is the story of how tonic got figured out, plus the reference stuff you'd need to work on it. it replaces a pile of lab-notebook markdown that grew over about two weeks. a lot of that notebook was *wrong* for days at a time and then corrected, and the wrong turns are kept here on purpose, because they're the actual lesson. the reference tables at the bottom are the parts that were verified against the game's own code or against captures.

## what the game actually is

minecraft: new nintendo 3ds edition is not bedrock. it's an mcpe 0.x-era derivative (internal title "Proteus"), frozen with an old raknet and its own packet id table. the "1.9.19" version label makes it sound newer than the wire format is.

- transport is raknet **protocol 8**, mtu **0x5d4** (1492), no encryption. the offline handshake is textbook: 0x05 request1 (with the magic and an mtu probe padded to 1464), 0x06 reply1, 0x07 request2, 0x08 reply2 with a trailing 00 for "no encryption". connected mode is the usual 0x84/0x88/0x8c data datagrams and 0xc0 acks
- game packets ride inside **zlib batches** (a 0xFE message, inflated to `[varint len][packet]*`)
- there is no bedrock-style login. first thing after raknet connects is a one-byte 0x40, then a tiny login packet (id 0x01) whose body is json: `{"playerName":"…","skinId":"Standard_Steve","uuid":"…"}`, about 100 bytes. no jwt, no skin pixels, no version number on the wire at all. the only version identity anywhere is the beacon's `MC` magic + a format byte
- the host answers with a login-response batch (id 0x00) that the client turns out to need — without it the client sits on "connecting" forever. (the captured real host doesn't obviously send one. never resolved, doesn't matter)
- then 0x04 handshake → playstatus(0) + resource packs info → 0x08 → resource pack stack → 0x08 → the big spawn batch → the client is in

the spawn batch a real host sends is one ~464 kb zlib message: playerlist/skin, settime, startgame, gamerules, the crafting/inventory tables (~37 kb), chunks (~60-80 kb each), addplayer, sethealth, etc.

the game is local-wireless only. it talks to the 3ds's `nwm::UDS` service over ipc, and it hosts or joins worlds only over that. game traffic rides uds **data channel 13**. a joining unit is a real uds client: node 2, host is node 1.

## the milestones, and what each one taught

### m0 — captures

two instances of a patched azahar (the citra fork) in a room, one hosting a world, one joining. hooks in azahar's `nwm_uds.cpp` logged every uds send/receive. `tools/m0_parse.py` and `tools/m0_unwrap.py` turn those logs into decoded mcpe packets (reassemble raknet fragments, inflate the batches, collapse retransmits).

this is where everything above came from: raknet 8, the mtu, zlib, the join sequence, the beacon layout, the first (wrong) reading of the chunk format, and a partial packet id map.

### m1 — a fake host on the pc

a java program that impersonates an mc3ds host over a tcp tunnel: the shim in azahar (`shim/`) redirects the uds service to it, the bridge answers scans with a beacon and connects with a connect-ok, and speaks raknet. the client spawned into a world and held a session for 200+ keepalives.

except: it only "worked" with a spawn containing **no chunks**. the notes at the time said "the client self-generates its world from the startgame seed". that was wrong — it was spawning into an empty void (player y read 32769, which is garbage). there was never any self-generated terrain. this misreading cost a lot of time later.

### the chunk saga (the m3 notebook, in order)

any spawn that contained chunks made the client leave ~2 s later with a raknet 0x15 disconnect. even a byte-exact replay of a *successful* real join (`spawn_realjoin.bin`, sha-verified against the capture) did it. in order, the theories:

1. **the client rejects chunks that contradict its self-generated world.** hours of ghidra found the self-gen vs receive switch (a byte at `Level+0x1674`, set in the level constructor, 1 from the guest startgame handler `FUN_0048d284`, 0 from the integrated-server setup `FUN_0047a4a8`). all true and all irrelevant — a live probe showed the client *is* a proper guest and builds a receive-level
2. **a null virtual call.** one rare crash had `PC=0` with `LR=0x00721308`: a `std::find_if` over the level's actor list calling vtable slot 0x548 on an actor whose vtable had that slot null. real mechanism, real crash, but a one-off race, not the normal path
3. **liveness.** the real host floods a 29-byte 0x43 packet ~537 times a second. theory: it's a per-tick chunk-grid update the client needs, and a static replayer going silent is why it leaves. replaying the real stream *did* keep the client in longer — then it crashed on a null level pointer, because it was a different session's world. this produced a whole "the client needs three things: consistent world, ongoing entity liveness, consistent with its world" model. also wrong. (0x43 is `MapItemDataPacket` — the bottom-screen map being drawn. nothing to do with chunks)
4. **a level watchdog.** found `disconnectionScreen.timeout` in the per-tick level function `FUN_00681950`, counter at `+0x50/+0x54`. patched azahar to make the timeout unreachable. client left on the same schedule. not it
5. **the 2 seconds isn't a timer.** lining up the client's own batches with the bridge log showed the "timeout" was resource-pack handshake latency; the disconnect follows the spawn batch by **290 ms**. so it's a parse rejection, not liveness
6. **the fixture describes the wrong console.** the captured spawn carries two uuids — the capture's guest and the capture's host — and the console we were replaying to *was* the host in that capture. so the batch said "you are this other player, and here's a remote player whose uuid is you". swapping the uuids: no change. real observation, not the trigger

and then the actual bug:

7. **raknet's mtu counts the ip and udp headers.** the bridge sized every datagram to the full 1492. only `1492 - 28 = 1464` bytes are yours. the real host's fragment datagrams are 1456 bytes; the client's own mtu probe pads to exactly 1464. it was in the captures the whole time. every message small enough for one datagram was under the limit and worked; anything that had to be split was 28 bytes oversized on **every fragment** — the client acked the datagrams, reassembled garbage, failed to parse, and left with the generic `disconnectionScreen.disconnected` and no reason code, because there was no protocol-level complaint to make.

    found by an 18-variant bisect scored purely on "did the client send 0x15": 1 fragment → stayed (9/9), 4+ fragments → disconnected (9/9). perfect correlation. packet identity, size and content predicted nothing.

8. **terrain still didn't render. chunks need loaded neighbours.** a chunk's mesh isn't built until the chunks around it are loaded, and the captured spawn was a 2×3 area where every chunk is missing a neighbour. a 4×4 grid renders. the "invisible but solid world" symptom (crosshair highlights blocks, you collide with them, nothing drawn) is the neighbour rule, not lighting.

    embarrassing sub-finding: two updates earlier the notes said "terrain renders!" it didn't. the beige wall with green squares and a pink block was the addplayer model's *face* at point-blank range. the user caught that, not me.

9. **the chunk format is sectioned and variable-length.** three wrong models before the right one (flat 49.9 kb array; u16 `id<<4|meta`; fixed 10240 stride). fixed strides all put section 0 in the right place, so section-0-only edits appeared to work and everything above drifted into meta/light data. the real layout was read out of `handleFullChunkData` and its two per-section readers, and is in the reference below. the reader `0x6619a4` consumes **four** arrays, not three (1 + 4096 + 2048 + 2048 + 2048 = 10241) — miscounting that cost most of a day. block order is `(x*16 + z)*16 + y`, settled by checking that y=0 is bedrock in 1536/1536 columns.
10. **the client won't relight.** no light block → pitch black. it relights locally when a block changes, but it does not light a fresh chunk. a relay has to supply skylight. and the light byte is `skylight<<4 | blocklight`: air is 0xF0, not 0xFF (0xFF makes every air block a torch, which the client undoes on the first block change as a big lag spike).

### m2 — the java leg

a hand-rolled 1.12.2 java client (why 1.12.2: it's the last version with `id<<4|meta` blocks, which is what mc3ds uses) behind viaproxy, mirroring the server's chunks into a `WorldModel`. `bridge.tools.MakeJavaSpawn` picked the most *varied* fully-loaded area (the first valid area is always ocean, which renders identically everywhere and tells you nothing) and the 3ds rendered real java terrain.

things that bit:

- a 1.21 superflat sits at y=-64, outside 1.12.2's 0..255, so viabackwards drops every block and every chunk arrives as air. use a normal world
- skylight has to pass through water, leaves, glass; treating water as opaque makes everything below sea level black
- paper kicks a client that never sends Client Settings, ~3 s after play
- command blocks in structures are viaproxy substituting modern blocks, not a bridge bug
- **"speed iv".** movement in java-sourced worlds felt way too fast and the measured net speed was normal, so falling, jitter, chunk streaming and the relay all got chased first. the cause: the captured spawn's UpdateAttributes set `minecraft:movement = 0.1` for the *captured* session's entity id, so our player never got one and used a default that's far too fast. the decisive test was three lines: send 0.03 and watch the player crawl. when the user keeps saying it's fast and the instrument disagrees, test the mechanism
- that was one of **four** bugs with the same root — the replayed spawn described a different player: addplayer put another model in our camera, adventuresettings gave us creative flight, the spawn point was theirs, the movement attribute was theirs. authoring startgame instead of replaying it fixed all of them
- reach: the 3ds reaches further than paper allows and paper drops an out-of-reach dig *silently* — no block change, no drop, and the 3ds is left showing a hole that isn't there. a server never echoes a block change to the player who broke it, so rejection looks identical to success from the client side
- drops need survival: the dev server was creative, so nothing ever dropped
- the beacon rename saga: four a/b rounds "proved" renaming the host broke the join list. actually every test also restarted the bridge, and the shim's reconnect takes ~5 s, and every "no servers found" was a check inside that window. capturing a real host's beacon settled it in one step. then the checksum (below) turned out to be real after all, and *that* was found from the samples

### on the console

the shim was 220 lines that speak six frames (`SCAN`, `BEACON`, `CONNECT`, `CONNECT_OK`, `DATA`, `DISCONNECT`) over tcp, so "port to hardware" meant "move the shim", not "port the bridge". it became a luma 3gx plugin that runs inside minecraft's process.

- **sockets.** `socInit` failed with `0xE0A01BF5`. first theory: a local-wireless game never got `soc:U` in its exheader. wrong — dumping the exheader shows all 32 service slots used and `soc:U` in slot 30, right next to `nwm::UDS` in 27. it's a *quota* error: socInit hands its buffer to `svcCreateMemoryBlock`, which needs heap-backed memory, and plgldr's default mapping isn't. `UsePrivateMemory: true` in the plginfo fixes it. (the service list should have been read before dumping anything — it's a one-line check)
- **the hook.** the sdk inlines ipc: there's no `svc 0x32; bx lr` stub to replace, there are 156 bare `svc 0x32` sites. the offline pass found 25 sites loading a handle from a global at `0x00A35E00` and called that the uds handle. on hardware every one of those was **apt** (fresh handle per call, InquireNotification/NotifyToWait/…); the collision was that apt's StartLibraryApplet has header `0x001E0084`, identical to uds ConnectToNetwork. the real uds wrappers are a cluster at `0x004C6FBC`–`0x004C739C` (11 sites, headers matched against 3dbrew), each `ldr r0,[r0]; svc 0x32` with the handle holder passed in r0, no global at all. tonic patches all 155 sites with a per-site veneer (save regs, call an answerer, run the original two instructions, return), seeds the uds handle set from the cluster sites, and filters everything else by handle value. a set, not one handle — the game opens more than one uds session, and letting *any* uds command through to the real service after faking the rest crashed it (`pc=0xFFF1C848`, `far=0x1423`)
- **the cache crash.** three identical crash dumps, `pc=0xFFF2B3F8` in svc mode, current process hid/gpio/dsp. not unmapped memory: luma's `svcFlushDataCacheRange`/`svcInvalidateInstructionCacheRange` run mva cache ops on the *other* core too, where a sysmodule is current and plugin memory isn't mapped. use `svcFlushProcessDataCache(CUR_PROCESS_HANDLE, …)` + `svcInvalidateEntireInstructionCache`, like ctrpf and rosalina do
- **the radio.** the 3ds has one radio. entering local-wireless mode calls `ndm:u EnterExclusiveState(2)` and infrastructure wifi is gone: `send()` returns `ENETDOWN` (115). tonic answers that one call with success and does nothing. it must *not* touch SuspendDaemons, which is routine at the title screen and neutralising it hangs the game
- **freezes.** the console froze after ~6 minutes: the game thread was blocking on a tunnel `recv` while the bridge stalled. reads are now `poll(0)`-gated into an 8 kb reassembly buffer and never block the game
- **3gxtool** segfaults on an elf with dwarf in it. the makefile strips before converting
- **the join list.** the beacon's 88 bytes are built by the plugin from the bridge's answer; the client validates a checksum in them (below)
- **it's bound to one binary.** the patch table (`plugin/tonic/Includes/sites.h`) and the two ndm call sites are absolute addresses from the usa title (`00040000001B8700`) on the v9.12.0 update. there's no runtime scan and no guard, so another region or update crashes at launch

### the server plugin

the mac-hosted bridge became a paper plugin (`src/paper`) so the 3ds connects straight to the server's tunnel port. then every 3ds became a real player.

- **floodgate.** the server is online-mode. floodgate's job is exactly "let a non-mojang client in as a real player", so tonicjava does what geyser does: a java client per 3ds, authenticating through floodgate's handshake. the handshake was reverse-engineered from the floodgate 2.2.5 jar and is in the reference. verified: a bot joined as `.Tonic3DS`, visible, tab-listed, /tp-able
- **name and skin come from the login.** the 3ds login json is uncompressed and readable, so the bot is spawned mid-handshake once the name is known (step 3 holds the loading screen up to ~6 s for it). `skinId` is a name, not pixels; other consoles resolve it from their own built-in list, so console-to-console skins cost nothing — the id just gets passed through in playerlist
- **movement.** relaying the 3ds's moves as client position packets got the bot rubber-banded back to spawn on every horizontal step (a flood of teleport-confirms), and it only escaped upward on a jump — a flying creative player still collides with blocks, and paper's movement validation logs nothing. the fix is server-authoritative: each relayed move is a target, and a per-tick task teleports the real player there
- **breaking.** the bot's digs were silently ignored: the bot spawns *at* world spawn, inside `spawn-protection=16`. no log line. and a creative dig would never drop anything anyway. so breaks are `breakNaturally` (drops as if mined with a pickaxe, tunable) and places are `setType` — bukkit edits bypass spawn protection. a bukkit `setType` consumes nothing and the 3ds's inventory mirrors the bot's, so the plugin decrements the bot's hand itself, or you get "refunded" every block
- **drops.** pickup already worked (bot collects, mirror pushes to the 3ds); the 3ds just never *saw* the item on the ground because nobody told it. the plugin's `ItemSpawnEvent` → an AddItemEntity (0x0f) whose layout is inferred from the packet family (every neighbouring id lines up with the mcpe 1.0 table), removed on collect/despawn
- **rejoins.** a 3ds that drops silently leaves a tcp session open until timeout, so its bot lingers; a rejoin with the same name makes the server kick the old bot (`duplicate_login`). the plugin now ends the 3ds session the instant its bot's java connection dies, and stale puppets can't answer skin lookups

### skins for pc players

java clients only accept skins **signed by mojang**. so each 3ds skin png is sent to mineskin once (bearer key, `variant` classic/slim, response `data.texture.value/signature`), cached on disk per skin id, and applied with paper's `setPlayerProfile`. the cache is disk-only on purpose: delete a file, the skin re-signs on the next join, no restart.

the textures come out of the game's romfs, `resourcepacks/skins/skinpacks/`, 24 packs, 630 skins. the format and the id rule are in the reference. the first pass looked wrong on the torso and hat — because 497 of the 628 skins ship **custom geometry** (`<Pack>.json`), with 11-tall limbs, an empty hat bone, and extra cubes (mario's belt, his cap brim) painted exactly where java's fixed model reads its jacket and hat overlays. `tools/3dst2png.py` rebuilds those textures part by part from the rig and leaves the overlay regions transparent unless the rig defines them. noses and brims are lost; java has nowhere to draw them.

## lessons that keep coming back

- **capture, don't construct.** a known-good sample of the real thing settles in one step what several a/b rounds can't
- **server-side rejections are silent.** reach, spawn protection, movement validation, `duplicate_login` — none of them log. three test cycles each. when the log is empty, assume rejection, not success
- **when the user says it's broken and the instrument says it's fine, test the mechanism directly.** the speed bug, the "terrain renders" face
- **every fixture packet from another session is a latent bug.** author it or expect it to describe someone else
- **build the oracle before the experiment.** player y as a "did chunks load" oracle, the 0x15-scored bisect harness, a world where every chunk is a different block. one-bit-per-join experiments are how a day disappears
- **read the file before dumping the console.** the exheader service list, the code's first words
- **static analysis has a ceiling** — runtime-resolved vtable calls, indirection through varying base+offset. when the probes stop giving answers, change tools, don't re-read the same functions

## reference

### login json

    {"playerName":"Brmkr","skinId":"Standard_Steve","uuid":"88821a6b-c6e7-1979-ce36-265842eb21de"}

packet id 0x01, uncompressed inside the 0xFE batch, ~103 bytes. `uuid` is stable per console. skin ids are `<PackId>_<SkinName>` (`Standard_Steve`, `MarioBrothers_Mario`).

### the beacon (88 bytes of uds application data)

    0x00  "MC"            magic
    0x02  03              format version
    0x04  u16 LE          checksum (below)
    0x06  u16 LE 0x0073   constant
    0x08  u64             host guid
    0x10  char[32]        host name, utf-8, zero padded
    0x30  char[32]        world name
    0x50  u8              current players
    0x51  u8              max players (the 2-player cap lives here; 3/8 displays fine)
    0x52  u8              unknown, covered by the checksum, both values accepted
    0x53  pad

checksum: one's-complement sum of the little-endian u16 words of all 88 bytes with the field itself as zero, carries folded back in, plus 0xBCB0:

    sum   = Σ words[i], i ≠ 2
    while (sum >> 16) sum = (sum & 0xFFFF) + (sum >> 16)
    field = (sum + 0xBCB0) & 0xFFFF

how it was findable: two samples differing only in player count differed in the field by exactly 1 (so additive, not a crc); swapping a name changed Σbytes by an even amount but the field by an odd one, which is impossible mod 65536 and fine mod 65535 — the signature of a one's-complement sum. the 0xBCB0 bias is unexplained (probably the game sums a longer internal struct). § colour codes render literally in the join list; wlan_comm_id is `0x001B8710`.

### packet ids

all 92 are in `tools/PACKET-IDS.json`, extracted from the rom: each packet class has a `getName()` returning its `"…Packet"` string, and `getId()` is one vtable slot before it (`mov r0,#imm; bx lr`). validated against the six ids known from captures. the ones tonic uses:

    0x01 Login          0x02 PlayStatus       0x06/07/08 resource packs   0x0a SetTime
    0x0b StartGame      0x0c AddPlayer        0x0e RemoveEntity           0x0f AddItemEntity
    0x12 MoveEntity     0x13 MovePlayer       0x15 RemoveBlock            0x16 UpdateBlock
    0x1a LevelEvent     0x1e UpdateAttributes 0x1f MobEquipment           0x23 UseItem
    0x24 PlayerAction   0x34 ContainerSetContent   0x3a FullChunkData     0x3f PlayerList
    0x43 MapItemData (the bottom-screen map, ~537/s from a real host — ignore it)
    0x45 RequestChunkRadius -> 0x46 ChunkRadiusUpdated (the client asks for 6 every session)

### the packets, as authored (all little-endian, varints zigzag where marked)

    StartGame     0b | varlong uniqueId | varint runtimeId | varint gamemode(zz: survival 0, creative 2)
                     | f32 x,y,z | f32 yaw,pitch | varint seed, dimension, generator, worldGamemode, difficulty
                     | varint spawnX(zz), spawnY, spawnZ(zz) | …tail (gamerules, level id, world name; copied from a capture)
    AddPlayer     0c | uuid(16) | string name | varlong uniqueId | varint runtimeId | f32 x,y,z | f32 motion x,y,z
                     | f32 pitch,yaw,headYaw | item stack | metadata (varint count, then entries — copied from a capture,
                       only the nametag string rewritten; building it by hand crashed the client)
    PlayerList    3f | u8 action(0=add) | varint count | [ uuid(16) | varlong uniqueId | string name | string skin ]*
                     (the skin is a built-in name like "Standard_Steve"; AddPlayer without a PlayerList first crashes
                      the console the moment it looks at the entity)
    MoveEntity    12 | varint runtimeId | f32 x,y,z | u8 pitch | u8 yaw | u8 headYaw | u8 onGround | u8 teleport
                     (byte angles, 256 per turn; verified against 124k captured packets)
    RemoveEntity  0e | varlong uniqueId                                   (inferred, never captured with a body)
    AddItemEntity 0f | varlong uniqueId | varint runtimeId | item stack | f32 x,y,z | f32 motion x,y,z | varint 0
                     (inferred from the mcpe 1.0 table; MC3DS_ITEMS=0 turns it off)
    UpdateAttributes 1e | varint entityId | varint count | [ f32 min | f32 max | f32 current | f32 default | string name ]*
                     (minecraft:movement = 0.1 is vanilla walking speed)
    LevelEvent    1a | zigzag eventId | f32 x,y,z | zigzag data          (2001 = block break particles, data = block id)
    UpdateBlock   16 | position, y is ONE BYTE (a y of -1 becomes 255 and a block appears in the sky)
    item stack       zz(id) | zz(aux = damage<<8 | count) | zz(9) | zz(0) | 00 00 00 00      (empty = a single 0)

and the ones the client sends:

    MovePlayer    13 | varint eid | f32 x,y,z (EYE position, 1.62 above the feet) | f32 pitch, yaw, headYaw | 3 bytes
    RemoveBlock   15 | zigzag x | u8 y | zigzag z
    PlayerAction  24 | varint entityId | zz action | zigzag x | u8 y | zigzag z | u8 face
                     (0 start break, 1 abort, 2 stop, 18 continue — continue means "moved to a new block", not "still going")
    UseItem       23 | BlockPos | zz face | varint | Vec3 | Vec3 | item stack    BlockPos = zigzag x | varint y | zigzag z
                     faces 0=-Y 1=+Y 2=-Z 3=+Z 4=-X 5=+X. the field after the position is the CLICKED block's id, not the face.
                     the client also emits junk 0x23s (1-7 bytes of zeros) that parse as (0,0,0) — reject them: the clicked
                     block must be solid in the world model
    MobEquipment  1f | varint entityId | item stack | u8 inventorySlot | u8 hotbarSlot | u8 window

### FullChunkData (0x3a)

    3a | zigzag chunkX | zigzag chunkZ | varint payloadLen | payload

    payload:
      count u8                       16-block sections, from y=0 up
      count x section:
          flagA  1 B
          ids    4096 B              one byte per block, index (x*16 + z)*16 + localY
          meta   2048 B              nibbles
          ?      2048 B              nibbles
          ?      2048 B              nibbles
          flagB  1 B                 1 = a light block follows
          light  4096 B              only when flagB != 0; one byte per block, skylight<<4 | blocklight
      heightmap 512 B                256 x u16 LE
      biome     256 B
      2 B

sections are **variable length** (10242 without light, 14338 with) and have to be walked. a chunk with no light renders pitch black. air = 0xF0. a chunk only gets a mesh once its orthogonal neighbours are loaded, so serve a full radius (tonic streams a ring of 4 around the player, one chunk per 250 ms — faster stalls the client). derived from `handleFullChunkData` at `0x0048F750` and its readers `0x6619a4` / `0x15add0`; the size identity `len = 1 + count*6146 + 4096*N + 771` holds on all six captured chunks.

### the rom (for whoever goes back in)

`code.bin` from the usa v9.12.0 update, 9,674,752 bytes, arm/a32 little-endian, no rtti, some asserts compiled out. contiguous load map, so `file_offset = VA − 0x00100000`:

    .text   0x00100000  size 0x818824
    .rodata 0x00919000  size 0x10f464
    .data   0x00a29000  size 0x010b70

ghidra: raw binary, arm v6 le, image base 0x00100000. anchors that were verified:

    0x0048F750  handleFullChunkData (ClientNetworkHandler vtable +0x104)     0x009c4c8c  ClientNetworkHandler vtable
    0x0048d284  StartGame handler (creates the receive-level)               0x009bf4b8  ServerNetworkHandler vtable
    0x005cf478  Level ctor (Level+0x1674 = receive flag)                     0x0066d6cc  Dimension::_initChunkSource
    0x00681950  per-tick level fn (timeout literal at 0x00681e2f)            0x006E18A0  MovePlayerPacket::write
    0x006E1624  LevelEventPacket::write                                      0x006FEEA8  FullChunkDataPacket::write
    0x007da810  zigzag varint pair writer   0x7da49c BlockPos writer   0x7d92c8 ItemStack writer
    0x008ce314  the std::function invoker that tail-calls vtable[0x548] (the PC=0 crash)
    0x004C6FBC–0x004C739C  the real nn::uds ipc wrappers (11 sites)          0x00A35E00  NOT uds — it's apt
    0x004BBB10 / 0x004BBC64  ndm:u EnterExclusiveState call sites            0x00137650  SuspendDaemons (leave it alone)
    "nwm::UDS" string at 0x0098B803, referenced once at 0x004C6EFC

### tonic3ds, the numbers

- `svc 0x32` sites: 156 total; 154 are `svc 0x32 ; ands r1, r0, #0x80000000`, 2 are `svc 0x32 ; stm r5,{r7,r8}`. 155 are patched (`plugin/tonic/Includes/sites.h`)
- patch = two words per site: `ldr pc,[pc,#-4]` + the veneer's absolute address. veneer = `stmfd sp!,{r0-r12,lr}; mov r0,sp; mov r1,#site; bl udsAnswer; cmp r0,#0; ldmfd; bne skip; svc 0x32; <clobbered ins>; ldr pc,[pc,#-4]; .word site+8`
- cache: `svcFlushProcessDataCache(CUR_PROCESS_HANDLE, …)` + `svcInvalidateEntireInstructionCache()`. never luma's range svcs
- plginfo needs `UsePrivateMemory: true` or `socInit` fails with `0xE0A01BF5` (a quota error, not permissions — the exheader has `soc:U` in slot 30)
- the uds commands the plugin answers: 0x1B InitializeWithVersion, 0x0F RecvBeaconBroadcastData (scan → beacon), 0x1E ConnectToNetwork, 0x12 Bind, 0x17 SendTo (→ tunnel), 0x14 PullPacket (← tunnel), 0x0B GetConnectionStatus, 0x0A DisconnectNetwork, 0x1F DecryptBeaconData, plus small constant answers for Unbind/GetChannel/GetNodeInformation. everything else on a uds handle is swallowed so the real service never sees a half-faked session
- tunnel frames: `SCAN`, `BEACON`, `CONNECT`, `CONNECT_OK`, `DATA(channel, bytes)`, `DISCONNECT`. config is `sdmc:/tonic.cfg` = `ip:port`, `inet_addr` only
- crash dumps land in `/luma/dumps/arm11/*.dmp`: 40-byte header, sizes at +20, registers at +40 (r0-r12, sp, lr, pc, cpsr, dfsr, ifsr, far), then code bytes ending at pc, process name in the trailing data. always check the cpsr mode and the process name before blaming memory

### the floodgate handshake (2.2.5, from the jar)

    key      = the 16 raw bytes of plugins/floodgate/key.pem
    cipher   = AES/GCM/NoPadding, 12-byte random iv, 128-bit tag
    data     = 12 fields joined with \0:
               version, username, xuid, deviceOs, languageCode, uiProfile, inputMode, ip,
               linkedPlayer ("null"), fromProxy (0/1), subscribeId, verifyCode (must be non-empty)
    wire     = "^Floodgate^>" + base64(iv) + "!" + base64(ciphertext + tag)
    address  = wire + "\0" + realServerAddress        (goes in the java handshake's address field)

floodgate prefixes the name with `.` and derives the offline uuid from the xuid, which must parse as a long. the server needs viaversion + viabackwards because the bot speaks 1.12.2. floodgate doesn't touch the player's textures unless geyser feeds it a skin, so a `setPlayerProfile` from the plugin sticks (it's re-asserted once, 2 s later, to be safe).

### .3dst skin textures

    0x00  "3DST"     0x04 u32 version (3)   0x08 u32 0   0x0C u32 w   0x10 u32 h   0x14 u32 w   0x18 u32 h   0x1C u32 format (1 = RGBA8)
    0x20  pixels: PICA200 8x8 morton-tiled texels, 4 bytes each stored A,B,G,R, tile rows bottom-up

decoded that way, a skin is the ordinary 64×64 java layout. a skin file is 16,416 bytes; anything else in a pack is an icon, a thumbnail or a 64×32 cape. `Base/` has a `skindefs.json` (steve, alex, a dummy); every other pack is one `.3dst` per skin named by the skin, `_slim` suffix = the alex model, and the pack's `.json` is custom *geometry* (bones with cubes {size, uv, mirror}), not a manifest. login ids are `<Folder>_<Stem>` except the standard pack, whose folder is `Base` and whose id prefix is `Standard`. `tools/3dst2png.py` does all of this, including rebuilding custom-rig textures onto java's model.

### working with azahar (the pc side)

the m1/m2 work ran against a patched azahar with a shim in `nwm_uds.cpp` (`shim/`). things that waste an afternoon if you don't know them: azahar pauses to 0 fps whenever it isn't the frontmost app, so synthetic input only lands while it's focused; menu navigation by keys loses presses and silently lands on **host**, which starts a local world that looks exactly like a successful join — tap the touch screen instead; keymap is circle pad = arrows, A = a, B = s, X = z, Y = x, L = q, R = w, and R is mine/attack. `tools/tunnel_smoke.py` drives scan → raknet → resource packs → spawn against a running bridge with no 3ds at all.
