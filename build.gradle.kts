import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.0"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

group = "octopusmerge"
version = "0.2.4"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation(kotlin("stdlib"))
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // The platform test framework still loads JUnit 4 classes at runtime.
    testRuntimeOnly("junit:junit:4.13.2")

    intellijPlatform {
        local(providers.gradleProperty("localIdePath"))
        bundledPlugin("com.intellij.platform.vcs")
        bundledPlugin("Git4Idea")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.JUnit5)
        pluginVerifier()
    }
}

kotlin {
    jvmToolchain(25)
}

ktlint {
    version = "1.8.0"
}

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = false
    pluginVerification {
        ides {
            local(file(providers.gradleProperty("localIdePath").get()))
        }
    }
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "263"
            untilBuild = provider { null }
        }
    }
}

tasks.processResources {
    // The plugin reports its version in issue links without asking the platform's internal plugin APIs.
    val pluginVersion = project.version.toString()
    inputs.property("pluginVersion", pluginVersion)
    filesMatching("octopusmerge/version.properties") { expand("version" to pluginVersion) }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
