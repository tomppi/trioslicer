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
        mavenCentral()
        // usb-serial-for-android is published through JitPack. It talks to USB
        // serial chips in userspace, which is the only option on this device: the
        // phone's kernel has CONFIG_USB_SERIAL unset, so no /dev/ttyUSB can exist.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "EnderSlicer"
include(":app")
