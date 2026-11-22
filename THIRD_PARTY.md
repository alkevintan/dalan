# Third-party code in Dalan

Dalan itself is licensed under the **MIT License**, Copyright (c) 2026 Aktive Solutions (see `LICENSE`).
Bundled components keep their own licenses, listed below; their texts are copied into the app (About screen)
from `third_party/licenses/`, `third_party/j2v8/LICENSE.txt`, `third_party/exoplayer/LICENSE` and
`third_party/commons-io-2.8.0/{LICENSE,NOTICE}.txt`.

## Vendored sources (copied, not submodules)

| Upstream | Commit | Copied to | License (as found in the repo) |
|---|---|---|---|
| github.com/yuliskov/MediaServiceCore | `30389a8d2dd13186b4ab4afe1263282375a37f21` | `third_party/mediaserviceinterfaces`, `third_party/youtubeapi` | MIT (c) 2020-present yuliskov |
| github.com/yuliskov/SharedModules | `5475d31808139e21c0e55f4d3462481002afae63` | `third_party/sharedutils`, `third_party/commons-io-2.8.0`, `third_party/j2v8` | MIT (SharedModules); commons-io: Apache-2.0 (except `mod/{Objects,StandardCharsets}.java`, see below); j2v8: Eclipse Public License 1.0 |
| github.com/yuliskov/SmartTube | `0000d7a14da287f1020aadbeaae9d5036b767931` | `third_party/exoplayer/{core,dash,hls,sabr,ui}` (ExoPlayer 2.10.6 Amazon fork) | ExoPlayer: Apache-2.0 (c) Google; SmartTube-specific additions (sabr, DashManifestParser2 ...): MIT |
| SmartTube (same commit) | | `app/.../player/smarttube/*.java` (3 small classes) | MIT |

Licensing history: the original plan expected GPL-3.0 for SmartTube / MediaServiceCore / SharedModules. The upstream
`LICENSE` files at the pinned commits are actually **MIT** (copies in `third_party/licenses/`), so Dalan is MIT
(0.1.1; 0.1.0 was labelled GPL-3.0-or-later, which was a mistake: j2v8 is EPL-1.0 and cannot be combined with a GPL work).

## Other licences inside the app

| Component | Where | License |
|---|---|---|
| commons-io `org/apache/commons/io/mod/Objects.java`, `mod/StandardCharsets.java` | `third_party/commons-io-2.8.0` | GPL-2.0 **with the Classpath exception** (Oracle/OpenJDK headers; copied by SharedModules). The Classpath exception permits linking into a work under other licences. |
| V8 (inside `libj2v8.so`) | `third_party/j2v8/src/main/jniLibs/armeabi-v7a` | BSD-3-Clause (Google V8; J2V8 itself is EPL-1.0) |
| J2V8 Java sources and JNI binding | `third_party/j2v8` | EPL-1.0 (`third_party/j2v8/LICENSE.txt`) |
| ExoPlayer 2.10.6 fork | `third_party/exoplayer` | Apache-2.0 (c) Google; SmartTube additions MIT |

### j2v8 source availability (EPL-1.0)
The J2V8 source used by Dalan is available from <https://github.com/eclipsesource/J2V8> (upstream) and from the
SharedModules commit pinned above (`5475d31808139e21c0e55f4d3462481002afae63`, directory `j2v8`). The Java/JNI
source is vendored unmodified in this repository under `third_party/j2v8`; only the prebuilt `armeabi-v7a`
`libj2v8.so` (which bundles V8) is a binary taken from SharedModules, the other ABIs were removed.

Dropped from each module when vendoring: `src/test`, `src/androidTest`, `libs/*.jar`, Groovy `build.gradle`
files, `.gitignore`, commons-io docs/site/build files, non-ARM j2v8 natives (x86, x86_64, arm64-v8a).
Each module got a new `build.gradle.kts` (see `settings.gradle.kts`).

## Local patches to vendored code

| # | File | Change |
|---|---|---|
| 1 | `third_party/sharedutils/src/main/java/com/liskovsoft/sharedutils/cronet/CronetManager.kt` | `// DALAN:` Replaced the body with a stub that returns `null`. Upstream builds an embedded Cronet engine through `org.chromium.net.impl.NativeCronetProvider` (`cronet-embedded`, ~5 MB native, plus a duplicate manifest namespace clash under AGP 9). The only caller (`RetrofitOkHttpHelper.addCronetInterceptor`, itself commented out upstream) already handles a null engine and falls back to plain OkHttp. This also means nothing needs Google Play Services. |
| 2 | every vendored `src/main/AndroidManifest.xml` | Removed the `package="..."` attribute (AGP 9 takes the namespace from `build.gradle.kts`). Mechanical, no XML comment possible on the removed attribute. |
| 3 | every vendored module | `build.gradle` (Groovy) deleted, replaced by `build.gradle.kts`: `com.android.library`, compileSdk 37, minSdk 21, Java 17, `buildFeatures.buildConfig = true`, no flavors, lint non-fatal. `youtubeapi` depends directly on `:j2v8` (no flavors). `exoplayer/core` keeps the upstream `consumerProguardFiles`. |
| 4 | `third_party/youtubeapi/src/main/java/com/liskovsoft/youtubeapi/service/YouTubeContentService.java` (`emitGroupsPartial(emitter, Pair)`) | `// DALAN:` The section-list paging loop now stops when the emitter is disposed (`!emitter.isDisposed()` in the loop condition plus a return right after each emission). Upstream keeps fetching the whole feed on a non-interruptible thread after the subscriber is gone. Paging could not be moved into `Repo` because `continueSectionList` is not exposed through `ContentService`. |
| 5 | `third_party/youtubeapi/src/main/java/com/liskovsoft/youtubeapi/app/playerdata/NSigExtractor.kt` | `// DALAN:` `mNFuncPattern` used `com.github.florianingerl.util:regex` only for the conditional `(?(2)...)`; that library is GPL-2.0, so it was dropped. The pattern now uses `java.util.regex`: an empty marker group `()` after the first two (non-`var=`) alternatives and a trailing `(?:\2\|...)` (a backreference to a group that did not participate fails, which emulates yt-dlp's `(?(var)...)`). Group numbers shifted (name = group 4, idx = group 5), and `extractNFunctionName1` now returns the name when there is no `[idx]` as yt-dlp does (upstream fell through to `extractNFunctionName2`, or crashed on `idx.toInt()` if a matching array assignment existed). |
| 6 | `third_party/youtubeapi/src/main/java/com/liskovsoft/youtubeapi/common/models/gen/CommonHelper.kt`, `CommonItems.kt` | `// DALAN:` `ItemWrapper.getType()` now handles `lockupViewModel` via its new `contentType` field (`LOCKUP_CONTENT_TYPE_CHANNEL/PLAYLIST/VIDEO/SHORT`; channel/playlist only when the lockup has a browse id, otherwise video if it has a videoId, e.g. mixes that open a watchEndpoint). The TV search endpoint returns every result as a lockup, so channel and playlist results were `TYPE_UNDEFINED` upstream. |
| 7 | `third_party/youtubeapi/.../common/models/gen/CommonHelper.kt`, `CommonItems.kt`, `impl/mediagroup/BaseMediaGroup.kt`, `SuggestionsGroup.kt` | `// DALAN:` (a) `ItemWrapper` gains `adSlotRenderer`, and `isAd()` / `isUnrecognized()` wrappers (ads, or renderers Dalan does not model, which parsed to an all-null wrapper = blank card) are dropped in `BaseMediaGroup` and the initial `SuggestionsGroup` shelf (its continuation path is unfiltered; the app skips items without a videoId there). (b) `LOCKUP_CONTENT_TYPE_ALBUM` and `_PODCAST` map to `TYPE_PLAYLIST` like playlists (browseId `VL...`). (c) `TILE_CONTENT_TYPE_SHORTS` (and the existing lockup SHORT) map to `TYPE_VIDEO`; `TileItem.getVideoId()` falls back to `contentId` for Shorts tiles. (d) a `TILE_CONTENT_TYPE_VIDEO` tile with no videoId whose `onSelectCommand.browseEndpoint` is `VL...` (show / series) is `TYPE_PLAYLIST`, `UC...` is `TYPE_CHANNEL`. Tests: `third_party/youtubeapi/src/test/.../DalanItemWrapperTest.kt` (adds `testImplementation` junit/gson to the module build file). |

All other vendored Java/Kotlin sources are byte-for-byte upstream.

Classes copied into the app (not in `third_party/`): `DashDefaultLoadErrorHandlingPolicy`,
`SabrDefaultLoadErrorHandlingPolicy`, `LiveDashManifestParser` (SmartTube `common/.../exoplayer/`); only the
`package` line changed, marked with a `// DALAN:` header. `MediaSourceBuilder`, `ErrorRecovery`,
`TrackPolicy`, `PlaybackController` are Dalan ports/rewrites of SmartTube's `ExoMediaSourceFactory`,
`ErrorFixerController`, `selector/*`, `VideoLoaderController`.

## Dependency versions

Taken from `SharedModules/constants.gradle`, pinned in `gradle/libs.versions.toml`.

| Dependency | Version | Note |
|---|---|---|
| retrofit2 / converter-gson | 2.5.0 | as upstream |
| gson | 2.8.2 | as upstream |
| json-path | 2.4.0 | as upstream |
| okhttp / dnsoverhttps / logging-interceptor | 3.12.13 | as upstream; single OkHttp in the app |
| rxjava / rxandroid | 2.2.21 / 2.1.1 | as upstream |
| cronet-api / cronet-okhttp | 105.5195.68 / 0.1.0 | as upstream (API classes only) |
| core-ktx | 1.12.0 | as upstream |
| kotlinx-coroutines-android | 1.7.3 | upstream's expression (`kotlinVersion`) is not a real coroutines version; used `kotlinxVersion` from the same file |
| appcompat / preference | 1.2.0 / 1.1.0 | needed by sharedutils (not used by Dalan's UI) |
| androidx.media | 1.0.1 | needed by the vendored ExoPlayer `ui` module |
| recyclerview | 1.1.0 | the only AndroidX UI library used by the app |
| protobuf-javalite / protoc | 3.17.3 | as upstream |
| nanojson, webkit | 1.7 / 1.11.0 | as upstream |
| **Bumped:** protobuf Gradle plugin | 0.9.5 -> **0.10.1** | 0.9.5 fails to apply under AGP 9.5 (`Cannot cast ... LibraryExtensionImpl to BaseExtension`) |
| **Replaced:** okhttp-brotli 4.1.0 | -> `org.brotli:dec` 0.1.2 | okhttp-brotli 4.x drags OkHttp 4 in next to 3.12.13 and OkHttp 4 turns `HttpUrl.parse` into a compile error in `DohProviders.kt`. `UnzippingInterceptor` only needs `org.brotli.dec.BrotliInputStream`, which is what okhttp-brotli itself depended on. |
| **Removed:** `org.chromium.net:cronet-embedded` | | see patch 1 |
| androidx.test:runner | 1.1.0 | instrumented smoke test only |

AGP 9.5.0, Gradle 9.8.2 (built-in Kotlin).

## Maven dependency licences

From the POMs in the Gradle cache (versions in the table above and `gradle/libs.versions.toml`).

| Dependency | License |
|---|---|
| retrofit2, converter-gson | Apache-2.0 |
| gson, json-path | Apache-2.0 |
| okhttp, okhttp-dnsoverhttps, logging-interceptor | Apache-2.0 |
| org.brotli:dec | MIT |
| rxjava, rxandroid | Apache-2.0 |
| cronet-api (org.chromium.net) | Chromium licence (POM: "Chromium and built-in dependencies", BSD-3-Clause based) |
| cronet-okhttp | Apache-2.0 |
| androidx (core-ktx, annotation, appcompat, preference, media, recyclerview, webkit) | Apache-2.0 |
| kotlinx-coroutines-android | Apache-2.0 |
| disklrucache, netcipher, okhttp-profiler | Apache-2.0 |
| dnsjava | BSD-2-Clause |
| protobuf-javalite | BSD-3-Clause |
| nanojson | no licence in the POM; the upstream repository (mmastrac/nanojson) is Apache-2.0 |

## Icons
The vector glyphs in `app/src/main/res/drawable/ic_*.xml` (search, refresh, settings, play, pause, close,
fullscreen, fullscreen-exit, closed-caption on/off, open-in-new, HD, and the arrow of replay-10 / forward-10)
follow the shapes of Google's Material Icons, which are licensed under the **Apache License 2.0**
(https://github.com/google/material-design-icons). The "10" digits in replay-10 / forward-10 and the
HD badge cut-out (a filled shape) are Dalan's own. The launcher icon is Dalan's own.
