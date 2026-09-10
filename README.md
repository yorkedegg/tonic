# tonic

> **Alpha / Minimum Viable Product**
> Expect bugs and missing features. DM `eg.o` on Discord for feedback/issues.

Connect Minecraft 3DS Edition (`mc3ds`) to a Paper Java server.

---

## Overview

Tonic consists of two main components:

* **`tonic3ds`** — Luma 3GX plugin running on the 3DS.
* **`tonicjava`** — Paper server plugin that bridges connections via Floodgate.

---

## Download

Get both files from the [latest release](https://www.google.com/search?q=../../releases):

* `tonic.3gx` $\rightarrow$ place at `sd:/luma/plugins/00040000001B8700/tonic.3gx`
* `TonicJava.jar` $\rightarrow$ place in your server's `plugins/` folder

---

## Prerequisites

### Console

* New Nintendo 3DS running Luma3DS
* USA copy of *Minecraft: 3DS Edition* with the v1.9 / v9.12.0 update
* A brain

### Server

* Paper 1.21.x
* [Floodgate](https://www.google.com/search?q=https://geysermc.org/floodgate)
* ViaVersion + ViaBackwards
* An open TCP port for the tunnel (default: `27953`)
* *Optional:* MineSkin API key

---

## Setup

### On 3DS

1. Copy `tonic.3gx` to `sd:/luma/plugins/00040000001B8700/tonic.3gx`.
2. Create `sd:/tonic.cfg` with a single line containing your server IP and port:
```text
1.2.3.4:27953

```


*Must be a raw IP address, not a hostname (no DNS support yet).*
*(If missing, Tonic will remain inactive and log to `sd:/tonic.log`)*.
3. Connect to Wi-Fi, launch the game, and tap **Play**.
4. Select **Tonic** from your join list (it appears as a local world).

### On Server

1. Drop `TonicJava.jar` into your `plugins/` directory.
*(To build from source: `./gradlew paperJar`. Requires Java 21; output lands in `build/libs/`)*.
2. Run the server once to generate `plugins/TonicJava/config.yml`.
3. Set `tunnel-port` in `config.yml` to your opened TCP port.
4. Ensure Floodgate is installed and running (`TonicJava` reads `plugins/floodgate/key.pem` to authenticate 3DS players).

### On Azahar (Emulator)

1. Install the v9.12.0 update CIA in Azahar via **File $\rightarrow$ Install CIA**.
*(The base v0.1.0 game binary is incompatible; Tonic will refuse to load and log why to `sdmc/tonic.log`)*.
2. Close Azahar and open `qt-config.ini` located in your Azahar config directory (`~/Library/Application Support/Azahar/config/` on macOS or OS equivalent).
3. Set the following configuration values:
```ini
plugin_loader=true
plugin_loader\default=false

```


*(If `plugin_loader\default` remains `true`, Azahar overrides your setting and disables the loader)*.
4. Copy `tonic.3gx` to `sdmc/luma/plugins/00040000001B8700/tonic.3gx` and place `tonic.cfg` at the root of `sdmc/`.
5. Launch the game and join **Tonic**. Check `sdmc/tonic.log` for status updates.

---

## Skins (Optional)

The 3DS client only transmits internal skin names. Raw skin textures are stored locally on the console and must be dumped once:

1. Using **GodMode9**, dump `romfs/resourcepacks/skins/skinpacks/` from your game copy to your SD card.
2. Run the conversion script:
```bash
python3 tools/3dst2png.py <path_to_skinpacks> plugins/TonicJava/skins

```


*Converts ~600 3DS skins into standard PNGs, builds an index, and remaps 3DS-specific models to fit Java geometry.*
3. Add a MineSkin API key to `config.yml` (`mineskin-key`).
*Java clients require skins signed by Mojang. MineSkin signs each skin on first use and caches it permanently.*

---

## Known Limitations

* **USA v9.12.0 binary only.**
* **Duplicate usernames:** Two 3DS players using the same username cannot be online at the same time (the second connection kicks the first).
* **Entities:** No mob support on 3DS yet—only players and dropped items render.
* **Skin details:** 3DS-specific model extensions (e.g., Mario's nose, R2-D2 parts) are lost during conversion. Fully non-humanoid skins render with transparent sections.
* **Disconnects:** Tunnel drops do not auto-reconnect. You must manually rejoin from the world list.

---

## Notes

* Read [`FINDINGS.md`](https://www.google.com/search?q=FINDINGS.md) for details on how this was reverse-engineered.
* Not affiliated with Mojang, Nintendo, or Other Ocean.
