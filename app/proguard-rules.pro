# Keep ML Kit stable under aggressive release optimization.
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# JNI/native bridges must keep the Java entry points expected by native code.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# sherpa-onnx, Vosk and JNA use native/JNI bindings. Keep their public bridge classes.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**
-dontwarn org.vosk.**
-dontwarn com.sun.jna.**

# Android entry point class names are referenced from AndroidManifest.xml.
# Their implementation methods can still be optimized/obfuscated where safe.
-keepnames class com.zhou.floatingtranslator.FloatingTranslatorApp
-keepnames class com.zhou.floatingtranslator.*Activity
-keepnames class com.zhou.floatingtranslator.*Service

# Do not leave original source file names in release stack traces/APK metadata.
-renamesourcefileattribute SourceFile
