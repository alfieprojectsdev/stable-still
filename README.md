# stable-still

Gyro-driven, horizon-locked image stabilisation for **still photography** on
Android - the HyperSmooth idea applied to a shutter press instead of a video
stream.

A continuously running ring buffer keeps the last few frames warm. When you tap
the shutter, the app takes the burst that *brackets* the tap, uses the
gyroscope trace to work out exactly how the phone rotated during each exposure,
warps every frame into the same optical frame of reference, and merges them into
one still.

Two things come out of that which no single exposure gives you:

- **Sharpness without a tripod.** Aligning before averaging means the merge
  removes noise rather than adding blur.
- **A level horizon**, from the accelerometer, independent of how you were
  holding the phone.

Target device: **Samsung Galaxy A07 5G** - a budget handset with no OIS, which
is exactly why software stabilisation is worth building for it.

## Why this doesn't already exist

Software stabilisation on phones is a *video* feature. Open Camera and MotionCam
both do it, both only in video mode, both on low-resolution real-time streams.
For full-resolution stills, apps either lean on hardware OIS (which the A07 does
not have) or on multi-frame HDR alignment that assumes you barely moved.

## Project layout

```
core/    Pure Kotlin. Quaternions, gyro integration, homography, anchor
         selection, alignment planning. No Android imports - unit-tested on the JVM.
app/     Android. Camera2 capture, sensor recording, GLES warp and merge, UI.
docs/    Architecture, roadmap, and the device-specific constraint notes.
```

The split is load-bearing. Everything that decides *where a pixel goes* lives in
`core` and is tested in milliseconds on a laptop; only the parts that genuinely
need hardware live in `app`.

## Building

```bash
# Math core - works anywhere with a JDK 17+, no Android SDK needed
gradle :core:test

# Full app - needs the Android SDK (Android Studio, or ANDROID_HOME set)
./gradlew :app:assembleDebug
```

`settings.gradle.kts` only includes `:app` when it can find an SDK, so the core
test suite stays runnable in CI and on any machine.

## Status

| Phase | What | State |
|---|---|---|
| 0 | Device probe - what can this phone actually do? | Implemented |
| 1 | Ring-buffer capture + gyro recording | Implemented, untested on device |
| 2 | Motion maths (integration, homography, anchors) | Implemented, 38 unit tests passing |
| 3 | GPU warp + ghost-rejecting merge | Implemented, untested on device |
| 4 | Clock-sync auto-calibration + optical refinement | Scaffolded, not implemented |
| 5 | UX, presets, long-exposure mode | Not started |

**Start by running Phase 0 on the handset.** The probe reports whether the
gyroscope is real hardware or a low-rate software fusion, and whether the camera
and sensor clocks share a time base. Those two answers decide whether the rest of
the pipeline works as designed or needs its fallback path - see
[docs/DEVICE-A07.md](docs/DEVICE-A07.md).

## Working on this from Windows

This repository was started from a cloud session. Moving it to a local Windows
machine is an ordinary clone - there is no cloud-specific state in the tree.

```powershell
git clone https://github.com/alfieprojectsdev/stable-still
cd stable-still
```

Open the folder in Android Studio. It writes `local.properties` with the SDK
path on first sync, which is what makes `settings.gradle.kts` start including
`:app`.

**Build natively on Windows, not under WSL2.** WSL2 is the better shell, but the
Android toolchain fights it: `adb` needs `usbipd-win` to see a USB device, the
emulator needs nested virtualisation, and Android Studio has to run over WSLg.
For an Android project the toolchain wins that argument.

If the motivation for WSL2 was keeping a personal Claude Code login from
colliding with a work one, that is better solved directly - `CLAUDE_CONFIG_DIR`
relocates credentials, settings, MCP servers and history as a unit:

```powershell
# in $PROFILE
function claude-personal {
    $old = $env:CLAUDE_CONFIG_DIR
    $env:CLAUDE_CONFIG_DIR = "$HOME\.claude-personal"
    try { claude @args } finally { $env:CLAUDE_CONFIG_DIR = $old }
}
```

Git identity is a separate axis from the Claude login, so pin it per-repository
rather than relying on a global default:

```powershell
git config user.email "you@example.com"
```

## Docs

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) - how the pieces fit, and the maths
- [docs/ROADMAP.md](docs/ROADMAP.md) - phase-by-phase plan with exit criteria
- [docs/DEVICE-A07.md](docs/DEVICE-A07.md) - Galaxy A07 5G constraints and open risks
