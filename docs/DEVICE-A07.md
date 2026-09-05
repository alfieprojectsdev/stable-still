# Samsung Galaxy A07 5G - measured constraints

The Phase 0 probe was run on 5 September 2026 against `SM-A076B`. Its report is
committed beside this file as `probe-SM-A076B.json`, and **it is the
authority**: where a published listing and the probe disagree, the probe is
right and the listing is describing a different SKU.

Verdict:

> Gyro pipeline fully supported (403 Hz). Camera and sensor clocks are shared,
> so no sync calibration is needed. Rolling-shutter skew is not reported; it
> will have to be estimated. No OIS, as expected - software stabilisation is
> the only option.

**The design holds.** The gyro path is viable, and two of the risks this
document was written to hedge against turned out not to exist.

## Measured

| | |
|---|---|
| Model | `SM-A076B` (`samsung a07x`) |
| Android | **16**, API 36 |
| Total RAM | **3480 MB** |
| GLES | 3.2 - so `EXT_color_buffer_float` is core, not an extension to test for |
| Gyroscope | Bosch `bmi3xy`, **`HARDWARE_FAST`** |
| Camera2 level | **`LEVEL_3`** |
| Timestamp source | `REALTIME`, i.e. `SHARED_REALTIME` with the sensors |
| OIS | Absent, as every listing agreed |

### Gyroscope

| Property | Value | Reading |
|---|---|---|
| Delivered rate | 402.7 Hz | 100.7% of the 400 Hz advertised - no throttling |
| Interval jitter | 0.002% | ~51 ns on a 2.5 ms interval: timestamps are sensor-side |
| Noise at rest | 0.00165 rad/s | 1.5x the resolution, so quantisation-limited |
| Resolution | 0.0011 rad/s | |
| Full scale | 34.9 rad/s | 2000 deg/s |
| Probe notes | *(none)* | No tell for a fused sensor fired |

This is a real MEMS part and not the accelerometer/magnetometer blend that the
A05 and A06 shipped. Nothing in the design needs to be defended against a fused
gyro any more.

### Camera 0

| | |
|---|---|
| Hardware level | `LEVEL_3` - RAW and manual sensor both supported |
| Sensor | 5.22 x 3.93 mm, focal length 3.98 mm |
| **Focal length in pixels** | **~3110 px** at 4080 wide (~66.5 deg horizontal) |
| Sensor orientation | 90 deg |
| Rolling-shutter skew | **Not reported** |
| Largest YUV | **4080 x 3060 (12.5 MP) at 20 fps** |
| Fastest useful YUV | 3264 x 2448 (8 MP) at 30 fps |

## What the probe settled

### 1. The gyroscope is real (was the highest risk)

`HARDWARE_FAST` at 403 Hz with hardware timestamps. The fallback plan - demote
the gyro to a seed for optical refinement, or drop it entirely and build a
multi-frame optical stacker - is not needed. It stays on the shelf rather than
in the roadmap.

For scale: at f ~ 3110 px, the measured noise integrated across a 20 ms exposure
is under 0.1 px of misalignment, and even integrated across a whole 550 ms burst
it reaches only ~0.13 px. Gyro *noise* is nowhere near the limiting factor.
Gyro *bias* is a different matter - see the open risks below.

### 2. The clocks are shared, so sync calibration is a no-op

This document previously bet on `UNKNOWN` and called sync calibration "the
reason Phase 4 is not optional". `SENSOR_INFO_TIMESTAMP_SOURCE` is `REALTIME`:
camera timestamps and `SensorEvent.timestamp` are both
`SystemClock.elapsedRealtimeNanos`, offset zero.

`SyncCalibration` should stay in `:core` with its tests - it costs nothing to
keep, it is the only defence if a future device reports `UNKNOWN`, and its
oscillatory-motion test guards a class of bug that recurs. But on this handset
it solves for zero, and Phase 4 shrinks accordingly.

### 3. `LEVEL_3` removes three constraints this document assumed

`LEVEL_3` is the highest Camera2 tier, above `FULL`. The consequences listed
here under "expect `LIMITED`" mostly do not apply:

- **RAW is available.** The merge need not happen on ISP-tone-mapped YUV. Worth
  revisiting once the YUV path works; not a reason to delay it.
- **Manual sensor control is available**, so a burst can be pinned to one
  exposure instead of relying on the weighted merge to absorb variation.
- **`SENSOR_TIMESTAMP` is dependable**, which `LEGACY` would have denied.

### 4. Rolling-shutter skew must be estimated

`SENSOR_ROLLING_SHUTTER_SKEW` is absent. `FrameMeta` defaults it to zero, which
degrades per-row correction without breaking alignment, so this is Phase 4
estimation work rather than a blocker.

## Open risks

These replace the pre-probe assumption table. All four are consequences of
measurement, not speculation.

### The 50 MP frame does not exist on the YUV path

Listings advertise a 50 MP main camera; the largest `YUV_420_888` output is
**4080 x 3060**. The full sensor resolution is presumably reachable only as JPEG
or RAW. Every figure in this document that was once derived from a 75 MB frame
was derived from a frame this pipeline cannot request.

A 12.5 MP `YUV_420_888` frame is **17.9 MB**.

### `recommendedStackDepth()` never binds

The heuristic budgets a quarter of total RAM and clamps to 3-12. On this device:
870 MB budget divided by 17.9 MB is **48 frames, so the clamp returns 12** - and
it returns 12 for every YUV size this camera offers. Even a hypothetical 50 MP
frame yields 12.2, which also clamps to 12.

So the RAM guard shrinks nothing. What it leaves ungoverned is real: twelve
frames is **214 MB of native ImageReader buffers on a 3.4 GB phone**. That is
the allocation-failure and thermal risk this section was always about, and the
heuristic as written does not address it. Decide the default depth from a
measured burst rather than from this formula.

### Full resolution costs frame rate, and frame rate costs alignment

4080 x 3060 runs at **20 fps**, not the 30 assumed before the probe. Twelve
frames therefore span **550 ms** rather than 367 ms - twice the hand-tremor
excursion to correct, and a correspondingly larger crop budget.

Dropping to 3264 x 2448 (8 MP) restores 30 fps. This is a genuine trade between
resolution and alignment quality, and it should be settled against a saved
burst, not by argument.

### Gyro bias is unmeasured, and it dominates

`restNoiseRadPerSec` is the standard deviation of the rate magnitude *about its
own mean*, so it deliberately excludes the zero-rate offset. `meanMag` is
computed inside `DeviceProbe` but never reaches the report.

Over a 550 ms burst, bias is the error that matters. Noise contributes ~0.13 px.
A 0.1 deg/s offset - unremarkable for an uncalibrated consumer MEMS part -
integrates to ~1.5 px across the +/-275 ms either side of the anchor, and
1 deg/s gives ~15 px. The one gyro property that will actually limit alignment
is the one the report does not carry. Emitting `meanMag` while the phone is
already sitting still is a cheap fix and should happen before Phase 1 depends
on it.

## Published specification, for the record

Retained because the disagreements between listings are what motivated the probe
in the first place. Struck-through entries are contradicted by measurement.

| | |
|---|---|
| SoC | MediaTek Dimensity 6300 (the 4G A07 uses Helio G99) |
| GPU | Mali-G57 MC2 |
| RAM | ~~4 or 6 GB~~ - 3480 MB reported |
| Display | 6.7" HD+ LCD |
| Main camera | 50 MP, f/1.8, PDAF, no OIS - but see the YUV ceiling above |
| Sensors listed | accelerometer, gyroscope, proximity, compass |
| Android | ~~15 (One UI 7)~~ - shipped 16 |

- [Samsung Galaxy A07 5G - Mobolist](https://www.mobolist.net/en/devices/samsung-galaxy-a07-5g)
- [Samsung Galaxy A07 5G - DeviceSpecifications](https://www.devicespecifications.com/en/model/c5f86615)
- [Samsung Galaxy A07 5G - GSMArena](https://www.gsmarena.com/samsung_galaxy_a07_5g-14409.php)
- [Galaxy A07 5G - Samsung India](https://www.samsung.com/in/smartphones/galaxy-a/galaxy-a07-5g-black-128gb-sm-a076bzkcins/)
- [Samsung Galaxy A07 5G - nanoreview](https://nanoreview.net/en/phone/samsung-galaxy-a07-5g)
