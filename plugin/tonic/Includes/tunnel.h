#pragma once
#include <3ds.h>

// The bridge tunnel, ported from shim/bridge_shim.h. Same six frames, same wire format:
//
//     u16be length (= 4 + payload)  |  u8 type  |  u8 src  |  u8 dst  |  u8 channel  |  payload
//
// Nothing above this layer knows whether it is talking to Azahar's shim or to Tonic.
enum {
    TUNNEL_SCAN = 1, TUNNEL_BEACON = 2, TUNNEL_CONNECT = 3,
    TUNNEL_CONNECT_OK = 4, TUNNEL_DATA = 5, TUNNEL_DISCONNECT = 6
};

bool tunnelConnect(const char *host, u16 port);
bool tunnelActive(void);
void tunnelClose(void);

void tunnelSendScan(void);
void tunnelSendConnect(const u8 *passphrase, u32 len);
void tunnelSendData(u8 channel, const u8 *data, u32 len);
void tunnelSendDisconnect(void);

/** Blocks up to timeout_ms for the next BEACON's application data. Returns its length, or 0. */
u32  tunnelWaitBeacon(u8 *out, u32 maxlen, s64 timeout_ms);
/** Blocks up to timeout_ms for CONNECT_OK; returns false on timeout. */
bool tunnelWaitConnectOk(u16 *nodeId, s64 timeout_ms);
/** Non-blocking: pops the next inbound DATA payload for a channel. Returns its length, or 0. */
u32  tunnelPopData(u8 channel, u8 *out, u32 maxlen, u8 *srcNode);
/** Synchronous: reads the next inbound DATA frame (skipping others). Returns payload len, or 0. */
u32  tunnelPollData(u8 *out, u32 maxlen, u8 *src, u8 *channel, s64 timeout_ms);
/** Close and reopen a fresh socket to the last host:port. Returns true on success. */
bool tunnelReconnect(void);
/** Cumulative tunnel counters, for diagnostics. */
void tunnelStats(u32 *recvBytes, u32 *recvFrames, u32 *sendErr, int *lastErrno);
