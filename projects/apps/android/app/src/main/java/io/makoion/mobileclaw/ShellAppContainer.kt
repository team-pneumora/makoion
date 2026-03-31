package io.makoion.mobileclaw

import android.app.Application
import io.makoion.mobileclaw.data.AndroidFileIndexRepository
import io.makoion.mobileclaw.data.AndroidChatAttachmentResolver
import io.makoion.mobileclaw.data.AndroidKeystoreDeliveryChannelCredentialVault
import io.makoion.mobileclaw.data.AgentTaskEngine
import io.makoion.mobileclaw.data.AgentTaskRetryCoordinator
import io.makoion.mobileclaw.data.AndroidKeystoreModelProviderCredentialVault
import io.makoion.mobileclaw.data.AndroidKeystoreMailboxCredentialVault
import io.makoion.mobileclaw.data.DefaultAgentRuntimeContextProvider
import io.makoion.mobileclaw.data.DefaultDeliveryRouter
import io.makoion.mobileclaw.data.DefaultScheduledAgentRunner
import io.makoion.mobileclaw.data.ImapMailboxGateway
import io.makoion.mobileclaw.data.HttpTelegramDeliveryGateway
import io.makoion.mobileclaw.data.PersistentEmailTriageRepository
import io.makoion.mobileclaw.data.PersistentMailboxConnectionRepository
import io.makoion.mobileclaw.data.PersistentDeliveryChannelRegistryRepository
import io.makoion.mobileclaw.data.PersistentExternalEndpointRegistryRepository
import io.makoion.mobileclaw.data.PersistentCloudDriveConnectionRepository
import io.makoion.mobileclaw.data.PersistentCodeGenerationProjectRepository
import io.makoion.mobileclaw.data.LocalCodeGenerationWorkspaceExecutor
import io.makoion.mobileclaw.data.FileActionExecutor
import io.makoion.mobileclaw.data.LocalFileGraphActionPlanner
import io.makoion.mobileclaw.data.PersistentChatTranscriptRepository
import io.makoion.mobileclaw.data.PersistentAgentTaskRepository
import io.makoion.mobileclaw.data.PhoneAgentActionCoordinator
import io.makoion.mobileclaw.data.PersistentModelProviderSettingsRepository
import io.makoion.mobileclaw.data.PersistentMcpSkillRepository
import io.makoion.mobileclaw.data.PersistentOrganizeDebugSettingsRepository
import io.makoion.mobileclaw.data.PersistentOrganizeExecutionRepository
import io.makoion.mobileclaw.data.PersistentResourceRegistryRepository
import io.makoion.mobileclaw.data.PersistentScheduledAutomationRepository
import io.makoion.mobileclaw.data.HttpProviderConversationClient
import io.makoion.mobileclaw.data.ScheduledAutomationCoordinator
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
    val chatAttachmentResolver = AndroidChatAttachmentResolver(application)
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
    val modelProviderCredentialVault = AndroidKeystoreModelProviderCredentialVault(application)
    val modelProviderSettingsRepository = PersistentModelProviderSettingsRepository(
        databaseHelper = databaseHelper,
        credentialVault = modelProviderCredentialVault,
    )
    val externalEndpointRepository = PersistentExternalEndpointRegistryRepository(
        databaseHelper = databaseHelper,
    )
    val mcpSkillRepository = PersistentMcpSkillRepository(
        databaseHelper = databaseHelper,
        auditTrailRepository = auditTrailRepository,
    )
    val deliveryChannelRepository = PersistentDeliveryChannelRegistryRepository(
        databaseHelper = databaseHelper,
    )
    val deliveryChannelCredentialVault = AndroidKeystoreDeliveryChannelCredentialVault(application)
    val mailboxConnectionRepository = PersistentMailboxConnectionRepository(
        databaseHelper = databaseHelper,
        auditTrailRepository = auditTrailRepository,
    )
    val mailboxCredentialVault = AndroidKeystoreMailboxCredentialVault(application)
    val emailTriageRepository = PersistentEmailTriageRepository(
        databaseHelper = databaseHelper,
    )
    val mailboxGateway = ImapMailboxGateway()
    val codeGenerationProjectRepository = PersistentCodeGenerationProjectRepository(
        databaseHelper = databaseHelper,
        auditTrailRepository = auditTrailRepository,
    )
    val codeGenerationWorkspaceExecutor = LocalCodeGenerationWorkspaceExecutor(
        context = application,
        auditTrailRepository = auditTrailRepository,
    )
    val resourceRegistryRepository = PersistentResourceRegistryRepository(
        databaseHelper = databaseHelper,
    )
    val scheduledAutomationRepository = PersistentScheduledAutomationRepository(
        databaseHelper = databaseHelper,
        auditTrailRepository = auditTrailRepository,
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
    val providerConversationClient = HttpProviderConversationClient(
        settingsRepository = modelProviderSettingsRepository,
        credentialVault = modelProviderCredentialVault,
    )
    val agentRuntimeContextProvider = DefaultAgentRuntimeContextProvider(
        fileIndexRepository = fileIndexRepository,
        approvalInboxRepository = approvalInboxRepository,
        agentTaskRepository = agentTaskRepository,
        auditTrailRepository = auditTrailRepository,
        chatTranscriptRepository = chatTranscriptRepository,
        devicePairingRepository = devicePairingRepository,
        cloudDriveConnectionRepository = cloudDriveConnectionRepository,
        modelProviderSettingsRepository = modelProviderSettingsRepository,
        externalEndpointRepository = externalEndpointRepository,
        deliveryChannelRepository = deliveryChannelRepository,
        mailboxConnectionRepository = mailboxConnectionRepository,
        emailTriageRepository = emailTriageRepository,
        scheduledAutomationRepository = scheduledAutomationRepository,
    )
    val scheduledAgentRunner = DefaultScheduledAgentRunner(
        contextProvider = agentRuntimeContextProvider,
        providerConversationClient = providerConversationClient,
        mailboxConnectionRepository = mailboxConnectionRepository,
        mailboxCredentialVault = mailboxCredentialVault,
        mailboxGateway = mailboxGateway,
        emailTriageRepository = emailTriageRepository,
    )
    val telegramDeliveryGateway = HttpTelegramDeliveryGateway()
    val deliveryRouter = DefaultDeliveryRouter(
        context = application,
        deliveryChannelRepository = deliveryChannelRepository,
        deliveryChannelCredentialVault = deliveryChannelCredentialVault,
        telegramDeliveryGateway = telegramDeliveryGateway,
    )
    val scheduledAutomationCoordinator = ScheduledAutomationCoordinator(
        context = application,
        scheduledAutomationRepository = scheduledAutomationRepository,
        auditTrailRepository = auditTrailRepository,
        scheduledAgentRunner = scheduledAgentRunner,
        deliveryRouter = deliveryRouter,
    )
    val voiceEntryCoordinator = VoiceEntryCoordinator(application)
    val shellRecoveryCoordinator = ShellRecoveryCoordinator(
        approvalInboxRepository = approvalInboxRepository,
        agentTaskRepository = agentTaskRepository,
        agentTaskRetryCoordinator = agentTaskRetryCoordinator,
        auditTrailRepository = auditTrailRepository,
        chatTranscriptRepository = chatTranscriptRepository,
        devicePairingRepository = devicePairingRepository,
        organizeExecutionRepository = organizeExecutionRepository,
        scheduledAutomationRepository = scheduledAutomationRepository,
        scheduledAutomationCoordinator = scheduledAutomationCoordinator,
        transferBridgeCoordinator = transferBridgeCoordinator,
    )
    val phoneAgentRuntime = LocalPhoneAgentRuntime(
        fileIndexRepository = fileIndexRepository,
        fileGraphActionPlanner = fileGraphActionPlanner,
        approvalInboxRepository = approvalInboxRepository,
        auditTrailRepository = auditTrailRepository,
        cloudDriveConnectionRepository = cloudDriveConnectionRepository,
        devicePairingRepository = devicePairingRepository,
        deliveryChannelRepository = deliveryChannelRepository,
        mailboxConnectionRepository = mailboxConnectionRepository,
        emailTriageRepository = emailTriageRepository,
        externalEndpointRepository = externalEndpointRepository,
        mcpSkillRepository = mcpSkillRepository,
        scheduledAutomationRepository = scheduledAutomationRepository,
        scheduledAutomationCoordinator = scheduledAutomationCoordinator,
        shellRecoveryCoordinator = shellRecoveryCoordinator,
        codeGenerationProjectRepository = codeGenerationProjectRepository,
        codeGenerationWorkspaceExecutor = codeGenerationWorkspaceExecutor,
        phoneAgentActionCoordinator = phoneAgentActionCoordinator,
        providerConversationClient = providerConversationClient,
        deliveryChannelCredentialVault = deliveryChannelCredentialVault,
        mailboxCredentialVault = mailboxCredentialVault,
        mailboxGateway = mailboxGateway,
        telegramDeliveryGateway = telegramDeliveryGateway,
    )
    val agentTaskEngine = AgentTaskEngine(
        agentTaskRepository = agentTaskRepository,
        phoneAgentRuntime = phoneAgentRuntime,
        auditTrailRepository = auditTrailRepository,
    )
}
