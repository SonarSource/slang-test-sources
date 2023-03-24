/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

plugins {
    id("org.gradle.kotlin.kotlin-dsl") version "3.2.4"
    kotlin("plugin.serialization") version "1.7.20"
}

val buildSnapshotTrain = properties["build_snapshot_train"]?.toString()?.toBoolean() == true

repositories {
    maven("https://plugins.gradle.org/m2")
    maven("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/dev")

    if (buildSnapshotTrain) {
        mavenLocal()
    }
}

sourceSets.main {
}

dependencies {
    implementation(kotlin("gradle-plugin", "1.7.20"))
    implementation(kotlin("serialization", "1.7.20"))

    val ktlint_version = libs.versions.ktlint.version.get()
    implementation("org.jmailen.gradle:kotlinter-gradle:$ktlint_version")

    implementation("io.ktor:ktor-server-default-headers:2.0.2")
    implementation("io.ktor:ktor-server-netty:2.0.2")
    implementation("io.ktor:ktor-server-cio:2.0.2")
    implementation("io.ktor:ktor-server-jetty:2.0.2")
    implementation("io.ktor:ktor-server-websockets:2.0.2")
    implementation("io.ktor:ktor-server-auth:2.0.2")
    implementation("io.ktor:ktor-server-caching-headers:2.0.2")
    implementation("io.ktor:ktor-server-conditional-headers:2.0.2")
    implementation("io.ktor:ktor-server-compression:2.0.2")
    implementation("io.ktor:ktor-server-content-negotiation:2.0.2")
    implementation("io.ktor:ktor-serialization-kotlinx:2.0.2")
    implementation("io.ktor:ktor-network-tls-certificates:2.0.2")

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.logback.classic)
}

kotlin {
    jvmToolchain {
        check(this is JavaToolchainSpec)
        languageVersion.set(JavaLanguageVersion.of(8))
    }
}
