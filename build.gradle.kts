import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    // WebStorm 2026.2 runs on Java 25, so compile and test against 25.
    jvmToolchain(25)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        local(providers.gradleProperty("localIdePath"))
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        name = "Test Status"
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
}

// `./gradlew runIde -PdemoProject=/path` opens that project in the sandbox IDE and skips first-run dialogs.
tasks.runIde {
    providers.gradleProperty("demoProject").orNull?.let { args(it) }
    jvmArgs(
        "-Djb.privacy.policy.text=<!--999.999-->",
        "-Djb.consents.confirmation.enabled=false",
        "-Didea.trust.all.projects=true",
        "-Dide.show.tips.on.startup.default.value=false",
        "-Dide.no.platform.update=true",
    )
}
