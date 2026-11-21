# Hardened R8 / ProGuard Obfuscation & Anti-Reverse-Engineering Rules for AADIFY
-repackageclasses 'a.b'
-flattenpackagehierarchy 'a.b'
-allowaccessmodification
-overloadaggressively
-renamesourcefileattribute ""
-optimizationpasses 5
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-keepattributes Exceptions,InnerClasses,Signature,EnclosingMethod

# Strip all logging and stdout/stderr diagnostics from compiled bytecode
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}
-assumenosideeffects class java.io.PrintStream {
    public void println(...);
    public void print(...);
}

# Keep only Room Database schema, Entities, DAOs, and Parcelable contracts
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Keep Android / Firebase / Media3 / WebView @JavascriptInterface entry points
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.example.BuildConfig { *; }
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**
-dontwarn okhttp3.**
-dontwarn okio.**
