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
import org.json.JSONArray

const val defaultTaskActionKey = "agent.turn"

enum class AgentTaskStatus {
    Queued,
    Planning,
    WaitingUser,
    WaitingResource,
    Running,
    Paused,
    RetryScheduled,
    Succeeded,
    Failed,
    Cancelled,
}

data class AgentTaskRecord(
    val id: String,
    val threadId: String,
    val title: String,
    val prompt: String,
    val actionKey: String,
    val status: AgentTaskStatus,
    val summary: String,
    val replyPreview: String? = null,
    val plannerMode: AgentPlannerMode? = null,
    val plannerSummary: String? = null,
    val plannerCapabilities: List<String> = emptyList(),
    val plannerResources: List<String> = emptyList(),
    val destination: AgentDestination = AgentDestination.Chat,
    val approvalRequestId: String? = null,
    val retryCount: Int = 0,
    val maxRetryCount: Int = 0,
    val nextRetryAtEpochMillis: Long? = null,
    val nextRetryAtLabel: String? = null,
    val lastError: String? = null,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val createdAtLabel: String,
    val updatedAtLabel: String,
)

interface AgentTaskRepository {
    val tasks: StateFlow<List<AgentTaskRecord>>

    suspend fun createTask(
        threadId: String,
        prompt: String,
        title: String,
        summary: String,
        actionKey: String = defaultTaskActionKey,
        maxRetryCount: Int = 0,
    ): AgentTaskRecord

    suspend fun updateTask(
        taskId: String,
        status: AgentTaskStatus,
        title: String? = null,
        summary: String? = null,
        replyPreview: String? = null,
        destination: AgentDestination? = null,
        approvalRequestId: String? = null,
        actionKey: String? = null,
        planningTrace: AgentPlanningTrace? = null,
        retryCount: Int? = null,
        maxRetryCount: Int? = null,
        nextRetryAtEpochMillis: Long? = null,
        lastError: String? = null,
    ): AgentTaskRecord?

    suspend fun updateTaskByApprovalRequestId(
        approvalRequestId: String,
        status: AgentTaskStatus,
        summary: String? = null,
        replyPreview: String? = null,
        nextRetryAtEpochMillis: Long? = null,
        lastError: String? = null,
    ): AgentTaskRecord?

    suspend fun findTaskById(taskId: String): AgentTaskRecord?

    suspend fun findTaskByApprovalRequestId(approvalRequestId: String): AgentTaskRecord?

    suspend fun dueRetryTasks(now: Long = System.currentTimeMillis()): List<AgentTaskRecord>

    suspend fun nextRetryAtEpochMillis(now: Long = System.currentTimeMillis()): Long?

    suspend fun refresh()
}

class PersistentAgentTaskRepository(
    private val databaseHelper: ShellDatabaseHelper,
) : AgentTaskRepository {
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _tasks = MutableStateFlow<List<AgentTaskRecord>>(emptyList())

    override val tasks: StateFlow<List<AgentTaskRecord>> = _tasks.asStateFlow()

    init {
        repositoryScope.launch {
            refresh()
        }
    }

    override suspend fun createTask(
        threadId: String,
        prompt: String,
        title: String,
        summary: String,
        actionKey: String,
        maxRetryCount: Int,
    ): AgentTaskRecord {
        val createdTaskId = "task-${UUID.randomUUID()}"
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            databaseHelper.writableDatabase.insert(
                "agent_tasks",
                null,
                ContentValues().apply {
                    put("id", createdTaskId)
                    put("thread_id", threadId)
                    put("title", title)
                    put("prompt", prompt)
                    put("action_key", actionKey)
                    put("status", AgentTaskStatus.Queued.name)
                    put("summary", summary)
                    put("destination", AgentDestination.Chat.name)
                    put("retry_count", 0)
                    put("max_retry_count", maxRetryCount)
                    put("created_at", now)
                    put("updated_at", now)
                },
            )
        }
        refresh()
        return _tasks.value.first { it.id == createdTaskId }
    }

    override suspend fun updateTask(
        taskId: String,
        status: AgentTaskStatus,
        title: String?,
        summary: String?,
        replyPreview: String?,
        destination: AgentDestination?,
        approvalRequestId: String?,
        actionKey: String?,
        planningTrace: AgentPlanningTrace?,
        retryCount: Int?,
        maxRetryCount: Int?,
        nextRetryAtEpochMillis: Long?,
        lastError: String?,
    ): AgentTaskRecord? {
        withContext(Dispatchers.IO) {
            val existing = queryTaskById(taskId) ?: return@withContext
            val resolvedStatus = status
            databaseHelper.writableDatabase.update(
                "agent_tasks",
                ContentValues().apply {
                    put("status", resolvedStatus.name)
                    put("title", title ?: existing.title)
                    put("summary", summary ?: existing.summary)
                    put("reply_preview", replyPreview ?: existing.replyPreview)
                    put("destination", (destination ?: existing.destination).name)
                    put("approval_request_id", approvalRequestId ?: existing.approvalRequestId)
                    put("action_key", actionKey ?: existing.actionKey)
                    put("planner_mode", planningTrace?.mode?.name ?: existing.plannerMode?.name)
                    put("planner_summary", planningTrace?.summary ?: existing.plannerSummary)
                    put(
                        "planner_capabilities_json",
                        planningTrace?.let { trace -> jsonArrayString(trace.capabilities) }
                            ?: jsonArrayString(existing.plannerCapabilities),
                    )
                    put(
                        "planner_resources_json",
                        planningTrace?.let { trace -> jsonArrayString(trace.resources) }
                            ?: jsonArrayString(existing.plannerResources),
                    )
                    put("retry_count", retryCount ?: existing.retryCount)
                    put("max_retry_count", maxRetryCount ?: existing.maxRetryCount)
                    if (resolvedStatus == AgentTaskStatus.RetryScheduled) {
                        put("next_retry_at", nextRetryAtEpochMillis ?: existing.nextRetryAtEpochMillis)
                    } else {
                        putNull("next_retry_at")
                    }
                    if (lastError != null) {
                        put("last_error", lastError)
                    } else if (resolvedStatus == AgentTaskStatus.RetryScheduled) {
                        put("last_error", existing.lastError)
                    } else {
                        putNull("last_error")
                    }
                    put("updated_at", System.currentTimeMillis())
                },
                "id = ?",
                arrayOf(taskId),
            )
        }
        refresh()
        return _tasks.value.firstOrNull { it.id == taskId }
    }

    override suspend fun updateTaskByApprovalRequestId(
        approvalRequestId: String,
        status: AgentTaskStatus,
        summary: String?,
        replyPreview: String?,
        nextRetryAtEpochMillis: Long?,
        lastError: String?,
    ): AgentTaskRecord? {
        withContext(Dispatchers.IO) {
            val existing = queryTaskByApprovalRequestId(approvalRequestId) ?: return@withContext
            databaseHelper.writableDatabase.update(
                "agent_tasks",
                ContentValues().apply {
                    put("status", status.name)
                    put("summary", summary ?: existing.summary)
                    put("reply_preview", replyPreview ?: existing.replyPreview)
                    if (status == AgentTaskStatus.RetryScheduled) {
                        put("next_retry_at", nextRetryAtEpochMillis ?: existing.nextRetryAtEpochMillis)
                    } else {
                        putNull("next_retry_at")
                    }
                    if (lastError != null) {
                        put("last_error", lastError)
                    } else if (status != AgentTaskStatus.RetryScheduled) {
                        putNull("last_error")
                    }
                    put("updated_at", System.currentTimeMillis())
                },
                "approval_request_id = ?",
                arrayOf(approvalRequestId),
            )
        }
        refresh()
        return _tasks.value.firstOrNull { it.approvalRequestId == approvalRequestId }
    }

    override suspend fun findTaskById(taskId: String): AgentTaskRecord? {
        return withContext(Dispatchers.IO) {
            queryTaskById(taskId)
        }
    }

    override suspend fun findTaskByApprovalRequestId(approvalRequestId: String): AgentTaskRecord? {
        return withContext(Dispatchers.IO) {
            queryTaskByApprovalRequestId(approvalRequestId)
        }
    }

    override suspend fun dueRetryTasks(now: Long): List<AgentTaskRecord> {
        return withContext(Dispatchers.IO) {
            queryTasks(
                selection = "status = ? AND next_retry_at IS NOT NULL AND next_retry_at <= ?",
                selectionArgs = arrayOf(AgentTaskStatus.RetryScheduled.name, now.toString()),
            )
        }
    }

    override suspend fun nextRetryAtEpochMillis(now: Long): Long? {
        return withContext(Dispatchers.IO) {
            databaseHelper.readableDatabase.rawQuery(
                """
                SELECT MIN(next_retry_at)
                FROM agent_tasks
                WHERE status = ? AND next_retry_at IS NOT NULL AND next_retry_at >= ?
                """.trimIndent(),
                arrayOf(AgentTaskStatus.RetryScheduled.name, now.toString()),
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getLong(0).takeIf { it > 0L }
                } else {
                    null
                }
            }
        }
    }

    override suspend fun refresh() {
        _tasks.value = withContext(Dispatchers.IO) {
            queryTasks()
        }
    }

    private fun queryTasks(): List<AgentTaskRecord> {
        return queryTasks(
            selection = null,
            selectionArgs = null,
        )
    }

    private fun queryTasks(
        selection: String?,
        selectionArgs: Array<String>?,
    ): List<AgentTaskRecord> {
        val now = System.currentTimeMillis()
        val tasks = mutableListOf<AgentTaskRecord>()
        databaseHelper.readableDatabase.query(
            "agent_tasks",
            arrayOf(
                "id",
                "thread_id",
                "title",
                "prompt",
                "action_key",
                "status",
                "summary",
                "reply_preview",
                "planner_mode",
                "planner_summary",
                "planner_capabilities_json",
                "planner_resources_json",
                "destination",
                "approval_request_id",
                "retry_count",
                "max_retry_count",
                "next_retry_at",
                "last_error",
                "created_at",
                "updated_at",
            ),
            selection,
            selectionArgs,
            null,
            null,
            "updated_at DESC",
            "24",
        ).use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow("id")
            val threadIdIndex = cursor.getColumnIndexOrThrow("thread_id")
            val titleIndex = cursor.getColumnIndexOrThrow("title")
            val promptIndex = cursor.getColumnIndexOrThrow("prompt")
            val actionKeyIndex = cursor.getColumnIndexOrThrow("action_key")
            val statusIndex = cursor.getColumnIndexOrThrow("status")
            val summaryIndex = cursor.getColumnIndexOrThrow("summary")
            val replyPreviewIndex = cursor.getColumnIndexOrThrow("reply_preview")
            val plannerModeIndex = cursor.getColumnIndexOrThrow("planner_mode")
            val plannerSummaryIndex = cursor.getColumnIndexOrThrow("planner_summary")
            val plannerCapabilitiesIndex = cursor.getColumnIndexOrThrow("planner_capabilities_json")
            val plannerResourcesIndex = cursor.getColumnIndexOrThrow("planner_resources_json")
            val destinationIndex = cursor.getColumnIndexOrThrow("destination")
            val approvalRequestIdIndex = cursor.getColumnIndexOrThrow("approval_request_id")
            val retryCountIndex = cursor.getColumnIndexOrThrow("retry_count")
            val maxRetryCountIndex = cursor.getColumnIndexOrThrow("max_retry_count")
            val nextRetryAtIndex = cursor.getColumnIndexOrThrow("next_retry_at")
            val lastErrorIndex = cursor.getColumnIndexOrThrow("last_error")
            val createdAtIndex = cursor.getColumnIndexOrThrow("created_at")
            val updatedAtIndex = cursor.getColumnIndexOrThrow("updated_at")

            while (cursor.moveToNext()) {
                val createdAt = cursor.getLong(createdAtIndex)
                val updatedAt = cursor.getLong(updatedAtIndex)
                val nextRetryAt = cursor.getLong(nextRetryAtIndex).takeIf {
                    !cursor.isNull(nextRetryAtIndex) && it > 0L
                }
                tasks += AgentTaskRecord(
                    id = cursor.getString(idIndex),
                    threadId = cursor.getString(threadIdIndex),
                    title = cursor.getString(titleIndex),
                    prompt = cursor.getString(promptIndex),
                    actionKey = cursor.getString(actionKeyIndex),
                    status = AgentTaskStatus.valueOf(cursor.getString(statusIndex)),
                    summary = cursor.getString(summaryIndex),
                    replyPreview = cursor.getString(replyPreviewIndex),
                    plannerMode = cursor.getString(plannerModeIndex)?.takeIf { it.isNotBlank() }?.let {
                        AgentPlannerMode.valueOf(it)
                    },
                    plannerSummary = cursor.getString(plannerSummaryIndex),
                    plannerCapabilities = jsonArrayToList(cursor.getString(plannerCapabilitiesIndex)),
                    plannerResources = jsonArrayToList(cursor.getString(plannerResourcesIndex)),
                    destination = runCatching {
                        AgentDestination.valueOf(cursor.getString(destinationIndex))
                    }.getOrDefault(AgentDestination.Chat),
                    approvalRequestId = cursor.getString(approvalRequestIdIndex),
                    retryCount = cursor.getInt(retryCountIndex),
                    maxRetryCount = cursor.getInt(maxRetryCountIndex),
                    nextRetryAtEpochMillis = nextRetryAt,
                    nextRetryAtLabel = nextRetryAt?.let {
                        DateUtils.getRelativeTimeSpanString(
                            it,
                            now,
                            DateUtils.SECOND_IN_MILLIS,
                        ).toString()
                    },
                    lastError = cursor.getString(lastErrorIndex),
                    createdAtEpochMillis = createdAt,
                    updatedAtEpochMillis = updatedAt,
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
                )
            }
        }
        return tasks
    }

    private fun queryTaskById(taskId: String): AgentTaskRecord? {
        return querySingleTask(
            selection = "id = ?",
            selectionArgs = arrayOf(taskId),
        )
    }

    private fun queryTaskByApprovalRequestId(approvalRequestId: String): AgentTaskRecord? {
        return querySingleTask(
            selection = "approval_request_id = ?",
            selectionArgs = arrayOf(approvalRequestId),
        )
    }

    private fun querySingleTask(
        selection: String,
        selectionArgs: Array<String>,
    ): AgentTaskRecord? {
        val now = System.currentTimeMillis()
        databaseHelper.readableDatabase.query(
            "agent_tasks",
            arrayOf(
                "id",
                "thread_id",
                "title",
                "prompt",
                "action_key",
                "status",
                "summary",
                "reply_preview",
                "planner_mode",
                "planner_summary",
                "planner_capabilities_json",
                "planner_resources_json",
                "destination",
                "approval_request_id",
                "retry_count",
                "max_retry_count",
                "next_retry_at",
                "last_error",
                "created_at",
                "updated_at",
            ),
            selection,
            selectionArgs,
            null,
            null,
            "updated_at DESC",
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            val nextRetryAtIndex = cursor.getColumnIndexOrThrow("next_retry_at")
            val nextRetryAt = cursor.getLong(nextRetryAtIndex).takeIf {
                !cursor.isNull(nextRetryAtIndex) && it > 0L
            }
            return AgentTaskRecord(
                id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                threadId = cursor.getString(cursor.getColumnIndexOrThrow("thread_id")),
                title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                prompt = cursor.getString(cursor.getColumnIndexOrThrow("prompt")),
                actionKey = cursor.getString(cursor.getColumnIndexOrThrow("action_key")),
                status = AgentTaskStatus.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("status"))),
                summary = cursor.getString(cursor.getColumnIndexOrThrow("summary")),
                replyPreview = cursor.getString(cursor.getColumnIndexOrThrow("reply_preview")),
                plannerMode = cursor.getString(cursor.getColumnIndexOrThrow("planner_mode"))
                    ?.takeIf { it.isNotBlank() }
                    ?.let { AgentPlannerMode.valueOf(it) },
                plannerSummary = cursor.getString(cursor.getColumnIndexOrThrow("planner_summary")),
                plannerCapabilities = jsonArrayToList(
                    cursor.getString(cursor.getColumnIndexOrThrow("planner_capabilities_json")),
                ),
                plannerResources = jsonArrayToList(
                    cursor.getString(cursor.getColumnIndexOrThrow("planner_resources_json")),
                ),
                destination = runCatching {
                    AgentDestination.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("destination")))
                }.getOrDefault(AgentDestination.Chat),
                approvalRequestId = cursor.getString(cursor.getColumnIndexOrThrow("approval_request_id")),
                retryCount = cursor.getInt(cursor.getColumnIndexOrThrow("retry_count")),
                maxRetryCount = cursor.getInt(cursor.getColumnIndexOrThrow("max_retry_count")),
                nextRetryAtEpochMillis = nextRetryAt,
                nextRetryAtLabel = nextRetryAt?.let {
                    DateUtils.getRelativeTimeSpanString(
                        it,
                        now,
                        DateUtils.SECOND_IN_MILLIS,
                    ).toString()
                },
                lastError = cursor.getString(cursor.getColumnIndexOrThrow("last_error")),
                createdAtEpochMillis = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                updatedAtEpochMillis = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
                createdAtLabel = DateUtils.getRelativeTimeSpanString(
                    cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                    now,
                    DateUtils.MINUTE_IN_MILLIS,
                ).toString(),
                updatedAtLabel = DateUtils.getRelativeTimeSpanString(
                    cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
                    now,
                    DateUtils.MINUTE_IN_MILLIS,
                ).toString(),
            )
        }
    }

    private fun jsonArrayString(values: List<String>): String {
        return JSONArray(values).toString()
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
}
