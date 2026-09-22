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
