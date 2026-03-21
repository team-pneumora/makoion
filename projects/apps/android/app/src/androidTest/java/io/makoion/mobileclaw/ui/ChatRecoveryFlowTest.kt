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
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
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
    }

    private val application: MobileClawApplication
        get() = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as MobileClawApplication
}
