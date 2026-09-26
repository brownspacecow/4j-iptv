# Keep the Xtream DTOs intact: kotlinx.serialization generates serializers that are only
# referenced reflectively from the generated code, and R8 cannot see those references.
-keepclassmembers class com.fourj.iptv.data.remote.xtream.** {
    *** Companion;
}
-keepclasseswithmembers class com.fourj.iptv.data.remote.xtream.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.fourj.iptv.**$$serializer { *; }

# Retrofit builds its service implementations from the interface's generic signatures.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**

# ExoPlayer / Media3 load extension renderers and extractors reflectively.
-dontwarn androidx.media3.**
