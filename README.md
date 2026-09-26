# 4J TV

A free IPTV player for **Google TV**, Android TV and Fire TV, built around the Xtream Codes API.

This is milestone 1: **connect to a provider and watch live TV.** Movies, series and the EPG are
not in yet — see [Roadmap](#roadmap).

---

## Status

| | |
|---|---|
| Milestone | 1 — live TV |
| Builds | `assembleFullDebug`, `assembleLiteDebug` |
| Tests | 31 unit tests, all passing |
| Verified on hardware | **No** — see [Honest limitations](#honest-limitations) |

## What works

- **Connect** with a pasted provider link, or with server / username / password typed separately.
  A pasted `get.php` or `player_api.php` link has its credentials extracted automatically, so a long
  password never has to be retyped on a television keyboard.
- **Browse live TV** by category, with the category list across the top and a channel list below.
- **Search** filters the loaded list as you type.
- **Channel zapping** — up and down change channel while watching, wrapping at both ends, the way a
  set-top box does.
- **Panel-required headers** (`User-Agent`, `Referer`) are forwarded to the player. A noticeable
  share of channels answer `403` without them.
- **Cached channel lists.** Categories load once, then come from a local Room database, so browsing
  is instant and survives a provider outage.
- **Encrypted credentials.** AES-256-GCM under a non-exportable Android Keystore key. App backup is
  disabled so the ciphertext is never copied off the device.
- **`full` / `lite` audio flavors.** See [Silent channels](#silent-channels).

## Installing on a television

The app declares `LEANBACK_LAUNCHER`, so it appears on the Google TV home screen. Build the APK,
copy it to the TV, and install it. Over ADB, from a computer on the same network:

```bash
adb connect <tv-ip>:5555
adb install -r app/build/outputs/apk/full/debug/app-full-debug.apk
```

Enable ADB debugging first: **Settings → Device Preferences → About → Build**, then click the build
number seven times, then **Settings → Device Preferences → Developer options → ADB debugging**.

## Building

Requires **JDK 17** and the **Android SDK** (compileSdk 35, build-tools 35.0.1). Point
`local.properties` at your SDK:

```properties
sdk.dir=/path/to/Android/sdk
```

Then:

```bash
./gradlew :app:testFullDebugUnitTest     # 31 unit tests
./gradlew :app:assembleFullDebug         # APK with software audio (~43 MB debug)
./gradlew :app:assembleLiteDebug         # smaller APK, hardware audio only
```

Both flavors are also produced per ABI in a release build (`assembleFullRelease`), which is where
the size difference really shows.

## Silent channels

Many IPTV providers carry audio in **AC-3 / E-AC-3 / DTS / MP2**, which low-end television hardware
often cannot decode. The result is a picture with no sound, which is indistinguishable from a dead
stream and sends people hunting for a network problem that does not exist.

The `full` flavor bundles the [NextLib](https://github.com/anilbeesetti/nextlib) FFmpeg software
decoders to fix this. The `lite` flavor omits them and is much smaller, but channels using those
codecs will be silent. **If your provider uses Dolby audio, install `full`.**

This is why the project is GPL-3.0: NextLib is GPL-3.0, and linking it means the combined work has
to be GPL-3.0. It also ships FFmpeg under LGPLv3 — see [Third-party notices](#third-party-notices).

## Design notes

A few decisions that are deliberate, and would otherwise look odd:

**Categories are fetched one at a time.** Asking a panel for its entire live list in one call
returns every channel it has — on a large account that is tens of thousands of records and a
payload that dominates startup time and memory. Each category is fetched on demand and cached.

**A failed login does not overwrite a working profile.** Credentials are only written to the
Keystore after the provider has accepted them.

**Cleartext HTTP is permitted.** Many panels are only reachable over plain HTTP, and refusing it
would make the app useless against a large share of real providers. The trade-off is real: over
HTTP, anyone able to observe the network path can read your credentials and your viewing traffic.
Use HTTPS where your provider offers it.

**`tv-material` supplies no `TextField`.** As of `androidx.tv:tv-material` 1.0.0 the library has
buttons, cards, surfaces and lists, but no text input, so `TvTextField` is built on Compose's
`BasicTextField` and styled for focus visibility at three metres.

**The lists are stable `LazyRow` / `LazyColumn`, not `TvLazyRow` / `TvLazyColumn`.** Those only
exist in alpha builds of `androidx.tv:tv-foundation`; depending on an alpha Compose artifact
alongside a stable BOM is a compatibility problem waiting to happen.

**The Media3 playback controller is switched off.** It is laid out for touch. On a television its
scrub bar and buttons are the wrong size in the wrong places, and they occupy the D-pad directions
that channel zapping needs.

## Roadmap

Ordered by what tends to cause the most annoyance first.

1. **EPG / now-next** — per-channel guide, and a "live now" row.
2. **Favourites** and continue-watching.
3. **Movies and series** with resume, series/season grouping and autoplay.
4. **Quality-variant merging and adaptive quality.** Collapsing a channel's `1`/`2`/`3`/4K/FHD/HD/SD
   variants into one logical channel, choosing by measured bandwidth, stepping down on stalls and
   failing over when a variant is dead. This is the single biggest perceived-quality improvement
   available, and none of the comparable open-source players do it.
5. **Catch-up / timeshift** via the panel's `has_archive`. Absent from every comparable
   open-source player — a differentiator if your provider supports it.
6. **Multi-profile support** and per-profile cache isolation.
7. **Parental / category lock** with a management password.
8. In-app update check, so a sideloaded build learns about fixes.

## Honest limitations

- **Nothing has been run on a real television yet.** It compiles, the manifest is verified to
  contain `LEANBACK_LAUNCHER`, and the pure logic is unit tested — but no one has pressed a
  physical remote against it. D-pad focus behaviour in particular is the thing most likely to need
  adjustment once it is on real hardware.
- **Playback is unverified against a real provider.** The Xtream API surface used here
  (`login`, `get_live_categories`, `get_live_streams`) is well documented, but panels vary.
- **No EPG, VOD or series support yet.**
- The debug APK is unsigned, as debug builds are. It is fine for sideloading; a release build needs
  a signing config that is deliberately not committed.
- Dependencies are pinned to versions known to resolve together (AGP 8.7.3, Kotlin 2.0.21,
  Media3 1.7.1), not to the newest available.

## Third-party notices

This project bundles FFmpeg-derived software decoders via NextLib. FFmpeg is licensed under
LGPLv3; NextLib is GPL-3.0. Full attribution for FFmpeg, libvpx, Mbed TLS and the rest of the stack
belongs in a `THIRD_PARTY_LICENSES.md` alongside this README — that is a loose end in milestone 1,
and it should be closed before this is passed to anyone else.

## Licence

[GPL-3.0](LICENSE).

## Legal

4J TV is a player. It ships with no channels, playlists, subscriptions or provider directory, and it
is not affiliated with any IPTV provider. You are responsible for ensuring you are authorised to
access whatever you configure it with.
