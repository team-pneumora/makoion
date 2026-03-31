package io.makoion.mobileclaw.data

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ScheduledAgentGoalKind {
    MorningBriefing,
    MarketNewsWatch,
    EmailTriage,
    Generic,
}

enum class AlertSeverity {
    Critical,
    High,
    Normal,
    Low,
}

enum class DeliveryTargetPolicy {
    LocalPush,
    PreferTelegramThenLocalPush,
    ChatSummary,
}

data class ScheduledAgentRunSpec(
    val goalKind: ScheduledAgentGoalKind,
    val goalSummary: String,
    val deliveryPolicy: DeliveryTargetPolicy,
)

data class AutomationExecutionResult(
    val status: ScheduledAutomationStatus,
    val summary: String,
    val alertTitle: String,
    val alertBody: String,
    val severity: AlertSeverity,
    val blockedReason: String? = null,
    val deliverToUser: Boolean = true,
)

interface AgentRuntimeContextProvider {
    suspend fun currentContext(): AgentTurnContext
}

class DefaultAgentRuntimeContextProvider(
    private val fileIndexRepository: FileIndexRepository,
    private val approvalInboxRepository: ApprovalInboxRepository,
    private val agentTaskRepository: AgentTaskRepository,
    private val auditTrailRepository: AuditTrailRepository,
    private val chatTranscriptRepository: ChatTranscriptRepository,
    private val devicePairingRepository: DevicePairingRepository,
    private val cloudDriveConnectionRepository: CloudDriveConnectionRepository,
    private val modelProviderSettingsRepository: ModelProviderSettingsRepository,
    private val externalEndpointRepository: ExternalEndpointRegistryRepository,
    private val deliveryChannelRepository: DeliveryChannelRegistryRepository,
    private val mailboxConnectionRepository: MailboxConnectionRepository,
    private val emailTriageRepository: EmailTriageRepository,
    private val scheduledAutomationRepository: ScheduledAutomationRepository,
) : AgentRuntimeContextProvider {
    override suspend fun currentContext(): AgentTurnContext {
        return AgentTurnContext(
            fileIndexState = fileIndexRepository.refreshIndex(),
            approvals = approvalInboxRepository.items.value,
            tasks = agentTaskRepository.tasks.value,
            auditEvents = auditTrailRepository.events.value,
            chatMessages = chatTranscriptRepository.messages.value,
            pairedDevices = devicePairingRepository.pairedDevices.value,
            selectedTargetDeviceId = devicePairingRepository.pairedDevices.value.firstOrNull()?.id,
            cloudDriveConnections = cloudDriveConnectionRepository.connections.value,
            modelPreference = resolveAgentModelPreference(modelProviderSettingsRepository.profiles.value),
            externalEndpoints = externalEndpointRepository.profiles.value,
            deliveryChannels = deliveryChannelRepository.profiles.value,
            mailboxConnections = mailboxConnectionRepository.profiles.value,
            emailTriageRecords = emailTriageRepository.records.value,
            scheduledAutomations = scheduledAutomationRepository.automations.value,
        )
    }
}

interface ScheduledAgentRunner {
    suspend fun execute(automation: ScheduledAutomationRecord): AutomationExecutionResult
}

class DefaultScheduledAgentRunner(
    private val contextProvider: AgentRuntimeContextProvider,
    private val providerConversationClient: ProviderConversationClient,
    private val mailboxConnectionRepository: MailboxConnectionRepository,
    private val mailboxCredentialVault: MailboxCredentialVault,
    private val mailboxGateway: MailboxGateway,
    private val emailTriageRepository: EmailTriageRepository,
) : ScheduledAgentRunner {
    override suspend fun execute(automation: ScheduledAutomationRecord): AutomationExecutionResult {
        val context = contextProvider.currentContext()
        val spec = automation.runSpecJson?.let(::decodeRunSpec) ?: buildScheduledAgentRunSpec(automation.prompt)
        return when (spec.goalKind) {
            ScheduledAgentGoalKind.MorningBriefing -> runMorningBriefing(automation, spec, context)
            ScheduledAgentGoalKind.MarketNewsWatch -> runMarketNewsWatch(automation, spec, context)
            ScheduledAgentGoalKind.EmailTriage -> runEmailTriage(automation, spec, context)
            ScheduledAgentGoalKind.Generic -> runGenericAutomation(automation, spec, context)
        }
    }

    private suspend fun runMorningBriefing(
        automation: ScheduledAutomationRecord,
        spec: ScheduledAgentRunSpec,
        context: AgentTurnContext,
    ): AutomationExecutionResult {
        val environment = buildAgentEnvironmentSnapshot(context)
        val fallback = buildString {
            append("Morning briefing\n")
            append("Files indexed: ${context.fileIndexState.indexedCount}\n")
            append("Pending approvals: ${context.approvals.count { it.status == ApprovalInboxStatus.Pending }}\n")
            append("Connected MCP endpoints: ${environment.connectedMcpEndpointCount}\n")
            append("Connected delivery channels: ${environment.connectedDeliveryChannelCount}\n")
            append("Active automations: ${environment.activeAutomationCount}")
        }
        val generated = generateWithProvider(
            prompt = "Create a concise morning briefing for the phone owner. Use four short bullet lines. Local state: $fallback",
            context = context,
        ) ?: fallback
        return AutomationExecutionResult(
            status = ScheduledAutomationStatus.Active,
            summary = "Generated the scheduled morning briefing.",
            alertTitle = automation.title.ifBlank { "Morning briefing" },
            alertBody = generated,
            severity = AlertSeverity.Normal,
        )
    }

    private suspend fun runMarketNewsWatch(
        automation: ScheduledAutomationRecord,
        spec: ScheduledAgentRunSpec,
        context: AgentTurnContext,
    ): AutomationExecutionResult {
        val feedItems = fetchMarketNewsFeed()
        if (feedItems.isEmpty()) {
            return AutomationExecutionResult(
                status = ScheduledAutomationStatus.Degraded,
                summary = "Public market news feed returned no headlines.",
                alertTitle = automation.title,
                alertBody = "No KOSPI/KOSDAQ headlines were available from the public feed during this run.",
                severity = AlertSeverity.Low,
                blockedReason = "No public feed headlines returned",
            )
        }
        val graded = feedItems.map { item ->
            GradedHeadline(
                title = item.title,
                url = item.url,
                grade = classifyHeadlineGrade(item.title),
            )
        }
        val aGrade = graded.filter { it.grade == "A" }
        val fallback = buildString {
            append("A-grade headlines: ${aGrade.size}\n")
            val topItems = (if (aGrade.isNotEmpty()) aGrade else graded.take(5)).take(5)
            topItems.forEachIndexed { index, item ->
                append("${index + 1}. [${item.grade}] ${item.title}\n")
            }
        }.trim()
        val generated = generateWithProvider(
            prompt = "Summarize only the market-moving KOSPI/KOSDAQ headlines below. Separate A and B grade items briefly and mention why they can affect prices.\n$fallback",
            context = context,
        ) ?: fallback
        return AutomationExecutionResult(
            status = ScheduledAutomationStatus.Active,
            summary = "Collected ${graded.size} headlines and flagged ${aGrade.size} A-grade items.",
            alertTitle = automation.title.ifBlank { "Market news watch" },
            alertBody = generated,
            severity = if (aGrade.isNotEmpty()) AlertSeverity.High else AlertSeverity.Normal,
        )
    }

    private suspend fun runGenericAutomation(
        automation: ScheduledAutomationRecord,
        spec: ScheduledAgentRunSpec,
        context: AgentTurnContext,
    ): AutomationExecutionResult {
        val generated = generateWithProvider(
            prompt = automation.prompt,
            context = context,
        ) ?: "Automation ran, but no provider-generated result was available."
        return AutomationExecutionResult(
            status = ScheduledAutomationStatus.Active,
            summary = "Executed the scheduled agent run.",
            alertTitle = automation.title,
            alertBody = generated,
            severity = AlertSeverity.Normal,
        )
    }

    private suspend fun runEmailTriage(
        automation: ScheduledAutomationRecord,
        spec: ScheduledAgentRunSpec,
        context: AgentTurnContext,
    ): AutomationExecutionResult {
        val mailbox = mailboxConnectionRepository.primaryMailbox()
        if (mailbox == null || mailbox.status != MailboxConnectionStatus.Connected) {
            return AutomationExecutionResult(
                status = ScheduledAutomationStatus.Blocked,
                summary = "Mailbox connection is still missing, so the triage automation remains blocked.",
                alertTitle = automation.title,
                alertBody = "Connect a mailbox from chat first, then activate this triage policy again.",
                severity = AlertSeverity.Low,
                blockedReason = "No connected mailbox is available yet.",
                deliverToUser = false,
            )
        }
        val password = mailboxCredentialVault.read(mailbox.mailboxId)?.trim().orEmpty()
        if (password.isBlank()) {
            return AutomationExecutionResult(
                status = ScheduledAutomationStatus.Blocked,
                summary = "Mailbox credential is missing, so the triage automation could not open the inbox.",
                alertTitle = automation.title,
                alertBody = "The mailbox secret is missing. Reconnect the mailbox from chat with the app password.",
                severity = AlertSeverity.Low,
                blockedReason = "Mailbox credential is missing.",
                deliverToUser = false,
            )
        }
        val config = MailboxConnectionConfig(
            mailboxId = mailbox.mailboxId,
            displayName = mailbox.displayName,
            host = mailbox.host,
            port = mailbox.port,
            username = mailbox.username,
            inboxFolder = mailbox.inboxFolder,
            promotionsFolder = mailbox.promotionsFolder,
        )
        val triage = mailboxGateway.triage(
            config = config,
            password = password,
            automationId = automation.id,
        )
        emailTriageRepository.replaceForAutomation(
            automationId = automation.id,
            items = triage.persistedItems,
        )
        val importantCount = triage.importantMessages.size
        val reviewCount = triage.reviewMessages.size
        val movedCount = triage.promotionalMovedCount
        val summary = "Scanned ${triage.scannedCount} mail(s), moved $movedCount promotional item(s), flagged $importantCount important item(s), and left $reviewCount in the review queue."
        val alertBody = if (importantCount == 0) {
            "Mailbox triage finished with no important mail. Moved $movedCount promotional item(s) and left $reviewCount review item(s) in Dashboard."
        } else {
            buildString {
                append("Important mail detected:\n")
                triage.importantMessages.take(4).forEachIndexed { index, message ->
                    append("${index + 1}. ${message.subject} — ${message.sender}\n")
                }
                append("\nMoved $movedCount promotional item(s). Review queue: $reviewCount.")
            }.trim()
        }
        return AutomationExecutionResult(
            status = ScheduledAutomationStatus.Active,
            summary = summary,
            alertTitle = automation.title.ifBlank { "Email triage" },
            alertBody = alertBody,
            severity = if (importantCount > 0) AlertSeverity.High else AlertSeverity.Low,
            deliverToUser = importantCount > 0,
        )
    }

    private suspend fun generateWithProvider(
        prompt: String,
        context: AgentTurnContext,
    ): String? {
        return when (
            val result = providerConversationClient.generateReply(
                prompt = prompt,
                recentMessages = emptyList(),
                context = context,
            )
        ) {
            is ProviderConversationResult.Reply -> result.text.trim().takeIf(String::isNotBlank)
            else -> null
        }
    }

    private suspend fun fetchMarketNewsFeed(): List<MarketFeedItem> {
        return withContext(Dispatchers.IO) {
            val url = URL(googleNewsMarketFeedUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = networkTimeoutMs
                readTimeout = networkTimeoutMs
                setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml")
            }
            connection.useConnection {
                if (responseCode !in 200..299) {
                    return@withContext emptyList()
                }
                val raw = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                val document = builder.parse(ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)))
                val items = document.getElementsByTagName("item")
                buildList {
                    for (index in 0 until items.length.coerceAtMost(maxFeedItems)) {
                        val node = items.item(index)
                        val children = node.childNodes
                        var title = ""
                        var link = ""
                        for (childIndex in 0 until children.length) {
                            val child = children.item(childIndex)
                            when (child.nodeName) {
                                "title" -> title = child.textContent.orEmpty().trim()
                                "link" -> link = child.textContent.orEmpty().trim()
                            }
                        }
                        if (title.isNotBlank()) {
                            add(MarketFeedItem(title = title, url = link))
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val networkTimeoutMs = 10_000
        private const val maxFeedItems = 12
        private const val googleNewsMarketFeedUrl =
            "https://news.google.com/rss/search?q=KOSPI%20OR%20KOSDAQ%20when%3A1d&hl=en-US&gl=US&ceid=US%3Aen"
    }
}

internal fun buildScheduledAgentRunSpec(prompt: String): ScheduledAgentRunSpec {
    val normalized = prompt.trim().lowercase()
    return when {
        containsAny(normalized, "morning briefing", "모닝 브리핑", "아침 브리핑", "기상") ->
            ScheduledAgentRunSpec(
                goalKind = ScheduledAgentGoalKind.MorningBriefing,
                goalSummary = "Generate a morning briefing at wake-up time.",
                deliveryPolicy = if (containsAny(normalized, "telegram", "텔레그램")) {
                    DeliveryTargetPolicy.PreferTelegramThenLocalPush
                } else {
                    DeliveryTargetPolicy.LocalPush
                },
            )
        containsAny(normalized, "kospi", "kosdaq", "코스피", "코스닥", "주가") &&
            containsAny(normalized, "news", "뉴스", "이슈", "watch", "monitor", "감시", "수집") ->
            ScheduledAgentRunSpec(
                goalKind = ScheduledAgentGoalKind.MarketNewsWatch,
                goalSummary = "Watch market-moving KOSPI/KOSDAQ headlines.",
                deliveryPolicy = if (containsAny(normalized, "telegram", "텔레그램")) {
                    DeliveryTargetPolicy.PreferTelegramThenLocalPush
                } else {
                    DeliveryTargetPolicy.LocalPush
                },
            )
        containsAny(normalized, "email", "mail", "이메일", "메일") ->
            ScheduledAgentRunSpec(
                goalKind = ScheduledAgentGoalKind.EmailTriage,
                goalSummary = "Triage the mailbox and alert on important mail.",
                deliveryPolicy = if (containsAny(normalized, "telegram", "텔레그램")) {
                    DeliveryTargetPolicy.PreferTelegramThenLocalPush
                } else {
                    DeliveryTargetPolicy.LocalPush
                },
            )
        else ->
            ScheduledAgentRunSpec(
                goalKind = ScheduledAgentGoalKind.Generic,
                goalSummary = "Run the recorded recurring goal.",
                deliveryPolicy = if (containsAny(normalized, "telegram", "텔레그램")) {
                    DeliveryTargetPolicy.PreferTelegramThenLocalPush
                } else {
                    DeliveryTargetPolicy.LocalPush
                },
            )
    }
}

internal fun encodeRunSpec(spec: ScheduledAgentRunSpec): String {
    return """{"goalKind":"${spec.goalKind.name}","goalSummary":${spec.goalSummary.jsonQuoted()},"deliveryPolicy":"${spec.deliveryPolicy.name}"}"""
}

internal fun decodeRunSpec(raw: String): ScheduledAgentRunSpec {
    val json = org.json.JSONObject(raw)
    return ScheduledAgentRunSpec(
        goalKind = runCatching {
            ScheduledAgentGoalKind.valueOf(json.optString("goalKind"))
        }.getOrDefault(ScheduledAgentGoalKind.Generic),
        goalSummary = json.optString("goalSummary").ifBlank { "Run the recorded recurring goal." },
        deliveryPolicy = runCatching {
            DeliveryTargetPolicy.valueOf(json.optString("deliveryPolicy"))
        }.getOrDefault(DeliveryTargetPolicy.LocalPush),
    )
}

private data class MarketFeedItem(
    val title: String,
    val url: String,
)

private data class GradedHeadline(
    val title: String,
    val url: String,
    val grade: String,
)

private fun classifyHeadlineGrade(title: String): String {
    val normalized = title.lowercase()
    return when {
        containsAny(
            normalized,
            "guidance",
            "earnings",
            "acquisition",
            "merger",
            "investigation",
            "bankruptcy",
            "sec",
            "contract",
            "regulation",
            "delisting",
            "실적",
            "규제",
            "수주",
            "계약",
            "유상증자",
            "상장폐지",
            "합병",
        ) -> "A"
        containsAny(
            normalized,
            "launch",
            "partnership",
            "forecast",
            "expansion",
            "estimate",
            "협업",
            "출시",
            "전망",
            "확장",
        ) -> "B"
        else -> "C"
    }
}

private fun containsAny(
    value: String,
    vararg needles: String,
): Boolean {
    return needles.any { needle -> value.contains(needle) }
}

private fun String.jsonQuoted(): String {
    return org.json.JSONObject.quote(this)
}

private inline fun <T> HttpURLConnection.useConnection(block: HttpURLConnection.() -> T): T {
    return try {
        block()
    } finally {
        disconnect()
    }
}
