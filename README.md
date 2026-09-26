# 4J TV

A free IPTV player for **Google TV**, Android TV and Fire TV, built around the Xtream Codes API.

Live TV with a programme guide, plus films and series with resume, and search across all three.

---

## Status

| | |
|---|---|
| Features | Live TV, EPG, films, series, search, manual sync, continue watching, favourites |
| Builds | `assembleFullDebug`, `assembleLiteDebug` |
| Tests | 175 unit tests, all passing |
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
  player. Fetched for the channels actually on screen, not the whole lineup. This is the feature most
  in doubt against a real account — see [Honest limitations](#honest-limitations).

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
- **A sync you start yourself**, on its own screen, with progress per kind and a stop button. It
  fetches your shelf list itself, so it works on a fresh install without browsing first; it picks up
  where it left off if stopped; and it can be left running while you watch something.
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
  browsing is instant and survives a provider outage. See
  [How caching works, and why there is no timer](#how-caching-works-and-why-there-is-no-timer).
- **Encrypted credentials.** AES-256-GCM under a non-exportable Android Keystore key. App backup is
  disabled so the ciphertext is never copied off the device.
- **Survives bad providers.** Large JSON responses are routinely truncated in transit. A cut-off
  response is not thrown away: the part that arrived intact is parsed and kept, and only the missing
  tail is lost. Requests that fail for transient reasons are retried. Guides that arrive
  Base64-encoded, or that list only programmes that have already finished, are handled.
- **`full` / `lite` flavors.** See
  [Codecs the hardware cannot always handle](#codecs-the-hardware-cannot-always-handle).

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

Requires **JDK 17** and the **Android SDK** with the **compileSdk 35** platform installed. Point
`local.properties` at your SDK:

```properties
sdk.dir=/path/to/Android/sdk
```

Then:

```bash
./gradlew :app:testFullDebugUnitTest     # 175 unit tests
./gradlew :app:assembleFullDebug         # APK with software audio + video fallback (43.2 MB debug)
./gradlew :app:assembleLiteDebug         # 20.4 MB, no software decoders
```

Versions are pinned in `gradle/libs.versions.toml` rather than tracked to "latest", so a build is
reproducible: AGP 8.7.3, Kotlin 2.0.21, Media3 1.7.1, Room 2.6.1, minSdk 26, compileSdk and
targetSdk 35.

Both flavors are also produced per ABI in a release build (`assembleFullRelease`), which is where
the size difference really shows.

## Testing

The unit tests run on the JVM with no device — 175 of them. A few are worth knowing about, because
each exists to stop a specific bug from coming back:

- **`SearchIndexingTest`** stands up a mock Xtream panel over a real socket. It covers a shelf that
  cannot be read not ending the run, a completed shelf not being re-fetched, a failed shelf being
  retried rather than remembered as done, and a truncated shelf being partly indexed rather than
  discarded. Two of those tests exist because the opposite shipped and shipped silently.
- **`TruncatedJsonTest`** covers salvaging a cut-off JSON array, including the cases a naive
  implementation gets wrong: a brace inside a title, an escaped quote, a cut landing mid-string, a
  trailing comma.
- **`SearchCoverageTest` and `SyncProgressTest`** pin the numbers on screen. Every one of them is a
  claim about what the app knows, and "everything is synced" when a third of the catalogue was never
  read is a falsehood told with total confidence.
- **`VodCachingTest`** asserts a shelf is downloaded once and that a deliberate refresh really does
  re-fetch. With a panel that answers a film shelf in about 20 MB, "re-fetched on every visit" is the
  difference between a usable app and an unusable one.
- **`VodViewModelTest`** includes an explicit guard against a category being fetched over and over.
  That is not hypothetical: it happened, at dozens of requests a second, which would burn a real
  provider account's connection allowance.
- **`VodMigrationTest`** builds an old database by hand, migrates it, then opens the result with
  Room so the migrated schema is checked against the entities the app actually declares.

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

Every server-side search action was tried against the provider this was built against:
`search_streams`, `search`, `search_vod`, `search_movies`, `search_movie` and `search_series` are
**all ignored**, each answered with a login object. Passing `search` to `get_vod_streams` or
`get_series` is worse than useless — the parameter is ignored and the entire catalogue comes back,
large enough to have the app killed for memory while buffering it. There is no server-side search to
call.

So the app keeps its own index, in its own database file, and searches that.

**Coverage is the trade, so it is shown rather than hidden.** Nothing is indexed until something asks
for it, so a search that returns nothing is not evidence that a title is absent from your provider.
The screens say so explicitly rather than implying the provider does not carry it, and the **Sync**
screen shows exactly how much is covered and how many shelves could not be read.

Three things feed the index, in increasing order of effort:

1. **Shelves you browse** are indexed as they load, which costs one write — the rows have just been
   downloaded anyway.
2. **A sync you start yourself**, from the **Sync** screen. It fetches the shelf list first, so it
   works on a fresh install without browsing anything, then walks each shelf in turn. Sequential
   rather than parallel, because a hundred simultaneous requests at one panel is how a provider
   starts refusing them.
3. **Resuming.** Progress is recorded per shelf, so closing the app mid-sync continues where it left
   off instead of starting again. A shelf that could not be read is left unrecorded, so a later run
   retries it.

Shelves are walked one at a time rather than paged, and the reason is worth stating: the `limit` and
`start` parameters are [ignored by this panel](#how-caching-works-and-why-there-is-no-timer), so there
is no smaller slice to ask for. The indexer sends them anyway, for panels that do honour them, and
stops when a panel proves otherwise by handing back the same first row twice.

**A shelf that cannot be read is skipped, not fatal.** This provider truncates large responses
constantly, and testing found it the hard way: the first version let one oversized shelf end the
whole run, having indexed 2,084 channels out of several hundred shelves, after which no amount of
pressing the button again would ever finish it. A shelf that fails is now stepped over, left
unrecorded so a later run can retry it, and **counted in the summary**.

**A truncated shelf is partly read, not thrown away.** This is the fix that makes a sync worth
running. A cut-off response used to fail the whole call, so a shelf whose *last* film never arrived
lost every film before it too — and with a 20 MB shelf cut at 2.2 MB, that is most of it. The
response is now closed at its last complete title and parsed, so the titles that did arrive are kept
and searchable. What is lost is only the tail, and the shortfall is reported using the panel's own
`Content-Length` rather than guessed at.

A shelf cut off *before any title completed* is still skipped: there is nothing to keep, and reporting
that as an indexed shelf would leave it looking like a provider with no films in it.

That last measure is the honest limit of the feature. On a panel that truncates as readily as this
one, some titles cannot be indexed at all, and the sync screen says how many shelves are affected
rather than reporting a tidy percentage.

---

## How caching works, and why there is no timer

Every shelf is downloaded **once** and then served from the database, indefinitely. A film or series
shelf is not re-fetched when you come back to it, switch tabs, or switch shelves and return.

**Why no expiry.** The panel this was built against offers no way to ask what has changed. Checked
directly against it:

- `get_vod_categories`, `get_vod_streams`, `get_series` and `get_live_streams` all return
  **no `ETag` and no `Last-Modified`**, so a conditional request cannot come back `304`.
- Passing `search` to `get_vod_streams` or `get_series` is **ignored** — the whole catalogue comes
  back, which is why search is [local](#search-and-why-it-is-local).
- `limit` and `start` are **ignored too**, per category: `limit=5&start=0`, `start=5` and `start=500`
  all return byte-identical rows. A shelf cannot be asked for a smaller slice.

So a shelf here is megabytes — a film category answers with about 20 MB — and the only way to avoid
the download is to not make it. Any automatic schedule would be a guess that costs a large download
whether or not anything actually changed.

**Refreshing is therefore deliberate.** Each shelf shows how old its cached copy is and has a
**Refresh** button that re-fetches it on demand. The age is on screen because a shelf that quietly
stopped updating would otherwise be indistinguishable from one that is current, and the only clue
would be a film the viewer expected to be missing.

**A failed fetch is not remembered as done.** The shelf is recorded as downloaded only after the
write succeeds, so a download that was cut off leaves the shelf to be retried rather than
permanently empty and permanently "already downloaded" — which looks exactly like a provider with no
films in it.

---

## Design notes

A few decisions that are deliberate, and would otherwise look odd:

**Categories are fetched one at a time.** Asking a panel for its entire live list in one call returns
every channel it has — on a large account that is tens of thousands of records and a payload that
dominates startup time and memory. Each category is fetched on demand and cached. The same applies to
films and series.

**Films and series categories are stored with a kind, and keyed on it.** A real provider returns both
in one combined list, distinguished only by names like `Movies-New Releases` against `Series-Drama`.
Inferring the kind from that text is fragile, and with a single-column key a series category of `7`
silently replaces a film category of `7` and a shelf appears empty.

So the kind is recorded at fetch time and carried, never re-derived. That is not theoretical caution:
the search indexer once worked out the kind by testing the category **id** for a `Series` prefix,
while this panel's `category_id` is numeric (`570`, `401`, `419`) and "Series" appears only in the
name. Every check was false, every series shelf was walked as a film shelf, and no series was ever
indexed — a search for a series title returned live channels and nothing else. The test that should
have caught it passed the name in as the id, so it passed for the wrong reason.

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

Done: EPG, favourites, continue watching, films and series, software HEVC fallback, search with a
manual sync, and per-shelf caching with deliberate refresh. Still to do, most valuable first:

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

- **Nothing has been run on a real television yet.** Everything claimed above was verified on an
  emulator against a mock panel and against a real provider, but no one has pressed a physical
  remote against it. D-pad focus behaviour in particular is the thing most likely to need
  adjustment once it is on real hardware.
- **The programme guide is unconfirmed against the real provider.** It is implemented, tested
  against a mock panel, and rendered correctly earlier in the project. But during the most recent
  real-account testing, every channel sampled came back with no listings at all. Two of this
  panel's quirks bear on that: it Base64-encodes some guide titles, and `get_short_epg` returns
  only programmes that have already finished, so "on now" and "next" can legitimately be empty.
  The empty-everything case is unexplained, and **a blank guide is the first thing to report**.
- **Audio smoothness and frame pacing cannot be judged in an emulator.** It has no hardware video
  decode and routes audio through the host, so it cannot tell you whether playback is smooth on your
  television. That has to be checked on the device.
- **Search is only as good as the sync behind it, and the sync cannot finish everything.** See
  [Search, and why it is local](#search-and-why-it-is-local). The panel implements no search action,
  so search can only cover what has been downloaded — and a shelf cut off before *any* of its titles
  arrived has nothing to salvage, so those titles are not findable at all. The Sync screen reports how
  many shelves are in that state rather than rounding up to a percentage.
- **Software video decoding is unverified on real HEVC.** The fallback demonstrably engages on the
  emulator — a hardware decoder that claims HEVC and then fails is detected, and the stream is
  retried with FFmpeg in front. But the one episode that needed it rendered a solid green frame
  there, and other HEVC episodes from the same series played correctly in hardware. Whether the
  green frame is an emulator artefact or a real limit is untested, because the emulator's software
  rendering path is a poor proxy. **Sideload and try an HEVC title that fails in hardware.**
- **A running sync can only be stopped from the Sync screen.** It keeps going if you leave, and it is
  resumable so nothing is lost, but the stop button is not elsewhere in the app. Force-stopping the
  app also stops it.
- The debug APK is unsigned, as debug builds are. It is fine for sideloading; a release build needs
  a signing config that is deliberately not committed.

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
