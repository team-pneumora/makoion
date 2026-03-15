package io.makoion.mobileclaw.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.text.format.DateUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

enum class DeliveryChannelType(
    val typeId: String,
    val displayName: String,
) {
    LocalNotification("local_notification", "Phone notification"),
    TelegramBot("telegram_bot", "Telegram bot"),
    DesktopCompanion("desktop_companion", "Desktop companion"),
    Webhook("webhook", "Webhook"),
}

enum class DeliveryChannelStatus {
    NeedsSetup,
    Staged,
    Connected,
}

data class DeliveryChannelProfileState(
    val channelId: String,
    val displayName: String,
    val type: DeliveryChannelType,
    val status: DeliveryChannelStatus,
    val summary: String,
    val supportedDeliveries: List<String>,
    val destinationLabel: String? = null,
    val updatedAtEpochMillis: Long,
    val updatedAtLabel: String,
)

internal data class SeededDeliveryChannelProfile(
    val channelId: String,
    val displayName: String,
    val type: DeliveryChannelType,
    val status: DeliveryChannelStatus,
    val summary: String,
    val supportedDeliveries: List<String>,
    val defaultConnectedLabel: String? = null,
)

interface DeliveryChannelRegistryRepository {
    val profiles: StateFlow<List<DeliveryChannelProfileState>>

    suspend fun stageChannel(channelId: String)

    suspend fun markConnected(
        channelId: String,
        destinationLabel: String? = null,
    )

    suspend fun resetChannel(channelId: String)

    suspend fun refresh()
}

internal fun defaultDeliveryChannelSeeds(): List<SeededDeliveryChannelProfile> {
    return listOf(
        SeededDeliveryChannelProfile(
            channelId = "phone-local-notification",
            displayName = "Phone local notification",
            type = DeliveryChannelType.LocalNotification,
            status = DeliveryChannelStatus.Connected,
            summary = "The shell can already surface task follow-ups and quick actions through local Android notifications. Scheduler binding is still pending.",
            supportedDeliveries = listOf("notifications.local", "notifications.quick_action"),
            defaultConnectedLabel = "Android system notifications",
        ),
        SeededDeliveryChannelProfile(
            channelId = "telegram-bot-delivery",
            displayName = "Telegram bot relay",
            type = DeliveryChannelType.TelegramBot,
            status = DeliveryChannelStatus.NeedsSetup,
            summary = "Telegram delivery is planned for recurring alerts and research summaries, but bot token storage and chat binding are not wired yet.",
            supportedDeliveries = listOf("notifications.telegram", "automation.alert"),
            defaultConnectedLabel = "Telegram bot placeholder",
        ),
        SeededDeliveryChannelProfile(
            channelId = "desktop-companion-delivery",
            displayName = "Desktop companion relay",
            type = DeliveryChannelType.DesktopCompanion,
            status = DeliveryChannelStatus.Staged,
            summary = "Manual desktop companion notifications exist, but automation-triggered delivery routing is still staged rather than fully wired.",
            supportedDeliveries = listOf("notifications.desktop", "session.notify"),
            defaultConnectedLabel = "Desktop companion placeholder",
        ),
        SeededDeliveryChannelProfile(
            channelId = "custom-webhook-delivery",
            displayName = "Custom webhook relay",
            type = DeliveryChannelType.Webhook,
            status = DeliveryChannelStatus.NeedsSetup,
            summary = "Webhook delivery is reserved for external inboxes and downstream automations, but signed request templates are not implemented yet.",
            supportedDeliveries = listOf("notifications.webhook", "automation.webhook"),
            defaultConnectedLabel = "Webhook placeholder",
        ),
    )
}

class PersistentDeliveryChannelRegistryRepository(
    private val databaseHelper: ShellDatabaseHelper,
) : DeliveryChannelRegistryRepository {
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _profiles = MutableStateFlow<List<DeliveryChannelProfileState>>(emptyList())

    override val profiles: StateFlow<List<DeliveryChannelProfileState>> = _profiles.asStateFlow()

    init {
        repositoryScope.launch {
            refresh()
        }
    }

    override suspend fun stageChannel(channelId: String) {
        val seed = defaultDeliveryChannelSeeds().firstOrNull { it.channelId == channelId } ?: return
        updateChannel(
            seed = seed,
            status = DeliveryChannelStatus.Staged,
            summary = when (seed.type) {
                DeliveryChannelType.LocalNotification ->
                    "Local notification delivery is staged for automation runs. Scheduler binding and richer notification templates still need wiring."
                DeliveryChannelType.TelegramBot ->
                    "Telegram delivery staging is recorded. Bot credential vault, chat binding, and send policies still need to be added."
                DeliveryChannelType.DesktopCompanion ->
                    "Desktop companion delivery staging is recorded. Background delivery routing and retry policy still need to be connected."
                DeliveryChannelType.Webhook ->
                    "Webhook delivery staging is recorded. Signed payload templates, retry policy, and audit review are still pending."
            },
            destinationLabel = null,
        )
    }

    override suspend fun markConnected(
        channelId: String,
        destinationLabel: String?,
    ) {
        val seed = defaultDeliveryChannelSeeds().firstOrNull { it.channelId == channelId } ?: return
        val recordedLabel = destinationLabel?.trim().takeUnless { it.isNullOrBlank() }
            ?: seed.defaultConnectedLabel
            ?: seed.displayName
        updateChannel(
            seed = seed,
            status = DeliveryChannelStatus.Connected,
            summary = "Mock-ready delivery target recorded for $recordedLabel. Real credential binding, delivery retries, and user-level routing still need to replace this placeholder state.",
            destinationLabel = recordedLabel,
        )
    }

    override suspend fun resetChannel(channelId: String) {
        val seed = defaultDeliveryChannelSeeds().firstOrNull { it.channelId == channelId } ?: return
        updateChannel(
            seed = seed,
            status = seed.status,
            summary = seed.summary,
            destinationLabel = if (seed.status == DeliveryChannelStatus.Connected) {
                seed.defaultConnectedLabel
            } else {
                null
            },
        )
    }

    override suspend fun refresh() {
        withContext(Dispatchers.IO) {
            val db = databaseHelper.writableDatabase
            ensureSeedData(db)
            _profiles.value = queryProfiles(db)
        }
    }

    private suspend fun updateChannel(
        seed: SeededDeliveryChannelProfile,
        status: DeliveryChannelStatus,
        summary: String,
        destinationLabel: String?,
    ) {
        withContext(Dispatchers.IO) {
            val db = databaseHelper.writableDatabase
            ensureSeedData(db)
            db.insertWithOnConflict(
                channelTable,
                null,
                ContentValues().apply {
                    put("channel_id", seed.channelId)
                    put("display_name", seed.displayName)
                    put("type_id", seed.type.typeId)
                    put("status", status.name)
                    put("summary", summary)
                    put("supported_deliveries_json", JSONArray(seed.supportedDeliveries).toString())
                    put("destination_label", destinationLabel)
                    put("updated_at", System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
        refresh()
    }

    private fun ensureSeedData(db: SQLiteDatabase) {
        val now = System.currentTimeMillis()
        defaultDeliveryChannelSeeds().forEach { seed ->
            db.insertWithOnConflict(
                channelTable,
                null,
                ContentValues().apply {
                    put("channel_id", seed.channelId)
                    put("display_name", seed.displayName)
                    put("type_id", seed.type.typeId)
                    put("status", seed.status.name)
                    put("summary", seed.summary)
                    put("supported_deliveries_json", JSONArray(seed.supportedDeliveries).toString())
                    if (seed.status == DeliveryChannelStatus.Connected) {
                        put("destination_label", seed.defaultConnectedLabel)
                    } else {
                        putNull("destination_label")
                    }
                    put("updated_at", now)
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
        }
    }

    private fun queryProfiles(db: SQLiteDatabase): List<DeliveryChannelProfileState> {
        val now = System.currentTimeMillis()
        return db.query(
            channelTable,
            arrayOf(
                "channel_id",
                "display_name",
                "type_id",
                "status",
                "summary",
                "supported_deliveries_json",
                "destination_label",
                "updated_at",
            ),
            null,
            null,
            null,
            null,
            "type_id ASC, display_name ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"))
                    add(
                        DeliveryChannelProfileState(
                            channelId = cursor.getString(cursor.getColumnIndexOrThrow("channel_id")),
                            displayName = cursor.getString(cursor.getColumnIndexOrThrow("display_name")),
                            type = typeFromId(cursor.getString(cursor.getColumnIndexOrThrow("type_id"))),
                            status = runCatching {
                                DeliveryChannelStatus.valueOf(
                                    cursor.getString(cursor.getColumnIndexOrThrow("status")),
                                )
                            }.getOrDefault(DeliveryChannelStatus.NeedsSetup),
                            summary = cursor.getString(cursor.getColumnIndexOrThrow("summary")),
                            supportedDeliveries = jsonArrayToList(
                                cursor.getString(
                                    cursor.getColumnIndexOrThrow("supported_deliveries_json"),
                                ),
                            ),
                            destinationLabel = cursor.getString(
                                cursor.getColumnIndexOrThrow("destination_label"),
                            ),
                            updatedAtEpochMillis = updatedAt,
                            updatedAtLabel = DateUtils.getRelativeTimeSpanString(
                                updatedAt,
                                now,
                                DateUtils.MINUTE_IN_MILLIS,
                            ).toString(),
                        ),
                    )
                }
            }
        }
    }

    private fun typeFromId(typeId: String): DeliveryChannelType {
        return DeliveryChannelType.entries.firstOrNull { it.typeId == typeId }
            ?: DeliveryChannelType.Webhook
    }

    private fun jsonArrayToList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) {
            return emptyList()
        }
        val array = runCatching { JSONArray(raw) }.getOrElse {
            return emptyList()
        }
        return buildList {
            for (index in 0 until array.length()) {
                add(array.optString(index))
            }
        }.filter { it.isNotBlank() }
    }

    companion object {
        private const val channelTable = "delivery_channel_profiles"
    }
}
