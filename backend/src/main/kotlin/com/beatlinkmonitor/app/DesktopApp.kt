package com.beatlinkmonitor.app

import io.github.oshai.kotlinlogging.KotlinLogging
import javafx.application.Application
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.Hyperlink
import javafx.scene.control.Label
import javafx.scene.layout.VBox
import javafx.stage.Stage
import java.awt.Desktop
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

// Deliberately lazy - see the equivalent comment in Main.kt: this file's
// `main()` is the JVM entry point for the packaged .app, so this class's
// static initializer (where a plain `= KotlinLogging.logger {}` would run)
// executes *before* `main()`'s own body - including before
// `LogSetup.applyDefault()` can set the log directory.
private val logger by lazy { KotlinLogging.logger {} }

/**
 * Native control window and browser launcher: boots the same backend as
 * the headless entry point ([bootstrapBackend]) in-process. One double-click
 * starts the backend, control window and separate default-browser frontend.
 *
 * The Web UI itself, however, is deliberately *not* shown in an embedded
 * JavaFX `WebView` (as an earlier version of this app did). JavaFX's
 * embedded WebView on macOS has a GPU-pipeline rendering bug that causes the
 * Live View's scrolling waveform canvas to visibly flicker - a bug that is
 * absent in real, standalone browsers (Safari/Chrome) entirely. Rather than
 * fight that platform bug further, this small control window simply opens
 * the Web UI in the user's actual default browser and offers a button to
 * reopen it if the tab gets closed.
 *
 * Packaged into a double-clickable macOS `.app` via `jpackage` (see the
 * `jpackageMac` Gradle task), bundling its own Java runtime so end users
 * never need to install anything themselves.
 */
class DesktopApp : Application() {

    private lateinit var context: AppContext
    private lateinit var url: String

    override fun init() {
        // Runs on the JavaFX launcher thread, before the UI toolkit is fully
        // up - safe place to boot the backend so the window can show
        // immediately once start() runs.
        context = AppContext()
        bootstrapBackend(context)
        url = "http://127.0.0.1:${context.applicationConfig.webUiPort}/"
    }

    override fun start(primaryStage: Stage) {
        primaryStage.title = "BeatLink Monitor"

        val statusLabel = Label("Starting server…")
        statusLabel.style = "-fx-font-size: 13px; -fx-text-fill: #888;"

        val link = Hyperlink(url)
        link.isVisible = false
        link.setOnAction { openInBrowser() }

        val openButton = Button("Open in Browser")
        openButton.isDisable = true
        openButton.setOnAction { openInBrowser() }

        val quitButton = Button("Quit")
        quitButton.setOnAction { primaryStage.close() }

        val hint = Label("The user interface runs in your default browser (e.g. Safari), not in this window.")
        hint.style = "-fx-font-size: 11px; -fx-text-fill: #999; -fx-wrap-text: true;"
        hint.maxWidth = 320.0

        val titleLabel = Label("BeatLink Monitor")
        titleLabel.style = "-fx-font-size: 18px; -fx-font-weight: bold;"

        val root = VBox(
            12.0,
            titleLabel,
            statusLabel,
            link,
            openButton,
            hint,
            quitButton,
        )
        root.alignment = Pos.CENTER
        root.padding = Insets(24.0)

        primaryStage.scene = Scene(root, 380.0, 280.0)

        primaryStage.setOnCloseRequest {
            // No explicit shutdown() call needed here: JavaFX's default
            // `Platform.isImplicitExit() == true` means closing the last
            // window automatically triggers stop() below, which already
            // handles the full backend shutdown exactly once.
            logger.info { "Window closed - shutting down backend" }
        }
        primaryStage.show()

        // The embedded Ktor server binds within milliseconds (verified: well
        // under a second), but we still wait for it to actually accept
        // connections before offering the "open in browser" action, so the
        // user never hits a blank "connection refused" error tab on a slow
        // machine (e.g. Raspberry Pi-class hardware).
        waitForServerReady(context.applicationConfig.webUiPort) {
            Platform.runLater {
                statusLabel.text = "Server running:"
                link.isVisible = true
                openButton.isDisable = false
                openInBrowser() // open automatically on first start, for convenience
            }
        }
    }

    /**
     * Opens [url] in the user's default browser.
     *
     * Deliberately runs the actual AWT/`ProcessBuilder` work on a dedicated
     * background thread, never directly on the JavaFX Application Thread:
     * `java.awt.Desktop.browse()` internally touches AWT/Cocoa's own native
     * event loop, and invoking it straight from the FX Application Thread on
     * macOS is a well-known trigger for a permanent deadlock between the two
     * toolkits' run loops - which froze this entire control window
     * (including the Quit button, since it shares the same, now-blocked FX
     * thread) as soon as the automatic "open on first start" call ran.
     */
    private fun openInBrowser() {
        Thread({
            if (!tryOpenWithAwtDesktop(url) && !tryOpenWithPlatformCommand(url)) {
                logger.warn { "Could not open browser automatically; please open $url manually" }
            }
        }, "open-browser").apply { isDaemon = true }.start()
    }

    /** Preferred, cross-platform way to open the default browser. */
    private fun tryOpenWithAwtDesktop(target: String): Boolean {
        return runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(target))
                true
            } else {
                false
            }
        }.getOrElse { e ->
            logger.warn(e) { "java.awt.Desktop.browse() failed" }
            false
        }
    }

    /**
     * Fallback for environments without AWT Desktop support (e.g. some
     * minimal Linux/Raspberry Pi desktop setups) - shells out to the
     * platform's own "open a URL" command.
     */
    private fun tryOpenWithPlatformCommand(target: String): Boolean {
        val os = System.getProperty("os.name").lowercase()
        val command = when {
            os.contains("mac") -> listOf("open", target)
            os.contains("win") -> listOf("rundll32", "url.dll,FileProtocolHandler", target)
            else -> listOf("xdg-open", target)
        }
        return try {
            ProcessBuilder(command).start()
            true
        } catch (e: IOException) {
            logger.warn(e) { "Platform open-URL command failed" }
            false
        }
    }

    override fun stop() {
        shutdown()
    }

    private fun shutdown() {
        runCatching { context.outputManager.stopAll() }
        runCatching { context.simulationService.stop() }
        runCatching { context.proDjLinkAdapter.stop() }
        // Must explicitly stop the embedded Netty engine: its worker threads
        // are not daemon threads by default, so without this the JVM process
        // would keep running in the background indefinitely even after this
        // window has closed and Platform.exit() has shut down the FX toolkit.
        runCatching { context.apiServer?.stop(1_000, 2_000) }
        Platform.exit()
    }

    private fun waitForServerReady(port: Int, onReady: () -> Unit) {
        Thread({
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline) {
                try {
                    val connection = URI("http://127.0.0.1:$port/health").toURL().openConnection() as HttpURLConnection
                    connection.connectTimeout = 300
                    connection.readTimeout = 300
                    if (connection.responseCode == 200) {
                        onReady()
                        return@Thread
                    }
                } catch (_: Exception) {
                    // Server not up yet - retry until the deadline.
                }
                Thread.sleep(100)
            }
            logger.error { "Web UI server did not become ready within 15s" }
            Platform.runLater {
                val alert = Alert(Alert.AlertType.ERROR, "The application could not start (server timeout). See the log file for details.", ButtonType.OK)
                alert.showAndWait()
                Platform.exit()
            }
        }, "webview-server-wait").apply { isDaemon = true }.start()
    }
}

fun main(args: Array<String>) {
    LogSetup.applyDefault() // must run before any logger (including AppContext's) is touched
    Application.launch(DesktopApp::class.java, *args)
}
