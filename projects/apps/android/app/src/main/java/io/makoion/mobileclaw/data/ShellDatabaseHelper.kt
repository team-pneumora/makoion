package io.makoion.mobileclaw.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class ShellDatabaseHelper(
    context: Context,
) : SQLiteOpenHelper(context, databaseName, null, databaseVersion) {
    override fun onCreate(db: SQLiteDatabase) {
        createApprovalTables(db)
        createAuditTables(db)
        createDeviceTables(db)
        createOrganizeExecutionTables(db)
        createAgentTaskTables(db)
        createChatTranscriptTables(db)
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) {
        if (oldVersion < 2) {
            createDeviceTables(db)
        }
        if (oldVersion >= 2 && oldVersion < 3) {
            db.execSQL(
                """
                ALTER TABLE transfer_outbox
                ADD COLUMN transport_endpoint TEXT
                """.trimIndent(),
            )
            db.execSQL(
                """
                ALTER TABLE transfer_outbox
                ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0
                """.trimIndent(),
            )
            db.execSQL(
                """
                ALTER TABLE transfer_outbox
                ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0
                """.trimIndent(),
            )
            db.execSQL(
                """
                ALTER TABLE transfer_outbox
                ADD COLUMN delivered_at INTEGER
                """.trimIndent(),
            )
            db.execSQL(
                """
                ALTER TABLE transfer_outbox
                ADD COLUMN last_error TEXT
                """.trimIndent(),
            )
            db.execSQL(
                """
                UPDATE transfer_outbox
                SET updated_at = created_at
                WHERE updated_at = 0
                """.trimIndent(),
            )
        }
        if (oldVersion >= 2 && oldVersion < 4) {
            db.execSQL(
                """
                ALTER TABLE paired_devices
                ADD COLUMN transport_mode TEXT NOT NULL DEFAULT 'Loopback'
                """.trimIndent(),
            )
            db.execSQL(
                """
                ALTER TABLE paired_devices
                ADD COLUMN endpoint_url TEXT
                """.trimIndent(),
            )
            db.execSQL(
                """
                ALTER TABLE paired_devices
                ADD COLUMN trusted_secret TEXT
                """.trimIndent(),
            )
        }
        if (oldVersion >= 2 && oldVersion < 5) {
            db.execSQL(
                """
                ALTER TABLE transfer_outbox
                ADD COLUMN file_refs_json TEXT
                """.trimIndent(),
            )
        }
        if (oldVersion >= 2 && oldVersion < 6) {
            db.execSQL(
                """
                ALTER TABLE transfer_outbox
                ADD COLUMN delivery_mode TEXT
                """.trimIndent(),
            )
            db.execSQL(
                """
                ALTER TABLE transfer_outbox
                ADD COLUMN receipt_json TEXT
                """.trimIndent(),
            )
        }
        if (oldVersion >= 2 && oldVersion < 7) {
            db.execSQL(
                """
                ALTER TABLE approval_requests
                ADD COLUMN intent_payload_json TEXT
                """.trimIndent(),
            )
        }
        if (oldVersion >= 2 && oldVersion < 8) {
            db.execSQL(
                """
                ALTER TABLE transfer_outbox
                ADD COLUMN next_attempt_at INTEGER NOT NULL DEFAULT 0
                """.trimIndent(),
            )
            db.execSQL(
                """
                UPDATE transfer_outbox
                SET next_attempt_at = updated_at
                WHERE next_attempt_at = 0
                """.trimIndent(),
            )
        }
        if (oldVersion >= 2 && oldVersion < 9) {
            db.execSQL(
                """
                ALTER TABLE paired_devices
                ADD COLUMN validation_mode TEXT NOT NULL DEFAULT 'Normal'
                """.trimIndent(),
            )
        }
        if (oldVersion < 10) {
            createOrganizeExecutionTables(db)
        }
        if (oldVersion < 11) {
            createAgentTaskTables(db)
        }
        if (oldVersion < 12) {
            addColumnIfMissing(
                db,
                tableName = "agent_tasks",
                columnName = "action_key",
                columnDefinition = "TEXT NOT NULL DEFAULT 'agent.turn'",
            )
            addColumnIfMissing(
                db,
                tableName = "agent_tasks",
                columnName = "retry_count",
                columnDefinition = "INTEGER NOT NULL DEFAULT 0",
            )
            addColumnIfMissing(
                db,
                tableName = "agent_tasks",
                columnName = "max_retry_count",
                columnDefinition = "INTEGER NOT NULL DEFAULT 0",
            )
            addColumnIfMissing(
                db,
                tableName = "agent_tasks",
                columnName = "next_retry_at",
                columnDefinition = "INTEGER",
            )
            addColumnIfMissing(
                db,
                tableName = "agent_tasks",
                columnName = "last_error",
                columnDefinition = "TEXT",
            )
        }
        if (oldVersion < 13) {
            db.execSQL(
                """
                ALTER TABLE transfer_outbox
                ADD COLUMN approval_request_id TEXT
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS idx_transfer_outbox_approval_request_id
                ON transfer_outbox(approval_request_id)
                """.trimIndent(),
            )
        }
        if (oldVersion < 14) {
            createChatTranscriptTables(db)
        }
        if (oldVersion < 15) {
            addColumnIfMissing(
                db,
                tableName = "agent_tasks",
                columnName = "thread_id",
                columnDefinition = "TEXT NOT NULL DEFAULT 'thread-primary'",
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS idx_agent_tasks_thread_id
                ON agent_tasks(thread_id)
                """.trimIndent(),
            )
        }
        if (oldVersion < 16) {
            addColumnIfMissing(
                db,
                tableName = "agent_tasks",
                columnName = "planner_mode",
                columnDefinition = "TEXT",
            )
            addColumnIfMissing(
                db,
                tableName = "agent_tasks",
                columnName = "planner_summary",
                columnDefinition = "TEXT",
            )
            addColumnIfMissing(
                db,
                tableName = "agent_tasks",
                columnName = "planner_capabilities_json",
                columnDefinition = "TEXT NOT NULL DEFAULT '[]'",
            )
            addColumnIfMissing(
                db,
                tableName = "agent_tasks",
                columnName = "planner_resources_json",
                columnDefinition = "TEXT NOT NULL DEFAULT '[]'",
            )
        }
    }

    private fun createApprovalTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE approval_requests (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                intent_action TEXT NOT NULL,
                assessed_risk TEXT NOT NULL,
                summary TEXT NOT NULL,
                intent_payload_json TEXT,
                requested_at INTEGER NOT NULL,
                status TEXT NOT NULL,
                decided_at INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX idx_approval_requests_status
            ON approval_requests(status)
            """.trimIndent(),
        )
    }

    private fun createAuditTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE audit_events (
                id TEXT PRIMARY KEY,
                request_id TEXT,
                action TEXT NOT NULL,
                result TEXT NOT NULL,
                details TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX idx_audit_events_created_at
            ON audit_events(created_at DESC)
            """.trimIndent(),
        )
    }

    private fun createDeviceTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE paired_devices (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                role TEXT NOT NULL,
                status TEXT NOT NULL,
                capabilities_json TEXT NOT NULL,
                transport_mode TEXT NOT NULL DEFAULT 'Loopback',
                endpoint_url TEXT,
                trusted_secret TEXT,
                validation_mode TEXT NOT NULL DEFAULT 'Normal',
                paired_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE pairing_sessions (
                id TEXT PRIMARY KEY,
                requested_role TEXT NOT NULL,
                qr_secret TEXT NOT NULL,
                requested_capabilities TEXT NOT NULL,
                status TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                decided_at INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE transfer_outbox (
                id TEXT PRIMARY KEY,
                device_id TEXT NOT NULL,
                device_name TEXT NOT NULL,
                file_names_json TEXT NOT NULL,
                approval_request_id TEXT,
                status TEXT NOT NULL,
                transport_endpoint TEXT,
                attempt_count INTEGER NOT NULL DEFAULT 0,
                file_refs_json TEXT,
                delivery_mode TEXT,
                receipt_json TEXT,
                next_attempt_at INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                delivered_at INTEGER,
                last_error TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX idx_pairing_sessions_status
            ON pairing_sessions(status)
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX idx_transfer_outbox_status
            ON transfer_outbox(status)
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX idx_transfer_outbox_approval_request_id
            ON transfer_outbox(approval_request_id)
            """.trimIndent(),
        )
    }

    private fun createOrganizeExecutionTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS organize_executions (
                approval_id TEXT PRIMARY KEY,
                result_json TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_organize_executions_updated_at
            ON organize_executions(updated_at DESC)
            """.trimIndent(),
        )
    }

    private fun createAgentTaskTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS agent_tasks (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                prompt TEXT NOT NULL,
                action_key TEXT NOT NULL DEFAULT 'agent.turn',
                thread_id TEXT NOT NULL DEFAULT 'thread-primary',
                status TEXT NOT NULL,
                summary TEXT NOT NULL,
                reply_preview TEXT,
                planner_mode TEXT,
                planner_summary TEXT,
                planner_capabilities_json TEXT NOT NULL DEFAULT '[]',
                planner_resources_json TEXT NOT NULL DEFAULT '[]',
                destination TEXT NOT NULL,
                approval_request_id TEXT,
                retry_count INTEGER NOT NULL DEFAULT 0,
                max_retry_count INTEGER NOT NULL DEFAULT 0,
                next_retry_at INTEGER,
                last_error TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_agent_tasks_status
            ON agent_tasks(status)
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_agent_tasks_updated_at
            ON agent_tasks(updated_at DESC)
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_agent_tasks_approval_request_id
            ON agent_tasks(approval_request_id)
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_agent_tasks_thread_id
            ON agent_tasks(thread_id)
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_agent_tasks_next_retry_at
            ON agent_tasks(next_retry_at)
            """.trimIndent(),
        )
    }

    private fun createChatTranscriptTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chat_threads (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chat_messages (
                id TEXT PRIMARY KEY,
                thread_id TEXT NOT NULL,
                role TEXT NOT NULL,
                text TEXT NOT NULL,
                linked_task_id TEXT,
                linked_approval_id TEXT,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_chat_threads_updated_at
            ON chat_threads(updated_at DESC)
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_chat_messages_thread_created_at
            ON chat_messages(thread_id, created_at ASC)
            """.trimIndent(),
        )
    }

    private fun addColumnIfMissing(
        db: SQLiteDatabase,
        tableName: String,
        columnName: String,
        columnDefinition: String,
    ) {
        if (tableHasColumn(db, tableName, columnName)) {
            return
        }
        db.execSQL(
            """
            ALTER TABLE $tableName
            ADD COLUMN $columnName $columnDefinition
            """.trimIndent(),
        )
    }

    private fun tableHasColumn(
        db: SQLiteDatabase,
        tableName: String,
        columnName: String,
    ): Boolean {
        db.rawQuery("PRAGMA table_info($tableName)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex).equals(columnName, ignoreCase = true)) {
                    return true
                }
            }
        }
        return false
    }

    companion object {
        private const val databaseName = "mobileclaw_shell.db"
        private const val databaseVersion = 16
    }
}
