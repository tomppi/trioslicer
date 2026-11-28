pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google's Maven Central mirror avoids intermittent 429 rate-limits on shared CI runners.
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        mavenCentral()
        // usb-serial-for-android is published through JitPack. It talks to USB
        // serial chips in userspace, which is the only option on this device: the
        // phone's kernel has CONFIG_USB_SERIAL unset, so no /dev/ttyUSB can exist.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "EnderSlicer"
include(":app")
include(":easyscanapp")
// On-device Gaussian splatting & DA3 depth module (Cross3DGS Vulkan, Parallax SfM, U2Net/DA3 NPU).
include(":splatproto")
