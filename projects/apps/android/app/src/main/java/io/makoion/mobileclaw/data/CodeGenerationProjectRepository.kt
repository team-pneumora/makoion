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

enum class CodeGenerationProjectStatus {
    Planned,
    Scoped,
    Ready,
    Blocked,
}

data class CodeGenerationProjectRecord(
    val id: String,
    val title: String,
    val prompt: String,
    val targetLabel: String,
    val workspaceLabel: String,
    val outputLabel: String,
    val summary: String,
    val status: CodeGenerationProjectStatus,
    val workspacePath: String?,
    val entryFilePath: String?,
    val generatedFileCount: Int,
    val generatorLabel: String?,
    val createdAtEpochMillis: Long,
    val createdAtLabel: String,
    val updatedAtLabel: String,
)

interface CodeGenerationProjectRepository {
    val projects: StateFlow<List<CodeGenerationProjectRecord>>

    suspend fun createProject(
        prompt: String,
        plan: CodeGenerationProjectPlan,
        artifact: CodeGenerationWorkspaceArtifact? = null,
    ): CodeGenerationProjectRecord

    suspend fun setStatus(
        projectId: String,
        status: CodeGenerationProjectStatus,
    ): CodeGenerationProjectRecord?

    suspend fun refresh()
}

class PersistentCodeGenerationProjectRepository(
    private val databaseHelper: ShellDatabaseHelper,
    private val auditTrailRepository: AuditTrailRepository,
) : CodeGenerationProjectRepository {
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _projects = MutableStateFlow<List<CodeGenerationProjectRecord>>(emptyList())

    override val projects: StateFlow<List<CodeGenerationProjectRecord>> = _projects.asStateFlow()

    init {
        repositoryScope.launch {
            refresh()
        }
    }

    override suspend fun createProject(
        prompt: String,
        plan: CodeGenerationProjectPlan,
        artifact: CodeGenerationWorkspaceArtifact?,
    ): CodeGenerationProjectRecord {
        val projectId = "codegen-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val summary = buildCodeGenerationSummary(
            plan = plan,
            artifact = artifact,
        )
        val initialStatus = if (artifact == null) {
            CodeGenerationProjectStatus.Planned
        } else {
            CodeGenerationProjectStatus.Ready
        }
        withContext(Dispatchers.IO) {
            databaseHelper.writableDatabase.insert(
                projectTable,
                null,
                ContentValues().apply {
                    put("id", projectId)
                    put("title", plan.title)
                    put("prompt", prompt)
                    put("target_label", plan.targetLabel)
                    put("workspace_label", plan.workspaceLabel)
                    put("output_label", plan.outputLabel)
                    put("summary", summary)
                    put("status", initialStatus.name)
                    put("workspace_path", artifact?.workspacePath)
                    put("entry_file_path", artifact?.entryFilePath)
                    put("generated_file_count", artifact?.generatedFileCount ?: 0)
                    put("generator_label", artifact?.generatorLabel.orEmpty())
                    put("created_at", now)
                    put("updated_at", now)
                },
            )
        }
        auditTrailRepository.logAction(
            action = "codegen.project",
            result = initialStatus.name.lowercase(),
            details = if (artifact == null) {
                "Recorded code generation skeleton ${plan.title} (${plan.targetLabel}, ${plan.workspaceLabel})."
            } else {
                "Generated code project ${plan.title} with ${artifact.generatedFileCount} file(s) at ${artifact.workspacePath}."
            },
        )
        refresh()
        return _projects.value.first { it.id == projectId }
    }

    override suspend fun setStatus(
        projectId: String,
        status: CodeGenerationProjectStatus,
    ): CodeGenerationProjectRecord? {
        withContext(Dispatchers.IO) {
            databaseHelper.writableDatabase.update(
                projectTable,
                ContentValues().apply {
                    put("status", status.name)
                    put("updated_at", System.currentTimeMillis())
                },
                "id = ?",
                arrayOf(projectId),
            )
        }
        auditTrailRepository.logAction(
            action = "codegen.project",
            result = status.name.lowercase(),
            details = "Updated code generation project $projectId to ${status.name}.",
        )
        refresh()
        return _projects.value.firstOrNull { it.id == projectId }
    }

    override suspend fun refresh() {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            _projects.value = databaseHelper.readableDatabase.query(
                projectTable,
                arrayOf(
                    "id",
                    "title",
                    "prompt",
                    "target_label",
                    "workspace_label",
                    "output_label",
                    "summary",
                    "status",
                    "workspace_path",
                    "entry_file_path",
                    "generated_file_count",
                    "generator_label",
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
                            CodeGenerationProjectRecord(
                                id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                                title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                                prompt = cursor.getString(cursor.getColumnIndexOrThrow("prompt")),
                                targetLabel = cursor.getString(cursor.getColumnIndexOrThrow("target_label")),
                                workspaceLabel = cursor.getString(cursor.getColumnIndexOrThrow("workspace_label")),
                                outputLabel = cursor.getString(cursor.getColumnIndexOrThrow("output_label")),
                                summary = cursor.getString(cursor.getColumnIndexOrThrow("summary")),
                                status = runCatching {
                                    CodeGenerationProjectStatus.valueOf(
                                        cursor.getString(cursor.getColumnIndexOrThrow("status")),
                                    )
                                }.getOrDefault(CodeGenerationProjectStatus.Planned),
                                workspacePath = cursor.getString(cursor.getColumnIndexOrThrow("workspace_path")),
                                entryFilePath = cursor.getString(cursor.getColumnIndexOrThrow("entry_file_path")),
                                generatedFileCount = cursor.getInt(cursor.getColumnIndexOrThrow("generated_file_count")),
                                generatorLabel = cursor.getString(cursor.getColumnIndexOrThrow("generator_label"))
                                    ?.takeIf { it.isNotBlank() },
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
        private const val projectTable = "code_generation_projects"
    }
}
