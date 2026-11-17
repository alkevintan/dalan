plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.liskovsoft.sharedutils"
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
    implementation(libs.appcompat)
    implementation(libs.preference)
    implementation(libs.okhttp)
    implementation(libs.okhttp.doh)
    implementation(libs.okhttp.logging)
    implementation(libs.brotli.dec)
    implementation(libs.okhttp.profiler) {
        exclude(group = "com.squareup.okhttp3", module = "okhttp") // force global version
    }
    implementation(libs.cronet.api)
    implementation(libs.gson)
    implementation(libs.rxandroid)
    implementation(libs.rxjava)
    implementation(libs.disklrucache)
    implementation(libs.netcipher)
    implementation(libs.core.ktx)
    implementation(libs.dnsjava)
}
