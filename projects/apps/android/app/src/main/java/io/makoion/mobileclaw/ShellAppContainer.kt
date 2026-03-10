package io.makoion.mobileclaw

import android.app.Application
import io.makoion.mobileclaw.data.AndroidFileIndexRepository
import io.makoion.mobileclaw.data.FileActionExecutor
import io.makoion.mobileclaw.data.LocalFileGraphActionPlanner
import io.makoion.mobileclaw.data.VoiceEntryCoordinator
import io.makoion.mobileclaw.data.PersistentApprovalInboxRepository
import io.makoion.mobileclaw.data.PersistentAuditTrailRepository
import io.makoion.mobileclaw.data.PersistentDevicePairingRepository
import io.makoion.mobileclaw.data.ShellDatabaseHelper
import io.makoion.mobileclaw.data.TransferBridgeCoordinator

class ShellAppContainer(
    application: Application,
) {
    private val databaseHelper = ShellDatabaseHelper(application)

    val fileIndexRepository = AndroidFileIndexRepository(application)
    val fileGraphActionPlanner = LocalFileGraphActionPlanner()
    val auditTrailRepository = PersistentAuditTrailRepository(
        context = application,
        databaseHelper = databaseHelper,
    )
    val transferBridgeCoordinator = TransferBridgeCoordinator(
        context = application,
        databaseHelper = databaseHelper,
        auditTrailRepository = auditTrailRepository,
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
    val voiceEntryCoordinator = VoiceEntryCoordinator(application)
}
