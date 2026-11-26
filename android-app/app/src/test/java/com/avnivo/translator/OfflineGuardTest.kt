package com.avnivo.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The guard that turns an unbounded native wait into a reported failure.
 *
 * The S23 Ultra report was only possible because a load and a decode could wait forever without
 * anything recording that fact. These tests pin the two behaviours that make that impossible: a
 * call that does not return within its budget fails with the stage name, and a result that arrives
 * after the caller gave up is still released instead of leaking a model.
 */
class OfflineGuardTest {

    private class FakeEngine : Closeable {
        @Volatile var closed = false
        override fun close() { closed = true }
    }

    @Test
    fun returnsTheValueWhenTheCallFinishesInTime() {
        assertEquals("matn", OfflineGuard.bounded("stage", 5_000, {}) { "matn" })
    }

    @Test
    fun propagatesTheFailureInsteadOfSwallowingIt() {
        val boom = IllegalStateException("native xatosi")
        val thrown = runCatching {
            OfflineGuard.bounded<String>("stage", 5_000, {}) { throw boom }
        }.exceptionOrNull()
        assertSame(boom, thrown)
    }

    @Test
    fun aCallThatNeverReturnsFailsWithTheStageName() {
        val gate = CountDownLatch(1)
        val started = System.currentTimeMillis()
        val thrown = runCatching {
            OfflineGuard.bounded("engine-load:test", 200, {}) {
                gate.await(5, TimeUnit.SECONDS)
                "never"
            }
        }.exceptionOrNull()
        gate.countDown()
        assertTrue("expected a stage timeout, got $thrown", thrown is OfflineGuard.StageTimeout)
        assertTrue(thrown!!.message.orEmpty().contains("engine-load:test"))
        assertTrue("the caller must not wait for the full call", System.currentTimeMillis() - started < 4_000)
    }

    @Test
    fun anEngineThatArrivesAfterTheTimeoutIsClosed() {
        val gate = CountDownLatch(1)
        val engine = FakeEngine()
        val thrown = runCatching {
            OfflineGuard.boundedCloseable("engine-load:late", 200, {}) {
                gate.await(5, TimeUnit.SECONDS)
                engine
            }
        }.exceptionOrNull()
        assertNotNull(thrown)
        gate.countDown()
        val deadline = System.currentTimeMillis() + 5_000
        while (!engine.closed && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("an abandoned load must not leak its engine", engine.closed)
    }

    @Test
    fun theDecodeBudgetGrowsWithTheRecordingAndStaysBounded() {
        val short = OfflineGuard.decodeBudgetMs(16_000)
        val long = OfflineGuard.decodeBudgetMs(160_000)
        assertTrue("a longer recording may take longer", long > short)
        assertEquals(OfflineGuard.decodeCeilingMs, OfflineGuard.decodeBudgetMs(60 * 16_000))
    }
}
