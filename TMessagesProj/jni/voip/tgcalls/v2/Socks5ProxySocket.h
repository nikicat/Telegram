/*
 * SOCKS5 (RFC 1928) proxy sockets for tgcalls v2.
 *
 * Socks5TcpProxySocket: rtc::AsyncSocketAdapter that performs a SOCKS5
 * CONNECT handshake on top of an inner TCP socket, then turns into a
 * transparent pass-through. Analogue of rtc::AsyncHttpsProxySocket.
 *
 * Socks5UdpProxySocket: rtc::AsyncPacketSocket that tunnels UDP datagrams
 * through a SOCKS5 proxy via UDP ASSOCIATE (RFC 1928 §7). It owns both
 * the inner UDP socket (the actual datagram endpoint) and a control TCP
 * socket to the proxy (required to keep the association alive).
 */

#ifndef TGCALLS_SOCKS5_PROXY_SOCKET_H_
#define TGCALLS_SOCKS5_PROXY_SOCKET_H_

#include <cstdint>
#include <memory>
#include <vector>

#include "absl/strings/string_view.h"
#include "rtc_base/async_packet_socket.h"
#include "rtc_base/async_socket.h"
#include "rtc_base/buffer.h"
#include "rtc_base/crypt_string.h"
#include "rtc_base/socket.h"
#include "rtc_base/socket_address.h"
#include "rtc_base/socket_factory.h"

namespace tgcalls {

// SOCKS5 CMD codes used by this module.
enum class Socks5Cmd {
    Connect = 0x01,
    UdpAssociate = 0x03,
};

// Encodes the SOCKS5 request that performs greeting → auth → command
// (CONNECT or UDP ASSOCIATE). Shared between the TCP and UDP wrappers.
class Socks5Handshake {
public:
    enum class State {
        kInit,
        kWaitGreeting,
        kWaitAuthResponse,
        kWaitCmdResponse,
        kDone,
        kError,
    };

    Socks5Handshake() = default;
    Socks5Handshake(Socks5Cmd cmd,
                    const rtc::SocketAddress& dest,
                    absl::string_view username,
                    const rtc::CryptString& password);

    // Build the initial greeting bytes and advance to kWaitGreeting.
    void BuildGreetingAndStart(rtc::Buffer* out);

    // Process a chunk of data that came back from the proxy. Appends any
    // bytes that should be written to the proxy into `to_send`. Consumes
    // however many bytes it can from `in` and leaves the remainder in place.
    // Returns false on protocol error (state_ becomes kError).
    bool Feed(const uint8_t* data, size_t len, size_t* consumed,
              rtc::Buffer* to_send);

    State state() const { return state_; }
    // Filled in after kDone when cmd == UdpAssociate — the relay endpoint
    // that UDP datagrams should be sent to.
    const rtc::SocketAddress& bound_address() const { return bound_address_; }

private:
    void BuildAuthRequest(rtc::Buffer* out) const;
    void BuildCommandRequest(rtc::Buffer* out) const;

    Socks5Cmd cmd_ = Socks5Cmd::Connect;
    rtc::SocketAddress dest_;
    std::string username_;
    rtc::CryptString password_;
    State state_ = State::kInit;
    rtc::SocketAddress bound_address_;
};

// TCP CONNECT through SOCKS5.
class Socks5TcpProxySocket : public rtc::AsyncSocketAdapter {
public:
    // Takes ownership of `socket`.
    Socks5TcpProxySocket(rtc::Socket* socket,
                         const rtc::SocketAddress& proxy,
                         absl::string_view username,
                         const rtc::CryptString& password);
    ~Socks5TcpProxySocket() override;

    Socks5TcpProxySocket(const Socks5TcpProxySocket&) = delete;
    Socks5TcpProxySocket& operator=(const Socks5TcpProxySocket&) = delete;

    int Connect(const rtc::SocketAddress& addr) override;
    rtc::SocketAddress GetRemoteAddress() const override;
    int Close() override;
    ConnState GetState() const override;

protected:
    void OnConnectEvent(rtc::Socket* socket) override;
    void OnReadEvent(rtc::Socket* socket) override;
    void OnCloseEvent(rtc::Socket* socket, int err) override;

private:
    void Error(int err);
    void FlushToSend();

    rtc::SocketAddress proxy_;
    rtc::SocketAddress dest_;
    std::string username_;
    rtc::CryptString password_;

    Socks5Handshake handshake_;
    rtc::Buffer pending_send_;
    bool tunneled_ = false;
};

// UDP ASSOCIATE through SOCKS5.
class Socks5UdpProxySocket : public rtc::AsyncPacketSocket {
public:
    // Creates the inner UDP socket (bound to `bind_address`) and starts
    // asynchronously establishing the SOCKS5 UDP ASSOCIATE control
    // connection. Returns nullptr if the inner sockets can't be created.
    static std::unique_ptr<Socks5UdpProxySocket> Create(
        rtc::SocketFactory* socket_factory,
        const rtc::SocketAddress& bind_address,
        const rtc::SocketAddress& proxy,
        absl::string_view username,
        const rtc::CryptString& password);

    ~Socks5UdpProxySocket() override;

    Socks5UdpProxySocket(const Socks5UdpProxySocket&) = delete;
    Socks5UdpProxySocket& operator=(const Socks5UdpProxySocket&) = delete;

    // AsyncPacketSocket overrides.
    rtc::SocketAddress GetLocalAddress() const override;
    rtc::SocketAddress GetRemoteAddress() const override;
    int Send(const void* pv, size_t cb,
             const rtc::PacketOptions& options) override;
    int SendTo(const void* pv, size_t cb,
               const rtc::SocketAddress& addr,
               const rtc::PacketOptions& options) override;
    int Close() override;
    State GetState() const override;
    int GetOption(rtc::Socket::Option opt, int* value) override;
    int SetOption(rtc::Socket::Option opt, int value) override;
    int GetError() const override;
    void SetError(int error) override;

private:
    Socks5UdpProxySocket(std::unique_ptr<rtc::Socket> control_tcp,
                         std::unique_ptr<rtc::Socket> inner_udp,
                         const rtc::SocketAddress& proxy,
                         absl::string_view username,
                         const rtc::CryptString& password);

    // Kick off the handshake — must be called on the network thread.
    void StartHandshake();

    // Inner UDP read callback.
    void OnInnerUdpRead(rtc::Socket* socket);
    // TCP control channel events.
    void OnTcpConnect(rtc::Socket* socket);
    void OnTcpRead(rtc::Socket* socket);
    void OnTcpClose(rtc::Socket* socket, int err);

    // Build the SOCKS5 UDP header for a given destination.
    static void BuildUdpHeader(const rtc::SocketAddress& dest,
                                rtc::Buffer* out);
    // Parse a SOCKS5 UDP header and return payload offset + source addr.
    static bool ParseUdpHeader(const uint8_t* data, size_t len,
                                rtc::SocketAddress* source,
                                size_t* payload_offset);

    void MarkError(int err);
    void FlushHandshakeSend();

    std::unique_ptr<rtc::Socket> control_tcp_;
    std::unique_ptr<rtc::Socket> inner_udp_;
    rtc::SocketAddress proxy_;
    Socks5Handshake handshake_;
    rtc::SocketAddress relay_addr_;
    rtc::Buffer pending_send_;
    rtc::Buffer tcp_recv_buffer_;
    rtc::Buffer udp_recv_buffer_;
    State state_ = STATE_BINDING;
    int error_ = 0;
};

}  // namespace tgcalls

#endif  // TGCALLS_SOCKS5_PROXY_SOCKET_H_
