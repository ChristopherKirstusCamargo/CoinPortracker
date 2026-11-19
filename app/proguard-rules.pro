# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keep,includedescriptorclasses class com.coinportracker.**$$serializer { *; }
-keepclassmembers class com.coinportracker.** { *** Companion; kotlinx.serialization.KSerializer serializer(...); }
# SQLCipher
-keep class net.zetetic.** { *; }
-dontwarn org.slf4j.**
# Strip android.util.Log calls (defense against accidental logging of financial data)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

# LiteRT (TFLite) — classes chamadas pelo código nativo
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# Media3 (ExoPlayer / Transformer) — reflexão e codecs
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
# ZXing QR
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**
