package dev.alfieprojects.stablestill.core

import kotlin.math.abs

/**
 * Everything a burst can be asked without opening its pixels.
 *
 * Which is nearly everything worth asking. Corner shift, rotation, crop
 * utilisation, anchor choice and motion blur all fall out of timestamps and
 * angular velocity, so an audit runs on an archive whose frame files were never
 * copied off the phone - and on twenty-two of them in the time it takes to read
 * their CSVs.
 */
data class BurstAuditRow(
    val name: String,
    val frameCount: Int,
    val usableCount: Int,
    val width: Int,
    val height: Int,
    val megapixels: Double,
    val isoRange: IntRange,
    val exposureMs: Double,
    /** Median interval between frames, and the frame rate it implies. */
    val intervalMs: Double,
    val fps: Double,
    val spanMs: Double,

    // --- the crop question
    val maxCornerShiftPx: Double,
    val maxRotationMrad: Double,
    val rotationBudgetMrad: Double,
    /** Fraction of the crop's slack the worst frame actually used. */
    val marginUsed: Double,
    /**
     * The smallest crop margin that would still have kept every frame.
     *
     * The number the 12% question turns on. A margin is headroom for motion that
     * has not happened yet, so the honest way to size it is the worst burst ever
     * captured, not the average one - but knowing what each burst *needed*
     * turns that from an argument into a maximum.
     */
    val minimumSafeMargin: Double?,

    // --- the anchor question
    val anchorIndex: Int,
    val anchorIsFirstFrame: Boolean,
    val anchorSteadiness: Double,
    val meanSteadiness: Double,
    val worstSteadiness: Double,
    /** How much steadier the anchor is than the average frame. 1.0 means no better. */
    val anchorAdvantage: Double,

    // --- the exposure question
    /** Motion blur smeared across the anchor's own exposure, in pixels. */
    val anchorBlurPx: Double,
    val meanBlurPx: Double,
    val worstBlurPx: Double,

    val gyroCoversFrames: Boolean,
    val gyroRateHz: Double,
) {
    /** Blur the anchor choice avoided, as a fraction of the average frame's. */
    val blurSaved: Double get() = if (meanBlurPx <= 0.0) 0.0 else 1.0 - anchorBlurPx / meanBlurPx
}

/**
 * Answers the standing questions about a burst from its trace alone.
 *
 * Three of them, and they are the same arithmetic asked three ways:
 *
 * - **Is the 12% crop margin far too generous?** [BurstAuditRow.minimumSafeMargin]
 *   says what each burst actually needed.
 * - **Is the chosen anchor a real choice, or frame 0 by default?**
 *   [BurstAuditRow.anchorAdvantage] says how much steadier it is than average,
 *   and [BurstAuditRow.anchorIsFirstFrame] says how often it happens to be first.
 * - **What does a shorter burst buy?** Span, interval and blur are all here, so
 *   a 20 fps burst and a 30 fps one of the same scene can be put side by side.
 */
object BurstAudit {

    /** Widest margin worth considering; [CropWindow] refuses 0.45 and above. */
    private const val MARGIN_CEILING = 0.44

    fun audit(
        name: String,
        contents: BurstArchiveContents,
        cropMarginFraction: Double = 0.12,
        sync: SyncCalibration = SyncCalibration.IDENTITY,
    ): BurstAuditRow {
        val manifest = contents.manifest
        val metas = contents.frames.map { it.toMeta() }
        val track = MotionTrack.integrate(contents.gyro)
        val crop = CropWindow(manifest.width, manifest.height, cropMarginFraction)
        val anchorIndex = AnchorSelector.select(metas, track, sync)
        val plan = BurstAligner.plan(
            frames = metas,
            track = track,
            intrinsics = manifest.intrinsics,
            rig = manifest.rig,
            crop = crop,
            sync = sync,
            anchorIndex = anchorIndex,
        )

        val scores = AnchorSelector.scores(metas, track, sync)
        val anchorScore = scores.getValue(anchorIndex)
        val meanScore = scores.values.average()

        val focal = minOf(manifest.intrinsics.fx, manifest.intrinsics.fy)
        val blur = metas.associate { it.index to blurPx(it, track, sync, focal) }

        val intervals = metas.map { it.sensorTimestampNanos }.sorted()
            .zipWithNext { a, b -> (b - a) / 1e6 }
        val medianInterval = if (intervals.isEmpty()) 0.0 else intervals.sorted().let {
            it[it.size / 2]
        }

        val gyroSpanNanos = if (contents.gyro.size < 2) 0L
        else contents.gyro.last().timestampNanos - contents.gyro.first().timestampNanos

        val budget = crop.rotationBudgetRadians(manifest.intrinsics)
        val worstShift = plan.alignments.maxOf { it.maxCornerShiftPx }
        val worstRotation = plan.alignments.maxOf { it.rotationRadians }

        return BurstAuditRow(
            name = name,
            frameCount = metas.size,
            usableCount = plan.usableCount,
            width = manifest.width,
            height = manifest.height,
            megapixels = manifest.width.toDouble() * manifest.height / 1e6,
            isoRange = (contents.frames.minOfOrNull { it.sensitivityIso } ?: 0)..
                (contents.frames.maxOfOrNull { it.sensitivityIso } ?: 0),
            exposureMs = metas.map { it.exposureTimeNanos }.average() / 1e6,
            intervalMs = medianInterval,
            fps = if (medianInterval > 0.0) 1000.0 / medianInterval else 0.0,
            spanMs = contents.frameSpanNanos / 1e6,
            maxCornerShiftPx = worstShift,
            maxRotationMrad = worstRotation * 1000.0,
            rotationBudgetMrad = budget * 1000.0,
            marginUsed = if (crop.offsetX <= 0.0) 0.0
            else worstShift / minOf(crop.offsetX, crop.offsetY),
            minimumSafeMargin = minimumSafeMargin(
                metas, track, manifest, sync, anchorIndex,
            ),
            anchorIndex = anchorIndex,
            anchorIsFirstFrame = anchorIndex == metas.minOf { it.index },
            anchorSteadiness = anchorScore,
            meanSteadiness = meanScore,
            worstSteadiness = scores.values.max(),
            anchorAdvantage = if (anchorScore <= 0.0) Double.MAX_VALUE else meanScore / anchorScore,
            anchorBlurPx = blur.getValue(anchorIndex),
            meanBlurPx = blur.values.average(),
            worstBlurPx = blur.values.max(),
            gyroCoversFrames = contents.gyroCoversFrames(),
            gyroRateHz = if (gyroSpanNanos <= 0L) 0.0
            else (contents.gyro.size - 1) * 1e9 / gyroSpanNanos,
        )
    }

    /**
     * Motion blur baked into one frame's exposure, in pixels.
     *
     * Mean angular rate across the frame's own exposure window, times the
     * exposure, times the focal length. This is the quantity the anchor choice
     * exists to minimise, and the one a shorter exposure buys down - it cannot
     * be removed by any amount of alignment afterwards, because it happened
     * while the shutter was open.
     */
    fun blurPx(
        meta: FrameMeta,
        track: MotionTrack,
        sync: SyncCalibration,
        focalLengthPx: Double,
    ): Double {
        val start = sync.toGyroClock(meta.sensorTimestampNanos)
        val end = sync.toGyroClock(
            meta.sensorTimestampNanos + meta.exposureTimeNanos + meta.rollingShutterSkewNanos
        )
        val rate = track.meanAngularSpeed(start, end)
        return rate * (meta.exposureTimeNanos / 1e9) * focalLengthPx
    }

    /**
     * The smallest crop margin at which every frame still lands on the sensor.
     *
     * Bisected rather than solved: usability is monotonic in the margin - slack
     * only ever helps - but it is decided by four corners through a homography,
     * not by a formula worth inverting. Null when no margin saves the burst,
     * which means a frame rotated past the plan's limit rather than off the edge.
     */
    fun minimumSafeMargin(
        metas: List<FrameMeta>,
        track: MotionTrack,
        manifest: BurstManifest,
        sync: SyncCalibration = SyncCalibration.IDENTITY,
        anchorIndex: Int = AnchorSelector.select(metas, track, sync),
        tolerance: Double = 1e-4,
    ): Double? {
        fun allUsable(margin: Double): Boolean = BurstAligner.plan(
            frames = metas,
            track = track,
            intrinsics = manifest.intrinsics,
            rig = manifest.rig,
            crop = CropWindow(manifest.width, manifest.height, margin),
            sync = sync,
            anchorIndex = anchorIndex,
        ).usableCount == metas.size

        if (!allUsable(MARGIN_CEILING)) return null
        if (allUsable(0.0)) return 0.0

        var lo = 0.0
        var hi = MARGIN_CEILING
        while (hi - lo > tolerance) {
            val mid = (lo + hi) / 2.0
            if (allUsable(mid)) hi = mid else lo = mid
        }
        return hi
    }

    /**
     * Side-by-side lines for a table.
     *
     * Deliberately one line per burst: twenty-two of them should fit on a screen,
     * because the questions here are all "which burst is the outlier".
     */
    fun format(rows: List<BurstAuditRow>): String = buildString {
        appendLine(
            "name                       MP   fps  exp    span  shift  rot/budget  used  " +
                "minMargin  anc  adv   blur"
        )
        for (r in rows) {
            appendLine(
                "%-24s %4.1f %5.1f %5.1f %6.0f %6.0f  %4.1f/%-5.1f %5.1f%%  %8s  %3d %4.2f %5.1f"
                    .format(
                        r.name.take(24),
                        r.megapixels,
                        r.fps,
                        r.exposureMs,
                        r.spanMs,
                        r.maxCornerShiftPx,
                        r.maxRotationMrad,
                        r.rotationBudgetMrad,
                        r.marginUsed * 100.0,
                        r.minimumSafeMargin?.let { "%.3f".format(it) } ?: "none",
                        r.anchorIndex,
                        r.anchorAdvantage,
                        r.anchorBlurPx,
                    )
            )
        }
    }

    /**
     * What the whole collection says, which is what the crop question wants.
     *
     * A margin sized from the mean burst is a margin that fails on the worst
     * one, so the summary reports the maximum and names the burst that set it.
     */
    fun summarise(rows: List<BurstAuditRow>): String {
        if (rows.isEmpty()) return "No bursts audited."
        val needed = rows.mapNotNull { row -> row.minimumSafeMargin?.let { row to it } }
        val worst = needed.maxByOrNull { it.second }
        val anchorFirst = rows.count { it.anchorIsFirstFrame }
        val advantage = rows.map { it.anchorAdvantage }.filter { it.isFinite() }
        return buildString {
            appendLine("${rows.size} bursts.")
            if (worst != null) {
                appendLine(
                    "Crop: the hungriest burst needed %.1f%% per side (%s); ".format(
                        worst.second * 100.0, worst.first.name,
                    ) + "%d of %d fitted inside 12%%.".format(
                        rows.count { (it.minimumSafeMargin ?: 1.0) <= 0.12 }, rows.size,
                    )
                )
            }
            appendLine(
                "Anchor: frame 0 chosen in $anchorFirst of ${rows.size} bursts; " +
                    "median steadiness advantage %.2fx.".format(
                        advantage.sorted().let {
                            if (it.isEmpty()) 0.0 else it[it.size / 2]
                        }
                    )
            )
            val blur = rows.map { it.anchorBlurPx }
            appendLine(
                "Blur at the anchor: %.1f to %.1f px, mean %.1f.".format(
                    blur.min(), blur.max(), blur.average(),
                )
            )
            val uncovered = rows.filter { !it.gyroCoversFrames }
            if (uncovered.isNotEmpty()) {
                appendLine(
                    "WARNING: gyro trace does not bracket every frame in " +
                        uncovered.joinToString(", ") { it.name } +
                        " - the outermost frames clamp, silently."
                )
            }
        }
    }

    /**
     * The 20-vs-30 fps trade, for two bursts of the same scene.
     *
     * Deliberately not a verdict. The two sides are measured in different
     * currencies - pixels against milliseconds - and which one wins depends on
     * what the picture is for, so this states both and leaves the choice where
     * it belongs.
     */
    fun compareRates(slower: BurstAuditRow, faster: BurstAuditRow): String = buildString {
        require(faster.fps >= slower.fps) { "compareRates takes the slower burst first" }
        appendLine("${slower.name} (%.0f fps) against ${faster.name} (%.0f fps)".format(
            slower.fps, faster.fps,
        ))
        appendLine(
            "  Resolution: %.1f MP against %.1f MP - %+.0f%%".format(
                slower.megapixels, faster.megapixels,
                (faster.megapixels / slower.megapixels - 1.0) * 100.0,
            )
        )
        appendLine(
            "  Burst span: %.0f ms against %.0f ms - the faster burst gives tremor " +
                "%.0f%% less time to accumulate".format(
                    slower.spanMs, faster.spanMs,
                    (1.0 - faster.spanMs / slower.spanMs) * 100.0,
                )
        )
        appendLine(
            "  Corner shift: %.0f px against %.0f px, needing %s against %s of margin".format(
                slower.maxCornerShiftPx, faster.maxCornerShiftPx,
                slower.minimumSafeMargin?.let { "%.1f%%".format(it * 100) } ?: "more than 44%",
                faster.minimumSafeMargin?.let { "%.1f%%".format(it * 100) } ?: "more than 44%",
            )
        )
        appendLine(
            "  Blur in the anchor: %.1f px against %.1f px".format(
                slower.anchorBlurPx, faster.anchorBlurPx,
            )
        )
        appendLine(
            "  ISO: ${slower.isoRange} against ${faster.isoRange}"
        )
        val pixelGain = faster.megapixels / slower.megapixels
        val spanGain = slower.spanMs / faster.spanMs
        appendLine(
            if (abs(pixelGain - 1.0) < 0.05)
                "  Same sensor mode, so the shorter span is free. Prefer the faster rate."
            else
                "  The trade is %.2fx the pixels against %.2fx the settling time.".format(
                    1.0 / pixelGain, spanGain,
                )
        )
    }
}
