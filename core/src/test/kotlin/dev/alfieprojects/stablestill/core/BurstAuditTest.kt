package dev.alfieprojects.stablestill.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The standing questions, asked of whatever bursts are to hand.
 *
 * The fixture carries one burst's trace and no pixels, which is enough for all
 * three: crop utilisation, anchor choice and blur are decided by timestamps and
 * angular velocity. Point [BURST_ROOT_PROPERTY] at a folder of bursts to get
 * the same table across the whole collection, which is where the crop question
 * is actually settled - a margin sized from one burst is sized from an anecdote.
 */
class BurstAuditTest {

    companion object {
        /** `-Dstablestill.burstRoot=/path/to/bursts` to audit a whole collection. */
        const val BURST_ROOT_PROPERTY = "stablestill.burstRoot"
    }

    private fun fixtureDir(): File {
        val url = javaClass.classLoader.getResource("burst-sample/manifest.txt")
        assertNotNull("Burst fixture is missing from test resources", url)
        return File(url!!.toURI()).parentFile
    }

    private fun fixtureRow(): BurstAuditRow =
        BurstAudit.audit("burst-sample", BurstReader.read(fixtureDir()))

    @Test
    fun `the audit reads the burst the archive describes`() {
        val row = fixtureRow()
        assertEquals(8, row.frameCount)
        assertEquals(8, row.usableCount)
        assertEquals(12.5, row.megapixels, 0.1)
        assertEquals(20.0, row.fps, 1.0)
        assertEquals(20.0, row.exposureMs, 0.1)
        assertEquals(350.0, row.spanMs, 1.0)
        assertTrue(row.gyroCoversFrames)
        assertTrue("Gyro delivered ${row.gyroRateHz} Hz", row.gyroRateHz in 350.0..450.0)
    }

    @Test
    fun `a steady burst needs a small fraction of the margin it is given`() {
        val row = fixtureRow()
        val needed = row.minimumSafeMargin
        assertNotNull("No margin kept the burst, which means a rotation limit bound", needed)
        // The open question in docs/HANDOVER.md, now a number rather than an
        // argument: this burst is the steady one, and it needs almost nothing.
        assertTrue(
            "Needed ${needed!! * 100}% per side against the 12% given",
            needed < 0.12,
        )
        assertTrue(
            "Used ${row.marginUsed * 100}% of the slack",
            row.marginUsed < 1.0,
        )
        assertTrue(
            "Rotation ${row.maxRotationMrad} mrad against a budget of ${row.rotationBudgetMrad}",
            row.maxRotationMrad < row.rotationBudgetMrad,
        )
    }

    @Test
    fun `the anchor is chosen rather than defaulted to`() {
        val row = fixtureRow()
        // The steadiest frame is at least as steady as the average frame by
        // construction; what this pins is that the selector is doing something
        // measurable rather than returning the first index it was handed.
        assertTrue(
            "Anchor advantage was only ${row.anchorAdvantage}x",
            row.anchorAdvantage > 1.0,
        )
        assertTrue(row.anchorSteadiness <= row.meanSteadiness)
        assertTrue(row.anchorSteadiness <= row.worstSteadiness)
        assertTrue(
            "Anchor blur ${row.anchorBlurPx} px should not exceed the mean ${row.meanBlurPx}",
            row.anchorBlurPx <= row.meanBlurPx,
        )
    }

    @Test
    fun `the minimum safe margin is the smallest one that works`() {
        // The bisection's contract, checked from both sides: at the margin it
        // reports every frame survives, and a hair below it they do not.
        val burst = BurstReader.read(fixtureDir())
        val metas = burst.frames.map { it.toMeta() }
        val track = MotionTrack.integrate(burst.gyro)
        val needed = BurstAudit.minimumSafeMargin(metas, track, burst.manifest)
        assertNotNull(needed)

        fun usableAt(margin: Double): Int = BurstAligner.plan(
            frames = metas,
            track = track,
            intrinsics = burst.manifest.intrinsics,
            rig = burst.manifest.rig,
            crop = CropWindow(burst.manifest.width, burst.manifest.height, margin),
        ).usableCount

        assertEquals(metas.size, usableAt(needed!!))
        if (needed > 0.001) {
            assertTrue(
                "Margin ${needed - 0.001} should have dropped a frame",
                usableAt(needed - 0.001) < metas.size,
            )
        }
    }

    @Test
    fun `audit every burst under the given root`() {
        val root = System.getProperty(BURST_ROOT_PROPERTY)
        assumeTrue("Set -D$BURST_ROOT_PROPERTY to audit a collection", root != null)
        val dirs = File(root!!).listFiles()
            ?.filter { it.isDirectory && File(it, BurstArchive.MANIFEST_FILE).isFile }
            ?.sortedBy { it.name }
            .orEmpty()
        assumeTrue("No bursts found under $root", dirs.isNotEmpty())

        val rows = dirs.map { BurstAudit.audit(it.name, BurstReader.read(it)) }
        println(BurstAudit.format(rows))
        println(BurstAudit.summarise(rows))

        // Where the collection holds more than one frame rate, put the extremes
        // side by side - that is the 20-vs-30 fps question, asked of real bursts.
        val byRate = rows.groupBy { Math.round(it.fps) }
        if (byRate.size > 1) {
            val slowest = rows.minByOrNull { it.fps }!!
            val fastest = rows.maxByOrNull { it.fps }!!
            println(BurstAudit.compareRates(slowest, fastest))
        }

        assertTrue("Every burst should audit", rows.size == dirs.size)
    }
}
