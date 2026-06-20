import 'common.just'

# list this module's recipes
default:
    @just --list device

# connect adb to DEVICE (no-op for USB serials, only for host:port)
adb device=redroid:
    @if [[ "{{device}}" == *:* ]]; then adb connect {{device}}; fi

# open an Android shell on DEVICE
shell device=redroid: (adb device)
    adb -s {{device}} shell

# mirror screen of DEVICE with scrcpy
scrcpy device=redroid: (adb device)
    scrcpy -s {{device}}

# build + install the APK on DEVICE. Honors the abi/build vars (e.g. `just abi=Arm64 device install`).
install device=redroid: (adb device)
    @just abi={{abi}} build={{build}} build apk
    adb -s {{device}} install -r TMessagesProj_App/build/outputs/apk/afat{{abi}}/$(echo {{build}} | tr A-Z a-z)/*.apk

# install without rebuilding (uses whatever APK is on disk). Honors the abi/build vars.
install-only device=redroid: (adb device)
    adb -s {{device}} install -r TMessagesProj_App/build/outputs/apk/afat{{abi}}/$(echo {{build}} | tr A-Z a-z)/*.apk

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
