plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij")
}

dependencies {
    implementation(project(":maxvibes-domain"))
    implementation(project(":maxvibes-application"))
    implementation(project(":maxvibes-adapter-psi"))
    implementation(project(":maxvibes-shared"))

    // Use shadow JAR from adapter-llm
    implementation(project(path = ":maxvibes-adapter-llm"))

    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk:1.13.10")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
}

intellij {
    version.set("2023.1.5")
    type.set("IC")
    plugins.set(listOf("com.intellij.java", "org.jetbrains.kotlin"))
}

tasks {
    test {
        useJUnitPlatform()
    }

    buildSearchableOptions {
        enabled = false
    }

    patchPluginXml {
        version.set("1.1.7")
        sinceBuild.set("231")
        untilBuild.set("253.*")
    }

    // Run plugin inside Android Studio instead of IDEA
    // Adjust the path via ANDROID_STUDIO_PATH env var, or edit the defaults below:
    //   macOS:   /Applications/Android Studio.app/Contents
    //   Windows: C:/Program Files/Android Studio
    //   Linux:   /opt/android-studio
    register<org.jetbrains.intellij.tasks.RunIdeTask>("runIdeAndroidStudio") {
        val androidStudioPath: String =
            System.getenv("ANDROID_STUDIO_PATH")
                ?: when {
                    org.gradle.internal.os.OperatingSystem.current().isMacOsX ->
                        "/Applications/Android Studio.app/Contents"

                    org.gradle.internal.os.OperatingSystem.current().isWindows ->
                        "C:/Program Files/Android Studio"

                    else -> "/opt/android-studio"
                }
        ideDir.set(file(androidStudioPath))
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}
