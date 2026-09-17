package dev.alfieprojects.stablestill.core

import java.io.File
import java.io.RandomAccessFile

/**
 * Loads a burst archive back off disk.
 *
 * This is the other half of [BurstArchive], and the reason the format lives in
 * `:core`: a burst saved on the phone can be replayed here, on a JVM, in
 * milliseconds. An alignment bug stops being something that happens in
 * someone's hand and becomes a failing test.
 *
 * `java.io` is fine here. The rule this module lives by bars *Android* types,
 * not the JVM library - it exists so `:core` can run without a device, which is
 * exactly what reading a saved burst is for.
 */
object BurstReader {

    /** Reads the three text files. Pixels are left on disk until asked for. */
    fun read(directory: File): BurstArchiveContents {
        require(directory.isDirectory) { "Not a burst directory: $directory" }
        val manifestFile = File(directory, BurstArchive.MANIFEST_FILE)
        // The writer emits the manifest last, so its absence means the archive
        // was interrupted rather than that it is merely unusual.
        require(manifestFile.isFile) {
            "No ${BurstArchive.MANIFEST_FILE} in $directory - the burst was not finished"
        }
        val manifest = BurstArchive.readManifest(manifestFile.readText())
        val frames = BurstArchive.readFrames(File(directory, BurstArchive.FRAMES_FILE).readText())
        val gyroFile = File(directory, BurstArchive.GYRO_FILE)
        val gyro = if (gyroFile.isFile) BurstArchive.readGyro(gyroFile.readText()) else emptyList()

        require(frames.size == manifest.frameCount) {
            "Manifest claims ${manifest.frameCount} frames but frames.csv lists ${frames.size}"
        }
        return BurstArchiveContents(manifest, frames, gyro.sortedBy { it.timestampNanos })
    }

    /**
     * Reads one frame's luma plane.
     *
     * Luma alone, because everything that decides where a pixel goes is
     * decided on luminance: alignment, sharpness scoring, and the merge's
     * agreement test. Skipping chroma reads two thirds of the file and a third
     * of the bytes.
     */
    fun readLuma(directory: File, record: BurstFrameRecord): LumaPlane {
        val file = File(directory, record.fileName)
        require(file.isFile) { "Missing frame file: $file" }
        val expected = BurstArchive.frameByteCount(record.width, record.height)
        require(file.length() == expected) {
            "${record.fileName} is ${file.length()} bytes, expected $expected for " +
                "${record.width}x${record.height} - the frame is not the geometry the CSV claims"
        }
        val pixels = ByteArray(record.width * record.height)
        RandomAccessFile(file, "r").use { it.readFully(pixels) }
        return LumaPlane(record.width, record.height, pixels)
    }

    /**
     * Reads one frame in full, chroma included.
     *
     * Three times the bytes of [readLuma], and worth it only for the merge,
     * which compares warped pixels against the anchor in RGB. Everything else
     * in the pipeline should keep reading luma alone.
     */
    fun readFrame(directory: File, record: BurstFrameRecord): YuvFrame {
        val file = File(directory, record.fileName)
        require(file.isFile) { "Missing frame file: $file" }
        val expected = BurstArchive.frameByteCount(record.width, record.height)
        require(file.length() == expected) {
            "${record.fileName} is ${file.length()} bytes, expected $expected for " +
                "${record.width}x${record.height} - the frame is not the geometry the CSV claims"
        }
        val bytes = ByteArray(expected.toInt())
        RandomAccessFile(file, "r").use { it.readFully(bytes) }
        return YuvFrame.fromI420(record.width, record.height, bytes)
    }
}

/**
 * One frame's luminance, 8 bits per pixel, tightly packed.
 *
 * Values are stored as [Byte] and read through [get], which unsigns them. A
 * signed read is the classic way to turn a bright pixel into a negative number
 * and a sharpness metric into nonsense.
 */
class LumaPlane(val width: Int, val height: Int, val pixels: ByteArray) {
    init {
        require(pixels.size == width * height) {
            "Luma plane is ${pixels.size} bytes, expected ${width * height}"
        }
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xFF

    /**
     * Bilinear tap at a continuous pixel coordinate, on the 0..1 range.
     *
     * The convention matches the shaders and [YuvFrame]: pixel `i` has its
     * centre at `i + 0.5`, so a coordinate landing on a whole number sits on the
     * boundary between two pixels and returns their mean. Taps outside the plane
     * are clamped to the edge.
     */
    fun sample(cx: Double, cy: Double): Double {
        val tx = cx - 0.5
        val ty = cy - 0.5
        val x0 = kotlin.math.floor(tx).toInt()
        val y0 = kotlin.math.floor(ty).toInt()
        val fx = tx - x0
        val fy = ty - y0
        val xa = x0.coerceIn(0, width - 1)
        val xb = (x0 + 1).coerceIn(0, width - 1)
        val ya = y0.coerceIn(0, height - 1)
        val yb = (y0 + 1).coerceIn(0, height - 1)
        val top = this[xa, ya] + (this[xb, ya] - this[xa, ya]) * fx
        val bottom = this[xa, yb] + (this[xb, yb] - this[xa, yb]) * fx
        return (top + (bottom - top) * fy) / 255.0
    }

    /**
     * Half-resolution copy, by 2x2 box average.
     *
     * The averaging is not decoration: dropping alternate pixels aliases sensor
     * noise straight into the coarse level, and a refinement searching there
     * would be matching noise rather than structure.
     */
    fun downsample(): LumaPlane {
        val w = width / 2
        val h = height / 2
        require(w >= 1 && h >= 1) { "Cannot halve a ${width}x$height plane" }
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val sum = this[2 * x, 2 * y] + this[2 * x + 1, 2 * y] +
                    this[2 * x, 2 * y + 1] + this[2 * x + 1, 2 * y + 1]
                out[y * w + x] = ((sum + 2) / 4).toByte()
            }
        }
        return LumaPlane(w, h, out)
    }

    val meanLuma: Double
        get() {
            var sum = 0L
            for (b in pixels) sum += (b.toInt() and 0xFF)
            return sum.toDouble() / pixels.size
        }

    /**
     * Mean squared Laplacian over a centred window - a standard focus measure.
     *
     * Higher is sharper. It is a *relative* measure only: comparable between
     * frames of one burst, meaningless between bursts, since it scales with
     * scene contrast and with noise. Noise inflates it, which is why a noisy
     * frame can score above a clean one and why this must never be used to
     * compare capture settings.
     */
    fun sharpness(sampleWindow: Int = 512): Double {
        val w = minOf(sampleWindow, width - 2)
        val h = minOf(sampleWindow, height - 2)
        if (w <= 0 || h <= 0) return 0.0
        val x0 = (width - w) / 2
        val y0 = (height - h) / 2
        var sum = 0.0
        for (y in y0 until y0 + h) {
            for (x in x0 until x0 + w) {
                val lap = -4 * this[x, y] + this[x - 1, y] + this[x + 1, y] +
                    this[x, y - 1] + this[x, y + 1]
                sum += lap.toDouble() * lap
            }
        }
        return sum / (w.toDouble() * h)
    }
}
