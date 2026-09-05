# CLAUDE.md

Guidance for Claude Code working in this repository.

**New to this project?** Read `docs/HANDOVER.md` first - it carries the current
state, the next action, and the decisions already settled.

## What this is

Gyro-driven, horizon-locked image stabilisation for **still photography** on
Android. A ring buffer of recent frames plus a gyroscope trace; on shutter
press the frames are warped into a common optical frame of reference and merged
into one still. Target device: Samsung Galaxy A07 5G (no OIS).

## Commands

```bash
./gradlew :core:test          # motion maths - needs no Android SDK, runs anywhere
./gradlew :app:assembleDebug  # needs the SDK; :app is excluded without one
./gradlew :app:installDebug   # deploy to a connected device
```

`settings.gradle.kts` includes `:app` only when it finds `local.properties`
with `sdk.dir`, or `ANDROID_HOME`/`ANDROID_SDK_ROOT` in the environment. If
`:app` seems to have vanished, that is why.

## The one architectural rule

**Anything that decides where a pixel goes belongs in `:core`, with a test.**

`:core` is pure Kotlin with no Android imports, so its tests run on the JVM in
milliseconds instead of on a phone in minutes. `:app` holds only what genuinely
needs hardware: Camera2, SensorManager, GLES, UI.

When adding maths, add it to `:core` and test it there. Do not reach for an
Android type in `:core` - that boundary is the reason the project is testable
at all, and it is one import away from being lost.

## Landmines

**`ImageReader` slot ownership.** Every `Image` held open is a buffer removed
from a fixed pool. Exhaust the pool and the camera *silently stops delivering
frames* - no exception, no log, just a frozen preview. `CapturedFrame` owns its
image; `FrameRingBuffer` closes what it evicts; `StillStacker` closes the burst
in a `finally`. Preserve that chain.

**`RigAlignment.handedness` is not settled.** It encodes the sign of the
rotation between gyro and camera axes. The value in the code is derived, not
confirmed on hardware. If stabilisation makes shake *worse*, flip it - that is
the first thing to try, not the last.

**Small angles break naive maths.** Hand tremor is milliradians. `acos`-based
angle extraction collapses to exactly zero there; `Quaternion.angle()` uses
`atan2` for this reason. Be suspicious of any new formula that divides by a
small quantity or takes `acos` of something near 1.

**A constant angular rate hides clock-sync bugs.** Under constant rotation a
common timestamp offset shifts anchor and frame equally and cancels exactly.
Test alignment against *oscillatory* motion, or the test proves nothing.

## Conventions

- British spelling in prose and comments (`stabilisation`, `normalise`).
- Comments explain *why*, not *what*. Density matches the surrounding file.
- Commit subject in the imperative; body explains the reasoning, not a file list.
- Docs live in `docs/`; keep `README.md` as the entry point.

## Where things are

| Path | Holds |
|---|---|
| `core/.../MotionTrack.kt` | Gyro integration into an orientation track |
| `core/.../BurstAlignment.kt` | Anchor choice and per-frame homography |
| `core/.../CameraGeometry.kt` | Intrinsics and the gyro-to-camera rig rotation |
| `app/.../probe/` | Phase 0 device probe |
| `app/.../capture/` | Camera2 ring buffer |
| `app/.../gl/` | GLES warp and merge |
| `docs/ARCHITECTURE.md` | How it fits together, and the maths |
| `docs/ROADMAP.md` | Phases with exit criteria |
| `docs/DEVICE-A07.md` | Hardware constraints and open risks |
