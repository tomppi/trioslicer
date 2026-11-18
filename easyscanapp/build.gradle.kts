plugins {
    id("com.android.library")
}

android {
    packaging {
        jniLibs {
            pickFirsts += listOf("**/libc++_shared.so")
            excludes += listOf("**/libopencv_java4.so")
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
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_shared") } }
    }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
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
