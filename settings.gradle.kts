pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "dalan"
include(":app")

// Vendored upstream modules (see THIRD_PARTY.md)
val vendored = mapOf(
    ":mediaserviceinterfaces" to "third_party/mediaserviceinterfaces",
    ":youtubeapi" to "third_party/youtubeapi",
    ":sharedutils" to "third_party/sharedutils",
    ":commons-io-2.8.0" to "third_party/commons-io-2.8.0",
    ":j2v8" to "third_party/j2v8",
    ":library-core" to "third_party/exoplayer/core",
    ":library-dash" to "third_party/exoplayer/dash",
    ":library-hls" to "third_party/exoplayer/hls",
    ":library-sabr" to "third_party/exoplayer/sabr",
    ":library-ui" to "third_party/exoplayer/ui",
)
vendored.forEach { (path, dir) ->
    include(path)
    project(path).projectDir = file(dir)
}
