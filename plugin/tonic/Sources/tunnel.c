#include <string.h>
#include <stdlib.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <sys/time.h>
#include <poll.h>
#include <errno.h>
#include "tunnel.h"

#define MAX_FRAME     1600
#define MAX_QUEUED       8
#define MAX_BEACON     512

typedef struct {
    u8  channel;
    u8  src;
    u32 len;
    u8  data[MAX_FRAME];
} Packet;

static int        sock = -1;
static volatile bool running;
static char       savedHost[64];
static u16        savedPort;
static volatile u32 gRecvBytes, gSendErr, gRecvFrames;
static volatile int gLastErrno;


static LightLock  lock;
static LightEvent beaconEvent, connectEvent;

static u8  beaconData[MAX_BEACON];
static u32 beaconLen;
static u16 connectNodeId;

// A ring of inbound DATA frames. The 3DS pulls packets on its own schedule (PullPacket), so they
// have to be held until it asks; dropping the oldest is right because stale game packets are
// worthless — better to lose one than to stall the reader.
static Packet queue[MAX_QUEUED];
static u32    qHead, qCount;

static bool sendAll(const u8 *p, u32 n) {
    while (n) {
        int w = send(sock, p, n, 0);
        if (w <= 0) { gLastErrno = errno; gSendErr++; running = false; return false; }
        p += w; n -= (u32)w;
    }
    return true;
}

static bool recvAll(u8 *p, u32 n) {
    while (n) {
        int r = recv(sock, p, n, 0);
        if (r <= 0) return false;
        p += r; n -= (u32)r;
    }
    return true;
}

static void sendFrame(u8 type, u8 src, u8 dst, u8 channel, const u8 *data, u32 len) {
    if (sock < 0) return;
    u8 head[6];
    u32 total = 4 + len;
    head[0] = (u8)(total >> 8); head[1] = (u8)total;
    head[2] = type; head[3] = src; head[4] = dst; head[5] = channel;
    LightLock_Lock(&lock);
    if (sendAll(head, 6) && len) sendAll(data, len);
    LightLock_Unlock(&lock);
}

// Poll-gated, non-blocking reassembly. The game thread drives PullPacket, so a read must NEVER
// block: poll(0) says whether bytes are ready, and a blocking recv() then returns immediately with
// whatever is available (possibly a partial frame). Bytes accumulate in rxbuf; whole frames are
// popped out. A bridge that stalls mid-frame therefore can no longer hang the console.
static u8  rxbuf[8192];
static u32 rxlen;

static void drainSocket(void) {
    struct pollfd pfd; pfd.fd = sock; pfd.events = POLLIN;
    while (sock >= 0 && rxlen < sizeof(rxbuf)) {
        pfd.revents = 0;
        if (poll(&pfd, 1, 0) <= 0 || !(pfd.revents & POLLIN)) break;
        int r = recv(sock, rxbuf + rxlen, sizeof(rxbuf) - rxlen, 0);
        if (r > 0) { rxlen += (u32)r; gRecvBytes += (u32)r; }
        else { running = false; break; }        // 0 = peer closed, <0 = error
    }
}

// Pops one complete frame if present. Returns payload length (0 if none yet).
static u32 popFrame(u8 *type, u8 *src, u8 *channel, u8 *out, u32 maxlen) {
    if (rxlen < 2) return 0;
    u32 flen = ((u32)rxbuf[0] << 8) | rxbuf[1];  // = 4 + payload
    if (flen < 4 || flen + 2 > sizeof(rxbuf)) { rxlen = 0; return 0; }  // desync: resync hard
    if (rxlen < flen + 2) return 0;              // frame not fully arrived
    if (type) *type = rxbuf[2];
    if (src) *src = rxbuf[3];
    if (channel) *channel = rxbuf[5];
    u32 payload = flen - 4, take = payload > maxlen ? maxlen : payload;
    if (take) memcpy(out, rxbuf + 6, take);
    u32 consumed = flen + 2;
    if (rxlen > consumed) memmove(rxbuf, rxbuf + consumed, rxlen - consumed);
    rxlen -= consumed;
    gRecvFrames++;
    return payload;
}

static u32 recvFrame(u8 *type, u8 *src, u8 *channel, u8 *out, u32 maxlen, s64 timeout_ms) {
    // SO_RCVTIMEO is not implemented by the 3DS socket service, so wait with poll() and only
    // read once there is something to read.
    struct pollfd pfd;
    pfd.fd = sock;
    pfd.events = POLLIN;
    pfd.revents = 0;
    if (poll(&pfd, 1, (int)timeout_ms) <= 0) return 0;

    u8 lenbuf[2];
    if (!recvAll(lenbuf, 2)) return 0;
    u32 len = ((u32)lenbuf[0] << 8) | lenbuf[1];
    if (len < 4 || len > MAX_FRAME + 4) return 0;

    u8 head[4];
    if (!recvAll(head, 4)) return 0;
    if (type) *type = head[0];
    if (src) *src = head[1];
    if (channel) *channel = head[3];

    u32 payload = len - 4;
    u32 take = payload > maxlen ? maxlen : payload;
    if (take && !recvAll(out, take)) return 0;
    gRecvBytes += payload; gRecvFrames++;
    for (u32 left = payload - take; left; ) {          // drain anything that did not fit
        u8 sink[64];
        u32 n = left > sizeof(sink) ? sizeof(sink) : left;
        if (!recvAll(sink, n)) return 0;
        left -= n;
    }
    return payload;
}

bool tunnelConnect(const char *host, u16 port) {
    strncpy(savedHost, host, sizeof(savedHost) - 1); savedHost[sizeof(savedHost)-1] = 0;
    savedPort = port;
    LightLock_Init(&lock);
    LightEvent_Init(&beaconEvent, RESET_ONESHOT);
    LightEvent_Init(&connectEvent, RESET_ONESHOT);
    qHead = qCount = 0;

    sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) return false;

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port);
    addr.sin_addr.s_addr = inet_addr(host);
    if (connect(sock, (struct sockaddr *)&addr, sizeof(addr)) != 0) {
        closesocket(sock); sock = -1;
        return false;
    }

    running = true;
    return true;
}

bool tunnelActive(void) { return running && sock >= 0; }

// Close and reopen a fresh socket to the bridge. The socket opened at plugin load is dead by the
// time the game reaches ConnectToNetwork (the game's own net init resets shared SOC state), so the
// data path must start from a clean connection.
bool tunnelReconnect(void) {
    if (sock >= 0) { closesocket(sock); sock = -1; }
    running = false;
    if (!savedPort) return false;
    return tunnelConnect(savedHost, savedPort);
}

void tunnelStats(u32 *recvBytes, u32 *recvFrames, u32 *sendErr, int *lastErrno) {
    if (recvBytes) *recvBytes = gRecvBytes;
    if (recvFrames) *recvFrames = gRecvFrames;
    if (sendErr) *sendErr = gSendErr;
    if (lastErrno) *lastErrno = gLastErrno;
}

void tunnelClose(void) {
    running = false;
    if (sock >= 0) { closesocket(sock); sock = -1; }

}

void tunnelSendScan(void)                                { sendFrame(TUNNEL_SCAN, 2, 1, 0, NULL, 0); }
void tunnelSendConnect(const u8 *pass, u32 len)          { sendFrame(TUNNEL_CONNECT, 2, 1, 0, pass, len); }
void tunnelSendData(u8 ch, const u8 *data, u32 len)      { sendFrame(TUNNEL_DATA, 2, 1, ch, data, len); }
void tunnelSendDisconnect(void)                          { sendFrame(TUNNEL_DISCONNECT, 2, 1, 0, NULL, 0); }

u32 tunnelWaitBeacon(u8 *out, u32 maxlen, s64 timeout_ms) {
    for (s64 waited = 0; waited <= timeout_ms; waited += 10) {
        drainSocket();
        for (int i = 0; i < 8; i++) {
            u8 type = 0; u32 n = popFrame(&type, NULL, NULL, out, maxlen);
            if (!n) break;
            if (type == TUNNEL_BEACON) return n;
        }
        if (!tunnelActive()) return 0;
        svcSleepThread(10ll * 1000 * 1000);
    }
    return 0;
}

bool tunnelWaitConnectOk(u16 *nodeId, s64 timeout_ms) {
    if (LightEvent_WaitTimeout(&connectEvent, timeout_ms * 1000000LL) != 0) return false;
    if (nodeId) *nodeId = connectNodeId;
    return true;
}

u32 tunnelPopData(u8 channel, u8 *out, u32 maxlen, u8 *srcNode) {
    u32 n = 0;
    LightLock_Lock(&lock);
    for (u32 i = 0; i < qCount; i++) {
        u32 slot = (qHead + i) % MAX_QUEUED;
        if (queue[slot].channel != channel) continue;
        n = queue[slot].len > maxlen ? maxlen : queue[slot].len;
        memcpy(out, queue[slot].data, n);
        if (srcNode) *srcNode = queue[slot].src;
        // Close the gap so ordering within a channel is preserved.
        for (u32 j = i; j + 1 < qCount; j++) {
            queue[(qHead + j) % MAX_QUEUED] = queue[(qHead + j + 1) % MAX_QUEUED];
        }
        qCount--;
        break;
    }
    LightLock_Unlock(&lock);
    return n;
}

u32 tunnelPollData(u8 *out, u32 maxlen, u8 *src, u8 *channel, s64 timeout_ms) {
    (void)timeout_ms;
    drainSocket();
    for (int i = 0; i < 8; i++) {
        u8 type = 0, s = 0, ch = 0;
        u32 n = popFrame(&type, &s, &ch, out, maxlen);
        if (!n) return 0;
        if (type == TUNNEL_DATA) { if (src) *src = s ? s : 1; if (channel) *channel = ch; return n; }
    }
    return 0;
}
