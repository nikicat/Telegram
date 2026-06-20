set shell := ["bash", "-cu"]

# Adapted from ../Telegram-X/justfile. Adjusted for:
#  - this project's module/flavor layout (afatX64 debug, see TMessagesProj_App)
#  - JDK 17 pin (system default on this host is JDK 21)
#  - debug package suffix `.beta` and launcher activity `DefaultIcon`
#  - host ADB port 5556 so it coexists with Telegram-X's redroid on 5555

# Redroid container settings
adb_port  := "5556"
network   := "telegram_redroid"
container := "telegram_redroid_1"
redroid   := "localhost:" + adb_port

# Toolchain pin
java_home    := "/usr/lib/jvm/java-17-openjdk"
android_home := env_var_or_default("ANDROID_HOME", "/home/nb/Android/Sdk")

# gradlew wrapped with the pinned JDK 17 + Android SDK env (shared by all gradle recipes)
_gradle := "env JAVA_HOME=" + java_home + " ANDROID_HOME=" + android_home + " ANDROID_SDK_ROOT=" + android_home + " PATH=" + java_home + "/bin:$PATH ./gradlew"

# Telegram app metadata (debug variant — kill/launch/wipe/uninstall use this pkg).
# Derived from app.id in local.properties (+ debug suffix); falls back to the upstream package.
pkg      := `id="$(grep -E '^app\.id=' local.properties 2>/dev/null | cut -d= -f2-)"; echo "${id:-org.telegram.messenger}.debug"`
activity := pkg + "/org.telegram.messenger.DefaultIcon"

_adb_redroid := "adb -s " + redroid

# Build matrix. Override BEFORE the recipe name, e.g. `just abi=Arm64 apk`, `just build=Release run`.
#   abi: X64|Arm64   build: Debug|Release|Standalone   (defaults match the redroid inner loop)
abi   := "X64"
build := "Debug"

# Default recipe: list available recipes.
default:
    @just --list

# ----- build -----

# build the singbox gomobile AAR (~75 MB, gitignored; required by :apk)
[group('build')]
singbox:
    env GO111MODULE=on PATH=$(go env GOPATH)/bin:$PATH \
        bash singbox/go/build.sh

# build singbox AAR only if it doesn't exist yet (idempotent guard for apk)
[private]
singbox-if-missing:
    test -f singbox/libs/singboxbridge.aar || just singbox

# build APK (honors the abi/build vars; override with e.g. `just abi=Arm64 apk`)
[group('build')]
apk: singbox-if-missing
    {{_gradle}} :TMessagesProj_App:assembleAfat{{abi}}{{build}}

# print absolute path to the built APK (does not build). Honors the abi/build vars.
[group('build')]
apk-path:
    @realpath TMessagesProj_App/build/outputs/apk/afat{{abi}}/$(echo {{build}} | tr A-Z a-z)/*.apk

# clean build outputs (slow next build)
[group('build')]
clean-build:
    {{_gradle}} :TMessagesProj_App:clean

# ----- release (Google Play) -----

# build a release AAB for Google Play (flavor bundleAfat: minSdk 21, all 4 ABIs; bump version first)
[group('release')]
aab: singbox-if-missing
    {{_gradle}} :TMessagesProj_App:bundleBundleAfatRelease

# print absolute path to the built AAB (does not build)
[group('release')]
aab-path:
    @realpath TMessagesProj_App/build/outputs/bundle/bundleAfatRelease/*.aab

# build an arm64-only release AAB (~1/4 the native size of `aab`; drops v7a/x86/x86_64)
[group('release')]
aab-arm64: singbox-if-missing
    {{_gradle}} :TMessagesProj_App:bundleBundleAfatArm64Release

# print absolute path to the built arm64-only AAB (does not build)
[group('release')]
aab-arm64-path:
    @realpath TMessagesProj_App/build/outputs/bundle/bundleAfatArm64Release/*.aab

# bump APP_VERSION_CODE +1 (Play needs an increasing code); optionally set name: `just bump-version 12.7.4`
[group('release')]
bump-version name="":
    @old=$(grep -E '^APP_VERSION_CODE=' gradle.properties | cut -d= -f2); \
        new=$((old + 1)); \
        sed -i "s/^APP_VERSION_CODE=.*/APP_VERSION_CODE=$new/" gradle.properties; \
        echo "APP_VERSION_CODE: $old -> $new (Play versionCode -> $((new * 10 + 1)))"; \
        if [ -n "{{name}}" ]; then \
            oldname=$(grep -E '^APP_VERSION_NAME=' gradle.properties | cut -d= -f2); \
            sed -i "s/^APP_VERSION_NAME=.*/APP_VERSION_NAME={{name}}/" gradle.properties; \
            echo "APP_VERSION_NAME: $oldname -> {{name}}"; \
        fi

# The publish-* recipes require play.service-account.file in local.properties (enables the GPP plugin);
# they upload to the play.track track (default "internal"). Bump the version first (`just bump-version`).

# upload the full AAB (all ABIs) + store listing to Google Play
[group('release')]
publish: singbox-if-missing
    {{_gradle}} :TMessagesProj_App:publishBundleAfatReleaseApps

# upload the arm64-only AAB + store listing to Google Play
[group('release')]
publish-arm64: singbox-if-missing
    {{_gradle}} :TMessagesProj_App:publishBundleAfatArm64ReleaseApps

# upload only the store listing (title/descriptions/graphics from src/main/play); no AAB build/upload
[group('release')]
publish-listing:
    {{_gradle}} :TMessagesProj_App:publishBundleAfatReleaseListing

# ----- firebase / google-services -----

# Registers app.id + .debug + .web (creating the Android apps if missing) and writes their merged
# config to the per-app-id path the build resolves. Requires `firebase login` (uses an installed
# `firebase`, else `npx firebase-tools`). project= defaults to the project id in any existing
# keystore/google-services.*.json. Example: `just google-services` or `just google-services nbrs-243915`.
# Fetch Firebase config for the current app.id -> keystore/google-services.<app.id>.json.
[group('firebase')]
google-services project="":
    #!/usr/bin/env bash
    set -euo pipefail
    app_id=$(grep -E '^app\.id=' local.properties 2>/dev/null | cut -d= -f2-)
    [ -n "$app_id" ] || { echo "set app.id in local.properties first" >&2; exit 1; }
    project="{{project}}"
    if [ -z "$project" ]; then
        project=$(python3 -c "import json,glob,sys; f=sorted(glob.glob('keystore/google-services.*.json')); sys.exit('no keystore/google-services.*.json to infer project; pass project=<id>') if not f else print(json.load(open(f[0]))['project_info']['project_id'])")
    fi
    out="keystore/google-services.${app_id}.json"
    fb=$(command -v firebase || true); [ -n "$fb" ] || fb="npx -y firebase-tools"
    echo "project: $project   app.id: $app_id   ->  $out   (using: $fb)"
    tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
    apps=$($fb apps:list ANDROID --project "$project" --json)
    files=()
    for suffix in "" .debug .web; do
        pkg="${app_id}${suffix}"
        aid=$(printf '%s' "$apps" | python3 -c "import json,sys; d=json.load(sys.stdin).get('result',[]); print(next((a['appId'] for a in d if (a.get('packageName') or a.get('namespace'))=='$pkg'),''))")
        if [ -z "$aid" ]; then
            echo "creating Android app $pkg ..."
            aid=$($fb apps:create ANDROID "$pkg" --package-name "$pkg" --project "$project" --json | python3 -c "import json,sys; print(json.load(sys.stdin)['result']['appId'])")
        else
            echo "found    $pkg  ($aid)"
        fi
        $fb apps:sdkconfig ANDROID "$aid" --project "$project" > "$tmp/$pkg.json"
        files+=("$tmp/$pkg.json")
    done
    python3 - "$out" "${files[@]}" <<'PY'
    import json, sys
    out, files = sys.argv[1], sys.argv[2:]
    def load(f):
        raw = open(f).read()
        return json.loads(raw[raw.index("{"):raw.rindex("}") + 1])
    base = load(files[0])
    clients = {}
    for f in files:
        for c in load(f).get("client", []):
            clients[c["client_info"]["android_client_info"]["package_name"]] = c
    base["client"] = list(clients.values())
    json.dump(base, open(out, "w"), indent=2)
    print("wrote %s with %d client(s): %s" % (out, len(clients), ", ".join(sorted(clients))))
    PY
    echo "done — run: just aab-arm64"

# ----- emulator (redroid) -----

# start the redroid container
[group('emulator')]
up:
    sudo podman compose up -d

# stop and remove the container
[group('emulator')]
down:
    sudo podman compose down

# down + up
[group('emulator')]
restart: down up

# restrict container traffic to the TUIC proxy (host/port from default.proxy.link in local.properties; port -> 443 if absent)
[group('emulator')]
firewall:
    @LINK=$(grep -E '^default\.proxy\.link=' local.properties | cut -d= -f2-) && \
        HOST=$(echo "$LINK" | sed -n 's/.*[?&]server=\([^&]*\).*/\1/p') && \
        PORT=$(echo "$LINK" | sed -n 's/.*[?&]port=\([^&]*\).*/\1/p') && \
        PORT=${PORT:-443} && \
        if [ -z "$HOST" ]; then echo "firewall: could not parse server= from default.proxy.link in local.properties" >&2; exit 1; fi && \
        BRIDGE=$(sudo podman network inspect {{network}} | python3 -c "import json,sys;print(json.load(sys.stdin)[0]['network_interface'])") && \
        echo "Applying firewall to bridge $BRIDGE for $HOST:$PORT" && \
        sudo nft delete table inet redroid_fw 2>/dev/null; \
        sudo nft -f nftables-redroid.conf -D REDROID_BRIDGE=$BRIDGE -D TUIC_HOST=$HOST -D TUIC_PORT=$PORT

# remove firewall rules
[group('emulator')]
unfirewall:
    -sudo nft delete table inet redroid_fw

# wait until Android finishes booting on the container
[group('emulator')]
boot:
    @echo "Waiting for Android boot on {{redroid}}..."
    @adb disconnect {{redroid}} >/dev/null 2>&1; \
    for i in $(seq 1 60); do \
        adb connect {{redroid}} >/dev/null 2>&1; \
        if [ "$({{_adb_redroid}} shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then \
            echo "Booted."; exit 0; \
        fi; \
        sleep 2; \
    done; \
    echo "Timeout waiting for boot"; exit 1

# up + firewall + wait for boot
[group('emulator')]
start: up firewall boot

# unfirewall + down
[group('emulator')]
stop: unfirewall down

# show container status and Android boot state
[group('emulator')]
status:
    @sudo podman ps -a --filter name={{container}}
    @adb connect {{redroid}} >/dev/null 2>&1 && \
        echo "boot_completed=$({{_adb_redroid}} shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" || \
        echo "ADB not reachable"

# tail container logs
[group('emulator')]
logs:
    sudo podman logs -f {{container}}

# stop container and wipe ./redroid-data (factory reset)
[group('emulator')]
nuke: stop
    sudo rm -rf ./redroid-data

# ----- device / app -----

# connect adb to DEVICE (no-op for USB serials, only for host:port)
[group('device')]
adb device=redroid:
    @if [[ "{{device}}" == *:* ]]; then adb connect {{device}}; fi

# open an Android shell on DEVICE
[group('device')]
shell device=redroid: (adb device)
    adb -s {{device}} shell

# mirror screen of DEVICE with scrcpy
[group('device')]
scrcpy device=redroid: (adb device)
    scrcpy -s {{device}}

# build + install the APK on DEVICE. Honors the abi/build vars (e.g. `just abi=Arm64 install`).
[group('device')]
install device=redroid: apk (adb device)
    adb -s {{device}} install -r TMessagesProj_App/build/outputs/apk/afat{{abi}}/$(echo {{build}} | tr A-Z a-z)/*.apk

# install without rebuilding (uses whatever APK is on disk). Honors the abi/build vars.
[group('device')]
install-only device=redroid: (adb device)
    adb -s {{device}} install -r TMessagesProj_App/build/outputs/apk/afat{{abi}}/$(echo {{build}} | tr A-Z a-z)/*.apk

# launch the app on DEVICE (does not build/install)
[group('device')]
launch device=redroid: (adb device)
    adb -s {{device}} shell am start -n {{activity}}

# stop the running app on DEVICE
[group('device')]
kill device=redroid: (adb device)
    adb -s {{device}} shell am force-stop {{pkg}}

# clear app data and cache on DEVICE (keeps install)
[group('device')]
wipe device=redroid: (adb device)
    adb -s {{device}} shell pm clear {{pkg}}

# uninstall the app from DEVICE
[group('device')]
uninstall device=redroid: (adb device)
    -adb -s {{device}} uninstall {{pkg}}

# full inner loop: build, install, kill old, launch (honors abi/build vars; defaults X64 Debug)
[group('device')]
run device=redroid: (install device) (kill device) (launch device)

# follow Telegram-relevant logcat tags on DEVICE
[group('device')]
logcat device=redroid: (adb device)
    adb -s {{device}} logcat -v threadtime tgnet:V tmessages.49:V TMessagesProj:V org.telegram.messenger:V AndroidRuntime:E DEBUG:E *:S
