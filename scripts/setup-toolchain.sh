#!/usr/bin/env bash
# Bootstrap a build host for purplegram (Arch Linux; aarch64 or x86_64).
#
# Adapted from ../purpletube/scripts/setup-toolchain.sh. Same idea, different
# build: purplegram compiles Telegram from source (Gradle + a large CMake/NDK
# native tree) instead of patching a prebuilt APK, so this script additionally
# provides a CMake the NDK is happy with, and skips the morphe/APKEditor tools.
#
# On an aarch64 host it installs the shims that let the Android toolchain build
# natively, with NO qemu emulation anywhere — Google ships aapt2, the SDK CMake
# and the NDK for linux-x86_64 only. See docs/arm64-host.md for the why.
#
# Idempotent: re-running only fills in what's missing. Needs sudo for pacman.
set -euo pipefail
cd "$(dirname "$0")/.."

SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
# Versions the build actually asks for — read them from the build files so this
# script cannot drift from a gradle/NDK bump.
NDK_VER=$(awk -F= '/^version\.ndk_primary=/ {print $2}' version.properties)
COMPILE_SDK=$(grep -m1 -oE 'compileSdkVersion +[0-9]+' TMessagesProj/build.gradle | grep -oE '[0-9]+')
BUILD_TOOLS=$(grep -m1 -oE "buildToolsVersion +'[0-9.]+'" TMessagesProj/build.gradle | grep -oE '[0-9.]+')
NDK_TC=$SDK/ndk/$NDK_VER/toolchains/llvm/prebuilt/linux-x86_64
AAPT2_DIR=$HOME/Android/aapt2-aarch64
AAPT2_RELEASE=35.0.2                   # github.com/lzhiyong/android-sdk-tools
CMAKE_VER=3.22.1                       # AGP's default; SDK cmake is x86_64-only
CMAKE_DIR=$HOME/Android/cmake-$CMAKE_VER-aarch64
GOMOBILE_VER=v0.1.12                   # must match singbox/go/build.sh's fork
GOMOBILE_SRC=$HOME/src/gomobile-sagernet-$GOMOBILE_VER
ARCH=$(uname -m)

log() { printf '\n== %s\n' "$*"; }

log "host packages"
PACKAGES=(
    jdk17-openjdk jdk-openjdk   # gradle/AGP need 17; keep a modern default JDK
    android-tools scrcpy        # native adb + screen mirroring
    imagemagick librsvg         # branding assets (launcher icon, splash)
    podman podman-compose       # redroid
    go git jq unzip zip python
    ninja                       # AGP runs it out of the pinned CMake's bin/
)   # aarch64 additionally needs clang/llvm/lld matching the NDK — installed
    # further down, once the NDK says which major version that is.
missing=()
for p in "${PACKAGES[@]}"; do pacman -Qq "$p" >/dev/null 2>&1 || missing+=("$p"); done
if [ ${#missing[@]} -gt 0 ]; then
    # -Syu, not -Sy: Arch does not support partial upgrades. Skipped entirely
    # when nothing is missing, so re-running this script never upgrades the host.
    echo "installing: ${missing[*]}"
    sudo pacman -Syu --needed --noconfirm "${missing[@]}"
fi

log "android sdk ($SDK)"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
    rm -rf /tmp/purplegram-cmdline
    mkdir -p "$SDK/cmdline-tools" /tmp/purplegram-cmdline
    curl -fsSL -o /tmp/purplegram-cmdline/cmdline.zip \
        https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip
    unzip -q /tmp/purplegram-cmdline/cmdline.zip -d /tmp/purplegram-cmdline
    mv /tmp/purplegram-cmdline/cmdline-tools "$SDK/cmdline-tools/latest"
fi
export ANDROID_HOME=$SDK
yes 2>/dev/null | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null || true
"$SDK/cmdline-tools/latest/bin/sdkmanager" --install \
    "platforms;android-$COMPILE_SDK" "build-tools;$BUILD_TOOLS" "platform-tools" "ndk;$NDK_VER"

# local.properties is gitignored and per-machine; seed it from the example so a
# fresh clone builds. Existing files are only appended to, never rewritten.
if [ ! -f local.properties ]; then
    log "local.properties (from local.properties.example)"
    cp local.properties.example local.properties
fi
setprop() {  # setprop <key> <value> — add only if the key isn't set at all
    grep -qE "^$1=" local.properties || printf '%s=%s\n' "$1" "$2" >> local.properties
}
setprop sdk.dir "$SDK"

if [ "$ARCH" != "aarch64" ]; then
    log "x86_64 host — no shims needed; done"
    exit 0
fi

log "native aapt2 (no linux-arm64 build exists on maven.google.com)"
if [ ! -x "$AAPT2_DIR/aapt2" ]; then
    mkdir -p "$AAPT2_DIR" /tmp/purplegram-aapt2
    curl -fsSL -o /tmp/purplegram-aapt2/t.zip \
        "https://github.com/lzhiyong/android-sdk-tools/releases/download/$AAPT2_RELEASE/android-sdk-tools-static-aarch64.zip"
    unzip -qo /tmp/purplegram-aapt2/t.zip -d /tmp/purplegram-aapt2
    install -m755 /tmp/purplegram-aapt2/build-tools/aapt2 "$AAPT2_DIR/aapt2"
fi
"$AAPT2_DIR/aapt2" version
# AGP resolves aapt2 from maven (x86_64 jar); point it at the native binary.
# User-global on purpose: the path is host-specific, so it has no business in
# the repo's gradle.properties.
GRADLE_PROPS=$HOME/.gradle/gradle.properties
mkdir -p "$HOME/.gradle"
if ! grep -q '^android.aapt2FromMavenOverride=' "$GRADLE_PROPS" 2>/dev/null; then
    cat >> "$GRADLE_PROPS" <<EOF
# aarch64 host: Google publishes aapt2 for linux-x86_64 only, so point AGP at a
# native aarch64 build (see docs/arm64-host.md).
android.aapt2FromMavenOverride=$AAPT2_DIR/aapt2
EOF
fi

log "CMake $CMAKE_VER for aarch64 ($CMAKE_DIR)"
# The SDK's cmake;3.22.1 package is linux-x86_64 only, and Arch's cmake is far
# newer than anything NDK r27 was tested with. Kitware publishes an official
# linux-aarch64 tarball of exactly the version AGP defaults to — use that.
if [ ! -x "$CMAKE_DIR/bin/cmake" ]; then
    rm -rf /tmp/purplegram-cmake && mkdir -p /tmp/purplegram-cmake "$(dirname "$CMAKE_DIR")"
    curl -fsSL -o /tmp/purplegram-cmake/cmake.tgz \
        "https://github.com/Kitware/CMake/releases/download/v$CMAKE_VER/cmake-$CMAKE_VER-linux-aarch64.tar.gz"
    tar -xzf /tmp/purplegram-cmake/cmake.tgz -C /tmp/purplegram-cmake
    rm -rf "$CMAKE_DIR"
    mv "/tmp/purplegram-cmake/cmake-$CMAKE_VER-linux-aarch64" "$CMAKE_DIR"
fi
# AGP requires ninja next to cmake in the same bin/ (it never looks at PATH).
ln -sf /usr/bin/ninja "$CMAKE_DIR/bin/ninja"
"$CMAKE_DIR/bin/cmake" --version | head -1
# The SDK's cmake is a Google fork carrying Android-only modules that upstream
# Kitware CMake does not ship — TMessagesProj/jni/CMakeLists.txt include()s
# AndroidNdkModules for android_ndk_import_module_cpufeatures(). The modules are
# plain CMake (arch-independent), so lift them out of the x86_64 package; none of
# that package's binaries is ever executed.
"$SDK/cmdline-tools/latest/bin/sdkmanager" --install "cmake;$CMAKE_VER" >/dev/null
cp -f "$SDK/cmake/$CMAKE_VER/share/cmake-${CMAKE_VER%.*}/Modules/AndroidNdk"*.cmake \
      "$CMAKE_DIR/share/cmake-${CMAKE_VER%.*}/Modules/"
setprop cmake.dir "$CMAKE_DIR"
setprop cmake.version "$CMAKE_VER"

log "NDK native-clang shim ($NDK_TC)"
# Drive the NDK with a host LLVM of the SAME major version it ships. Arch's
# default clang (22) chokes on the NDK's own clang-18 builtin headers — every
# arm_neon.h intrinsic fails with "incompatible constant for this __builtin_neon
# function" — so pin llvm/clang/lld <major> instead of using -nobuiltininc games.
NDK_CLANG_VER=$(basename "$(ls -d "$NDK_TC"/lib/clang/* | sort -V | tail -1)")
LLVM=/usr/lib/llvm$NDK_CLANG_VER/bin
if [ ! -x "$LLVM/clang" ]; then
    echo "installing clang$NDK_CLANG_VER llvm$NDK_CLANG_VER lld$NDK_CLANG_VER (matching the NDK's clang)"
    sudo pacman -Syu --needed --noconfirm \
        "clang$NDK_CLANG_VER" "llvm$NDK_CLANG_VER" "lld$NDK_CLANG_VER"
fi
# The per-target drivers (aarch64-linux-android23-clang, ...) are bash scripts
# that exec "$bin_dir/clang". Replace that one binary with a shim around the
# host's own clang, keeping the NDK's host-independent sysroot + Android
# compiler-rt. The x86_64 clang binary itself is unusable here, so it goes.
rm -f "$NDK_TC/bin/clang-$NDK_CLANG_VER" "$NDK_TC/bin/clang.x86_64" "$NDK_TC/bin/clang++.x86_64"
mkdir -p "$NDK_TC/bin.native"
for t in ld.lld lld llvm-ar llvm-ranlib llvm-strip llvm-objcopy llvm-nm \
         llvm-readelf llvm-objdump llvm-as llvm-symbolizer llvm-link \
         llvm-profdata llvm-cov clang-tidy; do
    [ -x "$LLVM/$t" ] || continue
    ln -sf "$LLVM/$t" "$NDK_TC/bin.native/$t"
    ln -sf "$LLVM/$t" "$NDK_TC/bin/$t"
done
ln -sf "$LLVM/ld.lld" "$NDK_TC/bin.native/ld"
ln -sf "$LLVM/ld.lld" "$NDK_TC/bin/ld"
for pair in "clang $LLVM/clang" "clang++ $LLVM/clang++"; do
    set -- $pair
    # rm first: in a pristine NDK both are symlinks (clang -> clang-18,
    # clang++ -> clang), and `cat >` would write *through* them — clobbering the
    # C shim with the C++ one. Also what makes a re-run a plain rewrite.
    rm -f "$NDK_TC/bin/$1"
    cat > "$NDK_TC/bin/$1" <<EOF
#!/usr/bin/env bash
# Native-aarch64 shim: this NDK ships linux-x86_64 binaries only, so drive the
# host's own clang with the NDK's (host-independent) sysroot + Android
# compiler-rt, and native lld/llvm binutils from bin.native/.
NDK_TC="\$(cd "\$(dirname "\${BASH_SOURCE[0]}")/.." && pwd)"
exec $2 \\
    --sysroot="\$NDK_TC/sysroot" \\
    -resource-dir="\$NDK_TC/lib/clang/$NDK_CLANG_VER" \\
    -B"\$NDK_TC/bin.native" \\
    "\$@"
EOF
    chmod +x "$NDK_TC/bin/$1"
done
# CMake probes the *unversioned* clang the NDK toolchain file points at; the
# yasm/nasm assembler for the x86 ABIs is an x86_64 ELF and unusable here, but
# only the x86/x86_64 ABIs need it (see docs/arm64-host.md).

log "gomobile $GOMOBILE_VER (patched for an arm64 host) — needed by \`just build singbox\`"
# Upstream archNDK() panics with "unsupported GOARCH: arm64" on Linux; with the
# shim above, prebuilt/linux-x86_64 is exactly the right directory to use.
if [ ! -d "$GOMOBILE_SRC" ]; then
    # upstream install is only to populate the module cache we copy from
    go install "github.com/sagernet/gomobile/cmd/gobind@$GOMOBILE_VER" >/dev/null
    mkdir -p "$(dirname "$GOMOBILE_SRC")"
    cp -r "$(go env GOMODCACHE)/github.com/sagernet/gomobile@$GOMOBILE_VER" "$GOMOBILE_SRC"
    chmod -R u+w "$GOMOBILE_SRC"
    python3 - "$GOMOBILE_SRC/cmd/gomobile/env.go" <<'PY'
import sys
path = sys.argv[1]
src = open(path).read()
old = """			if runtime.GOOS == "darwin" {
				arch = "x86_64"
				break
			}
			fallthrough"""
new = """			// ... and on Linux/arm64 there is still no arm64 host toolchain
			// (NDK r27). This project shims prebuilt/linux-x86_64's clang to the
			// system's native clang, so use that directory here too.
			arch = "x86_64"
			break"""
if old in src:
    open(path, "w").write(src.replace(old, new))
    print("patched", path)
else:
    print("already patched (or upstream changed):", path)
PY
fi
# always (re)install from the patched tree, so the binaries can't drift from it
(cd "$GOMOBILE_SRC" && go install ./cmd/gomobile ./cmd/gobind)

log "verifying the toolchain"
tmp=$(mktemp -d) && trap 'rm -rf "$tmp"' EXIT
printf '#include <string.h>\nint main(){return (int)strlen("ok");}\n' > "$tmp/t.c"
for pair in "aarch64 arm64-v8a" "x86_64 x86_64"; do
    set -- $pair
    "$NDK_TC/bin/$1-linux-android23-clang" "$tmp/t.c" -o "$tmp/t.$1"
    echo "  cross-compiled for $2: $(file -b "$tmp/t.$1" | cut -d, -f1-2)"
done

log "done — next:"
echo "   just build singbox      # gomobile AAR (~8 min, once)"
echo "   just emulator start     # redroid (arm64 guest) on localhost:5556"
echo "   just device run         # build arm64 debug APK, install, launch"
