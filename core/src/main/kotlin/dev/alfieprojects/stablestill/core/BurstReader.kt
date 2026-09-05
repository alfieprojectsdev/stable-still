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
