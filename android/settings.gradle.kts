// Flocket - Android client for herdr-core. Module map and rules: docs/ARCHITECTURE.md.

pluginManagement {
    includeBuild("build-logic")
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

rootProject.name = "flocket"

include(":app")
include(":benchmark")

// Pure Kotlin/JVM (no Android)
include(":core:model")
include(":core:common")
include(":core:protocol")
include(":core:session")
include(":core:ssh")

// Android libraries
include(":core:security")
include(":core:database")
include(":core:datastore")
include(":core:data")
include(":core:designsystem")
include(":core:ui")
include(":core:terminal")
include(":core:terminal-ghostty")
include(":core:testing")

// Features (api = navigation keys and public contracts, impl = screens and ViewModels)
listOf("onboarding", "hosts", "terminals", "terminal", "settings").forEach { feature ->
    include(":feature:$feature:api")
    include(":feature:$feature:impl")
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
