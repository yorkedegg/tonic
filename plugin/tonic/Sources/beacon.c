// Builds the RecvBeaconBroadcastData reply buffer for one fake host, byte-identical to what
// Azahar's GenerateBeaconFrame + the RecvBeaconBroadcastData handler produce. Portable C so the
// exact layout can be dumped and checked on the host (make -f beacon-test) before it runs on the
// console. The host name lives in the PLAINTEXT NetworkInfoTag, so the join list can show it
// without the beacon crypto; the encrypted node tag is present but zero-filled (the game would
// re-decrypt it with the real key anyway).
#include "beacon.h"
#include <string.h>

// ---- compact SHA-1 (public-domain style) ----
typedef struct { u32 h[5]; u32 len; unsigned char buf[64]; u32 n; } SHA1;
static u32 rol(u32 v, int b) { return (v << b) | (v >> (32 - b)); }
static void sha1_block(SHA1 *s, const unsigned char *p) {
    u32 w[80];
    for (int i = 0; i < 16; i++) w[i] = (p[i*4]<<24)|(p[i*4+1]<<16)|(p[i*4+2]<<8)|p[i*4+3];
    for (int i = 16; i < 80; i++) w[i] = rol(w[i-3]^w[i-8]^w[i-14]^w[i-16], 1);
    u32 a=s->h[0],b=s->h[1],c=s->h[2],d=s->h[3],e=s->h[4];
    for (int i = 0; i < 80; i++) {
        u32 f, k;
        if (i < 20)      { f=(b&c)|((~b)&d);        k=0x5A827999; }
        else if (i < 40) { f=b^c^d;                 k=0x6ED9EBA1; }
        else if (i < 60) { f=(b&c)|(b&d)|(c&d);     k=0x8F1BBCDC; }
        else             { f=b^c^d;                 k=0xCA62C1D6; }
        u32 t = rol(a,5)+f+e+k+w[i]; e=d; d=c; c=rol(b,30); b=a; a=t;
    }
    s->h[0]+=a; s->h[1]+=b; s->h[2]+=c; s->h[3]+=d; s->h[4]+=e;
}
static void sha1_init(SHA1 *s){ s->h[0]=0x67452301;s->h[1]=0xEFCDAB89;s->h[2]=0x98BADCFE;s->h[3]=0x10325476;s->h[4]=0xC3D2E1F0;s->len=0;s->n=0; }
static void sha1_update(SHA1 *s, const unsigned char *p, u32 n){
    s->len += n;
    while (n) { s->buf[s->n++]=*p++; n--; if (s->n==64){ sha1_block(s,s->buf); s->n=0; } }
}
static void sha1_final(SHA1 *s, unsigned char out[20]){
    u64 bits = (u64)s->len * 8; unsigned char pad=0x80; sha1_update(s,&pad,1);
    unsigned char z=0; while (s->n != 56) sha1_update(s,&z,1);
    unsigned char L[8]; for (int i=0;i<8;i++) L[i]=(unsigned char)(bits>>(56-8*i)); sha1_update(s,L,8);
    for (int i=0;i<5;i++){ out[i*4]=s->h[i]>>24; out[i*4+1]=s->h[i]>>16; out[i*4+2]=s->h[i]>>8; out[i*4+3]=s->h[i]; }
}
static void sha1(const unsigned char *p, u32 n, unsigned char out[20]){ SHA1 s; sha1_init(&s); sha1_update(&s,p,n); sha1_final(&s,out); }

// little-endian writers
static u8 *w16(u8 *p, u16 v){ p[0]=v; p[1]=v>>8; return p+2; }
static u8 *w32(u8 *p, u32 v){ p[0]=v; p[1]=v>>8; p[2]=v>>16; p[3]=v>>24; return p+4; }

static const u8 OUI[3] = {0x00,0x1F,0x32};
#define HOST_MAC0 0x02,0x00,0x00,0x1B,0x87,0x10

// Builds the 802.11 beacon frame at `out`; returns its length.
static u32 buildFrame(u8 *out, const u8 *appdata, u32 appLen) {
    u8 *p = out;
    // 1) fixed params (BeaconFrameHeader, 12): timestamp u64, interval u16=100, caps u16=0x0431
    p = w32(p, 900000000u); p = w32(p, 0);   // timestamp = 900000000 (fits in low word)
    p = w16(p, 100); p = w16(p, 0x0431);
    // 2) SSID tag (10): id 0, len 8, 8 zero bytes
    *p++ = 0x00; *p++ = 8; memset(p, 0, 8); p += 8;
    // 3) Nintendo Dummy tag (9)
    *p++ = 0xDD; *p++ = 7; *p++ = OUI[0]; *p++ = OUI[1]; *p++ = OUI[2];
    *p++ = 20; *p++ = 0x0A; *p++ = 0x00; *p++ = 0x00;
    // 4) NetworkInfoTag (54 + appLen). network_info[31] starts at oui_value.
    u8 *tag = p;
    *p++ = 0xDD; *p++ = (u8)(52 + appLen);
    u8 *ni = p;                                 // network_info[31]
    *p++ = OUI[0]; *p++ = OUI[1]; *p++ = OUI[2]; // oui_value
    *p++ = 21;                                   // oui_type = NetworkInfo
    *p++ = 0x00; *p++ = 0x1B; *p++ = 0x87; *p++ = 0x10; // wlan_comm_id BE
    *p++ = 0x00;                                 // id
    *p++ = 0x00;                                 // pad
    *p++ = 0x00; *p++ = 0x00;                    // attributes BE
    *p++ = 0x00; *p++ = 0x00; *p++ = 0x12; *p++ = 0x34; // network_id BE
    *p++ = 0x01;                                 // total_nodes
    *p++ = 0x02;                                 // max_nodes
    memset(p, 0, 13); p += 13;                   // pad to 31 bytes of network_info
    u8 *sha = p; memset(p, 0, 20); p += 20;      // sha_hash (zero for now)
    *p++ = (u8)appLen;                           // appdata_size
    memcpy(p, appdata, appLen); p += appLen;     // application_data
    // SHA1 over network_info..end (i.e. from `ni` to `p`), with sha_hash still zero.
    unsigned char digest[20]; sha1(ni, (u32)(p - ni), digest);
    memcpy(sha, digest, 20);
    (void)tag;
    // 5) EncryptedData0 tag (6 + 78), payload zero-filled
    *p++ = 0xDD; *p++ = 82; *p++ = OUI[0]; *p++ = OUI[1]; *p++ = OUI[2]; *p++ = 24;
    memset(p, 0, 78); p += 78;
    return (u32)(p - out);
}

u32 buildBeaconReply(u8 *out, u32 maxSize, u32 reqSize, const u8 *appdata, u32 appLen) {
    if (appLen > 0xC8) appLen = 0xC8;
    // frame goes at offset 12 (reply hdr) + 28 (entry hdr) = 40
    u32 need = 40 + 169 + appLen;
    if (need > maxSize) return 0;
    u8 *frame = out + 40;
    u32 frameLen = buildFrame(frame, appdata, appLen);
    // BeaconEntryHeader at offset 12 (28 bytes)
    u8 *e = out + 12;
    w32(e, 28 + frameLen);          // 0x00 total_size
    e[4] = 0; e[5] = 11;            // 0x04 pad, 0x05 wifi_channel = 11
    e[6] = 0; e[7] = 0;             // 0x06 pad
    { u8 mac[6] = {HOST_MAC0}; memcpy(e + 8, mac, 6); }  // 0x08 mac
    memset(e + 14, 0, 6);          // 0x0E pad
    w32(e + 20, 28 + frameLen);     // 0x14 unk_size
    w32(e + 24, 28);                // 0x18 header_size
    // BeaconDataReplyHeader at offset 0 (12 bytes)
    w32(out + 0, reqSize);          // max_output_size
    w32(out + 4, 40 + frameLen);    // total_size = header + entry + frame
    w32(out + 8, 1);                // total_entries
    return 40 + frameLen;
}
