plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0" apply false
    id("org.jetbrains.intellij") version "1.17.4" apply false
    id("com.github.johnrengelman.shadow") version "8.1.1" apply false  // Добавлено
}

allprojects {
    group = "com.maxvibes"
    version = "1.0-SNAPSHOT"

    repositories {
        mavenCentral()
        maven("https://packages.jetbrains.team/maven/p/grazi/grazie-platform-public")
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "java")

    configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            jvmTarget = "17"
        }
    }

    tasks.withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}