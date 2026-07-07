plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.teachmint.sharex"
version = "1.0.0"

repositories {
    mavenCentral()
}

// F-009: Align Ktor version with main project (3.4.1) to ensure security patches are applied
val ktorVersion = "3.4.1"
val serializationVersion = "1.8.0"

dependencies {
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-forwarded-header:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
    implementation("ch.qos.logback:logback-classic:1.5.6")
}

application {
    mainClass.set("com.teachmint.sharex.signaling.ServerKt")
}

kotlin {
    jvmToolchain(17)
}
