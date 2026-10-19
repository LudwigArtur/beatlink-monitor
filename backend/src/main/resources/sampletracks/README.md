# Sample track waveform data

The `.data` files in this directory are waveform *analysis* data (color
preview/detail segment bytes, as produced by Pioneer's rekordbox/CDJ
analysis and consumed via Deep Symmetry's `beat-link` library) - not audio.
The `beatgrid.csv` files are a simplified re-export (`timeMs,beatWithinBar`
per beat) of the equivalent beat grid analysis data.

They are taken, unmodified in substance, from the sample tracks bundled with
[beat-link-trigger](https://github.com/Deep-Symmetry/beat-link-trigger)
(EPL-2.0 licensed), which ships them for the explicit purpose of letting
developers exercise realistic waveform rendering without needing real Pro DJ
Link hardware or audio files - exactly the same purpose they are used for
here (requirements specification §16: simulator/test mode).

No audio, album artwork, or other track assets are included - only the
derived waveform segment bytes plus the track title/artist/duration/tempo
metadata, which is hardcoded in
`com.beatlinkmonitor.waveform.SampleTrackLibrary` (not copied as a file here).

Source tracks (for attribution):

- `track1`: "Concrete Angel (Original Mix)" - Gareth Emery
- `track2`: "Transcendence" - Lindsey Stirling

This is local development/testing data only; it is not redistributed audio
and this project does not claim any rights over the referenced recordings.

Copyright 2016-2025 Deep Symmetry, LLC, for the upstream project material.
Included under the upstream project's EPL-2.0 terms; see the repository's
`LICENSE` and `THIRD_PARTY_NOTICES.md`. The project license does not grant
rights in the underlying music recordings or trademarks.

Source: `resources/beat_link_trigger/sampleTracks/{1,2}/` in Beat Link Trigger,
reference revision `162695396b152da018bb7d5f25b7760d5a743d7b`.
The `.data` files are unchanged; beat timestamps and bar positions were
converted from `beat-grid.edn` into CSV. The upstream reference checkout,
audio and cover art are not part of this application's source publication.
