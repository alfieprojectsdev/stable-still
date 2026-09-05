# Handover

Written at the end of the cloud session that created this project, for the
local session that picks it up. If you arrived by `claude --teleport` you
already have the full conversation and can skim this; if you started cold, this
is the context you are missing.

---

## Status in one table

| Phase | What | State |
|---|---|---|
| 0 | Device probe | Written. **Never run.** |
| 1 | Ring-buffer capture + gyro recording | Written. **Never run.** |
| 2 | Motion maths | **38 unit tests passing.** |
| 3 | GPU warp + merge | Written. **Never run.** |
| 4 | Sync calibration + optical refinement | Designed only. Not written. |
| 5 | Product UX | Not started. |

**The `:app` module has never been compiled by anything.** It was authored in a
container with no Android SDK, so it has not even been syntax-checked by a
Kotlin compiler that understands Android types. Expect errors on first sync.
That is anticipated, not a regression - do not treat a broken build as evidence
that something has gone wrong since.

`:core` is different: it compiles and its tests pass.

---

## Do this first

**Run the Phase 0 probe on the A07 and read the verdict.** Nothing else is
worth doing before that.

The probe answers whether the phone's gyroscope is real hardware or a low-rate
software fusion. Budget Samsung A-series handsets have a history of listing a
"gyroscope" that is really an accelerometer/magnetometer blend at ~50 Hz -
useless for aligning a 20 ms exposure. The published listings for this device
claim a gyro, but they also disagree with each other about the chipset, so they
are not evidence.

The verdict decides the project's shape:

- **`HARDWARE_FAST` / `HARDWARE_ADEQUATE`** - proceed as designed.
- **`MARGINAL`** - the gyro seeds an optical refiner rather than driving the warp.
- **`UNUSABLE` / `ABSENT`** - the project becomes a multi-frame *optical*
  stacker, roughly what Google's HDR+ does. Still a real and useful app. None
  of the capture, merge, or crop work is wasted; only the source of the
  homography changes.

Getting there means: fix whatever `:app:assembleDebug` throws, install to the
phone, rest it on a table, tap **Run probe**, export the JSON.

---

## After the probe

In this order, because each step makes the next one debuggable:

1. **Save a burst to disk** - frames plus the gyro trace as CSV - and copy it
   to the laptop. This is the highest-value hour in the whole project: it turns
   every later alignment bug into something reproducible offline instead of a
   thing that only happens on a phone in your hand.
2. **Stack that saved burst in a JVM test** before trusting the GPU path.
3. **Then** Phase 4 (sync calibration, rig handedness, optical refinement).

---

## Decisions already made

Do not relitigate these without a reason; the reasoning is recorded so it can
be argued with, not repeated.

**Camera2, not CameraX.** CameraX hides per-frame `SENSOR_TIMESTAMP`, exposure
time and rolling-shutter skew. Those three values are what the entire pipeline
is built on.

**Fragment shaders, not compute, not RenderScript.** RenderScript was
deprecated in Android 12. Compute would work, but the merge is a pure gather -
one output pixel reads one location per input - which fragment shaders express
natively while staying on the GLES 3.0 baseline.

**The anchor is chosen, not assumed.** Not the frame at the shutter press, but
the steadiest frame in the window. The anchor's own motion blur is baked into
the output and cannot be removed by any amount of alignment.

**The merge rejects, it does not blindly average.** Each pixel is weighted by
its agreement with the anchor, so static regions get noise reduction while
moving subjects fall back to the anchor alone instead of ghosting.

**Stack depth comes from measured RAM.** A 50 MP YUV_420_888 frame is 75 MB.
The ten-frame buffer this design started from would be 750 MB on a 4 GB phone.
`recommendedStackDepth()` budgets a quarter of RAM and clamps to 3-12.

**`main` began as an empty root commit.** GitHub refuses a pull request between
branches with no common ancestor, so that root was merged into the feature
branch rather than rebasing it. Nothing to fix; noted so the odd-looking early
history makes sense.

---

## Environment as of handover

| | |
|---|---|
| Local clone | `C:\Users\admin\repos\stable-still` |
| Android SDK | `C:\Users\admin\AppData\Local\Android\Sdk` |
| Android Studio | Quail 4 |
| Needed platform | **API 35** (Android 15, "VanillaIceCream") |
| Also installed | API 37 - harmless, but see below |
| Device | Galaxy A07 5G, Android 15 |

`compileSdk`/`targetSdk` are 35 because AGP 8.7.3 caps there. To move to API 37,
bump AGP in `gradle/libs.versions.toml` **first**, then `compileSdk`. Doing it
the other way round produces an unsupported-compileSdk complaint.

No CI is configured. `./gradlew :core:test` is the only automated check.

---

## Two bugs the tests already caught

Recorded because both are the kind that recur.

- `Quaternion.angle()` used `acos`, which returns **exactly zero** at the
  milliradian scale hand tremor occupies - `w` there differs from 1.0 by less
  than a double represents. Now `2·atan2(|v|, |w|)`.
- A test meant to prove that clock-sync error degrades alignment could never
  have failed, because it used a constant angular rate, under which a common
  offset cancels exactly. Rewritten against oscillatory motion.

---

## Project vocabulary

| Term | Meaning here |
|---|---|
| **Anchor** | The frame others are warped onto; the steadiest in the burst |
| **Crop budget** | Margin reserved so warped frames have somewhere to slide; frames exceeding it are dropped, not stretched |
| **Rig alignment** | Fixed rotation between the gyro's device frame and the camera's optical frame |
| **Sync calibration** | The unknown offset between camera and sensor clocks |
| **Sampling matrix** | Per-frame `mat3` mapping an *output* pixel to the source pixel to sample - runs destination-to-source |
| **Grade** | The probe's verdict on gyroscope usability |

---

## Things not to do

- Do not test alignment against constant-rate rotation. It hides clock bugs.
- Do not skip Phase 0 because the build is finally working. The probe is the
  cheapest way to discover the design does not fit the hardware.
- Do not install emulator images. No gyroscope, no real rolling shutter; the
  probe would report a fiction.
- Do not add Android imports to `:core`.
- Do not raise `compileSdk` above what the pinned AGP supports.
