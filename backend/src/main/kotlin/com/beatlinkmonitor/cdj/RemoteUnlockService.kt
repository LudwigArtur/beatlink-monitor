package com.beatlinkmonitor.cdj

import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

@Serializable
enum class RemoteUnlockStatus { UNLOCKED, WRONG_PIN, NO_PIN, LOCKED_OUT, UNKNOWN_CONNECTION }

@Serializable
data class RemoteUnlockResult(val status: RemoteUnlockStatus, val retryAfterSeconds: Int? = null)

@Serializable
data class RemoteUnlockInfo(
    val pinSet: Boolean,
    val unlocked: Boolean,
    /** True for the device the server runs on: it needs no PIN and is the only one that creates or changes it. */
    val local: Boolean = false,
    /** Whether other devices can reach the UI at all (the saved "Allow LAN access" setting). */
    val lanAccessEnabled: Boolean = false,
)

@Serializable
enum class SetPinStatus { SAVED, INVALID, FAILED }

@Serializable
data class SetPinResult(val status: SetPinStatus, val message: String? = null)

/**
 * Gate for everything that writes to a DJM or CDJ (mixer settings, CDJ settings, remote buttons); these act on live
 * equipment. Another device may only do so after it entered the PIN. The device the server runs on ("local") needs
 * no PIN and is the only one that creates or changes it: the PIN is created whenever "Allow LAN access" is turned on
 * and discarded when it is turned off. It is stored only as a salted PBKDF2 hash in a file outside the user-facing
 * data, so it cannot be looked up, only replaced. The unlock belongs to one WebSocket connection: it is gone when that
 * connection closes, so every device has to enter the PIN again after every (re)connect. Wrong PINs are rate limited
 * per client address and globally.
 */
class RemoteUnlockService(
    private val pinFile: () -> File,
    /** Plain text PIN file of earlier versions; never read, deleted whenever the PIN changes. */
    private val legacyPinFile: () -> File? = { null },
    private val now: () -> Long = System::currentTimeMillis,
    private val iterations: Int = PBKDF2_ITERATIONS,
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
        const val PIN_STORE_NAME = "remote-pin.hash"
        const val SECURITY_DIR_NAME = "security"
        const val LEGACY_PIN_FILE_NAME = "remote-pin.txt"
        const val MIN_PIN_LENGTH = 4
        const val MAX_PIN_LENGTH = 64
        const val MAX_ATTEMPTS = 5
        const val GLOBAL_MAX_ATTEMPTS = 15
        const val LOCKOUT_MS = 60_000L
        const val FAILURE_MEMORY_MS = 5 * 60_000L
        const val PBKDF2_ITERATIONS = 120_000
        private const val KEY_BITS = 256
    }

    fun connectionOpened(connectionId: String) {
        open += connectionId
    }

    fun connectionClosed(connectionId: String) {
        open -= connectionId
        unlocked -= connectionId
    }

    fun isUnlocked(connectionId: String?, local: Boolean = false): Boolean =
        local || (connectionId != null && connectionId in open && connectionId in unlocked)

    fun lock(connectionId: String?) {
        if (connectionId != null) unlocked -= connectionId
    }

    fun info(connectionId: String?, local: Boolean = false, lanAccessEnabled: Boolean = false): RemoteUnlockInfo =
        RemoteUnlockInfo(isPinSet(), isUnlocked(connectionId, local), local, lanAccessEnabled)

    fun isPinSet(): Boolean = readStored() != null

    private class Stored(val iterations: Int, val salt: ByteArray, val hash: ByteArray)

    private fun hash(pin: String, salt: ByteArray, rounds: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, rounds, KEY_BITS)).encoded

    /** Format: `pbkdf2-sha256$<iterations>$<salt base64>$<hash base64>`, read on every attempt. */
    private fun readStored(): Stored? {
        val file = pinFile()
        if (!file.isFile) return null
        val parts = runCatching { file.readText(Charsets.UTF_8).trim().split('$') }.getOrNull() ?: return null
        if (parts.size != 4 || parts[0] != "pbkdf2-sha256") return null
        return runCatching {
            Stored(parts[1].toInt(), Base64.getDecoder().decode(parts[2]), Base64.getDecoder().decode(parts[3]))
        }.getOrNull()
    }

    /** Stores the PIN as a hash and locks every remote connection again, so a changed PIN takes effect at once. */
    fun setPin(pin: String): SetPinResult {
        val value = pin.trim()
        if (value.length < MIN_PIN_LENGTH || value.length > MAX_PIN_LENGTH || value.any { it.isWhitespace() || it.isISOControl() }) {
            return SetPinResult(SetPinStatus.INVALID, "The PIN needs $MIN_PIN_LENGTH to $MAX_PIN_LENGTH characters without spaces.")
        }
        val file = pinFile().absoluteFile
        return try {
            val dir = file.parentFile
            dir.mkdirs()
            runCatching { Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwx------")) }
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val encoder = Base64.getEncoder()
            val line = "pbkdf2-sha256\$$iterations\$${encoder.encodeToString(salt)}\$${encoder.encodeToString(hash(value, salt, iterations))}\n"
            val temp = File.createTempFile("remote-pin", ".tmp", dir)
            try {
                runCatching { Files.setPosixFilePermissions(temp.toPath(), PosixFilePermissions.fromString("rw-------")) }
                temp.writeText(line, Charsets.UTF_8)
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally {
                temp.delete()
            }
            legacyPinFile()?.delete()
            unlocked.clear()
            SetPinResult(SetPinStatus.SAVED)
        } catch (e: Exception) {
            SetPinResult(SetPinStatus.FAILED, "The PIN could not be stored: ${e.message}")
        }
    }

    /** Discards the PIN (LAN access was turned off), so the next activation has to create a new one. */
    fun clearPin() {
        pinFile().delete()
        legacyPinFile()?.delete()
        unlocked.clear()
    }

    fun unlock(connectionId: String?, pin: String, clientKey: String): RemoteUnlockResult {
        if (connectionId == null || connectionId !in open) return RemoteUnlockResult(RemoteUnlockStatus.UNKNOWN_CONNECTION)
        val time = now()
        val client = failures.computeIfAbsent(clientKey) { Failures() }
        synchronized(this) {
            val wait = maxOf(client.lockedUntil, globalFailures.lockedUntil) - time
            if (wait > 0) return RemoteUnlockResult(RemoteUnlockStatus.LOCKED_OUT, ((wait + 999) / 1000).toInt())
        }
        val stored = readStored() ?: return RemoteUnlockResult(RemoteUnlockStatus.NO_PIN)
        val match = MessageDigest.isEqual(stored.hash, hash(pin.trim(), stored.salt, stored.iterations))
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
