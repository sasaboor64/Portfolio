# Google Play release kit: Remote Plus

## Files in this folder

| File | Play Console field |
|---|---|
| `icon-512.png` | App icon (512×512) |
| `feature-graphic-1024x500.png` | Feature graphic |
| `screenshot-1-remote.png` … `screenshot-4-sony.png` | Phone screenshots (1080×2160) |
| `../app/build/outputs/bundle/release/app-release.aab` | The upload itself (build it with `./gradlew bundleRelease`) |

## Store listing

**App name** (30 max): `Remote Plus – TV Remote`

**Short description** (80 max):
`Ad-free remote for Android TV, Google TV & Sony Bravia, with trackpad and apps.`

**Full description:**

```
Remote Plus turns your phone into a fast, clean remote for your smart TV. No ads. No tracking. No accounts.

WORKS WITH
• Android TV and Google TV televisions and streaming boxes
• Sony BRAVIA: extra support for the Action Menu (Quick Settings) and your TV's own app list

FEATURES
• Trackpad: swipe to move, tap for OK, hold for long-press. Prefer buttons? Switch to a classic D-pad.
• App shortcuts: open YouTube, Netflix, Prime Video and more with one tap. Add any app, reorder or remove them.
• Sony BRAVIA: import the exact apps installed on your TV, and open the TV's Quick Settings bar (picture, sound, speakers) with the Menu button.
• Volume and channel rockers that show your TV's volume. Your phone's own volume buttons control the TV, too.
• Keyboard: type into search boxes on the TV instead of pecking at letters.
• Power, Home, Back, Input, Mute, and play/pause, rewind and fast-forward.
• Finds your TV automatically and reconnects every time you open the app.

PRIVATE BY DESIGN
The app talks only to your TV, over your home Wi-Fi. Nothing is sent anywhere else.

SETUP
Connect your phone to the same Wi-Fi as the TV, pick the TV, and type the code it shows. That's it.
For Sony extras, turn on IP control on the TV (the app shows you how).

Remote Plus is an independent app and is not affiliated with Google or Sony. Android TV and Google TV are trademarks of Google LLC. BRAVIA is a trademark of Sony Group Corporation.
```

**Category:** Tools  ·  **Tags:** Remote control, Smart TV  ·  **Contains ads:** No  ·  **In-app purchases:** No

**Privacy policy URL:** `https://sasaboor64.github.io/Portfolio/tv-remote/privacy-policy.html`
(Live once GitHub Pages is on for the repository's `main` branch and this branch is merged. See "Before you submit".)

## App content answers

**Data safety**
- Does your app collect or share any of the required user data types? **No**
  (All data stays on the device. Talking to the user's own TV on the local network isn't "collection".)
- Is all user data encrypted in transit? Answer **No**. The Sony IP-control requests are plain HTTP on
  the home network, as Sony's protocol requires. The Android TV connection is TLS.
- Do you provide a way for users to request data deletion? Not applicable: nothing is collected. Uninstalling removes everything.

**Content rating questionnaire:** Category "Utility, Productivity, Communication, or Other". Answer No to everything → rated Everyone / PEGI 3.

**Target audience:** 18+ (or 13+). Not designed for children.

**Ads:** No ads.

**App access:** "All functionality is available without special access." Add a reviewer note:
"Needs an Android TV / Google TV on the same Wi-Fi network. Without a TV the app shows the TV picker."

**Government apps / financial features / health:** No.

## Before you submit

1. **Developer account**: play.google.com/console, with a one-time $25 fee and identity verification.
   *New personal accounts must run a closed test with at least 12 testers for 14 days before they can publish
   to production.* Plan for that: upload the AAB to the Closed testing track first and invite 12+ people.
2. **Privacy policy URL**: merge this branch into `main`, then GitHub → repository Settings → Pages →
   "Deploy from a branch" → `main` / root. The policy is then at the URL above.
   (Any other public URL hosting `privacy-policy.html` works too. If you use another one, update
   `PRIVACY_URL` in `MainActivity.kt`.)
3. **Play App Signing**: accept Google-managed signing when you create the app. Your
   `upload-keystore.jks` is only the *upload* key. If you ever lose it, Google can reset it.
4. Build: `./gradlew bundleRelease` → upload `app-release.aab`.

## Updating later

Raise `versionCode` (and `versionName`) in `app/build.gradle.kts` for every upload.
