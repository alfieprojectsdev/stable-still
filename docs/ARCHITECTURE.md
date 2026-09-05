# Architecture

## The one-sentence version

Keep recent frames and recent gyro samples in memory; when the shutter is
pressed, integrate the gyro over the burst window, convert each frame's rotation
relative to a chosen anchor into a homography, warp every frame into the anchor's
frame of reference on the GPU, and average them with per-pixel outlier rejection.

## Data flow

```
 SensorManager ──► GyroRecorder ──────┐
 (TYPE_GYROSCOPE,                     │  rolling 4 s window
  SENSOR_DELAY_FASTEST)               ▼
                              MotionTrack.integrate()   ← quaternion orientation track
                                      │
 Camera2 ──► ImageReader ──► FrameRingBuffer            │
 (YUV_420_888,              (last N frames +            │
  repeating request)         CaptureResult timing)      │
        │                             │                 │
        └──── SENSOR_TIMESTAMP ───────┴─────────────────┤
                                                        ▼
                                          BurstAligner.plan()
                                     anchor pick + per-frame homography
                                                        │
                                                        ▼
                                          StackRenderer (GLES 3.0)
                                    warp → weight → accumulate → resolve
                                                        │
                                                        ▼
                                                    JPEG on disk
```

## The maths

### Orientation from angular velocity

The gyroscope reports angular velocity, not orientation, so we integrate. Each
interval's rotation increment is the exponential map of `ω·dt`, composed onto the
running orientation on the right (because ω is expressed in the body frame):

```
q(t + dt) = q(t) ⊗ exp(½ · ω_mid · dt)
```

`ω_mid` is the average of the bracketing samples - trapezoidal rather than a
zero-order hold, which halves the error for oscillatory motion at no cost. Hand
tremor is oscillatory at a few Hz, so this is the case that matters.

Absolute orientation is meaningless (it is relative to whenever integration
started). Only differences are ever used:

```
q_{a→b} = q(b)⁻¹ ⊗ q(a)
```

### Rotation to pixels

For a camera that only *rotates*, the mapping between two views is an exact
homography with no depth term:

```
H = K · R · K⁻¹
```

`K` is the pinhole intrinsic matrix built from the reported focal length and
physical sensor size. `R` is the frame-to-anchor rotation, re-expressed from the
device frame into the camera frame by the fixed rig rotation (`RigAlignment`).

The direction matters: the shader runs *backwards*, from each output pixel to the
source pixel it should sample, so `BurstAligner` produces `K · R_{anchor→frame} ·
K⁻¹`, pre-multiplied by the crop translation. One `mat3` per frame, straight into
a uniform.

**Where the model breaks.** Hand tremor is mostly rotation, but not purely: there
is a small translation too, and translation causes parallax that a homography
cannot represent. The error scales with `translation / subject distance`, so it
is invisible for landscapes and real for close-ups. That is what the Phase 4
optical refinement pass is for.

### Choosing the anchor

Not the frame at the shutter press - the *steadiest* frame in the window, scored
by mean angular speed during its own exposure. Its motion blur is baked in and
cannot be undone by alignment, so it is the one thing worth optimising for
directly.

### Merging

A plain average of aligned frames removes noise beautifully and turns anything
that moved into a ghost. So each pixel is weighted by how much it agrees with the
anchor:

```
w = exp(-‖rgb - rgb_anchor‖² / σ²)
```

Accumulation is additive into an RGBA16F target, storing `rgb·w` in colour and
`w` in alpha; the resolve pass divides. Where every frame disagreed - a moving
subject - the weights collapse and the result falls back to the anchor alone,
which is exactly right: no ghost, just a single-frame region.

### Rolling shutter

`SENSOR_TIMESTAMP` is the start of exposure of the *first row*. Rows below it are
exposed progressively later, by up to `SENSOR_ROLLING_SHUTTER_SKEW`. `FrameMeta`
models this per row. The current shader applies one homography per frame (using
the centre row's time); per-row correction is a Phase 4 refinement and only pays
off with a genuinely fast gyro.

## Threading

| Thread | Owns |
|---|---|
| `gyro-recorder` HandlerThread | Sensor callbacks, sample ring buffer |
| `capture-engine` HandlerThread | Camera2 callbacks, image/result pairing |
| Worker (Dispatchers.Default) | Probe, alignment planning, GL render, JPEG |
| Main | Compose UI only |

`FrameRingBuffer` and `GyroRecorder` are the shared state and both synchronise
internally.

## The resource that will bite you

`ImageReader` hands out a fixed pool of buffers. Every `Image` held open is a
slot removed from that pool, and when the pool empties the camera *silently stops
delivering frames* - no exception, no log, just a frozen preview. Hence:

- the reader is created with `ringCapacity + 3` slots;
- `FrameRingBuffer` closes what it evicts;
- `StillStacker` closes the whole burst in a `finally`;
- `CaptureEngine.reapOrphans` releases images whose capture result never arrived.

## Why not the obvious alternatives

**CameraX** hides per-frame `SENSOR_TIMESTAMP`, exposure time and rolling-shutter
skew - the exact metadata this pipeline is built on.

**RenderScript** is what older write-ups recommend for the warp. It was
deprecated in Android 12 and should not be used in new code.

**Compute shaders** would fit, but the merge is a pure gather - one output pixel
reads one location per input - which fragment shaders already express perfectly,
while staying on the GLES 3.0 baseline instead of requiring 3.1.

**OpenCV** would supply feature matching for free at the cost of a ~40 MB
dependency. Worth revisiting in Phase 4 if the hand-rolled refiner proves
inadequate; not worth it before then.
