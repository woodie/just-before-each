plugins {
    kotlin("jvm") version "2.2.10"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("com.netpress.kotidy") version "0.1.0"
    id("com.vanniktech.maven.publish") version "0.37.0"
}

group = "com.netpress"
version = "0.1.2"

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

// Maven Central (Central Publishing Portal) release config -- see issue #2.
// GPG signing credentials come from ~/.gradle/gradle.properties locally, or
// ORG_GRADLE_PROJECT_-prefixed env vars in CI; never checked in here.
// publishToMavenCentral() with no args is manual publish (deployment lands
// in "Validated" state on the Portal for a manual click), matching the
// issue's call for the first release -- switch to
// publishToMavenCentral(true) later if automatic release is wanted. Mirrors
// humane-kotlin's own bootstrap: composite-build-only at v0.1.0/v0.1.1,
// Maven Central once the DSL was proven out (see docs/COWORK.md "Packaging").
mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates("com.netpress", "kwick", version.toString())

    pom {
        name.set("kwick")
        description.set(
            "Better, cleaner tests for Kotest's DescribeSpec, starting with justBeforeEach.",
        )
        url.set("https://github.com/woodie/kwick")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://github.com/woodie/kwick/blob/main/LICENSE")
            }
        }
        developers {
            developer {
                id.set("woodie")
                name.set("John Woodell")
                email.set("woodie@netpress.com")
            }
        }
        scm {
            url.set("https://github.com/woodie/kwick")
            connection.set("scm:git:https://github.com/woodie/kwick.git")
            developerConnection.set("scm:git:ssh://git@github.com/woodie/kwick.git")
        }
    }
}
