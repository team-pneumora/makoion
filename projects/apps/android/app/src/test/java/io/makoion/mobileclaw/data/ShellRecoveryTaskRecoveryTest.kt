package io.makoion.mobileclaw.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellRecoveryTaskRecoveryTest {
    @Test
    fun `delivered transfer resolves to success`() {
        val resolution = resolveTransferTaskRecovery(
            listOf(
                draft(
                    status = TransferDraftStatus.Delivered,
                    detail = "Delivered via http://127.0.0.1:8799",
                ),
            ),
        )

        assertEquals(AgentTaskStatus.Succeeded, resolution.status)
        assertTrue(resolution.summary.contains("delivered transfer task"))
    }

    @Test
    fun `queued draft with next attempt resolves to retry scheduled`() {
        val resolution = resolveTransferTaskRecovery(
            listOf(
                draft(
                    status = TransferDraftStatus.Queued,
                    detail = "Retry scheduled in 2 minutes.",
                    nextAttemptAtEpochMillis = 1000L,
                    nextAttemptAtLabel = "in 2 minutes",
                ),
            ),
        )

        assertEquals(AgentTaskStatus.RetryScheduled, resolution.status)
        assertTrue(resolution.replyPreview.contains("retry"))
    }

    @Test
    fun `failed draft resolves to failed`() {
        val resolution = resolveTransferTaskRecovery(
            listOf(
                draft(
                    status = TransferDraftStatus.Failed,
                    detail = "Connection timeout",
                ),
            ),
        )

        assertEquals(AgentTaskStatus.Failed, resolution.status)
        assertTrue(resolution.summary.contains("manual retry"))
    }

    private fun draft(
        status: TransferDraftStatus,
        detail: String,
        nextAttemptAtEpochMillis: Long? = null,
        nextAttemptAtLabel: String? = null,
    ): TransferDraftState {
        return TransferDraftState(
            id = "draft-1",
            deviceId = "device-1",
            deviceName = "Desktop companion",
            status = status,
            createdAtLabel = "now",
            updatedAtLabel = "now",
            fileNames = listOf("photo.jpg"),
            detail = detail,
            attemptCount = 1,
            deliveryModeLabel = "direct_http",
            nextAttemptAtEpochMillis = nextAttemptAtEpochMillis,
            nextAttemptAtLabel = nextAttemptAtLabel,
        )
    }
}
