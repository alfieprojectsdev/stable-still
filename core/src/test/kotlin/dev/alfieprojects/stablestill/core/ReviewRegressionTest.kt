package dev.alfieprojects.stablestill.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Bugs a code review found in this module, kept fixed.
 *
 * Grouped by the defect rather than by the class, because what they have in
 * common is how they hid: every one of them produced a plausible answer. A
 * half-scaled correction still beats no correction, a wrong handedness scored
 * on the frames it did not ruin still looks convincing, a worker thread that
 * dies still lets `join` return. None of them would have been noticed by
 * looking at the output.
 */
class ReviewRegressionTest {

    // ---------------------------------------------------------------- reporting

    @Test
    fun `comparing frame rates prints numbers rather than its own format string`() {
        // `"..." + "...".format(x)` binds the format to the last fragment alone.
        // The burst-span line therefore printed a literal "%.0f ms against
        // %.0f ms" and filled the percentage from the span - in the one output
        // this whole comparison exists to produce.
        val slower = row(name = "slow", megapixels = 12.5, fps = 20.0, spanMs = 350.0)
        val faster = row(name = "fast", megapixels = 8.0, fps = 30.0, spanMs = 233.0)
        val text = BurstAudit.compareRates(slower, faster)

        assertFalse("Unformatted placeholder in:\n$text", text.contains("%."))
        assertTrue(text, text.contains("350 ms against 233 ms"))
        // 1 - 233/350 = 33%, and emphatically not 350%.
        assertTrue(text, text.contains("33% less time"))
        assertFalse("Span leaked into the percentage:\n$text", text.contains("350% less"))
    }

    @Test
    fun `a burst name carrying a percent sign does not break the report`() {
        val slower = row(name = "odd%name", megapixels = 12.5, fps = 20.0, spanMs = 350.0)
        val faster = row(name = "fast", megapixels = 8.0, fps = 30.0, spanMs = 233.0)
        // Interpolating the name into a format string would hand `format` a
        // stray conversion and throw. Directory names are the source here, so
        // this is a real input, not a hypothetical one.
        assertTrue(BurstAudit.compareRates(slower, faster).contains("odd%name"))
    }

    @Test
    fun `a motionless anchor does not put ten to the three hundred in the median`() {
        // The sentinel for "infinitely steadier than average" was MAX_VALUE,
        // which is *finite*, so the isFinite filter written to drop it kept it
        // and 1.79e308 went into a reported median. Driven through `audit` here
        // rather than by handing `summarise` a value, because the defect was the
        // sentinel the audit chose, not the filter that failed to catch it.
        val still = BurstAudit.audit("still", motionlessBurst())
        assertFalse(
            "A motionless anchor should be infinitely steadier, not 1.79e308",
            still.anchorAdvantage.isFinite(),
        )

        val summary = BurstAudit.summarise(listOf(still, row(name = "ordinary")))
        assertFalse("Sentinel reached the summary:\n$summary", summary.contains("E308"))
        assertTrue(summary, summary.contains("advantage 2.00x"))
        // And the table keeps its columns rather than printing "Infinity".
        assertTrue(BurstAudit.format(listOf(still)).contains(" inf "))
    }

    /** A burst whose gyro never moved, so every steadiness score is exactly zero. */
    private fun motionlessBurst(): BurstArchiveContents {
        val width = 64
        val height = 48
        val records = (0 until 4).map { i ->
            BurstFrameRecord(
                index = i,
                fileName = BurstArchive.frameFileName(i),
                sensorTimestampNanos = START + i * 50L * Synthetic.MS,
                exposureTimeNanos = 20L * Synthetic.MS,
                rollingShutterSkewNanos = 0L,
                width = width,
                height = height,
                sensitivityIso = 100,
            )
        }
        return BurstArchiveContents(
            manifest = BurstManifest(
                capturedAtEpochMillis = 0L,
                deviceModel = "synthetic",
                androidSdkInt = 36,
                cameraId = "0",
                width = width,
                height = height,
                intrinsics = CameraIntrinsics(
                    60.0, 60.0, width / 2.0, height / 2.0, width, height,
                ),
                rig = RigAlignment(sensorOrientationDegrees = 90),
                shutterNanos = START,
                gravity = Vec3(0.0, 9.81, 0.0),
                frameCount = records.size,
                gyroSampleCount = 0,
            ),
            frames = records,
            gyro = Synthetic.constantRate(START, 300, 400, Vec3.ZERO),
        )
    }

    // -------------------------------------------------------------- refinement

    // The `continue` that used to step over the inter-level rescale has no test,
    // because after building one it turned out the bug could not be reached.
    // `sampleGrid` bounds a tap by `1.5` and `width - 2.5` in *level* units,
    // which in full-size terms tighten as the level coarsens, so an empty level
    // implies every coarser level is empty too. Empty levels therefore all
    // precede the populated ones, and the estimate carried across a skipped
    // rescale is still zero. The loop was restructured anyway - the reasoning
    // lives in the bounds, not in the loop, and the next change to either could
    // make it false - but a test asserting a reachable failure would be
    // asserting a fiction.

    @Test
    fun `convergence is reported for the level the answer is in`() {
        // `converged` was set by any level and never cleared, so a coarse level
        // settling made the whole refinement claim convergence even when the
        // finest level ran out of iterations - and the finest level is the only
        // one whose units the answer is in.
        //
        // Strong near-Nyquist texture is what separates them. It survives at
        // full size and is averaged away by the pyramid, so the coarse levels
        // settle cleanly on the smooth structure while the finest is still
        // hunting inside the fine detail when its budget runs out.
        val result = OpticalRefinement.refine(
            frameIndex = 1,
            anchor = hunting(0.0),
            anchorSampling = shiftMatrix(0.0),
            frame = hunting(3.0),
            frameSampling = shiftMatrix(0.0),
            crop = CropWindow(RAMP_W, RAMP_H, 0.125),
            options = RefinementOptions(iterationsPerLevel = 2),
        )
        // It lands close, so the estimate is usable - but it did not settle, and
        // saying otherwise invites a caller to trust a digit that is not there.
        assertEquals(3.0, result.translationX, 0.2)
        assertTrue(
            "The finest level should not have settled exactly",
            kotlin.math.abs(result.translationX - 3.0) > 1e-6,
        )
        assertFalse(
            "Claimed the finest level converged when a coarse one did",
            result.converged,
        )
    }

    // ------------------------------------------------------------- handedness

    @Test
    fun `no frame surviving both signs is reported as no evidence`() {
        // With nothing comparable, both means were MAX_VALUE, the margin
        // short-circuited to 1.0 and the verdict came back decisive - a
        // confident sign from zero evidence, which would then have travelled in
        // the manifest of every burst captured afterwards.
        val metas = Synthetic.frames(
            startNanos = START, count = 4, intervalMs = 50,
            width = RIG_W, height = RIG_H,
        )
        val track = MotionTrack.integrate(
            Synthetic.gyro(START, 200, 400) { t -> Vec3(6.0 * sin(2 * PI * 2.0 * t), 0.0, 0.0) }
        )
        val flat = Synthetic.greyFrame(RIG_W, RIG_H) { _, _ -> 120 }.luma()

        val verdict = RigCalibration.settleHandedness(
            frames = metas,
            track = track,
            intrinsics = rigIntrinsics,
            rig = RigAlignment(sensorOrientationDegrees = 90, handedness = 1),
            // A margin this thin drops every frame under both hypotheses.
            crop = CropWindow(RIG_W, RIG_H, 0.0),
        ) { flat }

        assertEquals(0, verdict.comparedCount)
        assertFalse(RigCalibration.format(verdict), verdict.decisive)
        assertTrue(
            RigCalibration.format(verdict),
            RigCalibration.format(verdict).contains("no frame survived under both signs"),
        )
    }

    @Test
    fun `both signs are scored on the same frames`() {
        // Each hypothesis used to be averaged over whatever it individually
        // kept. A wrong sign that throws the high-motion frames off the sensor
        // would then be judged only on the calm ones it kept - the frames it
        // gets most nearly right - and could win a comparison it deserved to
        // lose.
        //
        // These numbers are not decorative: a tight margin and this particular
        // tremor are a case where the signs genuinely disagree about which
        // frames fit, keeping {5} against {5, 7}. Most bursts keep everything
        // under both signs, where the bug cannot show itself at all.
        val metas = Synthetic.frames(
            startNanos = START, count = 8, intervalMs = 40,
            width = RIG_W, height = RIG_H,
        )
        val track = MotionTrack.integrate(
            Synthetic.gyro(START, 300, 400) { t ->
                Vec3(3.1 * sin(2 * PI * 2.0 * t), 3.1 * 0.7 * sin(2 * PI * 1.5 * t + 1.0), 0.0)
            }
        )
        val plane = Synthetic.greyFrame(RIG_W, RIG_H) { x, y ->
            (128.0 + 40.0 * sin(2 * PI * x / 37.0) + 30.0 * sin(2 * PI * y / 29.0)).roundToInt()
        }.luma()

        val verdict = RigCalibration.settleHandedness(
            frames = metas,
            track = track,
            intrinsics = rigIntrinsics,
            rig = RigAlignment(sensorOrientationDegrees = 90, handedness = 1),
            crop = CropWindow(RIG_W, RIG_H, 0.05),
        ) { plane }

        val scored = verdict.scores.map { it.perFrame.keys.sorted() }
        assertEquals("The two signs were scored on different frames", scored[0], scored[1])
        assertEquals(scored[0].size, verdict.comparedCount)

        // The asymmetry is still visible, just not buried inside the means: one
        // sign really does keep a frame the other cannot.
        assertTrue(
            "This burst is meant to be one where the signs disagree about fit",
            verdict.scores[0].usableCount != verdict.scores[1].usableCount,
        )
    }

    // ------------------------------------------------------------------ merge

    @Test
    fun `the anchor frame is fetched once, not twice`() {
        // The callback exists so a caller can stream a 12.5 MP burst one frame
        // at a time. Asking for the anchor again during accumulation quietly
        // re-read 17.9 MB, against a documented promise that it would not.
        val frames = (0 until 3).map {
            Synthetic.greyFrame(64, 48) { x, y -> (2 * x + y) % 256 }
        }
        val requests = mutableListOf<Int>()
        val plan = Synthetic.translationPlan(
            CropWindow(64, 48, 0.125), anchorIndex = 0, shifts = List(3) { 0.0 to 0.0 },
        )
        ReferenceMerge.merge(plan, 0.15f) { index ->
            requests += index
            frames[index]
        }
        assertEquals("Each frame should be asked for once", requests.size, requests.toSet().size)
    }

    // A worker thread throwing is fixed but deliberately not tested here.
    //
    // `forEachRow` used to let a failed worker die quietly - `join` returned, the
    // accumulator kept whatever that row band had managed, and the merge returned
    // a picture with a stripe missing and an effectiveFrameCount that reported
    // the loss as honest rejection. It now keeps the first Throwable and rethrows
    // it once every worker has stopped.
    //
    // Injecting that failure needs a YuvFrame that throws from `sampleRgb`, which
    // means opening the class and making that method virtual. It is called once
    // per output pixel per frame - seventy million times for an eight-frame
    // 12.5 MP burst - and the reason this module exists is to sweep thresholds in
    // seconds. A test seam is not worth a bimorphic call site in that loop, so
    // the guard was verified by hand against a temporarily opened class and the
    // seam was not kept.

    // ------------------------------------------------------------------ fixtures

    private val RAMP_W = 512
    private val RAMP_H = 384
    private val RIG_W = 384
    private val RIG_H = 288
    private val START = 1_000_000_000L

    private val rigIntrinsics = CameraIntrinsics(
        fx = 400.0, fy = 400.0, cx = RIG_W / 2.0, cy = RIG_H / 2.0,
        width = RIG_W, height = RIG_H,
    )

    /** Smooth structure the pyramid can settle on, plus detail at the finest scale. */
    private fun hunting(shift: Double): LumaPlane = Synthetic.greyFrame(RAMP_W, RAMP_H) { x, y ->
        (128.0 + 30.0 * sin(2 * PI * (x - shift) / 89.0) +
            20.0 * sin(2 * PI * y / 53.0) +
            20.0 * sin(2 * PI * (x - shift) / 2.3)).roundToInt()
    }.luma()

    private fun shiftMatrix(extra: Double) = Mat3(
        1.0, 0.0, RAMP_W * 0.125 + extra,
        0.0, 1.0, RAMP_H * 0.125,
        0.0, 0.0, 1.0,
    )

    private fun row(
        name: String = "burst",
        megapixels: Double = 12.5,
        fps: Double = 20.0,
        spanMs: Double = 350.0,
        anchorAdvantage: Double = 2.0,
    ) = BurstAuditRow(
        name = name,
        frameCount = 8,
        usableCount = 8,
        width = 4080,
        height = 3060,
        megapixels = megapixels,
        isoRange = 1047..1047,
        exposureMs = 20.0,
        intervalMs = 1000.0 / fps,
        fps = fps,
        spanMs = spanMs,
        maxCornerShiftPx = 43.0,
        maxRotationMrad = 10.4,
        rotationBudgetMrad = 117.9,
        marginUsed = 0.117,
        minimumSafeMargin = 0.011,
        anchorIndex = 1,
        anchorIsFirstFrame = false,
        anchorSteadiness = 0.021,
        meanSteadiness = 0.044,
        worstSteadiness = 0.089,
        anchorAdvantage = anchorAdvantage,
        anchorBlurPx = 1.3,
        meanBlurPx = 2.8,
        worstBlurPx = 5.5,
        gyroCoversFrames = true,
        gyroRateHz = 402.7,
    )
}
