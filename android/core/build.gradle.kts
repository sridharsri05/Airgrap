plugins {
    `java-library`
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.0.21"
}

kotlin {
    jvmToolchain(17)
}

val ktorVersion = "3.0.3"

dependencies {
    // kotlinx.serialization rather than org.json: Android ships its own
    // org.json, so depending on the Maven artifact would collide at runtime,
    // and Android's version is stubbed out in JVM unit tests.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Coroutines: the transfer layer suspends rather than blocks, because a
    // file stream that blocked its caller would freeze the gesture loop on
    // the phone for the whole duration of the transfer.
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // These are `api`, not `implementation`: Node exposes Ktor and coroutine
    // types in its own signatures, so a consumer that cannot see them cannot
    // call it.
    //
    // Netty for the SERVER, CIO for the client.
    //
    // CIO would be the natural choice on Android, being pure Kotlin with no
    // native code, but its server engine throws UnsupportedOperationException
    // on sslConnector: it cannot do TLS. The phone has to RECEIVE as well as
    // send, so a server without TLS is not an option, and Netty is the engine
    // that supports it. TlsSpikeTest is what established this.
    api("io.ktor:ktor-server-core:$ktorVersion")
    api("io.ktor:ktor-server-netty:$ktorVersion")
    api("io.ktor:ktor-server-websockets:$ktorVersion")
    api("io.ktor:ktor-client-core:$ktorVersion")
    api("io.ktor:ktor-client-cio:$ktorVersion")
    api("io.ktor:ktor-client-websockets:$ktorVersion")

    // Self-signed certificate generation. Ktor's own helper works on a plain
    // JVM and on Android alike, which avoids shipping BouncyCastle in the APK
    // purely to mint one keypair at first launch.
    api("io.ktor:ktor-network-tls-certificates:$ktorVersion")

    // BouncyCastle SHIPS. Two earlier revisions of this comment claimed it
    // was test-only; a real handset proved otherwise.
    //
    // ktor-network-tls-certificates writes an X.509 certificate whose
    // signature AlgorithmIdentifier carries a parameters field. That is
    // correct for RSA and forbidden for ECDSA, and this protocol is P-256.
    // Java's certificate parser is lenient and accepts it, so every Kotlin
    // test passed and phone-to-phone worked. Python's parser is strict and
    // rejects the certificate outright, which surfaced as `auth_failed
    // bad_signature` on the first real pairing with the desktop — a message
    // pointing at the signature when the certificate was never readable.
    //
    // On Android this does not collide with the platform's own copy, which is
    // repackaged under com.android.org.bouncycastle.
    api("org.bouncycastle:bcpkix-jdk18on:1.79")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        showStandardStreams = false
    }
}
