package io.makoion.mobileclaw.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeGenerationWorkspaceExecutorTest {
    @Test
    fun `workspace slug falls back to project kind for non latin prompt`() {
        val slug = buildCodeGenerationWorkspaceSlug(
            prompt = "모바일 앱을 만들어줘",
            kind = CodeGenerationProjectKind.AndroidApp,
        )

        assertEquals("android-app", slug)
    }

    @Test
    fun `android scaffold blueprint declares manifest and entry file`() {
        val plan = buildCodeGenerationProjectPlan(
            prompt = "build a travel planner android app",
            companionAvailable = false,
        )

        val blueprint = buildCodeGenerationScaffoldBlueprint(
            prompt = "build a travel planner android app",
            plan = plan,
            projectSlug = "travel-planner",
            workspaceDirectoryName = "20260317-090000-travel-planner-ab12cd",
        )

        assertEquals("Phone local Android starter", blueprint.generatorLabel)
        assertTrue(blueprint.files.any { it.relativePath == "app/src/main/AndroidManifest.xml" })
        assertTrue(blueprint.entryFileRelativePath?.endsWith("MainActivity.kt") == true)
    }

    @Test
    fun `materialize scaffold writes generated files to workspace`() {
        val rootDirectory = Files.createTempDirectory("makoion-codegen-test").toFile()
        try {
            val artifact = materializeCodeGenerationScaffold(
                workspaceRoot = rootDirectory,
                blueprint = buildCodeGenerationScaffoldBlueprint(
                    prompt = "create a webhook automation",
                    plan = buildCodeGenerationProjectPlan(
                        prompt = "create a webhook automation",
                        companionAvailable = false,
                    ),
                    projectSlug = "webhook-automation",
                    workspaceDirectoryName = "20260317-090000-webhook-automation-ab12cd",
                ),
            )

            val workspaceDirectory = File(artifact.workspacePath)
            assertTrue(workspaceDirectory.exists())
            assertTrue(File(workspaceDirectory, "README.md").exists())
            assertTrue(File(workspaceDirectory, "workflow/automation.yaml").exists())
            assertTrue(File(artifact.entryFilePath ?: error("missing entry file")).exists())
            assertEquals(6, artifact.generatedFileCount)
        } finally {
            rootDirectory.deleteRecursively()
        }
    }

    @Test
    fun `workspace root prefers external documents when available`() {
        val externalRoot = File("/tmp/external-documents")
        val internalRoot = File("/tmp/internal-files")

        val resolved = resolveCodeGenerationWorkspaceRoot(
            externalDocumentsDir = externalRoot,
            filesDir = internalRoot,
        )

        assertEquals(
            File(externalRoot, "makoion-workspaces/codegen").path,
            resolved.path,
        )
    }
}
