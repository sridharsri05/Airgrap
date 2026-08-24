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
    }
}

rootProject.name = "AirGrab"

// `core` is deliberately a plain Kotlin/JVM module rather than an Android one.
// The protocol, the gesture state machine and the correlation logic contain no
// Android APIs, so keeping them out of the Android plugin means they compile
// and test in seconds on any JVM — no SDK, no emulator, no device. That is
// also what lets them be checked against the Python implementation's shared
// vectors as an ordinary unit test.
include(":core")
include(":app")
