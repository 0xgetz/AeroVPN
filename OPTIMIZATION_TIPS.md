# AeroVPN - APK Optimization Tips

## Target: < 15 MB APK Size

This guide provides comprehensive strategies to minimize APK size for AeroVPN while maintaining full functionality.

---

## Table of Contents

1. [Build Configuration](#build-configuration)
2. [ProGuard/R8 Optimization](#proguadr8-optimization)
3. [Resource Optimization](#resource-optimization)
4. [Dependency Management](#dependency-management)
5. [Code Optimization](#code-optimization)
6. [Native Libraries](#native-libraries)
7. [Asset Management](#asset-management)
8. [Testing & Verification](#testing--verification)

---

## Build Configuration

### Enable R8 Full Mode

In `gradle.properties`:

```properties
android.enableR8.fullMode=true
android.enableR8=true
android.enableResourceOptimization=true
android.enableAppCompileTimeRClass=true
```

### Release Build Settings

In `app/build.gradle`:

```groovy
android {
    buildTypes {
        release {
            minifyEnabled true
            shrinkResources true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
            
            // Enable resource shrinking
            resConfigs "en"
            resConfigs "mdpi", "hdpi", "xhdpi"
            
            // Compress resources
            crunchPngs true
        }
    }
    
    // Split APKs by ABI (reduces size by ~60%)
    splits {
        abi {
            enable true
            reset()
            include 'armeabi-v7a', 'arm64-v8a'
            universalApk false
        }
    }
}
```

### Use App Bundle

Instead of APK, use Android App Bundle (AAB):

```bash
./gradlew bundleRelease
```

Benefits:
- Google Play dynamically serves only needed resources
- Reduces download size by 30-50%
- Automatic ABI and density splitting

---

## ProGuard/R8 Optimization

### Aggressive Logging Removal

Add to `proguard-rules.pro`:

```proguard
# Remove all debug logs
-assumenosideeffects class android.util.Log {
    public static int d(java.lang.String, java.lang.String);
    public static int v(java.lang.String, java.lang.String);
    public static int i(java.lang.String, java.lang.String);
    public static int w(java.lang.String, java.lang.String);
}
```

### Use Short Obfuscation Names

```proguard
# Use 4-character names
-obfuscationdictionary words.txt
-useuniqueclassmembernames

# Enable aggressive optimizations
-optimizations code/removal/advanced
-optimizations method/inlining/short
-optimizations string/shrinking/utf8
```

### Analyze R8 Output

Check these files after build:
- `mapping.txt` - Obfuscation mapping
- `unused.txt` - Unused code that was removed
- `resources.txt` - Resources that were shrunk

---

## Resource Optimization

### Remove Unused Resources

Use Android Studio's Remove Unused Resources feature:

```
Build > Analyze APK > Unused Resources
```

Or run:

```bash
./gradlew shrinkReleaseRes
```

### Limit Supported Languages

Only include necessary translations:

```groovy
resConfigs "en"
// Add other languages only if needed
// resConfigs "id", "ar", "fa"
```

### Optimize Images

1. **Use WebP Format**

Convert PNG/JPG to WebP (50-80% smaller):

```bash
# Install webp tools
brew install webp

# Convert with quality 80%
cwebp input.png -q 80 -o output.webp
```

2. **Use Vector Drawables**

Replace PNG icons with vector drawables:

```xml
<!-- res/drawable/ic_vpn.xml -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2z"/>
</vector>
```

3. **Adaptive Icons**

Use adaptive icon format (single vector for all densities):

```xml
<!-- res/mipmap-anydpi-v26/ic_launcher.xml -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
</adaptive-icon>
```

### Limit Screen Densities

Keep only essential densities:

```groovy
resConfigs "mdpi", "hdpi", "xhdpi", "xxhdpi"
```

Most devices use xhdpi/xxhdpi, so mdpi/hdpi can often be removed.

---

## Dependency Management

### Analyze Dependencies

Check dependency sizes:

```bash
./gradlew app:dependencies
```

### Use Lightweight Alternatives

| Heavy Library | Lightweight Alternative | Size Savings |
|---------------|------------------------|--------------|
| Glide (1.2 MB) | Coil (200 KB) | ~1 MB |
| ButterKnife (150 KB) | View Binding (0 KB) | 150 KB |
| Gson (90 KB) | Kotlinx Serialization (85 KB) | Minimal |
| Apache Commons (600 KB) | Kotlin stdlib (400 KB) | 200 KB |

### Use Dynamic Feature Modules

For large features (not critical for VPN):

```groovy
// settings.gradle
include ':app', ':features:advanced-tools'

// app/build.gradle
dependencies {
    implementation project(':features:advanced-tools')
}
```

### Remove Unused Transitive Dependencies

Exclude unused transitive dependencies:

```groovy
implementation('com.example:library:1.0') {
    exclude group: 'com.google.guava', module: 'guava'
    exclude group: 'commons-io', module: 'commons-io'
}
```

---

## Code Optimization

### Remove Dead Code

Delete unused classes, methods, and fields. Enable R8 analysis:

```groovy
android {
    buildTypes {
        release {
            minifyEnabled true
            // R8 will detect and remove unused code
        }
    }
}
```

### Inline Small Functions

Use `@Inline` annotation for small utility functions:

```kotlin
@JvmSynthetic
inline fun <T> List<T>.fastFirst(predicate: (T) -> Boolean): T {
    return first(predicate)
}
```

### Use Primitive Types

Avoid autoboxing:

```kotlin
// Bad - uses Integer object
val count: Int? = null

// Good - uses primitive int
val count: Int = 0
```

### Remove Reflection

Replace reflection with compile-time solutions:

```kotlin
// Bad - uses reflection
val className = "com.aerovpn.MyClass"
val clazz = Class.forName(className)

// Good - direct reference
val clazz = MyClass::class.java
```

---

## Native Libraries

### Bundle Native Libraries Separately

WireGuard and V2Ray have large native libraries. Split by ABI:

```groovy
android {
    splits {
        abi {
            enable true
            reset()
            include 'armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64'
            universalApk false
        }
    }
}
```

### Use APK Splits

Create separate APKs for different ABIs:

```groovy
android {
    splits {
        abi {
            enable true
            reset()
            include 'arm64-v8a'  // Most modern devices
            // universalApk true  // Enable for universal APK
        }
    }
}
```

Result:
- arm64-v8a APK: ~3-4 MB
- armeabi-v7a APK: ~2-3 MB
- Universal APK: ~10-12 MB (includes all ABIs)

### Prefer Kotlin/Java Over Native

Use native libraries only when necessary:

```kotlin
// Use pure Kotlin implementation when possible
// Instead of C++ native libraries
```

---

## Asset Management

### Use Programmatic Drawing

Draw simple assets programmatically:

```kotlin
@Composable
fun ConnectButton() {
    Box(
        modifier = Modifier
            .size(200.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        Text("Connect")
    }
}
```

Instead of using PNG assets.

### Minimize Country Flags

Flags are large. Use emoji or single sprite sheet:

```kotlin
// Use emoji (no assets needed)
Text("\uD83C\uDDEE\uD83C\uDDE9")  // Indonesia flag emoji

// Or use sprite sheet (single image for all flags)
```

### Compress Config Files

If including sample configs, compress them:

```kotlin
// Use GZIP for embedded configs
val configInputStream = GZIPInputStream(assets.open("sample_config.gz"))
```

---

## Testing & Verification

### Check APK Size

After building release APK:

```bash
# Check raw size
ls -lh app/build/outputs/apk/release/

# Analyze APK
./gradlew app:analyzeReleaseApk

# Use Android Studio
Build > Analyze APK > Select APK
```

### Verify Functionality

⚠️ **CRITICAL**: After aggressive optimization:

1. **Test all protocols** - WireGuard, V2Ray, SSH, Shadowsocks
2. **Test all screens** - Home, ServerList, Tools, Config, Settings
3. **Test advanced tools** - IP Hunter, Payload Generator, DNS Checker
4. **Test notifications** - VPN service requires foreground notification
5. **Test crash handling** - Obfuscation can obscure crash reports

### Monitor R8 Warnings

Check for R8 warnings that might break functionality:

```bash
./gradlew assembleRelease 2>&1 | grep -i "warning\|error"
```

Fix important warnings in `proguard-rules.pro`.

### Use Release Builds for Testing

Always test release builds (not debug):

```bash
./gradlew assembleRelease
adb install app/build/outputs/apk/release/app-release.apk
```

---

## Advanced Techniques

### Enable R8 Experimental Features

```properties
# gradle.properties
android.enableR8.fullMode=true
android.experimental.enableNewResourceShrinker=true
android.experimental.enableNewResourceShrinker.preciseShrinking=true
```

### Optimize Strings

```proguard
-mergeinterface
-useuniqueclassmembernames

# Enable UTF-8 string optimization
-optimizations string/shrinking/utf8
```

### Remove AndroidX Bloat

Only include needed AndroidX libraries:

```groovy
// Instead of:
// implementation 'androidx.core:core-ktx:1.12.0'

// Use only needed:
implementation 'androidx.core:core:1.12.0'
```

### Optimize Compose

```groovy
// Exclude unused Compose libraries
implementation 'androidx.compose.ui:ui:1.5.0'  // Only core UI
implementation 'androidx.compose.material3:material3:1.1.0'  // Only Material 3

// Exclude if not using:
// implementation 'androidx.compose.material:material-icons-extended'  // 500 KB
```

---

## Quick Size Checklist

| Optimization | Expected Savings | Difficulty |
|--------------|------------------|------------|
| Enable R8 Full Mode | 20-30% | Easy |
| Resource shrinking | 10-20% | Easy |
| ProGuard rules | 10-15% | Medium |
| Remove unused resources | 5-10% | Easy |
| WebP images | 30-50% (images) | Medium |
| Split by ABI | 50-60% (per APK) | Easy |
| Vector drawables | 80-90% (icons) | Medium |
| Limit languages | 5-15% | Easy |
| Limit densities | 10-20% | Easy |
| Lightweight dependencies | 10-30% | Medium |

---

## Size Targets

| APK Type | Target Size | Notes |
|----------|-------------|-------|
| Universal APK | < 15 MB | All ABIs, all densities |
| Split APK (arm64) | < 8 MB | Modern devices |
| Split APK (armv7) | < 7 MB | Older devices |
| AAB (Play Store) | < 12 MB | Download size ~5-8 MB |

---

## Common Issues & Solutions

### Crash After ProGuard

**Problem**: App crashes in release but works in debug

**Solution**: Add keep rules for crashing classes:

```proguard
-keep class com.aerovpn.service.AeroVpnService { *; }
-keepclassmembers class com.aerovpn.service.AeroVpnService { *; }
```

### Missing Resources

**Problem**: Resource not found at runtime

**Solution**: Keep dynamically accessed resources:

```proguard
-keepresourcename my_dynamic_resource
```

### Reflection Fails

**Problem**: Kotlin serialization/Coroutines fail

**Solution**: Keep reflection-heavy classes:

```proguard
-keep class kotlinx.** { *; }
-keep class kotlin.reflect.** { *; }
```

### Library Warnings

**Problem**: R8 shows warnings for libraries

**Solution**: Suppress warnings:

```proguard
-dontwarn com.wireguard.**
-dontwarn com.v2ray.**
```

---

## Build Commands

### Build Release APK

```bash
./gradlew clean assembleRelease
```

### Build App Bundle

```bash
./gradlew clean bundleRelease
```

### Analyze APK Size

```bash
./gradlew app:analyzeReleaseApk

# Or use android-studio
# Build > Analyze APK > Select file
```

### Check Unused Resources

```bash
./gradlew shrinkReleaseRes
```

### Print R8 Mapping

```bash
# Already included in proguard-rules.pro
# Check: app/build/outputs/mapping/release/mapping.txt
```

---

## References

- [R8 Optimization Guide](https://developer.android.com/studio/build/shrink-code)
- [Android App Bundle](https://developer.android.com/guide/app-bundle)
- [ProGuard Manual](https://www.guardsquare.com/manual)
- [APK Analyzer](https://developer.android.com/studio/build/apk-analyzer)

---

**Last Updated**: 2026-04-09
**Version**: 1.0
**Target**: AeroVPN APK < 15 MB
