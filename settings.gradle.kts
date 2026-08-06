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

rootProject.name = "mlbb-draft"

// :engine is a pure-JVM module on purpose — the draft brain must be unit-testable
// in milliseconds without an emulator, and reusable from the Phase 1 overlay service.
include(":engine")
include(":app")
