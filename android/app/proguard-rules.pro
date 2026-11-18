-keep class io.narl.protongallery.bridge.** { *; }
-keep class com.sun.jna.** { *; }
-dontwarn java.awt.**

-keep class org.rustls.platformverifier.** { *; }
-keep class io.narl.protongallery.PlatformTls { *; }

# Release builds keep only warnings and errors in logcat.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
