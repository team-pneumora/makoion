package io.makoion.mobileclaw.data

import org.json.JSONArray
import org.json.JSONObject

data class TransferApprovalPayload(
    val deviceId: String,
    val deviceName: String,
    val fileReferences: List<TransferFileReference>,
    val requestedAtEpochMs: Long,
) {
    fun toJson(): String {
        return JSONObject()
            .put("device_id", deviceId)
            .put("device_name", deviceName)
            .put("requested_at_epoch_ms", requestedAtEpochMs)
            .put(
                "files",
                JSONArray(
                    fileReferences.map { file ->
                        JSONObject()
                            .put("source_id", file.sourceId)
                            .put("name", file.name)
                            .put("mime_type", file.mimeType)
                    },
                ),
            )
            .toString()
    }

    companion object {
        fun fromJson(raw: String): TransferApprovalPayload {
            val json = JSONObject(raw)
            val filesJson = json.optJSONArray("files") ?: JSONArray()
            val files = buildList {
                for (index in 0 until filesJson.length()) {
                    val item = filesJson.optJSONObject(index) ?: continue
                    add(
                        TransferFileReference(
                            sourceId = item.optString("source_id"),
                            name = item.optString("name"),
                            mimeType = item.optString("mime_type"),
                        ),
                    )
                }
            }
            return TransferApprovalPayload(
                deviceId = json.optString("device_id"),
                deviceName = json.optString("device_name"),
                fileReferences = files,
                requestedAtEpochMs = json.optLong("requested_at_epoch_ms"),
            )
        }
    }
}
