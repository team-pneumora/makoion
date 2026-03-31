package io.makoion.mobileclaw.data

data class TransferTaskRecoveryResolution(
    val status: AgentTaskStatus,
    val summary: String,
    val replyPreview: String,
)

fun resolveTransferTaskRecovery(
    drafts: List<TransferDraftState>,
): TransferTaskRecoveryResolution {
    if (drafts.isEmpty()) {
        return TransferTaskRecoveryResolution(
            status = AgentTaskStatus.WaitingResource,
            summary = "Recovery could not find any transfer drafts for this approved task. Inspect the paired device and retry from chat if needed.",
            replyPreview = "No transfer draft was available during task recovery.",
        )
    }

    val delivered = drafts.filter { it.status == TransferDraftStatus.Delivered }
    if (delivered.isNotEmpty()) {
        val reviewRequired = delivered.any { it.receiptReviewRequired || !it.receiptIssue.isNullOrBlank() }
        val latestDelivered = delivered.maxByOrNull { it.updatedAtLabel.length } ?: delivered.last()
        return if (reviewRequired) {
            TransferTaskRecoveryResolution(
                status = AgentTaskStatus.WaitingUser,
                summary = "Recovery found delivered transfer drafts, but receipt review is still required. ${latestDelivered.detail}",
                replyPreview = "Transfer delivery finished, but receipt review still needs attention.",
            )
        } else {
            TransferTaskRecoveryResolution(
                status = AgentTaskStatus.Succeeded,
                summary = "Recovery restored a delivered transfer task. ${latestDelivered.detail}",
                replyPreview = "Transfer delivery was already completed.",
            )
        }
    }

    val sending = drafts.filter { it.status == TransferDraftStatus.Sending }
    if (sending.isNotEmpty()) {
        return TransferTaskRecoveryResolution(
            status = AgentTaskStatus.Running,
            summary = "Recovery found ${sending.size} draft(s) still sending through the bridge.",
            replyPreview = "Transfer is still in progress after recovery.",
        )
    }

    val queued = drafts.filter { it.status == TransferDraftStatus.Queued }
    if (queued.isNotEmpty()) {
        val delayedDraft = queued.firstOrNull { it.nextAttemptAtEpochMillis != null }
        return if (delayedDraft != null) {
            TransferTaskRecoveryResolution(
                status = AgentTaskStatus.RetryScheduled,
                summary = "Recovery found queued transfer drafts waiting for the next bridge retry. ${delayedDraft.detail}",
                replyPreview = "Transfer retry is already scheduled.",
            )
        } else {
            TransferTaskRecoveryResolution(
                status = AgentTaskStatus.Running,
                summary = "Recovery found queued transfer drafts ready for bridge delivery.",
                replyPreview = "Transfer delivery is queued and should resume shortly.",
            )
        }
    }

    val failed = drafts.filter { it.status == TransferDraftStatus.Failed }
    if (failed.isNotEmpty()) {
        val latestFailed = failed.firstOrNull { !it.detail.isNullOrBlank() } ?: failed.first()
        return TransferTaskRecoveryResolution(
            status = AgentTaskStatus.Failed,
            summary = "Recovery found failed transfer drafts that still need a manual retry. ${latestFailed.detail}",
            replyPreview = "Transfer failed and needs a manual retry.",
        )
    }

    return TransferTaskRecoveryResolution(
        status = AgentTaskStatus.WaitingResource,
        summary = "Recovery found transfer drafts, but their state could not be classified safely. Inspect the task before retrying.",
        replyPreview = "Transfer recovery could not safely classify the draft state.",
    )
}
