# M2 — Java leg (MC3DS ↔ real Java server)

The bridge's Java client joins a modern Java server via ViaProxy (which down-translates to
1.12.2, restoring the id<<4|meta block model MC3DS uses) and mirrors the world. The jars and
generated data are gitignored; recreate with the steps below.

## Stack / ports
```
bridge JavaClient (1.12.2)  ->  ViaProxy :25568  ->  Paper server :25565
```

## Recreate
Requires JDK 21 (`brew --prefix openjdk@21`).

1. **Paper server** (runs on Java 21; 1.21.11 is the newest Java-21 build):
   ```bash
   cd m2/server
   curl -s "https://fill.papermc.io/v3/projects/paper/versions/1.21.11/builds" \
     | python3 -c "import sys,json;print(json.load(sys.stdin)[0]['downloads']['server:default']['url'])" \
     | xargs curl -sL -o paper.jar
   java -jar paper.jar --nogui   # eula.txt + server.properties are committed (offline, flat world)
   ```

2. **ViaProxy** (use the plain jar, NOT the +java8 one — it breaks on Java 21):
   ```bash
   cd m2/viaproxy
   curl -sL -o viaproxy.jar \
     https://github.com/ViaVersion/ViaProxy/releases/download/v3.4.12/ViaProxy-3.4.12.jar
   java -jar viaproxy.jar cli --bind-address 127.0.0.1:25568 \
     --target-address 127.0.0.1:25565 --target-version 1.21.11 --auth-method NONE
   ```

3. **Bridge Java leg** (from repo root):
   ```bash
   gradle build
   java -cp build/classes/java/main bridge.javaclient.JavaClient 127.0.0.1 25568
   ```
   Expected: `JOIN GAME ... level=default`, `spawned`, a `PARSED chunk(...)` line showing a
   surface column (grass=2 over dirt=3 over air=0), then `chunks received=N` counters.

## Status
- ✅ Java leg connects through ViaProxy, logs in offline, stays alive (keep-alive +
  teleport-confirm + **Client Settings** — without the last one Paper kicks with
  `disconnect.timeout` ~3s after PLAY).
- ✅ Parses 1.12.2 chunk sections (bitsPerBlock + palette + packed long[], values may span
  longs) into the `world` model as `id<<4|meta`. ~150 chunks mirrored in 20s on a normal world;
  verified real terrain (grass over dirt over stone at the right heights).
- ⚠️ Use a **normal** world, not superflat: a 1.21 superflat sits at y=-64, outside 1.12.2's
  0..255 range, so ViaBackwards drops every block and chunks arrive all-air.
- ⏳ next (M3): wire the Java `world` into the MC3DS StartGame/chunk legs so the 3DS sees the
  server's terrain. Open question: whether a *properly* built MC3DS FullChunkData is accepted
  (M1 showed captured/replayed chunks were rejected).
