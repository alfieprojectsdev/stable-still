# Roadmap

Each phase has an **exit criterion** - something observable that says "this
works, move on". The point is to avoid the classic failure mode of building the
whole pipeline and only then discovering the gyroscope was never usable.

Phases 0-3 are implemented in this repository. Phases 4-5 are designed but not
written.

---

## Phase 0 - Device probe ✅ implemented

**Goal:** find out what the handset can actually do, before committing to a
design that assumes things it cannot deliver.

- `DeviceProbe` reads Camera2 hardware level, timestamp source, sensor
  orientation, physical size and focal length, OIS support, RAW and manual
  sensor capability, whether rolling-shutter skew is reported, and the available
  YUV stream sizes with sustainable frame rates.
- It *measures* the gyroscope rather than trusting `minDelay`: delivered rate,
  interval jitter, and noise floor at rest, graded into a usability verdict.
- Report is exportable as JSON.

**Exit criterion:** you have a JSON report from the A07 and know whether the
gyro path is viable.

**This is the next thing to run.** Everything downstream is contingent on it.

---

## Phase 1 - Capture and synchronisation ✅ implemented

**Goal:** a continuously running frame + motion history that can be sliced.

- `CaptureEngine`: Camera2 repeating request into an `ImageReader`, pairing each
  `Image` with its `TotalCaptureResult` by `SENSOR_TIMESTAMP`.
- `FrameRingBuffer`: fixed-capacity, evicts and closes the oldest.
- `GyroRecorder`: 4-second rolling window at `SENSOR_DELAY_FASTEST`, plus gravity.

**Exit criterion:** shutter press yields N frames whose timestamps are bracketed
by gyro samples on both sides, with no ImageReader stall after 100 captures.

**Not yet verified on hardware.**

---

## Phase 2 - Motion maths ✅ implemented, 38 tests passing

**Goal:** turn frames + gyro into "where each frame goes", testably.

- Quaternion integration with a numerically stable small-angle path.
- `BurstAligner` producing one `mat3` per frame.
- `AnchorSelector` picking the steadiest frame.
- Frames beyond the crop budget rejected rather than stretched.

**Exit criterion:** ✅ met. `gradle :core:test` passes, including a check that a
pure yaw of θ displaces the sampled point by exactly `f·tan θ`.

---

## Phase 3 - GPU warp and merge ✅ implemented

**Goal:** produce an actual JPEG.

- `EglCore`: offscreen GLES 3.0 context.
- `StackRenderer`: YUV plane upload, warp, agreement-weighted accumulation into
  RGBA16F, resolve, readback.

**Exit criterion:** a static tripod-free scene stacked from 8 frames is visibly
less noisy than one frame, with no edge artefacts and no ghosting on a scene
with a moving element.

**Not yet verified on hardware.**

---

## Phase 4 - Calibration and optical refinement 🟨 built in `:core`, never run on hardware

This is where "works in principle" becomes "works on this phone". What the
probe left standing is two pieces, not three - it measured the camera and
sensor clocks as shared, so **4a solves for zero and is deleted**.

**4b. Rig handedness resolution.** ✅ `RigCalibration.settleHandedness` warps
the burst both ways and keeps whichever leaves the frames agreeing with the
anchor. Tested against bursts rendered *through* a stated handedness, so there
is a right answer to find rather than a plausible one to accept.

The burst has to be the right burst, and this is the part to know before
capturing one: handedness enters only through a rotation about the optical
axis, and rotations about that axis commute with it, so a burst that only
**rolls** gives both signs identical homographies. Pitch and yaw separate them.
The verdict refuses to choose when the two hypotheses land within a pixel of
each other, rather than returning a coin toss that would then travel in every
subsequent manifest.

**Run on 17 September against four real bursts: -1, unanimously**, by margins
of 39% to 130% with the hypotheses 55 to 459 px apart. The code had shipped
with +1. `RigAlignment.SETTLED_HANDEDNESS` carries the measurement, and
archives captured before that date align through `BurstManifest.replayRig`.

**4c. Optical refinement.** ✅ `OpticalRefinement` is Lucas-Kanade on a luma
pyramid, estimating a residual **translation** per frame - the term a gyroscope
structurally cannot see, and the one parallax at close range produces. Not
normalised cross-correlation as sketched here: a gradient method reaches
sub-pixel accuracy directly instead of interpolating a correlation peak, and the
exposure cap holds gain constant across the burst so NCC's normalisation buys
nothing.

It does the jobs listed here and one more that was not anticipated: because the
rejection threshold's ceiling is set by alignment residual rather than by gain,
refinement **raises the ceiling** and buys back stacking the ceiling forgoes.
Measured on a synthetic burst, misalignment of a few pixels costs most of the
stack at sigma 0.15 and refinement returns it.

**Still open:** neither has met a real frame, and neither is wired into `:app`.
The refiner reports the residual it leaves, which is the number that decides
whether a translation is enough or the model has to grow.

**Exit criterion:** residual misalignment under 1 px on a static scene *of a
real burst*, and the no-gyro fallback produces a usable stack.

---

## Phase 5 - Product ⬜ not started

- Camera preview with a live steadiness indicator (the anchor score, shown as
  "hold still" feedback before the press rather than a verdict after it).
- Presets: *Sharp* (short window, few frames) vs *Low light* (long window, more
  frames, wider reject sigma).
- Software long exposure: keep the alignment, stop rejecting motion, and let
  moving subjects streak deliberately - the tripod-free light-trail shot.
- Per-frame exposure normalisation for `LIMITED` devices with no manual control.
- MediaStore integration and EXIF, so shots land in the gallery.
- Thermal backoff: drop stack depth when the SoC throttles.

---

## Suggested working order

Given limited evening hours, this ordering keeps every session ending with
something that ran:

1. Open in Android Studio, build, **run Phase 0 on the A07**. Read the verdict.
2. If the gyro grades `HARDWARE_*`: wire a capture screen, save a burst to disk
   with its gyro trace as CSV, and inspect it on a laptop. Cheap, and it makes
   every later bug debuggable offline.
3. Stack that saved burst offline first, in a JVM test, before trusting the GPU
   path on the phone.
4. Then, and only then, Phase 4.

If the gyro grades `UNUSABLE` or `ABSENT`, skip to Phase 4c: the project becomes
a multi-frame optical stacker, which is still a real and useful app - it is
roughly what Google's HDR+ does - and none of the capture, merge, or crop work is
wasted.
