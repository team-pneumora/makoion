package io.makoion.mobileclaw.data

import android.content.ContentValues
import android.text.format.DateUtils
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ScheduledAutomationStatus {
    Planned,
    Active,
    Paused,
}

data class ScheduledAutomationRecord(
    val id: String,
    val title: String,
    val prompt: String,
    val scheduleLabel: String,
    val deliveryLabel: String,
    val summary: String,
    val status: ScheduledAutomationStatus,
    val createdAtEpochMillis: Long,
    val createdAtLabel: String,
    val updatedAtLabel: String,
)

interface ScheduledAutomationRepository {
    val automations: StateFlow<List<ScheduledAutomationRecord>>

    suspend fun createSkeleton(
        prompt: String,
        plan: ScheduledAutomationPlan,
    ): ScheduledAutomationRecord

    suspend fun setStatus(
        automationId: String,
        status: ScheduledAutomationStatus,
    ): ScheduledAutomationRecord?

    suspend fun refresh()
}

class PersistentScheduledAutomationRepository(
    private val databaseHelper: ShellDatabaseHelper,
    private val auditTrailRepository: AuditTrailRepository,
) : ScheduledAutomationRepository {
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _automations = MutableStateFlow<List<ScheduledAutomationRecord>>(emptyList())

    override val automations: StateFlow<List<ScheduledAutomationRecord>> = _automations.asStateFlow()

    init {
        repositoryScope.launch {
            refresh()
        }
    }

    override suspend fun createSkeleton(
        prompt: String,
        plan: ScheduledAutomationPlan,
    ): ScheduledAutomationRecord {
        val automationId = "automation-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val summary = buildScheduledAutomationSummary(plan)
        withContext(Dispatchers.IO) {
            databaseHelper.writableDatabase.insert(
                automationTable,
                null,
                ContentValues().apply {
                    put("id", automationId)
                    put("title", plan.title)
                    put("prompt", prompt)
                    put("schedule_label", plan.scheduleLabel)
                    put("delivery_label", plan.deliveryLabel)
                    put("summary", summary)
                    put("status", ScheduledAutomationStatus.Planned.name)
                    put("created_at", now)
                    put("updated_at", now)
                },
            )
        }
        auditTrailRepository.logAction(
            action = "automation.schedule",
            result = "planned",
            details = "Recorded automation skeleton ${plan.title} (${plan.scheduleLabel}, ${plan.deliveryLabel}).",
        )
        refresh()
        return _automations.value.first { it.id == automationId }
    }

    override suspend fun setStatus(
        automationId: String,
        status: ScheduledAutomationStatus,
    ): ScheduledAutomationRecord? {
        withContext(Dispatchers.IO) {
            databaseHelper.writableDatabase.update(
                automationTable,
                ContentValues().apply {
                    put("status", status.name)
                    put("updated_at", System.currentTimeMillis())
                },
                "id = ?",
                arrayOf(automationId),
            )
        }
        auditTrailRepository.logAction(
            action = "automation.schedule",
            result = status.name.lowercase(),
            details = "Updated automation $automationId to ${status.name}.",
        )
        refresh()
        return _automations.value.firstOrNull { it.id == automationId }
    }

    override suspend fun refresh() {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            _automations.value = databaseHelper.readableDatabase.query(
                automationTable,
                arrayOf(
                    "id",
                    "title",
                    "prompt",
                    "schedule_label",
                    "delivery_label",
                    "summary",
                    "status",
                    "created_at",
                    "updated_at",
                ),
                null,
                null,
                null,
                null,
                "updated_at DESC",
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))
                        val updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"))
                        add(
                            ScheduledAutomationRecord(
                                id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                                title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                                prompt = cursor.getString(cursor.getColumnIndexOrThrow("prompt")),
                                scheduleLabel = cursor.getString(cursor.getColumnIndexOrThrow("schedule_label")),
                                deliveryLabel = cursor.getString(cursor.getColumnIndexOrThrow("delivery_label")),
                                summary = cursor.getString(cursor.getColumnIndexOrThrow("summary")),
                                status = runCatching {
                                    ScheduledAutomationStatus.valueOf(
                                        cursor.getString(cursor.getColumnIndexOrThrow("status")),
                                    )
                                }.getOrDefault(ScheduledAutomationStatus.Planned),
                                createdAtEpochMillis = createdAt,
                                createdAtLabel = DateUtils.getRelativeTimeSpanString(
                                    createdAt,
                                    now,
                                    DateUtils.MINUTE_IN_MILLIS,
                                ).toString(),
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
    }

    companion object {
        private const val automationTable = "scheduled_automation_records"
    }
}

internal fun buildScheduledAutomationSummary(plan: ScheduledAutomationPlan): String {
    return "Recorded ${plan.scheduleLabel} delivery via ${plan.deliveryLabel}. Scheduler execution is still pending implementation."
}
