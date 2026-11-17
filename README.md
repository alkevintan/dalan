# Dalan

A YouTube client for the Samsung Galaxy Tab A 9.7 (SM-T550 running LineageOS 18.1 / Android 11 / API 30, 32-bit
Cortex-A53, 1024x768 mdpi, ~1.4 GB RAM, 192 MB heap, no GMS). Version 0.1.1, package `com.aktivesolutions.dalan`,
minSdk 21, targetSdk 25, armeabi-v7a only. No Google account, no Google Play Services.

## Features (0.1)
- Topic feeds that work without an account: Music (default), Gaming, News, Sports, Live; a Home tab appears only if
  Home returns rows (it is empty when logged out). Rows of cards, or a grid with infinite scroll, search with suggestions,
  channel pages (uploads grid), related videos.
- Full player: video fills the screen in landscape; portrait is a 16:9 player with title, channel,
  views/date, expandable description and related list; landscape non-fullscreen is a 65/35 split.
  Seek bar with buffered position, +/-10 s, quality, captions (WebVTT), speed 0.5-2x, fullscreen toggle.
- Background audio via a foreground service with a MediaStyle notification and MediaSession,
  audio-focus and "becoming noisy" handling, resume position per video (300-entry LRU), autoplay next.
- Opens `youtube.com/watch`, `youtu.be`, `m.youtube.com`, `/shorts/` links and shared text.
- Settings: max quality (360-1080), allow 60 fps, prefer VP9 (shown only if a hardware VP9 decoder exists),
  background play, autoplay next, caption language, clear image cache, licenses.

## Architecture
```
app (Kotlin, platform Activities + plain Views + RecyclerView)
 |- data/Repo          Rx wrapper over YouTubeServiceManager (io -> main thread)
 |- ui/*               MainActivity, SearchActivity, ChannelActivity, PlayerActivity, SettingsActivity,
 |                     FeedView, ImageLoader (own: RGB_565, LruCache 1/8 heap, 4 workers, 20 MB disk cache)
 `- player/            PlaybackService (owns SimpleExoPlayer) + PlaybackController
                       MediaSourceBuilder  (port of SmartTube ExoMediaSourceFactory)
                       SourceOrder         (same decision order as SmartTube VideoLoaderController)
                       TrackPolicy         (pure Kotlin, tiered: H.264 within the caps, H.264 above, hardware VP9, software VP9 <= 480p,
                        H.264 exceeding capabilities; never AV1; audio: no surround, non-DRC, itag 140>139>251>250)
                       DalanTrackSelector  (DefaultTrackSelector driven by TrackPolicy)
                       ErrorRecovery       (port of the key parts of ErrorFixerController, max 3 attempts)
third_party/           vendored, pinned upstream modules (see THIRD_PARTY.md)
 |- youtubeapi + mediaserviceinterfaces   MediaServiceCore: the stream *resolver*
 |- sharedutils, commons-io, j2v8         SharedModules (j2v8 runs YouTube's player JS: n-sig / PoToken)
 `- exoplayer/{core,dash,hls,sabr,ui}     SmartTube's ExoPlayer 2.10.6 fork: plays DASH, HLS and SABR
```
Resolver: `DalanApp.onCreate()` calls `GlobalPreferences.instance(app)` (the Context carrier that
MediaServiceCore's `AppService`, `MediaServiceData`, `LocaleManager` ... read); the locale comes from the
system configuration. Playback: `formatInfo` -> `SourceOrder` -> `MediaSourceBuilder` -> ExoPlayer; on a
403/source error the controller calls `switchNextClient()`, re-fetches the format info and resumes at the same
position (3 tries, then an error with a Retry button).

## Build
Requirements: JDK 17+ (built with 21), Android SDK with platform 37 and build-tools, `local.properties`
with `sdk.dir`.
```
./gradlew testDebugUnitTest assembleDebug assembleRelease
cp app/build/outputs/apk/release/app-release.apk Dalan-0.1.1.apk
```
The release APK is R8-minified (obfuscation off, broad keep rules: the resolver is reflection driven) and
signed with the debug key (sideload only). Gradle's JVM needs about 2 GB heap and 1 GB metaspace for R8:
`./gradlew -Dorg.gradle.jvmargs="-Xmx2g -XX:MaxMetaspaceSize=1g" ...` if your global limit is lower.
Unit tests: URL parser, track policy, resume LRU, source order, error recovery (see `app/src/test`, no network).
An instrumented smoke test (`app/src/androidTest`, `ResolverSmokeTest`) resolves a public video and asserts
`containsMedia()`; run it by hand on the device.

## Install
```
adb install -r Dalan-0.1.1.apk
```
or send the APK to the tablet with LocalSend.

## Known limits
- No sign-in in 0.1: no subscriptions, history or likes. Playlists open as a plain grid.
- The SM-T550 has hardware H.264/VP8 only (VP9 is software-only, no AV1), so Dalan plays H.264 (avc1) + AAC; 1080p only exists
  as H.264 and may stutter on a 1.2 GHz Cortex-A53. Default cap is 720p30.
- The resolver depends on YouTube internals and **will break when YouTube changes** (n-sig / PoToken /
  client versions / SABR). To refresh: clone newer MediaServiceCore, SharedModules and SmartTube, re-copy the
  modules listed in `THIRD_PARTY.md` over `third_party/`, re-apply the patches listed there (currently the
  `CronetManager` stub, the `isDisposed` check in `YouTubeContentService` plus build files), bump the commits in `THIRD_PARTY.md`, rebuild, and run the
  smoke test. SmartTube's fork of the player (`exoplayer/`) and MediaServiceCore are developed in lockstep:
  update them together.
- Cronet is not shipped (plain OkHttp / HttpURLConnection only). Live streams, DVR and Shorts feeds are
  only lightly handled. Captions are side-loaded WebVTT (auto-translate is not offered).

## Not verified on a device
This build was produced and unit-tested on a PC. It has **not** been installed or run on any device or
emulator: resolver initialisation, a real playback (DASH and SABR), the foreground service/notification,
R8-shrunk release behaviour, heap use (target: well under the 192 MB growth limit while browsing + 720p), and
all layouts have never been seen on screen.

## License
MIT; bundled components keep their own licenses (see THIRD_PARTY.md).
