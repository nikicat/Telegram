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
singbox:
    env GO111MODULE=on PATH=$(go env GOPATH)/bin:$PATH \
        bash singbox/go/build.sh

# build singbox AAR only if it doesn't exist yet (idempotent guard for apk)
singbox-if-missing:
    test -f singbox/libs/singboxbridge.aar || just singbox

# build APK (honors the abi/build vars; override with e.g. `just abi=Arm64 apk`)
apk: singbox-if-missing
    env JAVA_HOME={{java_home}} \
        ANDROID_HOME={{android_home}} \
        ANDROID_SDK_ROOT={{android_home}} \
        PATH={{java_home}}/bin:$PATH \
        ./gradlew :TMessagesProj_App:assembleAfat{{abi}}{{build}}

# clean build outputs (slow next build)
clean-build:
    env JAVA_HOME={{java_home}} \
        ANDROID_HOME={{android_home}} \
        ANDROID_SDK_ROOT={{android_home}} \
        PATH={{java_home}}/bin:$PATH \
        ./gradlew :TMessagesProj_App:clean

# print absolute path to the built APK (does not build). Honors the abi/build vars.
apk-path:
    @realpath TMessagesProj_App/build/outputs/apk/afat{{abi}}/$(echo {{build}} | tr A-Z a-z)/app.apk

# ----- redroid lifecycle -----

# start the redroid container
up:
    sudo podman compose up -d

# stop and remove the container
down:
    sudo podman compose down

# down + up
restart: down up

# restrict container traffic to the TUIC proxy (host/port from default.proxy.link in local.properties; port -> 443 if absent)
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
unfirewall:
    -sudo nft delete table inet redroid_fw

# wait until Android finishes booting on the container
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
start: up firewall boot

# unfirewall + down
stop: unfirewall down

# show container status and Android boot state
status:
    @sudo podman ps -a --filter name={{container}}
    @adb connect {{redroid}} >/dev/null 2>&1 && \
        echo "boot_completed=$({{_adb_redroid}} shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" || \
        echo "ADB not reachable"

# tail container logs
logs:
    sudo podman logs -f {{container}}

# stop container and wipe ./redroid-data (factory reset)
nuke: stop
    sudo rm -rf ./redroid-data

# ----- device interaction -----

# connect adb to DEVICE (no-op for USB serials, only for host:port)
adb device=redroid:
    @if [[ "{{device}}" == *:* ]]; then adb connect {{device}}; fi

# open an Android shell on DEVICE
shell device=redroid: (adb device)
    adb -s {{device}} shell

# mirror screen of DEVICE with scrcpy
scrcpy device=redroid: (adb device)
    scrcpy -s {{device}}

# ----- app lifecycle on DEVICE -----

# build + install the APK on DEVICE. Honors the abi/build vars (e.g. `just abi=Arm64 install`).
install device=redroid: apk (adb device)
    adb -s {{device}} install -r TMessagesProj_App/build/outputs/apk/afat{{abi}}/$(echo {{build}} | tr A-Z a-z)/app.apk

# install without rebuilding (uses whatever APK is on disk). Honors the abi/build vars.
install-only device=redroid: (adb device)
    adb -s {{device}} install -r TMessagesProj_App/build/outputs/apk/afat{{abi}}/$(echo {{build}} | tr A-Z a-z)/app.apk

# launch the app on DEVICE (does not build/install)
launch device=redroid: (adb device)
    adb -s {{device}} shell am start -n {{activity}}

# stop the running app on DEVICE
kill device=redroid: (adb device)
    adb -s {{device}} shell am force-stop {{pkg}}

# clear app data and cache on DEVICE (keeps install)
wipe device=redroid: (adb device)
    adb -s {{device}} shell pm clear {{pkg}}

# uninstall the app from DEVICE
uninstall device=redroid: (adb device)
    -adb -s {{device}} uninstall {{pkg}}

# full inner loop: build, install, kill old, launch (honors abi/build vars; defaults X64 Debug)
run device=redroid: (install device) (kill device) (launch device)

# follow Telegram-relevant logcat tags on DEVICE
logcat device=redroid: (adb device)
    adb -s {{device}} logcat -v threadtime tgnet:V tmessages.49:V TMessagesProj:V org.telegram.messenger:V AndroidRuntime:E DEBUG:E *:S
