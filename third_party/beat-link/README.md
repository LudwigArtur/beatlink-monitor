# Corresponding source: Beat Link 8.0.0-blm1

Copyright 2016-2025 Deep Symmetry, LLC. EPL-2.0; see
[LICENSE](../../LICENSE) and the original LICENSE.md inside the source archive.
This is not an official Deep Symmetry release.

## Included material

- `beat-link-8.0.0-source.tar.gz`: complete upstream source at release commit
  `c072c3642bd76b3dbeabbd92eaf3d102eceb0be8` (`v8.0.0`), downloaded from
  https://api.github.com/repos/Deep-Symmetry/beat-link/tarball/c072c3642bd76b3dbeabbd92eaf3d102eceb0be8
- `timefinder-null-guard.patch`: the local source modification.
- Binary: `backend/local-maven-repo/org/deepsymmetry/beat-link/8.0.0-blm1/`.

SHA-256:

```text
db5919e8feefb43ff181a0a1a6cc2684cf86e1935ebacb1fce89c5a3399cf136  beat-link-8.0.0-source.tar.gz
84401f0c9572e7be9c64113f3fb38864f9bd2961a92959f9d832f52204d4ce12  beat-link-8.0.0-blm1.jar
```

## Change

The beat listener checked `lastPosition.precise` without checking whether
`lastPosition` was null. The patch adds that guard before removing a position.
It matches upstream `main` at `855d0bd664716886445fefaddd587aab7b222934`
at publication preparation. The source diff against 8.0.0 is exactly that
one-line change. No behavior change is intended when a position exists.

## Build from source

Requires a JDK compatible with upstream's pom.xml and Maven, plus network
access for Maven dependencies. Work in a new directory, not in the vendored
Maven repository:

```sh
mkdir beat-link-source
tar xzf third_party/beat-link/beat-link-8.0.0-source.tar.gz \
  --strip-components=1 -C beat-link-source
cd beat-link-source
patch -p1 < ../third_party/beat-link/timefinder-null-guard.patch
mvn -DskipTests package
```

The upstream build instructions and pom.xml are included in the archive.
This builds the patched library from source. It does not promise a byte-for-byte
copy of the historical binary (timestamps, compiler and archive metadata vary).
Do not overwrite the public artifact under the same version with a different
binary; assign a new local version and update provenance/checksums if rebuilding.

The historical `blm1` artifact was made by recompiling TimeFinder and its inner
classes against Beat Link 8.0.0 and replacing those class files in the original
JAR. The original Maven POM was copied with the version set to `8.0.0-blm1`.
See [the local repository notes](../../backend/local-maven-repo/README.md).

The POM's EPL-1.0 entry is stale relative to the tagged source's EPL-2.0
LICENSE.md; it is retained for traceability. No GPL secondary-license grant
is assumed.
