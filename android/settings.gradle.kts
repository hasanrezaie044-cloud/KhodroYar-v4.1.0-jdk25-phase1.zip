pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google(); mavenCentral()
        maven("https://jitpack.io") { content { includeGroup("cz.adaptech.tesseract4android") } }
    }
}
rootProject.name = "CarManagement"
include(":app", ":ocr")
