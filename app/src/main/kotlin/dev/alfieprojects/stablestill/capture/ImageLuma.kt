package dev.alfieprojects.stablestill.capture

import android.media.Image
import dev.alfieprojects.stablestill.core.LumaPlane

/**
 * Copies a `YUV_420_888` image's luma out as a tightly packed [LumaPlane].
 *
 * The maths in `:core` wants rows of exactly `width` bytes. The camera gives
 * rows padded to `rowStride`, occasionally with a `pixelStride` above one, and
 * a final row that is not padded at all. Same stride handling as the archive
 * writer, for the same reason: copying `remaining()` straight out looks nearly
 * right and shears diagonally.
 */
fun Image.lumaPlane(): LumaPlane {
    val plane = planes[0]
    val buffer = plane.buffer.duplicate()
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val packed = ByteArray(width * height)
    val row = ByteArray(rowStride)
    for (y in 0 until height) {
        val start = y * rowStride
        if (start >= buffer.limit()) break
        buffer.position(start)
        val available = minOf(rowStride, buffer.remaining())
        buffer.get(row, 0, available)
        if (pixelStride == 1) {
            System.arraycopy(row, 0, packed, y * width, minOf(width, available))
        } else {
            val usable = minOf(width, (available + pixelStride - 1) / pixelStride)
            for (x in 0 until usable) packed[y * width + x] = row[x * pixelStride]
        }
    }
    return LumaPlane(width, height, packed)
}
