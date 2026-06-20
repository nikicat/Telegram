import 'common.just'

# list this module's recipes
default:
    @just --list build

# build the singbox gomobile AAR (~75 MB, gitignored; required by apk/aab)
singbox:
    env GO111MODULE=on PATH=$(go env GOPATH)/bin:$PATH \
        bash singbox/go/build.sh

# build singbox AAR only if it doesn't exist yet (idempotent guard for apk/aab)
[private]
singbox-if-missing:
    test -f singbox/libs/singboxbridge.aar || just build singbox

# build APK (honors the abi/build vars; override with e.g. `just abi=Arm64 build apk`)
apk: singbox-if-missing
    {{_gradle}} :TMessagesProj_App:assembleAfat{{abi}}{{build}}

# print absolute path to the built APK (does not build). Honors the abi/build vars.
apk-path:
    @realpath TMessagesProj_App/build/outputs/apk/afat{{abi}}/$(echo {{build}} | tr A-Z a-z)/*.apk

# clean build outputs (slow next build)
clean-build:
    {{_gradle}} :TMessagesProj_App:clean

# build a release AAB for Google Play (flavor bundleAfat: minSdk 21, all 4 ABIs; bump version first)
aab: singbox-if-missing
    {{_gradle}} :TMessagesProj_App:bundleBundleAfatRelease

# print absolute path to the built AAB (does not build)
aab-path:
    @realpath TMessagesProj_App/build/outputs/bundle/bundleAfatRelease/*.aab

# build an arm64-only release AAB (~1/4 the native size of `aab`; drops v7a/x86/x86_64)
aab-arm64: singbox-if-missing
    {{_gradle}} :TMessagesProj_App:bundleBundleAfatArm64Release

# print absolute path to the built arm64-only AAB (does not build)
aab-arm64-path:
    @realpath TMessagesProj_App/build/outputs/bundle/bundleAfatArm64Release/*.aab

# bump APP_VERSION_CODE +1 (Play needs an increasing code); optionally set name: `just build bump-version 12.7.4`
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
# they upload to the play.track track (default "internal"). Bump the version first (`just build bump-version`).

# upload the full AAB (all ABIs) + store listing to Google Play
publish: singbox-if-missing
    {{_gradle}} :TMessagesProj_App:publishBundleAfatReleaseApps

# upload the arm64-only AAB + store listing to Google Play
publish-arm64: singbox-if-missing
    {{_gradle}} :TMessagesProj_App:publishBundleAfatArm64ReleaseApps

# upload only the store listing (title/descriptions/graphics from src/main/play); no AAB build/upload
publish-listing:
    {{_gradle}} :TMessagesProj_App:publishBundleAfatReleaseListing
