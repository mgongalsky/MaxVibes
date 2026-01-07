pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.github.johnrengelman.shadow") version "8.1.1"
    }
}

// settings.gradle.kts
rootProject.name = "MaxVibes"

// Включаем все модули
include(
    ":maxvibes-domain",
    ":maxvibes-application",
    ":maxvibes-adapter-psi",
    ":maxvibes-adapter-llm",
    ":maxvibes-shared",
    ":maxvibes-plugin"
)