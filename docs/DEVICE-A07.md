# Samsung Galaxy A07 5G - constraints and open risks

## Published specification

From vendor and aggregator listings (see the sources at the end - note that they
**disagree**, which is itself a reason to trust the on-device probe over any of
them):

| | |
|---|---|
| SoC | MediaTek Dimensity 6300 (the 4G A07 uses Helio G99) |
| GPU | Mali-G57 MC2 |
| RAM | 4 or 6 GB |
| Display | 6.7" HD+ LCD |
| Main camera | 50 MP, f/1.8, PDAF, **no OIS** |
| Sensors listed | accelerometer, gyroscope, proximity, compass |
| Android | 15 (One UI 7) |

## What actually matters, and why the probe exists

Four properties decide whether this project's design works on this handset.
None of them can be read off a spec sheet.

### 1. Is the gyroscope real? (highest risk)

Budget Samsung A-series phones have a long history of listing "gyroscope" while
exposing a **software-fused** sensor derived from the accelerometer and
magnetometer. The A05 and A06 are documented examples; whether the A07 5G ships a
physical MEMS gyro is not something the listings settle - and note the two
searches behind the table above returned different chipsets for the same phone.

It matters because:

- a real MEMS gyro delivers 200-400 Hz with tight, sensor-side timestamps;
- a fused virtual gyro delivers ~50 Hz with timestamps assigned on *delivery*.

At 50 Hz, a 20 ms exposure spans one sample. There is nothing to integrate, and
the timestamps are not trustworthy enough to align against a frame anyway.

**How the probe answers it:** measures the *delivered* rate over 1.5 s, the
jitter in sample intervals, and the noise floor at rest, then grades the sensor
`HARDWARE_FAST` / `HARDWARE_ADEQUATE` / `MARGINAL` / `UNUSABLE` / `ABSENT`.
`minDelay <= 0` and jitter above 50% are both strong tells for a fused sensor.

**If it comes back unusable:** the gyro path is disabled and Phase 4's optical
alignment becomes the primary mechanism rather than a refinement. The capture,
merge, and crop machinery is unchanged - only the source of the homography
differs. The project is not dead, it just loses the horizon-lock-during-exposure
trick and gets slower.

### 2. Do the camera and sensor clocks share a time base?

`SENSOR_INFO_TIMESTAMP_SOURCE` is either `REALTIME` (camera timestamps are
`SystemClock.elapsedRealtimeNanos`, the same base as sensor events - offset zero,
nothing to do) or `UNKNOWN` (some other monotonic base, with a fixed but unknown
offset).

Budget devices very often report `UNKNOWN`. A 5 ms error at a typical 0.2 rad/s
tremor misaligns every frame by about a milliradian, which at f ≈ 2000 px is a
visible 2 px smear.

**Mitigation:** `SyncCalibration` solves for the offset by sweeping candidates
and keeping the one that leaves the least residual misalignment. Run once, store
in preferences. This is Phase 4 work, and it is the reason Phase 4 is not
optional.

### 3. Camera2 hardware level

Expect `LIMITED` (`FULL` would be a pleasant surprise, `LEGACY` a serious
problem). Consequences:

- **No RAW**, so the merge happens on YUV that the ISP has already tone-mapped.
  Acceptable - most of the noise win survives - but it caps the ceiling.
- **No manual exposure**, so frames in a burst may have differing exposure. The
  weighted merge partially absorbs this; per-frame exposure normalisation is a
  Phase 5 improvement.
- `SENSOR_ROLLING_SHUTTER_SKEW` may be absent. `FrameMeta` defaults it to zero,
  which degrades per-row correction without breaking alignment.
- On `LEGACY`, `SENSOR_TIMESTAMP` is not dependable at all and only the optical
  path is viable.

### 4. Memory and thermals

This is the constraint that most changes the design from the original sketch.

A 50 MP YUV_420_888 frame is **75 MB**. Ten of them is 750 MB - impossible on a
4 GB phone. Even the binned 12.5 MP output is 18.75 MB per frame.

`DeviceProbeReport.recommendedStackDepth()` therefore budgets a quarter of total
RAM and clamps to 3-12 frames. On a 4 GB A07 at 12.5 MP that lands around **8
frames**, which is the default.

The Dimensity 6300 will also throttle under a continuous full-resolution YUV
stream. Practical mitigations, in order of preference:

1. run the ring buffer at a **lower** resolution than the sensor's maximum
   (the probe reports sustainable fps per size);
2. keep the buffer running only while the capture screen is foregrounded;
3. cap the burst window - more frames past ~8 buys progressively less.

## Working assumptions until the probe says otherwise

| Assumption | Basis | If wrong |
|---|---|---|
| No OIS | Consistent across all listings | Nothing - we would just have less to correct |
| Gyro is real and >= 100 Hz | Listings claim a gyro | Fall back to optical alignment (Phase 4) |
| Camera timestamp source is `UNKNOWN` | Typical of budget HALs | Sync calibration becomes a no-op, which is a win |
| Camera2 level is `LIMITED` | Typical of this tier | `FULL` unlocks manual exposure and better bursts |
| ~12 MP binned output at 30 fps | 50 MP sensors bin 4-in-1 by default | Adjust stack depth from the probe's numbers |
| GLES 3.2 with `EXT_color_buffer_float` | Mali-G57 supports 3.2 | 8-bit accumulation, banding after ~6 frames |

## Sources

Specifications vary between listings; treat all of them as provisional and the
on-device probe as authoritative.

- [Samsung Galaxy A07 5G - Mobolist](https://www.mobolist.net/en/devices/samsung-galaxy-a07-5g)
- [Samsung Galaxy A07 5G - DeviceSpecifications](https://www.devicespecifications.com/en/model/c5f86615)
- [Samsung Galaxy A07 5G - GSMArena](https://www.gsmarena.com/samsung_galaxy_a07_5g-14409.php)
- [Galaxy A07 5G - Samsung India](https://www.samsung.com/in/smartphones/galaxy-a/galaxy-a07-5g-black-128gb-sm-a076bzkcins/)
- [Samsung Galaxy A07 5G - nanoreview](https://nanoreview.net/en/phone/samsung-galaxy-a07-5g)
