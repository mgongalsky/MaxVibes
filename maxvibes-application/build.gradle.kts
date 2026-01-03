plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":maxvibes-domain"))
    implementation(project(":maxvibes-shared"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("io.mockk:mockk:1.13.8")
}