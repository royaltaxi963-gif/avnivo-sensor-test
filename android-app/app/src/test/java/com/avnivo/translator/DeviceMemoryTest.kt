package com.avnivo.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Galaxy S23 Ultra reported "xotira yetarli emas: 2765 MB kerak, 1768 MB mavjud".
 *
 * Those numbers are NLLB's requirement in MiB against the system-wide free memory. Android keeps
 * free RAM low on purpose, so that comparison can never succeed on a healthy phone, and it refused
 * a model the device could comfortably load. These tests pin the replacement: a share of physical
 * RAM, refused only under real pressure.
 */
class DeviceMemoryTest {

    private fun snapshot(
        totalMb: Long,
        availMb: Long,
        low: Boolean = false,
        pssMb: Long = 0
    ) = MemorySnapshot(
        totalMem = totalMb * MB,
        availMem = availMb * MB,
        lowMemory = low,
        appPssBytes = pssMb * MB,
        nativeHeapBytes = 0,
        vmHeapMax = 256 * MB
    )

    @Test fun theReportedDeviceNowAcceptsNllb() {
        // Exactly the numbers from the S23 Ultra report: 1768 MB "available", NLLB needs 2765 MB.
        // The old gate refused; with 12 GB of physical RAM the model fits comfortably.
        val device = snapshot(totalMb = 12_000, availMb = 1768)
        assertTrue(
            "NLLB must fit on a 12 GB phone that reports 1768 MB free",
            device.fits(2_900_000_000L)
        )
    }

    @Test fun theOldGateWouldHaveRefusedTheSameNumbers() {
        // Guards the regression itself: free memory alone is smaller than the requirement, which
        // is what made the previous check always fail on this device.
        val device = snapshot(totalMb = 12_000, availMb = 1768)
        assertTrue(device.availMem < 2_900_000_000L)
    }

    @Test fun aModelLargerThanItsShareIsRefused() {
        // A 3 GB phone cannot give 2.9 GB to one model; refusing is the honest answer.
        val small = snapshot(totalMb = 3_000, availMb = 900)
        assertFalse(small.fits(2_900_000_000L))
    }

    @Test fun realPressureRefusesEverything() {
        val pressured = snapshot(totalMb = 12_000, availMb = 3_000, low = true)
        assertEquals("the low-memory killer is already active", 0L, pressured.modelBudgetBytes)
        assertFalse(pressured.fits(1L))
    }

    @Test fun criticallyLowFreeMemoryRefusesEvenWithoutTheFlag() {
        // Android sets lowMemory late; below the reserve the system is already unsafe.
        val nearlyOut = snapshot(totalMb = 12_000, availMb = 200)
        assertEquals(0L, nearlyOut.modelBudgetBytes)
    }

    @Test fun theBudgetNeverExceedsHalfOfPhysicalRam() {
        val huge = snapshot(totalMb = 24_000, availMb = 20_000)
        assertTrue(
            "one model must never claim more than half the device",
            huge.modelBudgetBytes <= 12_000 * MB
        )
    }

    @Test fun theReasonNamesEveryNumber() {
        val device = snapshot(totalMb = 3_000, availMb = 900, pssMb = 400)
        val text = device.describe(2_900_000_000L)
        assertTrue("must state the requirement: $text", text.contains("2765"))
        assertTrue("must state the budget: $text", text.contains("ruxsat"))
        assertTrue("must state physical RAM: $text", text.contains("3000"))
        assertTrue("must state process usage: $text", text.contains("400"))
    }

    private companion object {
        const val MB = 1024L * 1024L
    }
}
