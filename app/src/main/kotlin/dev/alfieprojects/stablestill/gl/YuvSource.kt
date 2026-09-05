package dev.alfieprojects.stablestill.gl

import android.graphics.ImageFormat
import android.media.Image
import dev.alfieprojects.stablestill.core.BurstArchive
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One plane's bytes and the strides needed to walk them. */
data class YuvPlane(val buffer: ByteBuffer, val rowStride: Int, val pixelStride: Int)

/**
 * Somewhere to get YUV 4:2:0 planes from.
 *
 * The renderer used to take a Camera2 [Image] directly, which quietly made the
 * GPU path untestable: an `Image` comes from an `ImageReader` and cannot be
 * built from a file, so the merge could only ever run against whatever the
 * camera happened to hand it. Behind this interface a burst saved to disk
 * feeds the same shaders as a live frame, which is the difference between a
 * stage that can be debugged and one that can only be re-photographed.
 */
interface YuvSource {
    val width: Int
    val height: Int

    /**
     * True when chroma is interleaved in one allocation, as NV12 and NV21 are.
     * The renderer reads such a plane as a two-channel texture.
     */
    val semiPlanar: Boolean

    /** 0 = Y, 1 = U/interleaved chroma, 2 = V. */
    fun plane(index: Int): YuvPlane
}

/** A live frame straight from the camera. */
class ImageYuvSource(private val image: Image) : YuvSource {
    override val width: Int get() = image.width
    override val height: Int get() = image.height

    override val semiPlanar: Boolean
        get() = image.format == ImageFormat.YUV_420_888 && image.planes[1].pixelStride == 2

    override fun plane(index: Int): YuvPlane = image.planes[index].let {
        YuvPlane(it.buffer, it.rowStride, it.pixelStride)
    }
}

/**
 * A frame read back from a burst archive: planar I420, no row padding.
 *
 * Direct buffers, because the upload path hands these to GLES, which cannot
 * read a heap array. Slices share the one allocation rather than copying it -
 * a 12.5 MP frame is 17.9 MB and there are eight of them.
 */
class I420YuvSource(
    override val width: Int,
    override val height: Int,
    bytes: ByteArray,
) : YuvSource {

    override val semiPlanar: Boolean get() = false

    private val chromaWidth = (width + 1) / 2
    private val chromaHeight = (height + 1) / 2

    private val planes: List<YuvPlane> = run {
        require(bytes.size.toLong() == BurstArchive.frameByteCount(width, height)) {
            "Frame is ${bytes.size} bytes, expected " +
                "${BurstArchive.frameByteCount(width, height)} for ${width}x$height"
        }
        val direct = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        direct.put(bytes)
        direct.rewind()

        val ySize = width * height
        val cSize = chromaWidth * chromaHeight
        fun slice(offset: Int, length: Int, rowStride: Int): YuvPlane {
            direct.position(offset)
            direct.limit(offset + length)
            val s = direct.slice().order(ByteOrder.nativeOrder())
            direct.clear()
            return YuvPlane(s, rowStride, 1)
        }
        listOf(
            slice(0, ySize, width),
            slice(ySize, cSize, chromaWidth),
            slice(ySize + cSize, cSize, chromaWidth),
        )
    }

    override fun plane(index: Int): YuvPlane = planes[index]
}
