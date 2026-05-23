# TUIC Proxy + VoIP-over-Proxy Port Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the proxy functionality from `../Telegram-X` so this Telegram-Android app supports TUIC proxies (via embedded sing-box) for both data traffic and 1-on-1 voice/video calls, with a per-proxy `useForCalls` flag, `tg://tuic` URL scheme, and a build-time default proxy link.

**Architecture:** Sing-box runs in-process and exposes each TUIC proxy as a loopback `127.0.0.1:<port>` SOCKS5 inbound. The rest of the app sees only SOCKS5 proxies. tgnet's existing SOCKS5 path handles data traffic; tgcalls gets SOCKS5 UDP + TCP socket adapters and an `allowTCP=true` JNI fix so VoIP traverses the same loopback inbound.

**Tech Stack:** Java (TMessagesProj app), Kotlin (singbox library module copied from Telegram-X), Go (`singbox/go/bridge.go` built with `gomobile bind --tags with_quic`), C++ (vendored tgcalls under `TMessagesProj/jni/voip/tgcalls/`), Gradle 8.7 + AGP 8.6.1 + NDK r27.

**Source of truth:** Design spec at `docs/superpowers/specs/2026-05-24-tuic-voip-proxy-port-design.md`. The corresponding Telegram-X work lives at commits `7070d58f..9c178047` in `/home/nb/src/Telegram-X`, with the tgcalls submodule patches at `49651f2` (SOCKS5 sockets) and `df0f85d` (ProxyType enum). Reference the diffs there for exact line-level content where this plan says "copy from Telegram-X".

**Verification model:** This codebase has no JVM unit-test infrastructure (`TMessagesProj_AppTests` contains only `ApplicationLoaderImpl.java`). Tasks verify by (a) `./gradlew :TMessagesProj_App:assembleAfatX64Debug` succeeds and (b) end-of-phase manual smoke tests in redroid using the existing `just install` / `just run` recipes. Adding JUnit is out of scope.

**Commit style:** Match the existing log — short imperative subject, lowercase, body explains why. Use `Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>` trailer (matches the user's recent commit convention, with version bumped for this session).

---

## Phase A — Foundations: sing-box module + ProxyInfo extension

### Task 1: Copy the `singbox/` Gradle module from Telegram-X

**Files:**
- Copy: `/home/nb/src/Telegram-X/singbox/` → `/home/nb/src/Telegram/singbox/` (whole tree)
- Modify: `/home/nb/src/Telegram/settings.gradle` (add `:singbox`)

The module is self-contained Kotlin. Source list to copy:
- `singbox/build.gradle.kts`
- `singbox/.gitignore`
- `singbox/go/bridge.go`
- `singbox/go/build.sh`
- `singbox/go/go.mod`
- `singbox/go/go.sum`
- `singbox/src/main/AndroidManifest.xml`
- `singbox/src/main/kotlin/tgx/singbox/SingBoxManager.kt`
- `singbox/src/main/kotlin/tgx/singbox/TuicConfig.kt`

- [ ] **Step 1: Copy the module tree**

```bash
cp -r /home/nb/src/Telegram-X/singbox /home/nb/src/Telegram/singbox
```

- [ ] **Step 2: Add the module to `settings.gradle`**

After the existing `include` lines (currently up to `:TMessagesProj_AppTests`), append:

```gradle
include ':singbox'
```

Final file head:

```gradle
include ':TMessagesProj'
include ':TMessagesProj_App'
include ':TMessagesProj_AppHuawei'
include ':TMessagesProj_AppHockeyApp'
include ':TMessagesProj_AppStandalone'
include ':TMessagesProj_AppTests'
include ':singbox'
```

- [ ] **Step 3: Add `TMessagesProj_App` dependency on `:singbox`**

In `TMessagesProj_App/build.gradle`, locate the `dependencies { ... }` block and add inside it:

```gradle
    implementation project(':singbox')
```

- [ ] **Step 4: Verify the Kotlin DSL build script works with this Groovy project**

The Telegram-X `singbox/build.gradle.kts` references the `kotlin-android` plugin and the Kotlin std lib. Telegram-Android's root `build.gradle` is Groovy; Gradle handles mixed DSLs natively but the Kotlin plugin needs to be on the classpath. Check the root `build.gradle`:

```bash
grep -n 'kotlin' /home/nb/src/Telegram/build.gradle
```

If no `kotlin-android` plugin is declared, add to the root `build.gradle` `buildscript { dependencies { ... } }`:

```gradle
classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.24"
```

(Match the Kotlin version Telegram-X is on — check `/home/nb/src/Telegram-X/build.gradle.kts` `plugins { kotlin(...) version "..." }`.)

- [ ] **Step 5: Build singbox module without the AAR (validation of Gradle wiring)**

Until `just singbox` produces `singbox/libs/bridge.aar`, the module dep will fail. For this task, only verify Gradle *recognizes* the module:

```bash
env JAVA_HOME=/usr/lib/jvm/java-17-openjdk \
    ANDROID_HOME=$HOME/Android/Sdk \
    ./gradlew projects
```

Expected: `:singbox` appears in the project list. The build of `:singbox` itself can fail at this step (missing AAR) — that's resolved in Task 2.

- [ ] **Step 6: Commit**

```bash
git add singbox/ settings.gradle build.gradle TMessagesProj_App/build.gradle
git commit -m "$(cat <<'EOF'
add singbox/ Kotlin library module from Telegram-X

Copies the module that wraps sing-box as a gomobile AAR with
Start/Stop/IsRunning. The AAR itself is gitignored and built on demand;
see the next commit for the just singbox recipe. TMessagesProj_App
depends on the module so the Java side can call SingBoxManager.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Add `just singbox` recipe and build the bridge AAR

**Files:**
- Modify: `/home/nb/src/Telegram/justfile`
- Output: `/home/nb/src/Telegram/singbox/libs/bridge.aar` (gitignored)

- [ ] **Step 1: Confirm Go toolchain availability**

```bash
go version
which gomobile || echo "gomobile missing"
```

Expected: Go ≥ 1.22. If `gomobile` is missing, install:

```bash
go install golang.org/x/mobile/cmd/gomobile@latest
gomobile init
```

For sing-box's QUIC tag we need sagernet's fork. The `singbox/go/build.sh` from Telegram-X already handles this; read it before the next step:

```bash
cat /home/nb/src/Telegram/singbox/go/build.sh
```

- [ ] **Step 2: Add the `singbox` recipe to the justfile**

Append the following section to `/home/nb/src/Telegram/justfile`. Style matches the existing `apk` recipe — single-line commented description above the recipe, env vars expanded inline.

```just
# build the singbox gomobile AAR (~75 MB, gitignored; required by :apk)
singbox:
    env GO111MODULE=on PATH=$(go env GOPATH)/bin:$PATH \
        bash singbox/go/build.sh
```

Then update the `apk` recipe to depend on the AAR existing. Add a prerequisite line right above the recipe body:

```just
# build the x86_64 debug APK (incremental; Gradle daemon stays warm)
apk: singbox-if-missing
    env JAVA_HOME={{java_home}} \
        ...

# build singbox AAR only if it doesn't exist yet (idempotent guard)
singbox-if-missing:
    test -f singbox/libs/bridge.aar || just singbox
```

- [ ] **Step 3: Build the AAR**

```bash
just singbox
ls -lh singbox/libs/bridge.aar
```

Expected: `bridge.aar` is `~75 MB`. If the build fails, read `singbox/go/build.sh` and resolve missing tools (sagernet's gomobile fork: `go install github.com/sagernet/gomobile/cmd/gomobile@latest`).

- [ ] **Step 4: Verify the `:singbox` Gradle module builds**

```bash
env JAVA_HOME=/usr/lib/jvm/java-17-openjdk \
    ANDROID_HOME=$HOME/Android/Sdk \
    ./gradlew :singbox:assemble
```

Expected: BUILD SUCCESSFUL. The compiled `singbox-debug.aar` / `singbox-release.aar` appear in `singbox/build/outputs/aar/`.

- [ ] **Step 5: Verify the app builds with the new dependency**

```bash
just apk
```

Expected: BUILD SUCCESSFUL. APK at `TMessagesProj_App/build/outputs/apk/afatX64/debug/app.apk`. No Java code uses `SingBoxManager` yet, so the AAR is dead weight at runtime — that's fine.

- [ ] **Step 6: Commit**

```bash
git add justfile
git commit -m "$(cat <<'EOF'
add just singbox recipe to build the gomobile bridge AAR

just apk now depends on singbox-if-missing, which builds the ~75 MB
AAR via gomobile/sagernet's fork on demand. The output stays
gitignored.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Extend `SharedConfig.ProxyInfo` with `type`, `tuicConfig`, `useForCalls` fields

**Files:**
- Modify: `TMessagesProj/src/main/java/org/telegram/messenger/SharedConfig.java`
- Create: `TMessagesProj/src/main/java/org/telegram/messenger/TuicProxyConfig.java` (Java mirror of Kotlin `TuicConfig`)

The Kotlin `tgx.singbox.TuicConfig` is the canonical form used by `SingBoxManager`, but we want a Java-side representation that the rest of TMessagesProj manipulates and serializes. The Java class converts to/from the Kotlin one when crossing the module boundary.

- [ ] **Step 1: Create `TuicProxyConfig.java`**

```java
package org.telegram.messenger;

public class TuicProxyConfig {
    public String server;
    public int port;
    public String uuid;
    public String password;
    public String congestionControl;
    public boolean tlsInsecure;

    public TuicProxyConfig(String server, int port, String uuid, String password,
                           String congestionControl, boolean tlsInsecure) {
        this.server = server == null ? "" : server;
        this.port = port;
        this.uuid = uuid == null ? "" : uuid;
        this.password = password == null ? "" : password;
        this.congestionControl = (congestionControl == null || congestionControl.isEmpty()) ? "bbr" : congestionControl;
        this.tlsInsecure = tlsInsecure;
    }

    public String identity() {
        return server + ":" + port + ":" + uuid;
    }

    public tgx.singbox.TuicConfig toKotlin() {
        return new tgx.singbox.TuicConfig(server, port, uuid, password, congestionControl, tlsInsecure);
    }
}
```

- [ ] **Step 2: Add type constants and new fields to `SharedConfig.ProxyInfo`**

In `SharedConfig.java`, locate the schema-version constants near line 57:

```java
private final static int PROXY_SCHEMA_V2 = 2;
private final static int PROXY_CURRENT_SCHEMA_VERSION = PROXY_SCHEMA_V2;
```

Replace with:

```java
private final static int PROXY_SCHEMA_V2 = 2;
private final static int PROXY_SCHEMA_V3 = 3;
private final static int PROXY_CURRENT_SCHEMA_VERSION = PROXY_SCHEMA_V3;

public static final int PROXY_TYPE_MTPROTO = 0;
public static final int PROXY_TYPE_SOCKS5 = 1;
public static final int PROXY_TYPE_TUIC = 2;
```

In the `ProxyInfo` class (line 376), add three fields next to the existing ones (after `secret`):

```java
public int type;                       // PROXY_TYPE_* constant
public TuicProxyConfig tuicConfig;     // non-null iff type == PROXY_TYPE_TUIC
public boolean useForCalls;
```

And update the existing constructor to set `type` based on existing inputs (for backward compat with all existing callers):

```java
public ProxyInfo(String address, int port, String username, String password, String secret) {
    this.address = address;
    this.port = port;
    this.username = username;
    this.password = password;
    this.secret = secret;
    if (this.address == null) this.address = "";
    if (this.password == null) this.password = "";
    if (this.username == null) this.username = "";
    if (this.secret == null) this.secret = "";
    this.type = TextUtils.isEmpty(this.secret) ? PROXY_TYPE_SOCKS5 : PROXY_TYPE_MTPROTO;
    this.tuicConfig = null;
    this.useForCalls = false;
}
```

Add a second constructor for TUIC:

```java
public static ProxyInfo forTuic(TuicProxyConfig cfg, boolean useForCalls) {
    ProxyInfo info = new ProxyInfo(cfg.server, cfg.port, "", "", "");
    info.type = PROXY_TYPE_TUIC;
    info.tuicConfig = cfg;
    info.useForCalls = useForCalls;
    return info;
}
```

- [ ] **Step 3: Update `loadProxyList()` to read V3 format and migrate from V2**

In `SharedConfig.java:1430` (`loadProxyList`), the existing V2 branch (line 1452) reads only the five strings + ping. Add a V3 branch that also reads `type`, optional `tuicConfig`, and `useForCalls`. The V2 branch stays so old installs upgrade smoothly — we migrate by populating defaults from the existing global `proxy_enabled_calls`.

Replace the body of the `if (version == PROXY_SCHEMA_V2) { ... }` / `else { ... unknown version ... }` block with:

```java
boolean legacyCallsFlag = preferences.getBoolean("proxy_enabled_calls", false);

if (version == PROXY_SCHEMA_V2 || version == PROXY_SCHEMA_V3) {
    count = data.readInt32(false);
    for (int i = 0; i < count; i++) {
        ProxyInfo info = new ProxyInfo(
                data.readString(false),
                data.readInt32(false),
                data.readString(false),
                data.readString(false),
                data.readString(false));

        info.ping = data.readInt64(false);
        info.availableCheckTime = data.readInt64(false);

        if (version >= PROXY_SCHEMA_V3) {
            info.type = data.readInt32(false);
            info.useForCalls = data.readBool(false);
            if (info.type == PROXY_TYPE_TUIC) {
                info.tuicConfig = new TuicProxyConfig(
                        data.readString(false),
                        data.readInt32(false),
                        data.readString(false),
                        data.readString(false),
                        data.readString(false),
                        data.readBool(false));
            }
        } else {
            // V2 → V3 migration: derive type from secret; carry over global flag
            info.useForCalls = legacyCallsFlag;
        }

        proxyList.add(0, info);
        if (currentProxy == null && !TextUtils.isEmpty(proxyAddress)) {
            if (proxyAddress.equals(info.address) && proxyPort == info.port && proxyUsername.equals(info.username) && proxyPassword.equals(info.password)) {
                currentProxy = info;
            }
        }
    }
} else {
    FileLog.e("Unknown proxy schema version: " + version);
}
```

Note: `SerializedData.readBool(boolean)` and `writeBool(boolean)` already exist (`SerializedData.java:144` / `:367`), so the calls above work as-is.

- [ ] **Step 4: Update `saveProxyList()` to write V3 format**

In `SharedConfig.java:1500` (`saveProxyList`), the existing V2 write loop writes 5 strings + 2 longs per entry. Extend it to also write `type`, `useForCalls`, and (if TUIC) the `tuicConfig` fields. Replace the inner per-entry block with:

```java
for (int a = count - 1; a >= 0; a--) {
    ProxyInfo info = infoToSerialize.get(a);
    serializedData.writeString(info.address != null ? info.address : "");
    serializedData.writeInt32(info.port);
    serializedData.writeString(info.username != null ? info.username : "");
    serializedData.writeString(info.password != null ? info.password : "");
    serializedData.writeString(info.secret != null ? info.secret : "");

    serializedData.writeInt64(info.ping);
    serializedData.writeInt64(info.availableCheckTime);

    serializedData.writeInt32(info.type);
    serializedData.writeBool(info.useForCalls);
    if (info.type == PROXY_TYPE_TUIC && info.tuicConfig != null) {
        TuicProxyConfig c = info.tuicConfig;
        serializedData.writeString(c.server);
        serializedData.writeInt32(c.port);
        serializedData.writeString(c.uuid);
        serializedData.writeString(c.password);
        serializedData.writeString(c.congestionControl);
        serializedData.writeBool(c.tlsInsecure);
    }
}
```

- [ ] **Step 5: Update `addProxy()` dedup check to handle TUIC identity**

`SharedConfig.java:1534` compares by `address/port/username/password/secret`. For TUIC entries the address is the server and username/password/secret are empty, so the existing check would treat two different-UUID TUICs to the same server as duplicates. Tighten:

```java
public static ProxyInfo addProxy(ProxyInfo proxyInfo) {
    loadProxyList();
    int count = proxyList.size();
    for (int a = 0; a < count; a++) {
        ProxyInfo info = proxyList.get(a);
        if (info.type != proxyInfo.type) continue;
        if (proxyInfo.type == PROXY_TYPE_TUIC) {
            if (proxyInfo.tuicConfig != null && info.tuicConfig != null
                    && proxyInfo.tuicConfig.identity().equals(info.tuicConfig.identity())) {
                return info;
            }
        } else {
            if (proxyInfo.address.equals(info.address) && proxyInfo.port == info.port
                    && proxyInfo.username.equals(info.username)
                    && proxyInfo.password.equals(info.password)
                    && proxyInfo.secret.equals(info.secret)) {
                return info;
            }
        }
    }
    proxyList.add(0, proxyInfo);
    saveProxyList();
    return proxyInfo;
}
```

- [ ] **Step 6: Build to verify the data-model changes compile**

```bash
just apk
```

Expected: BUILD SUCCESSFUL. (No callers use the new fields yet, but the existing code must still compile against the changed shape.)

- [ ] **Step 7: Commit**

```bash
git add TMessagesProj/src/main/java/org/telegram/messenger/SharedConfig.java \
        TMessagesProj/src/main/java/org/telegram/messenger/TuicProxyConfig.java
git commit -m "$(cat <<'EOF'
extend SharedConfig.ProxyInfo for TUIC and per-proxy calls flag

ProxyInfo gains type (MTProto/SOCKS5/TUIC), tuicConfig, and useForCalls.
Bumps proxy-list serialization to V3 and migrates from V2 by carrying
the global proxy_enabled_calls onto each entry. Adds TuicProxyConfig
as a Java mirror of singbox's Kotlin TuicConfig.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase B — TUIC integration glue

### Task 4: Create `SingBoxController` for sync + resolve

**Files:**
- Create: `TMessagesProj/src/main/java/org/telegram/messenger/SingBoxController.java`

This class is the only place in TMessagesProj that calls `SingBoxManager`. It owns:
- syncing the running sing-box config with the live `SharedConfig.proxyList`
- translating a TUIC `ProxyInfo` into a synthetic loopback SOCKS5 `ProxyInfo` for the connection manager
- translating a TUIC `ProxyInfo` into an `Instance.Proxy` for VoIP

- [ ] **Step 1: Write the class**

```java
package org.telegram.messenger;

import org.telegram.messenger.voip.Instance;
import tgx.singbox.SingBoxManager;
import tgx.singbox.TuicConfig;

import java.util.ArrayList;
import java.util.List;

public class SingBoxController {

    private SingBoxController() {}

    public static void syncWithProxyList() {
        List<TuicConfig> configs = new ArrayList<>();
        for (SharedConfig.ProxyInfo info : SharedConfig.proxyList) {
            if (info.type == SharedConfig.PROXY_TYPE_TUIC && info.tuicConfig != null) {
                configs.add(info.tuicConfig.toKotlin());
            }
        }
        SingBoxManager.INSTANCE.startAll(configs);
    }

    /** Returns a transient ProxyInfo pointing at the loopback SOCKS5 inbound
     *  for a TUIC entry. Returns the input unchanged for non-TUIC entries.
     *  Returns null if the TUIC tunnel is not currently up. */
    public static SharedConfig.ProxyInfo resolveForConnection(SharedConfig.ProxyInfo info) {
        if (info == null) return null;
        if (info.type != SharedConfig.PROXY_TYPE_TUIC) return info;
        if (info.tuicConfig == null) return null;
        int port = SingBoxManager.INSTANCE.getPort(info.tuicConfig.toKotlin());
        if (port == 0) return null;
        SharedConfig.ProxyInfo synthetic = new SharedConfig.ProxyInfo("127.0.0.1", port, "", "", "");
        synthetic.type = SharedConfig.PROXY_TYPE_SOCKS5;
        synthetic.useForCalls = info.useForCalls;
        return synthetic;
    }

    /** Returns an Instance.Proxy for the given proxy entry, or null if calls
     *  should not be tunnelled (useForCalls=false, TUIC tunnel down, or the
     *  entry is an MTProto-secret proxy which tgcalls doesn't speak). */
    public static Instance.Proxy resolveForVoIP(SharedConfig.ProxyInfo info) {
        if (info == null || !info.useForCalls) return null;
        if (info.type == SharedConfig.PROXY_TYPE_MTPROTO) return null;
        SharedConfig.ProxyInfo resolved = resolveForConnection(info);
        if (resolved == null) return null;
        return new Instance.Proxy(resolved.address, resolved.port, resolved.username, resolved.password);
    }
}
```

- [ ] **Step 2: Build to confirm wiring compiles**

```bash
just apk
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add TMessagesProj/src/main/java/org/telegram/messenger/SingBoxController.java
git commit -m "$(cat <<'EOF'
add SingBoxController glue between SharedConfig and singbox

Owns the TUIC -> loopback-SOCKS5 translation in two flavours:
resolveForConnection returns a synthetic ProxyInfo for tgnet,
resolveForVoIP returns an Instance.Proxy for tgcalls. syncWithProxyList
rebuilds the sing-box config from current SharedConfig.proxyList.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Wire `syncWithProxyList()` into `SharedConfig` mutators and app init

**Files:**
- Modify: `TMessagesProj/src/main/java/org/telegram/messenger/SharedConfig.java`
- Modify: `TMessagesProj/src/main/java/org/telegram/messenger/ApplicationLoader.java`

- [ ] **Step 1: Call sync from `addProxy` and `deleteProxy`**

In `SharedConfig.java:1543`, after `proxyList.add(0, proxyInfo); saveProxyList();`, add:

```java
SingBoxController.syncWithProxyList();
```

In `SharedConfig.java:1552` (`deleteProxy`), at the end of the method (after the existing `saveProxyList()` call — check the method body first), add the same line:

```java
SingBoxController.syncWithProxyList();
```

- [ ] **Step 2: Call sync once at app init**

In `ApplicationLoader.java`, locate the `applicationInited()` (or equivalent — search for "loadProxyList" or the first `MessagesController.getInstance(0)`-style call) and add right after the proxy list is loaded:

```java
SingBoxController.syncWithProxyList();
```

Find the right spot:

```bash
grep -n "loadProxyList\|SharedConfig.loadProxyList" TMessagesProj/src/main/java/org/telegram/messenger/ApplicationLoader.java
```

If `loadProxyList` is not explicitly called from `ApplicationLoader` (it's static-loaded via `SharedConfig.loadConfig()` reference at class-init time), add an explicit call:

```java
SharedConfig.loadProxyList();
SingBoxController.syncWithProxyList();
```

Place it in `applicationInited()` after `SharedConfig.loadConfig()`.

- [ ] **Step 3: Build**

```bash
just apk
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Smoke-test sing-box start on a fresh redroid install**

```bash
just install
just run
just adb logcat -d | grep -E "SingBox|sing-box"
```

Expected: no errors. With no TUIC proxies configured, `startAll(emptyList)` is called and is a no-op. Verify by checking that the logcat doesn't contain a stack trace from `SingBoxManager`.

- [ ] **Step 5: Commit**

```bash
git add TMessagesProj/src/main/java/org/telegram/messenger/SharedConfig.java \
        TMessagesProj/src/main/java/org/telegram/messenger/ApplicationLoader.java
git commit -m "$(cat <<'EOF'
sync sing-box config with SharedConfig.proxyList

ApplicationLoader.applicationInited calls SingBoxController.sync once
at startup; SharedConfig.addProxy/deleteProxy call it on every change.
sing-box rebinds inbounds whenever the TUIC set changes.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: Thread `resolveForConnection` through `ConnectionsManager` proxy-apply

**Files:**
- Modify: `TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java`

The existing path at `ConnectionsManager.java:618` reads `proxy_ip`/`proxy_port`/`proxy_user`/`proxy_pass`/`proxy_secret` from `preferences` and calls a native `native_setProxySettings`. Today, when `currentProxy` is a TUIC entry, those prefs (which were set by `ProxySettingsActivity` or the URL-import path) hold whatever the user typed — not the loopback endpoint. We need to substitute the resolved endpoint.

- [ ] **Step 1: Read the existing block**

```bash
grep -n -B2 -A20 "proxy_enabled.*false.*TextUtils.isEmpty.*proxyAddress" TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java | head -40
```

- [ ] **Step 2: Substitute the resolved endpoint**

Replace the proxy-apply block at line ~618. The current shape reads keys; the new shape consults `SharedConfig.currentProxy` first:

```java
SharedConfig.ProxyInfo current = SharedConfig.currentProxy;
SharedConfig.ProxyInfo resolved = SingBoxController.resolveForConnection(current);
boolean enabled = preferences.getBoolean("proxy_enabled", false) && resolved != null;
if (enabled) {
    native_setProxySettings(currentAccount, resolved.address, resolved.port, resolved.username, resolved.password, resolved.secret);
} else {
    native_setProxySettings(currentAccount, "", 0, "", "", "");
}
```

(Adjust the JNI call signature to match what currently exists — the line shows `native_setProxySettings` taking address/port/user/pass/secret strings + ints. Don't rename anything.)

If `currentAccount` isn't in scope in that block, use the existing variable (the method already targets one account — check the enclosing method signature).

- [ ] **Step 3: Build**

```bash
just apk
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add TMessagesProj/src/main/java/org/telegram/tgnet/ConnectionsManager.java
git commit -m "$(cat <<'EOF'
route ConnectionsManager proxy-apply through SingBoxController

When the active proxy is a TUIC entry, substitute the loopback SOCKS5
endpoint that sing-box exposes for that entry. Other proxy types pass
through unchanged.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase C — URL scheme (`tg://tuic`)

### Task 7: Extend `LaunchActivity.handleIntent` to recognize `tg://tuic`

**Files:**
- Modify: `TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java`

- [ ] **Step 1: Extend the `isProxy` matcher**

At `LaunchActivity.java:414`:

```java
isProxy = url.startsWith("tg:proxy") || url.startsWith("tg://proxy") || url.startsWith("tg:socks") || url.startsWith("tg://socks");
```

Change to:

```java
isProxy = url.startsWith("tg:proxy") || url.startsWith("tg://proxy")
        || url.startsWith("tg:socks") || url.startsWith("tg://socks")
        || url.startsWith("tg:tuic") || url.startsWith("tg://tuic");
```

- [ ] **Step 2: Build**

```bash
just apk
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

(Combine with Task 8 — keep this change unstaged for now. Task 8 makes the full URL parse work; committing them together keeps the history coherent.)

---

### Task 8: Parse `tg://tuic` URLs in `AndroidUtilities`

**Files:**
- Modify: `TMessagesProj/src/main/java/org/telegram/messenger/AndroidUtilities.java`

The parse paths at `AndroidUtilities.java:4602` and `:4614-4627` handle the `tg:` scheme and the `t.me/socks`/`t.me/proxy` `https:` paths. We add a TUIC branch for both.

- [ ] **Step 1: Read the existing block**

Read `AndroidUtilities.java` lines 4580–4650 to understand the local variables (`address`, `port`, `user`, `password`, `secret`, `scheme`, `data`).

- [ ] **Step 2: Add TUIC variables and parse branches**

At the top of the parsing block (where `address`, `port`, etc. are declared), add four more locals:

```java
String tuicUuid = null;
String tuicPassword = null;
String tuicCongestion = null;
String tuicTlsInsecure = null;
String tuicCalls = null;
```

In the `t.me/...` `https:` branch (at line ~4602), extend the `path.startsWith(...)` check:

```java
if (path.startsWith("/socks") || path.startsWith("/proxy") || path.startsWith("/tuic")) {
    address = data.getQueryParameter("server");
    if (AndroidUtilities.checkHostForPunycode(address)) {
        address = IDN.toASCII(address, IDN.ALLOW_UNASSIGNED);
    }
    port = data.getQueryParameter("port");
    user = data.getQueryParameter("user");
    password = data.getQueryParameter("pass");
    secret = data.getQueryParameter("secret");
    if (path.startsWith("/tuic")) {
        tuicUuid = data.getQueryParameter("uuid");
        tuicPassword = data.getQueryParameter("password");
        tuicCongestion = data.getQueryParameter("congestion_control");
        tuicTlsInsecure = data.getQueryParameter("tls_insecure");
        tuicCalls = data.getQueryParameter("calls");
    }
}
```

In the `scheme.equals("tg")` branch (at line ~4616), extend similarly:

```java
if (url.startsWith("tg:proxy") || url.startsWith("tg://proxy") || url.startsWith("tg:socks") || url.startsWith("tg://socks") || url.startsWith("tg:tuic") || url.startsWith("tg://tuic")) {
    url = url.replace("tg:proxy", "tg://telegram.org")
             .replace("tg://proxy", "tg://telegram.org")
             .replace("tg://socks", "tg://telegram.org")
             .replace("tg:socks", "tg://telegram.org")
             .replace("tg://tuic", "tg://telegram.org")
             .replace("tg:tuic", "tg://telegram.org");
    data = Uri.parse(url);
    address = data.getQueryParameter("server");
    if (AndroidUtilities.checkHostForPunycode(address)) {
        address = IDN.toASCII(address, IDN.ALLOW_UNASSIGNED);
    }
    port = data.getQueryParameter("port");
    user = data.getQueryParameter("user");
    password = data.getQueryParameter("pass");
    secret = data.getQueryParameter("secret");
    // detect TUIC by presence of uuid (a TUIC link always has one)
    String maybeUuid = data.getQueryParameter("uuid");
    if (maybeUuid != null) {
        tuicUuid = maybeUuid;
        tuicPassword = data.getQueryParameter("password");
        tuicCongestion = data.getQueryParameter("congestion_control");
        tuicTlsInsecure = data.getQueryParameter("tls_insecure");
        tuicCalls = data.getQueryParameter("calls");
    }
}
```

(Source path: the original URL string is mutated, but `tuicUuid` carries the discriminant.)

- [ ] **Step 3: Add a TUIC branch in the final import block**

At line ~4630 — `if (!TextUtils.isEmpty(address) && !TextUtils.isEmpty(port))` — extend:

```java
if (!TextUtils.isEmpty(address) && !TextUtils.isEmpty(port)) {
    if (user == null) user = "";
    if (password == null) password = "";
    if (secret == null) secret = "";
    if (tuicUuid != null) {
        if (invoked) showTuicProxyAlert(activity, address, port, tuicUuid,
                tuicPassword == null ? "" : tuicPassword,
                tuicCongestion == null ? "bbr" : tuicCongestion,
                tuicTlsInsecure == null || !tuicTlsInsecure.equals("0"),
                tuicCalls == null || !tuicCalls.equals("0"));
        return true;
    }
    if (invoked) showProxyAlert(activity, address, port, user, password, secret);
    return true;
}
```

- [ ] **Step 4: Implement `showTuicProxyAlert`**

Below the existing `showProxyAlert` at line 4677, add:

```java
public static void showTuicProxyAlert(Activity activity, final String address, final String port,
                                       final String uuid, final String password,
                                       final String congestion, final boolean tlsInsecure,
                                       final boolean useForCalls) {
    final BottomSheet.Builder builder = new BottomSheet.Builder(activity);
    builder.setApplyTopPadding(false);
    builder.setApplyBottomPadding(false);
    final Runnable dismiss = builder.getDismissRunnable();

    final LinearLayout linearLayout = new LinearLayout(activity);
    linearLayout.setOrientation(LinearLayout.VERTICAL);
    builder.setCustomView(linearLayout);

    final TextView headerView = TextHelper.makeTextView(activity, 20, Theme.key_dialogTextBlack, true);
    headerView.setText(getString(R.string.UseProxyTitle));
    linearLayout.addView(headerView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.FILL_HORIZONTAL, 22, 18, 22, 0));

    final TableView tableView = new TableView(activity, null);
    linearLayout.addView(tableView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.FILL_HORIZONTAL, 14, 18, 14, 0));

    tableView.addRow(getString(R.string.UseProxyAddress), address);
    tableView.addRow(getString(R.string.UseProxyPort), port);
    tableView.addRow("Type", "TUIC");
    tableView.addRow("UUID", uuid);

    final ButtonWithCounterView buttonView = new ButtonWithCounterView(activity, null).setRound();
    buttonView.setText(getString(R.string.ConnectingConnectProxy));
    buttonView.setOnClickListener(v -> {
        int p = Utilities.parseInt(port);
        TuicProxyConfig cfg = new TuicProxyConfig(address, p, uuid, password, congestion, tlsInsecure);
        SharedConfig.ProxyInfo info = SharedConfig.ProxyInfo.forTuic(cfg, useForCalls);
        info = SharedConfig.addProxy(info);
        SharedConfig.currentProxy = info;
        SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
        editor.putBoolean("proxy_enabled", true);
        editor.apply();
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
        dismiss.run();
    });
    linearLayout.addView(buttonView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL, 14, 18, 14, 14));

    builder.show();
}
```

- [ ] **Step 5: Build**

```bash
just apk
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Smoke test — send a `tg://tuic` URL into the app**

Compose a test URL (use any plausible values):

```
tg://tuic?server=example.com&port=443&uuid=11111111-2222-3333-4444-555555555555&password=hunter2&congestion_control=bbr&tls_insecure=1&calls=1
```

```bash
just adb shell am start -a android.intent.action.VIEW \
    -d 'tg://tuic?server=example.com&port=443&uuid=...&password=hunter2'
```

Expected: the proxy-import bottom sheet appears showing "Type: TUIC". Confirming "Connect" appends the proxy to the list. (Actual data flow won't work — the server is fake — but the import path is exercised.)

- [ ] **Step 7: Commit Tasks 7 + 8 together**

```bash
git add TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java \
        TMessagesProj/src/main/java/org/telegram/messenger/AndroidUtilities.java
git commit -m "$(cat <<'EOF'
parse tg://tuic and t.me/tuic proxy URLs

LaunchActivity recognizes the new scheme; AndroidUtilities parses
server/port/uuid/password/congestion_control/tls_insecure/calls and
calls a new showTuicProxyAlert that builds a TUIC ProxyInfo and
appends it via SharedConfig.addProxy (which triggers a sing-box
reconfigure).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: Emit `tg://tuic` from `ProxyInfo.getLink()` for TUIC entries

**Files:**
- Modify: `TMessagesProj/src/main/java/org/telegram/messenger/SharedConfig.java`

- [ ] **Step 1: Branch on `type` in `getLink()`**

`SharedConfig.java:410` (`getLink`). Replace the body with:

```java
public String getLink() {
    StringBuilder url;
    if (type == PROXY_TYPE_TUIC && tuicConfig != null) {
        url = new StringBuilder("https://t.me/tuic?");
        try {
            url.append("server=").append(URLEncoder.encode(tuicConfig.server, "UTF-8"));
            url.append("&port=").append(tuicConfig.port);
            url.append("&uuid=").append(URLEncoder.encode(tuicConfig.uuid, "UTF-8"));
            url.append("&password=").append(URLEncoder.encode(tuicConfig.password, "UTF-8"));
            url.append("&congestion_control=").append(URLEncoder.encode(tuicConfig.congestionControl, "UTF-8"));
            url.append("&tls_insecure=").append(tuicConfig.tlsInsecure ? "1" : "0");
            url.append("&calls=").append(useForCalls ? "1" : "0");
        } catch (UnsupportedEncodingException ignored) {}
        return url.toString();
    }

    url = new StringBuilder(!TextUtils.isEmpty(secret) ? "https://t.me/proxy?" : "https://t.me/socks?");
    try {
        url.append("server=").append(URLEncoder.encode(address, "UTF-8")).append("&port=").append(port);
        if (!TextUtils.isEmpty(username)) url.append("&user=").append(URLEncoder.encode(username, "UTF-8"));
        if (!TextUtils.isEmpty(password)) url.append("&pass=").append(URLEncoder.encode(password, "UTF-8"));
        if (!TextUtils.isEmpty(secret)) url.append("&secret=").append(URLEncoder.encode(secret, "UTF-8"));
        url.append("&calls=").append(useForCalls ? "1" : "0");
    } catch (UnsupportedEncodingException ignored) {}
    return url.toString();
}
```

(Note: SOCKS/MTProto links also now carry the `calls` flag, matching Telegram-X commit `3f1410b9`.)

- [ ] **Step 2: Build**

```bash
just apk
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add TMessagesProj/src/main/java/org/telegram/messenger/SharedConfig.java
git commit -m "$(cat <<'EOF'
emit tg://tuic share links and include useForCalls in all proxy links

ProxyInfo.getLink branches on type to produce a TUIC link with UUID,
password, congestion control, and TLS-insecure. SOCKS5 and MTProto
links gain a calls= query param so the receiver can preserve the
per-proxy flag.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase D — Default proxy bake

### Task 10: Surface `BuildConfig.DEFAULT_PROXY_LINK` from `local.properties`

**Files:**
- Modify: `TMessagesProj/build.gradle`

- [ ] **Step 1: Inspect existing buildConfigField usages in this file**

```bash
grep -n "buildConfigField" TMessagesProj/build.gradle | head -10
```

- [ ] **Step 2: Add the buildConfigField**

Inside the `android { defaultConfig { ... } }` block of `TMessagesProj/build.gradle`, near existing `buildConfigField` lines, add:

```gradle
def defaultProxyLink = project.findProperty('default.proxy.link') ?: ''
buildConfigField "String", "DEFAULT_PROXY_LINK", "\"" + defaultProxyLink + "\""
```

- [ ] **Step 3: Build with an empty value**

```bash
just apk
```

Expected: BUILD SUCCESSFUL. Verify `BuildConfig.DEFAULT_PROXY_LINK` exists by grepping the generated source:

```bash
grep -r 'DEFAULT_PROXY_LINK' TMessagesProj/build/generated/source/buildConfig/ 2>/dev/null | head -3
```

- [ ] **Step 4: Commit**

```bash
git add TMessagesProj/build.gradle
git commit -m "$(cat <<'EOF'
expose default.proxy.link from local.properties as BuildConfig field

Reads default.proxy.link via project.findProperty and surfaces it as
BuildConfig.DEFAULT_PROXY_LINK. Empty when unset; consumed in the
next commit to bootstrap a proxy on first launch.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 11: Add `DefaultProxyLinkParser` and first-run hook

**Files:**
- Create: `TMessagesProj/src/main/java/org/telegram/messenger/DefaultProxyLinkParser.java`
- Modify: `TMessagesProj/src/main/java/org/telegram/messenger/ApplicationLoader.java`

The Telegram-X parser at `app/src/main/java/org/thunderdog/challegram/unsorted/DefaultProxyLinkParser.java` parses `tg://proxy`, `tg://socks`, `tg://http`, `tg://tuic`. We port it without the HTTP branch.

- [ ] **Step 1: Read the Telegram-X parser as reference**

```bash
cat /home/nb/src/Telegram-X/app/src/main/java/org/thunderdog/challegram/unsorted/DefaultProxyLinkParser.java
```

- [ ] **Step 2: Port to TMessagesProj's package + ProxyInfo shape**

```java
package org.telegram.messenger;

import android.net.Uri;
import android.text.TextUtils;

public class DefaultProxyLinkParser {

    private DefaultProxyLinkParser() {}

    public static SharedConfig.ProxyInfo parse(String link) {
        if (TextUtils.isEmpty(link)) return null;
        Uri uri;
        try {
            uri = Uri.parse(link);
        } catch (Exception e) {
            FileLog.e("Invalid default proxy link: " + link);
            return null;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (!"tg".equals(scheme) || host == null) {
            FileLog.e("Default proxy link must use tg:// scheme: " + link);
            return null;
        }
        String server = uri.getQueryParameter("server");
        String portStr = uri.getQueryParameter("port");
        if (TextUtils.isEmpty(server) || TextUtils.isEmpty(portStr)) return null;
        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            return null;
        }
        switch (host) {
            case "tuic": {
                String uuid = uri.getQueryParameter("uuid");
                if (TextUtils.isEmpty(uuid)) return null;
                String password = nullToEmpty(uri.getQueryParameter("password"));
                String congestion = uri.getQueryParameter("congestion_control");
                if (TextUtils.isEmpty(congestion)) congestion = "bbr";
                String tlsInsecure = uri.getQueryParameter("tls_insecure");
                boolean insecure = tlsInsecure == null || !tlsInsecure.equals("0");
                String calls = uri.getQueryParameter("calls");
                boolean useForCalls = calls == null || !calls.equals("0");
                return SharedConfig.ProxyInfo.forTuic(
                        new TuicProxyConfig(server, port, uuid, password, congestion, insecure),
                        useForCalls);
            }
            case "socks": {
                SharedConfig.ProxyInfo info = new SharedConfig.ProxyInfo(server, port,
                        nullToEmpty(uri.getQueryParameter("user")),
                        nullToEmpty(uri.getQueryParameter("pass")),
                        "");
                info.useForCalls = "1".equals(uri.getQueryParameter("calls"));
                return info;
            }
            case "proxy": {
                String secret = nullToEmpty(uri.getQueryParameter("secret"));
                SharedConfig.ProxyInfo info = new SharedConfig.ProxyInfo(server, port,
                        nullToEmpty(uri.getQueryParameter("user")),
                        nullToEmpty(uri.getQueryParameter("pass")),
                        secret);
                info.useForCalls = "1".equals(uri.getQueryParameter("calls"));
                return info;
            }
            default:
                FileLog.e("Unsupported default proxy scheme tg://" + host);
                return null;
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
```

- [ ] **Step 3: Wire into `ApplicationLoader.applicationInited()`**

After the `SharedConfig.loadProxyList()` + `SingBoxController.syncWithProxyList()` calls added in Task 5, add:

```java
SharedPreferences mainPrefs = getApplicationContext()
        .getSharedPreferences("mainconfig", Context.MODE_PRIVATE);
if (!mainPrefs.getBoolean("default_proxy_imported", false)
        && SharedConfig.proxyList.isEmpty()
        && !TextUtils.isEmpty(BuildConfig.DEFAULT_PROXY_LINK)) {
    SharedConfig.ProxyInfo defaultInfo = DefaultProxyLinkParser.parse(BuildConfig.DEFAULT_PROXY_LINK);
    if (defaultInfo != null) {
        SharedConfig.ProxyInfo persisted = SharedConfig.addProxy(defaultInfo);
        SharedConfig.currentProxy = persisted;
        MessagesController.getGlobalMainSettings().edit().putBoolean("proxy_enabled", true).apply();
        mainPrefs.edit().putBoolean("default_proxy_imported", true).apply();
        // syncWithProxyList already called inside addProxy
    }
}
```

- [ ] **Step 4: Build with a TUIC default in `local.properties`**

Add a temporary test line to `local.properties` (do **not** commit this):

```properties
default.proxy.link=tg://tuic?server=example.com&port=443&uuid=00000000-0000-0000-0000-000000000000&password=test&congestion_control=bbr&tls_insecure=1&calls=1
```

Then:

```bash
just apk
```

Expected: BUILD SUCCESSFUL. `BuildConfig.DEFAULT_PROXY_LINK` contains the value.

- [ ] **Step 5: Smoke test in fresh redroid**

```bash
just adb shell pm clear org.telegram.messenger.beta
just install
just run
just adb logcat -d | grep -E "Default proxy|TUIC|SingBox"
```

Expected: the default TUIC entry appears in `Settings → Data and Storage → Proxy Settings` after first launch. Remove the test line from `local.properties` before committing.

- [ ] **Step 6: Commit**

```bash
git checkout local.properties  # ensure no test value committed
git add TMessagesProj/src/main/java/org/telegram/messenger/DefaultProxyLinkParser.java \
        TMessagesProj/src/main/java/org/telegram/messenger/ApplicationLoader.java
git commit -m "$(cat <<'EOF'
import BuildConfig.DEFAULT_PROXY_LINK on first launch

DefaultProxyLinkParser handles tg://proxy, tg://socks, and tg://tuic
links (HTTP intentionally out of scope). Importing is gated by a
one-time default_proxy_imported flag so manual deletion is sticky.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase E — VoIP wiring

### Task 12: JNI `allowTCP=true` fix

**Files:**
- Modify: `TMessagesProj/jni/voip/org_telegram_messenger_voip_Instance.cpp`

- [ ] **Step 1: Find the Descriptor::config initializer**

```bash
grep -n "enableP2P\|enableStunMarking\|enableP2p\b" TMessagesProj/jni/voip/org_telegram_messenger_voip_Instance.cpp | head -5
```

Expected line: around `780` in the existing file (`Descriptor descriptor = { .config = Config{ ... }, ... }`).

- [ ] **Step 2: Confirm `allowTCP` field exists**

```bash
grep -n "allowTCP" TMessagesProj/jni/voip/tgcalls/Instance.h
```

Expected: `bool allowTCP = false;` at `Instance.h:113` — so the patch is one-line.

- [ ] **Step 3: Add `.allowTCP = true` to the `Config{}` initializer**

In the `Descriptor::config = Config{ ... }` block (around line 780–796), add the field alongside the other booleans. Final initializer fragment:

```cpp
.enableP2P = configObject.getBooleanField("enableP2p") == JNI_TRUE,
.enableStunMarking = configObject.getBooleanField("enableSm") == JNI_TRUE,
.allowTCP = true,
.enableAEC = configObject.getBooleanField("enableAec") == JNI_TRUE,
```

- [ ] **Step 4: Build**

```bash
just apk
```

Expected: BUILD SUCCESSFUL. The C++ side compiles via the NDK toolchain during the Gradle assemble step.

- [ ] **Step 5: Commit**

This change is independent of the SOCKS5 socket port (Tasks 13–14): the field already exists on `Config`, and flipping it to `true` is a no-op until a proxy is configured. Commit standalone:

```bash
git add TMessagesProj/jni/voip/org_telegram_messenger_voip_Instance.cpp
git commit -m "$(cat <<'EOF'
JNI: set Descriptor::config.allowTCP=true unconditionally

forceTcp is the user's "I want TCP only" toggle; allowTCP is the
ICE-candidate-type permission. With allowTCP=false the port allocator
sets PORTALLOCATOR_DISABLE_TCP, which becomes catastrophic when a
proxy is added because tgcalls also disables UDP via a proxy. Set
allowTCP=true so TCP stays a permitted fallback regardless of the
user toggle.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 13: Copy `Socks5ProxySocket.{cpp,h}` into vendored tgcalls

**Files:**
- Create: `TMessagesProj/jni/voip/tgcalls/v2/Socks5ProxySocket.h`
- Create: `TMessagesProj/jni/voip/tgcalls/v2/Socks5ProxySocket.cpp`
- Create: `TMessagesProj/jni/voip/tgcalls/TgCallsCryptStringImpl.h` (extracted shared header — required by Socks5ProxySocket usage)

The two source files come verbatim from Telegram-X's tgcalls submodule commit `49651f2`. The crypt-string header comes from commit `df0f85d`. We port both tgcalls commits as a unit; the HTTP-related code paths they introduce become unused dead code in Telegram-Android, but that's the safest minimum-divergence approach (Telegram-X has end-to-end tested this exact code).

- [ ] **Step 1: Copy the three files**

```bash
cp /home/nb/src/Telegram-X/app/jni/tgvoip/third_party/tgcalls/tgcalls/v2/Socks5ProxySocket.h \
   /home/nb/src/Telegram/TMessagesProj/jni/voip/tgcalls/v2/Socks5ProxySocket.h
cp /home/nb/src/Telegram-X/app/jni/tgvoip/third_party/tgcalls/tgcalls/v2/Socks5ProxySocket.cpp \
   /home/nb/src/Telegram/TMessagesProj/jni/voip/tgcalls/v2/Socks5ProxySocket.cpp
cp /home/nb/src/Telegram-X/app/jni/tgvoip/third_party/tgcalls/tgcalls/TgCallsCryptStringImpl.h \
   /home/nb/src/Telegram/TMessagesProj/jni/voip/tgcalls/TgCallsCryptStringImpl.h
```

- [ ] **Step 2: Verify the files reference only existing tgcalls headers**

```bash
head -30 TMessagesProj/jni/voip/tgcalls/v2/Socks5ProxySocket.cpp
```

Expected `#include` lines should all be either:
- standard `<...>` headers
- `"v2/..."` or sibling tgcalls headers
- WebRTC headers (`"rtc_base/..."`, `"p2p/..."`)

If any include refers to a Telegram-X-only file not in this tree, abort and revisit.

- [ ] **Step 3: Verify `Instance.h` has the `ProxyType` enum and `Proxy.type` field**

```bash
grep -n "ProxyType\|enum class Proxy\|struct Proxy" TMessagesProj/jni/voip/tgcalls/Instance.h
```

If absent, proceed to Task 14 first (`Instance.h` is modified there). The build will fail on this task until both land.

- [ ] **Step 4: Don't build yet — depends on Task 14's CMakeLists update.** Mark complete and move on.

- [ ] **Step 5: Defer commit until Task 14 (combined commit)**

---

### Task 14: Apply the `ProxyType` enum + NativeNetworkingImpl + ReflectorPort + CMakeLists changes

**Files:**
- Modify: `TMessagesProj/jni/voip/tgcalls/Instance.h`
- Modify: `TMessagesProj/jni/voip/tgcalls/NetworkManager.cpp`
- Modify: `TMessagesProj/jni/voip/tgcalls/v2/InstanceV2Impl.cpp`
- Modify: `TMessagesProj/jni/voip/tgcalls/v2/InstanceV2ReferenceImpl.cpp`
- Modify: `TMessagesProj/jni/voip/tgcalls/v2/NativeNetworkingImpl.cpp`
- Modify: `TMessagesProj/jni/voip/tgcalls/v2/ReflectorPort.cpp`
- Modify: `TMessagesProj/jni/voip/CMakeLists.txt`

This task transplants two Telegram-X tgcalls submodule commits — `df0f85d` (ProxyType enum) and `49651f2` (SOCKS5 wiring in NativeNetworkingImpl + ReflectorPort) — onto the vendored tgcalls tree in TMessagesProj. The two commits' diffs are the spec for this task.

- [ ] **Step 1: View both diffs side-by-side**

```bash
cd /home/nb/src/Telegram-X/app/jni/tgvoip/third_party/tgcalls
git show df0f85d
git show 49651f2
cd /home/nb/src/Telegram
```

- [ ] **Step 2: Apply the `df0f85d` changes**

For each file in `df0f85d`:
- `tgcalls/Instance.h` — add the `ProxyType` enum and the `type` field on `Proxy`. The diff for this file is small (~6 lines added).
- `tgcalls/NetworkManager.cpp` — replace the hardcoded `PROXY_SOCKS5` assignment with a switch on `proxy.type`. ~40 lines refactored.
- `tgcalls/v2/InstanceV2Impl.cpp` — read `config.allowTCP` (or `enableTCP` — match the field name in `Instance.h` after the patch) instead of hardcoding `false`.
- `tgcalls/v2/InstanceV2ReferenceImpl.cpp` — uses ProxyType.
- `tgcalls/v2/NativeNetworkingImpl.cpp` — uses ProxyType (partially overlapping with `49651f2`).

Apply each by reading the Telegram-X diff and reproducing the change. For NativeNetworkingImpl.cpp, apply `df0f85d`'s portion first, then `49651f2`'s on top.

If the Telegram-Android vendored tgcalls diverges from Telegram-X's pre-patch state, you'll see merge-like conflicts. Resolve by hand: the goal is the post-patch behaviour, not a clean diff.

- [ ] **Step 3: Apply the `49651f2` changes to `NativeNetworkingImpl.cpp` and `ReflectorPort.cpp`**

The `Socks5ProxySocket.{cpp,h}` files were already copied in Task 13. The remaining changes wire them in. Refer directly to the `49651f2` diff:
- `NativeNetworkingImpl.cpp` — extend `WrappedBasicPacketSocketFactory` (constructor signature changes; `CreateUdpSocket` and `CreateClientTcpSocket` get SOCKS5 branches; port-allocator UDP disable removed for SOCKS5 case).
- `ReflectorPort.cpp` — wrap `CreateClientRawTcpSocket` with `Socks5TcpProxySocket` when proxy type is SOCKS5.

- [ ] **Step 4: Add `Socks5ProxySocket.cpp` to the CMake source list**

Inspect:

```bash
grep -n "v2/.*\.cpp\|NativeNetworkingImpl.cpp" TMessagesProj/jni/voip/CMakeLists.txt
```

Find the tgcalls source list. Add `tgcalls/v2/Socks5ProxySocket.cpp` next to `tgcalls/v2/NativeNetworkingImpl.cpp`.

- [ ] **Step 5: Build (this is the load-bearing build)**

```bash
just apk
```

Expected: BUILD SUCCESSFUL. The NDK build runs CMake, compiles the new file, and links. If there are missing symbols (`TgCallsCryptStringImpl`, `Socks5UdpProxySocket::Create`, etc.), re-check Task 13's file copies.

- [ ] **Step 6: Commit Tasks 13 + 14 together**

```bash
git add TMessagesProj/jni/voip/tgcalls/v2/Socks5ProxySocket.h \
        TMessagesProj/jni/voip/tgcalls/v2/Socks5ProxySocket.cpp \
        TMessagesProj/jni/voip/tgcalls/TgCallsCryptStringImpl.h \
        TMessagesProj/jni/voip/tgcalls/Instance.h \
        TMessagesProj/jni/voip/tgcalls/NetworkManager.cpp \
        TMessagesProj/jni/voip/tgcalls/v2/InstanceV2Impl.cpp \
        TMessagesProj/jni/voip/tgcalls/v2/InstanceV2ReferenceImpl.cpp \
        TMessagesProj/jni/voip/tgcalls/v2/NativeNetworkingImpl.cpp \
        TMessagesProj/jni/voip/tgcalls/v2/ReflectorPort.cpp \
        TMessagesProj/jni/voip/CMakeLists.txt
git commit -m "$(cat <<'EOF'
tgcalls: SOCKS5 UDP + TCP proxy sockets for VoIP ICE

Transplants Telegram-X tgcalls submodule commits df0f85d (ProxyType
enum) and 49651f2 (Socks5{Tcp,Udp}ProxySocket adapters) onto the
vendored tree. NativeNetworkingImpl wraps ICE host UDP and TCP
sockets with the new SOCKS5 adapters; ReflectorPort routes its raw
TCP fallback through the same. UDP is no longer force-disabled when
a SOCKS5 proxy is set. STUN candidates remain disabled for any proxy.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 15: Wire `SingBoxController.resolveForVoIP` into `VoIPService`

**Files:**
- Modify: `TMessagesProj/src/main/java/org/telegram/messenger/voip/VoIPService.java`

- [ ] **Step 1: Replace the proxy block at `VoIPService.java:3451`**

Original:

```java
// proxy
Instance.Proxy proxy = null;
if (preferences.getBoolean("proxy_enabled", false) && preferences.getBoolean("proxy_enabled_calls", false)) {
    final String server = preferences.getString("proxy_ip", null);
    final String secret = preferences.getString("proxy_secret", null);
    if (!TextUtils.isEmpty(server) && TextUtils.isEmpty(secret)) {
        proxy = new Instance.Proxy(server, preferences.getInt("proxy_port", 0), preferences.getString("proxy_user", null), preferences.getString("proxy_pass", null));
    }
}
```

Replace with:

```java
// proxy
Instance.Proxy proxy = null;
if (SharedConfig.isProxyEnabled()) {
    proxy = SingBoxController.resolveForVoIP(SharedConfig.currentProxy);
}
```

- [ ] **Step 2: Build**

```bash
just apk
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Smoke test — voice call through a TUIC proxy**

Prerequisite: a real TUIC server reachable from your redroid container, accessible by a known config. Reuse the one Telegram-X's `a7ed710b` smoke test used if you still have it. Otherwise stand up sing-box locally with a TUIC server config to a public endpoint.

1. In the app, add the TUIC proxy via `tg://tuic?...` or the settings UI (Task 17 lands the UI; until then, paste a URL into a chat and tap it).
2. Place a 1-on-1 voice call to a second test account.
3. Watch logcat:

```bash
just adb logcat -d | grep -E "tgcalls|Socks5|ICE|reflector" | tail -50
```

Expected: ICE picks a UDP relay candidate pair to a Telegram reflector, audio becomes writable within ~500 ms after ICE start (matching the Telegram-X benchmark).

If the call fails:
- Check `Instance.Proxy` resolution: add a `FileLog.d("VoIP proxy: " + proxy)` line in `VoIPService.java` and re-build.
- Check sing-box state: `adb shell logcat | grep SingBoxManager`.
- Check that the inbound port matches the resolved Instance.Proxy port.

- [ ] **Step 4: Place a 1-on-1 video call through the proxy**

Repeat with camera enabled. Expected: video frames flow both directions (1-on-1 video shares the same tgcalls Instance as voice — no extra changes needed).

- [ ] **Step 5: Commit**

```bash
git add TMessagesProj/src/main/java/org/telegram/messenger/voip/VoIPService.java
git commit -m "$(cat <<'EOF'
route VoIP through SharedConfig.currentProxy.useForCalls

Replaces the global proxy_enabled_calls gate with the per-proxy
useForCalls field. resolveForVoIP returns the loopback SOCKS5 endpoint
for TUIC entries and the original endpoint for plain SOCKS5.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase F — Settings UI

### Task 16: `ProxyListActivity` — per-row `useForCalls` checkbox

**Files:**
- Modify: `TMessagesProj/src/main/java/org/telegram/ui/ProxyListActivity.java`

- [ ] **Step 1: Read the existing per-row layout**

```bash
grep -n "useForCalls\|proxy_enabled_calls\|useProxyForCalls" TMessagesProj/src/main/java/org/telegram/ui/ProxyListActivity.java | head -10
```

Notable existing references at lines 346, 460, 478 read/write the **global** `proxy_enabled_calls`. The new per-row checkbox is bound to `ProxyInfo.useForCalls`.

- [ ] **Step 2: Remove the global "Use proxy for calls" row from the list adapter**

Find the row cell in `onCreateView`/`bindViewHolder` that toggles `useProxyForCalls`. Replace it with a per-row checkbox in the per-proxy cells. Lift the existing global pref toggle's UI placement and use it as a template; each `ProxyInfoCell` (or whatever the row class is named — read the file to identify) now exposes a `useForCalls` toggle.

For each `ProxyInfo` row:
- Display a small checkbox or chip labelled "Use for calls" right below the proxy address line.
- On toggle: `info.useForCalls = isChecked; SharedConfig.saveProxyList(); SingBoxController.syncWithProxyList();` (the last call is a no-op for non-TUIC but cheap).

The exact view code depends on Telegram's existing cell classes; consult the patterns in `Cells/` and `Components/Premium/`. If a clean cell extension is impractical without major refactor, add a tap-to-toggle behaviour in the existing row's overflow menu (long-press → "Use for calls").

- [ ] **Step 3: Remove the global `useProxyForCalls` state**

Delete the field, the load on line 346, and the writes on lines 460 and 478. Keep a one-time migration in `ApplicationLoader.applicationInited` (already covered by the V2→V3 migration in Task 3).

- [ ] **Step 4: Build**

```bash
just apk
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Smoke test**

Install, open `Settings → Proxy`, add a plain SOCKS5 proxy and a TUIC proxy. Toggle "Use for calls" on/off per row. Restart the app and verify the toggle persists.

- [ ] **Step 6: Commit**

```bash
git add TMessagesProj/src/main/java/org/telegram/ui/ProxyListActivity.java
git commit -m "$(cat <<'EOF'
replace global proxy_enabled_calls with per-row useForCalls

Each proxy entry in ProxyListActivity gains a 'Use for calls' control
bound to ProxyInfo.useForCalls. The global toggle is removed; existing
installs carry their value forward via the V2->V3 migration in
SharedConfig.loadProxyList.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 17: `ProxySettingsActivity` — TUIC editor mode

**Files:**
- Modify: `TMessagesProj/src/main/java/org/telegram/ui/ProxySettingsActivity.java`

- [ ] **Step 1: Read the existing mode-switching code**

```bash
grep -n "currentType\|TYPE_\|proxy_type" TMessagesProj/src/main/java/org/telegram/ui/ProxySettingsActivity.java | head -20
```

The activity has a mode selector (SOCKS5 vs MTProto). Add a TUIC mode.

- [ ] **Step 2: Add TUIC fields and mode**

Add a `TYPE_TUIC` constant (or wire to `SharedConfig.PROXY_TYPE_TUIC`). When TUIC mode is selected, swap the inputs to: server, port, uuid, password, congestion control (dropdown: bbr, cubic, new-reno), TLS-insecure (checkbox), useForCalls (checkbox). Reuse the same EditText classes the existing form uses.

On save, build a `TuicProxyConfig` + `SharedConfig.ProxyInfo.forTuic(cfg, useForCalls)` and either insert via `SharedConfig.addProxy` (new) or replace fields on the existing `currentProxy` (edit).

Telegram-X's `EditProxyController.java` shows the exact UI layout for TUIC inputs — model on it:

```bash
grep -n "MODE_TUIC\|tuicUuid\|tuicPassword" /home/nb/src/Telegram-X/app/src/main/java/org/thunderdog/challegram/ui/EditProxyController.java | head -20
```

- [ ] **Step 3: Build**

```bash
just apk
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Smoke test**

Install, open `Settings → Proxy → Add proxy`, switch mode to TUIC, fill in fields, save. Verify the entry appears in `ProxyListActivity` and a `tg://tuic?...` share link is generated by tap-to-share.

- [ ] **Step 5: Commit**

```bash
git add TMessagesProj/src/main/java/org/telegram/ui/ProxySettingsActivity.java
git commit -m "$(cat <<'EOF'
add TUIC editor mode to ProxySettingsActivity

New TYPE_TUIC mode swaps the form to server/port/uuid/password/
congestion_control/tls_insecure inputs plus the per-proxy use-for-
calls checkbox. Save builds a TuicProxyConfig and calls
SharedConfig.addProxy, which kicks SingBoxController to rebind.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## End-of-port verification

After all tasks, run a final smoke checklist:

- [ ] Fresh redroid install with `default.proxy.link` set in `local.properties` → proxy appears on first launch, app connects via TUIC.
- [ ] Add a second TUIC proxy via `tg://tuic` paste-and-tap → sing-box restarts with both inbounds.
- [ ] 1-on-1 voice call through active TUIC proxy → ICE picks UDP relay, audio writable in < 1 s.
- [ ] 1-on-1 video call through active TUIC proxy → frames in both directions.
- [ ] Toggle `useForCalls = false` on the current proxy → call traffic egresses directly (verify via logcat: no `Socks5*ProxySocket` mentions).
- [ ] Plain SOCKS5 proxy still works for both data and calls (regression check).
- [ ] MTProto-secret proxy works for data, calls bypass it (regression: `resolveForVoIP` returns null for MTProto entries).
- [ ] Share-link from a TUIC entry round-trips: copy → paste into another account → tap → import.

Once all green, the port is complete.

---

## Open follow-ups (out of scope for this plan)

These are noted in the spec under "Out of scope" and "Risks". List here as a parking lot — do **not** start them as part of this work.

- Group voice chats / video chats / livestreams (`makeGroupInstance` does not get a proxy).
- HTTP CONNECT proxy support (the Java layer; the C++ ProxyType enum already has it as dead code from Task 14).
- Go toolchain dep in Dockerfile (the Dockerfile is already known-stale; addressing it is project_dockerfile_drift territory).
- Bumping sing-box / sagernet version pin.
- Bringing the user's existing `3b1341c4f` "personal api_id" patch into the same proxy onboarding flow if desired.
