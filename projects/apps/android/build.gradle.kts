plugins {
    id("com.android.application") version "9.0.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
}

val localAppData = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
val customBuildRoot = System.getenv("MAKOION_ANDROID_BUILD_ROOT")?.takeIf { it.isNotBlank() }
val shouldUseExternalBuildRoot =
    customBuildRoot != null ||
        (
            rootDir.absolutePath.contains("OneDrive", ignoreCase = true) &&
                localAppData != null
        )

if (shouldUseExternalBuildRoot) {
    // Allow an explicit override anywhere, while still defaulting OneDrive worktrees to a safer external build root.
    val externalBuildRoot = customBuildRoot ?: "$localAppData\\Makoion\\android-gradle-build"
    layout.buildDirectory.set(file(externalBuildRoot).resolve(rootProject.name))
    subprojects {
        layout.buildDirectory.set(rootProject.layout.buildDirectory.dir(name))
    }
}
