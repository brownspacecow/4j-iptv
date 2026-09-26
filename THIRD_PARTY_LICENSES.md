# Third-party notices

4J TV is distributed under the GNU General Public License, version 3 (GPL-3.0). It bundles
third-party software, some of which carries its own licence obligations. Those are set out below.

This file covers the components whose obligations the project itself must satisfy. It is not a
substitute for the licence texts, which ship with the software itself.

---

## Bundled in the `full` flavor only

The `full` build flavor bundles software audio and video decoders derived from FFmpeg so that
channels carrying AC-3 / E-AC-3 / DTS / MP2 produce sound on hardware that cannot decode them.
The `lite` flavor contains none of this and depends only on the platform's own decoders.

### FFmpeg

- Upstream: https://ffmpeg.org/
- Licence: **LGPL-3.0-or-later** (some components under GPL-2.0-or-later; see FFmpeg's own
  `LICENSE.md` and `COPYING.LGPLv3` for the per-file breakdown)
- Obtained via: NextLib, below. No FFmpeg source is modified in this repository.

**How the LGPL obligation is met.** The decoders are packaged as separate shared libraries
(`libavcodec.so`, `libavutil.so`, `libswresample.so`, `libswscale.so`) and are loaded at runtime
rather than linked into the application's own code. FFmpeg's terms permit use under the LGPL
provided the user can replace those libraries — this project makes no modification to them and
does not restrict their replacement. The corresponding source is available from FFmpeg at the
version above, and from the NextLib project for the exact build shipped here.

### NextLib (nextlib-media3ext)

- Upstream: https://github.com/anilbeesetti/nextlib
- Licence: **GPL-3.0**
- Artefact: `io.github.anilbeesetti:nextlib-media3ext`

NextLib packages FFmpeg for Android and exposes it to Media3/ExoPlayer as a renderer. Because it
is GPL-3.0, the `full` flavor is a combined work and is distributed under GPL-3.0. This is why the
project as a whole is GPL-3.0 rather than a permissive licence — a permissively licensed
application cannot be distributed alongside a GPL-3.0 linked library.

The `lite` flavor does not include NextLib and is not affected by this.

### FFmpeg components

The bundled `libavcodec.so` includes, among others, decoders derived from the following upstream
projects, each under its own licence as recorded in FFmpeg's `LICENSE.md`:

| Component | Licence |
|---|---|
| libavcodec (AC-3, E-AC-3, DTS, MP2, AAC, H.264, HEVC and others) | LGPL-3.0-or-later |
| libavutil | LGPL-3.0-or-later |
| libswresample, libswscale | LGPL-3.0-or-later |
| libvpx (VP8/VP9) | BSD-3-Clause |
| Mbed TLS (in FFmpeg's network stack) | Apache-2.0 OR GPL-2.0-or-later |
| x264 (if present in a given build) | GPL-2.0-or-later, commercial licence available |
| libx265 (if present in a given build) | GPL-2.0-or-later |

### Media3 / ExoPlayer

- Upstream: https://github.com/androidx/media
- Licence: **Apache-2.0**
- Artefacts: `androidx.media3:media3-exoplayer`, `media3-exoplayer-hls`, `media3-ui`,
  `media3-session`, `media3-datasource-okhttp`

### AndroidX and Jetpack Compose

All `androidx.*` and `androidx.tv.*` artefacts used by this project are licensed
**Apache-2.0**. This includes Jetpack Compose, `androidx.tv:tv-material`, Room, Paging,
Lifecycle, DataStore, Activity and Navigation.

### Kotlin and kotlinx

- Kotlin: **Apache-2.0** (`org.jetbrains.kotlin`)
- kotlinx.coroutines, kotlinx.serialization: **Apache-2.0**

### OkHttp, Okio and Retrofit

- Square OkHttp / Okio: **Apache-2.0**
- Square Retrofit: **Apache-2.0**
- `com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter`: **Apache-2.0**

### Coil

- Upstream: https://github.com/coil-kt/coil
- Licence: **Apache-2.0**

### JUnit and test-only dependencies

- JUnit 4: **EPL-1.0**
- Robolectric: **Apache-2.0**
- MockWebServer: **Apache-2.0**
- Okio (test): **Apache-2.0**

Test-only dependencies are not distributed in the APK.

---

## Verifying the licences of a build

The complete, authoritative licence text for each dependency ships inside its own artefact. To
list what a given build actually contains:

```bash
./gradlew :app:dependencies
```

To inspect the licences of the AARs resolved for a configuration:

```bash
./gradlew :app:dependencies --configuration fullDebugRuntimeClasspath
```

---

## Note on content

4J TV is a player. It contains no channels, playlists, subscriptions, provider directory or media,
and it is not affiliated with any IPTV provider. Users are responsible for ensuring they are
authorised to access whatever they configure it with.
