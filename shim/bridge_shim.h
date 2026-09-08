// [BRIDGE] M1 shim — redirects MC3DS UDS to the PC-side Java bridge over a TCP tunnel.
// LOCAL DEBUG ONLY (not for upstream per AI-POLICY.md). Header-only so no CMake changes.
//
// Activated at runtime when the env var MC3DS_BRIDGE is set to "host:port"
// (e.g. MC3DS_BRIDGE=127.0.0.1:7777). When unset, the normal room path runs untouched.
//
// Tunnel frame: u16 length(BE) | u8 type | u8 src | u8 dst | u8 channel | payload.
// length counts the 4 header bytes after it + payload. Types match bridge.tunnel.Frame.

#pragma once

#include <atomic>
#include <condition_variable>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <arpa/inet.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <sys/socket.h>
#include <unistd.h>

#include "common/common_types.h"
#include "common/logging/log.h"

namespace Service::NWM {

class BridgeTunnel {
public:
    enum Type : u8 { SCAN = 1, BEACON = 2, CONNECT = 3, CONNECT_OK = 4, DATA = 5, DISCONNECT = 6 };

    static BridgeTunnel& Get() {
        static BridgeTunnel inst;
        return inst;
    }

    // True if MC3DS_BRIDGE is set. Lazily connects on first use.
    bool Active() {
        const char* env = std::getenv("MC3DS_BRIDGE");
        if (!env)
            return false;
        if (!connected.load())
            Connect(env);
        return connected.load();
    }

    void SendScan() { SendFrame(SCAN, 2, 1, 0, nullptr, 0); }

    void SendConnect(const u8* pass, u32 len) { SendFrame(CONNECT, 2, 1, 0, pass, len); }

    void SendData(u8 channel, const u8* data, u32 len) { SendFrame(DATA, 2, 1, channel, data, len); }

    // Blocks up to timeout_ms for the next BEACON's application-data blob.
    bool WaitBeacon(std::vector<u8>& out, int timeout_ms) {
        std::unique_lock lk(mtx);
        if (!beacon_cv.wait_for(lk, std::chrono::milliseconds(timeout_ms),
                                [&] { return have_beacon; }))
            return false;
        out = last_beacon;
        have_beacon = false;
        return true;
    }

    // Blocks up to timeout_ms for CONNECT_OK; returns the assigned node id.
    bool WaitConnectOk(u8& node_id, int timeout_ms) {
        std::unique_lock lk(mtx);
        if (!connect_cv.wait_for(lk, std::chrono::milliseconds(timeout_ms),
                                 [&] { return have_connect_ok; }))
            return false;
        node_id = assigned_node;
        have_connect_ok = false;
        return true;
    }

    // Non-blocking: pops the next inbound DATA payload for a channel, if any.
    bool PopData(u8 channel, std::vector<u8>& out, u8& src) {
        std::scoped_lock lk(mtx);
        for (auto it = rx.begin(); it != rx.end(); ++it) {
            if (it->channel == channel) {
                out = std::move(it->payload);
                src = it->src;
                rx.erase(it);
                return true;
            }
        }
        return false;
    }

private:
    struct Rx {
        u8 src, channel;
        std::vector<u8> payload;
    };

    int sock = -1;
    std::atomic<bool> connected{false};
    std::thread reader;
    std::mutex mtx;
    std::mutex write_mtx;
    std::condition_variable beacon_cv, connect_cv;
    bool have_beacon = false, have_connect_ok = false;
    u8 assigned_node = 2;
    std::vector<u8> last_beacon;
    std::deque<Rx> rx;

    void Connect(const std::string& hostport) {
        auto colon = hostport.find(':');
        std::string host = hostport.substr(0, colon);
        int port = std::stoi(hostport.substr(colon + 1));
        sock = ::socket(AF_INET, SOCK_STREAM, 0);
        if (sock < 0) {
            LOG_ERROR(Service_NWM, "[BRIDGE] socket() failed");
            return;
        }
        int one = 1;
        setsockopt(sock, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
        sockaddr_in addr{};
        addr.sin_family = AF_INET;
        addr.sin_port = htons(static_cast<u16>(port));
        inet_pton(AF_INET, host.c_str(), &addr.sin_addr);
        if (::connect(sock, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) < 0) {
            LOG_ERROR(Service_NWM, "[BRIDGE] connect to {} failed", hostport);
            ::close(sock);
            sock = -1;
            return;
        }
        connected.store(true);
        reader = std::thread([this] { ReadLoop(); });
        reader.detach();
        LOG_INFO(Service_NWM, "[BRIDGE] tunnel connected to {}", hostport);
    }

    void SendFrame(u8 type, u8 src, u8 dst, u8 channel, const u8* data, u32 len) {
        if (!connected.load())
            return;
        std::vector<u8> f;
        u32 total = 4 + len;
        f.push_back(static_cast<u8>((total >> 8) & 0xFF));
        f.push_back(static_cast<u8>(total & 0xFF));
        f.push_back(type);
        f.push_back(src);
        f.push_back(dst);
        f.push_back(channel);
        if (len)
            f.insert(f.end(), data, data + len);
        std::scoped_lock lk(write_mtx);
        SendAll(f.data(), f.size());
    }

    void SendAll(const u8* p, std::size_t n) {
        std::size_t sent = 0;
        while (sent < n) {
            ssize_t r = ::send(sock, p + sent, n - sent, 0);
            if (r <= 0) {
                connected.store(false);
                return;
            }
            sent += static_cast<std::size_t>(r);
        }
    }

    bool RecvAll(u8* p, std::size_t n) {
        std::size_t got = 0;
        while (got < n) {
            ssize_t r = ::recv(sock, p + got, n - got, 0);
            if (r <= 0)
                return false;
            got += static_cast<std::size_t>(r);
        }
        return true;
    }

    void ReadLoop() {
        while (connected.load()) {
            u8 lenbuf[2];
            if (!RecvAll(lenbuf, 2))
                break;
            u32 len = (static_cast<u32>(lenbuf[0]) << 8) | lenbuf[1];
            if (len < 4)
                break;
            std::vector<u8> body(len);
            if (!RecvAll(body.data(), len))
                break;
            u8 type = body[0], src = body[1];
            u8 channel = body[3];
            std::vector<u8> payload(body.begin() + 4, body.end());
            Dispatch(type, src, channel, std::move(payload));
        }
        connected.store(false);
        LOG_WARNING(Service_NWM, "[BRIDGE] tunnel closed");
    }

    void Dispatch(u8 type, u8 src, u8 channel, std::vector<u8>&& payload) {
        std::scoped_lock lk(mtx);
        switch (type) {
        case BEACON:
            last_beacon = std::move(payload);
            have_beacon = true;
            beacon_cv.notify_all();
            break;
        case CONNECT_OK:
            assigned_node = payload.empty() ? 2 : payload[0];
            have_connect_ok = true;
            connect_cv.notify_all();
            break;
        case DATA:
            rx.push_back({src, channel, std::move(payload)});
            break;
        default:
            break;
        }
    }
};

} // namespace Service::NWM
