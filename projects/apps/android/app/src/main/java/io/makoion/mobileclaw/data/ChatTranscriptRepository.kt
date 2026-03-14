package io.makoion.mobileclaw.data

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
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

enum class ChatMessageRole {
    User,
    Assistant,
}

data class ChatMessage(
    val id: String,
    val role: ChatMessageRole,
    val text: String,
    val linkedTaskId: String? = null,
    val linkedApprovalId: String? = null,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
)

data class ChatThreadRecord(
    val id: String,
    val title: String,
    val messageCount: Int,
    val lastMessagePreview: String? = null,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val createdAtLabel: String,
    val updatedAtLabel: String,
)

interface ChatTranscriptRepository {
    val activeThread: StateFlow<ChatThreadRecord?>
    val threads: StateFlow<List<ChatThreadRecord>>
    val messages: StateFlow<List<ChatMessage>>

    suspend fun appendMessage(
        message: ChatMessage,
        threadId: String? = null,
    ): ChatMessage

    suspend fun createThread(): ChatThreadRecord

    suspend fun activateThread(threadId: String): ChatThreadRecord?

    suspend fun refresh()
}

class PersistentChatTranscriptRepository(
    private val context: Context,
    private val databaseHelper: ShellDatabaseHelper,
) : ChatTranscriptRepository {
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _activeThread = MutableStateFlow<ChatThreadRecord?>(null)
    private val _threads = MutableStateFlow<List<ChatThreadRecord>>(emptyList())
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val preferences = context.getSharedPreferences(
        chatThreadPreferencesName,
        Context.MODE_PRIVATE,
    )

    override val activeThread: StateFlow<ChatThreadRecord?> = _activeThread.asStateFlow()
    override val threads: StateFlow<List<ChatThreadRecord>> = _threads.asStateFlow()
    override val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    init {
        repositoryScope.launch {
            refresh()
        }
    }

    override suspend fun appendMessage(
        message: ChatMessage,
        threadId: String?,
    ): ChatMessage {
        val resolvedThreadId = withContext(Dispatchers.IO) {
            threadId ?: resolveActiveThreadId()
        }
        withContext(Dispatchers.IO) {
            val db = databaseHelper.writableDatabase
            db.beginTransaction()
            try {
                ensureThreadExists(
                    db = db,
                    threadId = resolvedThreadId,
                    title = if (resolvedThreadId == primaryThreadId) primaryThreadTitle else newThreadTitle,
                    seedMessages = true,
                )
                val userMessageCountBeforeInsert = DatabaseUtils.longForQuery(
                    db,
                    "SELECT COUNT(*) FROM chat_messages WHERE thread_id = ? AND role = ?",
                    arrayOf(resolvedThreadId, ChatMessageRole.User.name),
                )
                val insertResult = db.insertWithOnConflict(
                    "chat_messages",
                    null,
                    ContentValues().apply {
                        put("id", message.id)
                        put("thread_id", resolvedThreadId)
                        put("role", message.role.name)
                        put("text", message.text)
                        put("linked_task_id", message.linkedTaskId)
                        put("linked_approval_id", message.linkedApprovalId)
                        put("created_at", message.createdAtEpochMillis)
                    },
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
                if (insertResult != -1L) {
                    val nextTitle = if (
                        message.role == ChatMessageRole.User &&
                        userMessageCountBeforeInsert == 0L &&
                        shouldAutoTitleThread(
                            threadId = resolvedThreadId,
                            currentTitle = queryThreadTitle(db, resolvedThreadId),
                        )
                    ) {
                        suggestedThreadTitle(message.text)
                    } else {
                        null
                    }
                    db.update(
                        "chat_threads",
                        ContentValues().apply {
                            put("updated_at", message.createdAtEpochMillis)
                            nextTitle?.let { put("title", it) }
                        },
                        "id = ?",
                        arrayOf(resolvedThreadId),
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        persistActiveThreadId(resolvedThreadId)
        refresh()
        return _messages.value.first { it.id == message.id }
    }

    override suspend fun createThread(): ChatThreadRecord {
        val threadId = "thread-${UUID.randomUUID()}"
        withContext(Dispatchers.IO) {
            val db = databaseHelper.writableDatabase
            db.beginTransaction()
            try {
                ensureThreadExists(
                    db = db,
                    threadId = threadId,
                    title = newThreadTitle,
                    seedMessages = true,
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            persistActiveThreadId(threadId)
        }
        refresh()
        return _activeThread.value ?: error("New thread $threadId was not created.")
    }

    override suspend fun activateThread(threadId: String): ChatThreadRecord? {
        val exists = withContext(Dispatchers.IO) {
            val db = databaseHelper.writableDatabase
            ensurePrimaryThreadSeeded(db)
            DatabaseUtils.longForQuery(
                db,
                "SELECT COUNT(*) FROM chat_threads WHERE id = ?",
                arrayOf(threadId),
            ) > 0L
        }
        if (!exists) {
            return null
        }
        persistActiveThreadId(threadId)
        refresh()
        return _activeThread.value
    }

    override suspend fun refresh() {
        withContext(Dispatchers.IO) {
            val threadId = resolveActiveThreadId()
            val now = System.currentTimeMillis()
            val threads = queryThreads(now)
            _threads.value = threads
            _activeThread.value = threads.firstOrNull { it.id == threadId }
                ?: threads.firstOrNull()?.also { persistActiveThreadId(it.id) }
            _messages.value = queryMessages(_activeThread.value?.id ?: threadId)
        }
    }

    private fun resolveActiveThreadId(): String {
        val db = databaseHelper.writableDatabase
        ensurePrimaryThreadSeeded(db)
        val persisted = preferences.getString(activeThreadKey, null)
        if (!persisted.isNullOrBlank()) {
            val exists = DatabaseUtils.longForQuery(
                db,
                "SELECT COUNT(*) FROM chat_threads WHERE id = ?",
                arrayOf(persisted),
            ) > 0L
            if (exists) {
                return persisted
            }
        }
        return primaryThreadId
    }

    private fun ensurePrimaryThreadSeeded(db: SQLiteDatabase): String {
        db.beginTransaction()
        try {
            ensureThreadExists(
                db = db,
                threadId = primaryThreadId,
                title = primaryThreadTitle,
                seedMessages = true,
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return primaryThreadId
    }

    private fun ensureThreadExists(
        db: SQLiteDatabase,
        threadId: String,
        title: String,
        seedMessages: Boolean,
    ) {
        val threadExists = DatabaseUtils.longForQuery(
            db,
            "SELECT COUNT(*) FROM chat_threads WHERE id = ?",
            arrayOf(threadId),
        ) > 0L
        if (!threadExists) {
            val now = System.currentTimeMillis()
            db.insert(
                "chat_threads",
                null,
                ContentValues().apply {
                    put("id", threadId)
                    put("title", title)
                    put("created_at", now)
                    put("updated_at", now)
                },
            )
        }
        if (!seedMessages) {
            return
        }
        val messageCount = DatabaseUtils.longForQuery(
            db,
            "SELECT COUNT(*) FROM chat_messages WHERE thread_id = ?",
            arrayOf(threadId),
        )
        if (messageCount == 0L) {
            val seedMessagesForThread = defaultSeedMessages(threadId)
            seedMessagesForThread.forEach { message ->
                db.insertWithOnConflict(
                    "chat_messages",
                    null,
                    ContentValues().apply {
                        put("id", message.id)
                        put("thread_id", threadId)
                        put("role", message.role.name)
                        put("text", message.text)
                        put("linked_task_id", message.linkedTaskId)
                        put("linked_approval_id", message.linkedApprovalId)
                        put("created_at", message.createdAtEpochMillis)
                    },
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
            }
            db.update(
                "chat_threads",
                ContentValues().apply {
                    put("updated_at", seedMessagesForThread.last().createdAtEpochMillis)
                },
                "id = ?",
                arrayOf(threadId),
            )
        }
    }

    private fun queryThreads(
        now: Long,
    ): List<ChatThreadRecord> {
        val threads = mutableListOf<ChatThreadRecord>()
        databaseHelper.readableDatabase.rawQuery(
            """
            SELECT
                chat_threads.id,
                chat_threads.title,
                chat_threads.created_at,
                chat_threads.updated_at,
                (
                    SELECT COUNT(*)
                    FROM chat_messages
                    WHERE chat_messages.thread_id = chat_threads.id
                ) AS message_count,
                (
                    SELECT text
                    FROM chat_messages
                    WHERE chat_messages.thread_id = chat_threads.id
                    ORDER BY created_at DESC, id DESC
                    LIMIT 1
                ) AS last_message_text
            FROM chat_threads
            ORDER BY updated_at DESC, created_at DESC
            LIMIT 12
            """.trimIndent(),
            emptyArray(),
        ).use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow("id")
            val titleIndex = cursor.getColumnIndexOrThrow("title")
            val createdAtIndex = cursor.getColumnIndexOrThrow("created_at")
            val updatedAtIndex = cursor.getColumnIndexOrThrow("updated_at")
            val messageCountIndex = cursor.getColumnIndexOrThrow("message_count")
            val lastMessageTextIndex = cursor.getColumnIndexOrThrow("last_message_text")

            while (cursor.moveToNext()) {
                val createdAt = cursor.getLong(createdAtIndex)
                val updatedAt = cursor.getLong(updatedAtIndex)
                threads += ChatThreadRecord(
                    id = cursor.getString(idIndex),
                    title = cursor.getString(titleIndex),
                    messageCount = cursor.getInt(messageCountIndex),
                    lastMessagePreview = cursor.getString(lastMessageTextIndex)?.trim()?.take(120),
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
        return threads
    }

    private fun queryMessages(threadId: String): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        databaseHelper.readableDatabase.query(
            "chat_messages",
            arrayOf(
                "id",
                "role",
                "text",
                "linked_task_id",
                "linked_approval_id",
                "created_at",
            ),
            "thread_id = ?",
            arrayOf(threadId),
            null,
            null,
            "created_at ASC, id ASC",
        ).use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow("id")
            val roleIndex = cursor.getColumnIndexOrThrow("role")
            val textIndex = cursor.getColumnIndexOrThrow("text")
            val linkedTaskIdIndex = cursor.getColumnIndexOrThrow("linked_task_id")
            val linkedApprovalIdIndex = cursor.getColumnIndexOrThrow("linked_approval_id")
            val createdAtIndex = cursor.getColumnIndexOrThrow("created_at")

            while (cursor.moveToNext()) {
                messages += ChatMessage(
                    id = cursor.getString(idIndex),
                    role = ChatMessageRole.valueOf(cursor.getString(roleIndex)),
                    text = cursor.getString(textIndex),
                    linkedTaskId = cursor.getString(linkedTaskIdIndex),
                    linkedApprovalId = cursor.getString(linkedApprovalIdIndex),
                    createdAtEpochMillis = cursor.getLong(createdAtIndex),
                )
            }
        }
        return messages
    }

    private fun defaultSeedMessages(threadId: String): List<ChatMessage> {
        val now = System.currentTimeMillis()
        return listOf(
            ChatMessage(
                id = "$threadId-assistant-welcome",
                role = ChatMessageRole.Assistant,
                text = "Makoion lives on this phone and will use your connected resources on your behalf.",
                createdAtEpochMillis = now,
            ),
            ChatMessage(
                id = "$threadId-assistant-guidance",
                role = ChatMessageRole.Assistant,
                text = "Ask for file summaries, organize planning, companion transfer approvals, approval decisions, failed-task retries, dashboard status, history, settings, or companion surfaces.",
                createdAtEpochMillis = now + 1L,
            ),
        )
    }

    private fun queryThreadTitle(
        db: SQLiteDatabase,
        threadId: String,
    ): String? {
        return db.query(
            "chat_threads",
            arrayOf("title"),
            "id = ?",
            arrayOf(threadId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndexOrThrow("title"))
            } else {
                null
            }
        }
    }

    private fun shouldAutoTitleThread(
        threadId: String,
        currentTitle: String?,
    ): Boolean {
        if (currentTitle.isNullOrBlank()) {
            return true
        }
        return threadId == primaryThreadId && currentTitle == primaryThreadTitle ||
            currentTitle == newThreadTitle
    }

    private fun suggestedThreadTitle(text: String): String {
        val normalized = text
            .trim()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("""^[^\p{L}\p{N}]+"""), "")
            .take(maxThreadTitleLength)
            .trim(' ', '.', ',', '!', '?', ':', ';', '-', '_')
        if (normalized.isBlank()) {
            return newThreadTitle
        }
        return normalized
    }

    private fun persistActiveThreadId(threadId: String) {
        preferences.edit()
            .putString(activeThreadKey, threadId)
            .apply()
    }

    companion object {
        private const val primaryThreadId = "thread-primary"
        private const val primaryThreadTitle = "Primary session"
        private const val newThreadTitle = "New session"
        private const val chatThreadPreferencesName = "makoion_chat_threads"
        private const val activeThreadKey = "active_thread_id"
        private const val maxThreadTitleLength = 56
    }
}
