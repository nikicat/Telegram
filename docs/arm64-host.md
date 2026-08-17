# Building on an aarch64 host (no emulation)

This project is developed on an **Arch Linux ARM (aarch64)** box — a Rockchip
SBC with a Mali GPU (`panthor` + mesa), 8 cores, 31 GB RAM. It started on an
x86_64 host with an NVIDIA card; anything in the other files that says "x86_64"
or "NVIDIA" is that machine's history, not a requirement.

Everything builds **natively**: the Gradle/AGP pipeline, the CMake/NDK native
tree under `TMessagesProj/jni` (ffmpeg, boringssl, tgcalls, tde2e, …) and the
gomobile build of `singboxbridge.aar`. No qemu, no binfmt, no x86_64 container.

Run `just setup` (⇒ `scripts/setup-toolchain.sh`) to get from a bare Arch
install to a working build host; it is idempotent, and a no-op re-run still
re-verifies the toolchain. The rest of this file is *why* it does what it does.
The sibling `../purpletube` project ported the same host first; this is the same
set of shims plus the ones a from-source build needs (CMake).

## What Google does not ship for linux-arm64

| Piece | Status | What we do |
|---|---|---|
| **aapt2** (AGP needs it) | maven.google.com has classifiers `linux`, `osx`, `windows` only — `aapt2-*-linux-arm64.jar` is a 404 | native aarch64 build, pointed at via an AGP property |
| **CMake** (`cmake;3.22.1`, `cmake;3.10.2` SDK packages) | linux-x86_64 zips only | Kitware's official `cmake-3.22.1-linux-aarch64` tarball + `cmake.dir` |
| **NDK** (r27, for the jni tree and `gomobile bind`) | `repository2-3.xml` lists `host-arch=x64` for every Linux NDK archive (macOS gets `aarch64`, Linux doesn't) | keep the NDK's host-independent parts, drive them with the host's own clang |
| **platform-tools** (`adb`) | x86_64 zip | Arch's `android-tools` package — native `adb`, `fastboot` |
| AUR `android-ndk`, `android-sdk-build-tools` | `arch=('x86_64')` — they just repackage Google's zips | not usable; see above |

Pure-Java parts of the pipeline have no arch problem and run as-is: Gradle, AGP,
`sdkmanager`, d8/R8, apksigner, bundletool, the Play Publisher plugin. So do
`scrcpy`, ImageMagick and `rsvg-convert` from the Arch repos. The SDK's
`build-tools;35.0.0` package stays installed (AGP wants it present for the
declared `buildToolsVersion`) but the only binary in it AGP actually executes is
aapt2 — which is overridden below.

## 1. aapt2 — a native binary + an AGP override

`github.com/lzhiyong/android-sdk-tools` publishes statically-linked aarch64
builds of the build-tools (aapt2, aapt, zipalign, …). Release 35.0.2 gives
**aapt2 2.19**, which runs on glibc despite being built for Android, and speaks
the daemon protocol AGP uses (`aapt2 daemon` → `Ready`).

AGP still honours the escape hatch (`Aapt2FromMaven`: *"for setting a local path
to the executable"*), so `~/.gradle/gradle.properties` carries:

    android.aapt2FromMavenOverride=/home/zxc/Android/aapt2-aarch64/aapt2

It is user-global on purpose — the path is host-specific, so it has no business
in the repo's `gradle.properties`.

## 2. CMake — Kitware's aarch64 3.22.1, selected per host

The jni tree is an `externalNativeBuild { cmake { … } }`, and upstream Telegram
pins `version '3.10.2'` — an SDK package that exists for linux-x86_64 only.
Arch's own `cmake` is 4.x, far newer than anything NDK r27 was tested against,
so the pick is Kitware's official **3.22.1 linux-aarch64** tarball (3.22.1 is
also AGP's own default, i.e. the version the NDK's `android.toolchain.cmake` is
exercised with). AGP looks for `ninja` **inside `cmake.dir/bin`**, never on
`PATH`, so setup symlinks the system ninja there.

Selecting it takes two keys in `local.properties` (both written by `just setup`):

    cmake.dir=/home/zxc/Android/cmake-3.22.1-aarch64
    cmake.version=3.22.1

One file has to be borrowed from Google's package: `TMessagesProj/jni/CMakeLists.txt`
does `include(AndroidNdkModules)` (for `android_ndk_import_module_cpufeatures()`),
and that module exists only in the SDK's CMake fork — not in upstream Kitware
CMake, and no longer in the NDK's `build/cmake/`. Setup installs `cmake;3.22.1`
and copies the `AndroidNdk*.cmake` modules (plain, arch-independent CMake) into
the aarch64 install; none of the x86_64 package's binaries is ever executed.

`cmake.dir` is AGP's own property; `cmake.version` is ours — the six module
`build.gradle`s read `hostProp('cmake.version', '3.10.2')` (helper defined in
the root `build.gradle`), because AGP errors out when the declared version and
the one found at `cmake.dir` disagree. Unset, the build is byte-for-byte the
upstream one, so x86_64 hosts and CI are unaffected.

The x86/x86_64 ABIs additionally assemble with `yasm`, which the NDK ships as an
x86_64 binary — irrelevant here, since the guest is arm64 (`enable_language(ASM)`
covers `armeabi-v7a`/`arm64-v8a`; see `TMessagesProj/jni/CMakeLists.txt`).

## 3. NDK — host-independent sysroot + the system clang

Only the *binaries* in `toolchains/llvm/prebuilt/linux-x86_64/bin` are x86_64.
The parts that describe the **target** are host-independent: the Bionic sysroot
(headers + `libc.so`/`libm.so`/… per API level) and the Android compiler-rt
builtins under `lib/clang/18/lib/linux`.

Handily, the per-target drivers (`aarch64-linux-android23-clang`, …) are *bash
scripts* that exec `"$bin_dir/clang"`. So the shim is one file:

```bash
exec /usr/lib/llvm18/bin/clang \
    --sysroot="$NDK_TC/sysroot" \
    -resource-dir="$NDK_TC/lib/clang/18" \
    -B"$NDK_TC/bin.native" \
    "$@"
```

`bin.native/` holds symlinks to `ld.lld`/`llvm-ar`/`llvm-strip`/… from the same
LLVM, so clang finds a linker without `-fuse-ld=lld` — which must *not* be
passed, since Go's cgo compiles with `-Werror` and would fail on `argument
unused during compilation`.

**The host clang must match the NDK's clang major version** (Arch's `clang18`
`llvm18` `lld18`, installed by `just setup`; the version is read off
`$NDK_TC/lib/clang/*`). NDK r27 is clang 18, and the resource dir it needs for
Android's compiler-rt also carries clang 18's *builtin headers*. Arch's default
clang 22 parses those headers with its own newer builtins and the jni tree dies
in `arm_neon.h`:

    error: incompatible constant for this __builtin_neon function
    error: invalid conversion between vector type 'float16x8_t' and integer type 'int'

`../purpletube` never hit this — its only native build is gomobile/cgo, which
does not pull in the NEON intrinsics. The pin is also why `lld18` is installed:
`TMessagesProj/jni/CMakeLists.txt` builds with `-flto=full`, so the linker has to
read the compiler's bitcode.

Two more things worth knowing:

- The pristine NDK has `clang -> clang-18` and `clang++ -> clang` as **symlinks**.
  Write the shims without deleting those first and `clang++`'s content lands in
  `clang`, silently turning every C compile into C++. `setup-toolchain.sh` `rm`s
  both first.
- Re-installing or upgrading the NDK restores Google's `bin/`, so re-run
  `just setup` afterwards. Ditto after a sibling project's setup script rewrites
  the shim — the NDK is shared per-host.
- Changing the compiler under an existing native build invalidates nothing that
  ninja tracks (the path is unchanged), so wipe `TMessagesProj/.cxx` when you do.

## 4. gomobile — one-line patch

`sagernet/gomobile@v0.1.12`'s `archNDK()` panics with `unsupported GOARCH:
arm64` on Linux (it only special-cases darwin/arm64). With the shim above,
`prebuilt/linux-x86_64` *is* the right directory on this host, so the patched
copy in `~/src/gomobile-sagernet-v0.1.12` returns it and `go install`s from
there. `singbox/go/build.sh` needs no arch-specific change.

`just build singbox` then produces `singbox/libs/singboxbridge.aar` — 75 MB,
with `jni/{arm64-v8a,armeabi-v7a,x86_64,x86}/libgojni.so`.

## Java

Two JDKs, deliberately:

- **JDK 17** for Gradle/AGP — pinned in `common.just` (`java_home`) and used by
  every gradle recipe.
- **newest (26) as the system default** — nothing in this repo needs it, but the
  sibling projects on this host run tools as plain `java -jar` that want 21+.

## redroid

The guest ABI follows the host: `redroid/redroid:11.0.0-latest` is a multi-arch
tag, so podman pulls the arm64 image and Android reports
`ro.product.cpu.abi=arm64-v8a`. `common.just` therefore defaults `abi` to the
host architecture (`Arm64` here, `X64` on the old box), and `just device run`
installs the matching flavor with no extra flags.

Why Android 11 and not the previously used `teddynight/redroid16`: that image is
published for amd64 only, and redroid ≥ 12 renders software-decoded video as a
black surface in guest GPU mode anyway. Details and the evidence trail are in
`compose.yaml` and `../purpletube/docs/redroid.md`. Host GPU mode is still not
an option — this host's Mali is mesa-driven (`panthor`), but redroid 11's
bundled mesa long predates that driver.

Host prerequisites (subuid/subgid, `loop` + `dm-verity` modules) are listed at
the top of `compose.yaml`; they are already set up on this box.

## Status on this host

Verified end-to-end: `just setup`, `just build singbox` (native gomobile, 4
ABIs), `just build apk` (arm64-v8a debug, full jni tree compiled natively),
`just emulator start` (arm64 guest, Android 11, booted), `just device run`
(install + launch on redroid).
