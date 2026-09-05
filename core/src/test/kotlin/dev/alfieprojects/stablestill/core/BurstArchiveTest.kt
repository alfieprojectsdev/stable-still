package dev.alfieprojects.stablestill.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BurstArchiveTest {

    private val intrinsics = CameraIntrinsics(
        fx = 3110.4, fy = 3098.2, cx = 2040.0, cy = 1530.0, width = 4080, height = 3060,
    )

    private val manifest = BurstManifest(
        capturedAtEpochMillis = 1_757_030_000_000L,
        deviceModel = "SM-A076B",
        androidSdkInt = 36,
        cameraId = "0",
        width = 4080,
        height = 3060,
        intrinsics = intrinsics,
        rig = RigAlignment(sensorOrientationDegrees = 90, frontFacing = false, handedness = 1),
        shutterNanos = 12_345_678_900_000L,
        gravity = Vec3(0.11, -9.79, 0.42),
        frameCount = 3,
        gyroSampleCount = 4,
        notes = listOf("first note", "second note"),
    )

    private fun frames(baseNanos: Long, count: Int, periodNanos: Long) =
        (0 until count).map { i ->
            BurstFrameRecord(
                index = i,
                fileName = BurstArchive.frameFileName(i),
                sensorTimestampNanos = baseNanos + i * periodNanos,
                exposureTimeNanos = 20_000_000L,
                rollingShutterSkewNanos = 0L,
                width = 4080,
                height = 3060,
            )
        }

    @Test
    fun `manifest survives a round trip`() {
        val parsed = BurstArchive.readManifest(BurstArchive.writeManifest(manifest))
        assertEquals(manifest, parsed)
    }

    @Test
    fun `frame records survive a round trip`() {
        val records = frames(1_000_000_000L, 12, 50_000_000L)
        assertEquals(records, BurstArchive.readFrames(BurstArchive.writeFrames(records)))
    }

    @Test
    fun `gyro samples survive a round trip without losing precision`() {
        // The measured zero-rate offset on the A07 is ~1.3e-4 rad/s. A format
        // that rounded to a few decimals would erase it, and erase any hope of
        // reproducing an alignment offline.
        val samples = listOf(
            GyroSample(1_000_000_000L, Vec3(-1.1388180368788996e-4, 7.71683590093843e-6, -5.37840133140079e-5)),
            GyroSample(1_002_500_000L, Vec3(0.0026485863531719, -0.0011, 0.34906299591064)),
        )
        val parsed = BurstArchive.readGyro(BurstArchive.writeGyro(samples))
        assertEquals(samples, parsed)
    }

    @Test
    fun `a version 1 archive still reads, with no ISO rather than zero gain`() {
        // Bursts captured before the ISO column existed must stay readable: a
        // reader can know what an older archive meant, which is the whole reason
        // the version check is one-sided.
        val v1Manifest = BurstArchive.writeManifest(manifest)
            .replace("formatVersion=${BurstArchive.FORMAT_VERSION}", "formatVersion=1")
        assertEquals(1, BurstArchive.readManifest(v1Manifest).formatVersion)

        val v1Frames = """
            index,fileName,sensorTimestampNanos,exposureTimeNanos,rollingShutterSkewNanos,width,height
            0,frame_00.i420,270758240298000,50000000,27406628,4080,3060
        """.trimIndent()
        val parsed = BurstArchive.readFrames(v1Frames).single()
        assertEquals(27_406_628L, parsed.rollingShutterSkewNanos)
        assertEquals(0, parsed.sensitivityIso)
    }

    @Test
    fun `a reader refuses an archive written by a newer format`() {
        val text = BurstArchive.writeManifest(manifest)
            .replace("formatVersion=${BurstArchive.FORMAT_VERSION}", "formatVersion=99")
        val error = runCatching { BurstArchive.readManifest(text) }.exceptionOrNull()
        assertTrue("Expected a refusal, got $error", error is IllegalArgumentException)
    }

    @Test
    fun `gyro coverage is judged against exposure edges, not frame timestamps`() {
        // A trace that brackets every SENSOR_TIMESTAMP can still miss the last
        // frame's exposure, because that timestamp is the *start* of the first
        // row. Coverage has to be judged against the exposure midpoints, which
        // is precisely the edge case that silently drops the outermost frames.
        val f = frames(baseNanos = 1_000_000_000L, count = 3, periodNanos = 50_000_000L)
        val lastTimestamp = f.last().sensorTimestampNanos

        val tooShort = BurstArchiveContents(
            manifest = manifest,
            frames = f,
            gyro = listOf(
                GyroSample(f.first().sensorTimestampNanos, Vec3.ZERO),
                GyroSample(lastTimestamp, Vec3.ZERO),
            ),
        )
        assertFalse(tooShort.gyroCoversFrames())

        val padded = tooShort.copy(
            gyro = listOf(
                GyroSample(f.first().sensorTimestampNanos - 50_000_000L, Vec3.ZERO),
                GyroSample(lastTimestamp + 50_000_000L, Vec3.ZERO),
            ),
        )
        assertTrue(padded.gyroCoversFrames())
    }

    @Test
    fun `frame span reports the wall clock cost of the burst`() {
        // Twelve frames at the A07's 20 fps full-resolution cap span 550 ms,
        // not 600: eleven intervals, not twelve.
        val f = frames(baseNanos = 0L, count = 12, periodNanos = 50_000_000L)
        val contents = BurstArchiveContents(manifest, f, emptyList())
        assertEquals(550_000_000L, contents.frameSpanNanos)
    }

    @Test
    fun `an archive replays into a motion track covering every frame`() {
        val f = frames(baseNanos = 1_000_000_000L, count = 4, periodNanos = 50_000_000L)
        val gyro = (0..200).map { i ->
            val t = 900_000_000L + i * 2_500_000L
            // Oscillatory, never constant-rate: a constant rate cancels clock
            // offsets exactly and would prove nothing about the replay.
            val phase = i * 0.05
            GyroSample(t, Vec3(0.02 * kotlin.math.sin(phase), 0.01 * kotlin.math.cos(phase), 0.0))
        }
        val contents = BurstArchiveContents(manifest, f, gyro)
        assertTrue(contents.gyroCoversFrames())

        val track = MotionTrack.integrate(contents.gyro)
        for (record in contents.frames) {
            assertTrue(
                "Track does not cover frame ${record.index}",
                track.covers(record.toMeta().midExposureNanos),
            )
        }
        // A real rotation was integrated, so the ends must differ.
        val rotation = track.rotationBetween(track.startNanos, track.endNanos)
        assertTrue("Expected a non-trivial rotation", rotation.angle() > 1e-4)
    }
}
