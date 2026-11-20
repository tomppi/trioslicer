# Nothing in this module is reached reflectively except the components named in its manifest,
# which the manifest keep rules already cover. The JNI entry points are resolved from native
# code by name, so the trainer bridge and the renderer bridge must keep their signatures.
-keepclasseswithmembers,allowoptimization class com.tomppi.splatproto.BrushTrainer {
    native <methods>;
}
-keepclasseswithmembers,allowoptimization class com.tomppi.splatproto.NativeCore {
    native <methods>;
}
-keepclasseswithmembers,allowoptimization class com.tomppi.splatproto.BrushRenderer {
    native <methods>;
}
