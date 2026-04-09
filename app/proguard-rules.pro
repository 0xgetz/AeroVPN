# ============================================
# AeroVPN - Aggressive ProGuard/R8 Rules
# Optimized for < 15 MB APK Size
# ============================================

# ============================================
# 1. KEEP RULES - Android Framework & Kotlin
# ============================================

# Android Framework - Keep essential classes
-keep class android.** { *; }
-keep interface android.** { *; }
-dontwarn android.**

# Kotlin - Keep reflection-heavy classes
-keep class kotlin.** { *; }
-keep interface kotlin.** { *; }
-keep class kotlin.reflect.** { *; }
-keep class kotlinx.** { *; }
-keep interface kotlinx.** { *; }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.CoroutineContextImpl { *; }
-keepclassmembers class kotlinx.coroutines.JobSupport { *; }

# Kotlin Serialization - Keep for reflection
-keepattributes *Annotation*, InnerClasses, Signature
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class kotlinx.serialization.** { *; }
-dontwarn kotlinx.serialization.**

# Kotlinx JSON Serialization
-keepattributes Signature
-keepattributes *Annotation*
-keep class kotlinx.serialization.json.** { *; }
-dontwarn kotlinx.serialization.json.**

# ============================================
# 2. KEEP RULES - Jetpack Compose
# ============================================

# Compose - Keep compiler-generated classes
-keep class androidx.compose.** { *; }
-keep interface androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Compose Runtime
-keep class androidx.compose.runtime.** { *; }
-keepclassmembers class androidx.compose.runtime.** { *; }

# Compose UI
-keep class androidx.compose.ui.** { *; }
-keepclassmembers class androidx.compose.ui.** { *; }

# Compose Material 3
-keep class androidx.compose.material3.** { *; }
-keepclassmembers class androidx.compose.material3.** { *; }

# Compose Navigation
-keep class androidx.navigation.compose.** { *; }
-keepclassmembers class androidx.navigation.compose.** { *; }

# Keep Compose lambda classes
-keepclassmembers class * extends androidx.compose.runtime.Composer { *; }

# ============================================
# 3. KEEP RULES - WireGuard Library
# ============================================

# WireGuard Android library
-keep class com.wireguard.** { *; }
-keep interface com.wireguard.** { *; }
-keep class com.wireguard.android.** { *; }
-dontwarn com.wireguard.**

# WireGuard native library loader
-keep class com.wireguard.android.backend.WireGuardGo { *; }

# ============================================
# 4. KEEP RULES - V2Ray/Xray Libraries
# ============================================

# V2Ray Core - Keep all protocol classes
-keep class com.v2ray.** { *; }
-keep interface com.v2ray.** { *; }
-keep class com.v2ray.ang.** { *; }
-dontwarn com.v2ray.**

# Xray Core
-keep class libv2ray.** { *; }
-keep class libXray.** { *; }
-dontwarn libv2ray.**

# Protobuf - Used by V2Ray
-keep class com.google.protobuf.** { *; }
-keep interface com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# gRPC - Used by V2Ray
-keep class io.grpc.** { *; }
-keep interface io.grpc.** { *; }
-dontwarn io.grpc.**

# OkHttp - Used by V2Ray
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keepclassmembers class okhttp3.internal.publicsuffix.PublicSuffixDatabase { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# ============================================
# 5. KEEP RULES - SSH & Shadowsocks
# ============================================

# JSch - SSH library
-keep class com.jcraft.jsch.** { *; }
-keep interface com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# Shadowsocks
-keep class com.github.shadowsocks.** { *; }
-keep interface com.github.shadowsocks.** { *; }
-dontwarn com.github.shadowsocks.**

# ============================================
# 6. KEEP RULES - Android Lifecycle & Architecture
# ============================================

# AndroidX Core
-keep class androidx.core.** { *; }
-keep interface androidx.core.** { *; }

# Lifecycle
-keep class androidx.lifecycle.** { *; }
-keep interface androidx.lifecycle.** { *; }

# ViewModel
-keep class androidx.lifecycle.ViewModel { *; }
-keepclassmembers class * extends androidx.lifecycle.ViewModel { *; }

# Room Database (if used)
-keep class androidx.room.** { *; }
-keep @androidx.room.Entity class *
-keepclassmembers class * extends androidx.room.Entity { *; }

# WorkManager
-keep class androidx.work.** { *; }
-keep interface androidx.work.** { *; }

# ============================================
# 7. KEEP RULES - JNI & Native Methods
# ============================================

# Keep all JNI native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep classes with native methods
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep serializable classes
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Keep Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Keep annotations
-keep class * extends java.lang.annotation.Annotation { *; }
-keep @interface *

# ============================================
# 8. REMOVE LOGGING - Release Builds
# ============================================

# Remove all Log.d() calls
-assumenosideeffects class android.util.Log {
    public static int d(java.lang.String, java.lang.String);
    public static int d(java.lang.String, java.lang.String, java.lang.Throwable);
}

# Remove all Log.v() calls (verbose)
-assumenosideeffects class android.util.Log {
    public static int v(java.lang.String, java.lang.String);
    public static int v(java.lang.String, java.lang.String, java.lang.Throwable);
}

# Remove all Log.i() calls (info) - Optional, comment out if you need release logs
-assumenosideeffects class android.util.Log {
    public static int i(java.lang.String, java.lang.String);
    public static int i(java.lang.String, java.lang.String, java.lang.Throwable);
}

# Remove Timber logs (if used)
-assumenosideeffects class timber.log.Timber {
    public static void d(java.lang.String, java.lang.Object...);
    public static void v(java.lang.String, java.lang.Object...);
    public static void i(java.lang.String, java.lang.Object...);
    public static void w(java.lang.String, java.lang.Object...);
    public static void e(java.lang.String, java.lang.Object...);
    public static void wtf(java.lang.String, java.lang.Object...);
}

# Remove custom Logger class logs (if you have one)
-assumenosideeffects class com.aerovpn.util.Logger {
    public static void d(java.lang.String, java.lang.String);
    public static void v(java.lang.String, java.lang.String);
    public static void i(java.lang.String, java.lang.String);
}

# ============================================
# 9. REMOVE DEBUG CODE
# ============================================

# Remove strict mode calls (debug only)
-assumenosideeffects class android.os.StrictMode {
    public static void enableDeathOnSql();
    public static void enableDeathOnNetwork();
    public static void enableDeathOnCleartextNetwork();
    public static void enableDeathOnNonSdkUsage();
    public static android.os.StrictMode$ThreadPolicy allowAll();
}

# Remove BuildConfig debug checks
-assumenosideeffects class com.aerovpn.BuildConfig {
    public static final boolean DEBUG;
}

# Remove assertion calls
-assumenosideeffects class java.lang.AssertionError {
    public <init>();
}

# ============================================
# 10. OPTIMIZE STRING POOLING
# ============================================

# Merge duplicate strings
-mergeinterface @androidx.annotation.IntVal
-mergeinterface @androidx.annotation.StringVal

# Use unique member names (shorter names = smaller APK)
-useuniqueclassmembernames

# Allow access modification for optimization
-allowaccessmodification

# Optimize container classes
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkParameterIsNotNull(java.lang.Object, java.lang.String);
    public static void checkNotNull(java.lang.Object);
    public static void checkNotNull(java.lang.Object, java.lang.String);
}

# ============================================
# 11. REMOVE UNUSED RESOURCES
# ============================================

# This is handled by R8 with: android.enableResourceOptimization=true
# But we can help by keeping only used resources

# Keep resources used by libraries
-keepresourcenamebumpers

# Keep notification icons (required for VPN service)
-keepresourcename bumpers
-keepresourcename notification_icon
-keepresourcename ic_stat_notify
-keepresourcename ic_launcher
-keepresourcename ic_launcher_foreground
-keepresourcename ic_launcher_background
-keepresourcename ic_vpn
-keepresourcename ic_connected
-keepresourcename ic_connecting
-keepresourcename ic_disconnected

# Keep country flag resources (if used)
-keepresourcename flag_*
-keepresourcename ic_flag_*

# Keep Material icons used in the app
-keepresourcename ic_baseline_*
-keepresourcename ic_outline_*

# Keep string resources referenced by code
-keepresourcename app_name
-keepresourcename notification_channel_name
-keepresourcename notification_channel_description

# ============================================
# 12. AGGRESSIVE OPTIMIZATIONS
# ============================================

# Remove code that throwsexceptions but is never caught
-optimizations code/removes/unused/throws

# Remove null checks on non-null parameters
-optimizations code/simplification/parameters

# Inline short methods
-optimizations method/inlining/short

# Merge interfaces where possible
-optimizations class/morphing/interface

# Remove enum class overhead (if you have enums)
-optimizations class/morphing/enum

# Optimize arithmetic operations
-optimizations arithmetic/*

# Optimize string operations
-optimizations string/*

# Remove dead code
-optimizations code/removal/advanced

# Simplify control flow
-optimizations controlflow/simplify

# Optimize branch targets
-optimizations controlflow/merge

# Remove redundant stores and loads
-optimizations field/removal/writeonly

# Inline constant fields
-optimizations field/propagation/value

# ============================================
# 13. DON'T WARN - Suppress warnings for libraries
# ============================================

# Suppress warnings for libraries with missing classes
-dontwarn com.wireguard.**
-dontwarn com.v2ray.**
-dontwarn libv2ray.**
-dontwarn libXray.**
-dontwarn io.grpc.**
-dontwarn com.jcraft.jsch.**
-dontwarn com.github.shadowsocks.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn su.java.lib.**

# ============================================
# 14. PREVENT OPTIMIZATION OF CRITICAL CLASSES
# ============================================

# Keep VPN service class (must not be optimized away)
-keep class com.aerovpn.service.AeroVpnService { *; }
-keep class com.aerovpn.service.** { *; }

# Keep protocol handlers
-keep class com.aerovpn.protocol.** { *; }

# Keep receiver classes (broadcast receivers must not be obfuscated)
-keep class com.aerovpn.receiver.** { *; }

# Keep all classes in com.aerovpn package
-keep class com.aerovpn.** { *; }

# ============================================
# 15. OBFUSCATION SETTINGS
# ============================================

# Enable obfuscation (reduces size)
-obfuscation

# Use short obfuscation names (4 chars)
-obfuscationdictionary words.txt

# Use one-character class names for release
-useuniqueclassmembernames

# Print mapping file for debugging
-printmapping mapping.txt

# Print seed file for reproducible builds
-printconfiguration proguard_seed.txt

# Print unused classes for analysis
-printusage unused.txt

# ============================================
# 16. SPECIFIC LIBRARY OPTIMIZATIONS
# ============================================

# Coil Image Loading (if used)
-keep class com.bumptech.glide.** { *; }
-keep interface com.bumptech.glide.** { *; }
-dontwarn com.bumptech.glide.**

# Accompanist (Compose utilities)
-keep class com.google.accompanist.** { *; }
-keep interface com.google.accompanist.** { *; }
-dontwarn com.google.accompanist.**

# DataStore (settings storage)
-keep class androidx.datastore.** { *; }
-keep interface androidx.datastore.** { *; }

# Hilt/Dagger (dependency injection, if used)
-keep class dagger.** { *; }
-keep interface dagger.** { *; }
-keep class * extends dagger.android.DaggerApplication { *; }
-dontwarn dagger.**

# Ktor (if used for networking)
-keep class io.ktor.** { *; }
-keep interface io.ktor.** { *; }
-dontwarn io.ktor.**

# ============================================
# 17. FINAL OPTIMIZATIONS
# ============================================

# Remove debug annotations
-assumenosideeffects class android.annotation.SuppressLint { *; }
-assumenosideeffects class android.annotation.TargetApi { *; }

# Suppress R8 optimization warnings for aggressive shrinking
-dontoptimize

# Enable full return type inference
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkNotNullExpressionValue(java.lang.Object, java.lang.String);
}

# ============================================
# NOTES FOR BUILDING
# ============================================
# 1. Build with: ./gradlew assembleRelease
# 2. Enable R8 full mode in gradle.properties
# 3. Check mapping.txt for obfuscation issues
# 4. Test release build thoroughly before deployment
# 5. Monitor unused.txt to identify dead code
# ============================================
