# Named print and filament presets serialize SlicerSettings by stable backing-field name.
-keepclassmembers class com.tomppi.enderslicer.model.SlicerSettings {
    <fields>;
}
