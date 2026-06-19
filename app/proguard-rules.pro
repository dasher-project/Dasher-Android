# DasherCore symbols used via JNI are loaded from libdasher.so; keep them.
-keep class at.dasher.android.** { *; }

# NativeBridge external declarations
-keepclassmembers class at.dasher.android.NativeBridge {
    public static native *** *(...);
}
