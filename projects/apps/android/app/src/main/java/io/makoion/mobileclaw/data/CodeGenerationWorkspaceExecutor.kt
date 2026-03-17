package io.makoion.mobileclaw.data

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CodeGenerationWorkspaceArtifact(
    val workspacePath: String,
    val entryFilePath: String?,
    val generatedFileCount: Int,
    val generatorLabel: String,
)

internal data class CodeGenerationScaffoldBlueprint(
    val workspaceDirectoryName: String,
    val entryFileRelativePath: String?,
    val generatorLabel: String,
    val files: List<CodeGenerationScaffoldFile>,
)

internal data class CodeGenerationScaffoldFile(
    val relativePath: String,
    val contents: String,
)

interface CodeGenerationWorkspaceExecutor {
    suspend fun generateScaffold(
        prompt: String,
        plan: CodeGenerationProjectPlan,
    ): CodeGenerationWorkspaceArtifact
}

class LocalCodeGenerationWorkspaceExecutor(
    private val context: Context,
    private val auditTrailRepository: AuditTrailRepository,
    private val nowProvider: () -> Long = System::currentTimeMillis,
    private val suffixProvider: () -> String = {
        UUID.randomUUID().toString().replace("-", "").take(workspaceSuffixLength)
    },
) : CodeGenerationWorkspaceExecutor {
    override suspend fun generateScaffold(
        prompt: String,
        plan: CodeGenerationProjectPlan,
    ): CodeGenerationWorkspaceArtifact = withContext(Dispatchers.IO) {
        val workspaceRoot = resolveCodeGenerationWorkspaceRoot(
            externalDocumentsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
            filesDir = context.filesDir,
        )
        val projectSlug = buildCodeGenerationWorkspaceSlug(
            prompt = prompt,
            kind = plan.kind,
        )
        val workspaceDirectoryName = buildCodeGenerationWorkspaceDirectoryName(
            projectSlug = projectSlug,
            createdAtEpochMillis = nowProvider(),
            suffix = suffixProvider(),
        )
        val blueprint = buildCodeGenerationScaffoldBlueprint(
            prompt = prompt,
            plan = plan,
            projectSlug = projectSlug,
            workspaceDirectoryName = workspaceDirectoryName,
        )
        val artifact = materializeCodeGenerationScaffold(
            workspaceRoot = workspaceRoot,
            blueprint = blueprint,
        )
        auditTrailRepository.logAction(
            action = "codegen.workspace",
            result = "generated",
            details = buildString {
                append("Generated ")
                append(plan.targetLabel)
                append(" scaffold with ")
                append(artifact.generatedFileCount)
                append(" file(s) at ")
                append(artifact.workspacePath)
                artifact.entryFilePath?.let { entryFilePath ->
                    append(" (entry: ")
                    append(entryFilePath)
                    append(')')
                }
            },
        )
        artifact
    }

    private companion object {
        private const val workspaceSuffixLength = 6
    }
}

internal fun resolveCodeGenerationWorkspaceRoot(
    externalDocumentsDir: File?,
    filesDir: File,
): File {
    return externalDocumentsDir?.resolve("makoion-workspaces/codegen")
        ?: filesDir.resolve("agent-workspaces/codegen")
}

internal fun buildCodeGenerationWorkspaceSlug(
    prompt: String,
    kind: CodeGenerationProjectKind,
): String {
    val filteredTokens = prompt
        .lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .split(Regex("\\s+"))
        .filter(String::isNotBlank)
        .filterNot { token -> token in workspaceSlugStopWords }
        .take(maxSlugTokenCount)
        .map { token -> token.take(maxSlugTokenLength) }
    val slug = filteredTokens.joinToString(separator = "-")
    return slug.ifBlank { kind.slugPrefix }
}

internal fun buildCodeGenerationWorkspaceDirectoryName(
    projectSlug: String,
    createdAtEpochMillis: Long,
    suffix: String,
): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(createdAtEpochMillis))
    val sanitizedSuffix = suffix.lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), "")
        .take(6)
        .ifBlank { "draft" }
    return "$stamp-$projectSlug-$sanitizedSuffix"
}

internal fun buildCodeGenerationScaffoldBlueprint(
    prompt: String,
    plan: CodeGenerationProjectPlan,
    projectSlug: String,
    workspaceDirectoryName: String,
): CodeGenerationScaffoldBlueprint {
    val projectName = buildCodeGenerationProjectName(prompt, plan)
    val commonFiles = listOf(
        CodeGenerationScaffoldFile(
            relativePath = "README.md",
            contents = buildReadmeFile(
                projectName = projectName,
                prompt = prompt,
                plan = plan,
            ),
        ),
        CodeGenerationScaffoldFile(
            relativePath = "REQUEST.md",
            contents = buildRequestFile(
                prompt = prompt,
                plan = plan,
            ),
        ),
    )
    val specificFiles = when (plan.kind) {
        CodeGenerationProjectKind.AndroidApp -> androidAppScaffoldFiles(projectName, projectSlug)
        CodeGenerationProjectKind.AutomationWorkflow -> automationScaffoldFiles(projectName)
        CodeGenerationProjectKind.ScriptOrTool -> scriptScaffoldFiles(projectName)
        CodeGenerationProjectKind.GenericCodeProject -> genericProjectScaffoldFiles(projectName)
    }
    val entryFileRelativePath = when (plan.kind) {
        CodeGenerationProjectKind.AndroidApp -> {
            val packagePath = buildAndroidPackageName(projectSlug).replace('.', '/')
            "app/src/main/java/$packagePath/MainActivity.kt"
        }
        CodeGenerationProjectKind.AutomationWorkflow -> "workflow/automation.yaml"
        CodeGenerationProjectKind.ScriptOrTool -> "src/main.py"
        CodeGenerationProjectKind.GenericCodeProject -> "src/main.kt"
    }
    val generatorLabel = when (plan.kind) {
        CodeGenerationProjectKind.AndroidApp -> "Phone local Android starter"
        CodeGenerationProjectKind.AutomationWorkflow -> "Phone local automation starter"
        CodeGenerationProjectKind.ScriptOrTool -> "Phone local script starter"
        CodeGenerationProjectKind.GenericCodeProject -> "Phone local source starter"
    }
    return CodeGenerationScaffoldBlueprint(
        workspaceDirectoryName = workspaceDirectoryName,
        entryFileRelativePath = entryFileRelativePath,
        generatorLabel = generatorLabel,
        files = commonFiles + specificFiles,
    )
}

internal fun materializeCodeGenerationScaffold(
    workspaceRoot: File,
    blueprint: CodeGenerationScaffoldBlueprint,
): CodeGenerationWorkspaceArtifact {
    if (!workspaceRoot.exists() && !workspaceRoot.mkdirs()) {
        error("Could not create the code generation workspace root at ${workspaceRoot.absolutePath}.")
    }
    val workspaceDirectory = workspaceRoot.resolve(blueprint.workspaceDirectoryName)
    if (!workspaceDirectory.exists() && !workspaceDirectory.mkdirs()) {
        error("Could not create the code generation workspace at ${workspaceDirectory.absolutePath}.")
    }
    blueprint.files.forEach { fileDefinition ->
        val outputFile = workspaceDirectory.resolve(fileDefinition.relativePath)
        val parentDirectory = outputFile.parentFile
        if (parentDirectory != null && !parentDirectory.exists() && !parentDirectory.mkdirs()) {
            error("Could not create ${parentDirectory.absolutePath} for the generated scaffold.")
        }
        outputFile.writeText(fileDefinition.contents)
    }
    return CodeGenerationWorkspaceArtifact(
        workspacePath = workspaceDirectory.absolutePath,
        entryFilePath = blueprint.entryFileRelativePath?.let(workspaceDirectory::resolve)?.absolutePath,
        generatedFileCount = blueprint.files.size,
        generatorLabel = blueprint.generatorLabel,
    )
}

internal fun compactCodeGenerationPath(path: String): String {
    val normalizedSegments = path.replace('\\', '/')
        .split('/')
        .filter(String::isNotBlank)
    return if (normalizedSegments.size <= compactPathSegmentCount) {
        normalizedSegments.joinToString("/")
    } else {
        normalizedSegments.takeLast(compactPathSegmentCount).joinToString("/")
    }
}

private fun buildCodeGenerationProjectName(
    prompt: String,
    plan: CodeGenerationProjectPlan,
): String {
    return prompt
        .replace('\n', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(maxProjectNameLength)
        .ifBlank { plan.title }
}

private fun buildReadmeFile(
    projectName: String,
    prompt: String,
    plan: CodeGenerationProjectPlan,
): String {
    return """
        # $projectName

        Generated by the Makoion phone-local scaffold executor.

        - Target: ${plan.targetLabel}
        - Workspace mode: ${plan.workspaceLabel}
        - Output: ${plan.outputLabel}

        ## Request

        $prompt

        ## Next steps

        1. Review the generated starter files in this workspace.
        2. Replace placeholders with provider-backed generation and execution loops.
        3. Export or share the workspace after companion or delivery wiring lands.
    """.trimIndent() + "\n"
}

private fun buildRequestFile(
    prompt: String,
    plan: CodeGenerationProjectPlan,
): String {
    return """
        # Original request

        $prompt

        ## Planner notes

        - Target label: ${plan.targetLabel}
        - Workspace label: ${plan.workspaceLabel}
        - Output label: ${plan.outputLabel}
        - This draft was generated by a phone-local template executor, not by a full provider coding loop.
    """.trimIndent() + "\n"
}

private fun androidAppScaffoldFiles(
    projectName: String,
    projectSlug: String,
): List<CodeGenerationScaffoldFile> {
    val packageName = buildAndroidPackageName(projectSlug)
    val packagePath = packageName.replace('.', '/')
    return listOf(
        CodeGenerationScaffoldFile(
            relativePath = ".gitignore",
            contents = """
                .gradle/
                build/
                */build/
                local.properties
            """.trimIndent() + "\n",
        ),
        CodeGenerationScaffoldFile(
            relativePath = "settings.gradle.kts",
            contents = """
                pluginManagement {
                    repositories {
                        google()
                        mavenCentral()
                        gradlePluginPortal()
                    }
                }

                dependencyResolutionManagement {
                    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                    repositories {
                        google()
                        mavenCentral()
                    }
                }

                rootProject.name = "$projectName"
                include(":app")
            """.trimIndent() + "\n",
        ),
        CodeGenerationScaffoldFile(
            relativePath = "build.gradle.kts",
            contents = """
                plugins {
                    id("com.android.application") version "8.8.0" apply false
                    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
                }
            """.trimIndent() + "\n",
        ),
        CodeGenerationScaffoldFile(
            relativePath = "app/build.gradle.kts",
            contents = """
                plugins {
                    id("com.android.application")
                    id("org.jetbrains.kotlin.android")
                }

                android {
                    namespace = "$packageName"
                    compileSdk = 36

                    defaultConfig {
                        applicationId = "$packageName"
                        minSdk = 29
                        targetSdk = 36
                        versionCode = 1
                        versionName = "0.1.0"
                    }

                    buildTypes {
                        release {
                            isMinifyEnabled = false
                        }
                    }

                    compileOptions {
                        sourceCompatibility = JavaVersion.VERSION_17
                        targetCompatibility = JavaVersion.VERSION_17
                    }

                    kotlinOptions {
                        jvmTarget = "17"
                    }
                }

                dependencies {
                    implementation("androidx.core:core-ktx:1.17.0")
                    implementation("androidx.appcompat:appcompat:1.7.1")
                    implementation("com.google.android.material:material:1.13.0")
                }
            """.trimIndent() + "\n",
        ),
        CodeGenerationScaffoldFile(
            relativePath = "app/src/main/AndroidManifest.xml",
            contents = """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <application
                        android:allowBackup="true"
                        android:label="$projectName"
                        android:supportsRtl="true">
                        <activity
                            android:name=".MainActivity"
                            android:exported="true">
                            <intent-filter>
                                <action android:name="android.intent.action.MAIN" />
                                <category android:name="android.intent.category.LAUNCHER" />
                            </intent-filter>
                        </activity>
                    </application>
                </manifest>
            """.trimIndent() + "\n",
        ),
        CodeGenerationScaffoldFile(
            relativePath = "app/src/main/java/$packagePath/MainActivity.kt",
            contents = """
                package $packageName

                import android.os.Bundle
                import android.widget.TextView
                import androidx.appcompat.app.AppCompatActivity

                class MainActivity : AppCompatActivity() {
                    override fun onCreate(savedInstanceState: Bundle?) {
                        super.onCreate(savedInstanceState)
                        setContentView(
                            TextView(this).apply {
                                text = "$projectName starter generated by Makoion. Replace this placeholder with your real flow."
                                textSize = 20f
                                setPadding(48, 96, 48, 48)
                            },
                        )
                    }
                }
            """.trimIndent() + "\n",
        ),
        CodeGenerationScaffoldFile(
            relativePath = "app/src/main/res/values/strings.xml",
            contents = """
                <resources>
                    <string name="app_name">$projectName</string>
                </resources>
            """.trimIndent() + "\n",
        ),
    )
}

private fun automationScaffoldFiles(projectName: String): List<CodeGenerationScaffoldFile> {
    return listOf(
        CodeGenerationScaffoldFile(
            relativePath = "workflow/automation.yaml",
            contents = """
                name: "$projectName"
                trigger:
                  type: schedule
                  cadence: daily
                steps:
                  - id: collect_sources
                    uses: browser.research
                    input:
                      query: "Replace with the live query for this automation"
                  - id: summarize
                    uses: model.summarize
                    input:
                      format: bullet-brief
                  - id: deliver
                    uses: channel.delivery
                    input:
                      target: local_notification
            """.trimIndent() + "\n",
        ),
        CodeGenerationScaffoldFile(
            relativePath = "workflow/prompts/system.md",
            contents = """
                # Automation system brief

                - Keep outputs concise and actionable.
                - Attach source links and delivery status.
                - Respect approvals before mutating user resources.
            """.trimIndent() + "\n",
        ),
        CodeGenerationScaffoldFile(
            relativePath = "workflow/delivery/targets.json",
            contents = """
                {
                  "primary": "local_notification",
                  "fallback": "telegram_bot",
                  "notes": "Bind real channel credentials and policies before activating this workflow."
                }
            """.trimIndent() + "\n",
        ),
        CodeGenerationScaffoldFile(
            relativePath = "workflow/state/README.md",
            contents = """
                # Workflow state

                Persist checkpoints, summaries, and delivery receipts here once the automation runner is wired.
            """.trimIndent() + "\n",
        ),
    )
}

private fun scriptScaffoldFiles(projectName: String): List<CodeGenerationScaffoldFile> {
    return listOf(
        CodeGenerationScaffoldFile(
            relativePath = "src/main.py",
            contents = """
                import json
                from pathlib import Path

                CONFIG_PATH = Path(__file__).resolve().parent.parent / "config.example.json"

                def main() -> None:
                    config = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
                    print(f"{config['project_name']} starter generated by Makoion")
                    print("Replace this placeholder logic with the task-specific automation flow.")

                if __name__ == "__main__":
                    main()
            """.trimIndent() + "\n",
        ),
        CodeGenerationScaffoldFile(
            relativePath = "config.example.json",
            contents = """
                {
                  "project_name": "$projectName",
                  "input_path": "./input",
                  "output_path": "./output"
                }
            """.trimIndent() + "\n",
        ),
        CodeGenerationScaffoldFile(
            relativePath = "docs/runbook.md",
            contents = """
                # Runbook

                1. Copy `config.example.json` to a runtime config.
                2. Replace the placeholder business logic in `src/main.py`.
                3. Bind the script to Makoion delivery or automation hooks.
            """.trimIndent() + "\n",
        ),
    )
}

private fun genericProjectScaffoldFiles(projectName: String): List<CodeGenerationScaffoldFile> {
    return listOf(
        CodeGenerationScaffoldFile(
            relativePath = "src/main.kt",
            contents = """
                package io.makoion.generated

                fun main() {
                    println("$projectName starter generated by Makoion")
                    println("Replace this file with the real implementation plan and code.")
                }
            """.trimIndent() + "\n",
        ),
        CodeGenerationScaffoldFile(
            relativePath = "docs/architecture.md",
            contents = """
                # Architecture notes

                - Define the core user flow.
                - List the required resources and approvals.
                - Split execution into planner, executor, and audit stages.
            """.trimIndent() + "\n",
        ),
        CodeGenerationScaffoldFile(
            relativePath = "notes/todo.md",
            contents = """
                # Next tasks

                - Flesh out the project requirements.
                - Add implementation milestones.
                - Connect delivery and recovery checkpoints.
            """.trimIndent() + "\n",
        ),
    )
}

private fun buildAndroidPackageName(projectSlug: String): String {
    val packageSegment = projectSlug
        .replace('-', '_')
        .replace(Regex("[^a-z0-9_]+"), "")
        .ifBlank { "generated_project" }
    val normalizedSegment = if (packageSegment.firstOrNull()?.isDigit() == true) {
        "p_$packageSegment"
    } else {
        packageSegment
    }
    return "io.makoion.generated.$normalizedSegment"
}

private const val maxProjectNameLength = 64
private const val maxSlugTokenCount = 4
private const val maxSlugTokenLength = 16
private const val compactPathSegmentCount = 4

private val workspaceSlugStopWords = setOf(
    "a",
    "an",
    "and",
    "app",
    "automation",
    "bot",
    "build",
    "code",
    "create",
    "develop",
    "for",
    "generate",
    "make",
    "mobile",
    "project",
    "script",
    "service",
    "the",
    "tool",
    "workflow",
)
