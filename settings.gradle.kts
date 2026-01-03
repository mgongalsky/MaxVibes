pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
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