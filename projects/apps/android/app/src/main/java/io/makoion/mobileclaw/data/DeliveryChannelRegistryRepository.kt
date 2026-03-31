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
    val address: String? = null,
    val configSummary: String? = null,
    val updatedAtEpochMillis: Long,
    val updatedAtLabel: String,
    val lastDeliveryAtEpochMillis: Long? = null,
    val lastDeliveryAtLabel: String? = null,
    val lastDeliveryError: String? = null,
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

    suspend fun configureTelegramBinding(
        channelId: String,
        chatId: String,
        destinationLabel: String?,
    )

    suspend fun noteDeliveryAttempt(
        channelId: String,
        deliveredAtEpochMillis: Long,
        error: String? = null,
    )

    suspend fun telegramBinding(channelId: String = "telegram-bot-delivery"): TelegramDeliveryBinding?

    suspend fun resetChannel(channelId: String)

    suspend fun refresh()
}

data class TelegramDeliveryBinding(
    val channelId: String,
    val chatId: String,
    val destinationLabel: String?,
)

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
            address = null,
            configSummary = null,
            lastDeliveryAtEpochMillis = null,
            lastDeliveryError = null,
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
            address = null,
            configSummary = null,
            lastDeliveryAtEpochMillis = null,
            lastDeliveryError = null,
        )
    }

    override suspend fun configureTelegramBinding(
        channelId: String,
        chatId: String,
        destinationLabel: String?,
    ) {
        val seed = defaultDeliveryChannelSeeds().firstOrNull { it.channelId == channelId } ?: return
        val trimmedChatId = chatId.trim()
        if (trimmedChatId.isBlank()) {
            return
        }
        val label = destinationLabel?.trim().takeUnless { it.isNullOrBlank() } ?: "Chat $trimmedChatId"
        updateChannel(
            seed = seed,
            status = DeliveryChannelStatus.Connected,
            summary = "Telegram delivery is active for $label. Scheduled alerts can now deliver through the bound chat before falling back to local notifications.",
            destinationLabel = label,
            address = trimmedChatId,
            configSummary = "Bound target chat $trimmedChatId",
            lastDeliveryAtEpochMillis = null,
            lastDeliveryError = null,
        )
    }

    override suspend fun noteDeliveryAttempt(
        channelId: String,
        deliveredAtEpochMillis: Long,
        error: String?,
    ) {
        withContext(Dispatchers.IO) {
            databaseHelper.ensureDeliveryChannelSchema()
            val db = databaseHelper.writableDatabase
            ensureSeedData(db)
            val current = db.query(
                channelTable,
                arrayOf(
                    "channel_id",
                    "display_name",
                    "type_id",
                    "status",
                    "summary",
                    "supported_deliveries_json",
                    "destination_label",
                    "address",
                    "config_json",
                ),
                "channel_id = ?",
                arrayOf(channelId),
                null,
                null,
                null,
            ).use { cursor ->
                if (!cursor.moveToFirst()) {
                    null
                } else {
                    ContentValues().apply {
                        put("channel_id", cursor.getString(cursor.getColumnIndexOrThrow("channel_id")))
                        put("display_name", cursor.getString(cursor.getColumnIndexOrThrow("display_name")))
                        put("type_id", cursor.getString(cursor.getColumnIndexOrThrow("type_id")))
                        put("status", cursor.getString(cursor.getColumnIndexOrThrow("status")))
                        put("summary", cursor.getString(cursor.getColumnIndexOrThrow("summary")))
                        put(
                            "supported_deliveries_json",
                            cursor.getString(cursor.getColumnIndexOrThrow("supported_deliveries_json")),
                        )
                        put("destination_label", cursor.getString(cursor.getColumnIndexOrThrow("destination_label")))
                        put("address", cursor.getString(cursor.getColumnIndexOrThrow("address")))
                        put("config_json", cursor.getString(cursor.getColumnIndexOrThrow("config_json")))
                    }
                }
            } ?: return@withContext
            current.put("updated_at", deliveredAtEpochMillis)
            current.put("last_delivery_at", deliveredAtEpochMillis)
            if (error.isNullOrBlank()) {
                current.putNull("last_delivery_error")
            } else {
                current.put("last_delivery_error", error)
            }
            db.insertWithOnConflict(channelTable, null, current, SQLiteDatabase.CONFLICT_REPLACE)
        }
        refresh()
    }

    override suspend fun telegramBinding(channelId: String): TelegramDeliveryBinding? {
        refresh()
        return _profiles.value.firstOrNull { it.channelId == channelId }
            ?.takeIf { it.type == DeliveryChannelType.TelegramBot && it.status == DeliveryChannelStatus.Connected }
            ?.address
            ?.let { chatId ->
                TelegramDeliveryBinding(
                    channelId = channelId,
                    chatId = chatId,
                    destinationLabel = _profiles.value.firstOrNull { it.channelId == channelId }?.destinationLabel,
                )
            }
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
            address = null,
            configSummary = null,
            lastDeliveryAtEpochMillis = null,
            lastDeliveryError = null,
        )
    }

    override suspend fun refresh() {
        withContext(Dispatchers.IO) {
            databaseHelper.ensureDeliveryChannelSchema()
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
        address: String?,
        configSummary: String?,
        lastDeliveryAtEpochMillis: Long?,
        lastDeliveryError: String?,
    ) {
        withContext(Dispatchers.IO) {
            databaseHelper.ensureDeliveryChannelSchema()
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
                    put("address", address)
                    put("config_json", configSummary)
                    put("updated_at", System.currentTimeMillis())
                    if (lastDeliveryAtEpochMillis == null) {
                        putNull("last_delivery_at")
                    } else {
                        put("last_delivery_at", lastDeliveryAtEpochMillis)
                    }
                    if (lastDeliveryError.isNullOrBlank()) {
                        putNull("last_delivery_error")
                    } else {
                        put("last_delivery_error", lastDeliveryError)
                    }
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
                    putNull("address")
                    putNull("config_json")
                    putNull("last_delivery_at")
                    putNull("last_delivery_error")
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
                "address",
                "config_json",
                "updated_at",
                "last_delivery_at",
                "last_delivery_error",
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
                            address = cursor.getString(
                                cursor.getColumnIndexOrThrow("address"),
                            ),
                            configSummary = cursor.getString(
                                cursor.getColumnIndexOrThrow("config_json"),
                            ),
                            updatedAtEpochMillis = updatedAt,
                            updatedAtLabel = DateUtils.getRelativeTimeSpanString(
                                updatedAt,
                                now,
                                DateUtils.MINUTE_IN_MILLIS,
                            ).toString(),
                            lastDeliveryAtEpochMillis = cursor.optLong("last_delivery_at"),
                            lastDeliveryAtLabel = cursor.optLong("last_delivery_at")?.let { timestamp ->
                                DateUtils.getRelativeTimeSpanString(
                                    timestamp,
                                    now,
                                    DateUtils.MINUTE_IN_MILLIS,
                                ).toString()
                            },
                            lastDeliveryError = cursor.getString(
                                cursor.getColumnIndexOrThrow("last_delivery_error"),
                            ),
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
        private const val telegramChannelId = "telegram-bot-delivery"
    }
}

private fun android.database.Cursor.optLong(columnName: String): Long? {
    val index = getColumnIndex(columnName)
    if (index < 0 || isNull(index)) {
        return null
    }
    return getLong(index)
}
