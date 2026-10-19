package com.beatlinkmonitor.app

import java.io.File

/**
 * Establishes a sane default log file location *before* any class with a
 * logger (`KotlinLogging.logger {}`/`LoggerFactory.getLogger(...)`) is
 * loaded - logback reads the `BEATLINK_LOG_DIR` system property while
 * initializing its own logging context, which happens as a side effect of
 * the very first such class being loaded by the JVM, so this must run as
 * the first statement of every entry point (`main()` in both [Main.kt] and
 * `DesktopApp.kt`), before `AppContext()` or anything else is constructed.
 *
 * Without this, `logback.xml`'s `${BEATLINK_LOG_DIR:-.}` default resolves to
 * the process's current working directory - for a double-clicked, jpackage'd
 * macOS `.app` launched via Finder/`open`, that is `/` (the filesystem
 * root), which a normal user cannot write to. Logback does not surface that
 * failure anywhere a user would see it (only to its own internal, silent
 * status log) - so every single one of this application's log lines simply
 * vanished, in every released build so far, making any post-hoc diagnosis
 * of a user's problem (including real Pro DJ Link hardware issues) nearly
 * impossible. An explicit, user-writable, platform-appropriate default
 * fixes this for good, while still letting advanced users/deployments
 * override it via the `BEATLINK_LOG_DIR` environment variable as before (an
 * already-set env var or system property always wins - see [applyDefault]).
 */
object LogSetup {
    fun applyDefault() {
        // An explicitly set environment variable or system property (e.g. for
        // a Raspberry Pi/server deployment with its own log rotation setup)
        // must always take priority over this built-in default.
        if (System.getProperty("BEATLINK_LOG_DIR") != null) return
        if (System.getenv("BEATLINK_LOG_DIR") != null) return

        val dir = defaultLogDir()
        dir.mkdirs()
        System.setProperty("BEATLINK_LOG_DIR", dir.absolutePath)
    }

    /** Mirrors `ConfigurationService.defaultAppSupportDir()`'s platform logic (duplicated, not shared, so this has zero dependencies and can run before any other class loads). */
    private fun defaultLogDir(): File {
        val os = System.getProperty("os.name").lowercase()
        val home = System.getProperty("user.home")
        val appSupportDir = when {
            os.contains("mac") -> File(home, "Library/Application Support/BeatLinkMonitor")
            os.contains("win") -> File(System.getenv("APPDATA") ?: home, "BeatLinkMonitor")
            else -> File(System.getenv("XDG_CONFIG_HOME") ?: "$home/.config", "beatlink-monitor")
        }
        return File(appSupportDir, "logs")
    }
}
