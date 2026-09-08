#pragma once
#ifdef HOST_TEST
#include <stdint.h>
typedef uint8_t u8; typedef uint16_t u16; typedef uint32_t u32; typedef uint64_t u64;
#else
#include <3ds.h>
#endif
// Writes a RecvBeaconBroadcastData reply (reply header + 1 entry + 802.11 frame) to `out`.
// reqSize = the buffer size the game asked for (echoed into max_output_size). Returns bytes written,
// or 0 if it would not fit in maxSize.
u32 buildBeaconReply(u8 *out, u32 maxSize, u32 reqSize, const u8 *appdata, u32 appLen);
