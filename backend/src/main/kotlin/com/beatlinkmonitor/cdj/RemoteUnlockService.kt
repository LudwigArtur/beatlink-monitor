package com.beatlinkmonitor.cdj

import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

@Serializable
enum class RemoteUnlockStatus { UNLOCKED, WRONG_PIN, NO_PIN_FILE, INVALID_PIN_FILE, LOCKED_OUT, UNKNOWN_CONNECTION }

@Serializable
data class RemoteUnlockResult(val status: RemoteUnlockStatus, val retryAfterSeconds: Int? = null)

@Serializable
data class RemoteUnlockInfo(val pinFileName: String, val pinFilePresent: Boolean, val unlocked: Boolean)

/**
 * Gate for the remote CDJ buttons, which act on live playback. A browser (or any other client) may only press
 * them after it entered the PIN stored in a file on the server that runs the backend. The file is never created
 * by the application, so without it nothing can be unlocked. The unlock belongs to one WebSocket connection: it
 * is gone when that connection closes, so every device has to enter the PIN again after every (re)connect.
 * Wrong PINs are rate limited per client address and globally.
 */
class RemoteUnlockService(
    private val pinFile: () -> File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val open = ConcurrentHashMap.newKeySet<String>()
    private val unlocked = ConcurrentHashMap.newKeySet<String>()
    private val failures = ConcurrentHashMap<String, Failures>()
    private val globalFailures = Failures()

    private class Failures {
        var count = 0
        var lockedUntil = 0L
        var lastFailureAt = 0L
    }

    companion object {
        const val PIN_FILE_NAME = "remote-pin.txt"
        const val MIN_PIN_LENGTH = 4
        const val MAX_ATTEMPTS = 5
        const val GLOBAL_MAX_ATTEMPTS = 15
        const val LOCKOUT_MS = 60_000L
        const val FAILURE_MEMORY_MS = 5 * 60_000L
    }

    fun connectionOpened(connectionId: String) {
        open += connectionId
    }

    fun connectionClosed(connectionId: String) {
        open -= connectionId
        unlocked -= connectionId
    }

    fun isUnlocked(connectionId: String?): Boolean = connectionId != null && connectionId in open && connectionId in unlocked

    fun lock(connectionId: String?) {
        if (connectionId != null) unlocked -= connectionId
    }

    fun info(connectionId: String?): RemoteUnlockInfo =
        RemoteUnlockInfo(PIN_FILE_NAME, readPin() is PinFile.Valid, isUnlocked(connectionId))

    private sealed interface PinFile {
        data object Missing : PinFile
        data object Invalid : PinFile
        class Valid(val pin: ByteArray) : PinFile
    }

    /** First non-empty line of the file, trimmed; read on every attempt so a new PIN needs no restart. */
    private fun readPin(): PinFile {
        val file = pinFile()
        if (!file.isFile) return PinFile.Missing
        val pin = runCatching { file.readLines(Charsets.UTF_8).map { it.trim() }.firstOrNull { it.isNotEmpty() } }.getOrNull()
        return if (pin == null || pin.length < MIN_PIN_LENGTH) PinFile.Invalid else PinFile.Valid(pin.toByteArray(Charsets.UTF_8))
    }

    fun unlock(connectionId: String?, pin: String, clientKey: String): RemoteUnlockResult {
        if (connectionId == null || connectionId !in open) return RemoteUnlockResult(RemoteUnlockStatus.UNKNOWN_CONNECTION)
        val time = now()
        val client = failures.computeIfAbsent(clientKey) { Failures() }
        synchronized(this) {
            val wait = maxOf(client.lockedUntil, globalFailures.lockedUntil) - time
            if (wait > 0) return RemoteUnlockResult(RemoteUnlockStatus.LOCKED_OUT, ((wait + 999) / 1000).toInt())
        }
        val stored = when (val file = readPin()) {
            PinFile.Missing -> return RemoteUnlockResult(RemoteUnlockStatus.NO_PIN_FILE)
            PinFile.Invalid -> return RemoteUnlockResult(RemoteUnlockStatus.INVALID_PIN_FILE)
            is PinFile.Valid -> file.pin
        }
        // Compare digests so that the comparison takes the same time whatever the entered PIN looks like.
        val sha = MessageDigest.getInstance("SHA-256")
        val match = MessageDigest.isEqual(sha.digest(stored), sha.digest(pin.trim().toByteArray(Charsets.UTF_8)))
        synchronized(this) {
            if (match) {
                client.count = 0
                unlocked += connectionId
                return RemoteUnlockResult(RemoteUnlockStatus.UNLOCKED)
            }
            if (time - client.lastFailureAt > FAILURE_MEMORY_MS) client.count = 0
            if (time - globalFailures.lastFailureAt > FAILURE_MEMORY_MS) globalFailures.count = 0
            client.lastFailureAt = time
            globalFailures.lastFailureAt = time
            client.count++
            globalFailures.count++
            if (client.count >= MAX_ATTEMPTS) {
                client.count = 0
                client.lockedUntil = time + LOCKOUT_MS
            }
            if (globalFailures.count >= GLOBAL_MAX_ATTEMPTS) {
                globalFailures.count = 0
                globalFailures.lockedUntil = time + LOCKOUT_MS
            }
        }
        return RemoteUnlockResult(RemoteUnlockStatus.WRONG_PIN)
    }
}
