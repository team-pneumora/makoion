package io.makoion.mobileclaw.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.makoion.mobileclaw.MainActivity
import io.makoion.mobileclaw.MobileClawApplication
import io.makoion.mobileclaw.data.ShellDatabaseHelper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScheduledAutomationFlowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()
    private val databaseHelper by lazy {
        ShellDatabaseHelper(InstrumentationRegistry.getInstrumentation().targetContext).also {
            it.ensureScheduledAutomationSchema()
        }
    }

    @After
    fun tearDown() {
        databaseHelper.close()
    }

    @Test
    fun createActivateAndRunScheduledAutomationFromChat() {
        val prompt = "Every morning send a notification digest automation"
        val initialAutomationCount = countAutomationRecords(prompt)

        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Record daily automation", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Record daily automation", useUnmergedTree = true).performClick()

        composeRule.waitUntil(timeoutMillis = 15_000) {
            countAutomationRecords(prompt) > initialAutomationCount
        }

        val plannedAutomation = readAutomationRecord(prompt)
        assertNotNull("Expected the scheduled automation to be recorded from chat.", plannedAutomation)
        val automationId = plannedAutomation!!.id

        composeRule.onNodeWithContentDescription("Dashboard", useUnmergedTree = true).performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(
                ShellTestTags.scheduledAutomationActivateButton(automationId),
                useUnmergedTree = true,
            )
                .fetchSemanticsNodes().isNotEmpty()
        }
        runBlocking {
            application.appContainer.scheduledAutomationCoordinator.activateAutomation(automationId)
        }

        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Pause schedule", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        runBlocking {
            application.appContainer.scheduledAutomationCoordinator.runAutomationNow(automationId)
        }

        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Last run", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        assertTrue(
            "Expected a visible last-run chip after the manual execution.",
            composeRule.onAllNodesWithText("Last run", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
    }

    private fun readAutomationRecord(prompt: String): AutomationSnapshot? {
        return databaseHelper.readableDatabase.query(
            "scheduled_automation_records",
            arrayOf("id", "status", "last_run_at", "next_run_at"),
            "prompt = ?",
            arrayOf(prompt),
            null,
            null,
            "created_at DESC",
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                AutomationSnapshot(
                    id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                    status = cursor.getString(cursor.getColumnIndexOrThrow("status")),
                    lastRunAtEpochMillis = cursor.optLong("last_run_at"),
                    nextRunAtEpochMillis = cursor.optLong("next_run_at"),
                )
            }
        }
    }

    private fun countAutomationRecords(prompt: String): Int {
        return databaseHelper.readableDatabase.query(
            "scheduled_automation_records",
            arrayOf("id"),
            "prompt = ?",
            arrayOf(prompt),
            null,
            null,
            null,
        ).use { cursor ->
            cursor.count
        }
    }

    private fun android.database.Cursor.optLong(columnName: String): Long? {
        val columnIndex = getColumnIndex(columnName)
        if (columnIndex == -1 || isNull(columnIndex)) {
            return null
        }
        return getLong(columnIndex)
    }

    private data class AutomationSnapshot(
        val id: String,
        val status: String,
        val lastRunAtEpochMillis: Long?,
        val nextRunAtEpochMillis: Long?,
    )

    private val application: MobileClawApplication
        get() = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as MobileClawApplication
}
