# Shared settings + variables, imported by the root justfile and every module.
# Importing into both root and modules lets CLI overrides (e.g. `just abi=Arm64 build apk`)
# propagate into the modules.

set shell := ["bash", "-cu"]

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

# Build matrix. Override BEFORE the module + recipe, e.g. `just abi=Arm64 build apk`, `just build=Release device run`.
#   abi: X64|Arm64   build: Debug|Release|Standalone   (defaults match the redroid inner loop)
abi   := "X64"
build := "Debug"
