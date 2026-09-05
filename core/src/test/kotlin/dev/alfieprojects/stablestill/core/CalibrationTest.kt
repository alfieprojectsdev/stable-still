package dev.alfieprojects.stablestill.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CalibrationTest {

    @Test
    fun `offset search recovers a known offset`() {
        val truth = -7_300_000L // -7.3 ms
        val found = SyncCalibration.search(searchRangeNanos = 30_000_000L) { c ->
            val err = (c.offsetNanos - truth) * 1e-6
            err * err
        }
        assertEquals(truth.toDouble(), found.offsetNanos.toDouble(), 400_000.0) // within 0.4 ms
    }

    @Test
    fun `offset search survives a noisy bumpy cost curve`() {
        val truth = 4_000_000L
        val found = SyncCalibration.search(searchRangeNanos = 20_000_000L, coarseSteps = 41) { c ->
            val err = (c.offsetNanos - truth) * 1e-6
            err * err + 0.05 * kotlin.math.sin(c.offsetNanos * 1e-5)
        }
        assertTrue(
            "expected within 1 ms, got ${found.offsetNanos}",
            abs(found.offsetNanos - truth) < 1_000_000L,
        )
    }

    @Test
    fun `clock conversion round trips`() {
        val c = SyncCalibration(1_234_567L)
        val camera = 987_654_321L
        assertEquals(camera, c.toCameraClock(c.toGyroClock(camera)))
    }

    @Test
    fun `crop window dimensions are even and the budget is sane`() {
        val crop = CropWindow(4000, 3000, 0.10)
        assertEquals(0, crop.outputWidth % 2)
        assertEquals(0, crop.outputHeight % 2)
        assertEquals(3200, crop.outputWidth)
        assertEquals(2400, crop.outputHeight)

        val k = CameraIntrinsics.fromHorizontalFov(78.0, 4000, 3000)
        val budget = crop.rotationBudgetRadians(k)
        // 300 px of vertical slack at f ~ 2500 px is roughly 0.12 rad.
        assertTrue("budget was $budget", budget in 0.05..0.30)
    }

    @Test
    fun `zero margin still yields a valid window`() {
        val crop = CropWindow(1920, 1080, 0.0)
        assertEquals(1920, crop.outputWidth)
        assertEquals(1080, crop.outputHeight)
        assertEquals(0.0, crop.rotationBudgetRadians(CameraIntrinsics.fromHorizontalFov(70.0, 1920, 1080)), 1e-12)
    }
}
