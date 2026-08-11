# ============================================
# AeroVPN - ProGuard/R8 Rules (release)
# ============================================
# Rewritten: the previous file used non-existent options
# (-mergeinterface, -keepresourcenamebumpers, -obfuscation, invented
# -optimizations filter names, -obfuscationdictionary words.txt which was
# missing) that made R8 fail before shrinking even started.

# ============================================
# App classes
# ============================================
# Keep everything in the app package: VPN service, receivers referenced from
# the manifest, protocol handlers touched via reflection, and the Gson-
# serialized config classes (field names must survive for persist/restore).
-keep class com.aerovpn.** { *; }
-keep interface com.aerovpn.** { *; }

# Serializable members (configs are passed via Intent extras)
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ============================================
# Kotlin / Coroutines
# ============================================
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlin.**
-dontwarn kotlinx.**

# ============================================
# JSch (SSH) — loads crypto engines via reflection
# ============================================
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# ============================================
# OkHttp / Okio / Gson
# ============================================
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn com.google.gson.**
-keep class com.google.gson.** { *; }

# ============================================
# WireGuard / V2Ray native integration (reflection via Class.forName)
# WireGuardProtocol calls com.wireguard.android.backend.WireGuardGo
# (wgTurnOn/wgTurnOff/wgGetSocketV4/wgGetSocketV6) by name via reflection,
# so those classes and their native methods MUST survive R8 renaming/removal.
# ============================================
-keep class com.wireguard.android.** { *; }
-keepclasseswithmembers class com.wireguard.android.** {
    native <methods>;
}
-dontwarn com.wireguard.**
-dontwarn com.v2ray.**
-dontwarn libv2ray.**

# ============================================
# Logging: strip d/v/i in release, keep w/e
# ============================================
-assumenosideeffects class android.util.Log {
    public static int d(java.lang.String, java.lang.String);
    public static int d(java.lang.String, java.lang.String, java.lang.Throwable);
    public static int v(java.lang.String, java.lang.String);
    public static int v(java.lang.String, java.lang.String, java.lang.Throwable);
    public static int i(java.lang.String, java.lang.String);
    public static int i(java.lang.String, java.lang.String, java.lang.Throwable);
}

# Mapping file for crash deobfuscation
-printmapping mapping.txt
