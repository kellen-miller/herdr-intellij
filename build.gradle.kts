import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.1.10"
    kotlin("plugin.serialization") version "2.1.10"
    id("org.jetbrains.intellij.platform") version "2.18.1"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

group = "dev.herdr"
version = providers.gradleProperty("pluginVersion").orElse("0.1.0-SNAPSHOT").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")

    intellijPlatform {
        intellijIdeaCommunity("2025.1.7.2")
        bundledModule("intellij.platform.vcs.impl")
        pluginVerifier()
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = true

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
        }
    }

    pluginVerification {
        ides {
            current()
            latest()
        }
    }
}

tasks {
    test {
        useJUnitPlatform()
    }

    check {
        dependsOn("ktlintCheck")
    }
}
