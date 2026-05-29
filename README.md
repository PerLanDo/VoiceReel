# Voice Reels

Voice Reels is a hands-free, voice-controlled short-video experience for Android. It ships with
two things:

1. **A built-in reels simulator** — a clean, minimalist TikTok-style vertical feed (rendered with
   procedurally animated Compose canvases, so no network or media files are required) that you can
   scroll, play/pause, and like entirely with your voice.
2. **A system-wide voice assistant** — an optional Accessibility Service that lets you control
   *other* shorts/reels apps (TikTok, YouTube Shorts, Instagram Reels, Facebook Reels) hands-free by
   performing swipe and tap gestures on your behalf.

Everything runs **on-device**. The app uses Android's built-in `SpeechRecognizer`; there is no
backend, no API key, and no account required.

## Voice commands

| Say… | Action |
| --- | --- |
| "Next", "Down", "Skip", "Forward" | Go to the next video |
| "Prev", "Previous", "Back", "Up" | Go to the previous video |
| "Play", "Start", "Resume", "Go" | Resume playback |
| "Pause", "Stop", "Wait", "Hold" | Pause playback |
| "Like", "Love", "Heart", "Favorite" | Like the current video |

In the in-app simulator you can also tap the on-screen command buttons (Play / Pause / Next / Prev /
Like) to trigger the same actions without speaking — handy for testing in noisy environments.

## Requirements

- [Android Studio](https://developer.android.com/studio) (latest stable)
- JDK 11+ (bundled with Android Studio)
- An emulator or device running Android 7.0 (API 24) or higher

> **Voice recognition note:** Android emulators usually do not provide a speech recognition engine,
> so the spoken commands will not be captured there. Use the on-screen command buttons to test the
> simulator on an emulator, and use a physical device to try real voice control.

## Run locally

1. Open Android Studio and choose **Open**, then select this project directory.
2. Let Android Studio sync Gradle and install any missing SDK components when prompted.
3. Run the **app** configuration on an emulator or a connected device.

The debug build is signed automatically with Android's managed debug keystore, so no extra setup is
needed.

### Build from the command line

```bash
./gradlew assembleDebug        # builds app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # runs the unit tests
./gradlew installDebug         # installs onto a running device/emulator
```

## Enabling system-wide voice control (optional)

To control other apps such as TikTok or YouTube Shorts with your voice:

1. Open Voice Reels and tap the **Assistant** tab.
2. Tap **Enable Voice Assistant** and turn on **Voice Reels** under
   *Settings → Accessibility → Installed services*.
3. Back in the Assistant tab, enable **System Background Listening**.
4. (Optional) Enable the **Floating Controls Bubble** to get a draggable, always-on-top control
   widget. This requires the "Display over other apps" permission.
5. Open your favorite shorts app and use the voice commands above.

The Accessibility Service is used solely to dispatch swipe/tap gestures and to optionally mute the
system recognition beeps. It does not read or store screen content
(`onAccessibilityEvent` is intentionally a no-op).

## Project structure

```
app/src/main/java/com/example/
├── MainActivity.kt                     # Compose UI + in-app SpeechRecognizer wiring
├── VoiceReelsViewModel.kt              # Feed state, command parsing, UI state flows
├── ReelItem.kt                         # Reel data model + ReelType enum
└── VoiceReelsAccessibilityService.kt   # System-wide gestures + floating overlay
```

## Release builds

The release build type expects signing credentials to be supplied via environment variables so that
no secrets live in the repository:

| Variable | Purpose | Default |
| --- | --- | --- |
| `KEYSTORE_PATH` | Path to your upload keystore | `my-upload-key.jks` |
| `STORE_PASSWORD` | Keystore password | — |
| `KEY_ALIAS` | Key alias | `upload` |
| `KEY_PASSWORD` | Key password | — |

```bash
KEYSTORE_PATH=/path/to/key.jks STORE_PASSWORD=*** KEY_PASSWORD=*** ./gradlew assembleRelease
```
