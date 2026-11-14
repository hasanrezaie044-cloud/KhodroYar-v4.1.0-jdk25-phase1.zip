pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google(); mavenCentral()
    }
}
rootProject.name = "CarManagement"
include(":app")
// CI-only, self-instrumenting black-box tests; never part of the delivered app.
include(":release-smoke")

// Disposable simulated store for IPC/deep-link tests, never bundled in :app.
include(":bazaar-fixture")
