# Hardened R8 / ProGuard Obfuscation & Anti-Reverse-Engineering Rules for AADIFY
-repackageclasses 'o'
-flattenpackagehierarchy 'o'
-allowaccessmodification
-overloadaggressively
-renamesourcefileattribute SourceFile
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*

# Strip all logging calls from decompiled bytecode so nothing leaks in logcat or decompilers
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

# Keep Room Entities, DAOs, and Database intact
-keep class com.example.models.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }

# Keep Android / Firebase / Media3 / WebView @JavascriptInterface entry points
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**
-dontwarn okhttp3.**
-dontwarn okio.**

