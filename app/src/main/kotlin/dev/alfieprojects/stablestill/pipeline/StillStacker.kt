package dev.alfieprojects.stablestill.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import dev.alfieprojects.stablestill.capture.CaptureEngine
import dev.alfieprojects.stablestill.capture.CapturedFrame
import dev.alfieprojects.stablestill.capture.lumaPlane
import dev.alfieprojects.stablestill.core.AlignmentPlan
import dev.alfieprojects.stablestill.core.BurstAligner
import dev.alfieprojects.stablestill.core.CropWindow
import dev.alfieprojects.stablestill.core.GyroBias
import dev.alfieprojects.stablestill.core.HorizonLock
import dev.alfieprojects.stablestill.core.Mat3
import dev.alfieprojects.stablestill.core.MotionTrack
import dev.alfieprojects.stablestill.core.NoiseModel
import dev.alfieprojects.stablestill.core.OpticalRefinement
import dev.alfieprojects.stablestill.core.RefinementResult
import dev.alfieprojects.stablestill.core.SyncCalibration
import dev.alfieprojects.stablestill.gl.EglCore
import dev.alfieprojects.stablestill.gl.ImageYuvSource
import dev.alfieprojects.stablestill.gl.RenderFrame
import dev.alfieprojects.stablestill.gl.StackRenderer
import dev.alfieprojects.stablestill.motion.GyroRecorder
import dev.alfieprojects.stablestill.probe.GyroGrade
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tunables the UI exposes. Defaults chosen for a no-OIS budget phone. */
data class StackSettings(
    val stackDepth: Int = 8,
    /** 12% per side. Slightly more than a GoPro uses, because we have no OIS to help. */
    val cropMarginFraction: Double = 0.12,
    val horizonLock: Boolean = false,
    /** Null derives the threshold from the anchor's measured noise, which is the default for a reason: see [NoiseModel]. */
    val rejectSigma: Float? = null,
    /** Correct the gyro plan against the pixels before merging. Off only to compare. */
    val refine: Boolean = true,
    val jpegQuality: Int = 95,
)

/** What happened, in enough detail to explain a disappointing result. */
data class StackResult(
    val file: File,
    val framesRequested: Int,
    val framesCaptured: Int,
    val framesMerged: Int,
    val anchorIndex: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val maxCornerShiftPx: Double,
    val horizonTiltDegrees: Double,
    val elapsedMillis: Long,
    val notes: List<String>,
    val rejectSigma: Float,
    val measuredNoise: Double,
    /** Per-frame refinement evidence; empty when refinement was off. */
    val refinement: List<RefinementResult> = emptyList(),
)

/**
 * Orchestrates one shutter press: slice the buffers, solve the motion, warp,
 * merge, save.
 *
 * Every stage degrades rather than fails. No gyro means an identity plan and a
 * straight average; a burst where most frames moved too far still returns the
 * anchor. A camera app that returns no photo is worse than one that returns a
 * mediocre photo.
 */
class StillStacker(
    private val context: Context,
    private val captureEngine: CaptureEngine,
    private val gyroRecorder: GyroRecorder,
    private val gyroGrade: GyroGrade,
) {
    companion object {
        private const val TAG = "StillStacker"
    }

    /** Solved once per device and then reused; see [SyncCalibration]. */
    var syncCalibration: SyncCalibration = SyncCalibration.IDENTITY

    fun capture(settings: StackSettings, shutterNanos: Long): StackResult {
        val started = System.currentTimeMillis()
        val notes = mutableListOf<String>()

        val frames = captureEngine.takeBurst(settings.stackDepth, shutterNanos)
        check(frames.isNotEmpty()) { "The ring buffer was empty - is the camera running?" }
        if (frames.size < settings.stackDepth) {
            notes += "Only ${frames.size} of ${settings.stackDepth} frames were buffered."
        }

        try {
            val intrinsics = captureEngine.intrinsics
                ?: error("Camera intrinsics are unavailable")
            val rig = captureEngine.rig ?: error("Rig alignment is unavailable")

            val first = frames.minOf { it.meta.sensorTimestampNanos }
            val last = frames.maxOf {
                it.meta.sensorTimestampNanos + it.meta.exposureTimeNanos + it.meta.rollingShutterSkewNanos
            }

            val useGyro = gyroGrade.supportsGyroWarp && gyroRecorder.hasGyroscope
            val samples = if (useGyro) {
                gyroRecorder.slice(
                    syncCalibration.toGyroClock(first),
                    syncCalibration.toGyroClock(last),
                )
            } else {
                emptyList()
            }

            if (useGyro && samples.size < 4) {
                notes += "Gyro history did not cover the burst; frames were merged unaligned."
            }
            if (!useGyro) {
                notes += "Gyro unusable on this device (${gyroGrade.name}); frames merged unaligned."
            }

            val track = MotionTrack.integrate(samples, GyroBias.NONE)
            val crop = CropWindow(
                sourceWidth = frames.first().meta.width,
                sourceHeight = frames.first().meta.height,
                marginFraction = settings.cropMarginFraction,
            )

            val gravity = gyroRecorder.gravity()
            val tilt = HorizonLock.tiltRadians(gravity, rig)
            val extraRotation = if (settings.horizonLock) {
                HorizonLock.correction(gravity, rig)
            } else {
                Mat3.IDENTITY
            }

            val gyroPlan: AlignmentPlan = BurstAligner.plan(
                frames = frames.map { it.meta },
                track = track,
                intrinsics = intrinsics,
                rig = rig,
                crop = crop,
                sync = syncCalibration,
                extraRotation = extraRotation,
            )

            // The gyro sees rotation only; at close range what it leaves is
            // parallax, and that residual sets the merge's ceiling. Corrected
            // before the threshold is chosen. The anchor's luma is held for the
            // noise measurement; the others are copied out of their Image one at
            // a time and dropped, rather than held alongside the buffers the
            // ring already owns.
            val byIndex = frames.associateBy { it.meta.index }
            val anchorLuma = byIndex.getValue(gyroPlan.anchorIndex).image.lumaPlane()
            val refined = if (settings.refine) {
                OpticalRefinement.refinePlan(gyroPlan) { index ->
                    if (index == gyroPlan.anchorIndex) anchorLuma
                    else byIndex.getValue(index).image.lumaPlane()
                }
            } else null
            val plan = refined?.plan ?: gyroPlan
            refined?.results?.values?.forEach {
                Log.i(
                    TAG,
                    "refine frame ${it.frameIndex}: shift=${"%.2f".format(it.shiftPx)}px " +
                        "residual ${"%.4f".format(it.residualBefore)} -> ${"%.4f".format(it.residualAfter)} " +
                        "(${"%.2f".format(it.improvement)}x) converged=${it.converged}",
                )
            }
            if (refined != null && refined.plan.usableCount < gyroPlan.usableCount) {
                notes += "${gyroPlan.usableCount - refined.plan.usableCount} frame(s) left the sensor " +
                    "after refinement and were dropped."
            }

            val measuredNoise = NoiseModel.estimateNoise(anchorLuma)
            val sigma = settings.rejectSigma ?: NoiseModel.sigmaFor(measuredNoise)

            if (plan.usableCount < frames.size) {
                notes += "${frames.size - plan.usableCount} frame(s) moved beyond the crop margin " +
                    "and were dropped."
            }

            val bitmap = renderStack(frames, plan, sigma)
            val file = writeJpeg(bitmap, settings.jpegQuality)
            bitmap.recycle()

            return StackResult(
                file = file,
                framesRequested = settings.stackDepth,
                framesCaptured = frames.size,
                framesMerged = plan.usableCount,
                anchorIndex = plan.anchorIndex,
                outputWidth = crop.outputWidth,
                outputHeight = crop.outputHeight,
                maxCornerShiftPx = plan.alignments.maxOfOrNull { it.maxCornerShiftPx } ?: 0.0,
                horizonTiltDegrees = Math.toDegrees(tilt),
                elapsedMillis = System.currentTimeMillis() - started,
                notes = notes,
                rejectSigma = sigma,
                measuredNoise = measuredNoise,
                refinement = refined?.results?.values?.toList() ?: emptyList(),
            )
        } finally {
            // The ring buffer handed us ownership; the ImageReader pool stalls
            // permanently if we do not give these slots back.
            frames.forEach { it.close() }
        }
    }

    private fun renderStack(
        frames: List<CapturedFrame>,
        plan: AlignmentPlan,
        rejectSigma: Float,
    ): Bitmap {
        EglCore().use { egl ->
            egl.setup()
            if (!egl.supportsFloatColorBuffer) {
                Log.w(TAG, "No float colour buffer; accumulating at 8-bit precision")
            }
            val renderer = StackRenderer(
                outputWidth = plan.crop.outputWidth,
                outputHeight = plan.crop.outputHeight,
                useFloatAccumulation = egl.supportsFloatColorBuffer,
            )
            renderer.rejectSigma = rejectSigma
            return renderer.use {
                it.setup()
                it.render(
                    frames.map { f -> RenderFrame(f.meta.index, ImageYuvSource(f.image)) },
                    plan,
                )
            }
        }
    }

    private fun writeJpeg(bitmap: Bitmap, quality: Int): File {
        val dir = File(context.getExternalFilesDir(null), "captures").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val file = File(dir, "stable-still-$stamp.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        return file
    }
}
