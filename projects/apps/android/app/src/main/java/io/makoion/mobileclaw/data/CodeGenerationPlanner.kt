package io.makoion.mobileclaw.data

data class CodeGenerationProjectPlan(
    val title: String,
    val targetLabel: String,
    val workspaceLabel: String,
    val outputLabel: String,
)

internal fun buildCodeGenerationProjectPlan(
    prompt: String,
    companionAvailable: Boolean,
): CodeGenerationProjectPlan {
    val normalized = prompt.lowercase()
    val targetLabel = when {
        containsAnyNormalized(
            normalized,
            "android",
            "mobile app",
            "compose",
            "apk",
            "안드로이드",
            "모바일 앱",
            "앱",
        ) -> "Android app"
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
        ) -> "Automation workflow"
        containsAnyNormalized(
            normalized,
            "script",
            "tool",
            "cli",
            "스크립트",
            "툴",
            "명령줄",
        ) -> "Script or tool"
        else -> "Code project"
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
    val outputLabel = when (targetLabel) {
        "Android app" -> "Gradle Android project skeleton"
        "Automation workflow" -> "Automation workflow scaffold"
        "Script or tool" -> "Runnable script scaffold"
        else -> "Source scaffold"
    }
    val title = when (targetLabel) {
        "Android app" -> "Android app build skeleton"
        "Automation workflow" -> "Automation build skeleton"
        "Script or tool" -> "Script build skeleton"
        else -> "Code project skeleton"
    }
    return CodeGenerationProjectPlan(
        title = title,
        targetLabel = targetLabel,
        workspaceLabel = workspaceLabel,
        outputLabel = outputLabel,
    )
}

internal fun buildCodeGenerationSummary(plan: CodeGenerationProjectPlan): String {
    return "Recorded a ${plan.targetLabel} request for ${plan.workspaceLabel}. Output target: ${plan.outputLabel}. Real file generation and execution are still pending implementation."
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
