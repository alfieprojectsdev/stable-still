package dev.alfieprojects.stablestill.capture

import android.media.Image
import android.os.Build
import android.os.StatFs
import android.util.Log
import dev.alfieprojects.stablestill.core.BurstArchive
import dev.alfieprojects.stablestill.core.BurstFrameRecord
import dev.alfieprojects.stablestill.core.BurstManifest
import dev.alfieprojects.stablestill.core.CameraIntrinsics
import dev.alfieprojects.stablestill.core.GyroSample
import dev.alfieprojects.stablestill.core.RigAlignment
import dev.alfieprojects.stablestill.core.Vec3
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Where a saved burst landed, and what it cost. */
data class SavedBurst(
    val directory: File,
    val frameCount: Int,
    val gyroSampleCount: Int,
    val bytesWritten: Long,
    val frameSpanMillis: Long,
    val gyroCoversFrames: Boolean,
    val elapsedMillis: Long,
    val notes: List<String>,
)

/**
 * Writes a burst to disk so it can be replayed off the phone.
 *
 * The point of this is not the pixels, it is the *reproducibility*: an
 * alignment bug that only appears on a handset is a bug you debug by taking
 * more photos, which is the slowest loop in the project. A saved burst turns
 * the same bug into a JVM test.
 *
 * Frames are stored as tightly-packed I420 rather than JPEG or PNG. Encoding
 * would silently apply exactly the tone mapping and denoise the stack is meant
 * to reason about, so the file has to hold the same samples the merge would see.
 */
class BurstWriter(private val root: File) {

    companion object {
        private const val TAG = "BurstWriter"

        /** Refuse to start a write that would leave the volume this close to full. */
        private const val FREE_SPACE_MARGIN_BYTES = 128L * 1024 * 1024
    }

    /**
     * Writes [frames] and [gyro] into a new timestamped directory.
     *
     * Does **not** close the frames. The caller owns them and must close them in
     * its own `finally`, because a write that throws halfway must not also leak
     * the ImageReader slots - once that pool is exhausted the camera stops
     * delivering with no error anywhere.
     */
    fun write(
        frames: List<CapturedFrame>,
        gyro: List<GyroSample>,
        cameraId: String,
        intrinsics: CameraIntrinsics,
        rig: RigAlignment,
        shutterNanos: Long,
        gravity: Vec3,
        extraNotes: List<String> = emptyList(),
    ): SavedBurst {
        require(frames.isNotEmpty()) { "Refusing to write an empty burst" }
        val started = System.currentTimeMillis()
        val notes = extraNotes.toMutableList()

        // Before StatFs, not after: it rejects a path that does not exist yet,
        // and on a first run this directory never does.
        require(root.mkdirs() || root.isDirectory) { "Could not create $root" }

        val first = frames.first().meta
        val needed = BurstArchive.frameByteCount(first.width, first.height) * frames.size
        assertSpaceFor(needed)

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(started))
        val dir = File(root, "burst-$stamp")
        require(dir.mkdirs() || dir.isDirectory) { "Could not create $dir" }

        var bytes = 0L
        val records = ArrayList<BurstFrameRecord>(frames.size)
        for ((i, frame) in frames.withIndex()) {
            val name = BurstArchive.frameFileName(i)
            val file = File(dir, name)
            BufferedOutputStream(FileOutputStream(file), 1 shl 16).use { out ->
                writeI420(frame.image, out)
            }
            bytes += file.length()
            val meta = frame.meta
            records += BurstFrameRecord(
                index = i,
                fileName = name,
                sensorTimestampNanos = meta.sensorTimestampNanos,
                exposureTimeNanos = meta.exposureTimeNanos,
                rollingShutterSkewNanos = meta.rollingShutterSkewNanos,
                width = meta.width,
                height = meta.height,
                sensitivityIso = meta.sensitivityIso,
            )
            if (meta.rollingShutterSkewNanos == 0L && i == 0) {
                notes += "Rolling-shutter skew reported as zero; per-row correction is disabled."
            }
        }

        val span = if (records.size < 2) 0L else
            records.maxOf { it.sensorTimestampNanos } - records.minOf { it.sensorTimestampNanos }

        // Judged before the manifest is built, not after. A warning that only
        // reaches the return value and not the file is a warning the person
        // reading the archive six months later never sees.
        val covered = gyro.isNotEmpty() &&
            gyro.first().timestampNanos <= records.minOf { it.toMeta().firstRowMidNanos } &&
            gyro.last().timestampNanos >= records.maxOf { it.toMeta().lastRowMidNanos }
        if (!covered) {
            notes += "Gyro trace does not bracket every frame's exposure; " +
                "the outermost frames will clamp instead of correcting."
        }

        val manifest = BurstManifest(
            capturedAtEpochMillis = started,
            deviceModel = Build.MODEL,
            androidSdkInt = Build.VERSION.SDK_INT,
            cameraId = cameraId,
            width = first.width,
            height = first.height,
            intrinsics = intrinsics,
            rig = rig,
            shutterNanos = shutterNanos,
            gravity = gravity,
            frameCount = records.size,
            gyroSampleCount = gyro.size,
            notes = notes,
        )

        File(dir, BurstArchive.FRAMES_FILE).writeText(BurstArchive.writeFrames(records))
        File(dir, BurstArchive.GYRO_FILE).writeText(BurstArchive.writeGyro(gyro))
        // Written last: its presence is what marks the directory complete, so a
        // write killed part-way leaves an archive a reader will reject outright
        // rather than one it will happily misinterpret.
        File(dir, BurstArchive.MANIFEST_FILE).writeText(BurstArchive.writeManifest(manifest))

        Log.i(TAG, "Wrote ${records.size} frames (${bytes / (1024 * 1024)} MB) to $dir")
        return SavedBurst(
            directory = dir,
            frameCount = records.size,
            gyroSampleCount = gyro.size,
            bytesWritten = bytes,
            frameSpanMillis = span / 1_000_000L,
            gyroCoversFrames = covered,
            elapsedMillis = System.currentTimeMillis() - started,
            notes = notes,
        )
    }

    private fun assertSpaceFor(bytes: Long) {
        val stat = StatFs(root.absolutePath)
        val free = stat.availableBytes
        check(free > bytes + FREE_SPACE_MARGIN_BYTES) {
            "Need ${bytes / (1024 * 1024)} MB for this burst but only " +
                "${free / (1024 * 1024)} MB is free"
        }
    }

    // ------------------------------------------------------------------- pixels

    /**
     * Copies a `YUV_420_888` image out as planar I420: Y, then U, then V, each
     * tightly packed.
     *
     * The stride handling is the whole job. `YUV_420_888` guarantees almost
     * nothing about layout: rows are padded to `rowStride`, and the chroma
     * planes may be *semi-planar* with `pixelStride == 2`, U and V interleaved
     * in one allocation. Copying `buffer.remaining()` straight out - the obvious
     * thing - produces an image that looks nearly right, with a diagonal shear
     * and swapped colours that are easy to mistake for an alignment bug.
     */
    private fun writeI420(image: Image, out: OutputStream) {
        val width = image.width
        val height = image.height
        writePlane(image.planes[0], width, height, out)
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        writePlane(image.planes[1], chromaWidth, chromaHeight, out)
        writePlane(image.planes[2], chromaWidth, chromaHeight, out)
    }

    private fun writePlane(plane: Image.Plane, width: Int, height: Int, out: OutputStream) {
        val buffer = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val row = ByteArray(rowStride)
        val packed = if (pixelStride == 1) row else ByteArray(width)

        for (y in 0 until height) {
            val start = y * rowStride
            if (start >= buffer.limit()) break
            buffer.position(start)
            // The final row is not padded out to rowStride, so read what is
            // there rather than what the stride implies.
            val available = minOf(rowStride, buffer.remaining())
            buffer.get(row, 0, available)

            if (pixelStride == 1) {
                out.write(row, 0, minOf(width, available))
            } else {
                val usable = minOf(width, (available + pixelStride - 1) / pixelStride)
                for (x in 0 until usable) packed[x] = row[x * pixelStride]
                out.write(packed, 0, usable)
            }
        }
    }
}
