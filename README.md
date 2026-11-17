# Dalan

Dalan is a lightweight YouTube client for Android 5.0 and later. It's built for older and low-end
devices: phones, tablets and TV boxes that the official app has dropped, or that run it badly.

It resolves streams with SmartTube's [MediaServiceCore](https://github.com/yuliskov/MediaServiceCore)
and plays them through SmartTube's ExoPlayer fork, which handles DASH, HLS and YouTube's SABR protocol.
No Google account or Google Play Services is needed.

- Package `com.aktivesolutions.dalan`, version 0.1.1
- minSdk 21 (Android 5.0), targetSdk 25
- Native code: `armeabi-v7a`. This runs on 32-bit ARM devices and on 64-bit ARM devices that can run
  32-bit apps, which is most of them before about 2023. It won't run on 64-bit-only devices or on x86.

## Features (0.1)
- **Feeds that work without an account:** Music (the default), Gaming, News, Sports and Live. A Home tab
  appears only when Home returns rows (it's empty when you aren't signed in). Feeds show rows of cards, or
  a grid with infinite scroll.
- **Search** with suggestions, **channel pages** (an uploads grid) and **related videos**.
- **Player:**
  - Landscape: the video fills the screen. Portrait: a 16:9 player with the title, channel, views and
    date, an expandable description and the related list below. Landscape split view: 65/35.
  - Seek bar with buffered position, ±10 s, quality, captions (WebVTT), speed 0.5–2×, fullscreen.
- **Background audio** through a foreground service, with a MediaStyle notification and a MediaSession
  (lock-screen and media-key control). It handles audio focus and pauses when headphones are unplugged.
- **Resume position** for each video (300 most recent), and **autoplay** of the next related video.
- **Opens links:** `youtube.com/watch`, `youtu.be`, `m.youtube.com` and `/shorts/` URLs, plus text
  shared from other apps.
- **Settings:** max quality (360–1080p), allow 60 fps, prefer VP9 (shown only when the device has a
  hardware VP9 decoder), background play, autoplay next, caption language, clear the image cache, licences.

### Built for weak hardware
- **Track selection follows what the device can decode in hardware.** H.264 comes first. VP9 is used only
  with a hardware decoder, or in software at 480p or lower. AV1 is never used.
- **Audio prefers stereo AAC.** Surround and DRC (dynamic range compression) tracks are skipped.
- **The default cap is 720p at 30 fps.** When a video has no track within the cap (for example a
  60 fps-only upload), Dalan falls back to the best track the device can play.
- **Light on memory:** plain Views rather than Compose, small player buffers, and RGB_565 thumbnails
  scaled to card size. The image cache uses 1/8 of the heap.
- **Video decoding stops** while the app is in the background, so only audio is decoded.

## Tested on
| Device | OS | Notes |
|---|---|---|
| Samsung Galaxy Tab A 9.7 (SM-T550) | LineageOS 18.1 (Android 11), no GMS | Snapdragon 410, 1.4 GB RAM, hardware H.264/VP8 only. Feeds, SABR playback (H.264 + AAC), background audio and media controls work. Java heap is about 10 MB while playing. |

The remaining device checks are tracked in [#4](https://github.com/alkevintan/dalan/issues/4). Reports
from other devices are welcome. Open an issue with the model, Android version and what you saw.

## Architecture
```
app (Kotlin, platform Activities + plain Views + RecyclerView)
 |- data/Repo          Rx wrapper over YouTubeServiceManager (io -> main thread)
 |- ui/*               MainActivity, SearchActivity, ChannelActivity, PlayerActivity, SettingsActivity,
 |                     FeedView, ImageLoader (own: RGB_565, LruCache 1/8 heap, 4 workers, 20 MB disk cache)
 `- player/            PlaybackService (owns SimpleExoPlayer) + PlaybackController
                       MediaSourceBuilder  (port of SmartTube ExoMediaSourceFactory)
                       SourceOrder         (same decision order as SmartTube VideoLoaderController)
                       TrackPolicy         (pure Kotlin, tiered: H.264 within the caps, H.264 above, hardware VP9,
                                            software VP9 <= 480p, H.264 exceeding capabilities; never AV1;
                                            audio: no surround, non-DRC, itag 140>139>251>250)
                       DalanTrackSelector  (DefaultTrackSelector driven by TrackPolicy)
                       ErrorRecovery       (port of the key parts of ErrorFixerController, max 3 attempts)
third_party/           vendored, pinned upstream modules (see THIRD_PARTY.md)
 |- youtubeapi + mediaserviceinterfaces   MediaServiceCore: the stream *resolver*
 |- sharedutils, commons-io, j2v8         SharedModules (j2v8 runs YouTube's player JS: n-sig / PoToken)
 `- exoplayer/{core,dash,hls,sabr,ui}     SmartTube's ExoPlayer 2.10.6 fork: plays DASH, HLS and SABR
```

**Resolver:** `DalanApp.onCreate()` calls `GlobalPreferences.instance(app)`. That's the Context carrier
MediaServiceCore's `AppService`, `MediaServiceData`, `LocaleManager` and others read from. The locale
comes from the system configuration.

**Playback:** `formatInfo` → `SourceOrder` → `MediaSourceBuilder` → ExoPlayer. On a 403 or source error,
the controller calls `switchNextClient()`, fetches the format info again and resumes at the same position.
After 3 tries it shows an error with a Retry button.

## Build
Requirements: JDK 17+ (built with 21), the Android SDK with platform 37 and build-tools, and a
`local.properties` file containing `sdk.dir`.
```
./gradlew testDebugUnitTest assembleDebug assembleRelease
cp app/build/outputs/apk/release/app-release.apk Dalan-0.1.1.apk
```
- **Release APK:** R8-minified with obfuscation off and broad keep rules, because the resolver is driven
  by reflection. It's signed with the debug key, so it's for sideloading only.
- **Gradle memory:** R8 needs about 2 GB of heap and 1 GB of metaspace. If your global limit is lower,
  pass `./gradlew -Dorg.gradle.jvmargs="-Xmx2g -XX:MaxMetaspaceSize=1g" ...`.
- **Unit tests** (`app/src/test`, no network): URL parser, track policy, resume LRU, source order, error
  recovery, image scaling.
- **Instrumented smoke test** (`app/src/androidTest`, `ResolverSmokeTest`): resolves a public video and
  asserts `containsMedia()`. Run it by hand on a device.

## Install
Sideload the APK, for example:
```
adb install -r Dalan-0.1.1.apk
```
Or copy it to the device (LocalSend, USB, a download) and open it with "Install unknown apps" allowed.

## Known limits
- **No sign-in in 0.1,** so no subscriptions, history or likes. Playlists open as a plain grid.
- **The resolver depends on YouTube internals and *will* break when YouTube changes** (n-sig, PoToken,
  client versions, SABR). To refresh it:
  1. Clone newer MediaServiceCore, SharedModules and SmartTube.
  2. Copy the modules listed in `THIRD_PARTY.md` over `third_party/`.
  3. Re-apply the patches listed there: currently the `CronetManager` stub, the `isDisposed` check in
     `YouTubeContentService`, and the build files.
  4. Update the commits in `THIRD_PARTY.md`, rebuild, and run the smoke test.

  SmartTube's player fork (`exoplayer/`) and MediaServiceCore are developed in lockstep, so update them
  together.
- **Cronet isn't shipped.** Networking uses only plain OkHttp / HttpURLConnection, which keeps the APK
  smaller and avoids depending on Play Services.
- **Not fully handled yet:** live streams, DVR and Shorts feeds are only lightly handled. Captions are
  side-loaded WebVTT; auto-translate isn't offered.
- **APK size:** about 12 MB, most of it the V8 engine (`libj2v8.so`).

## Licence
MIT; bundled components keep their own licences (see THIRD_PARTY.md). One dependency (a GPL-2.0 regex
library used by the resolver) is still being resolved: see [#1](https://github.com/alkevintan/dalan/issues/1).
