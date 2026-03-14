package io.makoion.mobileclaw.data

import org.json.JSONArray
import org.json.JSONObject

data class OrganizeApprovalStep(
    val fileId: String,
    val fileName: String,
    val mimeType: String,
    val sourceLabel: String,
    val destinationFolder: String,
    val reason: String,
)

data class OrganizeApprovalPayload(
    val strategy: FileOrganizeStrategy,
    val requestedAtEpochMs: Long,
    val forceDeleteConsentForTesting: Boolean = false,
    val steps: List<OrganizeApprovalStep>,
) {
    fun toJson(): String {
        return JSONObject()
            .put("strategy", strategy.name)
            .put("requested_at", requestedAtEpochMs)
            .put("force_delete_consent_for_testing", forceDeleteConsentForTesting)
            .put(
                "steps",
                JSONArray().apply {
                    steps.forEach { step ->
                        put(
                            JSONObject()
                                .put("file_id", step.fileId)
                                .put("file_name", step.fileName)
                                .put("mime_type", step.mimeType)
                                .put("source_label", step.sourceLabel)
                                .put("destination_folder", step.destinationFolder)
                                .put("reason", step.reason),
                        )
                    }
                },
            )
            .toString()
    }

    companion object {
        fun fromJson(raw: String): OrganizeApprovalPayload {
            val json = JSONObject(raw)
            val stepsJson = json.getJSONArray("steps")
            val steps = buildList {
                for (index in 0 until stepsJson.length()) {
                    val step = stepsJson.getJSONObject(index)
                    add(
                        OrganizeApprovalStep(
                            fileId = step.getString("file_id"),
                            fileName = step.getString("file_name"),
                            mimeType = step.optString("mime_type", "application/octet-stream"),
                            sourceLabel = step.optString("source_label", "Local"),
                            destinationFolder = step.getString("destination_folder"),
                            reason = step.optString("reason"),
                        ),
                    )
                }
            }
            return OrganizeApprovalPayload(
                strategy = FileOrganizeStrategy.valueOf(
                    json.optString("strategy", FileOrganizeStrategy.ByType.name),
                ),
                requestedAtEpochMs = json.optLong("requested_at", System.currentTimeMillis()),
                forceDeleteConsentForTesting = json.optBoolean("force_delete_consent_for_testing", false),
                steps = steps,
            )
        }
    }
}

fun buildOrganizeApprovalPayload(
    plan: FileOrganizePlan,
    items: List<IndexedFileItem>,
    forceDeleteConsentForTesting: Boolean = false,
    requestedAtEpochMs: Long = System.currentTimeMillis(),
): OrganizeApprovalPayload {
    val itemIndex = items.associateBy { it.id }
    return OrganizeApprovalPayload(
        strategy = plan.strategy,
        requestedAtEpochMs = requestedAtEpochMs,
        forceDeleteConsentForTesting = forceDeleteConsentForTesting,
        steps = plan.steps.map { step ->
            val item = itemIndex[step.fileId]
            OrganizeApprovalStep(
                fileId = step.fileId,
                fileName = step.fileName,
                mimeType = item?.mimeType ?: "application/octet-stream",
                sourceLabel = step.sourceLabel,
                destinationFolder = step.destinationFolder,
                reason = step.reason,
            )
        },
    )
}
