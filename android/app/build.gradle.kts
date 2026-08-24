plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.airgrab"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.airgrab"
        // 31 covers Android 12 and later. The iQOO Neo 10 runs Android 16.
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // MediaPipe ships a 21MB x86 library for emulators only; no
            // shipping Android phone uses it. arm64 covers everything modern,
            // armeabi-v7a the few remaining 32-bit devices.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    androidResources {
        // MediaPipe memory-maps the model straight out of the APK. A
        // compressed asset cannot be mapped, and the failure is a load error
        // at first use rather than at build time.
        noCompress += "task"
    }

    packaging {
        resources {
            // Netty ships a dozen jars that each carry these metadata files,
            // and the Android packager refuses to guess which copy wins.
            // None of them affect behaviour.
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/io.netty.versions.properties"
            excludes += "/META-INF/*.kotlin_module"
            // Jansi arrives transitively and carries native terminal libraries
            // for Windows, macOS and desktop Linux. A .dll inside an APK is
            // pure dead weight.
            excludes += "/org/fusesource/**"
            excludes += "/META-INF/services/org.fusesource.**"
        }
    }
}

dependencies {
    implementation(project(":core")) {
        // Netty's native transports are compiled for desktop Linux and macOS,
        // not Android, so they are megabytes of shared objects that can never
        // load here. Netty falls back to NIO, which is what Android uses
        // anyway.
        exclude(group = "io.netty", module = "netty-transport-native-epoll")
        exclude(group = "io.netty", module = "netty-transport-native-kqueue")
        exclude(group = "io.netty", module = "netty-transport-classes-epoll")
        exclude(group = "io.netty", module = "netty-transport-classes-kqueue")
    }
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // CameraX rather than Camera2 directly. Camera2 would mean handling
    // device-specific orientation, buffer formats and lifecycle by hand on
    // every handset; CameraX does that and hands over a frame.
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")

    // The same gesture recognizer model the desktop uses, so a fist looks
    // like a fist on both ends.
    implementation("com.google.mediapipe:tasks-vision:0.10.20")
    testImplementation(kotlin("test"))
}
