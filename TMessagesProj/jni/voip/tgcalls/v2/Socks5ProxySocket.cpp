/*
 * SOCKS5 (RFC 1928) proxy sockets for tgcalls v2.
 */

#include "v2/Socks5ProxySocket.h"

#include <arpa/inet.h>
#include <errno.h>
#include <string.h>

#include <algorithm>

#include "rtc_base/byte_order.h"
#include "rtc_base/crypt_string.h"
#include "rtc_base/ip_address.h"
#include "rtc_base/logging.h"
#include "rtc_base/network/received_packet.h"
#include "rtc_base/network/sent_packet.h"
#include "rtc_base/time_utils.h"

namespace tgcalls {

namespace {

constexpr uint8_t kSocksVersion = 0x05;
constexpr uint8_t kAuthVersion = 0x01;
constexpr uint8_t kMethodNoAuth = 0x00;
constexpr uint8_t kMethodUserPass = 0x02;
constexpr uint8_t kAtypIPv4 = 0x01;
constexpr uint8_t kAtypDomain = 0x03;
constexpr uint8_t kAtypIPv6 = 0x04;

void AppendU8(rtc::Buffer* buf, uint8_t value) {
    buf->AppendData(&value, 1);
}

void AppendU16BE(rtc::Buffer* buf, uint16_t value) {
    uint8_t b[2] = {static_cast<uint8_t>(value >> 8),
                     static_cast<uint8_t>(value & 0xff)};
    buf->AppendData(b, 2);
}

// Writes DST.ADDR + DST.PORT (ATYP-prefixed) for the given address into `buf`.
// For unresolved/empty addresses, writes IPv4 0.0.0.0:0.
void AppendSocksAddress(rtc::Buffer* buf, const rtc::SocketAddress& addr) {
    rtc::IPAddress ip = addr.ipaddr();
    if (ip.family() == AF_INET6) {
        AppendU8(buf, kAtypIPv6);
        in6_addr v6 = ip.ipv6_address();
        buf->AppendData(reinterpret_cast<const uint8_t*>(&v6), 16);
    } else {
        AppendU8(buf, kAtypIPv4);
        uint32_t v4_host = ip.v4AddressAsHostOrderInteger();
        uint8_t b[4] = {
            static_cast<uint8_t>((v4_host >> 24) & 0xff),
            static_cast<uint8_t>((v4_host >> 16) & 0xff),
            static_cast<uint8_t>((v4_host >> 8) & 0xff),
            static_cast<uint8_t>(v4_host & 0xff),
        };
        buf->AppendData(b, 4);
    }
    AppendU16BE(buf, addr.port());
}

}  // namespace

// ============================================================================
// Socks5Handshake
// ============================================================================

Socks5Handshake::Socks5Handshake(Socks5Cmd cmd,
                                   const rtc::SocketAddress& dest,
                                   absl::string_view username,
                                   const rtc::CryptString& password)
    : cmd_(cmd), dest_(dest), username_(username), password_(password) {}

void Socks5Handshake::BuildGreetingAndStart(rtc::Buffer* out) {
    out->Clear();
    AppendU8(out, kSocksVersion);
    if (!username_.empty()) {
        AppendU8(out, 2);  // NMETHODS
        AppendU8(out, kMethodNoAuth);
        AppendU8(out, kMethodUserPass);
    } else {
        AppendU8(out, 1);  // NMETHODS
        AppendU8(out, kMethodNoAuth);
    }
    state_ = State::kWaitGreeting;
}

void Socks5Handshake::BuildAuthRequest(rtc::Buffer* out) const {
    out->Clear();
    AppendU8(out, kAuthVersion);
    size_t ulen = std::min<size_t>(username_.size(), 255);
    AppendU8(out, static_cast<uint8_t>(ulen));
    out->AppendData(reinterpret_cast<const uint8_t*>(username_.data()), ulen);

    size_t plen = std::min<size_t>(password_.GetLength(), 255);
    AppendU8(out, static_cast<uint8_t>(plen));
    // Extract password bytes into a local buffer.
    if (plen > 0) {
        std::vector<char> pwbuf(plen + 1);
        password_.CopyTo(pwbuf.data(), /*nullterminate=*/true);
        out->AppendData(reinterpret_cast<const uint8_t*>(pwbuf.data()), plen);
    }
}

void Socks5Handshake::BuildCommandRequest(rtc::Buffer* out) const {
    out->Clear();
    AppendU8(out, kSocksVersion);
    AppendU8(out, static_cast<uint8_t>(cmd_));
    AppendU8(out, 0x00);  // RSV
    if (cmd_ == Socks5Cmd::UdpAssociate) {
        // For UDP ASSOCIATE, send 0.0.0.0:0 — the proxy picks its own relay
        // and we don't constrain the source.
        AppendU8(out, kAtypIPv4);
        uint8_t zero[4] = {0, 0, 0, 0};
        out->AppendData(zero, 4);
        AppendU16BE(out, 0);
    } else {
        AppendSocksAddress(out, dest_);
    }
}

bool Socks5Handshake::Feed(const uint8_t* data, size_t len, size_t* consumed,
                             rtc::Buffer* to_send) {
    *consumed = 0;
    to_send->Clear();

    while (*consumed < len) {
        size_t remaining = len - *consumed;
        const uint8_t* p = data + *consumed;

        switch (state_) {
            case State::kInit:
                // Caller should have sent the greeting already and moved to
                // kWaitGreeting before feeding data.
                state_ = State::kError;
                return false;

            case State::kWaitGreeting: {
                if (remaining < 2) return true;  // need more data
                if (p[0] != kSocksVersion) {
                    RTC_LOG(LS_WARNING) << "socks5: bad VER in greeting reply: "
                                         << static_cast<int>(p[0]);
                    state_ = State::kError;
                    return false;
                }
                uint8_t method = p[1];
                *consumed += 2;
                if (method == kMethodNoAuth) {
                    rtc::Buffer req;
                    BuildCommandRequest(&req);
                    to_send->AppendData(req.data(), req.size());
                    state_ = State::kWaitCmdResponse;
                } else if (method == kMethodUserPass && !username_.empty()) {
                    rtc::Buffer req;
                    BuildAuthRequest(&req);
                    to_send->AppendData(req.data(), req.size());
                    state_ = State::kWaitAuthResponse;
                } else {
                    RTC_LOG(LS_WARNING) << "socks5: unsupported auth method "
                                         << static_cast<int>(method);
                    state_ = State::kError;
                    return false;
                }
                break;
            }

            case State::kWaitAuthResponse: {
                if (remaining < 2) return true;
                if (p[0] != kAuthVersion) {
                    RTC_LOG(LS_WARNING) << "socks5: bad auth VER: "
                                         << static_cast<int>(p[0]);
                    state_ = State::kError;
                    return false;
                }
                if (p[1] != 0) {
                    RTC_LOG(LS_WARNING) << "socks5: auth rejected, status="
                                         << static_cast<int>(p[1]);
                    state_ = State::kError;
                    return false;
                }
                *consumed += 2;
                rtc::Buffer req;
                BuildCommandRequest(&req);
                to_send->AppendData(req.data(), req.size());
                state_ = State::kWaitCmdResponse;
                break;
            }

            case State::kWaitCmdResponse: {
                // Need at least VER REP RSV ATYP = 4 bytes.
                if (remaining < 4) return true;
                if (p[0] != kSocksVersion) {
                    RTC_LOG(LS_WARNING) << "socks5: bad VER in cmd reply";
                    state_ = State::kError;
                    return false;
                }
                if (p[1] != 0) {
                    RTC_LOG(LS_WARNING) << "socks5: cmd rejected, REP="
                                         << static_cast<int>(p[1]);
                    state_ = State::kError;
                    return false;
                }
                uint8_t atyp = p[3];
                size_t addr_len = 0;
                if (atyp == kAtypIPv4) {
                    addr_len = 4;
                } else if (atyp == kAtypIPv6) {
                    addr_len = 16;
                } else if (atyp == kAtypDomain) {
                    if (remaining < 5) return true;
                    addr_len = 1 + p[4];  // length byte + domain
                } else {
                    RTC_LOG(LS_WARNING) << "socks5: unknown ATYP "
                                         << static_cast<int>(atyp);
                    state_ = State::kError;
                    return false;
                }
                size_t total_len = 4 + addr_len + 2;
                if (remaining < total_len) return true;

                if (atyp == kAtypIPv4) {
                    uint32_t v4_host = (static_cast<uint32_t>(p[4]) << 24) |
                                       (static_cast<uint32_t>(p[5]) << 16) |
                                       (static_cast<uint32_t>(p[6]) << 8) |
                                       (static_cast<uint32_t>(p[7]));
                    in_addr a;
                    a.s_addr = htonl(v4_host);
                    bound_address_.SetIP(rtc::IPAddress(a));
                } else if (atyp == kAtypIPv6) {
                    in6_addr a;
                    memcpy(&a, p + 4, 16);
                    bound_address_.SetIP(rtc::IPAddress(a));
                } else {
                    // Domain — rare for UDP ASSOCIATE replies. Store as host.
                    std::string host(reinterpret_cast<const char*>(p + 5),
                                      addr_len - 1);
                    bound_address_.SetIP(host);
                }
                uint16_t port = (static_cast<uint16_t>(p[4 + addr_len]) << 8) |
                                static_cast<uint16_t>(p[4 + addr_len + 1]);
                bound_address_.SetPort(port);
                *consumed += total_len;
                state_ = State::kDone;
                return true;
            }

            case State::kDone:
                return true;

            case State::kError:
                return false;
        }
    }
    return true;
}

// ============================================================================
// Socks5TcpProxySocket
// ============================================================================

Socks5TcpProxySocket::Socks5TcpProxySocket(rtc::Socket* socket,
                                             const rtc::SocketAddress& proxy,
                                             absl::string_view username,
                                             const rtc::CryptString& password)
    : rtc::AsyncSocketAdapter(socket),
      proxy_(proxy),
      username_(username),
      password_(password) {}

Socks5TcpProxySocket::~Socks5TcpProxySocket() = default;

int Socks5TcpProxySocket::Connect(const rtc::SocketAddress& addr) {
    dest_ = addr;
    handshake_ = Socks5Handshake(Socks5Cmd::Connect, dest_, username_, password_);
    return rtc::AsyncSocketAdapter::Connect(proxy_);
}

rtc::SocketAddress Socks5TcpProxySocket::GetRemoteAddress() const {
    return dest_;
}

int Socks5TcpProxySocket::Close() {
    tunneled_ = false;
    return rtc::AsyncSocketAdapter::Close();
}

rtc::Socket::ConnState Socks5TcpProxySocket::GetState() const {
    if (tunneled_) return CS_CONNECTED;
    if (handshake_.state() == Socks5Handshake::State::kError) return CS_CLOSED;
    return CS_CONNECTING;
}

void Socks5TcpProxySocket::OnConnectEvent(rtc::Socket* socket) {
    // Low-level TCP connect to the proxy finished. Start SOCKS5 handshake.
    rtc::Buffer greeting;
    handshake_.BuildGreetingAndStart(&greeting);
    pending_send_ = std::move(greeting);
    FlushToSend();
}

void Socks5TcpProxySocket::OnReadEvent(rtc::Socket* socket) {
    if (tunneled_) {
        // Pass through to outer listeners.
        rtc::AsyncSocketAdapter::OnReadEvent(socket);
        return;
    }

    // Read whatever is available and feed it to the handshake.
    uint8_t buf[512];
    int got = socket->Recv(buf, sizeof(buf), nullptr);
    if (got <= 0) {
        if (got < 0 && (socket->GetError() == EWOULDBLOCK ||
                         socket->GetError() == EAGAIN)) {
            return;
        }
        Error(socket->GetError() != 0 ? socket->GetError() : ECONNRESET);
        return;
    }

    size_t consumed = 0;
    rtc::Buffer to_send;
    if (!handshake_.Feed(buf, static_cast<size_t>(got), &consumed, &to_send)) {
        Error(EPROTO);
        return;
    }
    if (to_send.size() > 0) {
        pending_send_ = std::move(to_send);
        FlushToSend();
    }
    if (handshake_.state() == Socks5Handshake::State::kDone) {
        tunneled_ = true;
        // Notify upper layers that the tunnel is ready.
        rtc::AsyncSocketAdapter::OnConnectEvent(socket);
    }
}

void Socks5TcpProxySocket::OnCloseEvent(rtc::Socket* socket, int err) {
    tunneled_ = false;
    rtc::AsyncSocketAdapter::OnCloseEvent(socket, err);
}

void Socks5TcpProxySocket::Error(int err) {
    RTC_LOG(LS_WARNING) << "Socks5TcpProxySocket: error " << err;
    SetError(err);
    Close();
}

void Socks5TcpProxySocket::FlushToSend() {
    if (pending_send_.size() == 0) return;
    int sent = GetSocket()->Send(pending_send_.data(), pending_send_.size());
    if (sent < 0) {
        int err = GetSocket()->GetError();
        if (err != EWOULDBLOCK && err != EAGAIN) {
            Error(err);
        }
        return;
    }
    // SOCKS5 handshake messages are small enough to fit in a single send.
    pending_send_.Clear();
}

// ============================================================================
// Socks5UdpProxySocket
// ============================================================================

std::unique_ptr<Socks5UdpProxySocket> Socks5UdpProxySocket::Create(
    rtc::SocketFactory* socket_factory,
    const rtc::SocketAddress& bind_address,
    const rtc::SocketAddress& proxy,
    absl::string_view username,
    const rtc::CryptString& password) {
    int family = bind_address.family() ? bind_address.family() : AF_INET;
    std::unique_ptr<rtc::Socket> udp(
        socket_factory->CreateSocket(family, SOCK_DGRAM));
    if (!udp) {
        return nullptr;
    }
    if (udp->Bind(bind_address) < 0) {
        RTC_LOG(LS_WARNING) << "Socks5UdpProxySocket: inner udp bind failed: "
                             << udp->GetError();
        return nullptr;
    }
    std::unique_ptr<rtc::Socket> tcp(
        socket_factory->CreateSocket(family, SOCK_STREAM));
    if (!tcp) {
        return nullptr;
    }
    auto result = std::unique_ptr<Socks5UdpProxySocket>(
        new Socks5UdpProxySocket(std::move(tcp), std::move(udp), proxy,
                                  username, password));
    result->StartHandshake();
    return result;
}

Socks5UdpProxySocket::Socks5UdpProxySocket(
    std::unique_ptr<rtc::Socket> control_tcp,
    std::unique_ptr<rtc::Socket> inner_udp,
    const rtc::SocketAddress& proxy,
    absl::string_view username,
    const rtc::CryptString& password)
    : control_tcp_(std::move(control_tcp)),
      inner_udp_(std::move(inner_udp)),
      proxy_(proxy),
      handshake_(Socks5Cmd::UdpAssociate, rtc::SocketAddress(),
                 username, password) {
    inner_udp_->SignalReadEvent.connect(this,
                                          &Socks5UdpProxySocket::OnInnerUdpRead);
    control_tcp_->SignalConnectEvent.connect(this,
                                               &Socks5UdpProxySocket::OnTcpConnect);
    control_tcp_->SignalReadEvent.connect(this,
                                            &Socks5UdpProxySocket::OnTcpRead);
    control_tcp_->SignalCloseEvent.connect(this,
                                             &Socks5UdpProxySocket::OnTcpClose);
}

Socks5UdpProxySocket::~Socks5UdpProxySocket() = default;

void Socks5UdpProxySocket::StartHandshake() {
    int ret = control_tcp_->Connect(proxy_);
    if (ret < 0 && control_tcp_->GetError() != EINPROGRESS &&
        control_tcp_->GetError() != EWOULDBLOCK) {
        MarkError(control_tcp_->GetError());
    }
}

void Socks5UdpProxySocket::OnTcpConnect(rtc::Socket* socket) {
    rtc::Buffer greeting;
    handshake_.BuildGreetingAndStart(&greeting);
    pending_send_ = std::move(greeting);
    FlushHandshakeSend();
}

void Socks5UdpProxySocket::OnTcpRead(rtc::Socket* socket) {
    uint8_t buf[512];
    int got = socket->Recv(buf, sizeof(buf), nullptr);
    if (got <= 0) {
        if (got < 0 && (socket->GetError() == EWOULDBLOCK ||
                         socket->GetError() == EAGAIN)) {
            return;
        }
        MarkError(socket->GetError() != 0 ? socket->GetError() : ECONNRESET);
        return;
    }
    size_t consumed = 0;
    rtc::Buffer to_send;
    if (!handshake_.Feed(buf, static_cast<size_t>(got), &consumed, &to_send)) {
        MarkError(EPROTO);
        return;
    }
    if (to_send.size() > 0) {
        pending_send_ = std::move(to_send);
        FlushHandshakeSend();
    }
    if (handshake_.state() == Socks5Handshake::State::kDone &&
        state_ != STATE_CONNECTED) {
        relay_addr_ = handshake_.bound_address();
        // Some proxies return 0.0.0.0 as BND.ADDR, meaning "same IP as the
        // control connection". Replace with the proxy's IP in that case.
        if (relay_addr_.ipaddr().IsNil()) {
            relay_addr_.SetIP(proxy_.ipaddr());
        }
        RTC_LOG(LS_INFO) << "Socks5UdpProxySocket: UDP ASSOCIATE ready, relay="
                          << relay_addr_.ToSensitiveString();
        state_ = STATE_CONNECTED;
        SignalAddressReady(this, inner_udp_->GetLocalAddress());
        SignalReadyToSend(this);
    }
}

void Socks5UdpProxySocket::OnTcpClose(rtc::Socket* socket, int err) {
    MarkError(err != 0 ? err : ECONNRESET);
}

void Socks5UdpProxySocket::OnInnerUdpRead(rtc::Socket* socket) {
    if (udp_recv_buffer_.size() < 2048) {
        udp_recv_buffer_.SetSize(2048);
    }
    rtc::SocketAddress source;
    int64_t ts = 0;
    int got = inner_udp_->RecvFrom(udp_recv_buffer_.data(),
                                     udp_recv_buffer_.size(), &source, &ts);
    if (got <= 0) {
        return;
    }
    if (state_ != STATE_CONNECTED) {
        // Drop packets received before the SOCKS5 UDP ASSOCIATE is ready.
        return;
    }
    // Accept packets only from the relay endpoint.
    if (!(source == relay_addr_)) {
        RTC_LOG(LS_VERBOSE) << "Socks5UdpProxySocket: ignoring packet from "
                             << source.ToSensitiveString();
        return;
    }
    rtc::SocketAddress inner_source;
    size_t payload_offset = 0;
    if (!ParseUdpHeader(udp_recv_buffer_.data(), static_cast<size_t>(got),
                          &inner_source, &payload_offset)) {
        return;
    }
    const uint8_t* payload = udp_recv_buffer_.data() + payload_offset;
    size_t payload_len = static_cast<size_t>(got) - payload_offset;
    NotifyPacketReceived(rtc::ReceivedPacket(
        rtc::MakeArrayView(payload, payload_len), inner_source,
        webrtc::Timestamp::Micros(rtc::TimeMicros())));
}

rtc::SocketAddress Socks5UdpProxySocket::GetLocalAddress() const {
    return inner_udp_->GetLocalAddress();
}

rtc::SocketAddress Socks5UdpProxySocket::GetRemoteAddress() const {
    return rtc::SocketAddress();
}

int Socks5UdpProxySocket::Send(const void* /*pv*/, size_t /*cb*/,
                                 const rtc::PacketOptions& /*options*/) {
    SetError(ENOTCONN);
    return -1;
}

int Socks5UdpProxySocket::SendTo(const void* pv, size_t cb,
                                   const rtc::SocketAddress& addr,
                                   const rtc::PacketOptions& options) {
    if (state_ != STATE_CONNECTED) {
        SetError(EWOULDBLOCK);
        return -1;
    }
    rtc::Buffer packet;
    BuildUdpHeader(addr, &packet);
    packet.AppendData(reinterpret_cast<const uint8_t*>(pv), cb);

    rtc::SentPacket sent_packet(options.packet_id, rtc::TimeMillis(),
                                 options.info_signaled_after_sent);
    CopySocketInformationToPacketInfo(packet.size(), *this, true,
                                        &sent_packet.info);
    int sent = inner_udp_->SendTo(packet.data(), packet.size(), relay_addr_);
    SignalSentPacket(this, sent_packet);
    if (sent < 0) return sent;
    // Report the original payload size to the caller (minus the SOCKS5
    // header) so bookkeeping at higher layers matches.
    return static_cast<int>(cb);
}

int Socks5UdpProxySocket::Close() {
    if (inner_udp_) inner_udp_->Close();
    if (control_tcp_) control_tcp_->Close();
    state_ = STATE_CLOSED;
    return 0;
}

Socks5UdpProxySocket::State Socks5UdpProxySocket::GetState() const {
    return state_;
}

int Socks5UdpProxySocket::GetOption(rtc::Socket::Option opt, int* value) {
    return inner_udp_->GetOption(opt, value);
}

int Socks5UdpProxySocket::SetOption(rtc::Socket::Option opt, int value) {
    return inner_udp_->SetOption(opt, value);
}

int Socks5UdpProxySocket::GetError() const {
    return error_;
}

void Socks5UdpProxySocket::SetError(int error) {
    error_ = error;
}

void Socks5UdpProxySocket::BuildUdpHeader(const rtc::SocketAddress& dest,
                                            rtc::Buffer* out) {
    out->Clear();
    AppendU8(out, 0x00);  // RSV
    AppendU8(out, 0x00);  // RSV
    AppendU8(out, 0x00);  // FRAG
    AppendSocksAddress(out, dest);
}

bool Socks5UdpProxySocket::ParseUdpHeader(const uint8_t* data, size_t len,
                                            rtc::SocketAddress* source,
                                            size_t* payload_offset) {
    if (len < 4) return false;
    if (data[0] != 0x00 || data[1] != 0x00) return false;  // RSV
    if (data[2] != 0x00) return false;                      // FRAG, no frags
    uint8_t atyp = data[3];
    size_t pos = 4;
    if (atyp == kAtypIPv4) {
        if (len < pos + 4 + 2) return false;
        in_addr a;
        memcpy(&a, data + pos, 4);
        source->SetIP(rtc::IPAddress(a));
        pos += 4;
    } else if (atyp == kAtypIPv6) {
        if (len < pos + 16 + 2) return false;
        in6_addr a;
        memcpy(&a, data + pos, 16);
        source->SetIP(rtc::IPAddress(a));
        pos += 16;
    } else if (atyp == kAtypDomain) {
        if (len < pos + 1) return false;
        size_t dlen = data[pos];
        pos += 1;
        if (len < pos + dlen + 2) return false;
        source->SetIP(std::string(reinterpret_cast<const char*>(data + pos), dlen));
        pos += dlen;
    } else {
        return false;
    }
    uint16_t port = (static_cast<uint16_t>(data[pos]) << 8) |
                    static_cast<uint16_t>(data[pos + 1]);
    source->SetPort(port);
    pos += 2;
    *payload_offset = pos;
    return true;
}

void Socks5UdpProxySocket::MarkError(int err) {
    RTC_LOG(LS_WARNING) << "Socks5UdpProxySocket: error " << err;
    error_ = err;
    state_ = STATE_CLOSED;
    NotifyClosed(err);
}

void Socks5UdpProxySocket::FlushHandshakeSend() {
    if (pending_send_.size() == 0) return;
    int sent = control_tcp_->Send(pending_send_.data(), pending_send_.size());
    if (sent < 0) {
        int err = control_tcp_->GetError();
        if (err != EWOULDBLOCK && err != EAGAIN) {
            MarkError(err);
        }
        return;
    }
    pending_send_.Clear();
}

}  // namespace tgcalls
