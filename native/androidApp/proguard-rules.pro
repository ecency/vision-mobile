# R8 rules for the release build. Libraries ship most of their own consumer rules (Compose, Coil, OkHttp,
# Ktor, kotlinx-coroutines); this file covers what they do not. After adding a dependency that uses
# reflection, ServiceLoader or JNI, run the release launch check on a minified build (native/docs/release-builds.md).

# secp256k1-kmp: Secp256k1.get() finds the Android JNI loader by class name, and the native library calls
# back into the JNI classes by name.
-keep class fr.acinq.secp256k1.** { *; }

# Ktor: engines and plugins can be found through ServiceLoader; debug-only classes are referenced optionally.
-keep class io.ktor.client.engine.okhttp.OkHttpEngineContainer { *; }
-dontwarn io.ktor.**
-dontwarn java.lang.management.**

# kotlinx-serialization: the app parses JSON trees (JsonElement), no generated serializers today. Keep
# companion serializers in case @Serializable classes are added.
-keepclassmembers class **$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepattributes *Annotation*, InnerClasses

# OkHttp optional TLS providers.
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Readable stack traces in Play vitals; Play deobfuscates with the uploaded mapping.txt.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
