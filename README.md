# Dalan

Dalan is a lightweight YouTube client for Android 5.0 and later. It's built for older and low-end
devices: phones, tablets and TV boxes that the official app has dropped, or that run it badly.

It resolves streams with SmartTube's [MediaServiceCore](https://github.com/yuliskov/MediaServiceCore)
and plays them through SmartTube's ExoPlayer fork, which handles DASH, HLS and YouTube's SABR protocol.
No Google account or Google Play Services is needed (signing in is optional).

- Package `com.aktivesolutions.dalan`, version 0.1.1
- minSdk 21 (Android 5.0), targetSdk 25
- Native code: `armeabi-v7a`. This runs on 32-bit ARM devices and on 64-bit ARM devices that can run
  32-bit apps, which is most of them before about 2023. It won't run on 64-bit-only devices or on x86.

## Features (0.1)
- **Feeds that work without an account:** Music (the default when signed out; Home is the default when signed in), Gaming, News, Sports and Live. A Home tab
  appears only when Home returns rows (it's empty when you aren't signed in). Feeds show rows of cards, or
  a grid with infinite scroll.
- **Sign in with a code:** Settings > Account shows a code to enter at `yt.be/activate` (the YouTube TV
  device flow). Once signed in, Home, Subscriptions and History tabs appear. Sign out from the same place.
- **Light and dark theme:** Settings > Appearance > Theme (System default, Light or Dark). System follows the
  device's night mode on Android 10+ and is dark on older versions. The tan and near-black brand colours are
  used for accents; the player video area stays black in both themes. A change applies when you return to the
  app, with no restart.
- **Skeleton loading:** feeds, channel pages, search and the player's info panel show placeholders shaped like
  the content (rows of cards, a grid, title and related list) with a cheap alpha pulse instead of a spinner.
- **Search** with suggestions, **channel pages** (an uploads grid) and **related videos**.
- **Player:**
  - Landscape: the video fills the screen. Portrait: a 16:9 player with the title, channel, views and
    date, an expandable description and the related list below. Landscape split view: 65/35.
  - Seek bar with buffered position and glyph controls: ±10 s, quality, captions (WebVTT; the icon fills when on),
    speed 0.5–2× (a text label) and fullscreen.
- **Background audio** through a foreground service, with a MediaStyle notification and a MediaSession
  (lock-screen and media-key control). It handles audio focus and pauses when headphones are unplugged.
- **Resume position** for each video (300 most recent), and **autoplay** of the next related video.
- **Opens links:** `youtube.com/watch`, `youtu.be`, `m.youtube.com` and `/shorts/` URLs, plus text
  shared from other apps.
- **Content filters:** Settings > Content filters. Hide videos whose title or channel matches words you choose
  (whole words, accent- and case-insensitive; `zelda*` matches a prefix; several words match as a phrase), for
  example to avoid spoilers. Filters can be limited to titles or channel names and can expire. Long-press any
  video to hide its channel, start a filter from its title, or tell YouTube "Not interested" (signed in).
  Filtering applies to feeds, search, channel pages, related videos and autoplay, never to History or to a video
  you open yourself.
- **Content language:** Settings > Content > Content language picks the language YouTube uses for recommendations,
  trending, search and search suggestions. YouTube picks the country from your network location, so it can't be
  changed. Changing the language reloads the current feed.
- **In-app updates:** checks GitHub Releases once a day (switch off in Settings > Updates) and offers to download,
  verify (same package and signing key) and install the new APK. Settings > Updates > Check for updates runs it by hand.
- **Settings:** account (sign in / out), max quality (360–1080p), allow 60 fps, prefer VP9 (shown only when the device has a
  hardware VP9 decoder), background play, autoplay next, caption language, theme, clear the image cache, licences.

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
  by reflection. It's signed with the release key when `DALAN_RELEASE_*` is set (see Releasing), otherwise with the debug key.
- **Gradle memory:** R8 needs about 2 GB of heap and 1 GB of metaspace. If your global limit is lower,
  pass `./gradlew -Dorg.gradle.jvmargs="-Xmx2g -XX:MaxMetaspaceSize=1g" ...`.
- **Unit tests** (`app/src/test`, no network): URL parser, track policy, resume LRU, source order, error
  recovery, image scaling.
- **Instrumented smoke test** (`app/src/androidTest`, `ResolverSmokeTest`): resolves a public video and
  asserts `containsMedia()`. Run it by hand on a device.

## Releasing
1. Bump `versionCode` / `versionName` in `app/build.gradle.kts`, commit and push to `main`.
2. Write release notes to a file and run `scripts/release.sh notes.md`. It builds the release APK, refuses to
   continue if it is debug-signed, and creates the GitHub release `v<versionName>` with the APK attached.
   The in-app updater finds it through the Releases API.

The release key is `~/.android/dalan-release.jks` (alias `dalan`); its passwords are in
`~/.gradle/gradle.properties` as `DALAN_RELEASE_STORE_FILE`, `DALAN_RELEASE_STORE_PASSWORD`,
`DALAN_RELEASE_KEY_ALIAS` and `DALAN_RELEASE_KEY_PASSWORD`. **Back up both files somewhere safe:** if the key is
lost, nobody can update in place any more and everyone has to uninstall and reinstall. Without those properties
the release build falls back to the debug key with a warning, which `release.sh` rejects.

Users on the debug-signed 0.1.2 must uninstall once before installing 0.1.3 (Android refuses updates signed with
a different key), so in-app updating starts from 0.1.3 onwards.

## Install
Sideload the APK, for example:
```
adb install -r Dalan-0.1.1.apk
```
Or copy it to the device (LocalSend, USB, a download) and open it with "Install unknown apps" allowed.

## Known limits
- **Sign-in is read-only browsing:** Home, Subscriptions and History only; no likes, comments or playlist
  management. The refresh token is stored unencrypted in a file in the app's internal storage by
  MediaServiceCore (`allowBackup` is off). Dalan doesn't report watch history, so videos watched in Dalan
  won't appear in History. Playlists open as a plain grid.
- **The resolver depends on YouTube internals and *will* break when YouTube changes** (n-sig, PoToken,
  client versions, SABR). To refresh it:
  1. Clone newer MediaServiceCore, SharedModules and SmartTube.
  2. Copy the modules listed in `THIRD_PARTY.md` over `third_party/`.
  3. Re-apply the patches listed there: currently the `CronetManager` stub, the `isDisposed` check in
     `YouTubeContentService`, the `NSigExtractor` regex, the lockup `getType()` handling in
     `CommonHelper`/`CommonItems`, and the build files.
  4. Update the commits in `THIRD_PARTY.md`, rebuild, and run the smoke test.

  SmartTube's player fork (`exoplayer/`) and MediaServiceCore are developed in lockstep, so update them
  together.
- **Cronet isn't shipped.** Networking uses only plain OkHttp / HttpURLConnection, which keeps the APK
  smaller and avoids depending on Play Services.
- **Not fully handled yet:** live streams, DVR and Shorts feeds are only lightly handled. Captions are
  side-loaded WebVTT; auto-translate isn't offered.
- **APK size:** about 12 MB, most of it the V8 engine (`libj2v8.so`).

## Licence
MIT; bundled components keep their own licences (see THIRD_PARTY.md).
