package io.makoion.mobileclaw.data

enum class ResourceConnectionState {
    Connected,
    Staged,
    NeedsSetup,
    Blocked,
}

enum class DeliveryCapabilityState {
    Ready,
    FallbackOnly,
    NeedsSetup,
}

enum class AutomationCapabilityState {
    Ready,
    Blocked,
}

data class CapabilityDescriptor(
    val capabilityId: String,
    val label: String,
    val state: ResourceConnectionState,
    val detail: String,
)

data class AgentEnvironmentSnapshot(
    val capabilities: List<CapabilityDescriptor>,
    val missingRequirements: List<String>,
    val connectedCompanionCount: Int,
    val connectedMcpEndpointCount: Int,
    val connectedDeliveryChannelCount: Int,
    val activeAutomationCount: Int,
    val deliveryCapabilityState: DeliveryCapabilityState,
    val automationCapabilityState: AutomationCapabilityState,
) {
    fun hasCapability(capabilityId: String): Boolean {
        return capabilities.any { it.capabilityId == capabilityId && it.state == ResourceConnectionState.Connected }
    }

    fun capabilitySummaryLines(): List<String> {
        return capabilities.map { descriptor ->
            "${descriptor.label}: ${descriptor.state.name.lowercase()}${descriptor.detail.takeIf(String::isNotBlank)?.let { " ($it)" }.orEmpty()}"
        }
    }
}

internal fun buildAgentEnvironmentSnapshot(context: AgentTurnContext): AgentEnvironmentSnapshot {
    val connectedMcpEndpoints = context.externalEndpoints.filter {
        it.status == ExternalEndpointStatus.Connected &&
            it.supportedCapabilities.any { capability ->
                capability.contains("mcp", ignoreCase = true) ||
                    capability.contains("browser", ignoreCase = true) ||
                    capability.contains("tool", ignoreCase = true)
            }
    }
    val connectedDeliveryChannels = context.deliveryChannels.filter {
        it.status == DeliveryChannelStatus.Connected
    }
    val primaryMailbox = context.mailboxConnections.firstOrNull { it.mailboxId == primaryMailboxConnectionId }
    val activeAutomations = context.scheduledAutomations.filter {
        it.status == ScheduledAutomationStatus.Active
    }
    val capabilities = buildList {
        add(
            CapabilityDescriptor(
                capabilityId = "phone.local_files",
                label = "Local files",
                state = if (
                    context.fileIndexState.permissionGranted ||
                    context.fileIndexState.documentTreeCount > 0
                ) {
                    ResourceConnectionState.Connected
                } else {
                    ResourceConnectionState.NeedsSetup
                },
                detail = "${context.fileIndexState.indexedCount} indexed, ${context.fileIndexState.documentTreeCount} roots",
            ),
        )
        add(
            CapabilityDescriptor(
                capabilityId = "model.providers.chat",
                label = "Model provider",
                state = if (context.modelPreference.configuredProviderIds.isNotEmpty()) {
                    ResourceConnectionState.Connected
                } else {
                    ResourceConnectionState.NeedsSetup
                },
                detail = context.modelPreference.preferredProviderLabel
                    ?.let { provider ->
                        context.modelPreference.preferredModel?.let { model -> "$provider / $model" } ?: provider
                    }
                    ?: "No configured provider",
            ),
        )
        add(
            CapabilityDescriptor(
                capabilityId = "mcp.bridge",
                label = "MCP bridge",
                state = when {
                    connectedMcpEndpoints.isNotEmpty() -> ResourceConnectionState.Connected
                    context.externalEndpoints.any { it.status == ExternalEndpointStatus.Staged } -> ResourceConnectionState.Staged
                    else -> ResourceConnectionState.NeedsSetup
                },
                detail = connectedMcpEndpoints.firstOrNull()?.let { endpoint ->
                    "${endpoint.toolNames.size} tool(s), ${endpoint.syncedSkillCount} synced skill(s)"
                } ?: "Companion-backed bridge not connected",
            ),
        )
        add(
            CapabilityDescriptor(
                capabilityId = "delivery.local_push",
                label = "Local push",
                state = if (context.deliveryChannels.any { it.channelId == localNotificationChannelId && it.status == DeliveryChannelStatus.Connected }) {
                    ResourceConnectionState.Connected
                } else {
                    ResourceConnectionState.NeedsSetup
                },
                detail = "On-device notification surface",
            ),
        )
        add(
            CapabilityDescriptor(
                capabilityId = "delivery.telegram",
                label = "Telegram relay",
                state = when (
                    context.deliveryChannels.firstOrNull { it.channelId == telegramDeliveryChannelId }?.status
                ) {
                    DeliveryChannelStatus.Connected -> ResourceConnectionState.Connected
                    DeliveryChannelStatus.Staged -> ResourceConnectionState.Staged
                    else -> ResourceConnectionState.NeedsSetup
                },
                detail = context.deliveryChannels.firstOrNull { it.channelId == telegramDeliveryChannelId }
                    ?.destinationLabel
                    ?: "Bot token and target chat are not bound",
            ),
        )
        add(
            CapabilityDescriptor(
                capabilityId = "automation.scheduler",
                label = "Automation scheduler",
                state = ResourceConnectionState.Connected,
                detail = "${activeAutomations.size} active, ${context.scheduledAutomations.size} total",
            ),
        )
        add(
            CapabilityDescriptor(
                capabilityId = "mailbox.connector",
                label = "Mailbox connector",
                state = when (primaryMailbox?.status) {
                    MailboxConnectionStatus.Connected -> ResourceConnectionState.Connected
                    MailboxConnectionStatus.Staged -> ResourceConnectionState.Staged
                    MailboxConnectionStatus.NeedsSetup, null -> ResourceConnectionState.NeedsSetup
                },
                detail = primaryMailbox?.let { mailbox ->
                    buildString {
                        append(mailbox.connectionLabel)
                        append(" / inbox ")
                        append(mailbox.inboxFolder)
                        append(" / promo ")
                        append(mailbox.promotionsFolder)
                        mailbox.lastError?.takeIf(String::isNotBlank)?.let { error ->
                            append(" / last error: ")
                            append(error)
                        }
                    }
                } ?: "No mailbox connection is configured yet",
            ),
        )
    }
    val missingRequirements = capabilities
        .filter { it.state != ResourceConnectionState.Connected }
        .map { "${it.label}: ${it.detail}" }
    val deliveryCapabilityState = when {
        connectedDeliveryChannels.any { it.channelId == telegramDeliveryChannelId } -> DeliveryCapabilityState.Ready
        connectedDeliveryChannels.any { it.channelId == localNotificationChannelId } -> DeliveryCapabilityState.FallbackOnly
        else -> DeliveryCapabilityState.NeedsSetup
    }
    val automationCapabilityState = if (context.modelPreference.configuredProviderIds.isNotEmpty()) {
        AutomationCapabilityState.Ready
    } else {
        AutomationCapabilityState.Blocked
    }
    return AgentEnvironmentSnapshot(
        capabilities = capabilities,
        missingRequirements = missingRequirements,
        connectedCompanionCount = context.pairedDevices.size,
        connectedMcpEndpointCount = connectedMcpEndpoints.size,
        connectedDeliveryChannelCount = connectedDeliveryChannels.size,
        activeAutomationCount = activeAutomations.size,
        deliveryCapabilityState = deliveryCapabilityState,
        automationCapabilityState = automationCapabilityState,
    )
}

private const val localNotificationChannelId = "phone-local-notification"
private const val telegramDeliveryChannelId = "telegram-bot-delivery"
