plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.spring") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
    id("org.springframework.boot") version "3.5.10"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.example"
version = "0.1.0"

repositories { mavenCentral() }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")

    implementation("io.temporal:temporal-spring-boot-starter:1.38.0")
    implementation("io.temporal:temporal-kotlin:1.38.0")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.temporal:temporal-testing:1.38.0")
    testImplementation(kotlin("test"))
}

// The machine default is JDK 26. Pin here so a bare ./gradlew from an IDE run
// configuration cannot silently compile against it.
kotlin { jvmToolchain(21) }

tasks.test { useJUnitPlatform() }
