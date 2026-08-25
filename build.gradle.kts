import org.jetbrains.changelog.Changelog.OutputType.HTML
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel
plugins {
  id("org.jetbrains.kotlin.jvm")
  id("org.jetbrains.intellij.platform")
  id("org.jetbrains.changelog")
}

changelog {
  groups.empty()
  repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
}

intellijPlatform {
  pluginConfiguration {
    version = providers.gradleProperty("version")
    changeNotes = provider {
      changelog.renderItem(
        (changelog.getOrNull(providers.gradleProperty("version").get())
         ?: changelog.getUnreleased())
          .withHeader(false)
          .withEmptySections(false),
        HTML,
      )
    }
  }

  pluginVerification {
    freeArgs = listOf("-mute", "TemplateWordInPluginName")
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

tasks {
  buildSearchableOptions {
    enabled = false
  }
}
