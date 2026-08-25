import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel

plugins {
  id("org.jetbrains.kotlin.jvm")
  id("org.jetbrains.intellij.platform")
  id("org.jetbrains.changelog")
}

intellijPlatform {
  pluginVerification {
    failureLevel = listOf(
      FailureLevel.COMPATIBILITY_PROBLEMS,
      FailureLevel.INVALID_PLUGIN,
    )
  }
}

dependencies {
  testImplementation("junit:junit:4.13.2")
  
  // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
  intellijPlatform {
    intellijIdea("2026.2.1")
    testFramework(TestFrameworkType.Platform)
  }
}
