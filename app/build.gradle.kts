plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.aktivesolutions.dalan"
    compileSdk {
        version = release(37) { minorApiLevel = 0 }
    }

    defaultConfig {
        applicationId = "com.aktivesolutions.dalan"
        minSdk = 21
        targetSdk = 25
        versionCode = 3
        versionName = "0.1.2"
        ndk { abiFilters += "armeabi-v7a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        jniLibs.useLegacyPackaging = true
    }
    lint {
        // Sideloaded on an API 25 tablet; the Play-store targetSdk check does not apply.
        disable += "ExpiredTargetSdkVersion"
        abortOnError = false
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":mediaserviceinterfaces"))
    implementation(project(":youtubeapi"))
    implementation(project(":sharedutils"))
    implementation(project(":library-core"))
    implementation(project(":library-dash"))
    implementation(project(":library-hls"))
    implementation(project(":library-sabr"))
    implementation(project(":library-ui"))
    implementation(libs.okhttp)
    implementation(libs.rxjava)
    implementation(libs.rxandroid)
    implementation(libs.recyclerview)
    implementation(libs.annotation)
    implementation(libs.core.ktx)

    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

// About screen shows LICENSE, THIRD_PARTY.md and every bundled licence text: copy them into the generated assets dir.
val copyLicenses = tasks.register<Copy>("copyLicenses") {
    from(rootProject.file("THIRD_PARTY.md"))
    from(rootProject.file("LICENSE"))
    into(layout.buildDirectory.dir("generated/licenses"))
    // assets/licenses/*
    from(rootProject.fileTree("third_party/licenses") { include("*.txt") }) { into("licenses") }
    from(rootProject.file("third_party/j2v8/LICENSE.txt")) { rename { "j2v8-EPL-1.0.txt" }; into("licenses") }
    from(rootProject.file("third_party/exoplayer/LICENSE")) { rename { "exoplayer-Apache-2.0.txt" }; into("licenses") }
    from(rootProject.file("third_party/commons-io-2.8.0/LICENSE.txt")) { rename { "commons-io-LICENSE.txt" }; into("licenses") }
    from(rootProject.file("third_party/commons-io-2.8.0/NOTICE.txt")) { rename { "commons-io-NOTICE.txt" }; into("licenses") }
}
android.sourceSets.getByName("main").assets.directories.add(layout.buildDirectory.dir("generated/licenses").get().asFile.path)
tasks.named("preBuild") { dependsOn(copyLicenses) }
