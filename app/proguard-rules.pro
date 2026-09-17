# TeleprompterPro release shrink rules.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
# CameraX is accessed partly via reflection / Camera2 interop keys.
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**
-keep class com.teleprompterpro.app.** { *; }
