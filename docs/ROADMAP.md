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

## Phase 4 - Calibration and optical refinement ⬜ designed, not implemented

This is where "works in principle" becomes "works on this phone". Three pieces:

**4a. Clock-sync auto-calibration.** `SyncCalibration.search` is written and
tested against synthetic cost functions; what is missing is the real cost
function - warp the burst at a candidate offset and measure residual
misalignment. Run once, persist the answer.

**4b. Rig handedness resolution.** `RigAlignment.handedness` encodes the sign of
the rotation between gyro and camera axes. Rather than deriving it and hoping,
try both and keep whichever reduces residual motion. Same harness as 4a.

**4c. Optical refinement.** After the gyro warp, estimate a residual translation
per frame by coarse-to-fine normalised cross-correlation on downsampled luma.
This does three jobs at once: absorbs leftover sync error, absorbs the parallax
the pure-rotation model cannot represent, and **becomes the primary alignment
mechanism if the probe grades the gyroscope unusable**.

**Exit criterion:** residual misalignment under 1 px on a static scene, and the
no-gyro fallback produces a usable stack.

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
