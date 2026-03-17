package io.makoion.mobileclaw.data

enum class CodeGenerationProjectKind(
    val targetLabel: String,
    val outputLabel: String,
    val title: String,
    val slugPrefix: String,
) {
    AndroidApp(
        targetLabel = "Android app",
        outputLabel = "Gradle Android project scaffold",
        title = "Android app build scaffold",
        slugPrefix = "android-app",
    ),
    AutomationWorkflow(
        targetLabel = "Automation workflow",
        outputLabel = "Automation workflow scaffold",
        title = "Automation build scaffold",
        slugPrefix = "automation-workflow",
    ),
    ScriptOrTool(
        targetLabel = "Script or tool",
        outputLabel = "Runnable script scaffold",
        title = "Script build scaffold",
        slugPrefix = "script-tool",
    ),
    GenericCodeProject(
        targetLabel = "Code project",
        outputLabel = "Source scaffold",
        title = "Code project scaffold",
        slugPrefix = "code-project",
    ),
}

data class CodeGenerationProjectPlan(
    val title: String,
    val targetLabel: String,
    val workspaceLabel: String,
    val outputLabel: String,
    val kind: CodeGenerationProjectKind,
)

internal fun buildCodeGenerationProjectPlan(
    prompt: String,
    companionAvailable: Boolean,
): CodeGenerationProjectPlan {
    val normalized = prompt.lowercase()
    val kind = when {
        containsAnyNormalized(
            normalized,
            "android",
            "mobile app",
            "compose",
            "apk",
            "안드로이드",
            "모바일 앱",
            "앱",
        ) -> CodeGenerationProjectKind.AndroidApp
        containsAnyNormalized(
            normalized,
            "automation",
            "workflow",
            "bot",
            "telegram",
            "알림 서비스",
            "자동화",
            "워크플로",
            "봇",
            "텔레그램",
        ) -> CodeGenerationProjectKind.AutomationWorkflow
        containsAnyNormalized(
            normalized,
            "script",
            "tool",
            "cli",
            "스크립트",
            "툴",
            "명령줄",
        ) -> CodeGenerationProjectKind.ScriptOrTool
        else -> CodeGenerationProjectKind.GenericCodeProject
    }
    val workspaceLabel = when {
        companionAvailable && containsAnyNormalized(
            normalized,
            "desktop",
            "pc",
            "companion",
            "remote",
            "데스크탑",
            "외부 pc",
            "컴패니언",
            "원격",
        ) -> "Companion-assisted workspace"
        companionAvailable -> "Phone workspace first, companion optional"
        else -> "Phone local workspace"
    }
    return CodeGenerationProjectPlan(
        title = kind.title,
        targetLabel = kind.targetLabel,
        workspaceLabel = workspaceLabel,
        outputLabel = kind.outputLabel,
        kind = kind,
    )
}

internal fun buildCodeGenerationSummary(
    plan: CodeGenerationProjectPlan,
    artifact: CodeGenerationWorkspaceArtifact? = null,
): String {
    return if (artifact == null) {
        "Recorded a ${plan.targetLabel} request for ${plan.workspaceLabel}. Output target: ${plan.outputLabel}. Real file generation and execution are still pending implementation."
    } else {
        buildString {
            append("Generated a ")
            append(plan.targetLabel)
            append(" scaffold with ")
            append(artifact.generatedFileCount)
            append(" file(s) via ")
            append(artifact.generatorLabel)
            append(". Workspace: ")
            append(compactCodeGenerationPath(artifact.workspacePath))
            artifact.entryFilePath?.let { entryFilePath ->
                append(". Entry file: ")
                append(compactCodeGenerationPath(entryFilePath))
            }
        }
    }
}

internal fun looksLikeCodeGenerationPrompt(normalizedPrompt: String): Boolean {
    val hasBuildVerb = containsAnyNormalized(
        normalizedPrompt,
        "build",
        "make",
        "create",
        "generate",
        "develop",
        "구축",
        "만들",
        "생성",
        "개발",
    )
    val hasCodeTarget = containsAnyNormalized(
        normalizedPrompt,
        "code",
        "app",
        "automation",
        "workflow",
        "script",
        "bot",
        "service",
        "프로젝트",
        "코드",
        "앱",
        "자동화",
        "워크플로",
        "스크립트",
        "봇",
        "서비스",
    )
    return hasBuildVerb && hasCodeTarget
}

private fun containsAnyNormalized(
    normalizedPrompt: String,
    vararg terms: String,
): Boolean {
    return terms.any(normalizedPrompt::contains)
}
