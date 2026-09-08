// Step 1 of the on-console port: prove a plugin loads into Minecraft and can run code and do I/O.
//
// Deliberately does nothing else. The UDS hook, the tunnel and the six-frame protocol all depend on
// this working, and this is the cheapest thing that answers it. See ../../ONCONSOLE-PLUGIN.md.
#include <3ds.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <malloc.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <errno.h>
#include "plgldr.h"
#include "tunnel.h"
#include "hook.h"

// Where the bridge is. The console reaches it over ordinary Wi-Fi — UDS is replaced wholesale
// rather than tunnelled, so the radio is never in local-wireless mode.
#define BRIDGE_HOST "10.161.209.234"   // fallback when sdmc:/tonic.cfg is absent
#define BRIDGE_PORT 7777

// Runtime target. Overridden by sdmc:/tonic.cfg, a one-line text file "host:port" (e.g.
// "203.0.113.5:7777" or "myserver.example.com:7777" once DNS is handled), so the console can be
// pointed at ANY server without rebuilding the plugin. Missing/unparseable file = the defaults.
static char gHost[64] = BRIDGE_HOST;
static u16  gPort     = BRIDGE_PORT;

static bool gUnderAzahar;
#define PLG_STACK_SIZE 0x4000
static u8 stack[PLG_STACK_SIZE] __attribute__((aligned(8)));
static Handle thread;

// Normally provided by the 3dsx crt0; NULL = "no homebrew env, use real srv"
void *__service_ptr = NULL;

extern char *fake_heap_start;
extern char *fake_heap_end;
extern u32 __ctru_heap;
extern u32 __ctru_linear_heap;
u32 __ctru_heap_size = 0;
u32 __ctru_linear_heap_size = 0;

void __system_allocateHeaps(PluginHeader *header) {
    __ctru_heap_size = header->heapSize;
    __ctru_heap = header->heapVA;
    fake_heap_start = (char *)__ctru_heap;
    fake_heap_end = fake_heap_start + __ctru_heap_size;
}

#define LOG_PATH "/tonic.log"

void logLine(const char *text) {
    FS_Archive sd;
    if (R_FAILED(FSUSER_OpenArchive(&sd, ARCHIVE_SDMC, fsMakePath(PATH_EMPTY, "")))) return;
    Handle f;
    // OPEN_APPEND is not a thing here; open for write and seek to the end instead.
    if (R_SUCCEEDED(FSUSER_OpenFile(&f, sd, fsMakePath(PATH_ASCII, LOG_PATH),
                                    FS_OPEN_WRITE | FS_OPEN_CREATE, 0))) {
        u64 size = 0;
        FSFILE_GetSize(f, &size);
        u32 written = 0;
        FSFILE_Write(f, &written, size, text, strlen(text), FS_WRITE_FLUSH);
        FSFILE_Close(f);
    }
    FSUSER_CloseArchive(sd);
}

// Reads sdmc:/tonic.cfg ("host:port") into gHost/gPort. Returns 1 if a config was applied.
static int readBridgeConfig(void) {
    FS_Archive sd; Handle f; int applied = 0;
    if (R_FAILED(FSUSER_OpenArchive(&sd, ARCHIVE_SDMC, fsMakePath(PATH_EMPTY, "")))) return 0;
    if (R_SUCCEEDED(FSUSER_OpenFile(&f, sd, fsMakePath(PATH_ASCII, "/tonic.cfg"), FS_OPEN_READ, 0))) {
        char buf[128]; u32 got = 0;
        if (R_SUCCEEDED(FSFILE_Read(f, &got, 0, buf, sizeof(buf) - 1)) && got) {
            buf[got] = 0;
            // first line only; trim trailing whitespace/CR/LF
            char *nl = strpbrk(buf, "\r\n"); if (nl) *nl = 0;
            char *colon = strrchr(buf, ':');
            if (colon && colon != buf) {
                long port = strtol(colon + 1, NULL, 10);
                u32 hl = (u32)(colon - buf);
                if (port > 0 && port < 65536 && hl < sizeof(gHost)) {
                    memcpy(gHost, buf, hl); gHost[hl] = 0;
                    gPort = (u16)port; applied = 1;
                }
            }
        }
        FSFILE_Close(f);
    }
    FSUSER_CloseArchive(sd);
    return applied;
}

// SOC needs a memory block it can use for the socket service, 0x1000-aligned.
#define SOC_ALIGN      0x1000
#define SOC_BUFFERSIZE 0x20000   // 128 KB; 1 MB out of a 2 MB plugin heap was optimistic
static u32 *socBuffer;

/**
 * Opens a TCP connection to the bridge and closes it again, reporting what happened.
 *
 * This is the whole of step 2's first increment. FTP proves the Mac can reach the console; it does
 * NOT prove the console can reach the Mac, and campus networks are quite capable of allowing one
 * direction only. Testing that with twenty lines beats discovering it after porting the tunnel.
 */
static void probeBridge(void) {
    // Allocate from the PROCESS heap, not the plugin's. socInit hands this buffer to
    // svcCreateMemoryBlock, and the kernel will only back a memory block with heap memory —
    // memalign here returns PLGLDR's own region (0x06001000), which it refuses, giving
    // socInit failed (0xE0A01BF5) regardless of how small the request is.
    // Back to the plugin's own heap, but with UsePrivateMemory set in the plgInfo. The process
    // heap is not an option: its address space has a 120 MB hole, but svcControlMemory there fails
    // 0xD86007F3 (out of memory) because a 3DS application has a fixed memory quota and Minecraft
    // uses all of it. Private plugin memory should be heap-backed, which is what
    // svcCreateMemoryBlock requires and what the default mapping evidently is not.
    socBuffer = (u32 *)memalign(SOC_ALIGN, SOC_BUFFERSIZE);
    if (!socBuffer) { logLine("tonic: memalign for SOC failed\n"); return; }
    {
        char m[96];
        sprintf(m, "tonic: soc buffer at %p (private memory)\n", (void *)socBuffer);
        logLine(m);
    }
    Result r = socInit(socBuffer, SOC_BUFFERSIZE);
    if (R_FAILED(r)) {
        char line[80];
        sprintf(line, "tonic: socInit failed (0x%08lX) — no network\n", (unsigned long)r);
        logLine(line);
        return;
    }

    char line[160];
    int cfg = readBridgeConfig();
    sprintf(line, "tonic: bridge target %s:%u (%s)\n", gHost, (unsigned)gPort, cfg ? "from tonic.cfg" : "built-in default");
    logLine(line);
    if (!tunnelConnect(gHost, gPort)) {
        logLine("tonic: could not reach the bridge\n");
        socExit();
        return;
    }
    logLine("tonic: tunnel up\n");

    // The same exchange Azahar's shim makes when the player opens the server list. If a beacon
    // comes back, the tunnel is carrying real traffic in both directions and the remaining work is
    // answering UDS commands with it.
    tunnelSendScan();
    static u8 beacon[512];
    u32 n = tunnelWaitBeacon(beacon, sizeof(beacon), 2000);
    if (n) {
        sprintf(line, "tonic: BEACON %lu bytes: %02x%02x%02x%02x%02x%02x%02x%02x\n",
                (unsigned long)n, beacon[0], beacon[1], beacon[2], beacon[3],
                beacon[4], beacon[5], beacon[6], beacon[7]);
        logLine(line);
        // The host name sits at 0x10 in the application data — print it as a readability check
        // that we are looking at the same bytes the bridge logs.
        char name[17];
        memcpy(name, beacon + 0x10, 16);
        name[16] = 0;
        sprintf(line, "tonic: beacon host name = \"%s\"\n", name);
        logLine(line);
        hookSetAppData(beacon, n);   // the join-list name blob the answerer will serve
    } else {
        logLine("tonic: no beacon within 2s\n");
    }
}


static void ThreadMain(void *arg) {
    (void)arg;
    fsInit();
    logLine(gUnderAzahar ? "tonic: plugin loaded and running inside the game (azahar's loader)\n"
                         : "tonic: plugin loaded and running inside the game\n");
    probeBridge();

    // Scan and patch from here, not from main(): at plugin load the game's code pages may not
    // be populated yet, and a scan then finds nothing without ever faulting — which is what the
    // first attempt reported.
    svcSleepThread(2000ULL * 1000 * 1000);
    hookInstall();
    {
        static char st[1024];
        hookStatus(st, sizeof(st));   // includes stage + veneer address even after a partial run
        logLine(st);
    }
    // Flush what the hook saw once a second; heartbeat every ten so liveness stays visible.
    static char buf[2048];
    for (u32 i = 1;; i++) {
        svcSleepThread(1000ULL * 1000 * 1000);
        u32 n = hookDrain(buf, sizeof(buf) - 1);
        if (n) { buf[n] = 0; logLine(buf); }
        if (i % 10 == 0) {
            char line[48];
            sprintf(line, "tonic: alive, %lus\n", (unsigned long)i);
            logLine(line);
        }
    }
}

void main(void) {
    PluginHeader *header = (PluginHeader *)0x07000000;
    // Luma writes "3GX$" here. Azahar's 3GX loader fills the header (version, heapVA at
    // 0x06000000, heapSize) but never the magic, so accept a magic-less header that otherwise
    // looks right instead of silently doing nothing in the emulator.
    bool luma = header->magic == HeaderMagic;
    bool azahar = header->magic == 0 && header->version != 0
               && header->heapVA == 0x06000000 && header->heapSize != 0;
    if (!luma && !azahar) return;
    gUnderAzahar = azahar;
    __system_allocateHeaps(header);
    srvInit();
    plgLdrInit();
    svcCreateThread(&thread, ThreadMain, 0, (u32 *)(stack + PLG_STACK_SIZE), 30, -1);
}
