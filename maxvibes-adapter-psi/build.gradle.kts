plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij")
}

dependencies {
    implementation(project(":maxvibes-domain"))
    implementation(project(":maxvibes-application"))
    implementation(project(":maxvibes-shared"))

    testImplementation(kotlin("test"))
}

intellij {
    version.set("2023.1.5")
    type.set("IC")
    plugins.set(listOf("org.jetbrains.kotlin"))
}

tasks {
    buildSearchableOptions {
        enabled = false
    }

    // Не собираем как отдельный плагин
    buildPlugin {
        enabled = false
    }

    runIde {
        enabled = false
    }
}