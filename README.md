## Telegram messenger for Android

[Telegram](https://telegram.org) is a messaging app with a focus on speed and security. It’s superfast, simple and free.
This repo contains the official source code for [Telegram App for Android](https://play.google.com/store/apps/details?id=org.telegram.messenger).

## Development (this fork)

```sh
just setup            # bootstrap the host: packages, SDK/NDK/CMake, local.properties
just build singbox    # gomobile AAR (once; ~8 min)
just emulator start   # redroid container + wait for boot (ADB on localhost:5556)
just device run       # build + install + launch the debug APK
just device logcat    # Telegram-tagged logcat
```

`just` lists the modules, `just <module>` its recipes: **build** (apk/aab/publish),
**device** (adb/install/run/scrcpy), **emulator** (redroid), **firebase**.
The build matrix is set with env vars, e.g. `ABI=Arm64 BUILD=Release just build apk`;
`ABI` defaults to the host's architecture. (just's `name=value` overrides do not
reach recipes inside a module, so they are not used here.)

Per-machine config lives in `local.properties` — see `local.properties.example`
for every key (app.id, Telegram API credentials, proxy link, signing, Firebase,
Play publishing). Building on an aarch64 host needs a few shims, all installed
and explained by `just setup`: [docs/arm64-host.md](docs/arm64-host.md).

## Creating your Telegram Application

We welcome all developers to use our API and source code to create applications on our platform.
There are several things we require from **all developers** for the moment.

1. [**Obtain your own api_id**](https://core.telegram.org/api/obtaining_api_id) for your application.
2. Please **do not** use the name Telegram for your app — or make sure your users understand that it is unofficial.
3. Kindly **do not** use our standard logo (white paper plane in a blue circle) as your app's logo.
3. Please study our [**security guidelines**](https://core.telegram.org/mtproto/security_guidelines) and take good care of your users' data and privacy.
4. Please remember to publish **your** code too in order to comply with the licences.

### API, Protocol documentation

Telegram API manuals: https://core.telegram.org/api

MTproto protocol manuals: https://core.telegram.org/mtproto

### Compilation Guide

**Note**: In order to support [reproducible builds](https://core.telegram.org/reproducible-builds), this repo contains dummy release.keystore,  google-services.json and filled variables inside BuildVars.java. Before publishing your own APKs please make sure to replace all these files with your own.

You will require Android Studio 3.4, Android NDK rev. 20 and Android SDK 8.1

1. Download the Telegram source code from https://github.com/DrKLO/Telegram ( git clone https://github.com/DrKLO/Telegram.git )
2. Copy your release.keystore into TMessagesProj/config
3. Fill out RELEASE_KEY_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_STORE_PASSWORD in gradle.properties to access your  release.keystore
4.  Go to https://console.firebase.google.com/, create two android apps with application IDs org.telegram.messenger and org.telegram.messenger.beta, turn on firebase messaging and download google-services.json, which should be copied to the same folder as TMessagesProj.
5. Open the project in the Studio (note that it should be opened, NOT imported).
6. Fill out values in TMessagesProj/src/main/java/org/telegram/messenger/BuildVars.java – there’s a link for each of the variables showing where and which data to obtain.
7. You are ready to compile Telegram.

### Localization

We moved all translations to https://translations.telegram.org/en/android/. Please use it.
