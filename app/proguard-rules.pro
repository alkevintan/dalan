# Dalan R8 rules. Starts from SmartTube's smarttubetv/proguard-rules.pro (MIT, Copyright (c) 2020-present yuliskov) and adds keeps for
# the vendored modules. Correctness beats size: the resolver is reflection / JSON-path driven.

-dontobfuscate
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*,InnerClasses,EnclosingMethod,Exceptions

-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn com.jayway.jsonpath.**
-dontwarn retrofit2.**
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn kotlin.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.xbill.**
-dontwarn sun.net.spi.nameservice.**
-dontwarn com.google.errorprone.annotations.**

# All of MediaServiceCore / SharedModules (models are filled by reflection: gson, json-path,
# custom @JsonPath annotation processing in youtubeapi).
-keep class com.liskovsoft.** { *; }
-keep interface com.liskovsoft.** { *; }
-keepclassmembers class com.liskovsoft.** { *; }

# Gson / Retrofit / json-path
-keep class com.google.gson.** { *; }
-keep class com.jayway.jsonpath.** { *; }
-keep class net.minidev.json.** { *; }
-keep class retrofit2.** { *; }
-keep interface retrofit2.** { *; }
-keepclasseswithmembers class * { @retrofit2.http.* <methods>; }
-keep class com.grack.nanojson.** { *; }
-keep class org.brotli.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep class com.localebro.** { *; }
-keep class org.xbill.DNS.** { *; }

# j2v8: native library looks up Java classes/methods by name through JNI.
-keep class com.eclipsesource.v8.** { *; }
-keepclassmembers class com.eclipsesource.v8.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# ExoPlayer 2.10.6 fork (incl. SABR + protobuf-lite generated classes)
-keep class com.google.android.exoplayer2.** { *; }
-keep interface com.google.android.exoplayer2.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**
# ExoPlayer reflectively loads some renderers / extensions
-dontwarn com.google.android.exoplayer2.ext.**

# Apache commons-io fork
-keep class org.apache.commons.** { *; }

# RxJava 2
-dontwarn io.reactivex.**
-keep class io.reactivex.** { *; }

# App
-keep class com.aktivesolutions.dalan.** { *; }
-keep class kotlin.Metadata { *; }
