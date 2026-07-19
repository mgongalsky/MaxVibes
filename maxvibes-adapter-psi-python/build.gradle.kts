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
  type.set("PC")
  plugins.set(listOf("PythonCore"))
  downloadSources.set(false)
  instrumentCode.set(false)
}

tasks {
  buildSearchableOptions { enabled = false }
  buildPlugin { enabled = false }
  runIde { enabled = false }
  patchPluginXml { enabled = false }
}
