# Development, builds and publication

## Layout

- [backend](../backend/): Kotlin, Gradle, Beat Link, Ktor, JavaFX launcher.
- [frontend](../frontend/): Svelte, TypeScript, Vite.
- [config](../config/): Sample configuration without production credentials.
- [third_party](../third_party/beat-link/README.md): Source for patched Beat Link.
- [Architecture](../ARCHITECTURE.md) and [requirements](REQUIREMENTS.md)
  describe the design; requirements are not guaranteed implementation claims.

JDK 21 and Node 24 are recommended. Do not commit a machine-specific
`org.gradle.java.home`; set `JAVA_HOME` locally instead.

## Local development

From the project root:

```sh
./scripts/dev.sh
```

This starts the headless backend and Vite separately. Alternatively, in two
terminals:

```sh
cd backend
./gradlew runHeadless
```

```sh
cd frontend
npm ci
npm run dev
```

The simulator is not enabled automatically. Do not use production configuration
for uncontrolled development tests.

## Validation

```sh
cd frontend
npm ci
npm run check
npm run build
```

```sh
cd backend
./gradlew test fatJarHeadless
```

Gradle builds the frontend for embedded resources automatically.
`npm ci` uses the lockfile; version ranges in package.json alone do not
guarantee identical installations.

Tests cover BPM, master handover, slot resolution, configuration, console
templates, locale formatting and waveform decoding, among other things.
They do not constitute hardware validation, a security audit or show approval.

GitHub Actions runs the frontend type check, backend tests and headless
application build on pushes and pull requests using JDK 21 and Node 24.

## Distribution formats

| Task | Result |
| --- | --- |
| `fatJarHeadless` | Runnable JAR for server/browser/kiosk use |
| `fatJar` | Runnable desktop JAR with JavaFX dependencies |
| `jpackageMac` | Local macOS app image with runtime |

JavaFX artifacts are platform-dependent. For example, for Linux ARM64:

```sh
./gradlew -PjavafxClassifier=linux-aarch64 fatJarHeadless
```

The headless fat-JAR task currently includes the shared module's runtime
dependencies, including JavaFX. Choose a classifier matching the target system;
this does not create a native cross-platform desktop app.

## Raspberry Pi package from source

1. Build the headless JAR with `-PjavafxClassifier=linux-aarch64`.
2. Copy it to `raspberrypi-package/app/beatlink-monitor-backend.jar`.
3. Supply required licenses, notices and corresponding source.
4. Only then create a release archive and test it on a Pi.

The old local TAR/JAR is deliberately not part of the source repository.
The Pi installation scripts do not replace a build and require the JAR.

## Release checklist

1. Check rights/provenance of every included file; compare
   [third-party notices](../THIRD_PARTY_NOTICES.md) against the actual resolved
   dependency graph.
2. Fulfill license-text, corresponding-source and any LGPL replacement/relinking
   obligations for the exact binary package. Preserve runtime legal directories.
3. Include `LICENSE`, `NOTICE.md`, AI disclosure and operational warnings.
4. Do not commit secrets, logs, user configuration or local machine paths.
5. Run frontend check/build and backend tests; record the results.
6. Test target platform, hardware/firmware, master loss and output behavior.
7. Align version numbers in Gradle, the application and package metadata.
8. Create a repository tag and clear release notes with known limitations;
   identify platform/architecture and provide SHA-256 checksums.
9. Upload binary releases separately; do not commit build directories.

Initial source publication is not a promise of a validated binary distribution.
Follow [Contributing](../CONTRIBUTING.md).
