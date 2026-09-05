# Session log

Newest first. One entry per working session: what changed, what was measured,
and what the next session should not have to rediscover.

`docs/HANDOVER.md` carries the *current* state and the next action.
This file carries how it got there.

---

## 2026-09-05 - Phase 0 run, Phase 1 built and replayed

First local session. The project arrived from a cloud session with `:core`
tested and `:app` never compiled by anything.

### Environment, settled once

- **`JAVA_HOME` must be Temurin 21.** Android Studio Quail bundles JBR **25**,
  and Gradle 8.14.3's embedded Kotlin script compiler throws
  `IllegalArgumentException: 25.0.3` parsing that version - it cannot compile
  `build.gradle.kts` at all, so even `:core:test` fails. Installed to
  `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot`. Not written into
  `gradle.properties` because that file is committed and the path is not
  portable.
- `local.properties` needs forward slashes. Backslashes are eaten by Java's
  properties parser and surface as `IOException: The filename, directory name,
  or volume label syntax is incorrect`, which names nothing useful.
- **Wireless debugging works and is worth using.** `adb pair`, then mDNS
  auto-connects. A USB cable tugs at the phone during capture, and hand tremor
  is the signal being measured.
- **Synthetic input is blocked on this handset.** `adb shell input tap` returns
  cleanly and does nothing, so on-device UI steps need a human finger. Reading
  the screen (`screencap` + `pull`) and pulling files both work fine.

### Two build fixes

`:app` compiled on the first real attempt - no Kotlin errors - contrary to the
handover's warning. Both failures were environmental:

- `settings.gradle.kts` filtered the Google repo to `com.android.*`,
  `androidx.*` and `com.google.android.*`, but AGP 8.7.3 needs
  `com.google.testing.platform:core-proto` on its own plugin classpath.
- The probe could not register the gyroscope at all without
  `HIGH_SAMPLING_RATE_SENSORS`: Android 12 gates rates above 200 Hz behind it,
  and an undeclared request throws rather than being capped.

### Phase 0: `HARDWARE_FAST`

Report committed as `docs/probe-SM-A076B.json`. Details in
`docs/DEVICE-A07.md`; the short version is that the highest-risk assumption
held and several hedges turned out unnecessary.

Four things measurement changed, two in each direction:

| Was assumed | Measured |
|---|---|
| Gyro possibly fused at ~50 Hz | Real Bosch BMI3xx at **403 Hz** |
| Clock offset needs calibration | `SHARED_REALTIME` - solves for zero |
| Camera2 `LIMITED` | **`LEVEL_3`** - RAW and manual sensor available |
| Bias would dominate alignment | **0.00013 rad/s** - 0.2 px over a burst |

The bias result is why `meanMag` was *not* what got reported. Mean magnitude is
non-negative, so it folds noise in and returns roughly `sqrt(b^2 + 3*sigma^2)`:
at this noise floor it would have read ~0.003 rad/s for a perfectly unbiased
sensor and sent Phase 1 chasing a calibration ghost. The mean *vector* is the
quantity that integrates into drift, and recovering it needed per-axis sums the
probe had been discarding.

### Phase 1: burst-to-disk

The archive format lives in `:core` because its entire purpose is to be read
off the phone. `:app` holds only the part that needs hardware - getting bytes
out of an `Image`.

Five bursts captured, two pulled. Every frame byte-exact against
`frameByteCount`, and both resolutions decode to coherent images, so the
`YUV_420_888` stride handling is confirmed against this HAL rather than merely
careful.

Bugs the real bursts exposed, both since fixed:

- **Shutter time came from `System.nanoTime()`** - `CLOCK_MONOTONIC` - while
  camera timestamps are `REALTIME`/`CLOCK_BOOTTIME`. The phone had been asleep
  37 hours, putting the clocks **134,000 seconds** apart, so every shutter time
  landed outside the ring buffer and the burst silently anchored on its oldest
  frames. Invisible only because burst size equalled ring capacity.
- The gyro-coverage warning was computed after the manifest was written, so it
  reached the UI and never the archive.

### What the bursts revealed about the hardware

- **Rolling-shutter skew is delivered at 27.4 ms**, though the probe reports
  otherwise. The probe asks `availableCaptureResultKeys`, which is what the HAL
  *declares*; this one under-declares. 27 ms is ~17 px of intra-frame rotation
  at a typical tremor rate, so per-row correction is worth doing and can use a
  measured value.
- **Exposure expands to fill the frame period** - 50 ms at 20 fps, 30 ms at
  30 fps, against a design assuming 20 ms. Addressed by capping exposure and
  taking the shortfall as gain, with AE left to do the metering.

### The exposure cap, confirmed on hardware

Three bursts at 12.5 MP with the cap at 20 ms. Exposure came back at exactly
20.0 ms on every frame, ISO at 1047 - AE's own 419 scaled by exactly the 2.5x
the cap demanded - and mean luma rose from 4 to 80 at *less than half* the
exposure. The 20 fps pin survived `CONTROL_AE_MODE_OFF`, so frame spacing is
unchanged at 50.1 ms.

ISO is constant across all eight frames, which is the property the "apply once"
design was protecting: a lock re-evaluated per frame would let brightness drift
mid-burst, and the weighted merge cannot absorb that.

At 1:1 the frames are sharp and noisy. That is the right side of the trade -
noise averages down across a stack, blur does not.

### The offline reader, and what replaying a real burst showed

`BurstReader` closes the loop the archive was built for: a burst captured on
the phone now replays on a JVM in milliseconds.

The test fixture is a genuine burst - manifest, frame timing, all 190 gyro
samples - minus the eight 17.9 MB frame files, because everything up to and
including the alignment plan is decided by timestamps and angular velocity
rather than pixels. So the replay runs anywhere, and
`-Dstablestill.burstDir=...` points the pixel checks at a full burst when one
is to hand. That property has to be forwarded explicitly in `core/build.gradle.kts`:
a `-D` on the command line reaches the Gradle daemon and stops there, which had
the pixel tests skipping while looking like they passed.

Replaying the capped burst through `BurstAligner`:

| Frame | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 |
|---|---|---|---|---|---|---|---|---|
| Shift (px) | 5.6 | **0.0** | 6.0 | 8.5 | 17.1 | 24.0 | 31.6 | 43.1 |
| Rotation (mrad) | 1.38 | **0.00** | 1.59 | 2.22 | 4.01 | 5.68 | 7.43 | 10.37 |

All eight frames usable, anchor at index 1, rotation growing monotonically away
from it - which is what a correct integration of real hand motion looks like.

The alignment test carries a lower bound as well as an upper one, deliberately.
A track that integrated to *nothing* - what a units slip or an over-eager bias
subtraction produces - leaves every shift at zero and sails through any test
that only checks shifts are small.

**The 12% crop is roughly fifteen times what that burst needed.** Worst
rotation 10.4 mrad against a budget of 118 mrad, so 9% of the margin was used.
12% per side discards 38% of the pixel count, which is a great deal of
resolution to spend on headroom nobody used. One steady indoor burst is not
grounds for changing the default, but the reader now makes it cheap to ask
across many.

### Left open

- Everything so far is **indoors at night**. The capped burst is usable, but
  the 20-vs-30 fps comparison still wants daylight at both resolutions.
- The crop margin is unexamined against a **shaky** hand; every burst so far
  was steady.
- **Phase 3 has still never executed.** The GPU warp and merge are the last
  untested stage, and now the cheapest to test: there is a known-good burst and
  a known-good alignment plan to feed them.
- `recommendedStackDepth()` returns 12 for every size this camera offers; the
  clamp binds, never the RAM budget. 214 MB of native buffers on a 3.4 GB phone
  is ungoverned.
- **Document capture was raised as a product direction** and is recorded in the
  handover. It would promote optical refinement to a requirement, because a
  gyroscope cannot see the translation that dominates at page distance.
