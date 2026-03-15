package io.makoion.mobileclaw

import android.app.Application
import io.makoion.mobileclaw.data.AndroidFileIndexRepository
import io.makoion.mobileclaw.data.AgentTaskEngine
import io.makoion.mobileclaw.data.AgentTaskRetryCoordinator
import io.makoion.mobileclaw.data.PersistentCloudDriveConnectionRepository
import io.makoion.mobileclaw.data.FileActionExecutor
import io.makoion.mobileclaw.data.LocalFileGraphActionPlanner
import io.makoion.mobileclaw.data.PersistentChatTranscriptRepository
import io.makoion.mobileclaw.data.PersistentAgentTaskRepository
import io.makoion.mobileclaw.data.PhoneAgentActionCoordinator
import io.makoion.mobileclaw.data.PersistentModelProviderSettingsRepository
import io.makoion.mobileclaw.data.PersistentOrganizeDebugSettingsRepository
import io.makoion.mobileclaw.data.PersistentOrganizeExecutionRepository
import io.makoion.mobileclaw.data.PersistentResourceRegistryRepository
import io.makoion.mobileclaw.data.VoiceEntryCoordinator
import io.makoion.mobileclaw.data.PersistentApprovalInboxRepository
import io.makoion.mobileclaw.data.PersistentAuditTrailRepository
import io.makoion.mobileclaw.data.PersistentDevicePairingRepository
import io.makoion.mobileclaw.data.LocalPhoneAgentRuntime
import io.makoion.mobileclaw.data.ShellDatabaseHelper
import io.makoion.mobileclaw.data.ShellRecoveryCoordinator
import io.makoion.mobileclaw.data.TransferBridgeCoordinator

class ShellAppContainer(
    application: Application,
) {
    private val databaseHelper = ShellDatabaseHelper(application)

    val fileIndexRepository = AndroidFileIndexRepository(application)
    val fileGraphActionPlanner = LocalFileGraphActionPlanner()
    val organizeDebugSettingsRepository = PersistentOrganizeDebugSettingsRepository(application)
    val auditTrailRepository = PersistentAuditTrailRepository(
        context = application,
        databaseHelper = databaseHelper,
    )
    val agentTaskRepository = PersistentAgentTaskRepository(
        databaseHelper = databaseHelper,
    )
    val chatTranscriptRepository = PersistentChatTranscriptRepository(
        context = application,
        databaseHelper = databaseHelper,
    )
    val cloudDriveConnectionRepository = PersistentCloudDriveConnectionRepository(
        databaseHelper = databaseHelper,
    )
    val modelProviderSettingsRepository = PersistentModelProviderSettingsRepository(
        databaseHelper = databaseHelper,
    )
    val resourceRegistryRepository = PersistentResourceRegistryRepository(
        databaseHelper = databaseHelper,
    )
    val transferBridgeCoordinator = TransferBridgeCoordinator(
        context = application,
        databaseHelper = databaseHelper,
        auditTrailRepository = auditTrailRepository,
        agentTaskRepository = agentTaskRepository,
    )
    val devicePairingRepository = PersistentDevicePairingRepository(
        databaseHelper = databaseHelper,
        auditTrailRepository = auditTrailRepository,
        transferOutboxScheduler = transferBridgeCoordinator,
    )
    val fileActionExecutor = FileActionExecutor(
        context = application,
        auditTrailRepository = auditTrailRepository,
    )
    val approvalInboxRepository = PersistentApprovalInboxRepository(
        context = application,
        databaseHelper = databaseHelper,
        auditTrailRepository = auditTrailRepository,
    )
    val organizeExecutionRepository = PersistentOrganizeExecutionRepository(
        databaseHelper = databaseHelper,
    )
    val agentTaskRetryCoordinator = AgentTaskRetryCoordinator(
        context = application,
        agentTaskRepository = agentTaskRepository,
        approvalInboxRepository = approvalInboxRepository,
        organizeExecutionRepository = organizeExecutionRepository,
        fileActionExecutor = fileActionExecutor,
        auditTrailRepository = auditTrailRepository,
    )
    val phoneAgentActionCoordinator = PhoneAgentActionCoordinator(
        fileIndexRepository = fileIndexRepository,
        approvalInboxRepository = approvalInboxRepository,
        agentTaskRepository = agentTaskRepository,
        organizeExecutionRepository = organizeExecutionRepository,
        fileActionExecutor = fileActionExecutor,
        devicePairingRepository = devicePairingRepository,
        agentTaskRetryCoordinator = agentTaskRetryCoordinator,
    )
    val voiceEntryCoordinator = VoiceEntryCoordinator(application)
    val phoneAgentRuntime = LocalPhoneAgentRuntime(
        fileIndexRepository = fileIndexRepository,
        fileGraphActionPlanner = fileGraphActionPlanner,
        approvalInboxRepository = approvalInboxRepository,
        auditTrailRepository = auditTrailRepository,
        devicePairingRepository = devicePairingRepository,
        phoneAgentActionCoordinator = phoneAgentActionCoordinator,
    )
    val agentTaskEngine = AgentTaskEngine(
        agentTaskRepository = agentTaskRepository,
        phoneAgentRuntime = phoneAgentRuntime,
        auditTrailRepository = auditTrailRepository,
    )
    val shellRecoveryCoordinator = ShellRecoveryCoordinator(
        approvalInboxRepository = approvalInboxRepository,
        agentTaskRepository = agentTaskRepository,
        agentTaskRetryCoordinator = agentTaskRetryCoordinator,
        auditTrailRepository = auditTrailRepository,
        devicePairingRepository = devicePairingRepository,
        organizeExecutionRepository = organizeExecutionRepository,
        transferBridgeCoordinator = transferBridgeCoordinator,
    )
}
