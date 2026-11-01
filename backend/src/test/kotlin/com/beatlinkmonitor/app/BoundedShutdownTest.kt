package com.beatlinkmonitor.app

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoundedShutdownTest {
    @Test
    fun `a step that finishes is waited for`() {
        val done = AtomicBoolean(false)

        val finished = BoundedShutdown.run("quick", 2_000) { done.set(true) }

        assertTrue(finished)
        assertTrue(done.get())
    }

    @Test
    fun `a step that hangs does not hold up the caller`() {
        val started = System.currentTimeMillis()

        val finished = BoundedShutdown.run("stuck", 300) { Thread.sleep(10_000) }

        assertFalse(finished)
        assertTrue(System.currentTimeMillis() - started < 3_000)
    }

    @Test
    fun `a failing step does not propagate`() {
        assertTrue(BoundedShutdown.run("failing", 2_000) { error("boom") })
    }
}
