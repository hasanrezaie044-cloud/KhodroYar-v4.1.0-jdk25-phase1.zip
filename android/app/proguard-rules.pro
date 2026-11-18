# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.khodroyar.app.data.legacy.** { *; }
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    static <1>$Companion Companion;
    *** Companion;
    static **$* *;
}
-keepclasseswithmembers class ** { kotlinx.serialization.KSerializer serializer(...); }

# WorkManager instantiates workers reflectively; keep our reminder worker intact.
-keep class com.khodroyar.app.notifications.** { *; }
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
# Annotation-only artifacts pulled in for R8 (jsr305 / error_prone).
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**

# LiteRT-LM uses a Kotlin/JNI facade and Gson message conversion. Keep the official runtime API
# stable under release shrinking; model files themselves are never packaged as resources.
-keep class com.google.ai.edge.litertlm.** { *; }
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**
