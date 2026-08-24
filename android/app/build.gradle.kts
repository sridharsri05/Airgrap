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

    packaging {
        resources {
            // Netty ships a dozen jars that each carry these metadata files,
            // and the Android packager refuses to guess which copy wins.
            // None of them affect behaviour.
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/io.netty.versions.properties"
            excludes += "/META-INF/*.kotlin_module"
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
    testImplementation(kotlin("test"))
}
