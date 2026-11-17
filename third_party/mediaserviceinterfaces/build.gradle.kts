plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.liskovsoft.mediaserviceinterfaces"
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
    implementation(project(":sharedutils"))
    implementation(libs.rxandroid)
    implementation(libs.rxjava)
    implementation(libs.annotation)
}
