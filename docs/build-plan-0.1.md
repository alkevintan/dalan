# Dalan — build plan (0.1)

> **Historical document.** This is the original build plan for Dalan 0.1. It was written for the first
> test device (Samsung SM-T550; its device profile lived outside this repo) and predates that device's move
> to LineageOS 18.1 / Android 11. Dalan itself is meant for any Android 5.0+ device: see the README for
> current behaviour. Where this plan and the code disagree, the code and the README win.

YouTube client for the Samsung SM-T550 (see `../CLAUDE.md`): Android 7.1.1 / API 25, 32-bit
Cortex-A53 @1.2 GHz, Adreno 306, 1024×768 mdpi 4:3, 96 MB heap growth limit, storage nearly full.

Stream resolution uses SmartTube's resolver (**MediaServiceCore**). Playback uses **SmartTube's
ExoPlayer 2.10.6 fork**, which plays SABR as well as DASH/HLS. v0.1 scope: browse, search,
channel pages, related videos, a full player. No Google account.

App id / namespace: `com.aktivesolutions.dalan`. minSdk 21, targetSdk 25, compileSdk 37.
versionName 0.1.0 / versionCode 1.

## 1. Upstream sources (vendored, pinned)

Copy, don't use git submodules (this folder is not a git repo). Clone each repo at the pinned
commit into a throwaway dir, then copy only the listed modules into `dalan/third_party/`:

| Repo | Commit | Modules to copy → `third_party/…` |
|---|---|---|
| github.com/yuliskov/MediaServiceCore | `30389a8d2dd13186b4ab4afe1263282375a37f21` | `mediaserviceinterfaces`, `youtubeapi` |
| github.com/yuliskov/SharedModules | `5475d31808139e21c0e55f4d3462481002afae63` | `sharedutils`, `commons-io-2.8.0`, `j2v8` |
| github.com/yuliskov/SmartTube | `0000d7a14da287f1020aadbeaae9d5036b767931` | `exoplayer-amzn-2.10.6/library/{core,dash,hls,sabr,ui}` and whatever they need from `exoplayer-amzn-2.10.6/constants.gradle` (reproduce the values in Kotlin DSL; don't copy the Groovy). Also use as **reference only** (do not vendor): `common/.../exoplayer/ExoMediaSourceFactory.java`, `.../exoplayer/errors/*`, `.../exoplayer/selector/*`, `.../playback/controllers/VideoLoaderController.java`, `ErrorFixerController.java`, `smarttubetv/proguard-rules.pro` |

- Drop each module's `src/test`, `src/androidTest`, `libs/*.jar` you don't need, and non-ARM natives:
  keep only `j2v8/src/main/jniLibs/armeabi-v7a/libj2v8.so`; delete x86, x86_64 and arm64-v8a.
- Write `THIRD_PARTY.md` listing each repo, commit, license (SmartTube/MediaServiceCore/SharedModules
  are **GPL-3.0**, the ExoPlayer fork is Apache-2.0), and every local patch you made. Dalan is
  therefore GPL-3.0. Add a `LICENSE` (GPL-3.0 text) at the root.
  **SUPERSEDED (0.1.1):** the upstream repos are actually MIT and j2v8 is EPL-1.0 (GPL-incompatible), so
  Dalan is **MIT** (`LICENSE`, Copyright (c) 2026 Aktive Solutions); see `THIRD_PARTY.md`.
- Keep local patches minimal and mark each one with a `// DALAN:` comment.

## 2. Build

- Gradle 9.8.2 wrapper: copy `gradlew`, `gradlew.bat` and `gradle/wrapper/*` from `../visor`.
  AGP 9.5.0 (built-in Kotlin), version catalog `gradle/libs.versions.toml`, `local.properties`
  copied from `../visor`. Root `settings.gradle.kts` includes `:app` plus every vendored module,
  each with `projectDir = file("third_party/…")`.
- Give every vendored module a **new `build.gradle.kts`** (`com.android.library`). Ignore their
  Groovy build files (delete them). Each module gets:
  `namespace` = the package in its AndroidManifest (remove `package=` from the manifest),
  `compileSdk 37`, `minSdk 21`, Java 17 source/target (fall back to Java 8 for a module only if it
  fails to compile), no product flavors. `youtubeapi` gets no flavors and depends on `:j2v8` directly.
- Dependencies: take the versions from `SharedModules/constants.gradle` (retrofit 2.5.0,
  gson 2.8.2, json-path 2.4.0, okhttp 3.12.13, rxjava2/rxandroid, cronet-api 105.5195.68 +
  cronet-okhttp 0.1.0, annotation 1.1.0, core-ktx, kotlinx-coroutines) and pin them in the catalog.
  Bump a version only if it won't build with AGP 9 / the current Kotlin, and record each bump in
  THIRD_PARTY.md. Run `./gradlew :app:dependencies` and make sure there's exactly one OkHttp and one
  ExoPlayer (the vendored fork, no Maven ExoPlayer).
- `app`: `ndk { abiFilters += "armeabi-v7a" }`. `packaging.jniLibs.useLegacyPackaging = true`
  so the 24 MB `libj2v8.so` is stored compressed in the APK (smaller download; it's extracted
  once on install). Record the release APK size.
- Release: R8 minify + shrink, debug-signed (sideload). ProGuard rules: start from
  `smarttubetv/proguard-rules.pro`, plus `-keep class com.liskovsoft.** { *; }` and the
  gson/retrofit/j2v8/exoplayer keeps. Correctness beats size: if R8 breaks parsing, keep more.
- `lint { disable += "ExpiredTargetSdkVersion" }`, like visor. Lint must not fail the build because
  of vendored code (`checkReleaseBuilds = false` / `abortOnError = false` on library modules is fine).

## 3. App architecture (`app/src/main/java/com/aktivesolutions/dalan/`)

Kotlin, platform `Activity` + `android:Theme.Material` (dark), plain Views. AndroidX
`recyclerview` is allowed. Nothing else UI-related (no AppCompat, Compose, Glide or Coil).

- `DalanApp` (Application): initialise anything the resolver needs from a `Context`.
  `GlobalPreferences.instance(context)` from sharedutils is used by youtubeapi, so check every
  `*.instance(Context)` / `sInstance` singleton SmartTube's `Application`/`SplashActivity` sets up
  and replicate the minimum. Also set the resolver locale/region from the system locale.
- `data/Repo`: thin wrapper over `YouTubeServiceManager.instance()`:
  `home()` (`getHome()`), `trending()` (group via `getGroup(...)` or a MediaGroup from home;
  pick whatever MediaServiceCore exposes), `search(q)`, `suggest(q)` (`getSearchTags`),
  `continueGroup(g)`, `channel(item)` (`getGroup(mediaItem)` for a channel item), `metadata(videoId)`
  (`getMetadata`: title, description, channel, views, date, related/suggestions groups),
  `formatInfo(videoId)`. Run everything on RxJava `Schedulers.io()` and deliver on
  `AndroidSchedulers.mainThread()`. Dispose in `onDestroy`.
- `ui/ImageLoader`: small in-house thumbnail loader built on the shared OkHttp client. Downsample
  to the target view size (`inSampleSize`), `RGB_565`, an `LruCache` sized to 1/8 of
  `Runtime.maxMemory()`, cancel when a view is recycled, at most 4 concurrent requests, and a small
  disk cache (20 MB max) in `cacheDir`.
- `ui/MainActivity`: ActionBar with a `SearchView` (with suggestions) and tabs/buttons for
  **Home** and **Trending**. Content is a vertical `RecyclerView` of `MediaGroup` sections (section
  title + horizontal row of cards) or, for a single group, a `GridLayoutManager` grid: 4 columns in
  landscape, 3 in portrait. Card = 16:9 thumbnail, duration badge, 2-line title, channel ·
  views · age. Infinite scroll via `continueGroup`. Pull-to-refresh isn't available without
  AndroidX swiperefresh, so add a Refresh menu item. Show an offline/error state with a Retry button.
- `ui/SearchActivity` (or a MainActivity mode): result grid, infinite scroll.
- `ui/ChannelActivity`: channel header (name, avatar) + uploads grid.
- `ui/PlayerActivity`:
  - Landscape: video fills the screen (`PlayerView` from the fork's `library-ui`) with custom
    controls (play/pause, seek bar with buffered position, ±10 s, quality, captions, speed,
    fullscreen toggle). Portrait: 16:9 player on top; below it title, channel (tap opens
    ChannelActivity), views/date, expandable description, and a related-videos list.
    Landscape non-fullscreen: player left (~65%), info + related on the right. Handle rotation with
    `configChanges` so the player is never recreated.
  - Keep the screen on while playing. Handle audio focus and pause on becoming-noisy.
  - Resume position: store `videoId → positionMs` in SharedPreferences (cap 300 entries, LRU),
    and skip it when the video has < 30 s left.
  - Autoplay the next related video at the end (setting, default on).
- `player/MediaSourceBuilder`: port SmartTube's `ExoMediaSourceFactory` (SABR, DASH from
  formatInfo, DASH from `createMpdStream()`, DASH URL, HLS, progressive URL list) with the
  matching load-error policies. Choose the source in the **same order as
  `VideoLoaderController`** (around L290–320): live HLS/DASH URL cases, then DASH formats, then
  SABR, then URL formats. If formatInfo `isUnplayable()`, show `getPlayabilityReason()`.
- `player/ErrorRecovery`: port the important parts of `ErrorFixerController`. On a source or
  `403`-type error: `switchNextClient()`, re-fetch formatInfo, resume at the same position.
  Max 3 attempts per video, then show the error and a Retry button.
- `player/TrackPolicy` (on top of `DefaultTrackSelector`; reference SmartTube's `selector/`):
  - **The SM-T550 has no hardware VP9/AV1 decoder.** Prefer `avc1` (H.264) video. Allow VP9 only
    if `MediaCodecList` reports a hardware VP9 decoder; never use AV1.
  - Default max quality 720p (setting: 360/480/720/1080, where 1080 is H.264 only). Max 30 fps by
    default (setting allows 60).
  - Audio: prefer AAC (mp4a) and fall back to Opus.
  - Quality dialog lists the available heights (+ Auto); speed dialog offers 0.5–2.0×.
  - Captions: list `formatInfo.getSubtitles()`, side-load the chosen one as a WebVTT
    `SingleSampleMediaSource` merged with the main source; remember the language choice.
- `player/PlaybackService`: foreground service that **owns the `SimpleExoPlayer`**, so audio
  keeps playing in the background (setting "Background play", default on) with a
  MediaStyle notification (play/pause, close) and a MediaSession. The `mediasession` extension
  needs `support-media-compat`, so use the framework `android.media.session.MediaSession` directly.
  PlayerActivity binds to it and attaches the `PlayerView`. Release everything on Close.
  If background play is off, pause in `onStop`.
- `ui/SettingsActivity`: framework `PreferenceActivity`/`PreferenceFragment` (no AndroidX
  preference). Settings: max quality, allow 60 fps, prefer VP9 (only shown when hardware VP9
  exists), background play, autoplay next, caption language, clear image cache, about/licenses
  (shows THIRD_PARTY.md content).
- Intent filters: `ACTION_VIEW` for `youtube.com/watch?v=`, `youtu.be/`, `m.youtube.com`,
  `youtube.com/shorts/` → PlayerActivity, and `ACTION_SEND` text/plain containing a YouTube URL.
  Write a `VideoUrlParser` with unit tests.

### Performance rules (this tablet)
- Never block the main thread. No more than one formatInfo request in flight per player.
- Bitmap memory: RGB_565, downsampled. Pause image loading during fast flings.
- `DefaultLoadControl`: modest buffers (min 15 s, max 30 s, playback 2.5 s, rebuffer 5 s) so
  memory stays low on 1.4 GB RAM.
- Check the heap: browsing + playing 720p must stay under 96 MB (`adb shell dumpsys meminfo`).

## 4. Tests (JVM, `app/src/test`)
- `VideoUrlParser` (all URL forms, timestamps `t=1m30s` / `t=90`, garbage input).
- `TrackPolicy` selection logic, pure Kotlin over a list of simple format descriptors:
  prefers avc1, respects the max height/fps caps, falls back when only VP9 exists.
- Resume-position store LRU cap.
- Source-order selection (a fake `MediaItemFormatInfo` → expected source kind).
- No live network tests in the unit suite. Instead, add an **instrumented smoke test**
  (`androidTest`, run by hand on the device): resolve a known stable public video's formatInfo and
  assert `containsMedia()`.

## 5. Done when
- `./gradlew testDebugUnitTest assembleDebug assembleRelease` passes.
- The release APK is built for armeabi-v7a only and copied to `dalan/Dalan-0.1.0.apk`; report its size.
- `README.md` covers: features, the resolver + player architecture, how to build, install
  (`adb install -r` or LocalSend), known limits (no sign-in in 0.1, no VP9 hardware, resolver
  breakage when YouTube changes and how to refresh the vendored modules to a newer commit),
  and the licence notice (**superseded:** MIT, bundled components keep their own licences).
- An honest note on what was **not** verified on device.

## 6. After the build (not part of the build task)
- Opus high-effort review, then fixes.
- Static site `~/Projects/smt550-revival/site/`: add `apps/dalan.html`, rows in the catalogue,
  downloads and index tables, a sidebar link, the APK in `downloads/` and `SHA256SUMS`. GPL-3.0
  means the page must offer the corresponding source (a source tarball in `downloads/` or a link).
  **SUPERSEDED (0.1.1):** Dalan is MIT, so no GPL source offer is required; the page should show the MIT
  licence and link the j2v8 source (see `THIRD_PARTY.md`, "j2v8 source availability").
  **Ask the user before running `deploy.sh`**, since it's a public site.
- Send the APK to the tablet via LocalSend when the user asks.
