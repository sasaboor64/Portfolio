# TV Remote (ad-free)

An ad-free Android phone app that controls Android TV / Google TV devices over Wi-Fi.
It contains no ads, no analytics and no third-party SDKs, and it only talks to your TV.

## Features

- **Menu button**: sends the TV's Menu/Options key (`KEYCODE_MENU`). Tap for a normal
  press. Hold for a long-press, which some apps use for extra options.
- D-pad with OK. Arrows repeat while held. Long-press OK works too.
- Back, Home, Power, Input, Settings, Info
- Volume up/down/mute. The phone's own volume buttons also control the TV.
- Play/pause, rewind, fast-forward, channel up/down
- Keyboard: type text into the TV's search boxes
- Finds TVs on the network automatically, or you can enter the IP address by hand
- Remembers the paired TV and reconnects on launch

## Install

Download `release/tv-remote-1.0.apk` onto your phone and open it. Allow
"Install unknown apps" when Android asks. Requires Android 8.0 or newer.

The first time you connect, the TV shows a 6-character code. Type it into the app to pair.

## Build

```
cd tv-remote
./gradlew assembleRelease        # APK -> app/build/outputs/apk/release/
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
- `TvDiscovery.kt`: mDNS discovery of `_androidtvremote2._tcp`
- `MainActivity.kt`: the UI

Known limitation: the TV uses a self-signed certificate, so the app accepts any
certificate on the local network. Pairing the TV with its on-screen code is what
establishes trust.
