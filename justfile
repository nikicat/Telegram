# Adapted from ../Telegram-X/justfile. Adjusted for:
#  - this project's module/flavor layout (afatX64 debug, see TMessagesProj_App)
#  - JDK 17 pin (system default on this host is JDK 21)
#  - debug package suffix `.beta` and launcher activity `DefaultIcon`
#  - host ADB port 5556 so it coexists with Telegram-X's redroid on 5555
#
# Recipes are split into modules (see *.just). Invoke as `just <module> <recipe>`, e.g.
#   just build apk            just device run            just emulator start
#   just build publish        just firebase google-services
# Override the build matrix with env vars, e.g. `ABI=Arm64 just build apk`, `BUILD=Release just device run`.
# `just` or `just --list` lists modules; `just <module>` lists that module's recipes.

# Shared settings + variables (also imported by each module, since just does not pass a parent's
# variables down into a module). The build matrix is env-var driven for the same reason: just's
# `name=value` overrides never reach a module's recipes.
import 'common.just'

mod build
mod firebase
mod emulator
mod device

# list available modules
default:
    @just --list

# bootstrap this host: packages, Android SDK/NDK/CMake, local.properties, aarch64 shims (idempotent)
setup:
    bash scripts/setup-toolchain.sh
