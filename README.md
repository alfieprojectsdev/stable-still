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

### Prerequisites

What you actually need is the **Android SDK**. Android Studio is simply the
least painful way to get it, and it bundles a compatible JDK.

| | |
|---|---|
| **Android Studio** | Stable channel, Windows 64-bit `.exe`. ~1.5 GB download, budget ~10 GB installed. |
| **SDK Platform 35** | The project sets `compileSdk`/`targetSdk` to 35. |
| **SDK Build-Tools** | Latest; installed by default. |
| **SDK Platform-Tools** | Provides `adb`, which is how the app reaches the phone. |

Install the last three from **Settings → Languages & Frameworks → Android SDK**.

You do **not** need to install Gradle or a JDK separately. The Gradle wrapper is
committed, and Android Studio's bundled JetBrains Runtime satisfies the JDK 17
target this project builds against.

**Skip the emulator system images.** This app reads a physical gyroscope, a real
sensor's rolling-shutter timing, and the GPU. An emulator has none of those in
any meaningful form - the Phase 0 probe would report a fiction. Everything gets
tested on a real handset over USB, so those several gigabytes buy nothing here.

### Phone setup (once)

1. Settings → About phone → Software information → tap **Build number** 7 times.
2. Settings → Developer options → enable **USB debugging**.
3. Connect over USB and accept the *Allow USB debugging?* prompt on the phone.

If Windows does not see the device, install the Samsung USB driver - Galaxy
handsets sometimes need it where generic WinUSB is enough for other phones.

### Clone and build

```powershell
git clone https://github.com/alfieprojectsdev/stable-still
cd stable-still
```

Open the folder in Android Studio. It writes `local.properties` with the SDK
path on first sync, which is what makes `settings.gradle.kts` start including
`:app`.

```powershell
adb devices              # the phone should read "device", not "unauthorized"
.\gradlew :core:test     # maths tests; needs no SDK
.\gradlew :app:assembleDebug
```

`:core:test` passing while `:app:assembleDebug` fails is the expected first
state - see [Status](#status).

### Why not WSL2

Build natively on Windows. WSL2 is the better shell, but the Android toolchain
fights it: `adb` needs `usbipd-win` to see a USB device, the emulator needs
nested virtualisation, and Android Studio has to run over WSLg. For an Android
project the toolchain wins that argument.

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

- [docs/HANDOVER.md](docs/HANDOVER.md) - current state, next action, decisions already settled
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) - how the pieces fit, and the maths
- [docs/ROADMAP.md](docs/ROADMAP.md) - phase-by-phase plan with exit criteria
- [docs/DEVICE-A07.md](docs/DEVICE-A07.md) - Galaxy A07 5G constraints and open risks
