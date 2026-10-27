package com.beatlinkmonitor.cdj

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RemoteUnlockServiceTest {
    private val dir: File = Files.createTempDirectory("pin-test").toFile()
    private val file = File(dir, RemoteUnlockService.PIN_FILE_NAME)
    private var clock = 1_000_000L
    private val service = RemoteUnlockService({ file }, { clock })

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun `nothing can be unlocked without a pin file, and the service never creates one`() {
        service.connectionOpened("c1")

        assertEquals(RemoteUnlockStatus.NO_PIN_FILE, service.unlock("c1", "1234", "host").status)
        assertFalse(service.info("c1").pinFilePresent)
        assertFalse(file.exists())
        assertFalse(service.isUnlocked("c1"))
    }

    @Test
    fun `a too short pin file is refused`() {
        file.writeText("12\n")
        service.connectionOpened("c1")

        assertEquals(RemoteUnlockStatus.INVALID_PIN_FILE, service.unlock("c1", "12", "host").status)
    }

    @Test
    fun `the correct pin unlocks only that connection and closing it locks again`() {
        file.writeText("\n  482913  \nignored line\n")
        service.connectionOpened("c1")
        service.connectionOpened("c2")

        assertEquals(RemoteUnlockStatus.WRONG_PIN, service.unlock("c1", "000000", "host").status)
        assertFalse(service.isUnlocked("c1"))
        assertEquals(RemoteUnlockStatus.UNLOCKED, service.unlock("c1", " 482913 ", "host").status)
        assertTrue(service.isUnlocked("c1"))
        assertFalse(service.isUnlocked("c2"))
        assertTrue(service.info("c1").unlocked)

        service.connectionClosed("c1")
        assertFalse(service.isUnlocked("c1"))
        // The same id cannot be revived after the connection is gone.
        assertEquals(RemoteUnlockStatus.UNKNOWN_CONNECTION, service.unlock("c1", "482913", "host").status)
        assertFalse(service.isUnlocked("c1"))
    }

    @Test
    fun `unknown or missing connection ids never count as unlocked`() {
        file.writeText("482913")

        assertEquals(RemoteUnlockStatus.UNKNOWN_CONNECTION, service.unlock("nope", "482913", "host").status)
        assertEquals(RemoteUnlockStatus.UNKNOWN_CONNECTION, service.unlock(null, "482913", "host").status)
        assertFalse(service.isUnlocked(null))
        assertFalse(service.isUnlocked("nope"))
    }

    @Test
    fun `explicit lock revokes the unlock`() {
        file.writeText("482913")
        service.connectionOpened("c1")
        service.unlock("c1", "482913", "host")

        service.lock("c1")

        assertFalse(service.isUnlocked("c1"))
    }

    @Test
    fun `repeated wrong pins lock the client out for a minute even for the right pin`() {
        file.writeText("482913")
        service.connectionOpened("c1")
        repeat(RemoteUnlockService.MAX_ATTEMPTS) { assertEquals(RemoteUnlockStatus.WRONG_PIN, service.unlock("c1", "0000", "evil").status) }

        val locked = service.unlock("c1", "482913", "evil")
        assertEquals(RemoteUnlockStatus.LOCKED_OUT, locked.status)
        assertNotNull(locked.retryAfterSeconds)
        assertFalse(service.isUnlocked("c1"))
        // Another client is not affected by that lockout.
        assertEquals(RemoteUnlockStatus.UNLOCKED, service.unlock("c1", "482913", "friend").status)

        clock += RemoteUnlockService.LOCKOUT_MS + 1
        service.connectionOpened("c2")
        assertEquals(RemoteUnlockStatus.UNLOCKED, service.unlock("c2", "482913", "evil").status)
    }

    @Test
    fun `many wrong pins from different clients trigger a global lockout`() {
        file.writeText("482913")
        service.connectionOpened("c1")
        repeat(RemoteUnlockService.GLOBAL_MAX_ATTEMPTS) { service.unlock("c1", "0000", "host$it") }

        assertEquals(RemoteUnlockStatus.LOCKED_OUT, service.unlock("c1", "482913", "another").status)
    }
}
