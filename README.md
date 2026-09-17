# TeleprompterPro — script overlay on your live camera, in one screen

A standalone **native Android** teleprompter + camera recorder (Kotlin, Jetpack
Compose, CameraX). The script is drawn **directly on top of the live camera
preview** while you record — no separate reader screen, no "draw over other
apps" permission.

Free. Offline. No account, no ads, no analytics, no subscription, no paywall,
no character limit, no script expiry.

---

## Download & install the APK (no Android Studio needed)

Every push to `main` builds a debug APK on GitHub Actions.

1. Open this repo on GitHub → **Actions** tab → click the latest
   **"Build Debug APK"** run (green check).
2. Scroll to **Artifacts** → download **`app-debug.apk`** (it arrives as a
   `.zip`; unzip it to get `app-debug.apk`).
3. Copy the APK to your phone (or download it directly on the phone).
4. On the phone, open the file. When Android asks, allow
   **"Install unknown apps"** for the browser / file manager you're using
   (Settings → Apps → Special app access → Install unknown apps).
5. Tap **Install**, then open **TeleprompterPro**.

Works on Android 7.0 (API 24) and newer, on all four ABIs
(arm64-v8a, armeabi-v7a, x86, x86_64) — one universal APK.

Local fallback (Android Studio / JDK 17 + SDK 35):

```bash
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug APK is signed with the standard debug key — fine for your own
testing; not for Play Store distribution.

---

## Why this exists — competitor failures → what this app does

| # | Complaint about "Teleprompter: Vlog & Scripts" | TeleprompterPro |
|---|---|---|
| 1 | "Voice sync" only reacts to volume | `reader/SpeechSyncEngine` + `WordMatcher`: on-device `SpeechRecognizer` partial results are matched **word-by-word** against the script; the text advances only on matched words. `onRmsChanged` is deliberately ignored. Unit-tested (`WordMatcherTest`). |
| 2 | Deceptive trials / double charges | There is no billing code at all. See *Monetization policy* below. |
| 3 | Dark video, no exposure control, no landscape, 480p cap | Exposure-compensation slider (live, even mid-take); portrait **and** landscape layouts; resolution picker lists only 720p/1080p/4K modes the lens actually reports (`CameraCapabilityChecker`). |
| 4 | External mic works 50%, silent fallback | `audio/MicrophoneProbe` opens the *selected* device, checks which device Android really routed, and measures signal before you record. During recording, CameraX `AudioStats` (silenced / source error) and device-removal callbacks raise a visible warning. Never silent. |
| 5 | 1000–2000 char limit | No `maxLength` anywhere. `ScriptTest` stores a 50,000-word script untouched. |
| 6 | Controls cover the text; tiny sliders; wrong ETA | Overlay and controls are **sibling regions** in a Column/Row — they can't overlap. Controls collapse to a thin strip 2 s into recording (tap to bring back). Full-width 44 dp sliders. ETA = `words / wpm` math in `ScrollMath`, recomputed every frame, unit-tested. |
| 7 | Script lost on backgrounding; auto-delete; stripped spacing | `script/ScriptRepository` (DataStore): debounced autosave, save on pause/stop/dispose, synchronous save in `onCleared`. No expiry code exists. Body stored byte-for-byte (only `\r\n`→`\n` on `.txt` import). |
| 8 | Crashes on remote disconnect / long takes | Remotes are plain `KeyEvent`s (nothing to disconnect). Every camera/recorder/recognizer call is guarded; `StorageGuard` + `BatteryThermalMonitor` trigger a **safe stop that still saves the file**. |
| 9 | Needs SYSTEM_ALERT_WINDOW | The overlay is a Compose `Box` inside our own camera screen. Manifest: `CAMERA`, `RECORD_AUDIO` — nothing else. |
| 10 | Unresponsive support | Open source. File issues here; the diagnostics you need are in the Camera sheet (active quality, storage, battery/thermal). |

---

## Features

**Camera + teleprompter screen (the main screen)**
- CameraX preview (rear default, front toggle) via `SingleCameraSession`
  (reused from creator-cam). Stabilization + target FPS through Camera2Interop.
- Script overlay box (top / center / bottom), semi-transparent, big text,
  draggable; progress bar + `elapsed / total • remaining` under the text.
- Record button, timer, mic indicator with live level. Auto-collapses to a
  strip 2 s after recording starts; tap preview to reveal.
- Exposure slider, zoom, torch, tap-to-focus, front-preview mirror (preview
  only — the file is never mirrored).
- Resolution / FPS / stabilization picker limited to real hardware support.
- Orientation follows the phone; **locked for the duration of a take**.
- Bluetooth remote / media buttons: play-pause, ±5 s, speed, restart, record.

**Scripts**
- Create / rename / duplicate / delete. Unlimited count and length.
- Editor keeps whitespace exactly. Paste from clipboard (works after
  switching apps), import `.txt` via the system document picker (SAF).
- Per-script font size, WPM, background dimness, scroll mode, mirror.

**Reader / scroll engine**
- Timed mode: WPM or target-duration slider; ETA is exact and live.
- Voice-sync mode: word matching (see below).
- Play / pause / rewind / forward / restart, from touch or remote.

**Recording / export**
- `RecordingController` (adapted from creator-cam's single path):
  preflight → countdown → record → monitor → safe stop → save.
- Saved to `Movies/TeleprompterPro/` through MediaStore — visible in the
  gallery immediately (API 24–28: app-owned Movies dir + media scanner, so
  still no storage permission).
- Lossless trim + optional mute (`VideoTrimmer`, reused).

**Settings** — theme, default camera, default resolution, reader defaults,
voice language, default mic behaviour. DataStore, creator-cam pattern.

---

## Voice sync — how it works and its honest limits

- Uses Android's `SpeechRecognizer` with `EXTRA_PARTIAL_RESULTS`. On API 31+
  it prefers `createOnDeviceSpeechRecognizer` when the device reports
  on-device support; otherwise the system default recognizer (usually
  Google's, which may need its **offline language pack** installed to work
  without a network — the app itself never makes network calls).
- Every hypothesis is fed to `WordMatcher`, which finds the trailing 1–3
  spoken words in a 40-word look-ahead window from the current cursor and
  moves the cursor only on a match. Fuzzy by one edit for words ≥5 letters.
- **English** (`en-US`) is available on virtually all phones.
  **Urdu** (`ur-PK`) works only if the phone's recognizer has that model;
  if it rejects the language, the app reports *"Urdu is not available in
  this phone's speech recognizer"* and switches to timed scrolling. Google's
  on-device offline packs currently do not include Urdu on most devices, so
  Urdu voice sync typically requires the online recognizer.
- **While recording**, CameraX's recorder owns the microphone. Many devices
  still feed the recognizer audio; some hand it silence or `ERROR_AUDIO`.
  The engine detects repeated instant timeouts / audio errors and falls back
  to timed scrolling with a visible banner explaining why. Test on your
  device: start voice sync, then press record and speak — the status line
  shows what the recognizer hears.

---

## Permissions

| Permission | Used for |
|---|---|
| `CAMERA` | preview + recording |
| `RECORD_AUDIO` | recording audio, mic signal test, voice sync |

Not requested: `SYSTEM_ALERT_WINDOW`, any storage permission, Bluetooth,
contacts, location, `INTERNET`. Mic enumeration uses `AudioManager`
(no Bluetooth permission needed); remotes arrive as key events.

---

## Monetization policy

v1 is fully free with no feature gating and no billing SDK. If a paid
option is ever added it must: show the exact price before any purchase
flow; never auto-charge at the end of a trial without an explicit
confirmation step; include an in-app **Cancel subscription** button that
deep-links to `https://play.google.com/store/account/subscriptions`; and
never gate previously-saved scripts or recordings.

---

## Project structure

```
app/src/main/java/com/teleprompterpro/app/
├── MainActivity.kt                # single-activity Compose host + remote key routing
├── camera/                        # SingleCameraSession (reused), CapabilityChecker + QualityAdvisor (adapted)
├── audio/                         # MicrophoneMonitor (reused), MicrophoneProbe (new)
├── recording/                     # RecordingController (adapted), StorageGuard, BatteryThermalMonitor (reused)
├── media/                         # MediaStoreSaver (reused + API 24 path), VideoTrimmer, VideoMetadata (reused)
├── script/                        # Script, ScriptRepository (new, DataStore)
├── reader/                        # ScrollMath, WordMatcher, SpeechSyncEngine, RemoteKeys (new)
├── settings/                      # AppSettings + SettingsRepository (creator-cam pattern)
└── ui/                            # camera (overlay + controls), scripts, editor, settings, recordings, trim, permissions
app/src/test/                      # JVM tests: ScrollMath, WordMatcher, Script
.github/workflows/build-debug-apk.yml
```

Toolchain: AGP 8.7.3 • Gradle 8.9 • Kotlin 2.1.0 • Compose BOM 2025.01.00 •
CameraX 1.4.2 • minSdk 24 • targetSdk 35.

Reused from [creator-cam](https://github.com/zaindrs1-prog/creator-cam);
everything dual-camera / GL composition was intentionally left out.
