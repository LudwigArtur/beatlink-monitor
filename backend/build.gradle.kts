plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
}

group = "com.beatlinkmonitor"
version = "0.1.0"

repositories {
    // Required patched Beat Link artifact and optional local JavaFX mirror.
    maven {
        url = uri("local-maven-repo")
        content { includeGroup("org.deepsymmetry") }
    }
    mavenCentral()
}

val ktorVersion = "2.3.12"
val defaultJavafxVersion = "21.0.2"

/**
 * JavaFX publishes separate artifacts per OS/architecture (no "universal"
 * jar). We add them as plain Maven dependencies instead of via the
 * `org.openjfx.javafxplugin` Gradle plugin because that plugin's own
 * resolution step is blocked in some sandboxed/offline-ish build
 * environments even when direct Maven Central access works fine.
 *
 * Override with `-PjavafxClassifier=win` or `-PjavafxClassifier=linux-aarch64`
 * when cross-building a jpackage bundle for Windows or Raspberry Pi from a
 * CI machine of a different OS/architecture.
 */
fun detectJavafxClassifier(): String {
    project.findProperty("javafxClassifier")?.let { return it.toString() }
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    return when {
        os.contains("mac") && (arch.contains("aarch64") || arch.contains("arm")) -> "mac-aarch64"
        os.contains("mac") -> "mac"
        os.contains("win") -> "win"
        arch.contains("aarch64") || arch.contains("arm") -> "linux-aarch64"
        else -> "linux"
    }
}

val javafxClassifier = detectJavafxClassifier()
// OpenJFX did not publish a Linux ARM64 classifier until JavaFX 22.
// Version 22.0.2 still runs on the project's JDK 21 baseline.
val javafxVersion = if (javafxClassifier == "linux-aarch64") "22.0.2" else defaultJavafxVersion

dependencies {
    // Native desktop control window/browser launcher, platform-specific per
    // `javafxClassifier` above. Each module's POM declares its dependencies
    // on other javafx-* artifacts *without* a classifier (resolved
    // automatically by the official javafx Gradle plugin, which we are not
    // using here) - those classifier-less jars do not actually exist on
    // Maven Central, so transitive resolution is disabled and every module
    // we need is listed explicitly with the correct classifier instead.
    implementation("org.openjfx:javafx-base:$javafxVersion:$javafxClassifier") { isTransitive = false }
    implementation("org.openjfx:javafx-graphics:$javafxVersion:$javafxClassifier") { isTransitive = false }
    implementation("org.openjfx:javafx-controls:$javafxVersion:$javafxClassifier") { isTransitive = false }
    implementation("org.openjfx:javafx-media:$javafxVersion:$javafxClassifier") { isTransitive = false }

    // Pro DJ Link core library (Deep Symmetry). Pinned to a locally-vendored
    // "8.0.0-blm1" build (see local-maven-repo/README.md) instead of plain
    // 8.0.0: upstream's own `TimeFinder`'s beat-listener has a null-check bug
    // (missing `lastPosition != null` guard before reading
    // `lastPosition.precise`) that throws a NullPointerException whenever a
    // beat packet arrives for a device before its beat grid has been cached
    // (observed hundreds of times/hour on the Raspberry Pi deployment,
    // permanently breaking that device's position tracking - and therefore
    // the Live View's waveform/playhead - until its next track load). Fixed
    // upstream on beat-link's `main` branch but not yet in any released
    // version as of 8.0.0; "8.0.0-blm1" is the unmodified 8.0.0 jar with
    // only that single already-merged upstream fix cherry-picked in
    // (verified: `diff` against beat-link's `main`-branch `TimeFinder.java`
    // shows exactly one line changed, nothing else).
    implementation("org.deepsymmetry:beat-link:8.0.0-blm1")

    // Coroutines for async orchestration between subsystems
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // JSON (state serialization, config persistence, WebSocket payloads)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Embedded HTTP/WebSocket server for the local UI API
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")

    // Logging
    implementation("io.github.oshai:kotlin-logging-jvm:5.1.4")
    implementation("ch.qos.logback:logback-classic:1.5.6")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

kotlin {
    jvmToolchain(21)
}

application {
    // The desktop control window/browser launcher is the default program. Headless/server
    // use (Raspberry Pi kiosk, §18.3/§18.4) stays available by running
    // `com.beatlinkmonitor.app.MainKt` directly - see `runHeadless` below.
    mainClass.set("com.beatlinkmonitor.app.DesktopAppKt")
}

tasks.register<JavaExec>("runHeadless") {
    group = "application"
    description = "Runs the backend without the native desktop window (server/Raspberry-Pi/Chromium-kiosk mode)."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.beatlinkmonitor.app.MainKt")
}

// --- Frontend integration -----------------------------------------------
// Builds the Svelte frontend and copies its static output into the backend's
// resources so Ktor can serve the whole UI from the same process/port as the
// API (see Server.kt's `staticResources("/", "webapp")`). This is what lets
// the packaged application behave as a single program instead of two
// separately-started servers.
val frontendDir = file("../frontend")
val generatedResourcesDir = layout.buildDirectory.dir("generated/webResources")

val npmInstall = tasks.register<Exec>("npmInstall") {
    group = "frontend"
    workingDir = frontendDir
    commandLine("npm", "ci", "--silent")
    inputs.file(File(frontendDir, "package.json"))
    inputs.file(File(frontendDir, "package-lock.json"))
    outputs.dir(File(frontendDir, "node_modules"))
}

val buildFrontend = tasks.register<Exec>("buildFrontend") {
    group = "frontend"
    dependsOn(npmInstall)
    workingDir = frontendDir
    commandLine("npm", "run", "build")
    inputs.dir(File(frontendDir, "src"))
    inputs.file(File(frontendDir, "package.json"))
    inputs.file(File(frontendDir, "vite.config.ts"))
    outputs.dir(File(frontendDir, "dist"))
}

val copyFrontend = tasks.register<Copy>("copyFrontend") {
    group = "frontend"
    dependsOn(buildFrontend)
    from(File(frontendDir, "dist"))
    into(generatedResourcesDir.map { it.dir("webapp") })
}

sourceSets {
    main {
        resources.srcDir(generatedResourcesDir)
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(copyFrontend)
    from(rootProject.file("../LICENSE")) { into("META-INF/beatlink-monitor") }
    from(rootProject.file("../NOTICE.md")) { into("META-INF/beatlink-monitor") }
    from(rootProject.file("../THIRD_PARTY_NOTICES.md")) { into("META-INF/beatlink-monitor") }
    from(rootProject.file("../AI_DISCLOSURE.md")) { into("META-INF/beatlink-monitor") }
    from(rootProject.file("../DISCLAIMER.md")) { into("META-INF/beatlink-monitor") }
    from(rootProject.file("../licenses")) { into("META-INF/beatlink-monitor/licenses") }
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<Jar>("jar") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.register<Jar>("fatJar") {
    group = "build"
    manifest { attributes["Main-Class"] = "com.beatlinkmonitor.app.DesktopAppKt" }
    archiveClassifier.set("all")
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.register<Jar>("fatJarHeadless") {
    group = "build"
    description = "Builds a runnable jar without the desktop GUI, for headless/server deployments."
    manifest { attributes["Main-Class"] = "com.beatlinkmonitor.app.MainKt" }
    archiveClassifier.set("headless")
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// --- macOS application bundle (jpackage) ---------------------------------
// Produces a double-clickable BeatLinkMonitor.app with its own bundled
// Java+JavaFX runtime image, so end users do not need Java installed at all
// (requirements specification §18.1: "soll auf Apple-Silicon-Macs laufen").
//
// Builds into a directory *outside* this project on purpose: when the
// project lives under an iCloud-Drive-synced folder (e.g. the default
// macOS Desktop with "Desktop & Documents Folders" sync enabled), macOS
// attaches extra metadata (`com.apple.FinderInfo` et al.) to newly created
// files there, and `codesign` - which jpackage runs internally, since every
// arm64/Apple-Silicon executable must at least be ad-hoc signed to launch -
// refuses to sign anything carrying that attribute. Building in a plain
// temp directory avoids the problem entirely.
val appImageStagingDir = file(System.getProperty("java.io.tmpdir") + "/beatlinkmonitor-jpackage")
val appImageDir = layout.buildDirectory.dir("jpackage")

tasks.register<Exec>("jpackageMac") {
    group = "distribution"
    description = "Packages the application as a native macOS .app bundle via jpackage."
    dependsOn("fatJar")
    doFirst {
        appImageStagingDir.deleteRecursively()
        appImageStagingDir.mkdirs()
    }
    val fatJarFile = tasks.named<Jar>("fatJar").get().archiveFile.get().asFile
    val iconFile = file("packaging/macos/AppIcon.icns")
    // Resolve jpackage from the same JDK Gradle itself is running on (set via
    // `org.gradle.java.home` in gradle.properties) rather than relying on
    // the shell's PATH: the system-default `java`/`jpackage` on PATH may
    // point at a JRE-only install that lacks the `jpackage` tool entirely.
    val jpackageBinary = File(org.gradle.internal.jvm.Jvm.current().javaHome, "bin/jpackage").absolutePath
    commandLine(
        buildList {
            add(jpackageBinary)
            add("--type"); add("app-image")
            add("--name"); add("BeatLinkMonitor")
            add("--input"); add(fatJarFile.parentFile.absolutePath)
            add("--main-jar"); add(fatJarFile.name)
            add("--main-class"); add("com.beatlinkmonitor.app.DesktopAppKt")
            add("--dest"); add(appImageStagingDir.absolutePath)
            add("--app-version"); add("1.0.0")
            add("--vendor"); add("BeatLinkMonitor")
            add("--mac-package-identifier"); add("com.beatlinkmonitor.app")
            add("--java-options"); add("-Dfile.encoding=UTF-8")
            if (iconFile.exists()) {
                add("--icon"); add(iconFile.absolutePath)
            }
        },
    )
    doLast {
        // Copy the finished, already-signed .app back next to the other
        // build outputs for convenience. If this copy step itself fails
        // (e.g. due to the same iCloud xattr quirk on the *copy*), that is
        // non-fatal - the authoritative, working .app remains in the temp
        // staging directory printed below.
        val finalDir = appImageDir.get().asFile
        runCatching {
            finalDir.deleteRecursively()
            finalDir.mkdirs()
            appImageStagingDir.resolve("BeatLinkMonitor.app").copyRecursively(finalDir.resolve("BeatLinkMonitor.app"), overwrite = true)
        }
        println("BeatLinkMonitor.app created at: ${appImageStagingDir.absolutePath}/BeatLinkMonitor.app")
    }
}
