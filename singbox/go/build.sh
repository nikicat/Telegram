#!/bin/bash
# Build singboxbridge.aar from Go source using sagernet's gomobile fork.
#
# Prerequisites:
#   go install github.com/sagernet/gomobile/cmd/gomobile@latest
#   go install github.com/sagernet/gomobile/cmd/gobind@latest
#
# ANDROID_NDK_HOME must point to an NDK whose lld supports 16 KB ELF alignment
# (r27+); older NDKs will produce libgojni.so with 4 KB LOAD alignment.

set -euo pipefail
cd "$(dirname "$0")"

ndk_primary=$(awk -F= '/^version\.ndk_primary=/ {print $2}' ../../version.properties)
: "${ANDROID_NDK_HOME:=$ANDROID_HOME/ndk/${ndk_primary}}"
export ANDROID_NDK_HOME

gomobile bind -v \
  -target android/arm64,android/arm,android/amd64,android/386 \
  -androidapi 21 \
  -tags with_quic \
  -ldflags="-extldflags=-Wl,-z,max-page-size=16384" \
  -o ../libs/singboxbridge.aar \
  .

ls -lh ../libs/singboxbridge.aar
