# Hardened R8 / ProGuard Obfuscation & Security Rules for AADIFY
-repackageclasses 'o'
-allowaccessmodification
-renamesourcefileattribute SourceFile

# Strip verbose/debug logging from decompiled bytecode
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
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
