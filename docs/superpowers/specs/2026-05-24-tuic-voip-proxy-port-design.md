# TUIC proxy + VoIP-over-proxy port from Telegram-X

Date: 2026-05-24
Status: Approved, pending implementation plan

## Goal

Port the proxy-related functionality from `../Telegram-X` (commits `7070d58f..9c178047`) into this Telegram-Android repo so that:

1. TUIC proxies are first-class: addable from settings, persistable, shareable via a `tg://tuic?...` URL scheme, configurable via a build-time default.
2. Both **data traffic and 1-on-1 voice/video VoIP calls** can route through the configured TUIC proxy.
3. Each proxy carries its own `useForCalls` flag, replacing today's global `proxy_enabled_calls` preference.
4. A default proxy link can be baked into the APK at build time and imported on first launch.

We are porting **functionality, not commits**. Some Telegram-X commits exist purely because Telegram-X is TDLib-based and uses different package layout; their Telegram-Android equivalents may collapse to fewer changes.

## Non-goals

- HTTP CONNECT proxy support (explicit user decision; the `tg://http` URL scheme, `CallProxy`/`Socks5Proxy` replacement, and TDLib-bypass changes from Telegram-X are skipped).
- Group voice chats, group video chats, livestreams. `VoIPService.makeGroupInstance(...)` will continue to receive no proxy — group call traffic egresses directly even when a TUIC proxy is active. Flagged as a known gap.
- TDLib migration. Telegram-Android uses tgnet+MTProto directly; we keep that path.
- Redroid/build-infra commits from Telegram-X (`0bba4b1f`, `0b8b4776`, `29b3ba9e`, `5ab9ee7e`, `93693e0f`, etc.) — these are environment-specific and either already present here (the `afatX64` flavor, the local justfile) or irrelevant.

## Architecture

The keystone idea, copied directly from Telegram-X: **sing-box runs in-process and exposes each TUIC proxy as a local `127.0.0.1:<port>` SOCKS5 inbound.** Everything else in the app — `ConnectionsManager`, `VoIPService`, tgcalls — sees only a SOCKS5 proxy. This means:

- The data path and the VoIP path do not need to know about TUIC at all.
- The only SOCKS5-specific code paths we touch are the ones that already exist in tgcalls (which currently mis-handle SOCKS5 UDP).
- Adding a future proxy type (Trojan, Shadowsocks, …) only requires extending the sing-box config builder and the URL scheme.

### Component overview

```
┌──────────────────────────┐        ┌────────────────────────────┐
│ TMessagesProj (Java)     │        │ singbox/ (Kotlin Android   │
│                          │        │           library module)  │
│ SharedConfig.ProxyInfo ──┼─TUIC──▶│ SingBoxManager.startAll()  │
│   (type, tuicConfig,     │        │   ├── pick free port       │
│    useForCalls)          │        │   ├── build sing-box JSON  │
│                          │        │   └── Bridge.start(json) ──┼──▶ singbox/go (gomobile AAR)
│ SingBoxController ◀──────┼─port───┤ SingBoxManager.getPort()   │      ├── box.Box
│   (TMessagesProj         │        │                            │      └── SOCKS5 inbound → TUIC outbound
│    integration glue)     │        └────────────────────────────┘
│   │                      │
│   ├─▶ ConnectionsManager.setProxySettings(127.0.0.1:port, "", "")
│   │   (synthetic SOCKS5 ProxyInfo when current proxy is TUIC)
│   │
│   └─▶ VoIPService.makeInstance(...)
│           Instance.Proxy(127.0.0.1, port, "", "")
└──────────────────────────┘
                │
                ▼
        TMessagesProj/jni/voip/tgcalls/v2/
            NativeNetworkingImpl.cpp    ─── SOCKS5-wrapped sockets for ICE
            ReflectorPort.cpp           ─── SOCKS5-wrapped TCP for reflectors
            Socks5ProxySocket.{cpp,h}   ─── NEW: TCP CONNECT + UDP ASSOCIATE
```

### Components and interfaces

#### 1. `singbox/` module (new Gradle subproject)

Copied verbatim from `/home/nb/src/Telegram-X/singbox/`. Self-contained Kotlin Android library, registered in `settings.gradle`, consumed by `TMessagesProj` as an `implementation project(':singbox')` dep.

- `singbox/go/bridge.go` — Go package exposing `Start(configJSON string) error`, `Stop() error`, `IsRunning() bool` over `github.com/sagernet/sing-box`'s `box.Box`. Single in-process instance, mutex-guarded.
- `singbox/go/build.sh` — runs `gomobile bind -tags with_quic` against the sagernet fork, emits `singbox/libs/bridge.aar` (~75 MB).
- `singbox/build.gradle.kts` — declares the local AAR as a flat-dir dep; the AAR is gitignored and rebuilt on demand via a `just singbox` recipe (added to the existing `justfile`).
- `singbox/src/main/kotlin/tgx/singbox/SingBoxManager.kt`:
  - `fun startAll(configs: List<TuicConfig>)` — idempotent on identity set; builds one SOCKS inbound + one TUIC outbound + one route per config, restarts the bridge.
  - `fun stop()`
  - `fun isRunning(): Boolean`
  - `fun getPort(config: TuicConfig): Int` — port assigned to that TUIC config, or `0` if unknown.
- `singbox/src/main/kotlin/tgx/singbox/TuicConfig.kt` — `data class TuicConfig(server, port, uuid, password, congestionControl="bbr", tlsInsecure=true)` with `fun identity() = "$server:$port:$uuid"`.

No changes from Telegram-X. The module is identical Kotlin and gets called from Java without trouble.

#### 2. `SharedConfig.ProxyInfo` extension (`TMessagesProj/src/main/java/org/telegram/messenger/SharedConfig.java`)

Extend the existing class at `SharedConfig.java:376` with three new fields:

```java
public static class ProxyInfo {
    // existing fields…
    public int type;                  // 0 = MTProto/secret, 1 = SOCKS5, 2 = TUIC
    public TuicConfig tuicConfig;     // non-null iff type == 2
    public boolean useForCalls;       // per-proxy; replaces global proxy_enabled_calls
    // existing fields…
}
```

`type` is derivable from existing fields for legacy entries (secret non-empty → MTProto, else → SOCKS5), so the migration is mechanical: old entries read with `type = secret.isEmpty() ? SOCKS5 : MTPROTO`, `tuicConfig = null`, `useForCalls = preferences.getBoolean("proxy_enabled_calls", false)` (one-time read).

Proxy-list serialization (`SharedConfig.java:1501`–`1530`) gets a version bump. New format adds `type`, `tuicConfig` fields (only persisted when `type == TUIC`), and `useForCalls`.

`isProxyEnabled()` (`SharedConfig.java:1549`) remains unchanged (a proxy is "enabled" iff `proxy_enabled` true and `currentProxy` set). The global `proxy_enabled_calls` pref stays in place for one release as the migration source, then is removed.

#### 3. `SingBoxController` (new, `TMessagesProj/src/main/java/org/telegram/messenger/SingBoxController.java`)

Thin Java glue around `SingBoxManager`:

- `static void syncWithProxyList()` — reads `SharedConfig.proxyList`, filters to TUIC entries, calls `SingBoxManager.INSTANCE.startAll(list)`. Called once on `ApplicationLoader` init and from `SharedConfig.addProxy` / `deleteProxy` / the (new) edit-in-place callsite reachable from `ProxySettingsActivity` save.
- `static ProxyInfo resolveForConnection(ProxyInfo info)` — if `info.type == TUIC`, returns a *transient* `ProxyInfo` with `address = "127.0.0.1"`, `port = SingBoxManager.getPort(info.tuicConfig)`, `username = "" / password = "" / secret = ""`. Otherwise returns `info` unchanged. Callers that need the on-wire SOCKS5 endpoint (the connection manager + VoIP) go through this.
- `static Instance.Proxy resolveForVoIP(ProxyInfo info)` — same idea, but returns the JNI-bound `Instance.Proxy` directly. Returns `null` when `info.useForCalls == false` or when the TUIC tunnel isn't up.

Failure mode: if sing-box fails to start (port collision, malformed config, native init failure), `getPort()` returns `0`. Both `resolveForConnection` and `resolveForVoIP` return `null` in that case, and the rest of the app behaves as if no proxy is configured. We log the failure and post a `NotificationCenter` event so the UI can show a banner; we do **not** silently fall back to a direct connection without a visible indicator.

#### 4. VoIP wiring

**Java (`VoIPService.java`)** — at the proxy-construction block (`VoIPService.java:3451`–`3459`):

```java
// before
if (preferences.getBoolean("proxy_enabled", false) && preferences.getBoolean("proxy_enabled_calls", false)) {
    final String server = preferences.getString("proxy_ip", null);
    final String secret = preferences.getString("proxy_secret", null);
    if (!TextUtils.isEmpty(server) && TextUtils.isEmpty(secret)) {
        proxy = new Instance.Proxy(server, port, user, pass);
    }
}

// after
ProxyInfo current = SharedConfig.currentProxy;
if (SharedConfig.isProxyEnabled() && current != null && current.useForCalls) {
    proxy = SingBoxController.resolveForVoIP(current);
}
```

**JNI (`TMessagesProj/jni/voip/org_telegram_messenger_voip_Instance.cpp`)** — the `a7ed710b` fix: in the `Descriptor::config` initializer (line ~780), set `.allowTCP = true` unconditionally. (`forceTcp` keeps its independent meaning: it selects TCP relay endpoints in the legacy reflector path. `allowTCP` controls whether TCP is a permitted ICE candidate type at all, and disabling it leaves zero candidates when a proxy disables UDP gathering.)

**tgcalls (`TMessagesProj/jni/voip/tgcalls/`)** — translate the two Telegram-X submodule commits as direct file edits:

- New file `tgcalls/v2/Socks5ProxySocket.h` (~196 lines) and `tgcalls/v2/Socks5ProxySocket.cpp` (~642 lines), copied verbatim from `49651f2`. Provides `Socks5TcpProxySocket` (CONNECT command, analog of `rtc::AsyncHttpsProxySocket`) and `Socks5UdpProxySocket` (UDP ASSOCIATE per RFC 1928 §7 — owns the UDP datagram socket plus a TCP control channel keeping the association alive).
- `tgcalls/v2/NativeNetworkingImpl.cpp`: extend `WrappedBasicPacketSocketFactory` to wrap UDP and TCP sockets in `Socks5*ProxySocket` when a SOCKS5 proxy is set. Stop calling `PORTALLOCATOR_DISABLE_UDP` for SOCKS5 (kept disabled only when no SOCKS5 UDP path exists — i.e. never, after this patch). STUN/server-reflexive candidates stay disabled for any proxy.
- `tgcalls/v2/ReflectorPort.cpp`: route `CreateClientRawTcpSocket` through `Socks5TcpProxySocket` when proxy info indicates SOCKS5.
- `TMessagesProj/jni/voip/CMakeLists.txt`: add `tgcalls/v2/Socks5ProxySocket.cpp` to the tgcalls source list.

We **do not** port the HTTP CONNECT bits from `df0f85d` (ProxyType enum, `PROXY_HTTPS` mapping, `TgCallsCryptStringImpl.h`). When the only proxy in play is loopback SOCKS5, those changes are dead weight.

#### 5. `tg://tuic` URL scheme

**`LaunchActivity.java:414`** — extend the `isProxy` matcher to also accept `tg:tuic`/`tg://tuic` and `t.me/tuic`. The downstream handler currently builds a SOCKS or MTProto `ProxyInfo` from query params; add a TUIC branch that parses `server`, `port`, `uuid`, `password`, `congestion_control` (optional, default `bbr`), `tls_insecure` (optional, default `1`), and `calls` (optional, default `1`).

**`SharedConfig`** — add a `static ProxyInfo addTuicProxy(TuicConfig cfg, boolean useForCalls)` that appends and triggers `SingBoxController.syncWithProxyList()`.

**Sharing path** — wherever the existing UI builds a `tg://socks?...`/`tg://proxy?...` share link (search for `t.me/socks` / `t.me/proxy` references in `ProxyListActivity.java`, `SharedConfig.java:411`), add a TUIC branch emitting `tg://tuic?server=...&port=...&uuid=...&password=...&congestion_control=...&tls_insecure=...&calls=...`.

#### 6. Default-proxy bake

**Build side (`TMessagesProj/build.gradle`)** — read `default.proxy.link` from `local.properties`, surface it as `BuildConfig.DEFAULT_PROXY_LINK`:

```gradle
buildConfigField "String", "DEFAULT_PROXY_LINK", "\"${project.findProperty('default.proxy.link') ?: ''}\""
```

**Java side (new `DefaultProxyLinkParser.java`)** — ported from Telegram-X's `app/src/main/java/org/thunderdog/challegram/unsorted/DefaultProxyLinkParser.java`, minus the `tg://http` branch. Parses `tg://proxy`, `tg://socks`, `tg://tuic`.

**First-run hook (`MessagesController` init or `ApplicationLoader.applicationInit`)** — if `SharedConfig.proxyList.isEmpty()` and `BuildConfig.DEFAULT_PROXY_LINK` is non-empty, parse it, append to the proxy list with `useForCalls = true`, set as `currentProxy`, enable proxy. Guarded by a one-time `default_proxy_imported` flag in shared prefs so manual deletion is sticky.

#### 7. Settings UI

`ProxySettingsActivity.java` (the edit/add screen): add a TUIC mode that swaps the address+port+user+pass form for server+port+uuid+password+congestion-control+TLS-insecure inputs. Modeled on Telegram-X's `EditProxyController.MODE_TUIC`.

`ProxyListActivity.java`: every row gains a "Use for calls" checkbox bound to `ProxyInfo.useForCalls`, persisting via `SharedConfig.saveProxyList()`. The old global toggle in the same screen is hidden once any per-row override exists (we keep it visible during the migration release for discoverability, then remove it).

## Data flow

### Adding a TUIC proxy
1. User pastes `tg://tuic?server=...&port=...&uuid=...&password=...` into Telegram or taps a share link.
2. `LaunchActivity.handleIntent` matches the `tg:tuic`/`tg://tuic` scheme and calls `SharedConfig.addTuicProxy(parsedCfg, calls=true)`.
3. `SharedConfig.addProxy` appends, persists, and calls `SingBoxController.syncWithProxyList()`.
4. `SingBoxController.syncWithProxyList()` rebuilds the sing-box config from all TUIC entries and starts/restarts the in-process instance. Each entry gets a fresh `127.0.0.1:port` SOCKS5 inbound.

### Using a TUIC proxy for data
1. User taps the new entry in `ProxyListActivity` → `currentProxy = info`, `proxy_enabled = true`.
2. `MessagesController.checkProxyInfo(...)` (or wherever the connection manager is currently fed) calls `SingBoxController.resolveForConnection(currentProxy)` and passes the resulting synthetic SOCKS5 endpoint to `ConnectionsManager.setProxySettings(...)`.
3. tgnet opens SOCKS5 to `127.0.0.1:port`; sing-box translates to TUIC and forwards.

### Using a TUIC proxy for VoIP
1. User starts a 1-on-1 call; `VoIPService.makeInstance(...)` runs.
2. `SingBoxController.resolveForVoIP(currentProxy)` returns an `Instance.Proxy(127.0.0.1, port, "", "")`.
3. tgcalls is told this is a SOCKS5 proxy. `NativeNetworkingImpl` wraps every ICE host candidate and reflector relay socket with the new `Socks5TcpProxySocket` / `Socks5UdpProxySocket`. ICE gathers UDP+TCP relay candidates through the loopback SOCKS5 inbound. sing-box delivers them as TUIC datagrams/streams.
4. End-to-end smoke test mirror of Telegram-X's: redroid VM → sing-box loopback → external TUIC server → reflector → echo bot.

## Error handling and edge cases

| Scenario | Behavior |
|----------|----------|
| sing-box fails to start (port collision, bad config) | `getPort()` returns 0; `resolveFor*` return null; UI shows banner via `NotificationCenter`. App behaves as if proxy disabled. **No silent direct connection.** |
| TUIC proxy added while a call is in progress | Existing call keeps its current endpoint (sing-box restart only re-binds *new* SOCKS inbounds). Next call picks up the new tunnel. |
| User edits a TUIC config → `identity()` changes (server/port/uuid) | Treated as a new entry by `SingBoxManager.startAll` (identity-keyed). Port reassignment happens on next `syncWithProxyList`. |
| `default.proxy.link` malformed at build time | Parser logs error on first launch and skips import; user lands in clean proxy-list state. |
| Legacy proxy with `proxy_enabled_calls = true`, after upgrade | One-time migration copies the global flag to `useForCalls` on every existing proxy, then drops the global pref after one release. |
| User force-enables TCP via `dbg_force_tcp_in_calls` while using a TUIC proxy | Still works: `forceTcp` selects `ENDPOINT_TYPE_TCP_RELAY` in `VoIPService.java:3427`; the tgcalls JNI fix ensures `allowTCP=true` so the TCP relay isn't dropped from ICE. |
| Group call started while TUIC proxy active | Group call traffic egresses directly (known gap, not in scope). Could be flagged with a UI hint later. |

## Testing

Telegram-Android doesn't ship an automated VoIP test suite, so verification is hands-on. The test plan parallels the smoke test that exited Telegram-X's `a7ed710b`:

1. **Data path**: with a known-good TUIC server, set the proxy in settings, confirm `Settings → Devices` shows new session, send/receive a message.
2. **Voice call**: place a 1-on-1 voice call to a second test account; verify ICE picks a relay candidate pair to a Telegram reflector, audio becomes writable within ~500 ms (matching the Telegram-X smoke benchmark).
3. **Video call**: same as above with camera enabled; confirm video frames flow both directions.
4. **Per-proxy flag**: with two proxies (one with `useForCalls=true`, one false), confirm that toggling `currentProxy` between them changes whether the call traffic uses the tunnel.
5. **Default-proxy bake**: build with a `default.proxy.link=tg://tuic?...` in `local.properties`, install on a fresh redroid container, verify the proxy appears on first launch.
6. **URL sharing**: copy a TUIC proxy link from `ProxyListActivity`, paste into another account's chat, confirm the link round-trips and imports correctly.

The user runs Telegram in a redroid x86_64 container ([[user_redroid]]) with an existing `afatX64` flavor ([[project_afatx64_flavor]]) and justfile recipes ([[project_redroid_tooling]]), so the test loop is already set up. The `justfile` will gain one new recipe (`just singbox` → builds the AAR) and the existing `just install` flow stays unchanged.

## Risks and tradeoffs

- **APK size**: the sing-box AAR is ~75 MB pre-strip and contributes ~10-15 MB per ABI to the final APK. For `afatX64` this is acceptable. For the full `afat` flavor (armv7+arm64+x86+x86_64) the cumulative growth could push past Play Store's 100 MB APK limit, requiring an App Bundle. Out of scope for the redroid use case but worth flagging.
- **Go toolchain dep**: the build now requires Go ≥ 1.22 and `gomobile bind`. The user's existing build pins ([[project_build_toolchain]]) do not include this, and the Dockerfile is already out of sync ([[project_dockerfile_drift]]). We'll add Go to the `justfile` setup notes and document the manual install; we will **not** add Go to the Dockerfile in this port (that's a follow-up).
- **Sing-box version pinning**: `singbox/go/go.mod` pins a specific sagernet/sing-box commit. Security updates require regenerating the AAR. We'll inherit Telegram-X's exact pin; bumping is a separate change.
- **Group call gap**: explicit non-goal but worth tracking. If you ever join a sensitive group video chat over what you believe is a fully-proxied app, you'll be leaking the connection. A future change could thread the proxy through `makeGroupInstance`.

## Open questions

None at design-approval time. All scope decisions confirmed in the brainstorming exchange:

- Functionality not commits → driving force.
- HTTP CONNECT → out.
- TUIC + per-proxy calls flag + default proxy + tg://tuic → in.
- SOCKS5 plumbing → in, but only as TUIC's loopback transport.
- 1-on-1 video → in (free with voice). Group video → out.
