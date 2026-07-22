# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep camera2 classes
-keep class android.hardware.camera2.** { *; }
