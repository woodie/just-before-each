pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Lets Gradle auto-download a matching JDK when jvmToolchain(17) (see
    // build.gradle.kts) can't find one already installed, instead of failing
    // outright -- matches humane-kotlin's/next-caltrain-kotlin's own
    // settings.gradle.kts.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "kwick"
