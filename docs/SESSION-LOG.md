# Session log

Newest first. One entry per working session: what changed, what was measured,
and what the next session should not have to rediscover.

`docs/HANDOVER.md` carries the *current* state and the next action.
This file carries how it got there.

---

## 2026-09-17 - the rig was the wrong way round

First session with the cloud branch, the SDK and the phone all in one place.
The plan was to run what the 16 September session built. What it found was
that the most error-prone constant in the pipeline had been wrong since the
first burst.

### Handedness is -1, on every burst asked

`RigCalibration.settleHandedness` gained a real-burst hook and was run on the
four bursts with frame files on the laptop:

| burst | scene | margin | corners apart | rotation |
|---|---|---|---|---|
| 085821 | indoor static, 30 cm | 48% | 55 px | 6.5 mrad |
| 085849 | indoor, hand crossing | 83% | 459 px | 61 mrad |
| 113314 | rooftop document | 39% | 300 px | 50 mrad |
| 225438 | 5 Sept archive | 130% | 84 px | 10 mrad |

Four for four, and none close to the 2 px separation at which the test
declines to answer. The +1 the code shipped with had been *adding* every
frame's rotation rather than removing it, for two weeks of measurements.

The default is now `RigAlignment.SETTLED_HANDEDNESS = -1`, so new captures
record it. The 27 archives on disk record the +1 they were captured with,
faithfully, so `BurstManifest.replayRig` supplies the manifest's geometry with
the measured sign, and the replayer, the audit and the pixel tests all align
through it.

**Why nothing noticed.** Corner shift is a magnitude. Flipping the sign rotates
the correction the other way by the same amount, so every shift, crop and
rotation figure in the audit comes out identical under either sign - the
re-run audit after the fix is the same table to the pixel. Only a residual
against pixels can see handedness, and until 16 September there was no code
that measured one.

### With the sign right, refinement finds what it was built to find

`OpticalRefinement` is wired into `BurstReplayer` and ran on the phone under
both signs. What it had left to correct:

| burst | under +1 | under -1 |
|---|---|---|
| archive, far field | 8-65 px, 1.9-2.5x | **0.2-4.3 px**, 1.0-1.3x |
| indoor static, 30 cm | 7-53 px, 2.1-3.3x | **2-14 px**, 1.4-2.8x, all converge |
| hand crossing | 41-146 px | 8-133 px, frames 5-7 chasing the hand |
| rooftop document, 177 px | 19-48 px, 1.0-1.8x | 4-30 px, 1.1-1.3x, residual ~0.10 stays |

The archive row is the confirmation: at distance the gyro alone is enough, and
refinement is nearly a no-op. The indoor row is the design working: 2-14 px of
parallax at 30 cm, which a gyroscope cannot see and a page at reading distance
produces, removed. The document row is open - something translation does not
model survives, and at 177 px of shift with 27 ms of rolling-shutter skew that
is the first suspect.

Refinement costs 14-40 s per burst on the phone, which is fine for a replay
tool and not for a shutter button.

### The sweep runs on the JVM now, and the ceiling stands

`ThresholdSweepTest` gained a real-burst hook: ten thresholds, gyro-only and
then refined, on a 12.5 MP burst, **80 seconds, no phone**.

On the static control, refinement lowers the ghost from residual misalignment
at every threshold - **16 levels against 27 at sigma 0.60** - which is the
"refinement raises the ceiling" claim measured rather than argued. On the
hand-crossing burst it *raises* it, 28 against 25: a rigid-scene translation is
pulled toward a subject filling a third of the frame and drags the background
out of alignment behind it. Refinement needs to be told where not to look
before it is safe on a moving scene. That is the next thing to build in it.

The ceiling itself was measured last week through the wrong sign, and it
holds: the ghost-to-static ratio crosses 1.0 at about **0.13** under both plans
on the hand burst, where the eye put the onset at 0.15. A moving hand ghosts
however well the background behind it is aligned.

One reading note for the sweep table. `residNoise` comes out *higher* refined
than gyro-only, and that is not refinement adding noise: the statistic is
noise plus texture, and alignment good enough to preserve texture reads as
more of it. Effective frame count is the honest column, and it rises.

### The audit, over all 27

CSVs only, 458 KB, one command. Crop: the hungriest burst that fits at all
needs **7.6% per side** (the hand burst); 25 of 27 fit inside 12%; the two that
do not are the dim shakes at 102% and 118% of the rotation budget, which no
margin saves. Anchor: frame 0 chosen in **5 of 27**, median steadiness
advantage 2.12x - the selector is working. Blur at the anchor 0.1 to 24 px,
mean 3.8. None of these move with handedness, for the reason above.

### Also

- The phone had rebooted since the 9th, which clears `adb tcpip`; re-pinned.
  On the same Wi-Fi the mDNS transport reappears alongside it, so `-s` is
  needed until the phone leaves the network.
- Test count 96, five gated on burst paths.

---

## 2026-09-16 (fourth) - the phone comes out of the loop

No hardware this session, and no burst pixels either: the 3.3 GB of archives
were still on the far end of a 1 MB/s tailnet, so everything here was built and
tested against the metadata fixture and against synthetic bursts whose answers
are known by construction. That constraint turned out to set a good agenda,
because the thing most worth building was the thing that removes the phone from
the loop.

### The merge now runs on a JVM

`ReferenceMerge` is the shaders' three passes in `:core`: warp, weight, resolve.
It is written to *match* `StackRenderer`, not to improve on it, and the awkward
parts are the point - the half-texel offset GLSL bakes into `texture()`, the
anchor quantised to eight bits before any difference is measured against it, the
bounds guard that drops a contribution where clamping would smear an edge pixel
across the output. The single intended divergence is precision: the GPU
accumulates into `RGBA16F` where the extension is present, this accumulates in
single precision, so a disagreement of a level or two is that and a disagreement
of ten is a bug in one of the two.

What it was for is `ThresholdSweep`. Separating the benefit from the cost needs
one threshold-independent pass - how far the most dissenting frame sits from the
anchor - after which a departure from the anchor where that is large is a ghost
and the same departure elsewhere is noise being averaged away. The sweep
therefore reports the trade rather than a number someone still has to interpret,
and the 9 September finding is now a test rather than an afternoon:

|  sigma | eff. frames | residual noise | ghost (levels) |
|---|---|---|---|
| 0.05 | 3.96 | 0.01571 | 0.00 |
| 0.10 | 5.38 | 0.01010 | 0.00 |
| **0.15** | **5.96** | **0.00874** | **0.00** |
| 0.30 | 6.46 | 0.00814 | 5.49 |
| 0.60 | 6.97 | 0.00827 | 65.60 |
| 1.00 | 7.48 | 0.00832 | 81.46 |

Synthetic, so the numbers are not the device's. The *shape* is the claim, and it
is the shape measured on hardware: noise stops improving around 0.15-0.30 and
ghosting is still climbing at 1.00. Everything above the knee buys a ghost and
nothing else.

### Optical refinement, and two things the tests decided

`OpticalRefinement` is Lucas-Kanade on a luma pyramid, estimating a
**translation** - the term a gyroscope structurally cannot see and the one
parallax at close range produces. Rotation the gyro already has to milliradians
from a 403 Hz trace, and two parameters stay conditioned on the texture a real
scene offers where eight would fit noise.

Plain LK rather than ECC, for a reason specific to this capture path: ECC buys
invariance to illumination change between frames, and the exposure cap holds
exposure and gain fixed for the whole burst, so there is nothing to be invariant
to.

Two things came out of testing rather than out of design:

- **The normal equations need damping.** A scene textured one way only - a
  horizon, a window frame, ruled lines on a page - is singular in the
  perpendicular direction *alone*. The first implementation checked the
  determinant and refused the whole step, which throws away the direction that
  is perfectly observable. Damped, the observable direction comes back intact
  and the unobservable one is pulled to zero, which is the honest answer for a
  shift nothing in the frame constrains.
- **Four pyramid levels is not a round number.** It is what reaches the 10-30 px
  the page-at-30 cm case predicts. Measured across the range, with four levels
  the recovery is exact; with two, it converges, *reports* convergence, and
  settles a whole period of the scene's finest texture away from the truth. A
  refiner that is confidently wrong is worse than one that declines, so this is
  not a default to trim later without re-measuring.

And the claim that refinement raises the *threshold's* ceiling rather than only
sharpening the picture is now a test: four frames of a static scene displaced by
a few pixels each stack 2.2 of 4 at sigma 0.15 before refinement and 4.0 after.
Misalignment reads as disagreement, the merge rejects disagreement, and the
rejected frames come back when the alignment is corrected.

### Handedness can now be settled, and needs the right burst

`RigCalibration.settleHandedness` warps a burst both ways and keeps whichever
leaves the frames agreeing with the anchor. Tested against bursts *rendered*
through a stated handedness, so there is a right answer to find: seeded with
`+1` and shown a burst built the other way, it says so, by a margin over 100%
rather than a few percent.

**The burst has to have a tilt in it.** Handedness enters only through a
rotation about the optical axis, by `handedness * SENSOR_ORIENTATION`, and
rotations about that axis commute with it - so a burst that only *rolls* gives
both signs identical homographies and can decide nothing. Pitch and yaw separate
them. The verdict reports how far apart the two hypotheses place a crop corner
and refuses to choose when that is sub-pixel, because the alternative is a coin
toss recorded as a measurement, which would then travel in the manifest of every
burst captured afterwards.

### The crop margin is not as generous as it looked

`BurstAudit` answers the crop, anchor and blur questions from timestamps and
angular velocity alone, so it runs on an archive whose frame files never left
the phone. `minimumSafeMargin` bisects for the smallest margin at which every
frame still lands on the sensor - tighter than dividing the worst shift by the
width, because a corner pushed *inwards* costs nothing and what binds is the
signed excursion.

On the fixture burst, which is the steady one:

| | |
|---|---|
| Max corner shift | 43.1 px |
| Max rotation | 10.4 mrad against a 117.9 mrad budget |
| Slack used | 11.7% |
| **Margin actually needed** | **1.13% per side, against 12% given** |

Taken alone that says 12% is lavish, which is what the open question assumed.
Taken with the shifts already recorded here it says the opposite. The motion
burst of 9 September moved a corner 253 px and the two dim bursts about 350 px;
against 4080 px of width that is 6.2% and 8.6%, and against 3060 px of height
8.3% and **11.4%**. Which axis binds decides whether the worst burst on record
sits comfortably inside 12% or almost exactly on it.

So the answer is not "far too generous". It is **roughly right, possibly
trimmable to 10%**, and the earlier suspicion was formed from the steadiest
burst in the collection. Trimming to 10% would buy back 10.8% of the pixel
count - `(1-2m)^2` is 0.64 against 0.578 - and still clear a 350 px x-axis
excursion by 17%. It would *not* clear the same excursion on the y axis. The
audit over all twenty-two bursts is what decides it, and is now a one-liner:

```
./gradlew :core:test --tests '*BurstAuditTest*' -Dstablestill.burstRoot=/path/to/bursts
```

### The anchor is a real choice, and its value is not where it looks

On the fixture burst the selector picks **frame 1, not frame 0**, so it is not
defaulting. It is 2.07x steadier than the average frame. But the per-frame
steadiness scores show where that is actually worth something:

| frame | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 |
|---|---|---|---|---|---|---|---|---|
| rad/s | 0.0215 | **0.0214** | 0.0307 | 0.0495 | 0.0506 | 0.0344 | 0.0586 | 0.0890 |
| blur px | 1.33 | **1.33** | 1.90 | 3.07 | 3.14 | 2.13 | 3.63 | 5.52 |

Frames 0 and 1 are tied to within 0.15%, which is noise. **The choice between
the top two is a coin toss; the value is entirely in avoiding frames 6 and 7**,
where blur is four times the anchor's. That is worth knowing before anyone
spends effort on a sharpness-based tie-break: at ISO 1047 a Laplacian score is
inflated by noise, and it would be breaking a tie that does not matter.

### The 20-vs-30 fps trade, as far as it goes without the pairs

The daylight pairs are still unexamined, but the trade is now mostly decided by
things already measured. 12.5 MP runs at 20 fps and 8 MP at 30 fps; eight frames
therefore span 351 ms or 233 ms.

- **Noise is neutral.** The 20 ms exposure cap sits below both frame intervals
  (50.1 ms and 33.4 ms), so it binds at neither rate. Same exposure, same gain,
  same per-frame noise, same stacking benefit for the same frame count.
- **The shorter span buys alignment**, about a third less time for tremor to
  accumulate - and the crop audit above says the margin is not binding in good
  light, so in daylight that buys very little.
- **It costs 36% of the pixels**, 8 MP against 12.5.
- **The ISO ceiling is what actually decides it.** 12.5 MP caps at ISO 1047;
  8 MP reaches 2425-3055. Past the cap, full resolution cannot expose the scene
  at all, and no amount of stacking fixes an underexposed frame.

So the rule is a light meter, not a preference: **stay at 12.5 MP / 20 fps while
the metered ISO is under about 1000, and drop to 8 MP / 30 fps when the scene
asks for more gain than that.** Below the cap the resolution is free; above it,
the resolution is imaginary.

The one case that could overturn this is document capture, where 8-point type
wants every pixel and the parallax residual wants the shorter span. That one
needs the page burst before anyone argues about it.

### A review pass, and what it says about writing tests

A code review over the branch found seven defects in the above, all fixed, each
with a regression test. What they have in common is worth more than the list:
**every one of them produced a plausible answer.** Nothing crashed, no test went
red, and each wrong result sat in exactly the range a right one would.

- `compareRates` printed a literal `%.0f` and put a burst span where a
  percentage belonged, because `"..." + "...".format(x)` binds the format to the
  last fragment alone. In the one output the fps comparison exists to produce.
- Both handedness hypotheses were averaged over *whatever each one individually
  kept*. A wrong sign that flings the high-motion frames off the sensor was then
  judged only on the calm ones it kept - the frames it gets most nearly right -
  and could win a comparison it deserved to lose. Now scored on the frames both
  signs kept, with the asymmetry reported separately rather than buried.
- With no frame usable under either sign, the margin short-circuited to 1.0 and
  the verdict came back **decisive**: a confident sign from zero evidence, which
  is precisely the failure the indecisive path was written to prevent, and it
  would have travelled in the manifest of every burst captured afterwards.
- `converged` was set by any pyramid level and never cleared, so a coarse level
  settling made the whole refinement claim convergence while the finest level
  was still hunting. The answer is in the finest level's units; a caller
  trusting the flag would trust a digit that is not there.
- The sentinel for "infinitely steadier than average" was `MAX_VALUE`, which is
  finite, so the `isFinite` filter written to drop it kept it and put 1.79e308
  into a reported median. It is `POSITIVE_INFINITY` now, which is both true and
  detectable.
- The anchor frame was fetched twice, against a documented promise that each
  frame is requested once - 17.9 MB re-read per merge for a streaming caller.
- A worker thread throwing died silently: `join` returned, and the merge
  returned a picture with a stripe missing and an `effectiveFrameCount` that
  reported the loss as honest rejection.

Two findings did not survive investigation, and that is the other lesson. A
`continue` that steps over the inter-level rescale is real in the source and
**unreachable in practice**: `sampleGrid` bounds a tap in *level* units, which
tighten as the level coarsens, so emptiness is monotone - every empty level
precedes every populated one and the estimate carried across is still zero. The
loop was restructured anyway, since the reasoning lives in the bounds rather
than the loop, but no test asserts a failure that cannot happen.

**Every regression test here was checked by reverting its fix.** Three of the
first drafts passed against the broken code - they exercised the right function
and never reached the defect - which would have left the branch looking guarded
and not being. Two needed a specific burst to reproduce at all: the handedness
subset bug only shows where the signs genuinely disagree about which frames fit
(a 5% margin and one particular tremor, keeping `{5}` against `{5, 7}`), and the
convergence bug needs near-Nyquist texture so the coarse levels settle while the
finest is still hunting. A regression test that has not been watched to fail is
a guess about what it covers.

One guard is deliberately untested. Injecting a worker-thread failure needs a
`YuvFrame` that throws from `sampleRgb`, which means making that method virtual -
and it is called once per output pixel per frame, seventy million times for an
eight-frame 12.5 MP burst, in the loop whose speed is the entire reason this
module exists. It was verified by hand against a temporarily opened class and
the seam was not kept.

### Not done, and why

`:app` was not touched. There is no Android SDK in this environment, so
`settings.gradle.kts` drops `:app` entirely and anything written there would
have been pushed uncompiled. Wiring `OpticalRefinement` into `BurstReplayer` is
the obvious next edit and is deliberately left for a session that can build it.

---

## 2026-09-09 (third) - you cannot make a frame noisier by turning the lights off

Eight dim indoor bursts, captured to settle whether the ghosting onset scales
with noise. They do not settle it, because the premise behind asking for them
was wrong. What did settle is the *knee*, which scales cleanly, and that turns
out to be the more load-bearing half.

### The premise was wrong

Darkness raises gain but lowers signal, and it lowers signal faster. Shot noise
goes as the square root of signal, so the absolute pixel differences the merge
compares against get *smaller* as a room gets darker. Two bursts at identical
ISO 1047 and 12.5 MP make the point without any inference:

| burst | ISO | mean luma | crushed | measured noise |
|---|---|---|---|---|
| 5 Sept archive | 1047 | 80.9 | 0.6% | **0.0250** |
| 9 Sept dim | 1047 | 18.7 | 16.8% | **0.0143** |

Same gain, same sensor, same resolution. The darker one measures *less* noise,
because 86% of its pixels sit in the bottom eighth of the range. All eight dim
bursts came back at mean luma 20-37 against 104 for a lit room, two of them
effectively black.

This is not a fault in `NoiseModel`. The merge compares absolute differences,
so a dark frame genuinely has smaller ones, and reporting that is correct. The
fault was in asking for a darker room instead of a *dimmer-lit* one.

**What a high-noise burst actually requires** is high gain at correct exposure:
ambient dim enough to force ISO 1000+, but a subject exposed near mid-histogram
(mean luma 80-100). The 5 September archive burst is exactly that and remains
the noisiest sample in the collection.

Also learned, and worth knowing before chasing gain: **12.5 MP caps at ISO
1047.** Three bursts and the archive all sit at exactly that figure. The 8 MP
mode reaches 2425-3055, presumably binned. Past the cap, a darker room only
underexposes.

### The knee scales, and that is the useful result

Where residual noise stops improving, against the frame's measured noise:

| burst | noise | knee | knee / noise |
|---|---|---|---|
| 113426 rooftop | 0.0069 | ~0.06 | 8.7 |
| 113227 rooftop | 0.0097 | ~0.09 | 9.3 |
| 085821 indoor static | 0.0128 | ~0.12 | 9.4 |
| 085849 indoor motion | 0.0136 | ~0.12-0.15 | 9-11 |
| 225438 archive | 0.0250 | ~0.20-0.25 | 8-10 |

**Nine to ten times the measured noise, across a 3.6x range.** That is what
justifies the shape of the model: the *benefit* of a looser threshold scales
with noise, which is precisely what `SIGMA_PER_NOISE` encodes, and 6.5 sits
below the knee everywhere with margin to spare.

### The onset probably scales too, but the evidence is muddier

At near-matched alignment residual, comparing the noisiest burst with the
cleanest:

| burst | noise | shift | onset |
|---|---|---|---|
| 113426 rooftop | 0.0069 | 48 px | ~0.15 |
| 225438 archive | 0.0250 | 43 px | ~0.40 |

3.6x the noise, roughly 2.7x the onset - consistent with scaling, sub-linear if
anything. But 113227 breaks the pattern: noise 0.0097 with only 28 px of shift
and an onset near 0.40. Shift and *subject texture* both confound it. Weathered
concrete loses its mottling long before bold printed type doubles, so "where
ghosting starts" is partly a property of what is in frame. Three static scenes
cannot separate three variables.

### So MAX_SIGMA may be over-tight, and exactly one burst would say

The clamp only ever binds above noise 0.023 - high ISO, correctly exposed. And
that is the regime where the measured knee is 0.20-0.25 and the static onset is
0.40, so holding at 0.15 costs real noise reduction: 2.07 against 1.94 levels
on the archive burst, about 6%.

Against that, the 0.15 figure came from a moving subject at noise 0.0136 -
a regime where the clamp does *not* bind, since the derived threshold there is
0.088. If the onset scales, the same subject at noise 0.0250 would ghost nearer
0.28, and the clamp could rise.

Left at **0.15**, because raising it on that reasoning would be fitting to an
extrapolation, which is the mistake this whole thread of work exists to undo.
The burst that decides it: **lamp-lit room, ISO 1000+, mean luma near 80, hand
crossing frame, plus a static control of the same scene.** Not a darker room -
a dimmer-lit one.

### Incidental

- Two dim bursts merged only 6 of 8 frames, at 375 px and 346 px of corner
  shift. First time the crop budget has been approached rather than admired.

---

## 2026-09-09 (later) - daylight, and a noise estimator that returned zero

Nine rooftop bursts at ISO 25-64, from a phone on mobile data over Tailscale
with the laptop indoors. The ceiling set earlier today survives; a prediction
made alongside it does not; and a defect turned up that had nothing to do with
either.

### estimateNoise returned exactly zero on three of nine real bursts

Auto-exposure held 20 ms outdoors in three bursts, blowing out **52-73% of
their pixels**. The 25th-percentile tile then landed *inside* the clipped
region, where variance is zero because the sensor ran out of range rather than
because the scene is quiet, and the function returned 0.0000. Threshold falls
to `MIN_SIGMA`, stacking quietly stops doing much, and nothing says so.

The doc comment claimed this was handled - "not the minimum, though: a clipped
black region has no variance at all". A percentile only survives while the
clipped fraction stays *below* the percentile. At 70% it does not.

Fixed by dropping tiles that are more than half clipped before taking the
percentile. On the three real bursts, 0.0000 becomes 0.0028, 0.0051 and 0.0150;
the six unclipped bursts do not move. A frame clipped everywhere still returns
zero, which is the honest answer.

| burst | clipped | noise before | after |
|---|---|---|---|
| 113149 | 70.7% | 0.0000 | 0.0028 |
| 113401 | 73.3% | 0.0000 | 0.0051 |
| 113412 | 51.8% | 0.0000 | 0.0150 |
| the other six | 0-0.3% | unchanged | unchanged |

### The ceiling holds, and the mechanism is alignment residual

Noise reduction finishes early in every burst measured, at 0.06 to 0.12, and
the ghosting onset moves with **how far the frame had to be warped** rather
than with gain:

| burst | shift | noise | ghosting onset |
|---|---|---|---|
| daylight document, 8 MP | 28 px | 0.0097 | ~0.40, clear by 0.60 |
| daylight document, 12.5 MP | 177 px | 0.0102 | ~0.25-0.40 |
| indoor static | - | 0.0128 | ~0.40 |
| indoor, hand crossing frame | 253 px | 0.0136 | **0.15** |

`MAX_SIGMA = 0.15` sits below every onset and above every knee. Nothing here
argues for moving it, and the moving subject remains what binds - as it should,
since it is the only case where the disagreement is real signal rather than
residual error.

That the onset tracks residual and not noise is the more useful half. It says
optical refinement would not merely sharpen the output, it would *raise the
ceiling*, because the ceiling is a consequence of alignment error.

### A prediction that did not survive

Yesterday's reasoning said daylight would drive measured noise to about 0.005,
the 6.5x rule to 0.033, and therefore pin the threshold at `MIN_SIGMA = 0.06` -
while an onset scaling at 11x noise would sit at 0.055, *below* that floor,
indicting the floor. Wrong twice over:

- Measured noise in daylight is **0.0069 to 0.0102**, not 0.005. On a real
  textured scene the estimator is limited by scene texture, not by the sensor:
  ISO 25 through ISO 376 all report 0.009-0.014, a range far narrower than
  their gain. It resolves high noise, not low.
- At the lowest usable figure, 0.0069, a sweep through 0.03 / 0.06 / 0.09 shows
  a clean image at 0.06. The floor is not causing ghosting.

So `MIN_SIGMA` stands. The scaling question - does the onset move with noise or
sit at a fixed value - is **still open**, but for a different reason than
expected: not that it was answered, but that the estimator cannot resolve a
gain change large enough to ask. Settling it needs the high-noise end, a dark
indoor burst with a moving subject, not a brighter one.

### Incidental

- Two bursts carry the app's own warning that exposure varied across the burst
  (113216, 113326). Worth avoiding when a burst is being used as evidence.
- `am start` for the next replay races the previous activity's `finish()`, and
  the new intent lands on an activity already tearing down:
  `JobCancellationException: Job was cancelled`. Five of nine replays failed
  this way before a settle delay was added between them. It is a harness
  problem, not a pipeline one, but it fails in a way that looks like a crash.
- The 12.5 MP and 8 MP daylight pairs needed for the 20-vs-30 fps trade now
  exist and are unexamined.
- Test count 59 to 61.

---

## 2026-09-09 - the rejectSigma ceiling, measured

Two bursts of the same scene - a hand over a laptop on a desk, ISO 322-376 -
one static, one with the hand moving through frame. Each replayed at fourteen
thresholds from 0.01 to 1.00. `MAX_SIGMA` drops from **0.60 to 0.15**.
`SIGMA_PER_NOISE` stays at 6.5; measurement bracketed it rather than moved it.

### Why the last sweep could not see this

It scored whole frames on noise. Noise is exactly the quantity a looser
threshold always improves, so the metric had no way to express the cost it was
buying. Two changes made the knee visible:

- **A reference that isolates the merge.** Replaying at sigma 0.01 underflows
  every non-anchor weight, so the output is the anchor alone - through the same
  warp, the same crop and the same JPEG encoder as every other threshold.
  Differences against it are what the merge did, with no resampling or encoding
  artefact to argue about.
- **Measuring noise in the static region only, and then looking at the
  pictures.** The scalar located the knee; the crops confirmed what it was.

### The knee, and it is a real one

Residual noise in static regions, 25th-percentile tile deviation, 8-bit levels:

| sigma | 0.01 | 0.03 | 0.06 | 0.09 | 0.12 | 0.15 | 0.20 | 0.30 | 0.40 | 0.60 | 1.00 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| static burst | 2.52 | 1.87 | 1.27 | 1.15 | 1.10 | 1.09 | 1.10 | 1.09 | 1.09 | 1.08 | 1.08 |
| motion burst | 2.71 | 2.49 | 1.78 | 1.44 | 1.30 | 1.25 | 1.18 | 1.17 | 1.14 | 1.14 | 1.13 |

**Noise reduction is finished by about 0.12.** From there to 1.00 - a factor of
eight in threshold - it improves by 0.02 levels, which is nothing. Everything
above that point is bought with ghosting and paid for with no noise at all.

### Where ghosting starts, by eye at 1:1

Two ceilings, and the tighter one binds:

| | onset | what it looks like |
|---|---|---|
| **Moving subject** | **0.15** | A translucent contour of the fingertip appears on plain background. Clean at 0.12, faint at 0.15, unmistakable at 0.20, a full second finger by 1.00. |
| **Static scene** | ~0.40 | High-contrast edges double. The ThinkPad logo is sharp through 0.30, visibly doubled at 0.40, badly ghosted at 1.00. |

The static number is the more surprising of the two, because it says a static
scene ghosts as well - just later. Nothing in frame moved; what doubles is
residual misalignment, and rejection was the only thing hiding it. So "with
nothing moving, a looser threshold is always better", which the 8 September
entry offered as the reason its sweep was degenerate, is only true of the
metric that sweep used.

### What that makes of 6.5

The onset sits at 0.15 against a measured noise of 0.0136, so ghosting begins
near **eleven times** the per-channel noise, and noise reduction stops
improving near **nine times** it. The derived 6.5 sits below both, with about a
1.7x margin to the onset, and gives up a few percent of the available noise
reduction to keep it. Left alone: the measurement covers one gain, and the
margin is what covers the rest.

The clamp now binds where it should. The ISO 1047 archive burst measures 0.0250
and the rule alone would take it to 0.162, past the onset; it is held at 0.150,
which that burst's own sweep says costs 1.92x noise reduction against 1.96x.

### Caveat worth carrying

The motion burst also had a **253 px** max corner shift - a quarter of the crop
budget, against 10.4 mrad for the steady burst - at roughly 30 cm subject
distance. Translation parallax at that range displaces the static background
too, which is why its whole-frame disagreement map lights up everywhere and why
the moving region could not be isolated by a scalar alone. It does not weaken
the ceiling, which was read off crops at 1:1, but it means the 0.15 figure is a
*conservative* onset: a burst with less camera translation might tolerate a
little more. Nothing above 0.12 buys noise reduction anyway, so there is no
reason to go looking.


### Getting adb to survive the phone leaving the desk

Wireless debugging is tied to Wi-Fi. Take the phone off the network and Android
switches it off, `adbd` stops listening, and the tailnet stays perfectly
healthy with nothing on the other end - which reads as a Tailscale failure and
is not one.

`adb tcpip 5555` sets `service.adb.tcp.port` instead: a listener on all
interfaces, independent of Wi-Fi, persisting across network changes until the
next reboot. With the phone on mobile data and Tailscale up, `adb connect
100.79.189.46:5555` then works from anywhere, and a replay sweep can run while
the phone is somewhere else entirely. Set on 9 September and verified end to
end.

Two caveats. Port 5555 takes any connection that can reach it and falls back to
the on-screen RSA prompt, where paired wireless debugging requires pairing
first - fine over a tailnet, weaker on whatever LAN the phone rejoins. And the
mDNS transport reappears on its own while the phone is on the same network, so
one phone shows up twice and bare `adb` fails with "more than one
device/emulator" until one is dropped or `-s` is passed. Leaving the network
resolves that by itself.

**Capture needs no connection at all.** The app writes bursts to the phone's
own storage and the Capture tab confirms each save on screen, so a capture trip
out of adb range loses nothing. Storage is not a constraint either: 66 GB free
against 1.2 GB for ten bursts.
### Also

- `--es burst <directoryName>` now selects which burst `autoReplay` stacks.
  "Newest" stopped being a fixed input the moment a control burst existed.
- `./gradlew :app:...` from a worktree needs `ANDROID_HOME` in the environment.
  The worktree has no `local.properties`, so `settings.gradle.kts` drops `:app`
  and Gradle reports it as a task that does not exist - which reads as a typo
  rather than a missing SDK. An install can appear to succeed while the old APK
  stays on the phone; new intent extras being ignored is the symptom.
- Test count 57 to 59.

---

## 2026-09-08 (later) - rejectSigma derived from measured noise

`rejectSigma` was a constant. It is now derived from the anchor frame's own
noise, in `:core` with tests, because the distance between two *correctly
aligned* frames is itself proportional to sensor noise - so a fixed threshold
rejects genuine agreement precisely when gain is high and there is most noise
to average away.

`NoiseModel.estimateNoise` takes the standard deviation inside 8 px tiles and
returns the 25th percentile across them. Not the mean, which every edge drags
upward; not the median, which only survives while most of the frame is flat;
not the minimum, which a clipped black region takes to zero. On the fixture
burst it reports **0.0250 (6.4/255)**, stable to three digits across frames.

Note this is a different statistic from the 9.87/255 quoted in the 5 September
entry, which was a mean over tiles. Both are "the noise", measured differently.

### The sweep was degenerate, and that is the finding

Eight thresholds against the same ISO 1047 burst:

| sigma | 0.10 | 0.15 | 0.16 | 0.20 | 0.25 | 0.30 | 0.40 | 0.55 |
|---|---|---|---|---|---|---|---|---|
| stacked noise | 5.85 | 5.14 | 5.03 | 4.77 | 4.49 | 4.25 | 3.82 | 3.38 |
| reduction | 1.69x | 1.92x | 1.96x | 2.07x | 2.20x | 2.32x | 2.58x | 2.92x |

There is no knee. Noise falls monotonically to the clamp, which is exactly
what a *static* scene must do: with nothing moving, a looser threshold is
always better, and the rejection has nothing to earn its keep against. So the
sweep cannot calibrate the constant. Fitting to it would drive the threshold
to its maximum and buy a ghosting regression on the first moving subject.

`SIGMA_PER_NOISE = 6.5` is therefore derived rather than fitted: the
difference of two noisy samples carries sqrt(2) the noise of either, and the
magnitude of that difference across three channels is Maxwell-distributed with
its 99.9th percentile near 4.6 standard deviations, so sqrt(2) * 4.6 = 6.5
admits essentially all genuine noise.

On the fixture that yields sigma **0.162** and 1.96x reduction, against 1.69x
for the old constant. A real improvement, and well short of the 2.92x the
loosest threshold reached - deliberately, since none of that headroom is
justified until something moves in frame.

### Left open

- **The ceiling is unmeasured.** Setting it needs a burst containing motion:
  a person walking, a hand crossing the frame, traffic. Until then the model
  is sound in its lower half and guesswork in its upper.
- The identity NCC against the anchor falls as sigma rises, 0.9885 to 0.9601.
  That is averaging working, not drift - the output is meant to stop
  resembling any single frame - but it means NCC-against-anchor cannot double
  as a correctness check once the threshold is loose.

---

## 2026-09-08 - Tailscale as the route to the phone

Short session, no code changes.

The A07 joined the tailnet, and its Wireless debugging screen then advertised
the *tailnet* address rather than the LAN one - `adbd` binds to all interfaces
- so `adb connect 100.79.189.46:<port>` works and shell, pull and screencap
all behave normally over it. That removes the same-network requirement
entirely: the phone can be on mobile data, or the laptop elsewhere.

Two caveats, neither obvious:

- mDNS does not cross a tailnet, so nothing auto-discovers. The port still has
  to be read off the phone whenever wireless debugging restarts.
- With the mDNS transport also live, one physical phone appears twice in
  `adb devices`, which breaks any bare `adb` command until one is dropped or
  `-s` is passed.

A first attempt with a stale port returned "actively refused" rather than a
timeout - a TCP reset from the phone, which was already proof the tailnet
route worked and only the port was wrong.

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
- **Wireless debugging over Tailscale** superseded mDNS on 8 September; see
  that entry above.

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
- **rejectSigma is a constant where it should be a function of noise.** 0.10
  loses more than half the available averaging at ISO 1047; 0.40 recovers it
  but is untested against anything that moves.
- `recommendedStackDepth()` returns 12 for every size this camera offers; the
  clamp binds, never the RAM budget. 214 MB of native buffers on a 3.4 GB phone
  is ungoverned.
- **Document capture was raised as a product direction** and is recorded in the
  handover. It would promote optical refinement to a requirement, because a
  gyroscope cannot see the translation that dominates at page distance.

### Phase 3 executed, against a saved burst

The GPU path had never run. It does now, driven from disk rather than a live
camera - `StackRenderer` took a Camera2 `Image` directly, which quietly made
the merge untestable, since an `Image` comes from an `ImageReader` and cannot
be built from a file. Behind a `YuvSource` interface a saved burst feeds the
same shaders as a live frame.

Triggered by intent, because synthetic input is blocked on this handset:

```
adb shell am start -n dev.alfieprojects.stablestill/.ui.MainActivity \
    --ez autoReplay true --es rejectSigma 0.40
```

Device and JVM agree exactly - anchor 1, worst shift 43.1 px - which is a
useful cross-check that `:core` and `:app` are solving the same problem.

**The output was upside down.** Two vertical flips where one was needed: the
vertex shader gives `vUv.y = 0` at the framebuffer's *bottom*, so it writes
source row 0 there, and `glReadPixels` reads bottom-first into a Bitmap that
fills top-first. Those cancel; the explicit flip undid the correction again.
Measured against its own anchor frame the output correlated **0.9885 flipped,
0.0069 unflipped** - a stack of an ordinary room hides this almost perfectly
by eye, which is why it was worth measuring rather than looking.

**`rejectSigma = 0.10` is far too tight at ISO 1047.** Noise in flat tiles,
source 9.87:

| sigma | 0.10 | 0.40 |
|---|---|---|
| stacked noise | 5.85 | 3.29 |
| reduction | 1.69x | 3.0x |

At 0.10, eight frames bought 2.9 frames' worth of averaging. The default is
deliberately unchanged: a static room cannot ghost, so this says nothing about
the moving-subject case the rejection exists for. The fix is a sigma that
scales with measured noise, not a different constant.

### Session ended here

Stopped 5 September 2026, late evening. All phases that exist now run end to
end: probe, capture, archive, JVM replay, GPU merge.

Next session starts with **making `rejectSigma` scale with measured noise**.
The data needed is already in the archive - ISO travels in the manifest as of
format version 2 - and `BurstReplayer` can be pointed at a saved burst with a
sigma on the command line, so the change can be measured rather than guessed.

