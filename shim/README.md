# Azahar shim (M1)

Redirects MC3DS UDS to the bridge tunnel. Activated at runtime by env
`MC3DS_BRIDGE=127.0.0.1:7777` (normal room path runs when unset).

- `bridge_shim.h` — header-only tunnel client (drop into
  `src/core/hle/service/nwm/` in the Azahar tree; no CMake changes).
- `nwm_uds.patched.cpp` — reference copy of the patched UDS service. Hooks:
  RecvBeaconBroadcastData (synthesize beacon), ConnectToNetworkHLE (tunnel
  connect + respond success directly), SendToHLE (tunnel send), PullPacketHLE
  (tunnel receive). Search for `[BRIDGE]`.

Build: incremental `cmake --build build` in the Azahar tree.
