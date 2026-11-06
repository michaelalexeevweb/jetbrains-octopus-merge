import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.0"
}

group = "octopusmerge"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation(kotlin("stdlib"))
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // IDE tests are JUnit 3 style (BasePlatformTestCase) and need JUnit 4 on the classpath.
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.13.4")

    intellijPlatform {
        local(providers.gradleProperty("localIdePath"))
        bundledPlugin("com.intellij.platform.vcs")
        bundledPlugin("Git4Idea")
        testFramework(TestFrameworkType.Platform)
        pluginVerifier()
    }
}

kotlin {
    jvmToolchain(25)
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

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
