package dev.alfieprojects.stablestill.core

import kotlin.math.floor

/**
 * One frame's pixels, planar I420, exactly as the archive stores them.
 *
 * [LumaPlane] deliberately reads luma alone, because alignment and sharpness
 * are decided on luminance. The merge is the one stage that needs colour: it
 * compares each warped pixel against the anchor *in RGB*, which is what the
 * shader does and therefore what a reference implementation has to do too.
 *
 * Sampling here mirrors `texture()` under `GL_LINEAR` and `GL_CLAMP_TO_EDGE`,
 * half-texel offset included. That offset is not a detail to tidy away: a
 * reference that samples at `srcPx` where the GPU samples at `srcPx - 0.5`
 * disagrees by half a pixel everywhere, which is larger than the alignment
 * error the whole project exists to remove.
 */
class YuvFrame(
    val width: Int,
    val height: Int,
    val y: ByteArray,
    val u: ByteArray,
    val v: ByteArray,
) {
    val chromaWidth: Int = (width + 1) / 2
    val chromaHeight: Int = (height + 1) / 2

    init {
        require(y.size == width * height) {
            "Luma plane is ${y.size} bytes, expected ${width * height}"
        }
        require(u.size == chromaWidth * chromaHeight && v.size == u.size) {
            "Chroma planes are ${u.size}/${v.size} bytes, expected ${chromaWidth * chromaHeight}"
        }
    }

    /** The luma plane on its own, for noise estimation and sharpness scoring. */
    fun luma(): LumaPlane = LumaPlane(width, height, y)

    /**
     * Samples BT.601 full-range RGB at a normalised coordinate, writing r, g, b
     * into [out] on the 0..1 range.
     *
     * [out] is a caller-supplied scratch array rather than a returned triple
     * because this runs once per output pixel per frame - seventy million times
     * for an eight-frame 12.5 MP burst - and an allocation there costs more than
     * the arithmetic.
     */
    fun sampleRgb(uvX: Double, uvY: Double, out: DoubleArray) {
        val luma = sample(y, width, height, uvX, uvY)
        // Chroma planes are half-size, and the shader addresses them with the
        // same normalised coordinate, so the scale falls out of the plane
        // dimensions rather than being a hard-coded halving.
        val cb = sample(u, chromaWidth, chromaHeight, uvX, uvY) - 0.5
        val cr = sample(v, chromaWidth, chromaHeight, uvX, uvY) - 0.5
        out[0] = clamp01(luma + 1.402 * cr)
        out[1] = clamp01(luma - 0.344136 * cb - 0.714136 * cr)
        out[2] = clamp01(luma + 1.772 * cb)
    }

    private fun clamp01(v: Double): Double = if (v < 0.0) 0.0 else if (v > 1.0) 1.0 else v

    /**
     * Bilinear tap on one plane, in the normalised coordinates GLSL uses.
     *
     * `uv * size - 0.5` is where the half texel comes from: `texture()` treats
     * uv as addressing texel *centres*, so uv = 0.5/size lands exactly on texel
     * zero. Taps falling outside are clamped to the edge texel, matching
     * `GL_CLAMP_TO_EDGE`; the merge's own bounds guard means that only ever
     * happens on the anchor pass, which clamps on purpose.
     */
    private fun sample(plane: ByteArray, w: Int, h: Int, uvX: Double, uvY: Double): Double {
        val tx = uvX * w - 0.5
        val ty = uvY * h - 0.5
        val x0 = floor(tx).toInt()
        val y0 = floor(ty).toInt()
        val fx = tx - x0
        val fy = ty - y0

        val xa = x0.coerceIn(0, w - 1)
        val xb = (x0 + 1).coerceIn(0, w - 1)
        val ya = y0.coerceIn(0, h - 1)
        val yb = (y0 + 1).coerceIn(0, h - 1)

        val rowA = ya * w
        val rowB = yb * w
        val p00 = (plane[rowA + xa].toInt() and 0xFF).toDouble()
        val p10 = (plane[rowA + xb].toInt() and 0xFF).toDouble()
        val p01 = (plane[rowB + xa].toInt() and 0xFF).toDouble()
        val p11 = (plane[rowB + xb].toInt() and 0xFF).toDouble()

        val top = p00 + (p10 - p00) * fx
        val bottom = p01 + (p11 - p01) * fx
        return (top + (bottom - top) * fy) / 255.0
    }

    companion object {
        /** Splits one archive frame's bytes into its three planes. */
        fun fromI420(width: Int, height: Int, bytes: ByteArray): YuvFrame {
            val expected = BurstArchive.frameByteCount(width, height)
            require(bytes.size.toLong() == expected) {
                "Frame is ${bytes.size} bytes, expected $expected for ${width}x$height"
            }
            val ySize = width * height
            val cSize = ((width + 1) / 2) * ((height + 1) / 2)
            return YuvFrame(
                width = width,
                height = height,
                y = bytes.copyOfRange(0, ySize),
                u = bytes.copyOfRange(ySize, ySize + cSize),
                v = bytes.copyOfRange(ySize + cSize, ySize + 2 * cSize),
            )
        }
    }
}
