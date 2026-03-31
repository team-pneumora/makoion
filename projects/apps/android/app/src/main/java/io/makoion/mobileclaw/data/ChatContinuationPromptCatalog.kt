package io.makoion.mobileclaw.data

const val companionWorkflowIdOpenLatestAction = "open_latest_action"
const val companionWorkflowIdOpenLatestTransfer = "open_latest_transfer"
const val companionWorkflowIdOpenActionsFolder = "open_actions_folder"

enum class ChatContinuationPromptId {
    OpenSettingsAndResources,
    CheckCompanionHealth,
    ConnectMcpBridge,
    ShowMcpStatus,
    ShowMcpTools,
    UpdateMcpSkills,
    SendDesktopNotification,
    RunOpenLatestActionWorkflow,
    RunOpenLatestTransferWorkflow,
    RunOpenActionsFolderWorkflow,
    OpenLatestActionFolder,
    OpenLatestTransferFolder,
    OpenCompanionInbox,
    OpenActionsFolder,
}

data class ChatContinuationPromptSpec(
    val label: String,
    val prompt: String,
)

object ChatContinuationPromptCatalog {
    fun spec(promptId: ChatContinuationPromptId): ChatContinuationPromptSpec {
        return when (promptId) {
            ChatContinuationPromptId.OpenSettingsAndResources ->
                ChatContinuationPromptSpec(
                    label = "Open settings",
                    prompt = "Open settings and show my connected resources",
                )
            ChatContinuationPromptId.CheckCompanionHealth ->
                ChatContinuationPromptSpec(
                    label = "Check health",
                    prompt = "Check companion health",
                )
            ChatContinuationPromptId.ConnectMcpBridge ->
                ChatContinuationPromptSpec(
                    label = "Connect MCP",
                    prompt = "Connect the MCP bridge",
                )
            ChatContinuationPromptId.ShowMcpStatus ->
                ChatContinuationPromptSpec(
                    label = "MCP status",
                    prompt = "Show MCP status",
                )
            ChatContinuationPromptId.ShowMcpTools ->
                ChatContinuationPromptSpec(
                    label = "MCP tools",
                    prompt = "Show MCP tools",
                )
            ChatContinuationPromptId.UpdateMcpSkills ->
                ChatContinuationPromptSpec(
                    label = "Update skills",
                    prompt = "Update MCP skills",
                )
            ChatContinuationPromptId.SendDesktopNotification ->
                ChatContinuationPromptSpec(
                    label = "Send notification",
                    prompt = "Send a desktop notification",
                )
            ChatContinuationPromptId.RunOpenLatestActionWorkflow ->
                ChatContinuationPromptSpec(
                    label = "Run latest action",
                    prompt = "Run the open latest action workflow",
                )
            ChatContinuationPromptId.RunOpenLatestTransferWorkflow ->
                ChatContinuationPromptSpec(
                    label = "Run latest transfer",
                    prompt = "Run the open latest transfer workflow",
                )
            ChatContinuationPromptId.RunOpenActionsFolderWorkflow ->
                ChatContinuationPromptSpec(
                    label = "Run actions workflow",
                    prompt = "Run the open actions folder workflow",
                )
            ChatContinuationPromptId.OpenLatestActionFolder ->
                ChatContinuationPromptSpec(
                    label = "Open latest action",
                    prompt = "Open the latest action folder",
                )
            ChatContinuationPromptId.OpenLatestTransferFolder ->
                ChatContinuationPromptSpec(
                    label = "Open latest transfer",
                    prompt = "Open the latest transfer folder",
                )
            ChatContinuationPromptId.OpenCompanionInbox ->
                ChatContinuationPromptSpec(
                    label = "Open inbox",
                    prompt = "Open the companion inbox",
                )
            ChatContinuationPromptId.OpenActionsFolder ->
                ChatContinuationPromptSpec(
                    label = "Open actions",
                    prompt = "Open the actions folder",
                )
        }
    }

    fun workflowSuccessPrompts(workflowId: String): List<ChatContinuationPromptId> {
        return when (workflowId) {
            companionWorkflowIdOpenLatestAction -> listOf(
                ChatContinuationPromptId.OpenLatestActionFolder,
                ChatContinuationPromptId.RunOpenActionsFolderWorkflow,
                ChatContinuationPromptId.SendDesktopNotification,
            )
            companionWorkflowIdOpenLatestTransfer -> listOf(
                ChatContinuationPromptId.OpenLatestTransferFolder,
                ChatContinuationPromptId.OpenCompanionInbox,
                ChatContinuationPromptId.CheckCompanionHealth,
            )
            companionWorkflowIdOpenActionsFolder -> listOf(
                ChatContinuationPromptId.OpenActionsFolder,
                ChatContinuationPromptId.RunOpenLatestActionWorkflow,
                ChatContinuationPromptId.CheckCompanionHealth,
            )
            else -> listOf(
                ChatContinuationPromptId.CheckCompanionHealth,
                ChatContinuationPromptId.OpenSettingsAndResources,
            )
        }
    }

    fun workflowRetryPrompts(workflowId: String): List<ChatContinuationPromptId> {
        return when (workflowId) {
            companionWorkflowIdOpenLatestAction -> listOf(
                ChatContinuationPromptId.RunOpenLatestActionWorkflow,
                ChatContinuationPromptId.CheckCompanionHealth,
                ChatContinuationPromptId.OpenSettingsAndResources,
            )
            companionWorkflowIdOpenLatestTransfer -> listOf(
                ChatContinuationPromptId.RunOpenLatestTransferWorkflow,
                ChatContinuationPromptId.CheckCompanionHealth,
                ChatContinuationPromptId.OpenSettingsAndResources,
            )
            companionWorkflowIdOpenActionsFolder -> listOf(
                ChatContinuationPromptId.RunOpenActionsFolderWorkflow,
                ChatContinuationPromptId.CheckCompanionHealth,
                ChatContinuationPromptId.OpenSettingsAndResources,
            )
            else -> listOf(
                ChatContinuationPromptId.CheckCompanionHealth,
                ChatContinuationPromptId.OpenSettingsAndResources,
            )
        }
    }

    fun appOpenSuccessPrompts(targetKind: String): List<ChatContinuationPromptId> {
        return when (targetKind) {
            companionAppOpenTargetInbox -> listOf(
                ChatContinuationPromptId.SendDesktopNotification,
                ChatContinuationPromptId.OpenLatestTransferFolder,
                ChatContinuationPromptId.CheckCompanionHealth,
            )
            companionAppOpenTargetLatestTransfer -> listOf(
                ChatContinuationPromptId.RunOpenLatestTransferWorkflow,
                ChatContinuationPromptId.OpenCompanionInbox,
                ChatContinuationPromptId.CheckCompanionHealth,
            )
            companionAppOpenTargetActionsFolder -> listOf(
                ChatContinuationPromptId.RunOpenActionsFolderWorkflow,
                ChatContinuationPromptId.OpenLatestActionFolder,
                ChatContinuationPromptId.CheckCompanionHealth,
            )
            companionAppOpenTargetLatestAction -> listOf(
                ChatContinuationPromptId.RunOpenLatestActionWorkflow,
                ChatContinuationPromptId.OpenActionsFolder,
                ChatContinuationPromptId.CheckCompanionHealth,
            )
            else -> listOf(
                ChatContinuationPromptId.CheckCompanionHealth,
                ChatContinuationPromptId.OpenSettingsAndResources,
            )
        }
    }
}
