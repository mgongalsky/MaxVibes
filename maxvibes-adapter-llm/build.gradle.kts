plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

dependencies {
    // Project modules
    implementation(project(":maxvibes-domain"))
    implementation(project(":maxvibes-application"))
    implementation(project(":maxvibes-shared"))

    // Koog - AI agents framework
    implementation("ai.koog:koog-agents:0.6.0")
    implementation("ai.koog:prompt-executor-llms-all:0.6.0")
    implementation("ai.koog:prompt-executor-openai-client:0.6.0")
    implementation("ai.koog:prompt-executor-anthropic-client:0.6.0")
    implementation("ai.koog:prompt-executor-ollama-client:0.6.0")

    // Kotlinx
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
}

tasks.test {
    useJUnitPlatform()
}

// Shadow JAR - relocate Ktor to avoid IntelliJ conflicts
tasks.shadowJar {
    archiveClassifier.set("")

    // Relocate ALL Ktor packages
    relocate("io.ktor", "com.maxvibes.shadow.ktor")

    // Also relocate transitive dependencies that might conflict
    relocate("io.netty", "com.maxvibes.shadow.netty")
    relocate("okhttp3", "com.maxvibes.shadow.okhttp3")
    relocate("okio", "com.maxvibes.shadow.okio")

    mergeServiceFiles()

    // Exclude signatures that break the JAR
    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")
}

// Replace standard JAR with shadow JAR
tasks.jar {
    enabled = false
}

artifacts {
    archives(tasks.shadowJar)
}

// Make other tasks depend on shadowJar
tasks.build {
    dependsOn(tasks.shadowJar)
}