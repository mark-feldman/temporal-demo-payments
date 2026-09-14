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

/**
 * Integration tests live in their own source set so `test` stays fast and Spring-free.
 * It compiles against the unit suite's output too, so the fakes and fixtures in
 * `src/test/kotlin/.../support` are shared rather than duplicated.
 */
sourceSets {
    create("integrationTest") {
        compileClasspath += sourceSets["main"].output + sourceSets["test"].output
        runtimeClasspath += sourceSets["main"].output + sourceSets["test"].output
    }
}

configurations["integrationTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

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
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation(kotlin("test"))
    // Gradle 9 no longer puts the launcher on the test runtime classpath implicitly.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The machine default is JDK 26. Pin here so a bare ./gradlew from an IDE run
// configuration cannot silently compile against it.
kotlin { jvmToolchain(21) }

/**
 * ScenarioStore's default path is `.scenario-store.json` RELATIVE TO CWD, and a Gradle Test
 * task runs in the project directory -- which is exactly where the demo's real store lives.
 * Without this every test run would rewrite live demo state. The property is a seam the
 * production code already reads.
 */
fun Test.useDisposableScenarioStore(name: String) {
    systemProperty(
        "demo.scenarioFile",
        layout.buildDirectory.file("tmp/$name-scenarios.json").get().asFile.absolutePath,
    )
}

tasks.test {
    useJUnitPlatform()
    useDisposableScenarioStore("test")
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

val integrationTest by tasks.registering(Test::class) {
    description = "Spring Boot tests against the in-memory Temporal test server."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnitPlatform()
    useDisposableScenarioStore("integrationTest")
    shouldRunAfter(tasks.test)
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.check { dependsOn(integrationTest) }
