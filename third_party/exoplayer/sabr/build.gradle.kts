plugins {
    alias(libs.plugins.protobuf)
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.google.android.exoplayer2.source.sabr"
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
    implementation(project(":library-core"))
    implementation(project(":mediaserviceinterfaces"))
    implementation(project(":sharedutils"))
    implementation(project(":youtubeapi"))
    compileOnly(libs.checker.qual)
    implementation(libs.annotation)
    implementation(libs.protobuf.javalite)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                create("java") { option("lite") }
            }
        }
    }
}
