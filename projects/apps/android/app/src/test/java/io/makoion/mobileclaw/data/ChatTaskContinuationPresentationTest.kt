package io.makoion.mobileclaw.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTaskContinuationPresentationTest {
    @Test
    fun `health success offers next companion actions`() {
        val task = task(
            actionKey = companionHealthProbeActionKey,
            status = AgentTaskStatus.Succeeded,
            prompt = "Check companion health",
            summary = "Companion is healthy.",
        )

        val prompts = ChatTaskContinuationPresentation.continuationPrompts(task)

        assertEquals(
            listOf(
                ChatContinuationPromptId.SendDesktopNotification,
                ChatContinuationPromptId.OpenCompanionInbox,
                ChatContinuationPromptId.RunOpenLatestActionWorkflow,
            ),
            prompts,
        )
    }

    @Test
    fun `workflow failure keeps retry and setup prompts`() {
        val task = task(
            actionKey = companionWorkflowRunActionKey,
            status = AgentTaskStatus.Failed,
            prompt = "Run the open latest transfer workflow",
            summary = "Workflow open_latest_transfer failed.",
        )

        val prompts = ChatTaskContinuationPresentation.continuationPrompts(task)

        assertEquals(
            listOf(
                ChatContinuationPromptId.RunOpenLatestTransferWorkflow,
                ChatContinuationPromptId.CheckCompanionHealth,
                ChatContinuationPromptId.OpenSettingsAndResources,
            ),
            prompts,
        )
    }

    @Test
    fun `manual retry eligibility matches transfer approval tasks`() {
        val task = task(
            actionKey = filesTransferExecuteActionKey,
            status = AgentTaskStatus.Failed,
            approvalRequestId = "approval-1",
        )

        assertTrue(ChatTaskContinuationPresentation.isManualRetryEligible(task))
    }

    @Test
    fun `mcp guide waiting resource offers chat-first setup prompts`() {
        val task = task(
            actionKey = mcpSetupGuideActionKey,
            status = AgentTaskStatus.WaitingResource,
            prompt = "MCP 연결 어떻게 해?",
            summary = "채팅 기준 MCP 연결 단계를 정리했습니다.",
        )

        val prompts = ChatTaskContinuationPresentation.continuationPrompts(task)

        assertEquals(
            listOf(
                ChatContinuationPromptId.CheckCompanionHealth,
                ChatContinuationPromptId.ConnectMcpBridge,
                ChatContinuationPromptId.OpenSettingsAndResources,
            ),
            prompts,
        )
    }

    @Test
    fun `mcp bridge success offers status tools and sync`() {
        val task = task(
            actionKey = mcpBridgeConnectActionKey,
            status = AgentTaskStatus.Succeeded,
            prompt = "Connect the MCP bridge",
            summary = "Completed companion-backed MCP bridge discovery from chat.",
        )

        val prompts = ChatTaskContinuationPresentation.continuationPrompts(task)

        assertEquals(
            listOf(
                ChatContinuationPromptId.ShowMcpStatus,
                ChatContinuationPromptId.ShowMcpTools,
                ChatContinuationPromptId.UpdateMcpSkills,
            ),
            prompts,
        )
    }

    private fun task(
        actionKey: String,
        status: AgentTaskStatus,
        prompt: String = "Task",
        summary: String = "Summary",
        approvalRequestId: String? = null,
        maxRetryCount: Int = 0,
    ): AgentTaskRecord {
        return AgentTaskRecord(
            id = "task-1",
            threadId = "thread-1",
            title = "Task",
            prompt = prompt,
            actionKey = actionKey,
            status = status,
            summary = summary,
            approvalRequestId = approvalRequestId,
            maxRetryCount = maxRetryCount,
            createdAtEpochMillis = 0L,
            updatedAtEpochMillis = 0L,
            createdAtLabel = "now",
            updatedAtLabel = "now",
        )
    }
}
