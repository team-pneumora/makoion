package io.makoion.mobileclaw.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.makoion.mobileclaw.MainActivity
import io.makoion.mobileclaw.MobileClawApplication
import io.makoion.mobileclaw.data.ChatMessage
import io.makoion.mobileclaw.data.ChatMessageRole
import io.makoion.mobileclaw.data.ShellRecoveryStatus
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatRecoveryFlowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun restoresActiveChatThreadAfterActivityRecreate() {
        val threadTitle = "Check recovery after recreate"
        val assistantReply = "Recovery state is now preserved in this thread."

        runBlocking {
            val thread = application.appContainer.chatTranscriptRepository.createThread()
            application.appContainer.chatTranscriptRepository.appendMessage(
                message = ChatMessage(
                    id = "user-recovery-check",
                    role = ChatMessageRole.User,
                    text = threadTitle,
                ),
                threadId = thread.id,
            )
            application.appContainer.chatTranscriptRepository.appendMessage(
                message = ChatMessage(
                    id = "assistant-recovery-check",
                    role = ChatMessageRole.Assistant,
                    text = assistantReply,
                ),
                threadId = thread.id,
            )
            application.appContainer.chatTranscriptRepository.activateThread(thread.id)
        }

        composeRule.waitUntil(timeoutMillis = 15_000) {
            application.appContainer.chatTranscriptRepository.activeThread.value?.title == threadTitle
        }

        composeRule.activityRule.scenario.recreate()

        composeRule.waitUntil(timeoutMillis = 15_000) {
            application.appContainer.chatTranscriptRepository.activeThread.value?.title == threadTitle
        }

        check(
            application.appContainer.chatTranscriptRepository.activeThread.value?.title == threadTitle
        ) {
            "Expected the active chat thread title to be restored after activity recreation."
        }
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText(assistantReply, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(assistantReply, useUnmergedTree = true).assertIsDisplayed()

        application.appContainer.shellRecoveryCoordinator.requestManualRecovery()

        composeRule.waitUntil(timeoutMillis = 15_000) {
            val recoveryState = application.appContainer.shellRecoveryCoordinator.state.value
            recoveryState.triggerLabel == "Manual" && recoveryState.status != ShellRecoveryStatus.Running
        }

        val recoveryState = application.appContainer.shellRecoveryCoordinator.state.value
        assertTrue(
            "Expected manual shell recovery to finish successfully after recreating the chat screen.",
            recoveryState.status == ShellRecoveryStatus.Success,
        )
        assertTrue(
            "Expected recovery details to mention the restored active chat thread.",
            recoveryState.detail.contains("Chat recovery restored active thread $threadTitle", ignoreCase = true),
        )
    }

    private val application: MobileClawApplication
        get() = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as MobileClawApplication
}
