pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "DBPlay"
include(":common")
include(":mobile")
include(":automotive")
include(":shared")

// ---------------------------------------------------------------------------
// Local extension layer (NOT part of upstream xcertplay).
// Keep this block contiguous and append-only so upstream merges stay trivial.
// See ARCHITECTURE.md, PATCHES.md (PATCH-0001).
// ---------------------------------------------------------------------------
include(":car:api")
include(":car:platform-android")
include(":car:platform-flyme")
include(":car:platform-ecarx")
include(":car:platform-geely")
include(":car:core-bridge")
include(":car:ui")
include(":car:steering")
include(":car:app")
include(":car:hud-probe")
