plugins {
    id("com.android.application") version "9.0.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
}

val localAppData = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
val customBuildRoot = System.getenv("MAKOION_ANDROID_BUILD_ROOT")?.takeIf { it.isNotBlank() }
val shouldUseExternalBuildRoot =
    rootDir.absolutePath.contains("OneDrive", ignoreCase = true) &&
        (customBuildRoot != null || localAppData != null)

if (shouldUseExternalBuildRoot) {
    // OneDrive frequently locks Gradle outputs on Windows, so keep build artifacts outside the synced repo.
    val externalBuildRoot = customBuildRoot ?: "$localAppData\\Makoion\\android-gradle-build"
    layout.buildDirectory.set(file(externalBuildRoot).resolve(rootProject.name))
    subprojects {
        layout.buildDirectory.set(rootProject.layout.buildDirectory.dir(name))
    }
}
