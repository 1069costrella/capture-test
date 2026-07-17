# Capture Probe

A minimal Android app that tests whether Amazon Music blocks the system-level
`AudioPlaybackCapture` API (the API a "middleware crossfade" app would need).

## What it actually does

- Uses `MediaProjection` + `AudioPlaybackCaptureConfiguration` (Android 10+) to
  try to capture audio specifically from Amazon Music's process (`com.amazon.mp3`).
- Records 8 seconds of whatever it captures to a raw PCM file.
- Reports the peak amplitude. Near-silence despite Amazon Music actively playing
  is the signature of `ALLOW_CAPTURE_BY_NONE` (i.e., Amazon opted out of capture).
  Real audio means capture is *not* blocked on your device/OS version.

This does not decrypt, download, or bypass DRM. It uses a public, documented
Android API that any app can request — the same mechanism apps like screen
recorders use, and the same mechanism streaming apps can opt out of.

## Build & sideload

1. Install Android Studio (Giraffe or newer).
2. `File > Open` → select this project folder.
3. Let Gradle sync (needs internet the first time, to pull the plugins/deps).
4. Plug in your Android device with USB debugging enabled (or use an emulator
   running API 29+, though playback capture behaves inconsistently on emulators —
   a real device is more reliable).
5. Click Run. Android Studio installs the debug APK directly — this **is** the
   sideload; no Play Store involved.

If you'd rather build from the command line once Gradle syncs once in Studio:
```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Running the test

1. Open Amazon Music first and start a song playing.
2. Switch to Capture Probe, tap "Start Test."
3. Approve the system permission dialog (Android always frames this as
   "screen capture," even though this app never reads pixels — it's a quirk
   of how the OS scopes the `MediaProjection` permission).
4. Keep Amazon Music playing for the 8-second window.
5. Read the result on screen.

## Reading the result

- **"SILENT capture"** → Amazon Music has almost certainly set
  `setAllowedCapturePolicy(ALLOW_CAPTURE_BY_NONE)`. A software-only middleware
  approach is a dead end against this app specifically; you'd need the
  physical loopback route discussed earlier.
- **"AUDIO CAPTURED"** → Amazon Music is not opted out on this OS version/device,
  and a software capture pipeline is viable in principle. You'd still need to
  handle track-boundary detection and build the actual crossfade/playback engine
  — this app only answers the yes/no question.

## Known rough edges

- Foreground service + `MediaProjection` permission flows changed across
  Android versions; tested logic targets API 29–34 behavior. Very new OS
  versions may require additional consent-dialog handling.
- No UI polish — this is a diagnostic tool, not a finished app.
