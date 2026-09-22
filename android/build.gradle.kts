// AGP 9.x ships built-in Kotlin support (KGP 2.3.x is a runtime dependency of AGP), so
// `org.jetbrains.kotlin.android` is deliberately NOT declared here any more — applying it
// alongside AGP 9 is an error under the new DSL. The Compose compiler plugin is still a
// separate plugin and its version must track the KGP that AGP brings in.
plugins {
    id("com.android.application") version "9.2.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.10" apply false
    id("com.google.devtools.ksp") version "2.3.10" apply false
}
