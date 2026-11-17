plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.google.android.exoplayer2.core"
    compileSdk {
        version = release(37) { minorApiLevel = 0 }
    }
    defaultConfig {
        minSdk = 21
        consumerProguardFiles("proguard-rules.txt")
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
    // DALAN: upstream adds sharedutils for a custom DNS resolver
    implementation(project(":sharedutils"))
    implementation(libs.annotation)
    compileOnly(libs.checker.qual)
    compileOnly(libs.checker.compat.qual)
}
