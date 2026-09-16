# Handover

The current state and the next action. `docs/SESSION-LOG.md` records how it got
here; `docs/DEVICE-A07.md` is the authority on what the hardware does.

Updated 16 September 2026 (fourth session).

Phases 0 to 3 all run: probe, capture, archive, JVM replay, GPU merge. The
merge's rejection threshold is measured at both ends. **Phase 4's maths now
exists in `:core` and has never met the hardware** - optical refinement and
handedness calibration are written and tested against bursts whose answers are
known by construction, and neither has seen a real frame.

The bigger change is that most of what is left no longer needs a phone. The
merge runs on a JVM, so threshold sweeps, crop audits and alignment residuals
are laptop work against archived bursts.

---

## Status in one table

| Phase | What | State |
|---|---|---|
| 0 | Device probe | **Run. Verdict `HARDWARE_FAST`.** |
| 1 | Ring-buffer capture + gyro recording | **Runs. Bursts saved and replayed.** |
| 2 | Motion maths | **98 unit tests**, including a real-burst replay. |
| 3 | GPU warp + merge | **Runs on device; also runs on the JVM.** Threshold derived from noise, ceiling measured against motion. |
| 4 | Sync calibration + optical refinement | Sync and skew deleted by measurement. Refinement and handedness calibration **built and unit-tested, not yet run on hardware or wired into `:app`**. |
| 5 | Product UX | Not started. |

`:app` now builds and runs on the A07. The warning that it had never been
compiled no longer applies - it compiled on the first real attempt, with no
Kotlin errors.

### What `:core` can do without a phone

Everything here runs on a laptop, against an archived burst or none at all.

| Type | Answers |
|---|---|
| `ReferenceMerge` | The shaders' warp, weight and resolve, on the CPU. Matches `StackRenderer` deliberately, precision aside. |
| `ThresholdSweep` | What each rejection threshold buys in noise and costs in ghosting, separated by a motion mask rather than by eye. |
| `OpticalRefinement` | The translation the gyro cannot see, and the residual left before and after. |
| `RigCalibration` | Which handedness the pixels prefer, or a refusal when the burst cannot tell. |
| `BurstAudit` | Crop margin needed, anchor choice, motion blur - from the CSVs alone, no frame files. |

**Build requirement:** `JAVA_HOME` must point at **Temurin 21**, not Android
Studio's bundled JBR 25. Gradle 8.14.3 cannot compile the build scripts on
Java 25 and fails with a bare `IllegalArgumentException: 25.0.3`.

---

## Do this first

**Pull the twenty-two bursts to the laptop and run the audit.** It needs no
phone, no SDK and no pixels - only the CSVs - and it settles two open questions
in one command:

```
./gradlew :core:test --tests '*BurstAuditTest*' \
    -Dstablestill.burstRoot=/path/to/bursts
```

That prints one line per burst - corner shift, rotation against budget, the
smallest crop margin that would have kept every frame, the anchor it chose and
the blur that choice avoided - then a summary naming the hungriest burst. The
crop question below turns on the maximum across all twenty-two, and one burst
cannot supply it. Copying only `manifest.txt`, `frames.csv` and `gyro.csv` is
a few hundred kilobytes and enough; the 3.3 GB of frame files can wait.

With the frame files, the second thing is a threshold sweep with no handset in
it, via `ThresholdSweep` - and a first look at what `OpticalRefinement` leaves
behind on a real burst, which is the number that decides how much of Phase 4c
is worth keeping.

### Then, on the phone

**Two bursts, and the second one is new.**

**A tilt burst, to settle handedness.** Pitch and yaw the phone through the
burst - *do not roll it*. Rotations about the optical axis commute with the
sensor-orientation rotation that handedness flips, so a rolling burst gives both
signs identical homographies and decides nothing. `RigCalibration` will refuse
to answer in that case rather than guess, and reports how far apart the two
hypotheses placed a crop corner so the refusal is legible.

**A lamp-lit burst with a moving subject - dimmer light, not a darker room.**

This is the one burst that would let `MAX_SIGMA` rise from 0.15, which is worth
about 6% of the noise reduction at high ISO. What it needs:

- **Ambient dim enough to force ISO 1000+**, but the subject exposed near
  mid-histogram - **mean luma 80-100**, not a frame that looks black on screen.
- **A hand crossing frame** against a plain background, ~30 cm, one smooth
  pass, plus **a static control** of the same scene.

Turning the lights off does not work and has already been tried. Darkness
raises gain but lowers signal faster, so absolute noise *falls*: two bursts at
identical ISO 1047 measured 0.0250 at mean luma 81 and 0.0143 at mean luma 19.
Note also that **12.5 MP caps at ISO 1047**; the 8 MP mode reaches ~3000.

Two errands remain outstanding and neither blocks:

- **The 20-vs-30 fps trade.** The 12.5 MP and 8 MP daylight pairs it needs
  exist as of 9 September and are unexamined. The trade is mostly decided
  analytically below; the pairs would confirm it rather than settle it.
- **A deliberately shaky burst**, for the crop question below - though two dim
  bursts already merged only 6 of 8 frames at ~350 px of corner shift, which is
  most of the answer.

And one edit is waiting for a machine that can build `:app`: **wire
`OpticalRefinement` into `BurstReplayer`**, between the plan and the render.
It was left undone deliberately - there was no Android SDK in the session that
wrote it, so `settings.gradle.kts` drops `:app` and the code could not have been
compiled, let alone run.

On the phone: **Capture** tab, depth **8**, max exposure **20 ms**. Watch that
auto-exposure does not hold 20 ms in bright light - three rooftop bursts came
back 52-73% clipped that way. `adb shell input tap` does not work on this
handset, so capture needs a finger. Replaying afterwards does not:

```
adb shell am start -n dev.alfieprojects.stablestill/.ui.MainActivity \
    --ez autoReplay true --es burst burst-20260909-085849 --es rejectSigma 0.40
```

Omit `rejectSigma` to use the derived one, and `burst` to take the newest.
Results land under the `AutoReplay` logcat tag, and the output JPEG is named
after the threshold that produced it. **Leave a couple of seconds between
replays**: the next `am start` otherwise races the previous activity's
`finish()` and the run dies with `JobCancellationException`, which looks like a
pipeline crash and is not one.

**From a worktree, `:app` needs `ANDROID_HOME` in the environment.** There is
no `local.properties` there, so `settings.gradle.kts` drops `:app` and Gradle
says the task does not exist - which reads as a typo. Worse, an install can
look like it succeeded while the old APK stays on the phone; new intent extras
being silently ignored is the symptom.

---

## The rejectSigma threshold, as of 9 September

`MAX_SIGMA` is **0.15**, down from a guessed 0.60. `MIN_SIGMA` stays at 0.06
and `SIGMA_PER_NOISE` at 6.5, both tested rather than assumed. Twenty-two
bursts across ISO 25 to 3055, indoor, rooftop and dim; numbers in
`docs/SESSION-LOG.md`. What is settled:

- **The benefit scales with noise.** Residual noise stops improving at 9-10x
  the frame's measured noise, consistently across a 3.6x range. That is what
  justifies deriving the threshold from noise at all, and why 6.5 is a sound
  multiplier - it sits below the knee everywhere.
- **The cost is set by alignment residual, not gain.** 28 px of corner shift
  ghosts near 0.40; 177 px near 0.25; 253 px with a hand crossing frame at
  **0.15**. A moving subject binds, as it should - it is the only case where
  the disagreement is signal rather than error.
- **A static scene ghosts too**, near 0.40, where residual misalignment doubles
  high-contrast edges. Nothing in frame moved. That cost the 8 September
  session its answer: it reasoned a still room cannot calibrate this, and the
  metric in use simply could not see it.

**This raises the stakes on optical refinement.** If the ceiling is a
consequence of alignment error, refinement does not merely sharpen the output -
it raises the ceiling, and buys back the noise reduction the ceiling forgoes.

What is *not* settled is whether the ghosting onset scales with noise the way
the knee does. The evidence leans yes - 3.6x the noise bought roughly 2.7x the
onset at matched shift - but subject texture confounds it, since fine texture
washes out long before bold type doubles. See "Do this first".

---

## The crop margin: not far too generous after all

The suspicion was that 12% per side is lavish, since it costs 42% of the pixel
count. It was formed from the steadiest burst in the collection, and the
audit now says the opposite.

That burst does need almost nothing - `minimumSafeMargin` puts it at **1.13%
per side**, with 11.7% of the slack used and rotation at 10.4 mrad against a
118 mrad budget. But the shifts already recorded tell a different story at the
other end. The 9 September motion burst moved a corner **253 px** and the two
dim bursts about **350 px**. Against 4080 px of width that is 6.2% and 8.6%;
against 3060 px of height, 8.3% and **11.4%**. Which axis the excursion runs
along decides whether the worst burst on record sits comfortably inside 12% or
almost exactly on it.

So: **roughly right, possibly trimmable to 10%.** That would buy back 10.8% of
the pixel count - `(1-2m)^2` is 0.64 against 0.578 - and still clear a 350 px
excursion by 17% *on the x axis only*. On the y axis it would not clear it at
all.

Running the audit over all twenty-two bursts is what settles this, and it needs
no pixels. See "Do this first". Do not trim the margin on the strength of the
steady burst; that is the mistake this question was already making.

## The anchor is a real choice, and its value is not where it looks

On the fixture burst the selector picks **frame 1, not frame 0**, and is 2.07x
steadier than the average frame - so it is not defaulting. But frames 0 and 1
are tied to within 0.15%, which is noise, while frames 6 and 7 carry 3.6 and
5.5 px of blur against the anchor's 1.3.

**The choice between the top two is a coin toss; the value is entirely in
avoiding the worst frames.** Worth knowing before anyone spends effort on a
sharpness-based tie-break: at ISO 1047 a Laplacian score is inflated by noise,
so it would be breaking a tie that does not matter, using a measure that cannot
be trusted at that gain. One burst, so confirm it across the collection - the
audit reports it per burst.

## The 20-vs-30 fps trade is decided by the ISO ceiling

The daylight pairs are still unexamined, but most of the trade is already
settled by things measured elsewhere. 12.5 MP runs at 20 fps and 8 MP at 30;
eight frames span 351 ms or 233 ms.

- **Noise is neutral.** The 20 ms exposure cap sits below both frame intervals
  (50.1 and 33.4 ms), so it binds at neither rate: same exposure, same gain,
  same per-frame noise, same stacking benefit at the same frame count.
- **The shorter span buys alignment** - a third less time for tremor - and the
  crop finding above says that is worth very little in good light.
- **It costs 36% of the pixels.**
- **The ISO ceiling decides it.** 12.5 MP caps at ISO 1047; 8 MP reaches
  2425-3055. Past the cap, full resolution cannot expose the scene at all, and
  stacking does not rescue an underexposed frame.

**Stay at 12.5 MP / 20 fps while metered ISO is under about 1000; drop to
8 MP / 30 fps when the scene asks for more gain.** Below the cap the resolution
is free; above it, the resolution is imaginary. The one case that could
overturn this is document capture, where 8-point type wants every pixel and the
parallax residual wants the shorter span - and that needs the page burst.

---

## What Phase 0 settled, in one paragraph

The gyro is a real Bosch BMI3xx at 403 Hz with sensor-side timestamps and a
negligible zero-rate offset; the camera is `LEVEL_3` with RAW and manual
sensor; camera and sensor clocks are shared, so sync calibration solves for
zero; rolling-shutter skew is delivered at 27.4 ms despite the probe declaring
otherwise. Phase 4 loses sync calibration and skew estimation, keeping only rig
handedness and optical refinement. The full detail, including four things
measurement made *worse*, is in `docs/DEVICE-A07.md`.

The original decision tree below is kept because it records why the probe was
built, and because it is the fallback if a second device grades differently:

- **`HARDWARE_FAST` / `HARDWARE_ADEQUATE`** - proceed as designed.
- **`MARGINAL`** - the gyro seeds an optical refiner rather than driving the warp.
- **`UNUSABLE` / `ABSENT`** - the project becomes a multi-frame *optical*
  stacker, roughly what Google's HDR+ does. Still a real and useful app. None
  of the capture, merge, or crop work is wasted; only the source of the
  homography changes.

---

## Product direction under consideration

**Document capture for people with unsteady hands** - photographing specific
pages in a library, legibly, for citation and annotation. Raised 5 September
2026; not decided, recorded because it changes a technical priority.

Why it suits this design better than general photography:

- The subject is **flat and static**, so almost every frame contributes almost
  everywhere. Not quite "no ghosting case exists", which is what this said
  before 9 September: a static scene does ghost once the threshold passes about
  0.40, because residual misalignment doubles high-contrast edges and rejection
  was the only thing hiding it. On 8-point type that is the failure mode to
  watch, and it is the same argument for optical refinement made below.
- Libraries are dim and flash is usually banned or useless on glossy paper,
  which forces high ISO - and noise is what stacking removes.
- Text is the ideal thing to sharpen, and legibility is pass/fail rather than
  aesthetic, so results can be judged honestly.
- Nothing in that market targets tremor. Adobe Scan, Google Lens and Microsoft
  Lens are all essentially single-frame.

**The catch, and it is architectural.** A gyroscope sees rotation only.
`BurstAligner` assumes translation parallax is sub-pixel, which holds beyond a
couple of metres and does not hold for a page at 30 cm: at f ~ 3110, one
millimetre of hand *translation* shifts the image by about 10 px, and tremor
over a 350 ms burst is comfortably several millimetres. Gyro-only alignment
would leave 10-30 px of residual - unnoticeable on a landscape, fatal on
8-point type.

So this use case promotes **optical refinement from a Phase 4 polish step to a
requirement**. The consolation is that a page is the easiest possible case for
it: planar, richly textured, no moving elements, no internal parallax. A planar
scene's true inter-frame motion *is* a homography, so refinement is
well-conditioned and the gyro supplies a good initial estimate.

**That refiner now exists** (`OpticalRefinement`), and the 10-30 px predicted
here is exactly the range it was tested across - recovered to within 0.2 px,
with the pyramid depth chosen because that is what reaches it. It estimates a
translation only, which is the term a gyroscope cannot see and the one parallax
produces; if a real page burst shows residual a translation cannot absorb, that
is the signal to grow it, and the residual it reports is how you would know.

Two smaller consequences: the crop budget fights tight page framing, since
people frame edge-to-edge; and a static subject permits **more frames over a
longer window** than a moving scene would, because nothing can ghost.

Next step if pursued: capture a burst of a book page in library light. The
reader will report how much residual the gyro leaves, which is the number that
decides whether optical refinement is the next thing built.

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

**Stack depth comes from measured RAM** - and on this device that turned out to
decide nothing. The reasoning assumed a 75 MB 50 MP frame, but the largest
`YUV_420_888` output is 12.5 MP at 17.9 MB, so `recommendedStackDepth()`
returns its clamp of 12 for every size this camera offers. The budget never
binds. What that leaves ungoverned is 214 MB of native ImageReader buffers on a
3.4 GB phone, which is the risk the heuristic was written to address and does
not. Decide the default from a measured burst instead.

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
| Android Studio | Quail 4 - but its bundled JBR 25 cannot run this Gradle |
| **JDK for Gradle** | **Temurin 21** at `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot` |
| Installed platform | API 35 only; build-tools 34 and 36 |
| Device | Galaxy A07 5G, **Android 16 / API 36** - the listings saying 15 are wrong |
| Debugging | `adb tcpip 5555`, over Tailscale. Synthetic input is blocked. |

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
