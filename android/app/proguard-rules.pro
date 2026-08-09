# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Capacitor plugins are discovered via @CapacitorPlugin annotation reflection.
# Without these rules, R8 strips SharePackagePlugin in release builds and the
# JS layer gets "Plugin SharePackage not implemented" at runtime.
-keep @com.getcapacitor.annotation.CapacitorPlugin class * { *; }
-keepclassmembers class * { @com.getcapacitor.PluginMethod *; }
-keep class run.krolik.oxpulse.** { *; }

# AndroidX FileProvider used by SharePackagePlugin to produce scoped URIs.
-keep class androidx.core.content.FileProvider { *; }

# Phase 2: Belt-and-braces Service keep. Current run.krolik.oxpulse.** catch covers this,
# but explicit rule is safer for future package moves (AC #12).
-keep public class * extends android.app.Service
