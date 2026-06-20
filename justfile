# Adapted from ../Telegram-X/justfile. Adjusted for:
#  - this project's module/flavor layout (afatX64 debug, see TMessagesProj_App)
#  - JDK 17 pin (system default on this host is JDK 21)
#  - debug package suffix `.beta` and launcher activity `DefaultIcon`
#  - host ADB port 5556 so it coexists with Telegram-X's redroid on 5555
#
# Recipes are split into modules (see *.just). Invoke as `just <module> <recipe>`, e.g.
#   just build apk            just device run            just emulator start
#   just release publish      just firebase google-services
# Override the build matrix before the module, e.g. `just abi=Arm64 build apk`, `just build=Release device run`.
# `just` or `just --list` lists modules; `just <module>` lists that module's recipes.

# Shared settings + variables (also imported by each module). Imported here so command-line
# overrides like `just abi=Arm64 ...` are accepted at the root and propagate into the modules.
import 'common.just'

mod build
mod release
mod firebase
mod emulator
mod device

# list available modules
default:
    @just --list
