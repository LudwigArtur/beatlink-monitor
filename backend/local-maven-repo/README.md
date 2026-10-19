# Local Maven repository (vendored dependencies)

This directory contains a small, hand-populated Maven repository layout for
dependencies that could not be resolved automatically in the environment this
project was originally built in (that sandbox's JVM processes have no
outbound network access at all - only the `curl` binary does - so Gradle's
normal dependency resolution over HTTPS fails even though `mavenCentral()` is
configured correctly).

The published repository includes `org.deepsymmetry:beat-link:8.0.0-blm1`.
It is required even on machines with normal Internet access; Maven Central
does not contain this local version. Complete corresponding source and the
exact patch are in [third_party/beat-link](../../third_party/beat-link/README.md).
See also [third-party notices](../../THIRD_PARTY_NOTICES.md).

The original development machine additionally has an optional, unmodified
JavaFX mirror, **excluded from public version control**:

- `org.openjfx:javafx-base:21.0.2:mac-aarch64`
- `org.openjfx:javafx-graphics:21.0.2:mac-aarch64`
- `org.openjfx:javafx-controls:21.0.2:mac-aarch64`
- `org.openjfx:javafx-media:21.0.2:mac-aarch64`
- `org.openjfx:javafx-web:21.0.2:mac-aarch64`

These are the exact, unmodified `.pom`/`.jar` files published on Maven
Central under `org.openjfx`, used here purely as a local mirror.

- `org.deepsymmetry:beat-link:8.0.0-blm1`

This one is **not** an unmodified mirror: it is the official
`beat-link-8.0.0.jar` from Maven Central with exactly one class,
`org.deepsymmetry.beatlink.data.TimeFinder`, replaced by a recompiled
version containing a single upstream bugfix that was merged to beat-link's
`main` branch after the 8.0.0 release but has not yet shipped in any
published version. The bug: `TimeFinder`'s internal beat-listener reads
`lastPosition.precise` without checking `lastPosition` for `null` first,
which throws a `NullPointerException` whenever a beat packet arrives for a
device before its beat grid has been cached - this was observed happening
continuously (thousands of times per hour) for several players on the
Raspberry Pi deployment, permanently breaking position tracking (and
therefore the Live View's waveform/playhead) for those players until their
next track load. The fix adds the missing `lastPosition != null` check,
verified via `diff` against upstream's current `TimeFinder.java` to be the
*only* change relative to 8.0.0, and verified in the recompiled `.class`
file's bytecode to contain the null check before the field access.

To reproduce/update this artifact (e.g. once beat-link publishes a real
fixed release and this vendoring can be dropped):
```bash
# 1. Get the exact source beat-link 8.0.0 shipped, and upstream's current fix:
curl -sL -o TimeFinder-8.0.0.java \
  https://raw.githubusercontent.com/Deep-Symmetry/beat-link/v8.0.0/src/main/java/org/deepsymmetry/beatlink/data/TimeFinder.java
curl -sL -o TimeFinder-main.java \
  https://raw.githubusercontent.com/Deep-Symmetry/beat-link/main/src/main/java/org/deepsymmetry/beatlink/data/TimeFinder.java
diff TimeFinder-8.0.0.java TimeFinder-main.java   # confirm it's still just this one fix

# 2. Recompile that one class against the original jar + its API deps
#    (apiguardian-api, slf4j-api - already in the Gradle cache), then copy
#    the resulting .class files into a copy of the original jar, and drop
#    a copy of its .pom here with the version bumped to "8.0.0-blm1".
```

Once beat-link ships an official release containing this fix, switch the
dependency in `build.gradle.kts` back to the plain released version and
delete this directory.

## On a machine with normal internet access

You do **not** need the `org/openjfx/` mirror; Gradle resolves JavaFX from
Maven Central. Do **not** delete the `org/deepsymmetry/` artifact while the
build depends on `8.0.0-blm1`. It is included with its corresponding source.

## Adding artifacts for another platform (Windows / Linux / Raspberry Pi)

Download the matching classifier (`win`, `linux`, or `linux-aarch64`) for
each of the five artifacts above from Maven Central, e.g.:

```bash
V=21.0.2
for ART in javafx-base javafx-graphics javafx-controls javafx-media javafx-web; do
  mkdir -p "org/openjfx/$ART/$V"
  curl -sL -o "org/openjfx/$ART/$V/$ART-$V.pom" \
    "https://repo1.maven.org/maven2/org/openjfx/$ART/$V/$ART-$V.pom"
  curl -sL -o "org/openjfx/$ART/$V/$ART-$V-win.jar" \
    "https://repo1.maven.org/maven2/org/openjfx/$ART/$V/$ART-$V-win.jar"
done
```
