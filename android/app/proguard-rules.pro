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

# AndroidJUnitRunner shares this runtime dependency with the target APK. AGP
# removes the duplicate from the instrumentation APK, so retain its public entry
# points even when the app's calls are otherwise fully inlined by R8. Keep just
# this small bridge, not the app/test framework or an unminified/debug target.
-keep class androidx.tracing.Trace { *; }

# JNI names used by the bundled Tesseract4Android engine.
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }
