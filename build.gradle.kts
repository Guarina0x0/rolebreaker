import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.0"
    id("com.gradleup.shadow") version "9.0.0-beta12"
}

group = "com.authzmatrix"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    // Burp provides the Montoya API at runtime — compile only, do not bundle it.
    compileOnly("net.portswigger.burp.extensions:montoya-api:2025.5")

    // Tiny JSON lib to parse JWT claims. Bundled into the fat-jar.
    implementation("org.json:json:20250517")

    // Tests (pure-logic core).
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Montoya types are referenced by some main classes; present at test runtime (not instantiated).
    testImplementation("net.portswigger.burp.extensions:montoya-api:2025.5")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    compilerOptions {
        // Burp runs on Java 17+; target 17 for maximum compatibility.
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.shadowJar {
    // Produce a clean name: build/libs/authz-matrix-<version>.jar
    archiveClassifier.set("")
}

// `gradle build` should produce the loadable fat-jar.
tasks.build {
    dependsOn(tasks.shadowJar)
}
