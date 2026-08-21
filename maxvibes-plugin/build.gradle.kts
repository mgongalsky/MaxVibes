plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij")
}

dependencies {
    implementation(project(":maxvibes-domain"))
    implementation(project(":maxvibes-application"))
    implementation(project(":maxvibes-adapter-psi"))
    implementation(project(":maxvibes-adapter-psi-python"))
    implementation(project(":maxvibes-shared"))

    implementation("org.commonmark:commonmark:0.29.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.29.0") // LLM любят таблицы

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

        // The IDE-bundled coroutines-javaagent is built against kotlinx-coroutines 1.6.4
        // (platform 2023.1), while the test classpath carries 1.7+ (mockk, app modules).
        // The agent crashes with NoSuchMethodError in AgentPremain before any test runs.
        // Tests don't need the coroutine debug agent, so strip it from jvmArgs and from
        // argument providers while keeping all other provider-supplied args intact.
        doFirst {
            jvmArgs = jvmArgs.orEmpty().filterNot { it.contains("coroutines-javaagent") }
            val keptProviderArgs = jvmArgumentProviders
                .flatMap { it.asArguments() }
                .filterNot { it.contains("coroutines-javaagent") }
            jvmArgumentProviders.clear()
            jvmArgumentProviders.add(
                org.gradle.process.CommandLineArgumentProvider { keptProviderArgs }
            )
        }
    }

    buildSearchableOptions {
        enabled = false
    }

    patchPluginXml {
        version.set("1.2.12")
        sinceBuild.set("231")
        untilBuild.set("262.*")
    }

    register<org.jetbrains.intellij.tasks.RunIdeTask>("runIdePyCharm") {
        // gradle-intellij-plugin 1.x cannot boot IDEs with the 2024.2+ distribution layout —
        // it fails with a bare "Index: 1, Size: 1". Until the migration to IntelliJ Platform
        // Gradle Plugin 2.x, this task only actually launches a 2023.x–2024.1 PyCharm;
        // point PYCHARM_PATH at such an install to use it. To test the plugin in a modern
        // PyCharm, install the buildPlugin zip via "Install Plugin from Disk" instead.
        val pyCharmPath: String =
            System.getenv("PYCHARM_PATH")
                ?: when {
                    org.gradle.internal.os.OperatingSystem.current().isMacOsX ->
                        "/Applications/PyCharm CE.app/Contents"

                    org.gradle.internal.os.OperatingSystem.current().isWindows ->
                        "C:/Program Files/JetBrains/PyCharm Community Edition 2025.2"

                    else -> "/opt/pycharm-community"
                }
        ideDir.set(file(pyCharmPath))
    }

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