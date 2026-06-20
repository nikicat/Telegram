import 'common.just'

# list this module's recipes
default:
    @just --list emulator

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
