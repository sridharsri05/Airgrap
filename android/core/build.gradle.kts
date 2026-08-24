plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.0.21"
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // kotlinx.serialization rather than org.json: Android ships its own
    // org.json, so depending on the Maven artifact would collide at runtime,
    // and Android's version is stubbed out in JVM unit tests.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // BouncyCastle is TEST-ONLY on purpose. Certificate *verification* uses
    // plain JCA, which exists on every platform. Certificate *generation* is
    // the only platform-specific part: Android generates a self-signed cert
    // through the hardware-backed Keystore, so shipping BouncyCastle in the
    // APK would add megabytes and collide with Android's bundled copy.
    testImplementation("org.bouncycastle:bcpkix-jdk18on:1.79")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        showStandardStreams = false
    }
}
