import 'common.just'

# list this module's recipes
default:
    @just --list build

# build the singbox gomobile AAR (~75 MB, gitignored; required by apk)
singbox:
    env GO111MODULE=on PATH=$(go env GOPATH)/bin:$PATH \
        bash singbox/go/build.sh

# build singbox AAR only if it doesn't exist yet (idempotent guard for apk)
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
