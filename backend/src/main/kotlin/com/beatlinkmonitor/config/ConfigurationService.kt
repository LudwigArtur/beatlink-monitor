package com.beatlinkmonitor.config

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

private val logger = KotlinLogging.logger {}

/**
 * Reads and writes the application's persistent configuration. Uses
 * platform-appropriate application-support directories (requirements specification §26) -
 * never a hardcoded user-specific path - and tolerates corrupted config files
 * by falling back to defaults instead of crashing the whole application
 * (requirements specification §23).
 */
class ConfigurationService(baseDir: File? = null) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val rootDir: File = baseDir ?: defaultAppSupportDir()
    val profilesDir: File = File(rootDir, "profiles")

    init {
        rootDir.mkdirs()
        profilesDir.mkdirs()
    }

    private fun defaultAppSupportDir(): File {
        val os = System.getProperty("os.name").lowercase()
        val home = System.getProperty("user.home")
        return when {
            os.contains("mac") -> File(home, "Library/Application Support/BeatLinkMonitor")
            os.contains("win") -> File(System.getenv("APPDATA") ?: home, "BeatLinkMonitor")
            else -> File(System.getenv("XDG_CONFIG_HOME") ?: "$home/.config", "beatlink-monitor")
        }
    }

    fun loadApplicationConfig(): ApplicationConfig =
        loadOrDefault(File(rootDir, "application.json")) { ApplicationConfig() }

    fun saveApplicationConfig(config: ApplicationConfig) =
        save(File(rootDir, "application.json"), config, ApplicationConfig.serializer())

    fun loadOutputsConfig(): OutputsConfig =
        loadOrDefault(File(rootDir, "outputs.json")) { OutputsConfig() }

    fun saveOutputsConfig(config: OutputsConfig) =
        save(File(rootDir, "outputs.json"), config, OutputsConfig.serializer())

    fun loadProfile(name: String): UiProfile =
        loadOrDefault(File(profilesDir, "$name.json")) { UiProfile(name = name) }

    fun saveProfile(profile: UiProfile) =
        save(File(profilesDir, "${profile.name}.json"), profile, UiProfile.serializer())

    fun listProfiles(): List<String> =
        profilesDir.listFiles { f -> f.extension == "json" }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()

    fun deleteProfile(name: String): Boolean = File(profilesDir, "$name.json").delete()

    fun exportProfile(name: String, target: File) {
        val profile = loadProfile(name)
        save(target, profile, UiProfile.serializer())
    }

    fun importProfile(source: File): UiProfile {
        val profile = json.decodeFromString(UiProfile.serializer(), source.readText())
        saveProfile(profile)
        return profile
    }

    private inline fun <reified T> loadOrDefault(file: File, default: () -> T): T {
        if (!file.exists()) return default()
        return try {
            @Suppress("UNCHECKED_CAST")
            when (T::class) {
                ApplicationConfig::class -> json.decodeFromString(ApplicationConfig.serializer(), file.readText()) as T
                OutputsConfig::class -> json.decodeFromString(OutputsConfig.serializer(), file.readText()) as T
                UiProfile::class -> json.decodeFromString(UiProfile.serializer(), file.readText()) as T
                else -> default()
            }
        } catch (e: Exception) {
            logger.error(e) { "Configuration file '${file.name}' is corrupted or unreadable, falling back to defaults" }
            default()
        }
    }

    private fun <T> save(file: File, value: T, serializer: kotlinx.serialization.KSerializer<T>) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(serializer, value))
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (e: IOException) {
            logger.error(e) { "Failed to persist configuration file '${file.name}'" }
        }
    }
}
