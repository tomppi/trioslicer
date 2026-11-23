plugins {
    id("com.android.library")
}

// The OpenVINS/Ceres native build needs its full third-party tree. When that
// tree is unavailable, `-PusePrebuiltVio=true` packages the matching prebuilt
// libs already in src/main/jniLibs instead of running CMake.
val usePrebuiltVio = providers.gradleProperty("usePrebuiltVio").orNull == "true"

android {
    packaging {
        jniLibs {
            pickFirsts += listOf("**/libc++_shared.so")
            if (!usePrebuiltVio) excludes += listOf("**/libopencv_java4.so")
        }
    }

    namespace = "com.rawmechatronics.easyscanner"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }

    ndkVersion = "28.2.13676358"

    defaultConfig {
        ndk { abiFilters += "arm64-v8a" }
        if (!usePrebuiltVio) {
            externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_shared") } }
        }
    }

    if (!usePrebuiltVio) {
        externalNativeBuild {
            cmake { path = file("src/main/cpp/CMakeLists.txt") }
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("com.google.ar:core:1.49.0")
    implementation("org.opencv:opencv:4.14.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.preference:preference-ktx:1.2.1")
}