package com.beatlinkmonitor.config

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigurationServiceTest {

    private fun newService(): ConfigurationService =
        ConfigurationService(createTempDirectory("beatlink-test").toFile())

    @Test
    fun `defaults are returned when no configuration file exists yet`() {
        val service = newService()
        val config = service.loadApplicationConfig()
        assertEquals(CURRENT_CONFIG_VERSION, config.configVersion)
        assertEquals(2, config.bpmDecimalPlaces)
    }

    @Test
    fun `fresh output defaults do not connect to a console or contain credentials`() {
        val config = newService().loadOutputsConfig()
        assertFalse(config.grandMa.enabled)
        assertFalse(config.abletonLink.enabled)
        assertEquals("127.0.0.1", config.grandMa.host)
        assertEquals("", config.grandMa.username)
        assertEquals("", config.grandMa.password)
        assertEquals("AUTOMATIC", config.grandMa.networkInterfaceMode)
        assertNull(config.grandMa.manualInterfaceName)
    }

    @Test
    fun `application config round-trips through save and load`() {
        val service = newService()
        val config = ApplicationConfig(webUiPort = 9999, allowLanAccess = true, developmentMode = true)
        service.saveApplicationConfig(config)

        val reloaded = service.loadApplicationConfig()
        assertEquals(9999, reloaded.webUiPort)
        assertTrue(reloaded.allowLanAccess)
        assertTrue(reloaded.developmentMode)
    }

    @Test
    fun `corrupted configuration file falls back to defaults instead of crashing`() {
        val service = newService()
        File(service.rootDir, "application.json").writeText("{ not valid json ]]]")

        val config = service.loadApplicationConfig()
        assertEquals(ApplicationConfig().webUiPort, config.webUiPort)
    }

    @Test
    fun `outputs config round-trips and preserves the grandma command template`() {
        val service = newService()
        val outputs = OutputsConfig(
            grandMa = GrandMaConfig(enabled = true, host = "10.1.1.1", commandTemplate = "SpecialMaster 3.1 At {BPM}"),
        )
        service.saveOutputsConfig(outputs)

        val reloaded = service.loadOutputsConfig()
        assertEquals("10.1.1.1", reloaded.grandMa.host)
        assertEquals("SpecialMaster 3.1 At {BPM}", reloaded.grandMa.commandTemplate)
        assertTrue(reloaded.grandMa.enabled)
    }

    @Test
    fun `profile save, list and load`() {
        val service = newService()
        service.saveProfile(UiProfile(name = "rakete-main", globalScale = 1.5))

        assertTrue(service.listProfiles().contains("rakete-main"))
        assertEquals(1.5, service.loadProfile("rakete-main").globalScale)
    }

    @Test
    fun `default profile supports up to six player slots with layout fields`() {
        val profile = UiProfile()
        assertEquals(6, profile.playerSlots.size)
        assertEquals(4, profile.playerSlots.count { it.visible })
        assertTrue(profile.playerSlots.all { it.width > 0 && it.height > 0 })
        assertEquals(DEFAULT_GLOBAL_WIDGETS.map { it.id }, profile.widgets.map { it.id })
    }

    @Test
    fun `player slot layout (order, width, height) round-trips through persistence`() {
        val service = newService()
        val reordered = UiProfile(name = "rakete-compact").let { p ->
            p.copy(
                playerSlots = p.playerSlots.map {
                    if (it.slot == 1) it.copy(order = 3, width = 420.0, height = 270.0) else it
                },
            )
        }
        service.saveProfile(reordered)

        val reloaded = service.loadProfile("rakete-compact")
        val slot1 = reloaded.playerSlots.first { it.slot == 1 }
        assertEquals(3, slot1.order)
        assertEquals(420.0, slot1.width)
        assertEquals(270.0, slot1.height)
    }

    @Test
    fun `global widget visibility and order round-trip through persistence`() {
        val service = newService()
        val profile = UiProfile(name = "debug").let { p ->
            p.copy(widgets = p.widgets.map { if (it.id == "masterBpm") it.copy(visible = false, order = 9) else it })
        }
        service.saveProfile(profile)

        val reloaded = service.loadProfile("debug")
        val masterBpmWidget = reloaded.widgets.first { it.id == "masterBpm" }
        assertEquals(false, masterBpmWidget.visible)
        assertEquals(9, masterBpmWidget.order)
    }

    @Test
    fun `profile export and import preserves content (requirements specification §11_1)`() {
        val service = newService()
        val original = UiProfile(name = "external-display", globalScale = 2.0, fullscreen = true)
        service.saveProfile(original)

        val exportFile = File(service.rootDir, "exported.json")
        service.exportProfile("external-display", exportFile)

        val importedService = newService()
        val imported = importedService.importProfile(exportFile)

        assertEquals(original.name, imported.name)
        assertEquals(original.globalScale, imported.globalScale)
        assertEquals(original.fullscreen, imported.fullscreen)
        assertTrue(importedService.listProfiles().contains("external-display"))
    }

    @Test
    fun `deleting a profile removes it from the list`() {
        val service = newService()
        service.saveProfile(UiProfile(name = "temp"))
        assertTrue(service.listProfiles().contains("temp"))

        service.deleteProfile("temp")
        assertTrue(!service.listProfiles().contains("temp"))
    }
}
