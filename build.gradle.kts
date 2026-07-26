plugins {
    kotlin("jvm") version "2.2.10"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("com.netpress.kotidy") version "0.1.0"
}

group = "com.netpress"
version = "0.1.0"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Kotest is a real (non-test-only) dependency here, not just a test
    // fixture -- justBeforeEach/JustBeforeEachExtension are built directly
    // against ContainerScope/TestCase/TestCaseExtension/Descriptor, so
    // consumers need Kotest's real API types to compile against ours.
    // See docs/COWORK.md.
    implementation("io.kotest:kotest-framework-api:5.9.1")
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Same nested describe/context/it tree rendering every sibling Kotlin repo
// in this account uses -- see kotidy's own docs/COWORK.md for what it does
// and why it's a Gradle plugin rather than a library.
kotidy {
    style = "fs"
}

// Maven Central (Central Publishing Portal) publishing setup deliberately
// not added yet -- humane-kotlin's own bootstrap sequence (docs/COWORK.md,
// v0.1.0 -> v0.1.1) added com.vanniktech.maven.publish only once the real
// library existed and worked, not at scaffold time. Add it the same way,
// once the core DSL is built and proven. See this repo's own docs/COWORK.md
// "Packaging" section.
