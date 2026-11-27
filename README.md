# V.R.T.U.A.L. — Quest 3 companion app

Android project: `com.vrtualmilitia.vrtual` • Kotlin • Java 17 • compile/target SDK 34 • min SDK 29.

## Build

- Open the folder in Android Studio (Hedgehog or newer), or run `./gradlew assembleDebug`.
- The included `gradlew` / `gradlew.bat` bootstrap Gradle 8.7 directly. This project intentionally does not need a binary `gradle-wrapper.jar`.
- GitHub Actions runs on every push and manual `workflow_dispatch`, then uploads `app-debug.apk` as artifact **VRTUAL-debug-apk**.

## Features

- Google TTS engine is selected explicitly (`com.google.android.tts`) with a default-engine fallback in both the activity and foreground service.
- Twitch IRC WebSocket chat reader; YouTube Live Chat polling through Data API v3; TikTok-compatible configurable WebSocket or HTTP relay. TikTok requires a relay service that emits JSON with `username` and `message` (or `text`) fields. Anonymous Twitch IRC is read-only and subject to Twitch's current IRC behavior/rate limits.
- TTS transient audio focus requests duck other audio where the device/audio policy supports ducking.
- Shizuku shell reset modes and fallback to Horizon Shell's app details settings.
- Edge-to-edge split WebView dashboard. The preview pane injects a mute script on page load; sites can create media later, so the script is re-applied only on page finish and cannot guarantee muting against every dynamically-created player or site policy.

## Important Quest safety note

`pm clear` deletes an app's local data. Clearing `com.oculus.vrshell`, `com.oculus.shellenv`, or `com.oculus.systemux` can reset launcher/system UI state or make the headset temporarily difficult to use. The app's Auto-Run mode runs the shell reset when the activity opens if Shizuku is already authorized; use **Off** or **Manual Only** unless you intentionally want that behavior. If Shizuku is unavailable, the app opens the Horizon Shell app details page; it does not bypass Android's confirmation UI.

Shizuku must be separately installed/started and authorized by the user. This app does not silently grant itself Shizuku access. Some Quest firmware versions may block clearing protected system packages even with Shizuku.

## Configuration

Enter a Twitch channel, YouTube live video ID/URL and Data API v3 key, optional TikTok relay URL and username. Enter full HTTPS URLs for the chat pop-out and stream preview. Google Speech Recognition & Synthesis must be installed/enabled on the headset for the preferred Google TTS engine.
