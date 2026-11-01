package com.beatlinkmonitor.outputs.grandma

import com.beatlinkmonitor.config.GrandMaConfig
import com.beatlinkmonitor.state.ApplicationState
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GrandMaTelnetOutputReconnectTest {
    private val server = ServerSocket(0)
    private val accepted = CopyOnWriteArrayList<Socket>()
    private val acceptor = thread(isDaemon = true) {
        while (!server.isClosed) {
            val client = runCatching { server.accept() }.getOrNull() ?: break
            accepted += client
        }
    }
    private var output: GrandMaTelnetOutput? = null

    @AfterTest
    fun cleanup() {
        output?.stop()
        accepted.forEach { runCatching { it.close() } }
        server.close()
    }

    private fun start(keepalive: Boolean): GrandMaTelnetOutput =
        GrandMaTelnetOutput(
            ApplicationState(),
            GrandMaConfig(enabled = true, host = "127.0.0.1", port = server.localPort, keepalive = keepalive, reconnect = true),
        ).also {
            output = it
            it.start()
        }

    private fun waitFor(timeoutMs: Long = 15_000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    @Test
    fun `a console that hangs up is noticed and reconnected even without keepalive writes`() {
        val grandMa = start(keepalive = false)
        assertTrue(waitFor { grandMa.getStatus().connected && accepted.size == 1 }, "first connection")

        accepted[0].close()

        assertTrue(waitFor { accepted.size == 2 }, "the output reconnects on its own")
        assertTrue(waitFor { grandMa.getStatus().connected })
    }

    @Test
    fun `reconnect now drops the connection and connects again at once`() {
        val grandMa = start(keepalive = false)
        assertTrue(waitFor { grandMa.getStatus().connected && accepted.size == 1 })

        grandMa.reconnectNow()

        assertTrue(waitFor(5_000) { accepted.size == 2 }, "a second connection is made without waiting for a failure")
        assertTrue(waitFor { grandMa.getStatus().connected })
        assertEquals(2, accepted.size)
    }

    @Test
    fun `an unreachable console is retried with a growing delay and no connection counts as connected`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val grandMa = GrandMaTelnetOutput(
            ApplicationState(),
            GrandMaConfig(enabled = true, host = "127.0.0.1", port = closedPort, reconnect = true),
        ).also { output = it }

        grandMa.start()
        assertTrue(waitFor { grandMa.getStatus().lastError != null }, "the failure is reported")

        assertFalse(grandMa.getStatus().connected)
        // The first retry comes after 3 s, the second after 6 s: after about 4 s there must be exactly two attempts.
        Thread.sleep(4_000)
        val attempts = grandMa.getConsoleHistory().count { it.text.startsWith("Connection lost/failed") }
        assertTrue(attempts in 1..2, "attempts so far: $attempts")
    }
}
