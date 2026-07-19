pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.github.johnrengelman.shadow") version "8.1.1"
    }
}

rootProject.name = "MaxVibes"

include(
    ":maxvibes-domain",
    ":maxvibes-application",
    ":maxvibes-adapter-psi",
    ":maxvibes-adapter-psi-python",
    ":maxvibes-adapter-llm",
    ":maxvibes-shared",
    ":maxvibes-plugin"
)
