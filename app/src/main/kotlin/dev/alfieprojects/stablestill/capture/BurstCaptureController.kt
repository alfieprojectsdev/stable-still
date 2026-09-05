package dev.alfieprojects.stablestill.capture

import android.content.Context
import android.os.SystemClock
import android.util.Size
import dev.alfieprojects.stablestill.motion.GyroRecorder
import dev.alfieprojects.stablestill.pipeline.BurstReplayer
import dev.alfieprojects.stablestill.pipeline.ReplayResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One of the resolutions worth saving a burst at.
 *
 * The A07 offers 12.5 MP only at 20 fps and 8 MP at 30, so a twelve-frame burst
 * spans either 550 ms or 367 ms. Which of those aligns better is the open
 * question `docs/DEVICE-A07.md` says to settle against a saved burst rather than
 * by argument, so both are offered here instead of one being chosen in advance.
 */
data class CaptureOption(val label: String, val size: Size, val fps: Int) {
    val frameBytes: Long get() = size.width.toLong() * size.height * 3 / 2

    companion object {
        val FULL = CaptureOption("12.5 MP - 20 fps", Size(4080, 3060), 20)
        val FAST = CaptureOption("8 MP - 30 fps", Size(3264, 2448), 30)
        val ALL = listOf(FULL, FAST)
    }
}

/**
 * Runs the camera and the gyro recorder together, and writes one burst on demand.
 *
 * Deliberately thinner than [dev.alfieprojects.stablestill.pipeline.StillStacker]:
 * nothing here warps, merges or even looks at a pixel. The handover asks for a
 * saved burst *before* the GPU path is trusted, precisely so that when the merge
 * does misbehave there is already a known-good input to feed it.
 */
class BurstCaptureController(private val context: Context) {

    private var engine: CaptureEngine? = null
    private var recorder: GyroRecorder? = null

    var option: CaptureOption = CaptureOption.FULL
        private set
    var ringCapacity: Int = 8
        private set
    var maxExposureNanos: Long? = null
        private set

    /** What AE actually settled on, once the cap has been applied. */
    val exposureNanos: Long get() = engine?.appliedExposureNanos ?: 0L
    val iso: Int get() = engine?.appliedIso ?: 0

    val running: Boolean get() = engine != null
    val deliveredFrames: Long get() = engine?.deliveredFrames ?: 0L
    val bufferedFrames: Int get() = engine?.ringBuffer?.size ?: 0
    val hasGyroscope: Boolean get() = recorder?.hasGyroscope ?: false

    /** Where bursts land. On the app's external files dir, so `adb pull` reaches it. */
    val outputRoot: File
        get() = File(context.getExternalFilesDir(null), "bursts")

    suspend fun start(option: CaptureOption, ringCapacity: Int, maxExposureNanos: Long?) {
        stop()
        this.option = option
        this.ringCapacity = ringCapacity
        this.maxExposureNanos = maxExposureNanos

        // The recorder starts first and on purpose. Its window has to already
        // extend backwards past the oldest buffered frame by the time a burst is
        // taken, and a gyro trace that begins after the frames do cannot be
        // fixed after the fact.
        val r = GyroRecorder(context).also { it.start() }
        recorder = r

        val e = CaptureEngine(context, ringCapacity)
        try {
            e.start(
                preview = null,
                requestedSize = option.size,
                targetFps = option.fps,
                maxExposureNanos = maxExposureNanos,
            )
            engine = e
        } catch (t: Throwable) {
            e.stop()
            r.stop()
            recorder = null
            throw t
        }
    }

    fun stop() {
        engine?.stop()
        engine = null
        recorder?.stop()
        recorder = null
    }

    /**
     * Takes the burst sitting in the ring buffer and writes it to disk.
     *
     * The frames are closed in a `finally` whatever happens. Every one of them
     * holds an ImageReader slot, and a leak here does not raise anything - the
     * camera just quietly stops delivering.
     */
    suspend fun saveBurst(): SavedBurst = withContext(Dispatchers.IO) {
        val e = engine ?: error("Capture is not running")
        val r = recorder ?: error("Capture is not running")

        // elapsedRealtimeNanos, never System.nanoTime(). The camera's
        // SENSOR_TIMESTAMP source is REALTIME, which is CLOCK_BOOTTIME and counts
        // through suspend; nanoTime is CLOCK_MONOTONIC and does not. On a phone
        // that has been asleep the two are days apart - measured at 37 hours on
        // this handset - so a shutter time taken from the wrong clock lands
        // outside the ring buffer entirely and silently anchors the burst to its
        // oldest frames.
        val shutterNanos = SystemClock.elapsedRealtimeNanos()
        val frames = e.takeBurst(ringCapacity, shutterNanos)
        check(frames.isNotEmpty()) {
            "The ring buffer is empty. Give the camera a moment to fill it."
        }
        try {
            val firstExposure = frames.minOf { it.meta.firstRowMidNanos }
            val lastExposure = frames.maxOf { it.meta.lastRowMidNanos }
            val gyro = r.slice(firstExposure, lastExposure)

            val notes = mutableListOf<String>()
            if (!r.hasGyroscope) notes += "No gyroscope on this device; the trace is empty."
            if (frames.size < ringCapacity) {
                notes += "Only ${frames.size} of $ringCapacity frames were buffered."
            }
            notes += "Captured at ${option.label}."
            val exposure = frames.first().meta.exposureTimeNanos
            maxExposureNanos?.let { cap ->
                if (exposure > cap) {
                    notes += "Exposure cap of ${cap / 1_000_000} ms was requested but " +
                        "frames came back at ${exposure / 1_000_000} ms; ISO could not " +
                        "cover the shortfall."
                }
            }
            if (frames.map { it.meta.exposureTimeNanos }.distinct().size > 1) {
                notes += "Exposure varies across the burst; the merge cannot absorb that."
            }

            BurstWriter(outputRoot).write(
                frames = frames,
                gyro = gyro,
                cameraId = e.cameraId ?: "unknown",
                intrinsics = e.intrinsics ?: error("Intrinsics were never built"),
                rig = e.rig ?: error("Rig alignment was never built"),
                shutterNanos = shutterNanos,
                gravity = r.gravity(),
                extraNotes = notes,
            )
        } finally {
            frames.forEach { it.close() }
        }
    }

    /** The command that pulls a saved burst to a laptop. */
    fun pullCommand(burst: SavedBurst): String =
        "adb pull ${burst.directory.absolutePath}"

    /** Saved bursts, newest first. */
    fun savedBursts(): List<File> =
        outputRoot.listFiles { f: File -> f.isDirectory }?.sortedDescending() ?: emptyList()

    /**
     * Stacks the most recent saved burst on the GPU.
     *
     * Runs against fixed input on purpose. A live capture differs every time -
     * light, tremor, anchor - so a merge that looks wrong says nothing about
     * whether the shaders or the hand were at fault. Replaying a saved burst
     * holds the pixels and the plan still, and any change in the output came
     * from the code.
     */
    suspend fun replayLatest(): ReplayResult = withContext(Dispatchers.IO) {
        val latest = savedBursts().firstOrNull() ?: error("No saved bursts to replay")
        BurstReplayer(File(context.getExternalFilesDir(null), "captures")).replay(latest)
    }
}
