pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google(); mavenCentral()
        maven("https://jitpack.io") {
            content { includeGroup("cz.adaptech.tesseract4android") }
        }
    }
}
rootProject.name = "CarManagement"
include(":app")
// CI-only, self-instrumenting black-box tests; never part of the delivered app.
include(":release-smoke")
