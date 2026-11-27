# Nothing in this module is reached reflectively except the components named in its manifest,
# which the manifest keep rules already cover. The JNI entry points and callbacks are resolved
# from native code by name, so the Parallax core and Cross3DGS JNI bridges must keep their
# signatures.
-keepclasseswithmembers,allowoptimization class com.tomppi.splatproto.NativeCore {
    native <methods>;
}
-keepclasseswithmembers,allowoptimization class org.unclearness.cross3dgs.NativeBridge {
    native <methods>;
}
-keep class org.unclearness.cross3dgs.NativeBridge$Listener {
    *;
}
-keep class * implements org.unclearness.cross3dgs.NativeBridge$Listener {
    void onLog(java.lang.String);
    void onProgress(int, int, float, double);
    void onFinished(int, java.lang.String, java.lang.String);
}
