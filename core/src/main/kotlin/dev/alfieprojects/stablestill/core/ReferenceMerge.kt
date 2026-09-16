package dev.alfieprojects.stablestill.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Eight-bit RGB, row-major, three bytes per pixel, first row first.
 *
 * Row order matches the GPU path's output without needing a flip, which is
 * worth stating because the GPU path needs *two*. There, the vertex shader puts
 * `vUv.y = 0` at the framebuffer's bottom and `glReadPixels` reads bottom row
 * first, and the two cancel. Here output row 0 simply is output row 0.
 */
class RgbImage(val width: Int, val height: Int, val pixels: ByteArray) {
    init {
        require(pixels.size == width * height * 3) {
            "Image is ${pixels.size} bytes, expected ${width * height * 3}"
        }
    }

    /** Channel 0, 1, 2 = R, G, B, as 0..255. */
    operator fun get(x: Int, y: Int, channel: Int): Int =
        pixels[(y * width + x) * 3 + channel].toInt() and 0xFF

    /** Mean of the three channels at a pixel, on the 0..1 range. */
    fun meanAt(x: Int, y: Int): Double =
        (this[x, y, 0] + this[x, y, 1] + this[x, y, 2]) / (3.0 * 255.0)

    /**
     * BT.601 luminance, so a merged result can be handed to the same noise and
     * sharpness measures that a captured frame is.
     */
    fun toLuma(): LumaPlane {
        val out = ByteArray(width * height)
        for (p in out.indices) {
            val o = p * 3
            val r = pixels[o].toInt() and 0xFF
            val g = pixels[o + 1].toInt() and 0xFF
            val b = pixels[o + 2].toInt() and 0xFF
            out[p] = (0.299 * r + 0.587 * g + 0.114 * b + 0.5).toInt().coerceIn(0, 255).toByte()
        }
        return LumaPlane(width, height, out)
    }

    /**
     * Binary PPM, so a merge can be looked at without dragging an image library
     * into `:core`. `javax.imageio` would work on a laptop and not exist on the
     * phone, which is the kind of dependency that compiles and then crashes.
     */
    fun toPpm(): ByteArray {
        val header = "P6\n$width $height\n255\n".toByteArray(Charsets.US_ASCII)
        return header + pixels
    }
}

/** What a merge produced, and what the threshold cost it. */
data class MergeResult(
    val image: RgbImage,
    val anchorIndex: Int,
    val framesContributed: Int,
    /** Mean weight each frame carried, over every output pixel. Keyed by frame index. */
    val meanWeight: Map<Int, Double>,
    /**
     * Mean total weight per output pixel - how many frames the merge *effectively*
     * averaged, as opposed to how many it was offered.
     *
     * This is the number a threshold sweep is really asking about. Noise falls
     * as its square root, so it converts a rejection threshold straight into the
     * noise reduction that threshold buys, with no phone in the loop.
     */
    val effectiveFrameCount: Double,
    /** Fraction of output pixels where every other frame was rejected. */
    val anchorFallbackFraction: Double,
) {
    /** Expected noise reduction factor, if the rejected pixels were only noise. */
    val noiseReduction: Double get() = sqrt(effectiveFrameCount)
}

/**
 * The merge, on the CPU, as the reference the shaders are answerable to.
 *
 * `StackRenderer` is the production path and will stay that way - nine million
 * output pixels times eight frames is what a GPU is for. But it can only run on
 * a phone, which made every question about the merge a question about hardware
 * availability: measuring where the rejection threshold starts ghosting took a
 * handset on a desk, fourteen replays, and an eyeball at 1:1. The same sweep
 * here is a loop over an archived burst.
 *
 * This is written to *match* the shaders rather than to improve on them, and
 * the awkward parts are the point: the half-texel sampling offset, the anchor
 * quantised to eight bits before anything is compared against it, the bounds
 * guard that drops a frame's contribution rather than clamping it. Where it
 * diverges deliberately, it says so.
 *
 * The one intended divergence is precision. The GPU accumulates into `RGBA16F`
 * where the float buffer extension is present, whose eleven-bit mantissa can
 * shift a resolved pixel by a least-significant bit or so; this accumulates in
 * single precision. A disagreement of one or two levels is that. A disagreement
 * of ten is a bug in one of the two, and the point of having both.
 */
object ReferenceMerge {

    /** Below this accumulated weight, the pixel falls back to the anchor alone. */
    private const val WEIGHT_FLOOR = 1e-4f

    /** Convenience for callers holding every frame already. */
    fun merge(
        frames: List<Pair<Int, YuvFrame>>,
        plan: AlignmentPlan,
        rejectSigma: Float,
    ): MergeResult {
        val byIndex = frames.toMap()
        return merge(plan, rejectSigma) { index ->
            byIndex[index] ?: error("Frame $index is missing from the burst")
        }
    }

    /**
     * Merges a burst according to [plan].
     *
     * Frames arrive through [frameAt] rather than as a list so a caller with a
     * 12.5 MP burst can read one frame at a time and let each go. Eight of them
     * held at once is 143 MB, which is a real constraint on the phone and an
     * irritating one on a laptop running a sweep.
     *
     * Each frame is requested exactly once, in plan order, after the anchor.
     */
    fun merge(
        plan: AlignmentPlan,
        rejectSigma: Float,
        frameAt: (Int) -> YuvFrame,
    ): MergeResult {
        require(rejectSigma > 0f) { "rejectSigma must be positive, was $rejectSigma" }
        val usable = plan.usableFrames
        check(usable.isNotEmpty()) { "No frame in the burst was usable" }

        val width = plan.crop.outputWidth
        val height = plan.crop.outputHeight
        val pixelCount = width * height

        // Pass 1 - the reference every other frame is judged against.
        val anchorAlignment = plan.alignments.firstOrNull { it.frameIndex == plan.anchorIndex }
            ?: error("Anchor frame ${plan.anchorIndex} has no alignment in the plan")
        // Held, not re-fetched. The anchor is needed twice - once to render the
        // reference and once as an ordinary contributor - and asking `frameAt`
        // for it a second time would re-read 17.9 MB from disk for a caller
        // streaming frames, which is the cost the callback exists to avoid.
        val anchorFrame = frameAt(plan.anchorIndex)
        val anchor = renderAnchor(anchorFrame, anchorAlignment, width, height)

        // Pass 2 - weighted accumulation. Interleaved r, g, b, weight, because
        // that is one cache line's worth of the same pixel rather than four
        // strides across a 144 MB working set.
        val accum = FloatArray(pixelCount * 4)
        val meanWeight = LinkedHashMap<Int, Double>()
        var contributed = 0

        for (alignment in plan.alignments) {
            if (!alignment.usable) continue
            val isAnchor = alignment.frameIndex == plan.anchorIndex
            val frame = if (isAnchor) anchorFrame else frameAt(alignment.frameIndex)
            val summed = accumulate(
                frame, alignment, anchor, accum, width, height, rejectSigma, isAnchor,
            )
            meanWeight[alignment.frameIndex] = summed / pixelCount
            contributed++
        }

        // Pass 3 - normalise, falling back to the anchor where everything was
        // rejected. A black hole there would be the honest arithmetic and the
        // wrong picture.
        val out = ByteArray(pixelCount * 3)
        var totalWeight = 0.0
        var fallbacks = 0
        for (p in 0 until pixelCount) {
            val w = accum[p * 4 + 3]
            totalWeight += w
            val o = p * 3
            if (w < WEIGHT_FLOOR) {
                fallbacks++
                out[o] = anchor[o]
                out[o + 1] = anchor[o + 1]
                out[o + 2] = anchor[o + 2]
            } else {
                out[o] = quantise(accum[p * 4] / w)
                out[o + 1] = quantise(accum[p * 4 + 1] / w)
                out[o + 2] = quantise(accum[p * 4 + 2] / w)
            }
        }

        return MergeResult(
            image = RgbImage(width, height, out),
            anchorIndex = plan.anchorIndex,
            framesContributed = contributed,
            meanWeight = meanWeight,
            effectiveFrameCount = totalWeight / pixelCount,
            anchorFallbackFraction = fallbacks.toDouble() / pixelCount,
        )
    }

    /**
     * The anchor alone, warped and cropped - pass 1 on its own.
     *
     * This is what the merge is measured against: noise reduction is the result
     * moving away from it, and ghosting is the result moving away from it
     * somewhere a subject was.
     */
    fun anchorImage(plan: AlignmentPlan, frameAt: (Int) -> YuvFrame): RgbImage {
        val alignment = plan.alignments.firstOrNull { it.frameIndex == plan.anchorIndex }
            ?: error("Anchor frame ${plan.anchorIndex} has no alignment in the plan")
        val width = plan.crop.outputWidth
        val height = plan.crop.outputHeight
        return RgbImage(
            width, height,
            renderAnchor(frameAt(plan.anchorIndex), alignment, width, height),
        )
    }

    /**
     * Per output pixel, how far the most dissenting frame sits from the anchor.
     *
     * Independent of any threshold, which is the point: it separates "the scene
     * changed here" from "the threshold let it through". A sweep computes this
     * once and then knows, for every threshold, whether a deviation in the
     * output is noise being averaged away or a subject being smeared.
     *
     * In RGB distance over the 0..1 range, the same units as `rejectSigma`.
     */
    fun disagreement(plan: AlignmentPlan, frameAt: (Int) -> YuvFrame): FloatArray {
        val width = plan.crop.outputWidth
        val height = plan.crop.outputHeight
        val anchor = anchorImage(plan, frameAt).pixels
        val worst = FloatArray(width * height)

        for (alignment in plan.alignments) {
            if (!alignment.usable || alignment.frameIndex == plan.anchorIndex) continue
            val frame = frameAt(alignment.frameIndex)
            val m = alignment.samplingMatrix
            val maxX = frame.width - 1.5
            val maxY = frame.height - 1.5
            forEachRow(height) { py ->
                val rgb = DoubleArray(3)
                val outY = py + 0.5
                for (px in 0 until width) {
                    val outX = px + 0.5
                    val z = m.m20 * outX + m.m21 * outY + m.m22
                    if (abs(z) < 1e-6) continue
                    val srcX = (m.m00 * outX + m.m01 * outY + m.m02) / z
                    val srcY = (m.m10 * outX + m.m11 * outY + m.m12) / z
                    if (srcX < 0.5 || srcY < 0.5 || srcX > maxX || srcY > maxY) continue
                    frame.sampleRgb(srcX / frame.width, srcY / frame.height, rgb)
                    val o = (py * width + px) * 3
                    val dr = rgb[0] - (anchor[o].toInt() and 0xFF) / 255.0
                    val dg = rgb[1] - (anchor[o + 1].toInt() and 0xFF) / 255.0
                    val db = rgb[2] - (anchor[o + 2].toInt() and 0xFF) / 255.0
                    val d = sqrt(dr * dr + dg * dg + db * db).toFloat()
                    val p = py * width + px
                    if (d > worst[p]) worst[p] = d
                }
            }
        }
        return worst
    }

    // ---------------------------------------------------------------- the passes

    /**
     * Renders the anchor alone, quantised to eight bits.
     *
     * The quantisation is deliberate and load-bearing. `anchorTex` is `GL_RGBA8`,
     * so the reference the weighting compares against has already been rounded
     * to 1/255 before a single difference is computed. Keeping it in float here
     * would make this reference quietly kinder than the shader at exactly the
     * distances the threshold cares about.
     */
    private fun renderAnchor(
        frame: YuvFrame,
        alignment: FrameAlignment,
        width: Int,
        height: Int,
    ): ByteArray {
        val out = ByteArray(width * height * 3)
        val m = alignment.samplingMatrix
        forEachRow(height) { py ->
            val rgb = DoubleArray(3)
            val outY = py + 0.5
            for (px in 0 until width) {
                val outX = px + 0.5
                val x = m.m00 * outX + m.m01 * outY + m.m02
                val y = m.m10 * outX + m.m11 * outY + m.m12
                val z = m.m20 * outX + m.m21 * outY + m.m22

                // ANCHOR_ONLY divides by max(|z|, 1e-6) and re-applies the sign,
                // so a degenerate row maps to the origin rather than to infinity.
                val denom = if (abs(z) > 1e-6) abs(z) else 1e-6
                val sign = if (z > 0.0) 1.0 else if (z < 0.0) -1.0 else 0.0
                val srcX = x / denom * sign
                val srcY = y / denom * sign

                // The anchor pass clamps rather than rejecting: it must define
                // every output pixel, since pass 3 falls back to it.
                val uvX = (srcX / frame.width).coerceIn(0.0, 1.0)
                val uvY = (srcY / frame.height).coerceIn(0.0, 1.0)
                frame.sampleRgb(uvX, uvY, rgb)

                val o = (py * width + px) * 3
                out[o] = quantise(rgb[0].toFloat())
                out[o + 1] = quantise(rgb[1].toFloat())
                out[o + 2] = quantise(rgb[2].toFloat())
            }
        }
        return out
    }

    /** Adds one frame's weighted contribution, and returns the weight it carried. */
    private fun accumulate(
        frame: YuvFrame,
        alignment: FrameAlignment,
        anchor: ByteArray,
        accum: FloatArray,
        width: Int,
        height: Int,
        rejectSigma: Float,
        isAnchor: Boolean,
    ): Double {
        val m = alignment.samplingMatrix
        val sigmaSq = rejectSigma.toDouble() * rejectSigma
        // Half a pixel of guard so the bilinear taps never reach outside the
        // frame - the same bound the shader applies, and the reason a frame
        // sliding off the sensor contributes nothing instead of smearing its
        // edge pixel across the output.
        val maxX = frame.width - 1.5
        val maxY = frame.height - 1.5

        val rowSums = DoubleArray(height)
        forEachRow(height) { py ->
            val rgb = DoubleArray(3)
            val outY = py + 0.5
            var rowSum = 0.0
            for (px in 0 until width) {
                val outX = px + 0.5
                val z = m.m20 * outX + m.m21 * outY + m.m22
                if (abs(z) < 1e-6) continue
                val srcX = (m.m00 * outX + m.m01 * outY + m.m02) / z
                val srcY = (m.m10 * outX + m.m11 * outY + m.m12) / z
                if (srcX < 0.5 || srcY < 0.5 || srcX > maxX || srcY > maxY) continue

                frame.sampleRgb(srcX / frame.width, srcY / frame.height, rgb)

                val w: Double
                if (isAnchor) {
                    // The anchor is never rejected; it cannot disagree with itself,
                    // and it is what the result degrades to.
                    w = 1.0
                } else {
                    val o = (py * width + px) * 3
                    val dr = rgb[0] - (anchor[o].toInt() and 0xFF) / 255.0
                    val dg = rgb[1] - (anchor[o + 1].toInt() and 0xFF) / 255.0
                    val db = rgb[2] - (anchor[o + 2].toInt() and 0xFF) / 255.0
                    w = exp(-(dr * dr + dg * dg + db * db) / sigmaSq)
                }

                val a = (py * width + px) * 4
                accum[a] += (rgb[0] * w).toFloat()
                accum[a + 1] += (rgb[1] * w).toFloat()
                accum[a + 2] += (rgb[2] * w).toFloat()
                accum[a + 3] += w.toFloat()
                rowSum += w
            }
            rowSums[py] = rowSum
        }
        return rowSums.sum()
    }

    // ------------------------------------------------------------------ plumbing

    private fun quantise(v: Float): Byte {
        val scaled = (v * 255f + 0.5f).toInt()
        return (if (scaled < 0) 0 else if (scaled > 255) 255 else scaled).toByte()
    }

    /**
     * Runs [body] over every output row, across the available cores.
     *
     * Rows are independent - each writes only its own slice of the accumulator -
     * so this is the whole of the concurrency story. It matters because the
     * reason to have a CPU merge at all is sweeping thresholds in seconds, and
     * seventy million weighted samples single-threaded is not seconds.
     */
    private inline fun forEachRow(height: Int, crossinline body: (Int) -> Unit) {
        val workers = minOf(Runtime.getRuntime().availableProcessors(), height)
        if (workers <= 1) {
            for (py in 0 until height) body(py)
            return
        }
        // A worker that throws dies quietly: `join` returns normally and the
        // accumulator is left holding whatever that thread managed before it
        // failed. The merge would then return a picture missing a stripe, and an
        // effectiveFrameCount that reports the loss as rejection. So the first
        // failure is kept and rethrown once every worker has stopped.
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val threads = (0 until workers).map { worker ->
            Thread {
                try {
                    var py = worker
                    while (py < height) {
                        body(py)
                        py += workers
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        failure.get()?.let { throw it }
    }
}
