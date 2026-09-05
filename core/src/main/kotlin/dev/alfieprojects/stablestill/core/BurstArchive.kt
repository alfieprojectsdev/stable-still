package dev.alfieprojects.stablestill.core

/**
 * The on-disk format for a captured burst: frames, their timing, and the gyro
 * trace that covers them.
 *
 * This lives in `:core` rather than `:app` because its whole reason to exist is
 * to be read *off* the phone. A burst saved once and replayed in a JVM test
 * turns every later alignment bug into something reproducible at a desk instead
 * of a thing that only happens in someone's hand. A format that could only be
 * parsed by Android code would defeat that entirely.
 *
 * Everything is text apart from the pixels. The files are meant to be openable
 * in an editor when a number looks wrong, which matters more here than the few
 * hundred bytes it costs.
 */
object BurstArchive {

    /**
     * Bumped when a field changes meaning. A reader that does not recognise the
     * version should refuse the archive rather than silently misread a column.
     */
    const val FORMAT_VERSION = 1

    const val MANIFEST_FILE = "manifest.txt"
    const val FRAMES_FILE = "frames.csv"
    const val GYRO_FILE = "gyro.csv"

    /** Planar Y, then U, then V, each plane tightly packed with no row padding. */
    const val FRAME_EXTENSION = "i420"

    fun frameFileName(index: Int): String =
        "frame_" + index.toString().padStart(2, '0') + "." + FRAME_EXTENSION

    /** Bytes one I420 frame of these dimensions occupies, padding excluded. */
    fun frameByteCount(width: Int, height: Int): Long {
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        return width.toLong() * height + 2L * chromaWidth * chromaHeight
    }

    // ------------------------------------------------------------------ frames

    private const val FRAMES_HEADER =
        "index,fileName,sensorTimestampNanos,exposureTimeNanos,rollingShutterSkewNanos,width,height"

    fun writeFrames(records: List<BurstFrameRecord>): String = buildString {
        appendLine(FRAMES_HEADER)
        for (r in records) {
            append(r.index).append(',')
            append(r.fileName).append(',')
            append(r.sensorTimestampNanos).append(',')
            append(r.exposureTimeNanos).append(',')
            append(r.rollingShutterSkewNanos).append(',')
            append(r.width).append(',')
            append(r.height).append('\n')
        }
    }

    fun readFrames(text: String): List<BurstFrameRecord> =
        dataLines(text).map { line ->
            val f = line.split(',')
            require(f.size >= 7) { "Malformed frame row: $line" }
            BurstFrameRecord(
                index = f[0].trim().toInt(),
                fileName = f[1].trim(),
                sensorTimestampNanos = f[2].trim().toLong(),
                exposureTimeNanos = f[3].trim().toLong(),
                rollingShutterSkewNanos = f[4].trim().toLong(),
                width = f[5].trim().toInt(),
                height = f[6].trim().toLong().toInt(),
            )
        }

    // -------------------------------------------------------------------- gyro

    private const val GYRO_HEADER = "timestampNanos,wx,wy,wz"

    fun writeGyro(samples: List<GyroSample>): String = buildString {
        appendLine(GYRO_HEADER)
        for (s in samples) {
            append(s.timestampNanos).append(',')
            // Double.toString is locale-independent, which String.format is not.
            // A comma decimal separator would silently corrupt every row.
            append(s.omega.x).append(',')
            append(s.omega.y).append(',')
            append(s.omega.z).append('\n')
        }
    }

    fun readGyro(text: String): List<GyroSample> =
        dataLines(text).map { line ->
            val f = line.split(',')
            require(f.size >= 4) { "Malformed gyro row: $line" }
            GyroSample(
                timestampNanos = f[0].trim().toLong(),
                omega = Vec3(f[1].trim().toDouble(), f[2].trim().toDouble(), f[3].trim().toDouble()),
            )
        }

    // ---------------------------------------------------------------- manifest

    fun writeManifest(m: BurstManifest): String = buildString {
        appendLine("# stable-still burst archive")
        appendLine("formatVersion=${m.formatVersion}")
        appendLine("capturedAtEpochMillis=${m.capturedAtEpochMillis}")
        appendLine("deviceModel=${m.deviceModel}")
        appendLine("androidSdkInt=${m.androidSdkInt}")
        appendLine("cameraId=${m.cameraId}")
        appendLine("width=${m.width}")
        appendLine("height=${m.height}")
        appendLine("fx=${m.intrinsics.fx}")
        appendLine("fy=${m.intrinsics.fy}")
        appendLine("cx=${m.intrinsics.cx}")
        appendLine("cy=${m.intrinsics.cy}")
        appendLine("sensorOrientationDegrees=${m.rig.sensorOrientationDegrees}")
        appendLine("frontFacing=${m.rig.frontFacing}")
        appendLine("handedness=${m.rig.handedness}")
        appendLine("shutterNanos=${m.shutterNanos}")
        appendLine("gravityX=${m.gravity.x}")
        appendLine("gravityY=${m.gravity.y}")
        appendLine("gravityZ=${m.gravity.z}")
        appendLine("frameCount=${m.frameCount}")
        appendLine("gyroSampleCount=${m.gyroSampleCount}")
        // Newlines would break the line-per-key parse, so they are flattened.
        appendLine("notes=${m.notes.joinToString(" | ") { it.replace('\n', ' ') }}")
    }

    fun readManifest(text: String): BurstManifest {
        val kv = HashMap<String, String>()
        for (line in dataLines(text, header = false)) {
            val i = line.indexOf('=')
            if (i > 0) kv[line.substring(0, i).trim()] = line.substring(i + 1).trim()
        }
        fun req(key: String): String = kv[key] ?: error("Manifest is missing '$key'")

        val version = req("formatVersion").toInt()
        require(version == FORMAT_VERSION) {
            "Burst archive is format version $version, this build reads $FORMAT_VERSION"
        }
        val width = req("width").toInt()
        val height = req("height").toInt()
        return BurstManifest(
            formatVersion = version,
            capturedAtEpochMillis = req("capturedAtEpochMillis").toLong(),
            deviceModel = req("deviceModel"),
            androidSdkInt = req("androidSdkInt").toInt(),
            cameraId = req("cameraId"),
            width = width,
            height = height,
            intrinsics = CameraIntrinsics(
                fx = req("fx").toDouble(),
                fy = req("fy").toDouble(),
                cx = req("cx").toDouble(),
                cy = req("cy").toDouble(),
                width = width,
                height = height,
            ),
            rig = RigAlignment(
                sensorOrientationDegrees = req("sensorOrientationDegrees").toInt(),
                frontFacing = req("frontFacing").toBooleanStrict(),
                handedness = req("handedness").toInt(),
            ),
            shutterNanos = req("shutterNanos").toLong(),
            gravity = Vec3(
                req("gravityX").toDouble(),
                req("gravityY").toDouble(),
                req("gravityZ").toDouble(),
            ),
            frameCount = req("frameCount").toInt(),
            gyroSampleCount = req("gyroSampleCount").toInt(),
            notes = (kv["notes"] ?: "").split(" | ").filter { it.isNotBlank() },
        )
    }

    /** Non-blank, non-comment lines, optionally dropping a CSV header row. */
    private fun dataLines(text: String, header: Boolean = true): List<String> {
        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
        return if (header && lines.isNotEmpty()) lines.drop(1) else lines
    }
}

/** One frame's timing, and the file holding its pixels. */
data class BurstFrameRecord(
    val index: Int,
    val fileName: String,
    val sensorTimestampNanos: Long,
    val exposureTimeNanos: Long,
    val rollingShutterSkewNanos: Long,
    val width: Int,
    val height: Int,
) {
    fun toMeta(): FrameMeta = FrameMeta(
        index = index,
        sensorTimestampNanos = sensorTimestampNanos,
        exposureTimeNanos = exposureTimeNanos,
        rollingShutterSkewNanos = rollingShutterSkewNanos,
        width = width,
        height = height,
    )
}

/**
 * Everything a replay needs that is not a pixel or a timestamp.
 *
 * The intrinsics and rig travel with the burst on purpose. They are properties
 * of the device and the stream configuration, and a burst re-analysed six months
 * later against whatever constants the code happens to hold by then would be
 * measuring the wrong camera.
 */
data class BurstManifest(
    val formatVersion: Int = BurstArchive.FORMAT_VERSION,
    val capturedAtEpochMillis: Long,
    val deviceModel: String,
    val androidSdkInt: Int,
    val cameraId: String,
    val width: Int,
    val height: Int,
    val intrinsics: CameraIntrinsics,
    val rig: RigAlignment,
    /** Shutter press, in the camera timestamp domain. The burst is centred here. */
    val shutterNanos: Long,
    /** Device-frame gravity at capture, for horizon lock. */
    val gravity: Vec3,
    val frameCount: Int,
    val gyroSampleCount: Int,
    val notes: List<String> = emptyList(),
)

/** A parsed archive, ready to be integrated and aligned. */
data class BurstArchiveContents(
    val manifest: BurstManifest,
    val frames: List<BurstFrameRecord>,
    val gyro: List<GyroSample>,
) {
    /**
     * Whether the gyro trace actually brackets every frame's exposure.
     *
     * A trace that starts after the first frame or ends before the last leaves
     * [MotionTrack] clamping at the edges, which loses the correction on exactly
     * the frames furthest from the anchor - silently, and only on those frames.
     */
    fun gyroCoversFrames(): Boolean {
        if (gyro.isEmpty() || frames.isEmpty()) return false
        val first = frames.minOf { it.toMeta().firstRowMidNanos }
        val last = frames.maxOf { it.toMeta().lastRowMidNanos }
        return gyro.first().timestampNanos <= first && gyro.last().timestampNanos >= last
    }

    /** Wall-clock span from the first frame's exposure start to the last frame's. */
    val frameSpanNanos: Long
        get() = if (frames.size < 2) 0L
        else frames.maxOf { it.sensorTimestampNanos } - frames.minOf { it.sensorTimestampNanos }
}
