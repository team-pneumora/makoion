package io.makoion.mobileclaw.data

data class ChatEntryPrompt(
    val label: String,
    val prompt: String,
)

data class ChatEntryPresentation(
    val headline: String,
    val body: String,
    val note: String,
    val prompts: List<ChatEntryPrompt>,
)

internal fun buildChatEntryPresentation(
    prefersKorean: Boolean,
    hasConfiguredProvider: Boolean,
    hasIndexedFiles: Boolean,
    hasPairedCompanion: Boolean,
): ChatEntryPresentation {
    val openSettingsPrompt = ChatContinuationPromptCatalog.spec(ChatContinuationPromptId.OpenSettingsAndResources).prompt
    val checkHealth = ChatContinuationPromptCatalog.spec(ChatContinuationPromptId.CheckCompanionHealth)
    val startSetup = ChatEntryPrompt(
        label = if (prefersKorean) "초기 설정" else "Start setup",
        prompt = if (prefersKorean) {
            "설정을 열고 초기 설정을 시작해줘"
        } else {
            "Open settings and start initial setup"
        },
    )
    val summarizeFiles = ChatEntryPrompt(
        label = if (prefersKorean) "파일 요약" else "Summarize files",
        prompt = if (prefersKorean) {
            "내 인덱스 파일 요약해줘"
        } else {
            "Summarize my indexed files"
        },
    )
    val prompts = buildList {
        if (!hasConfiguredProvider || (!hasIndexedFiles && !hasPairedCompanion)) {
            add(startSetup)
        }
        if (hasIndexedFiles) {
            add(summarizeFiles)
        } else if (hasPairedCompanion) {
            add(ChatEntryPrompt(label = checkHealth.label, prompt = checkHealth.prompt))
        } else if (isEmpty()) {
            add(ChatEntryPrompt(label = "Open settings", prompt = openSettingsPrompt))
        }
    }.take(2)
    return if (prefersKorean) {
        ChatEntryPresentation(
            headline = "한 문장으로 시작하세요",
            body = "파일, 승인, companion 작업은 이 채팅에서 이어집니다.",
            note = if (hasConfiguredProvider) {
                "원하는 일을 그대로 말하면 됩니다. 사진, 동영상, 파일, 음성은 필요할 때만 더하세요."
            } else {
                "먼저 `초기 설정`으로 들어가 API key와 파일 접근을 잡아 두는 편이 좋습니다."
            },
            prompts = prompts,
        )
    } else {
        ChatEntryPresentation(
            headline = "Start with one request",
            body = "Files, approvals, and companion actions stay in this chat.",
            note = if (hasConfiguredProvider) {
                "Type naturally. Add photo, video, file, or voice only when it helps."
            } else {
                "Start setup first so you can add an API key and local file access in one place."
            },
            prompts = prompts,
        )
    }
}

internal fun buildExplainCapabilitiesReply(
    prefersKorean: Boolean,
    hasConfiguredProvider: Boolean,
): String {
    return if (prefersKorean) {
        buildString {
            append("지금은 Makoion chat workspace예요.\n")
            append("파일, 승인, companion 작업을 여기서 이어갈 수 있어요.\n")
            append(
                if (hasConfiguredProvider) {
                    "원하는 일을 한 문장으로 말하면 됩니다."
                } else {
                    "먼저 초기 설정에서 모델 API key와 파일 접근을 잡아 두는 편이 좋습니다."
                },
            )
        }
    } else {
        buildString {
            append("You are in the Makoion chat workspace.\n")
            append("Files, approvals, and companion actions continue here.\n")
            append(
                if (hasConfiguredProvider) {
                    "Start with one plain request."
                } else {
                    "Start setup first so you can add a model API key and local file access."
                },
            )
        }
    }
}
