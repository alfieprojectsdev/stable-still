package dev.alfieprojects.stablestill.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import dev.alfieprojects.stablestill.capture.CaptureEngine
import dev.alfieprojects.stablestill.capture.CapturedFrame
import dev.alfieprojects.stablestill.core.AlignmentPlan
import dev.alfieprojects.stablestill.core.BurstAligner
import dev.alfieprojects.stablestill.core.CropWindow
import dev.alfieprojects.stablestill.core.GyroBias
import dev.alfieprojects.stablestill.core.HorizonLock
import dev.alfieprojects.stablestill.core.Mat3
import dev.alfieprojects.stablestill.core.MotionTrack
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
    val rejectSigma: Float = 0.10f,
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

            val plan: AlignmentPlan = BurstAligner.plan(
                frames = frames.map { it.meta },
                track = track,
                intrinsics = intrinsics,
                rig = rig,
                crop = crop,
                sync = syncCalibration,
                extraRotation = extraRotation,
            )

            if (plan.usableCount < frames.size) {
                notes += "${frames.size - plan.usableCount} frame(s) moved beyond the crop margin " +
                    "and were dropped."
            }

            val bitmap = renderStack(frames, plan, settings)
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
        settings: StackSettings,
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
            renderer.rejectSigma = settings.rejectSigma
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
