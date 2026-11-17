plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.liskovsoft.youtubeapi"
    compileSdk {
        version = release(37) { minorApiLevel = 0 }
    }
    defaultConfig {
        minSdk = 21
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(project(":commons-io-2.8.0"))
    implementation(project(":sharedutils"))
    implementation(project(":mediaserviceinterfaces"))
    implementation(project(":j2v8"))

    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.gson)
    implementation(libs.json.path)
    implementation(libs.okhttp)
    implementation(libs.cronet.okhttp)
    implementation(libs.cronet.api)
    implementation(libs.rxandroid)
    implementation(libs.rxjava)
    implementation(libs.annotation)
    implementation(libs.core.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.florianingerl.regex)
    implementation(libs.webkit)
    implementation(libs.nanojson)
}
