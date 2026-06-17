# 4J-IPTV

A Tivimate-style IPTV player for Android Google TV with Xtream Codes API support.

## Features

- **Live TV, Movies & Series** — Browse and stream all content from your Xtream Codes provider
- **Local Search** — Instant search across all cached content (Live, Movies, Series) with filter tabs
- **Tivimate-inspired UI** — Dark theme, sidebar navigation, dense content rows, D-pad optimized
- **ExoPlayer** — Hardware-accelerated playback with aspect ratio control on the player bar
- **Persistent Cache** — Content preloads on home screen and survives app restarts via disk cache
- **Multi-Provider** — Add, switch, and manage multiple Xtream Codes providers

## Screenshots

*(coming soon)*

## Tech Stack

- **Kotlin** + **Jetpack Compose for TV** (leanback)
- **Media3 ExoPlayer** for video playback
- **Ktor** for HTTP client
- **DataStore** for provider persistence
- **Kotlinx Serialization** for JSON parsing

## Building

Open in Android Studio or build via command line:

```bash
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

## Usage

1. Launch the app and go to **Add Provider**
2. Enter your Xtream Codes credentials (server URL, username, password)
3. Content loads automatically — browse Live TV, Movies, or Series from the sidebar
4. Use **Search** to instantly filter all cached content

## License

[MIT](LICENSE)
