plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0" apply false
    id("org.jetbrains.intellij") version "1.17.4" apply false
    // Shadow plugin больше не нужен!
}

allprojects {
    group = "com.maxvibes"
    version = "1.2.11"

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

        testLogging {
            events(
                org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED,
                org.gradle.api.tasks.testing.logging.TestLogEvent.SKIPPED
            )
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT
        }

        // Per-module summary: "module: SUCCESS — N tests, N passed, N failed, N skipped".
        // project.name is captured at configuration time — touching Task.project inside
        // the closure at execution time breaks the configuration cache.
        val moduleName = project.name
        afterSuite(KotlinClosure2({ desc: TestDescriptor, result: TestResult ->
            if (desc.parent == null) {
                println("$moduleName: ${result.resultType} — ${result.testCount} tests, ${result.successfulTestCount} passed, ${result.failedTestCount} failed, ${result.skippedTestCount} skipped")
            }
        }))
    }

    // gradle-intellij-plugin 1.x: instrumentCode ищет macOS-layout ('<jdk>/Packages')
    // на Windows-JDK и валит buildPlugin после clean. GUI Forms в проекте нет,
    // инструментация не нужна — выключаем до миграции на IPGP 2.x
    // (docs/TODOs/migrate-to-intellij-platform-gradle-plugin-2.md).
    plugins.withId("org.jetbrains.intellij") {
        extensions.configure<org.jetbrains.intellij.IntelliJPluginExtension> {
            instrumentCode.set(false)
        }
    }
}
