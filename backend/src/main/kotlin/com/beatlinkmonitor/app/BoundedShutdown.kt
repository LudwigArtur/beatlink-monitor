package com.beatlinkmonitor.app

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Runs a shutdown step on its own daemon thread and waits for it at most [timeoutMs]. Closing sockets can block for a
 * long time when the operating system (or a network filter such as a VPN client's content filter) holds a kernel lock;
 * the macOS hang reports of the show on 31 October showed the JavaFX thread stuck in `dup2` while the application was
 * being quit. A step that does not finish in time is left running in the background, so quitting is not held up by it.
 */
object BoundedShutdown {
    /** Returns true if the step finished within the timeout (or failed with an exception, which is logged). */
    fun run(name: String, timeoutMs: Long = 3_000L, block: () -> Unit): Boolean {
        var failure: Throwable? = null
        val thread = Thread({
            try {
                block()
            } catch (e: Throwable) {
                failure = e
            }
        }, "shutdown-$name").apply { isDaemon = true }
        thread.start()
        thread.join(timeoutMs)
        if (thread.isAlive) {
            logger.warn { "Shutdown step '$name' did not finish within ${timeoutMs / 1000} s - continuing without waiting for it" }
            return false
        }
        failure?.let { logger.warn(it) { "Shutdown step '$name' failed" } }
        return true
    }
}
