package dev.alfieprojects.stablestill.gl

import android.graphics.Bitmap
import android.media.Image
import android.opengl.GLES30
import dev.alfieprojects.stablestill.capture.CapturedFrame
import dev.alfieprojects.stablestill.core.AlignmentPlan
import dev.alfieprojects.stablestill.core.Mat3
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Warps a burst into a single still on the GPU.
 *
 * Three passes:
 *  1. render the anchor frame, giving pass 2 a reference to compare against;
 *  2. additively accumulate every usable frame, each weighted by its agreement
 *     with that reference;
 *  3. divide the accumulated colour by the accumulated weight.
 *
 * Requires a current EGL context - construct [EglCore] and call `setup()` first.
 */
class StackRenderer(
    private val outputWidth: Int,
    private val outputHeight: Int,
    private val useFloatAccumulation: Boolean,
) : AutoCloseable {

    private var warpProgram = 0
    private var anchorProgram = 0
    private var resolveProgram = 0

    private var accumTex = 0
    private var accumFbo = 0
    private var anchorTex = 0
    private var anchorFbo = 0
    private var resultTex = 0
    private var resultFbo = 0

    private var yTex = 0
    private var uTex = 0
    private var vTex = 0
    private var vao = 0

    /**
     * How far a pixel may differ from the anchor before it stops contributing.
     *
     * In RGB distance over the 0..1 range. Too tight and genuine noise gets
     * rejected, defeating the whole point; too loose and moving subjects ghost.
     * 0.10 is a sane starting point for daylight and wants raising in low light,
     * where the per-frame noise is itself larger.
     */
    var rejectSigma: Float = 0.10f

    fun setup() {
        warpProgram = GlUtil.buildProgram(Shaders.VERTEX, Shaders.WARP_AND_WEIGHT)
        anchorProgram = GlUtil.buildProgram(Shaders.VERTEX, Shaders.ANCHOR_ONLY)
        resolveProgram = GlUtil.buildProgram(Shaders.VERTEX, Shaders.RESOLVE)

        val vaos = IntArray(1)
        GLES30.glGenVertexArrays(1, vaos, 0)
        vao = vaos[0]

        val accumFormat = if (useFloatAccumulation) GLES30.GL_RGBA16F else GLES30.GL_RGBA8
        val (aTex, aFbo) = createTarget(accumFormat)
        accumTex = aTex; accumFbo = aFbo
        val (nTex, nFbo) = createTarget(GLES30.GL_RGBA8)
        anchorTex = nTex; anchorFbo = nFbo
        val (rTex, rFbo) = createTarget(GLES30.GL_RGBA8)
        resultTex = rTex; resultFbo = rFbo

        val planes = IntArray(3)
        GLES30.glGenTextures(3, planes, 0)
        yTex = planes[0]; uTex = planes[1]; vTex = planes[2]
        listOf(yTex, uTex, vTex).forEach { configurePlaneTexture(it) }

        GlUtil.checkGlError("StackRenderer.setup")
    }

    /**
     * Merges [frames] according to [plan] and returns the stacked image.
     *
     * Frames marked unusable in the plan are skipped: they were rotated further
     * than the crop margin can absorb, so including them would drag undefined
     * pixels into the result.
     */
    fun render(frames: List<CapturedFrame>, plan: AlignmentPlan): Bitmap {
        val byIndex = frames.associateBy { it.meta.index }
        val anchorFrame = byIndex[plan.anchorIndex]
            ?: error("Anchor frame ${plan.anchorIndex} is missing from the burst")
        val anchorAlignment = plan.alignments.first { it.frameIndex == plan.anchorIndex }

        GLES30.glBindVertexArray(vao)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glViewport(0, 0, outputWidth, outputHeight)

        // Pass 1 - the reference.
        uploadFrame(anchorFrame.image)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, anchorFbo)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(anchorProgram)
        bindPlaneUniforms(anchorProgram, anchorFrame.image)
        setMatrix(anchorProgram, "uSampling", anchorAlignment.samplingMatrix)
        setVec2(anchorProgram, "uOutputSize", outputWidth.toFloat(), outputHeight.toFloat())
        setVec2(
            anchorProgram, "uSourceSize",
            anchorFrame.meta.width.toFloat(), anchorFrame.meta.height.toFloat(),
        )
        drawFullscreen()

        // Pass 2 - weighted accumulation.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, accumFbo)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        GLES30.glUseProgram(warpProgram)

        var contributed = 0
        for (alignment in plan.alignments) {
            if (!alignment.usable) continue
            val frame = byIndex[alignment.frameIndex] ?: continue
            uploadFrame(frame.image)
            bindPlaneUniforms(warpProgram, frame.image)

            GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, anchorTex)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(warpProgram, "uAnchor"), 3)

            setMatrix(warpProgram, "uSampling", alignment.samplingMatrix)
            setVec2(warpProgram, "uOutputSize", outputWidth.toFloat(), outputHeight.toFloat())
            setVec2(
                warpProgram, "uSourceSize",
                frame.meta.width.toFloat(), frame.meta.height.toFloat(),
            )
            GLES30.glUniform1f(
                GLES30.glGetUniformLocation(warpProgram, "uRejectSigma"), rejectSigma
            )
            GLES30.glUniform1f(
                GLES30.glGetUniformLocation(warpProgram, "uIsAnchor"),
                if (alignment.frameIndex == plan.anchorIndex) 1f else 0f,
            )
            drawFullscreen()
            contributed++
        }
        GLES30.glDisable(GLES30.GL_BLEND)
        check(contributed > 0) { "No frame in the burst was usable" }

        // Pass 3 - normalise.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, resultFbo)
        GLES30.glUseProgram(resolveProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, accumTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(resolveProgram, "uAccum"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, anchorTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(resolveProgram, "uAnchor"), 1)
        drawFullscreen()

        return readBack()
    }

    // ------------------------------------------------------------------ plumbing

    private fun drawFullscreen() {
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    private fun createTarget(internalFormat: Int): Pair<Int, Int> {
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, internalFormat, outputWidth, outputHeight)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val fbo = IntArray(1)
        GLES30.glGenFramebuffers(1, fbo, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, tex[0], 0,
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        check(status == GLES30.GL_FRAMEBUFFER_COMPLETE) {
            "Framebuffer incomplete (0x${Integer.toHexString(status)}) for format " +
                "0x${Integer.toHexString(internalFormat)}"
        }
        return tex[0] to fbo[0]
    }

    private fun configurePlaneTexture(tex: Int) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        // Bilinear on the luma plane is what gives sub-pixel warping; nearest
        // would quantise every correction back to whole pixels and undo the point.
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    private fun isSemiPlanar(image: Image): Boolean = image.planes[1].pixelStride == 2

    private fun uploadFrame(image: Image) {
        val w = image.width
        val h = image.height

        uploadPlane(yTex, image.planes[0].buffer, image.planes[0].rowStride, 1, w, h, GLES30.GL_R8, GLES30.GL_RED)

        if (isSemiPlanar(image)) {
            // Interleaved chroma. planes[1] points at the first Cb byte and the
            // next byte is Cr for both NV12 and NV21 layouts, so a single RG
            // texture reads correctly either way.
            uploadPlane(
                uTex, image.planes[1].buffer, image.planes[1].rowStride, 2,
                w / 2, h / 2, GLES30.GL_RG8, GLES30.GL_RG,
            )
        } else {
            uploadPlane(
                uTex, image.planes[1].buffer, image.planes[1].rowStride, 1,
                w / 2, h / 2, GLES30.GL_R8, GLES30.GL_RED,
            )
            uploadPlane(
                vTex, image.planes[2].buffer, image.planes[2].rowStride, 1,
                w / 2, h / 2, GLES30.GL_R8, GLES30.GL_RED,
            )
        }
    }

    private fun uploadPlane(
        tex: Int,
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
        internalFormat: Int,
        format: Int,
    ) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)

        val bytesPerPixel = if (format == GLES30.GL_RG) 2 else 1
        val required = rowStride.toLong() * (height - 1) + width.toLong() * pixelStride
        val source: ByteBuffer
        val rowLengthPixels: Int

        if (buffer.remaining() >= required && rowStride % bytesPerPixel == 0) {
            source = buffer
            rowLengthPixels = rowStride / bytesPerPixel
        } else {
            // Short or awkwardly strided buffer: repack tightly. Slower, but some
            // HALs really do hand back a final row that stops early.
            source = packTightly(buffer, rowStride, width, height, bytesPerPixel)
            rowLengthPixels = width
        }

        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, rowLengthPixels)
        source.position(0)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, internalFormat, width, height, 0,
            format, GLES30.GL_UNSIGNED_BYTE, source,
        )
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0)
    }

    private fun packTightly(
        buffer: ByteBuffer,
        rowStride: Int,
        width: Int,
        height: Int,
        bytesPerPixel: Int,
    ): ByteBuffer {
        val rowBytes = width * bytesPerPixel
        val out = ByteBuffer.allocateDirect(rowBytes * height).order(ByteOrder.nativeOrder())
        val row = ByteArray(rowBytes)
        for (y in 0 until height) {
            val offset = y * rowStride
            if (offset >= buffer.limit()) break
            val available = minOf(rowBytes, buffer.limit() - offset)
            buffer.position(offset)
            buffer.get(row, 0, available)
            out.put(row, 0, rowBytes)
        }
        out.position(0)
        return out
    }

    private fun bindPlaneUniforms(program: Int, image: Image) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, yTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uY"), 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, uTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uU"), 1)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, vTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uV"), 2)

        GLES30.glUniform1i(
            GLES30.glGetUniformLocation(program, "uSemiPlanar"),
            if (isSemiPlanar(image)) 1 else 0,
        )
    }

    private fun setMatrix(program: Int, name: String, m: Mat3) {
        GLES30.glUniformMatrix3fv(
            GLES30.glGetUniformLocation(program, name), 1, false, m.toGlColumnMajor(), 0
        )
    }

    private fun setVec2(program: Int, name: String, x: Float, y: Float) {
        GLES30.glUniform2f(GLES30.glGetUniformLocation(program, name), x, y)
    }

    private fun readBack(): Bitmap {
        val buffer = ByteBuffer
            .allocateDirect(outputWidth * outputHeight * 4)
            .order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(
            0, 0, outputWidth, outputHeight,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer,
        )
        buffer.rewind()
        val bitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        // GL's origin is bottom-left, Bitmap's is top-left.
        return flipVertically(bitmap)
    }

    private fun flipVertically(source: Bitmap): Bitmap {
        val matrix = android.graphics.Matrix().apply { preScale(1f, -1f) }
        val flipped = Bitmap.createBitmap(
            source, 0, 0, source.width, source.height, matrix, false
        )
        if (flipped != source) source.recycle()
        return flipped
    }

    override fun close() {
        GLES30.glDeleteProgram(warpProgram)
        GLES30.glDeleteProgram(anchorProgram)
        GLES30.glDeleteProgram(resolveProgram)
        GLES30.glDeleteFramebuffers(3, intArrayOf(accumFbo, anchorFbo, resultFbo), 0)
        GLES30.glDeleteTextures(6, intArrayOf(accumTex, anchorTex, resultTex, yTex, uTex, vTex), 0)
        GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
    }
}
