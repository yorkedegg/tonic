// Tonic step 3b: watch the UDS command stream, via per-site veneers.
//
// The game inlines svcSendSyncRequest; there is no shared stub to replace. Instead each of the 25
// call sites that use the UDS handle global (0x00A35E00, identified offline against the exact
// running .code) is redirected to a veneer in plugin memory that records the request and then runs
// the original two instructions untouched. Because every one of these sites is already UDS, no
// runtime handle filtering is needed — every event is a real UDS command.
//
// The veneer, generated per site (INS = the instruction the patch clobbers, R = return address):
//     stmfd sp!, {r0-r12, lr}      ; ldr r0, [sp] (= handle) ; bl udsObserve ; ldmfd sp!, {r0-r12, lr}
//     svc 0x32 ; <INS> ; ldr pc, [pc, #-4] ; .word R
// verified byte-for-byte with a disassembler before ever reaching hardware.
#include <3ds.h>
#include <string.h>
#include <stdio.h>
#include "csvc.h"
#include "hook.h"
#include "beacon.h"
#include "tunnel.h"

#include "sites.h"
#define NSITES (sizeof(SITES) / sizeof(SITES[0]))
#define VWORDS 12
/* The tail of the game's last executable page: 2012 bytes of zero padding after the final "bx lr"
 * at 0x00918820, running to the page end at 0x00919000. 156 stubs need 1248 of them. Verified
 * empty at run time before anything is written, so a different binary refuses instead of corrupting
 * itself. */
#define THUNKS 0x00918840u                                  // words per veneer (11 used, padded)

static u32 veneers[NSITES * VWORDS] __attribute__((aligned(32)));
static u32 nInstalled;
static u32 firstWords[4];
u32 veneerBase;
u32 flushRes[2];
#define MAXUH 32
static u32 udsHandles[MAXUH];                       // the game opens more than one nwm::UDS session
static u32 nUdsHandles, udsNext;                    // a ring: see addUdsHandle
static u32 udsHandle;                               // most-recent, for logging only
static int isUdsHandle(u32 h) { for (u32 i = 0; i < nUdsHandles; i++) if (udsHandles[i] == h) return 1; return 0; }
// Every join opens two fresh nwm::UDS sessions. The old fixed set of 8 silently DROPPED the ninth
// handle, so from the fifth join in one boot (or the first rejoin after a tunnel reset) every UDS
// command on that session fell through to the real, never-initialised service: the
// pc=0x0011FF0C / far=0x1423 crash, back again. Now a ring that recycles the OLDEST entry. Stale
// values are harmless: the kernel never reuses a handle value (the high bits are a generation
// counter), so a recycled slot can only ever have matched a session that is already gone.
static void addUdsHandle(u32 h) {
    if (isUdsHandle(h)) return;
    udsHandles[udsNext] = h; udsNext = (udsNext + 1) % MAXUH;
    if (nUdsHandles < MAXUH) nUdsHandles++;
    udsHandle = h;
}
static u32 answerMask = 0x003E;                    // +bit5 = answer InitializeWithVersion (keep Wi-Fi up)
static volatile u32 connected;                     // set once we've answered ConnectToNetwork
static u16 myNodeId = 2;
static u8  gMaxNodes = 2;
static volatile u32 answeredConnect, answeredStatus, answeredPull, answeredPullData, answeredSend, answeredInit, answeredStub, answeredNdm;
static volatile u16 lastNdmCmd;
static volatile u32 lastExclState = 0xFFFFFFFF;
static Handle gConnEvent, gBindEvent;
static volatile u16 lastStubCmd;
static volatile u32 answeredEmpty, answeredFull, answeredDecrypt;
static u8  gApp[256];                              // host beacon app-data (the join-list name blob)
static u32 gAppLen;
void hookSetAppData(const u8 *d, u32 n) { if (n > sizeof(gApp)) n = sizeof(gApp); memcpy(gApp, d, n); gAppLen = n; }

typedef struct { u16 site; u16 nwords; u32 handle; u32 w[24]; } Event;
#define RING 128
static Event ring[RING];
static u32 rHead, rCount;
static LightLock rLock;

// Runs on the GAME's threads, between two of its instructions. No IPC, no blocking — just the ring.
// `site` is the index into SITES; cluster sites are UDS by construction and teach us the handle,
// everything else is kept only if it uses that same handle (Shutdown/Unbind/... live elsewhere).
static volatile u32 gcsCount;                       // GetConnectionStatus is polled constantly; count, don't log

// Runs on the GAME's threads, wedged between two of its instructions. `saved` points at the
// {r0..r12,lr} the veneer pushed (saved[0] = the value r0 held at the site = the session handle).
// Returns 1 to have the veneer SKIP the real svc — in that case we have already forged the reply
// into the TLS command buffer and the output buffer, and set saved[0]=0 (transport success).
// The tunnel died under a live session (server restart, network drop: send() -> ECONNRESET).
// Stop claiming to be connected: the next GetConnectionStatus reports NotConnected and the
// status event fires, so the game leaves cleanly ("connection lost") instead of spinning until
// RakNet gives up and then churning fresh sessions. The next ConnectToNetwork reconnects.
static void noteTunnelDead(void) {
    if (connected && !tunnelActive()) {
        connected = 0;
        if (gConnEvent) svcSignalEvent(gConnEvent);
        logLine("tonic: tunnel died under a live session -> reporting NotConnected\n");
    }
}

u32 udsAnswer(u32 *saved, u32 site) {
    u32 handle = saved[0];
    u32 *cmd = getThreadCommandBuffer();

    // Keep internet Wi-Fi alive. A 3DS game reserves the single radio for local wireless via
    // ndm:u EnterExclusiveState / SuspendDaemons / OverrideDefaultDaemons, which tears down the
    // infrastructure connection (send() then fails ENETDOWN=115). We fake UDS entirely, so the radio
    // must stay in infra mode: neutralise those calls (report success, do nothing). Matched by the
    // exact call-site address found offline, so no other service is touched.
    // ndm:u EnterExclusiveState(state) reserves the radio. state==2 (LOCAL_COMMUNICATIONS) is the one
    // that disconnects internet Wi-Fi; NONE/INFRASTRUCTURE (0/1) are routine and must pass through.
    // SuspendDaemons is ALSO used routinely at the title screen, so we must NOT touch it.
    u32 addr = (site < NSITES) ? SITES[site] : 0;
    if (addr == 0x004BBB10 || addr == 0x004BBC64) {    // ndm:u EnterExclusiveState
        lastExclState = cmd[1];
        if (cmd[1] == 2) {                             // LOCAL_COMMUNICATIONS: keep infra Wi-Fi
            cmd[0] = 0x00010040u;                      // result-only reply
            cmd[1] = 0;                                // success
            saved[0] = 0; answeredNdm++; lastNdmCmd = (u16)addr;
            return 1;
        }
    }
    if (site < NSITES && IS_UDS[site]) addUdsHandle(handle);  // cluster sites are definitively nwm::UDS
    else if (!isUdsHandle(handle)) return 0;                  // another service (APT etc.): leave it alone
    u32 cmdid = (cmd[0] >> 16) & 0xFFFF;
    if (cmdid == 0x0003 || cmdid == 0x000A) connected = 0;   // Shutdown / DisconnectNetwork

    // ---- GetConnectionStatus: once "connected", report it ourselves (real UDS never saw the join) ----
    if (cmdid == 0x0014 || cmdid == 0x0017) { /* too high-rate to log; counted in the summary */ }
    else if (cmdid == 0x000B) {
        gcsCount++;
        noteTunnelDead();
        if (gConnEvent) {                           // we own the session: never touch real nwm
            cmd[0] = 0x000B0340u;                   // reply: cmd 0x0B, 13 normal, 0 translate
            cmd[1] = 0;                             // ResultSuccess
            volatile u8 *st = (volatile u8 *)&cmd[2];   // ConnectionStatus (0x30)
            for (int i = 0; i < 0x30; i++) st[i] = 0;
            if (connected) {
                *(volatile u32 *)(st + 0x00) = 9;   // ConnectedAsClient
                *(volatile u32 *)(st + 0x04) = 1;   // ConnectionEstablished
                *(volatile u16 *)(st + 0x08) = myNodeId;
                *(volatile u16 *)(st + 0x0A) = 0x3;
                *(volatile u16 *)(st + 0x0C) = 1;
                *(volatile u16 *)(st + 0x0C + (myNodeId - 1) * 2) = myNodeId;
                st[0x2C] = 2; st[0x2D] = gMaxNodes;
                *(volatile u16 *)(st + 0x2E) = 0x3;
            } else {
                *(volatile u32 *)(st + 0x00) = 3;   // NotConnected
            }
            saved[0] = 0; answeredStatus++;
            return 1;
        }
        return 0;
    }
    // ---- observe: record for the log ----
    {
        LightLock_Lock(&rLock);
        u32 slot = (rHead + rCount) % RING;
        if (rCount == RING) { rHead = (rHead + 1) % RING; rCount--; }
        ring[slot].site = (u16)site; ring[slot].handle = handle;
        u32 normal = (cmd[0] >> 6) & 0x3F, xlate = cmd[0] & 0x3F;
        u32 nw = 1 + normal + xlate; if (nw > 24) nw = 24;
        ring[slot].nwords = (u16)nw;
        for (u32 i = 0; i < 24; i++) ring[slot].w[i] = cmd[i];  // TLS cmdbuf is 64 words; 24 is always safe
        rCount++;
        LightLock_Unlock(&rLock);
    }

    // ---- answer: RecvBeaconBroadcastData (0x000F0404) ----
    // Request layout confirmed from a live dump: 16 normal + 4 translate; the mapped output buffer
    // is the last two translate words (cmd[19]=descriptor 0x0001000C, cmd[20]=game VA). For now we
    // return a valid EMPTY scan result: writes the 12-byte header only, and crucially SKIPS the real
    // scan svc so the radio never leaves infrastructure mode and the tunnel survives.
    if ((answerMask & 0x0003u) && cmdid == 0x000F) {
        u32 outVA = cmd[20], reqSize = cmd[1], desc = cmd[19];
        if (outVA) {
            u32 wrote = 0;
            if ((answerMask & 0x0002u) && gAppLen)
                wrote = buildBeaconReply((u8 *)outVA, reqSize, reqSize, gApp, gAppLen);
            if (wrote) { answeredFull++; }
            else {
                /* No list to show (the bridge was unreachable, so no BEACON ever arrived, or it
                 * would not fit): answer an EMPTY scan. This used to fall through to the real nwm
                 * when bit 0 of answerMask was clear -- which it is by default -- and the real nwm
                 * had never been initialised because we answered InitializeWithVersion ourselves.
                 * That is the data abort at 0x1378 inside nwm that two users hit the moment they
                 * opened Multiplayer with a wrong bridge address in tonic.cfg. Once init has been
                 * bypassed, no UDS command may ever reach nwm; an empty list is the right answer. */
                volatile u32 *ob = (volatile u32 *)outVA;
                ob[0] = reqSize; ob[1] = 12; ob[2] = 0;
                answeredEmpty++;
                if (answeredEmpty == 1 && !gAppLen)
                    logLine("tonic: scan answered EMPTY - no beacon from the bridge yet (is tonic.cfg right and the server up?)\n");
            }
            cmd[0] = 0x000F0042u;                       // reply: cmd 0x0F, 1 normal, 2 translate
            cmd[1] = 0;                                 // ResultSuccess
            cmd[2] = desc;                              // echo mapped-buffer descriptor
            cmd[3] = outVA;                             // echo buffer VA
            saved[0] = 0;                               // transport success
            return 1;
        }
    }

    // ---- answer: Bind (0x12) — success + a data-ready event the game can wait on ----
    if (gConnEvent && cmdid == 0x0012) {
        if (!gBindEvent) svcCreateEvent(&gBindEvent, RESET_ONESHOT);
        cmd[0] = 0x00120042u;                       // 1 normal, 2 translate (copy 1 handle)
        cmd[1] = 0; cmd[2] = 0x00000000u; cmd[3] = gBindEvent;
        saved[0] = 0; answeredStub++;
        return 1;
    }
    // ---- answer: GetChannel (0x1A) — report the network channel ----
    if (gConnEvent && cmdid == 0x001A) {
        cmd[0] = 0x001A0080u;                       // 2 normal, 0 translate
        cmd[1] = 0; cmd[2] = 11;                    // DefaultNetworkChannel
        saved[0] = 0; answeredStub++;
        return 1;
    }

    // ---- answer: InitializeWithVersion (0x1B) / Initialize (0x01) ----
    // Passing this to real UDS makes the console tear down internet Wi-Fi to bring up local wireless
    // (one radio), which kills the tunnel. So we initialise a fake UDS ourselves. The plugin runs in
    // the game's process, so an event we svcCreateEvent is directly usable by the game as the
    // connection-status event; we signal it on connect. Reply: result + one copied handle.
    if ((answerMask & 0x0020u) && (cmdid == 0x001B || cmdid == 0x0001)) {
        if (!gConnEvent) svcCreateEvent(&gConnEvent, RESET_ONESHOT);
        cmd[0] = (cmdid << 16) | 0x0042u;           // 1 normal, 2 translate (copy 1 handle)
        cmd[1] = 0;                                 // ResultSuccess
        cmd[2] = 0x00000000u;                       // copy-handles descriptor (1 handle)
        cmd[3] = gConnEvent;                        // usable directly: same process as the game
        saved[0] = 0; answeredInit++;
        return 1;
    }

    // ---- answer: PullPacket (0x001400C0) — deliver a tunnel DATA frame to the game ----
    // 3 normal params: bind_node_id, max_out_aligned (<<2, capped 0x172), max_out_size. Output goes
    // to the game's registered static buffer (id 0). Reply: result, size, src_node, static buffer.
    if ((answerMask & 0x0010u) && cmdid == 0x0014) {
        noteTunnelDead();
        u32 maxAligned = cmd[2], maxSize = cmd[3];
        u32 buffSize = (maxAligned < 0x172 ? maxAligned : 0x172) << 2;
        u32 cap = buffSize < maxSize ? buffSize : maxSize;
        u32 *sb = getThreadStaticBuffers();
        u32 outDesc = sb[0], outVA = sb[1];
        u8 src = 1, ch = 0; u32 got = 0;
        if (outVA && cap) got = tunnelPollData((u8 *)outVA, cap, &src, &ch, 0);
        cmd[0] = 0x001400C2u;                       // reply: cmd 0x14, 3 normal, 2 translate
        cmd[1] = 0;                                 // ResultSuccess
        cmd[2] = got;                               // packet size
        cmd[3] = src;                               // src_node_id
        cmd[4] = outDesc;                           // static-buffer descriptor
        cmd[5] = outVA;                             // static-buffer address
        saved[0] = 0; answeredPull++; if (got) answeredPullData++;
        return 1;
    }

    // ---- answer: SendTo (0x00170182) — forward the game's outbound data to the tunnel ----
    // Layout (from the IPC header 6 normal + 2 translate): cmd[2]=dest, cmd[3]=channel, cmd[5]=size,
    // cmd[6]=flags, cmd[7]=in-buffer descriptor, cmd[8]=in-buffer VA. Reply: just success.
    if ((answerMask & 0x0010u) && cmdid == 0x0017) {
        noteTunnelDead();
        u8 ch = (u8)cmd[3]; u32 dsize = cmd[5], dataVA = cmd[8];
        if (dataVA && dsize && dsize <= 1500) {
            static u8 tx[1600];
            for (u32 i = 0; i < dsize; i++) tx[i] = *(volatile u8 *)(dataVA + i);
            tunnelSendData(ch, tx, dsize);
        }
        cmd[0] = 0x00170040u;                       // reply: cmd 0x17, 1 normal, 0 translate
        cmd[1] = 0;
        saved[0] = 0; answeredSend++;
        return 1;
    }

    // ---- answer: ConnectToNetwork (0x001E0084) ----
    // 2 normal (type, passphrase len) + NetworkInfo static buffer (cmd[4]) + passphrase (cmd[6]).
    // We can't join a real local-wireless net; tell the bridge over the tunnel and report success,
    // exactly as the emulator shim does. The game then polls GetConnectionStatus (answered above).
    if ((answerMask & 0x0008u) && cmdid == 0x001E) {
        u32 netVA = cmd[4], passVA = cmd[6], passLen = cmd[2];
        if (netVA) { u8 m = *(volatile u8 *)(netVA + 0x1D); if (m) gMaxNodes = m; }
        u8 pass[64]; if (passLen > sizeof(pass)) passLen = sizeof(pass);
        for (u32 i = 0; i < passLen; i++) pass[i] = *(volatile u8 *)(passVA + i);
        // The startup socket is normally still alive here (the bridge keeps it), and the plugin's
        // small SOC buffer only allows one socket at a time — closing it to reopen fails. So reuse it,
        // and only reconnect if it has actually gone down.
        bool act = tunnelActive();
        if (!act) act = tunnelReconnect();
        { char lg[80]; sprintf(lg, "tonic: connect: tunnel %s\n", act ? "up" : "DOWN (reconnect failed)"); logLine(lg); }
        tunnelSendConnect(pass, passLen);           // bridge creates a fresh RakNet session
        myNodeId = 2; connected = 1;
        if (gConnEvent) svcSignalEvent(gConnEvent);  // wake the game's status wait
        cmd[0] = 0x001E0040u;                       // reply: cmd 0x1E, 1 normal, 0 translate
        cmd[1] = 0;                                 // ResultSuccess
        saved[0] = 0; answeredConnect++;
        return 1;
    }

    // ---- answer: DecryptBeaconData (0x001F0006) ----
    // 3 input static buffers (cmd[2]=NetworkInfo 0x108, cmd[4]=enc0, cmd[6]=enc1); the decrypted
    // NodeInfo list is written to the game's pre-registered output static buffer (id 0), found in
    // the thread static-buffer area (TLS+0x180). We can't run the real crypto (secret key), so we
    // synthesise the node list directly: one host node, id 1, named from the beacon.
    // Shutdown/Disconnect: drop our session state, then fall through to the generic stub.
    if (cmdid == 0x0003 || cmdid == 0x000A) { connected = 0; }

    if ((answerMask & 0x0004u) && cmdid == 0x001F) {
        u32 *sb = getThreadStaticBuffers();         // sb[0]=descriptor, sb[1]=address (buffer id 0)
        u32 outDesc = sb[0], outVA = sb[1], outSize = outDesc >> 14;
        u32 netVA = cmd[2];
        if (outVA && outSize >= 0x28) {
            u8 maxNodes = netVA ? *(volatile u8 *)(netVA + 0x1D) : 1;
            if (maxNodes == 0 || maxNodes > 16) maxNodes = 2;
            u32 total = (u32)maxNodes * 0x28;       // sizeof(NodeInfo) == 0x28
            if (total > outSize) total = outSize;
            volatile u8 *o = (volatile u8 *)outVA;
            for (u32 i = 0; i < total; i++) o[i] = 0;
            // node[0] = host: network_node_id (offset 0x20) = 1, username "Tonic" (UTF-16LE @0x08)
            o[0x20] = 1; o[0x21] = 0;
            static const char nm[] = "Tonic";
            for (u32 i = 0; nm[i] && i < 10; i++) { o[0x08 + i*2] = (u8)nm[i]; o[0x09 + i*2] = 0; }
            cmd[0] = 0x001F0042u;                   // reply: cmd 0x1F, 1 normal, 2 translate (static buffer)
            cmd[1] = 0;                             // ResultSuccess
            cmd[2] = outDesc;                       // echo static-buffer descriptor
            cmd[3] = outVA;                         // echo output address
            saved[0] = 0;
            answeredDecrypt++;
            return 1;
        }
    }

    // Catch-all: we own the UDS session, so any command we did not specifically answer must still be
    // kept away from the bypassed nwm (which has no session state and would fault). Forge a bare
    // success reply and record the id so we learn which commands still need real answers.
    if (gConnEvent) {
        cmd[0] = (cmdid << 16) | 0x0040u;           // 1 normal (result), 0 translate
        cmd[1] = 0;
        saved[0] = 0;
        answeredStub++;
        lastStubCmd = (u16)cmdid;
        return 1;
    }
    return 0;
}

// Set by hookInstall so the worker can report where it got to if it faults.
volatile u32 hookStage;

void hookInstall(void) {
    LightLock_Init(&rLock);
    volatile u32 *code = (volatile u32 *)0x00100000u;
    for (int i = 0; i < 4; i++) firstWords[i] = code[i];

    hookStage = 1;
    // Three crashes in a row were NOT unmapped memory: every dump had pc=0xFFF2B3F8 in SVC mode,
    // faulting on "mcr p15,0,r1,c7,c10,1" (clean D-cache line by MVA) with the current process
    // named "hid"/"gpio". That is Luma's svcFlushDataCacheRange (0x91): it enters the kernel's
    // internal range-flush 3 instructions in, skipping the process-context handling, and the MVA
    // loop is run on the *other* core too, where a sysmodule is current and 0x06xxxxxx/0x07xxxxxx
    // is simply not mapped -> section translation fault. The writes themselves had all succeeded.
    // The official svcFlushProcessDataCache (what CTRPF uses for every code patch) does the same
    // work inside the process's own context; the I-cache is invalidated wholesale, exactly as
    // Rosalina does after patching a game (svcInvalidateEntireInstructionCache, no MVAs at all).
    // Refuse to patch a binary this table was not made for. Every site must hold the svc 0x32 the
    // patch replaces (the table is from the USA v9.12.0 .code); on any other region or update the
    // addresses land on unrelated instructions and the game dies the moment one runs. A friend's
    // azahar with only the base v0.1.0 .cci and no update installed was exactly this.
    /* Fingerprint the build before anything else. The site check below only asks whether each
     * address happens to hold an svc 0x32, and svcSendSyncRequest is inlined all over this game --
     * a DIFFERENT build can pass that test with all 156 sites landing on unrelated syscalls. Then we
     * answer requests that were never uds and let real ones through half-formed, and the nwm
     * sysmodule dereferences a context it never initialised and takes a data abort. That is not a
     * theoretical worry: a user hit exactly that, crashing in nwm the moment they opened
     * multiplayer. Four words is enough to tell the right .code from a stranger. */
    {
        static const u32 EXPECT[4] = {0xEB000007u, 0xEB001553u, 0xEB008F8Au, 0xEB00156Eu};
        if (firstWords[0] != EXPECT[0] || firstWords[1] != EXPECT[1] ||
            firstWords[2] != EXPECT[2] || firstWords[3] != EXPECT[3]) {
            char line[176];
            sprintf(line, "tonic: WRONG GAME BUILD - text starts %08lX %08lX %08lX %08lX\n",
                    (unsigned long)firstWords[0], (unsigned long)firstWords[1],
                    (unsigned long)firstWords[2], (unsigned long)firstWords[3]);
            logLine(line);
            sprintf(line, "tonic:   expected %08lX %08lX %08lX %08lX (USA 00040000001B8700 on update v9.12.0)\n",
                    (unsigned long)EXPECT[0], (unsigned long)EXPECT[1],
                    (unsigned long)EXPECT[2], (unsigned long)EXPECT[3]);
            logLine(line);
            logLine("tonic:   a EUR or JPN copy, or a different update, needs its own site table.\n");
            logLine("tonic:   not patching - the game runs untouched, multiplayer will just do a real scan.\n");
            hookStage = 9;
            return;
        }
    }
    {
        u32 bad = 0, firstBad = 0;
        for (u32 s = 0; s < NSITES; s++) {
            if (*(volatile u32 *)SITES[s] != 0xEF000032u) { if (!bad) firstBad = SITES[s]; bad++; }
        }
        if (bad) {
            char line[160];
            sprintf(line, "tonic: WRONG GAME BINARY - %lu/%lu hook sites are not svc 0x32 (first bad 0x%08lX)\n",
                    (unsigned long)bad, (unsigned long)NSITES, (unsigned long)firstBad);
            logLine(line);
            sprintf(line, "tonic:   text starts %08lX %08lX %08lX %08lX. tonic needs the USA copy on the v9.12.0 update\n",
                    (unsigned long)firstWords[0], (unsigned long)firstWords[1],
                    (unsigned long)firstWords[2], (unsigned long)firstWords[3]);
            logLine(line);
            logLine("tonic:   (on azahar: install the update cia too). not patching, the game runs untouched.\n");
            hookStage = 9;
            return;
        }
    }
    veneerBase = (u32)veneers;
    hookStage = 2;

    /* Patch each site with ONE word, not two. This is the whole fix for the start-up hang.
     *
     * The old patch wrote a pair: "ldr pc,[pc,#-4]" over the svc, and the veneer's address into the
     * word after it. Those two stores cannot be made simultaneous, and if a core refetched that
     * cache line in between it saw the game's original svc followed by an address where an
     * instruction should be. That is exactly the reported crash -- UndefinedInstruction at
     * 0x004AD5EC, which is site 0x004AD5E8 plus four. Two stores, two chances, 156 sites, every
     * launch: it hung or crashed far more often than it had any right to, and it moved around
     * whenever unrelated code changed the layout, because the layout decides what that stray word
     * decodes to.
     *
     * A branch reaches only +-32MB, so it cannot reach a veneer in plugin memory 112MB away. But it
     * does not have to: it only has to reach a two-word stub, and the game's own .text ends at
     * 0x00918824 with 2012 bytes of zero padding running to the end of that executable page. That
     * is room for all 156 stubs with space left over. So each site becomes a single "b stub", the
     * stub does the far jump to the veneer, and one aligned 32-bit store is either seen or not
     * seen. There is no half-patched state left to race with.
     *
     * Patch EVERY site, still. Narrowing this was a mistake twice: the disconnect path runs through
     * a site outside the uds cluster, and skipping it crashed the console on Disconnect every time.
     */
    for (u32 s = 0; s < NSITES; s++) {
        if (*(volatile u32 *)(THUNKS + s * 8) || *(volatile u32 *)(THUNKS + s * 8 + 4)) {
            logLine("tonic: hook: the .text padding is not empty - refusing to patch\n");
            hookStage = 8;
            return;
        }
    }
    for (u32 s = 0; s < NSITES; s++) {
        u32 *v = &veneers[s * VWORDS];
        u32 blOff = ((u32)&udsAnswer - ((u32)&v[3] + 8)) >> 2;
        v[0]  = 0xE92D5FFFu;                        // stmfd sp!, {r0-r12, lr}
        v[1]  = 0xE1A0000Du;                        // mov r0, sp     (&saved; saved[0] = handle)
        v[2]  = 0xE3A01000u | (s & 0xFF);           // mov r1, #site index
        v[3]  = 0xEB000000u | (blOff & 0xFFFFFF);   // bl udsAnswer   (r0 = 0 pass / 1 handled)
        v[4]  = 0xE3500000u;                        // cmp r0, #0
        v[5]  = 0xE8BD5FFFu;                        // ldmfd sp!, {r0-r12, lr}   (flags preserved)
        v[6]  = 0x1A000000u;                        // bne +0 -> v[8], skipping the svc (handled)
        v[7]  = 0xEF000032u;                        // svc 0x32       (pass-through: real request)
        v[8]  = 0xE51FF004u;                        // ldr pc, [pc, #-4]
        v[9]  = SITES[s] + 4;                       // back to the instruction after the svc
        v[10] = v[11] = 0;

        volatile u32 *t = (volatile u32 *)(THUNKS + s * 8);
        t[0] = 0xE51FF004u;                         // ldr pc, [pc, #-4]  (the far jump)
        t[1] = (u32)v;
    }
    {   // make veneers and stubs real, executable code before a single site points at them
        Result r = svcFlushProcessDataCache(CUR_PROCESS_HANDLE, (u32)veneers, sizeof(veneers));
        if (R_FAILED(r)) flushRes[0] = (u32)r;
        r = svcFlushProcessDataCache(CUR_PROCESS_HANDLE, THUNKS, NSITES * 8);
        if (R_FAILED(r) && !flushRes[0]) flushRes[0] = (u32)r;
        svcInvalidateEntireInstructionCache();
    }
    hookStage = 3;

    for (u32 s = 0; s < NSITES; s++) {
        volatile u32 *at = (volatile u32 *)SITES[s];
        u32 off = ((THUNKS + s * 8) - (SITES[s] + 8)) >> 2;
        Result r;
        at[0] = 0xEA000000u | (off & 0x00FFFFFFu);  // b stub -- one store, no in-between state
        r = svcFlushProcessDataCache(CUR_PROCESS_HANDLE, SITES[s], 4);
        if (R_FAILED(r) && !flushRes[1]) flushRes[1] = (u32)r;
        nInstalled++;
    }
    hookStage = 5;
    // Same sequence CTRPF's HookManager uses after every hook (its Luma range calls are commented
    // out there for a reason). No svcFlushEntireDataCache: CTRPF never calls it, and it is only
    // the range routine with len=-1 underneath.
    svcInvalidateEntireInstructionCache();
    hookStage = 4;
    {   // safe to touch the filesystem again: every site and every veneer is coherent now
        char line[112];
        sprintf(line, "tonic: hook: %lu sites live, veneers @0x%08lX, icache invalidated\n",
                (unsigned long)nInstalled, (unsigned long)veneerBase);
        logLine(line);
    }
}

extern volatile u32 hookStage;
u32 hookStatus(char *out, u32 max) {
    return snprintf(out, max,
        "tonic: hook: stage %lu, veneers@0x%08lX, flush=%08lX/%08lX; text = %08lX %08lX %08lX %08lX; patched %lu/%lu\n",
        (unsigned long)hookStage, (unsigned long)veneerBase, (unsigned long)flushRes[0], (unsigned long)flushRes[1],
        (unsigned long)firstWords[0], (unsigned long)firstWords[1],
        (unsigned long)firstWords[2], (unsigned long)firstWords[3],
        (unsigned long)nInstalled, (unsigned long)NSITES);
}

u32 hookDrain(char *out, u32 max) {
    static const struct { u16 id; const char *name; } NM[] = {
        {0x01,"Initialize"},{0x03,"Shutdown"},{0x05,"EjectClient"},{0x06,"EjectSpectator"},{0x07,"UpdateNetAttr"},
        {0x08,"DestroyNetwork"},{0x0A,"DisconnectNetwork"},{0x0B,"GetConnectionStatus"},{0x0D,"GetNodeInformation"},
        {0x0F,"RecvBeaconBroadcastData"},{0x10,"SetApplicationData"},{0x11,"GetApplicationData"},{0x12,"Bind"},
        {0x13,"Unbind"},{0x14,"PullPacket"},{0x15,"SetMaxSendDelay"},{0x17,"SendTo"},{0x19,"Flush?"},{0x1A,"GetChannel"},
        {0x1B,"InitializeWithVersion"},{0x1D,"BeginHostingNetwork"},{0x1E,"ConnectToNetwork"},{0x1F,"DecryptBeaconData"},
        {0x21,"cmd21"},{0,0}};
    u32 n = 0;
    static u32 reported, lastGcs;
    if (udsHandle && reported != udsHandle) {
        reported = udsHandle;
        n += snprintf(out + n, max - n, "tonic: UDS session handle learned: 0x%08lX\n", (unsigned long)udsHandle);
    }
    if (gcsCount != lastGcs) {
        n += snprintf(out + n, max - n, "tonic: UDS GetConnectionStatus x%lu\n", (unsigned long)(gcsCount - lastGcs));
        lastGcs = gcsCount;
    }
    static u32 lastFull, lastEmpty;
    if (answeredFull != lastFull) {
        n += snprintf(out + n, max - n, "tonic: ANSWERED RecvBeacon x%lu WITH 1 HOST ENTRY (%lu B appdata)\n",
                      (unsigned long)(answeredFull - lastFull), (unsigned long)gAppLen);
        lastFull = answeredFull;
    }
    if (answeredEmpty != lastEmpty) {
        n += snprintf(out + n, max - n, "tonic: ANSWERED RecvBeacon x%lu empty\n",
                      (unsigned long)(answeredEmpty - lastEmpty));
        lastEmpty = answeredEmpty;
    }
    static u32 lastPull, lastSend;
    if (answeredPull != lastPull) {
        u32 rb = 0, rf = 0, se = 0; int le = 0; tunnelStats(&rb, &rf, &se, &le);
        n += snprintf(out + n, max - n,
                      "tonic: PullPacket x%lu (%lu DATA) | SendTo x%lu | tunnel rx=%lu bytes/%lu frames"
                      " txerr=%lu txdrop=%lu\n",
                      (unsigned long)(answeredPull - lastPull), (unsigned long)answeredPullData,
                      (unsigned long)answeredSend, (unsigned long)rb, (unsigned long)rf,
                      (unsigned long)se, (unsigned long)tunnelTxDropped());
        if (se) { n += snprintf(out + n, max - n, "tonic: tunnel last send errno=%d, %lu handles\n", le, (unsigned long)nUdsHandles); }
        lastPull = answeredPull; lastSend = answeredSend;
    }
    static u32 seenExcl = 0xFFFFFFFF;
    if (lastExclState != seenExcl) {
        n += snprintf(out + n, max - n, "tonic: ndm EnterExclusiveState(state=%lu) seen\n", (unsigned long)lastExclState);
        seenExcl = lastExclState;
    }
    static u32 lastNdmN;
    if (answeredNdm != lastNdmN) {
        n += snprintf(out + n, max - n, "tonic: NEUTRALIZED Wi-Fi-teardown calls x%lu (last site 0x%04X)\n",
                      (unsigned long)(answeredNdm - lastNdmN), (unsigned)lastNdmCmd);
        lastNdmN = answeredNdm;
    }
    static u32 lastStubN;
    if (answeredStub != lastStubN) {
        n += snprintf(out + n, max - n, "tonic: stubbed UDS cmds x%lu (last 0x%04X)\n",
                      (unsigned long)(answeredStub - lastStubN), (unsigned)lastStubCmd);
        lastStubN = answeredStub;
    }
    static u32 lastInit;
    if (answeredInit != lastInit) {
        n += snprintf(out + n, max - n, "tonic: ANSWERED InitializeWithVersion x%lu (real UDS bypassed)\n",
                      (unsigned long)(answeredInit - lastInit));
        lastInit = answeredInit;
    }
    static u32 lastConn, lastStat;
    if (answeredConnect != lastConn) {
        n += snprintf(out + n, max - n, "tonic: ANSWERED ConnectToNetwork x%lu -> node %u (CONNECTED)\n",
                      (unsigned long)(answeredConnect - lastConn), myNodeId);
        lastConn = answeredConnect;
    }
    if (answeredStatus != lastStat) {
        n += snprintf(out + n, max - n, "tonic: ANSWERED GetConnectionStatus x%lu (connected as client)\n",
                      (unsigned long)(answeredStatus - lastStat));
        lastStat = answeredStatus;
    }
    static u32 lastDec;
    if (answeredDecrypt != lastDec) {
        n += snprintf(out + n, max - n, "tonic: ANSWERED DecryptBeaconData x%lu (host node injected)\n",
                      (unsigned long)(answeredDecrypt - lastDec));
        lastDec = answeredDecrypt;
    }
    LightLock_Lock(&rLock);
    while (rCount && n + 400 < max) {
        Event e = ring[rHead]; rHead = (rHead + 1) % RING; rCount--;
        LightLock_Unlock(&rLock);
        u32 cmd = (e.w[0] >> 16) & 0xFFFF, normal = (e.w[0] >> 6) & 0x3F, xlate = e.w[0] & 0x3F;
        const char *nm = "?";
        for (int i = 0; NM[i].name; i++) if (NM[i].id == cmd) { nm = NM[i].name; break; }
        n += snprintf(out + n, max - n, "tonic: UDS %-24s hdr=%08lX norm=%lu xlate=%lu site=%08lX\n",
                      nm, (unsigned long)e.w[0], (unsigned long)normal, (unsigned long)xlate,
                      (unsigned long)(e.site < NSITES ? SITES[e.site] : 0));
        // Full word dump for the commands whose buffers the answerer will need to read or fill.
        if (cmd==0x0F||cmd==0x1F||cmd==0x1D||cmd==0x1E||cmd==0x0D||cmd==0x12||cmd==0x14||cmd==0x17||cmd==0x1B||cmd==0x10) {
            for (u32 i = 0; i < e.nwords && i < 24; i += 6) {
                n += snprintf(out + n, max - n, "tonic:   w[%02lu]", (unsigned long)i);
                for (u32 j = i; j < i + 6 && j < e.nwords && j < 24; j++)
                    n += snprintf(out + n, max - n, " %08lX", (unsigned long)e.w[j]);
                n += snprintf(out + n, max - n, "\n");
            }
        }
        LightLock_Lock(&rLock);
    }
    LightLock_Unlock(&rLock);
    return n;
}
