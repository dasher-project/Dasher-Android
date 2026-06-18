# DasherCore symbols used via JNI are loaded from libdasher.so; keep them.
-keep class org.dasherproject.android.** { *; }

# NativeBridge external declarations
-keepclassmembers class org.dasherproject.android.NativeBridge {
    public static native *** *(...);
}
