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
    private val file = File(File(dir, RemoteUnlockService.SECURITY_DIR_NAME), RemoteUnlockService.PIN_STORE_NAME)
    private val legacy = File(dir, RemoteUnlockService.LEGACY_PIN_FILE_NAME)
    private var clock = 1_000_000L
    private val service = RemoteUnlockService({ file }, { legacy }, { clock }, iterations = 1_000)

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun `nothing can be unlocked while no pin is set`() {
        service.connectionOpened("c1")

        assertEquals(RemoteUnlockStatus.NO_PIN, service.unlock("c1", "1234", "host").status)
        assertFalse(service.info("c1").pinSet)
        assertFalse(file.exists())
        assertFalse(service.isUnlocked("c1"))
    }

    @Test
    fun `the pin is stored only as a salted hash, never as text`() {
        assertEquals(SetPinStatus.SAVED, service.setPin(" 482913 ").status)

        val stored = file.readText()
        assertFalse(stored.contains("482913"))
        assertTrue(stored.startsWith("pbkdf2-sha256\$1000\$"))
        assertTrue(service.isPinSet())
        assertTrue(service.info(null).pinSet)

        // The same PIN gets a different salt every time.
        service.setPin("482913")
        assertTrue(file.readText() != stored)
    }

    @Test
    fun `the stored pin file is not accessible to others`() {
        service.setPin("482913")

        val permissions = Files.getPosixFilePermissions(file.toPath()).map { it.toString() }
        assertEquals(listOf("OWNER_READ", "OWNER_WRITE"), permissions.sorted())
    }

    @Test
    fun `a corrupt store counts as no pin`() {
        file.parentFile.mkdirs()
        file.writeText("garbage")
        service.connectionOpened("c1")

        assertFalse(service.isPinSet())
        assertEquals(RemoteUnlockStatus.NO_PIN, service.unlock("c1", "garbage", "host").status)
    }

    @Test
    fun `the correct pin unlocks only that connection and closing it locks again`() {
        service.setPin("482913")
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
        service.setPin("482913")

        assertEquals(RemoteUnlockStatus.UNKNOWN_CONNECTION, service.unlock("nope", "482913", "host").status)
        assertEquals(RemoteUnlockStatus.UNKNOWN_CONNECTION, service.unlock(null, "482913", "host").status)
        assertFalse(service.isUnlocked(null))
        assertFalse(service.isUnlocked("nope"))
    }

    @Test
    fun `explicit lock revokes the unlock`() {
        service.setPin("482913")
        service.connectionOpened("c1")
        service.unlock("c1", "482913", "host")

        service.lock("c1")

        assertFalse(service.isUnlocked("c1"))
    }

    @Test
    fun `repeated wrong pins lock the client out for a minute even for the right pin`() {
        service.setPin("482913")
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
        service.setPin("482913")
        service.connectionOpened("c1")
        repeat(RemoteUnlockService.GLOBAL_MAX_ATTEMPTS) { service.unlock("c1", "0000", "host$it") }

        assertEquals(RemoteUnlockStatus.LOCKED_OUT, service.unlock("c1", "482913", "another").status)
    }

    @Test
    fun `the local device is always allowed and reported as local`() {
        assertTrue(service.isUnlocked(null, local = true))
        assertTrue(service.info(null, local = true, lanAccessEnabled = true).local)
        assertTrue(service.info(null, local = true, lanAccessEnabled = true).lanAccessEnabled)
        assertFalse(service.info(null, local = false).local)
        assertFalse(service.isUnlocked("c1", local = false))
    }

    @Test
    fun `changing the pin replaces it, locks remote connections again and removes the old plain text file`() {
        legacy.writeText("1111\n")
        service.connectionOpened("c1")
        service.setPin("482913")
        assertFalse(legacy.exists())

        assertEquals(RemoteUnlockStatus.UNLOCKED, service.unlock("c1", "482913", "host").status)
        assertEquals(SetPinStatus.SAVED, service.setPin("7788").status)
        assertFalse(service.isUnlocked("c1"))
        assertEquals(RemoteUnlockStatus.WRONG_PIN, service.unlock("c1", "482913", "host").status)
        assertEquals(RemoteUnlockStatus.UNLOCKED, service.unlock("c1", "7788", "host").status)
        assertTrue(file.parentFile.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `clearing the pin forgets it and locks everything`() {
        service.setPin("482913")
        service.connectionOpened("c1")
        service.unlock("c1", "482913", "host")

        service.clearPin()

        assertFalse(service.isPinSet())
        assertFalse(service.isUnlocked("c1"))
        assertEquals(RemoteUnlockStatus.NO_PIN, service.unlock("c1", "482913", "host").status)
    }

    @Test
    fun `invalid pins are rejected and leave the stored pin untouched`() {
        service.setPin("482913")
        val before = file.readText()

        for (bad in listOf("", "123", "12 34", "a\tb12", "x".repeat(RemoteUnlockService.MAX_PIN_LENGTH + 1))) {
            assertEquals(SetPinStatus.INVALID, service.setPin(bad).status, bad)
        }
        assertEquals(before, file.readText())
    }
}
