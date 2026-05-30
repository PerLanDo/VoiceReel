# Voice Reels

Voice Reels is a **hands-free voice controller for short-video apps**. Enable it once, then scroll,
play/pause, and like videos in **TikTok, Instagram Reels, Facebook Reels, and YouTube Shorts** using
only your voice — no tapping required.

It works by running on-device speech recognition in the background and performing the matching
swipe/tap gestures on whatever app you're watching, through Android's Accessibility framework.
Everything runs **on-device**: there is no backend, no API key, and no account.

> Earlier versions of this project shipped an in-app "reels simulator". That demo feed has been
> removed — Voice Reels is now a focused controller for real apps.

## Voice commands

| Say… | Action |
| --- | --- |
| "Next", "Down", "Skip", "Forward" | Scroll to the next video |
| "Previous", "Back", "Up", "Last" | Scroll to the previous video |
| "Play", "Resume", "Start" | **Resume** — only if the video is currently paused |
| "Pause", "Stop", "Wait" | **Pause** — only if the video is currently playing |
| "Like", "Love", "Heart", "Favorite" | Like the current video |

Natural phrases work too — e.g. *"go to the next one"* or *"I love this"*.

Play and Pause are **state-aware**: saying "pause" when the video is already paused (or "play" when
it's already playing) does nothing, so they never accidentally toggle the wrong way.

## Hearing you over the video, fast, without false repeats

Recognizing speech while a loud video plays — and reacting cleanly — is the hard part. Voice Reels
handles it on several fronts:

- **Isolates video audio while listening.** When voice control is on, Voice Reels holds transient
  audio focus *and* lowers the media stream to ~18% of your current volume (many short-video apps
  ignore focus-only ducking). While you are actively speaking it dips further — near-mute — so loud
  dialog and music in the reel cannot mask your command. The recognizer also prefers the
  `VOICE_COMMUNICATION` mic path on Android 10+ for hardware echo cancellation. Toggle under
  *Listening & performance → Lower video volume while listening*.
- **Acts on partial results.** Commands fire the **instant** a matching word is detected, instead of
  waiting for you to finish a sentence, with sub-second restart latency between listening cycles.
- **Sensitive, fuzzy matching.** The parser understands synonyms, common mis-hearings (e.g.
  *"necks"* → next, *"lake"* → like), and near-miss words via edit-distance matching, and prefers
  the fast on-device recognition engine.
- **Repeat protection.** If a word is heard several times (because you repeated it), the **same**
  command is rate-limited so the action only happens once. Choose the window under
  *Listening & performance → Repeat protection* (1s / 2s / 3s, default **2s**). A *different* command
  can still follow quickly, so "next" then "like" stays responsive.

The system recognition "beep" can also be muted (*Listening & performance → Silence recognition
beeps*).

## How it works

- A continuously-running **`SpeechRecognizer`** inside the Accessibility Service captures your voice.
- Recognized speech (and its alternative hypotheses) is mapped to a command by a small, unit-tested
  parser (`VoiceCommand.kt`) with synonym, homophone, and fuzzy matching.
- The service dispatches the gesture for the active app:
  - **Next / Previous** → a vertical swipe (works the same across all four apps).
  - **Play / Pause** → a single center tap, but only when it would actually change the state
    (detected via `AudioManager.isMusicActive()`).
  - **Like** → a center double-tap on TikTok / Instagram / Facebook (their standard "like" gesture).
    On **YouTube Shorts**, double-tap is used for seeking, so Voice Reels instead finds and clicks
    the real *Like* button via the accessibility node tree, falling back to a double-tap if it can't.
- It tracks only the **foreground package name** to pick the right Like strategy. It does not store
  screen content; the node tree is only read on demand when you say "like" in YouTube.

## Requirements

- [Android Studio](https://developer.android.com/studio) (latest stable)
- A **physical device** running Android 7.0 (API 24) or higher

> **Why a physical device?** Most Android emulators don't ship a speech-recognition engine, and the
> target apps (TikTok, etc.) aren't installed on them. Use a real phone to actually try voice control.
> The floating-bubble manual buttons can be used to test gestures without speaking.

## Run locally

1. Open Android Studio and choose **Open**, then select this project directory.
2. Let Android Studio sync Gradle and install any missing SDK components when prompted.
3. Run the **app** configuration on a connected device.

The debug build is signed automatically with Android's managed debug keystore — no setup required.

### Build from the command line

```bash
./gradlew assembleDebug        # builds app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # runs the unit tests
./gradlew installDebug         # installs onto a connected device
```

A ready-to-sideload debug APK is also kept at `.build-outputs/app-debug.apk`.

## Setup on your phone

Open Voice Reels and complete the three setup steps on the home screen:

1. **Microphone access** — grant the mic permission.
2. **Accessibility service** — tap to open *Settings → Accessibility → Voice Reels* and turn it on.
   This is what lets the app scroll/tap inside other apps for you.
3. **Background voice control** — flip the switch to start listening.

Under **Listening & performance** you can fine-tune behavior:

- **Repeat protection** — how long the same command is suppressed after firing (1s / 2s / 3s).
- **Lower video volume while listening** (on by default) — focus + direct media attenuation for loud feeds.
- **Silence recognition beeps** — mutes the system beeps that play when listening starts.
- **Floating controls bubble** — a draggable, always-on-top widget with manual buttons. Tap the
  bubble to open its panel: turn **Voice control** on or off without opening this app, or switch
  **Floating bubble** off to hide the widget entirely.

Then open TikTok, Instagram Reels, Facebook Reels, or YouTube Shorts (the home screen has quick
<<<<<<< HEAD
launch buttons that deep-link to short-video feeds when possible) and use the voice commands.
=======
launch buttons) and use the voice commands.
>>>>>>> origin/main

## Permissions

| Permission | Why |
| --- | --- |
| `RECORD_AUDIO` | Capture spoken commands |
| Accessibility service | Perform swipes/taps and read the foreground app for the Like action |
| `SYSTEM_ALERT_WINDOW` | Optional floating controls bubble |
| `INTERNET` | Some devices use network-based speech recognition |

## Project structure

```
app/src/main/java/com/example/
├── MainActivity.kt                     # Compose setup / control-center UI
├── VoiceCommand.kt                     # Command enum + pure, unit-tested parser (synonyms/fuzzy)
└── VoiceReelsAccessibilityService.kt   # Background listening, ducking, state-aware gestures, overlay
```

## Testing

Unit tests cover the command parser (`VoiceCommandParserTest`, including synonym/homophone/fuzzy
cases) and basic app resources. Run them with:

```bash
./gradlew testDebugUnitTest
```

## Known limitations

- Continuous background recognition uses battery; turn off background voice control when you're done.
- Play/Pause state detection relies on whether the app is emitting audio. If you watch with the
  video **muted**, Voice Reels can't tell playing from paused, so it falls back to doing nothing for
  the already-in-that-state case.
- Media attenuation lowers playback volume while listening and restores it when you turn voice
  control off. If an app pauses instead of playing quietly, turn the option off.
- Gesture targeting is coordinate-based for scroll/play and works across phones, but heavily
  customized device skins or unusual layouts may need different swipe zones.
- Because matching is intentionally sensitive, an unrelated word that sounds like a command can
  occasionally trigger an action.
- While Voice Reels is listening it holds the microphone, so apps that record audio at the same time
  may conflict.

## Release builds

The release build type reads signing credentials from environment variables so no secrets live in
the repo:

| Variable | Purpose | Default |
| --- | --- | --- |
| `KEYSTORE_PATH` | Path to your upload keystore | `my-upload-key.jks` |
| `STORE_PASSWORD` | Keystore password | — |
| `KEY_ALIAS` | Key alias | `upload` |
| `KEY_PASSWORD` | Key password | — |

```bash
KEYSTORE_PATH=/path/to/key.jks STORE_PASSWORD=*** KEY_PASSWORD=*** ./gradlew assembleRelease
```
