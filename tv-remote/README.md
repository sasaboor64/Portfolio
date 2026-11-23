# Remote Plus: ad-free TV remote

An ad-free Android phone app that controls Android TV / Google TV devices over Wi-Fi.
It contains no ads, no analytics and no third-party SDKs, and it only talks to your TV.

## Features

- **Menu button**: opens the TV's own menu, like the button on a physical remote. By
  default it holds Home, which opens the Google TV / Android TV quick panel (picture,
  sound, settings). TVs differ, so ⚙ lets you choose the Settings key, the TV menu key,
  or the in-app options key instead.
- **App shortcuts**: one-tap YouTube, Netflix, Prime Video and more. Add any app by package
  name or link, and reorder or remove them. On a Sony Bravia, import the exact app list from the TV.
- **Sony Bravia extras** (with IP control on): Menu opens Sony's Action Menu / Quick Settings
  bar, and apps launch through Sony's own app control.
- **Trackpad**: swipe to move, tap for OK, hold for a long-press OK. Switch to a classic
  D-pad with the chip in its corner or in ⚙.
- Back, Home, Power, Input
- VOL and CH rockers like a physical remote. The VOL rocker shows the TV's volume, and
  the phone's own volume buttons also control the TV.
- Mute, play/pause, rewind, fast-forward
- Keyboard: type text into the TV's search boxes
- Finds TVs on the network automatically, or you can enter the IP address by hand
- Remembers the paired TV and reconnects on launch

## Install

Download `release/remote-plus.apk` onto your phone and open it. Allow
"Install unknown apps" when Android asks. Requires Android 8.0 or newer.

The first time you connect, the TV shows a 6-character code. Type it into the app to pair.

## Build

Release builds read the upload key from `keystore.properties` (not committed):

```
storeFile=upload-keystore.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

Without that file, release builds are signed with the debug key.

```
cd tv-remote
./gradlew assembleRelease        # APK -> app/build/outputs/apk/release/
./gradlew bundleRelease          # AAB for Google Play
./gradlew testDebugUnitTest      # protocol tests against a fake TV
```

The release APK is signed with the debug key so it can be sideloaded. Use your own
signing key before distributing it.

## How it works

The app speaks the Android TV Remote protocol v2, the same one Google's own remote uses:

- **Pairing (port 6467):** TLS with a self-signed client certificate made on first
  launch. The TV shows a code, and the app proves it saw the code by sending
  SHA-256(client key, TV key, code).
- **Remote (port 6466):** protobuf messages for key presses, text input, volume and
  power state. Key presses use standard Android key codes (Menu = 82).

Code layout:

- `protocol/`: plain Kotlin with no Android dependencies, covered by `FakeTvTest`
- `protocol/SonyBravia.kt`: Sony IP control (Action Menu, app list, app launch)
- `AppShortcuts.kt`: app presets and storage
- `TrackpadView.kt`: the swipe trackpad
- `TvDiscovery.kt`: mDNS discovery of `_androidtvremote2._tcp`
- `MainActivity.kt`: the UI

Known limitation: the TV uses a self-signed certificate, so the app accepts any
certificate on the local network. Pairing the TV with its on-screen code is what
establishes trust.

## Google Play

Store listing text, graphics, Data safety answers and release steps are in [`store/PLAY_STORE.md`](store/PLAY_STORE.md).
