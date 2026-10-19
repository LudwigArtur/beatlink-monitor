# Third-party notices

The root EPL-2.0 license covers application-specific contributions, not an
assertion that every dependency has the same license. Retain upstream notices.

## Included third-party material

| Material | Origin / version | License and modifications |
| --- | --- | --- |
| Beat Link library | Deep Symmetry, LLC, 8.0.0 / local 8.0.0-blm1 | EPL-2.0 per the release's LICENSE.md; one TimeFinder null guard added |
| Simulator waveform bytes and beat grids | Beat Link Trigger, Deep Symmetry, LLC | EPL-2.0 project material; waveform bytes unchanged, beat grids converted to CSV |
| Gradle Wrapper | Gradle 8.10.2 | Apache-2.0; wrapper JAR and scripts |

### Beat Link source and patch

Copyright 2016-2025 Deep Symmetry, LLC.
Source tag `v8.0.0`, commit `c072c3642bd76b3dbeabbd92eaf3d102eceb0be8`.
Complete upstream source, exact local change and reproduction instructions:
[third_party/beat-link](third_party/beat-link/README.md).

The upstream 8.0.0 Maven POM still says "Eclipse Public License 1.0".
The actual tagged source distribution's
[LICENSE.md](https://github.com/Deep-Symmetry/beat-link/blob/v8.0.0/LICENSE.md)
and README identify **EPL-2.0**. The original POM is retained rather than
silently rewriting third-party metadata. EPL-2.0 is included in
[licenses/EPL-2.0.txt](licenses/EPL-2.0.txt).

### Beat Link Trigger simulator data

Foundation: https://github.com/Deep-Symmetry/beat-link-trigger

Reference revision verified at publication preparation:
`162695396b152da018bb7d5f25b7760d5a743d7b`.
Source directory: `resources/beat_link_trigger/sampleTracks/{1,2}/`.
Included subset: `preview-color.data`, `detail-color.data` and beat timestamps
/ bar positions converted from `beat-grid.edn` to `beatgrid.csv`.
Track names: "Concrete Angel (Original Mix)" (Gareth Emery) and
"Transcendence" (Lindsey Stirling). No recordings or cover artwork are included.
The project does not claim rights in these recordings or broader music rights.
See [the data-specific notice](backend/src/main/resources/sampletracks/README.md).

The local `beat-link-trigger-main/` reference checkout is not needed to build
this application and is not part of the published repository.

## Dependencies resolved during the build

Direct dependencies are declared in
[build.gradle.kts](backend/build.gradle.kts) and
[package.json](frontend/package.json); exact frontend resolution is in
[package-lock.json](frontend/package-lock.json).

| Component | Declared version | Upstream license |
| --- | --- | --- |
| Kotlin / Kotlin stdlib | 2.0.21 | Apache-2.0 |
| kotlinx.coroutines | 1.8.1 | Apache-2.0 |
| kotlinx.serialization | 1.6.3 | Apache-2.0 |
| Ktor | 2.3.12 | Apache-2.0 |
| kotlin-logging | 5.1.4 | Apache-2.0 |
| Logback | 1.5.6 | EPL-1.0 / LGPL-2.1 dual license |
| OpenJFX | 21.0.2 | GPL-2.0 with Classpath Exception; additional component notices apply |
| Svelte | ^5.57.0 | MIT |
| Vite / Svelte Vite plugin | ^8.3.0 / ^7.3.0 | MIT |
| TypeScript | ~6.0.2 | Apache-2.0 |
| Carabiner (separate, optional process) | not bundled | GPL-2.0; check the chosen release |

This table is not a complete transitive dependency or binary-license inventory.
Beat Link transitively uses, among others, Remote Tea (LGPL-2.0) and the
Kaitai Struct Java runtime (MIT). Other resolved components include Netty,
SLF4J and their own dependencies. Distribution obligations must be assessed
against the actual resolved release, not only this overview.

Unmodified local OpenJFX mirror files are excluded from source publication;
public builds can resolve them from Maven Central. Java/JRE distributions are
not part of the source repository.

## Before redistributing binaries

The repository publishes source and the patched Beat Link JAR with its
corresponding source. Existing local application archives and app bundles are
not published automatically. For a release:

1. Record resolved backend dependencies (`./gradlew dependencies
   --configuration runtimeClasspath`) and the frontend lockfile.
2. Include all required third-party license texts and copyright notices for
   that exact dependency set; do not rely solely on fat-JAR duplicate handling.
3. Make corresponding source and local modifications available where required
   (including relevant EPL, LGPL and GPL obligations).
4. Preserve JRE/OpenJFX legal directories and component notices when bundling
   runtimes. Observe replacement/relinking rights where applicable.
5. Include the project license, AI disclosure and operational warnings.
6. Check music/artwork rights independently if adding any media.

The Gradle resources task embeds project notices in
`META-INF/beatlink-monitor/`. This is a baseline, not proof that a binary
redistribution satisfies every dependency obligation.
