// Splat Proto's capture -> SfM -> Cross3DGS Vulkan training engine, embedded in TrioSlicer.
//
// This is a library module: it has no launcher of its own, and the app enters it through
// More -> Gaussian splatting. The UI (PipelineActivity) is the phone workflow: Camera2 +
// SuperVINS capture, the NPU object mask, Parallax reconstruction and Cross3DGS training, with
// the trained .ply written into the app's SplatLibrary folder so the existing splat viewer lists it.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.tomppi.splatproto"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        ndk {
            // Same ABI as the app's engines; the vendored Parallax core and libcross3dgs_android
            // are arm64-only.
            abiFilters += "arm64-v8a"
        }
        // OpenCV's Prefab package (libopencv_java4) uses the shared C++ runtime.
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    buildTypes {
        release {
            // The app's release build owns shrinking; nothing in this module is reachable
            // reflectively except the manifest components.
            consumerProguardFiles("proguard-splatproto.pro")
        }
    }

    buildFeatures {
        compose = true
        prefab = true
    }

    ndkVersion = "28.2.13676358"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            excludes += listOf(
                "lib/arm64-v8a/libQnnGpu.so",
                "lib/arm64-v8a/libQnnHtpV68Skel.so",
                "lib/arm64-v8a/libQnnHtpV68Stub.so",
                "lib/arm64-v8a/libQnnHtpV69Skel.so",
                "lib/arm64-v8a/libQnnHtpV69Stub.so",
                "lib/arm64-v8a/libQnnHtpV75Skel.so",
                "lib/arm64-v8a/libQnnHtpV75Stub.so",
                "lib/arm64-v8a/libQnnHtpV79Skel.so",
                "lib/arm64-v8a/libQnnHtpV79Stub.so",
                "lib/arm64-v8a/libQnnHtpV81Skel.so",
                "lib/arm64-v8a/libQnnHtpV81Stub.so",
            )
        }
        resources {
            excludes += listOf("META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

// Verify the Cross3DGS Vulkan JNI library and the U2Net NPU masker assets are staged before
// packaging an APK whose Gaussian splatting menu opens.
val splatProtoPayloadFiles = listOf(
    "src/main/jniLibs/arm64-v8a/libcross3dgs_android.so",
    "src/main/assets/masker/bin/qnn-net-run",
    "src/main/assets/masker/models/u2net.bin",
    "src/main/assets/masker/models/htp_ext.json",
    "src/main/assets/masker/skel/libQnnHtpV73Skel.so",
    "src/main/assets/masker/libQnnHtp.so",
    "src/main/assets/masker/SOURCE.md",
    "src/main/assets/masker/SHA256SUMS",
)
val splatProtoModuleDir = projectDir

val verifySplatProtoPayload by tasks.registering {
    group = "verification"
    description = "Checks the Splat Proto payload is staged (scripts/fetch-splatproto-payload-android.sh)"
    doLast {
        val missing = splatProtoPayloadFiles.filterNot { relative ->
            File(splatProtoModuleDir, relative).let { it.isFile && it.length() > 0L }
        }
        check(missing.isEmpty()) {
            "The Splat Proto payload is not staged: " + missing.joinToString(", ") +
                " - Run scripts/fetch-splatproto-payload-android.sh, or rebuild it with " +
                "scripts/build-splatproto-payload-android.sh --package."
        }
    }
}

tasks.matching {
    it.name in setOf("preBuild", "mergeDebugAssets", "mergeReleaseAssets")
}.configureEach { dependsOn(verifySplatProtoPayload) }

kotlin {
    jvmToolchain(17)
}

dependencies {
    // The scanner module owns the VIO front-end (SuperVINS: SuperPoint + LightGlue + VINS-Fusion)
    // and its calibration profile; the splat capture reuses the same pose source.
    implementation(project(":easyscanapp"))
    // Vendored Parallax SfM core links against OpenCV's Prefab package.
    implementation("org.opencv:opencv:4.14.0")

    // Camera2 capture; factory intrinsics via the Camera2 interop.
    implementation("androidx.camera:camera-core:1.6.2")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")

    implementation(platform("androidx.compose:compose-bom:2026.06.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.webkit:webkit:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
