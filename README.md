# 4J TV

A free IPTV player for **Google TV**, Android TV and Fire TV, built around the Xtream Codes API.

Live TV with a programme guide, plus films and series with resume, and search across all three.

---

## Status

| | |
|---|---|
| Features | Live TV, EPG, films, series, search, continue watching, favourites |
| Builds | `assembleFullDebug`, `assembleLiteDebug` |
| Tests | 141 unit tests, all passing |
| Verified on hardware | **No** — see [Honest limitations](#honest-limitations) |

## What works

**Live TV**

- **Connect** with a pasted provider link, or with server / username / password typed separately. A
  pasted `get.php` or `player_api.php` link has its credentials extracted automatically, so a long
  password never has to be retyped on a television keyboard.
- **Browse by category**, with an "On now" row above the channel list.
- **Channel zapping** — up and down change channel while watching, wrapping at both ends, the way a
  set-top box does.
- **Programme guide**: what's on now, how long is left, and what's next, on the row itself and in the
  player. Fetched for the channels actually on screen, not the whole lineup.

**Films and series**

- **Films and series** in separate sections, each with its own categories.
- **Series detail** with seasons across the top and episodes below.
- **Seek and resume.** Left and right scrub; position is saved every few seconds and again on back,
  so a crash or a power cut costs seconds rather than the film. "Continue watching" picks up where
  you left off.
- **Favourites**, kept per account and per content type.

**Search**

- **One search box for live TV, films and series**, reachable from every tab, with results grouped by
  type. Typing filters as you go — debounced, so a burst of keypresses gives one settled answer
  rather than a list thrashing under you.
- **Ranking by how well the title matches.** A title starting with what you typed comes first, then
  one containing the word, then the rest.
- **Instant and offline.** Search is a local query, so it never waits on a provider and works with
  the television off.
- **Coverage is shown, not assumed.** This is a real limit rather than a rounding error — see
  [Search, and why it is local](#search-and-why-it-is-local).

**Throughout**

- **Panel-required headers** (`User-Agent`, `Referer`) are forwarded to the player. A noticeable
  share of channels answer `403` without them.
- **Cached catalogues.** Categories and contents load once, then come from a local Room database, so
  browsing is instant and survives a provider outage.
- **Encrypted credentials.** AES-256-GCM under a non-exportable Android Keystore key. App backup is
  disabled so the ciphertext is never copied off the device.
- **Survives bad providers.** Large JSON responses are routinely truncated in transit; those requests
  are retried rather than reported as failures. Guides that arrive Base64-encoded, or that list only
  programmes that have already finished, are handled.
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
./gradlew :app:testFullDebugUnitTest     # 141 unit tests
./gradlew :app:assembleFullDebug         # APK with software audio + video fallback (~43 MB debug)
./gradlew :app:assembleLiteDebug         # smaller APK, no software decoders
```

Both flavors are also produced per ABI in a release build (`assembleFullRelease`), which is where
the size difference really shows.

## Testing

The unit tests run on the JVM with no device. Two of them are worth knowing about:

- **`VodRepositoryTest`** stands up a mock Xtream panel over a real socket with an in-memory Room
  database, so the whole chain — HTTP, JSON, mapping, database — is covered. It is what caught the
  bugs listed in the [Roadmap](#roadmap) note below.
- **`VodViewModelTest`** includes an explicit guard against a category being fetched over and over.
  That is not a hypothetical: it happened, at dozens of requests a second, which would burn a real
  provider account's connection allowance.

`VodMigrationTest` builds a version 1 database by hand, migrates it, then opens the result with Room
so the migrated schema is checked against the entities the app actually declares.

## Codecs the hardware cannot always handle

Many IPTV providers carry audio in **AC-3 / E-AC-3 / DTS / MP2** and video in **HEVC**, which
low-end television hardware often cannot decode.

Audio that cannot be decoded gives a picture with no sound — indistinguishable from a dead stream,
and it sends people hunting for a network problem that does not exist. Video that cannot be decoded
just fails.

The `full` flavor bundles the [NextLib](https://github.com/anilbeesetti/nextlib) FFmpeg software
decoders to cover both. The `lite` flavor omits them and is roughly half the size, but channels
using those codecs will be silent or will not play. **If your provider uses Dolby audio or HEVC,
install `full`.**

Software video is a **fallback, not the default**. Hardware decoding is faster and far cheaper on
battery, so it always gets first refusal. Software video is only used when the hardware decoder
has actually failed, and the app retries the stream once with the software decoder in front. That
retry exists because a device can advertise HEVC support, have ExoPlayer report the format as
supported, and then fail to decode it — appending the software decoder behind the hardware one does
not help in that case, because the hardware renderer has already claimed the track.

This is why the project is GPL-3.0: NextLib is GPL-3.0, and linking it means the combined work has
to be GPL-3.0. It also ships FFmpeg under LGPLv3 — see [Third-party notices](#third-party-notices).

## Search, and why it is local

Search is a local index, not a panel query. That was not a preference — it is what the provider
allows.

Every server-side search action was tried against the provider these tests run on:
`search_streams`, `search`, `search_vod`, `search_movies`, `search_movie` and `search_series` are
**all ignored**, each answered with a login object. Passing `search` to `get_vod_streams` or
`get_series` is worse than useless — the parameter is ignored and the entire catalogue comes back,
large enough to have the app killed for memory while buffering it. There is no server-side search to
call.

So the app keeps its own index, in its own database file, and searches that.

**Coverage is the trade, so it is shown rather than hidden.** Categories are fetched on demand, so a
freshly installed app has almost nothing indexed, and a search that returns nothing is not evidence
that a title is absent from your provider. The screen says so explicitly instead of implying the
provider does not carry it, and offers one button to index the rest.

Three things feed the index, in increasing order of effort:

1. **Shelves you browse** are indexed as they load, which costs one write — the rows have just been
   downloaded anyway.
2. **A background pass** you start yourself, which walks every category a page at a time. It is
   sequential rather than parallel, because a hundred simultaneous requests at one panel is how a
   provider starts refusing them.
3. **Resuming.** Progress is recorded per category, so closing the app mid-index continues where it
   left off instead of starting again.

**A shelf that cannot be read is skipped, not fatal.** This provider truncates large responses
constantly, and testing found it the hard way: the first version let one oversized shelf end the
whole run, having indexed 2,084 channels out of several hundred shelves, after which no amount of
pressing the button again would ever finish it. A shelf that fails is now stepped over, left
unrecorded so a later run can retry it, and **counted in the summary** — a run on the test provider
finished with 9,019 titles added and 99 shelves unreadable, and saying so is the difference between a
tool you can trust and one that quietly lies about what it knows.

That last number is the honest limit of this feature: on a provider that truncates as readily as
this one, roughly a third of the catalogue cannot be indexed at all. It is a property of the panel,
not something the app can code around.

---

## Design notes

A few decisions that are deliberate, and would otherwise look odd:

**Categories are fetched one at a time.** Asking a panel for its entire live list in one call returns
every channel it has — on a large account that is tens of thousands of records and a payload that
dominates startup time and memory. Each category is fetched on demand and cached. The same applies to
films and series.

**Films and series categories are stored with a kind, and keyed on it.** A real provider returns
both in one combined list, distinguished only by names like `Movies-New Releases` against
`Series-Drama`. Inferring the kind from the name is fragile, and with a single-column key a series
category of `7` silently replaces a film category of `7` and a shelf appears empty.

**A category load replaces rather than accumulates.** The provider's answer is authoritative, so a
film withdrawn at the provider leaves the grid instead of lingering forever. An empty answer clears
the shelf; a failed request does not, so a network blip never looks like "no films here".

**The live player and the VOD player are separate screens.** Live TV has no timeline to scrub and
changes channel with the D-pad; a film has both, and the two want opposite input mappings. One screen
with a mode flag meant either the live overlay grew seek controls that could never do anything, or the
film player inherited D-pad zapping.

**Back returns to the tab bar before it changes tabs.** Once focus descends into a grid or a list,
left and right are swallowed, so without this the tab bar is unreachable and the app feels stuck.
Only back from Live TV leaves the application — the tabs are peers, not a stack, and dropping out of
the app for pressing back once too many is not acceptable on a television.

**A failed login does not overwrite a working profile.** Credentials are only written to the
Keystore after the provider has accepted them.

**Database changes are never destructive.** `fallbackToDestructiveMigration` is deliberately not
enabled: wiping favourites and continue-watching because a schema changed would be a real loss. The
category cache is disposable and is rebuilt; the viewer's own data is migrated.

**Cleartext HTTP is permitted.** Many panels are only reachable over plain HTTP, and refusing it
would make the app useless against a large share of real providers. The trade-off is real: over
HTTP, anyone able to observe the network path can read your credentials and your viewing traffic.
Use HTTPS where your provider offers it.

**`tv-material` supplies no `TextField`.** As of `androidx.tv:tv-material` 1.0.0 the library has
buttons, cards, surfaces and lists, but no text input, so `TvTextField` is built on Compose's
`BasicTextField` and styled for focus visibility at three metres. Up and down move focus rather than
the caret, or a single-line field would trap focus inside itself; right only moves on at the end of
the text, so a viewer can still reach a field that sits to the right of another.

**The lists are stable `LazyRow` / `LazyColumn`, not `TvLazyRow` / `TvLazyColumn`.** Those only exist
in alpha builds of `androidx.tv:tv-foundation`; depending on an alpha Compose artifact alongside a
stable BOM is a compatibility problem waiting to happen.

**The Media3 playback controller is switched off for live TV.** It is laid out for touch. On a
television its scrub bar and buttons are the wrong size in the wrong places, and they occupy the
D-pad directions that channel zapping needs. The VOD player draws its own overlay instead.

## Roadmap

Done: EPG, favourites, continue watching, films and series. Still to do, most valuable first:

1. **Quality-variant merging and adaptive quality.** Collapsing a channel's `1`/`2`/`3`/4K/FHD/HD/SD
   variants into one logical channel, choosing by measured bandwidth, stepping down on stalls and
   failing over when a variant is dead. This is the single biggest perceived-quality improvement
   available, and none of the comparable open-source players do it.
2. **Catch-up / timeshift** via the panel's `has_archive`. Absent from every comparable
   open-source player — a differentiator if your provider supports it.
3. **Multi-profile support** and per-profile cache isolation.
4. **Parental / category lock** with a management password.
5. In-app update check, so a sideloaded build learns about fixes.
6. CI, and a release signing configuration.

## Honest limitations

- **Nothing has been run on a real television yet.** Everything below was verified on an Android
  emulator against a mock panel and against a real provider, but no one has pressed a physical remote
  against it. D-pad focus behaviour in particular is the thing most likely to need adjustment once it
  is on real hardware.
- **Audio smoothness and frame pacing cannot be judged in an emulator.** It has no hardware video
  decode and routes audio through the host, so it cannot tell you whether playback is smooth on your
  television. That has to be checked on the device.
- **Search cannot be complete on this provider.** See
  [Search, and why it is local](#search-and-why-it-is-local). The panel implements no search action,
  and truncates enough large responses that a full index run left 99 of 269 shelves unreadable. Those
  titles are not findable, and no amount of retrying reliably fixes it.
- **The indexer cannot be stopped once you leave the search screen.** It keeps running, and it is
  resumable so nothing is lost, but the progress and the stop button are only on the search screen.
  Force-stopping the app also stops it.
- The debug APK is unsigned, as debug builds are. It is fine for sideloading; a release build needs
  a signing config that is deliberately not committed.
- Dependencies are pinned to versions known to resolve together (AGP 8.7.3, Kotlin 2.0.21,
  Media3 1.7.1, Room 2.6.1), not to the newest available.

## Third-party notices

This project bundles FFmpeg-derived software decoders via NextLib. FFmpeg is licensed under
LGPLv3; NextLib is GPL-3.0. Full attribution for FFmpeg, libvpx, Mbed TLS and the rest of the stack is
in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).

## Licence

[GPL-3.0](LICENSE).

## Legal

4J TV is a player. It ships with no channels, playlists, subscriptions or provider directory, and it
is not affiliated with any IPTV provider. You are responsible for ensuring you are authorised to
access whatever you configure it with.
