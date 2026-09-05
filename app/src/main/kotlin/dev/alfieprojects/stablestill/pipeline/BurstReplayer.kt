package dev.alfieprojects.stablestill.pipeline

import android.graphics.Bitmap
import android.util.Log
import dev.alfieprojects.stablestill.core.AlignmentPlan
import dev.alfieprojects.stablestill.core.BurstAligner
import dev.alfieprojects.stablestill.core.BurstReader
import dev.alfieprojects.stablestill.core.CropWindow
import dev.alfieprojects.stablestill.core.MotionTrack
import dev.alfieprojects.stablestill.gl.EglCore
import dev.alfieprojects.stablestill.gl.I420YuvSource
import dev.alfieprojects.stablestill.gl.RenderFrame
import dev.alfieprojects.stablestill.gl.StackRenderer
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/** What a replay produced, and what it cost. */
data class ReplayResult(
    val output: File,
    val sourceDirectory: File,
    val framesMerged: Int,
    val framesTotal: Int,
    val anchorIndex: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val maxCornerShiftPx: Double,
    val floatAccumulation: Boolean,
    val elapsedMillis: Long,
)

/**
 * Runs the GPU merge over a burst read back from disk.
 *
 * The point is that the input never changes. A live capture is different every
 * time - different light, different tremor, different anchor - so a merge that
 * looks wrong tells you nothing about whether the shaders or the hand were at
 * fault. Against a saved burst the pixels and the plan are fixed, and any
 * change in the output came from the code.
 *
 * This is also the first thing in the project that exercises Phase 3 at all.
 */
class BurstReplayer(private val outputDir: File) {

    companion object {
        private const val TAG = "BurstReplayer"
    }

    fun replay(
        directory: File,
        cropMarginFraction: Double = 0.12,
        rejectSigma: Float = 0.10f,
        jpegQuality: Int = 95,
    ): ReplayResult {
        val started = System.currentTimeMillis()
        val burst = BurstReader.read(directory)
        check(burst.frames.isNotEmpty()) { "Burst at $directory has no frames" }

        val track = MotionTrack.integrate(burst.gyro)
        val crop = CropWindow(burst.manifest.width, burst.manifest.height, cropMarginFraction)
        val plan: AlignmentPlan = BurstAligner.plan(
            frames = burst.frames.map { it.toMeta() },
            track = track,
            intrinsics = burst.manifest.intrinsics,
            rig = burst.manifest.rig,
            crop = crop,
        )

        // Frames are read one at a time and released as soon as the GPU has
        // them. Eight 12.5 MP frames is 143 MB, and holding all of them as
        // direct buffers alongside the textures is how a 3.4 GB phone runs out.
        val bitmap = EglCore().use { egl ->
            egl.setup()
            if (!egl.supportsFloatColorBuffer) {
                Log.w(TAG, "No float colour buffer; accumulating at 8-bit precision")
            }
            StackRenderer(
                outputWidth = crop.outputWidth,
                outputHeight = crop.outputHeight,
                useFloatAccumulation = egl.supportsFloatColorBuffer,
            ).use { renderer ->
                renderer.rejectSigma = rejectSigma
                renderer.setup()
                val sources = burst.frames.map { record ->
                    val bytes = ByteArray(
                        dev.alfieprojects.stablestill.core.BurstArchive
                            .frameByteCount(record.width, record.height).toInt()
                    )
                    RandomAccessFile(File(directory, record.fileName), "r").use {
                        it.readFully(bytes)
                    }
                    RenderFrame(record.index, I420YuvSource(record.width, record.height, bytes))
                }
                renderer.render(sources, plan)
            }
        }

        outputDir.mkdirs()
        val output = File(outputDir, "${directory.name}-stacked.jpg")
        FileOutputStream(output).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
        }
        bitmap.recycle()

        return ReplayResult(
            output = output,
            sourceDirectory = directory,
            framesMerged = plan.usableCount,
            framesTotal = burst.frames.size,
            anchorIndex = plan.anchorIndex,
            outputWidth = crop.outputWidth,
            outputHeight = crop.outputHeight,
            maxCornerShiftPx = plan.alignments.maxOfOrNull { it.maxCornerShiftPx } ?: 0.0,
            floatAccumulation = true,
            elapsedMillis = System.currentTimeMillis() - started,
        )
    }
}
