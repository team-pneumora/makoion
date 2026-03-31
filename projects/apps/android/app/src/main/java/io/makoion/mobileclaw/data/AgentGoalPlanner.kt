package io.makoion.mobileclaw.data

enum class GoalNodeType {
    Connect,
    Execute,
    Schedule,
    Deliver,
}

enum class ExecutionPolicy {
    Automatic,
    RequiresConsent,
}

enum class DeliveryPolicy {
    LocalPushOnly,
    PreferTelegramThenLocalPush,
    ChatSummaryOnly,
}

data class ConnectionRequirement(
    val capabilityId: String,
    val label: String,
    val state: ResourceConnectionState,
    val nextStep: String,
)

data class PlannedTaskNode(
    val id: String,
    val type: GoalNodeType,
    val label: String,
    val status: ResourceConnectionState,
)

data class TaskDependencyEdge(
    val fromId: String,
    val toId: String,
)

enum class AgentGoalType {
    MarketNewsWatch,
    MorningBriefing,
    EmailTriage,
    TelegramConnect,
}

data class VerticalSkillRecipe(
    val goalType: AgentGoalType,
    val summary: String,
    val capabilities: List<String>,
)

data class AgentGoalPlan(
    val type: AgentGoalType,
    val summary: String,
    val requirements: List<ConnectionRequirement>,
    val nodes: List<PlannedTaskNode>,
    val edges: List<TaskDependencyEdge>,
    val executionPolicy: ExecutionPolicy,
    val deliveryPolicy: DeliveryPolicy,
    val recipe: VerticalSkillRecipe,
    val blockedReason: String? = null,
) {
    val missingRequirements: List<ConnectionRequirement>
        get() = requirements.filter { it.state != ResourceConnectionState.Connected }
}

internal fun planAgentGoal(
    prompt: String,
    context: AgentTurnContext,
): AgentGoalPlan? {
    val normalized = prompt.trim().lowercase()
    val environment = buildAgentEnvironmentSnapshot(context)
    return when {
        isMarketNewsGoal(normalized) -> buildMarketNewsGoalPlan(environment)
        isMorningBriefingGoal(normalized) -> buildMorningBriefingGoalPlan(environment, normalized)
        isEmailTriageGoal(normalized) -> buildEmailTriageGoalPlan(environment)
        isTelegramConnectionGoal(normalized) -> buildTelegramConnectionGoalPlan(environment)
        else -> null
    }
}

private fun buildMarketNewsGoalPlan(environment: AgentEnvironmentSnapshot): AgentGoalPlan {
    val browserRequirement = environment.capabilities.firstOrNull { it.capabilityId == "mcp.bridge" }
    val telegramRequirement = environment.capabilities.firstOrNull { it.capabilityId == "delivery.telegram" }
    val requirements = buildList {
        browserRequirement?.let {
            add(
                ConnectionRequirement(
                    capabilityId = it.capabilityId,
                    label = it.label,
                    state = it.state,
                    nextStep = if (it.state == ResourceConnectionState.Connected) {
                        "Use the connected browser/MCP inventory if available, otherwise fall back to the public market feed."
                    } else {
                        "Connect the MCP/browser bridge from chat or let the runtime use the public market feed fallback."
                    },
                ),
            )
        }
        telegramRequirement?.let {
            add(
                ConnectionRequirement(
                    capabilityId = it.capabilityId,
                    label = it.label,
                    state = it.state,
                    nextStep = if (it.state == ResourceConnectionState.Connected) {
                        "Telegram delivery is ready for important alerts."
                    } else {
                        "Bind a Telegram bot token and target chat if you want off-device alerts."
                    },
                ),
            )
        }
    }
    val collectNode = PlannedTaskNode(
        id = "collect-market-news",
        type = GoalNodeType.Execute,
        label = "Collect market headlines",
        status = if (browserRequirement?.state == ResourceConnectionState.Connected) {
            ResourceConnectionState.Connected
        } else {
            ResourceConnectionState.Staged
        },
    )
    val classifyNode = PlannedTaskNode(
        id = "classify-market-news",
        type = GoalNodeType.Execute,
        label = "Classify A/B/C impact",
        status = ResourceConnectionState.Connected,
    )
    val scheduleNode = PlannedTaskNode(
        id = "schedule-market-news",
        type = GoalNodeType.Schedule,
        label = "Schedule recurring market watch",
        status = ResourceConnectionState.Connected,
    )
    val deliverNode = PlannedTaskNode(
        id = "deliver-market-news",
        type = GoalNodeType.Deliver,
        label = "Deliver A-grade alerts",
        status = if (environment.deliveryCapabilityState == DeliveryCapabilityState.NeedsSetup) {
            ResourceConnectionState.NeedsSetup
        } else {
            ResourceConnectionState.Connected
        },
    )
    return AgentGoalPlan(
        type = AgentGoalType.MarketNewsWatch,
        summary = "Collect market-moving KOSPI/KOSDAQ headlines, classify impact, and deliver only the high-signal alerts.",
        requirements = requirements,
        nodes = listOf(collectNode, classifyNode, scheduleNode, deliverNode),
        edges = listOf(
            TaskDependencyEdge(collectNode.id, classifyNode.id),
            TaskDependencyEdge(classifyNode.id, scheduleNode.id),
            TaskDependencyEdge(classifyNode.id, deliverNode.id),
        ),
        executionPolicy = ExecutionPolicy.Automatic,
        deliveryPolicy = if (telegramRequirement?.state == ResourceConnectionState.Connected) {
            DeliveryPolicy.PreferTelegramThenLocalPush
        } else {
            DeliveryPolicy.LocalPushOnly
        },
        recipe = VerticalSkillRecipe(
            goalType = AgentGoalType.MarketNewsWatch,
            summary = "Market news watcher",
            capabilities = listOf("news.collect", "news.classify", "automation.schedule", "delivery.alert"),
        ),
    )
}

private fun buildMorningBriefingGoalPlan(
    environment: AgentEnvironmentSnapshot,
    normalizedPrompt: String,
): AgentGoalPlan {
    val telegramRequirement = environment.capabilities.firstOrNull { it.capabilityId == "delivery.telegram" }
    return AgentGoalPlan(
        type = AgentGoalType.MorningBriefing,
        summary = "Generate a morning briefing and deliver it on the requested wake-up schedule.",
        requirements = listOfNotNull(
            telegramRequirement?.let {
                ConnectionRequirement(
                    capabilityId = it.capabilityId,
                    label = it.label,
                    state = it.state,
                    nextStep = if (normalizedPrompt.contains("telegram") || normalizedPrompt.contains("텔레그램")) {
                        "Bind Telegram if you want the morning briefing to leave the phone."
                    } else {
                        "Optional: bind Telegram for an off-device fallback channel."
                    },
                )
            },
        ),
        nodes = listOf(
            PlannedTaskNode(
                id = "compose-briefing",
                type = GoalNodeType.Execute,
                label = "Compose morning briefing",
                status = if (environment.automationCapabilityState == AutomationCapabilityState.Ready) {
                    ResourceConnectionState.Connected
                } else {
                    ResourceConnectionState.Blocked
                },
            ),
            PlannedTaskNode(
                id = "schedule-briefing",
                type = GoalNodeType.Schedule,
                label = "Schedule daily wake-up run",
                status = ResourceConnectionState.Connected,
            ),
            PlannedTaskNode(
                id = "deliver-briefing",
                type = GoalNodeType.Deliver,
                label = "Deliver the briefing alert",
                status = if (environment.deliveryCapabilityState == DeliveryCapabilityState.NeedsSetup) {
                    ResourceConnectionState.NeedsSetup
                } else {
                    ResourceConnectionState.Connected
                },
            ),
        ),
        edges = listOf(
            TaskDependencyEdge("compose-briefing", "schedule-briefing"),
            TaskDependencyEdge("compose-briefing", "deliver-briefing"),
        ),
        executionPolicy = ExecutionPolicy.Automatic,
        deliveryPolicy = if (telegramRequirement?.state == ResourceConnectionState.Connected) {
            DeliveryPolicy.PreferTelegramThenLocalPush
        } else {
            DeliveryPolicy.LocalPushOnly
        },
        recipe = VerticalSkillRecipe(
            goalType = AgentGoalType.MorningBriefing,
            summary = "Morning briefing",
            capabilities = listOf("briefing.compose", "automation.schedule", "delivery.alert"),
        ),
        blockedReason = if (environment.automationCapabilityState == AutomationCapabilityState.Ready) {
            null
        } else {
            "A configured model provider is still required before scheduled briefing generation can run."
        },
    )
}

private fun buildEmailTriageGoalPlan(environment: AgentEnvironmentSnapshot): AgentGoalPlan {
    val mailboxRequirement = environment.capabilities.first { it.capabilityId == "mailbox.connector" }
    val deliveryState = if (environment.deliveryCapabilityState == DeliveryCapabilityState.NeedsSetup) {
        ResourceConnectionState.NeedsSetup
    } else {
        ResourceConnectionState.Connected
    }
    val mailboxReady = mailboxRequirement.state == ResourceConnectionState.Connected
    return AgentGoalPlan(
        type = AgentGoalType.EmailTriage,
        summary = "Connect a mailbox, classify recent mail, move promotional items, and alert on important mail.",
        requirements = listOf(
            ConnectionRequirement(
                capabilityId = mailboxRequirement.capabilityId,
                label = mailboxRequirement.label,
                state = mailboxRequirement.state,
                nextStep = if (mailboxReady) {
                    "The mailbox connector is ready. The agent can now classify recent mail, move promotional messages, and alert on important messages."
                } else {
                    "Connect the mailbox from chat with host, username, and app password so the agent can validate the inbox and promotions folder."
                },
            ),
        ),
        nodes = listOf(
            PlannedTaskNode(
                id = "connect-mailbox",
                type = GoalNodeType.Connect,
                label = "Connect mailbox provider",
                status = mailboxRequirement.state,
            ),
            PlannedTaskNode(
                id = "classify-mail",
                type = GoalNodeType.Execute,
                label = "Classify promotional vs important mail",
                status = if (mailboxReady) ResourceConnectionState.Connected else ResourceConnectionState.Blocked,
            ),
            PlannedTaskNode(
                id = "deliver-mail-alerts",
                type = GoalNodeType.Deliver,
                label = "Alert on important mail",
                status = if (mailboxReady) deliveryState else ResourceConnectionState.Blocked,
            ),
        ),
        edges = listOf(
            TaskDependencyEdge("connect-mailbox", "classify-mail"),
            TaskDependencyEdge("classify-mail", "deliver-mail-alerts"),
        ),
        executionPolicy = if (mailboxReady) ExecutionPolicy.Automatic else ExecutionPolicy.RequiresConsent,
        deliveryPolicy = if (environment.deliveryCapabilityState == DeliveryCapabilityState.Ready) {
            DeliveryPolicy.PreferTelegramThenLocalPush
        } else {
            DeliveryPolicy.LocalPushOnly
        },
        recipe = VerticalSkillRecipe(
            goalType = AgentGoalType.EmailTriage,
            summary = "Email triage",
            capabilities = listOf("mail.connect", "mail.classify", "mail.move", "delivery.alert"),
        ),
        blockedReason = if (mailboxReady) null else mailboxRequirement.detail,
    )
}

private fun buildTelegramConnectionGoalPlan(environment: AgentEnvironmentSnapshot): AgentGoalPlan {
    val telegramRequirement = environment.capabilities.firstOrNull { it.capabilityId == "delivery.telegram" }
        ?: return AgentGoalPlan(
            type = AgentGoalType.TelegramConnect,
            summary = "Bind a Telegram bot token and a target chat.",
            requirements = emptyList(),
            nodes = emptyList(),
            edges = emptyList(),
            executionPolicy = ExecutionPolicy.RequiresConsent,
            deliveryPolicy = DeliveryPolicy.ChatSummaryOnly,
            recipe = VerticalSkillRecipe(
                goalType = AgentGoalType.TelegramConnect,
                summary = "Telegram connect",
                capabilities = listOf("delivery.telegram.connect"),
            ),
        )
    return AgentGoalPlan(
        type = AgentGoalType.TelegramConnect,
        summary = "Bind a Telegram bot token, test the target chat, and activate Telegram delivery.",
        requirements = listOf(
            ConnectionRequirement(
                capabilityId = telegramRequirement.capabilityId,
                label = telegramRequirement.label,
                state = telegramRequirement.state,
                nextStep = "Provide the bot token and target chat ID in chat so the runtime can store the secret and validate delivery.",
            ),
        ),
        nodes = listOf(
            PlannedTaskNode(
                id = "store-telegram-secret",
                type = GoalNodeType.Connect,
                label = "Store bot token",
                status = if (telegramRequirement.state == ResourceConnectionState.Connected) {
                    ResourceConnectionState.Connected
                } else {
                    ResourceConnectionState.NeedsSetup
                },
            ),
            PlannedTaskNode(
                id = "bind-telegram-chat",
                type = GoalNodeType.Connect,
                label = "Bind target chat",
                status = telegramRequirement.state,
            ),
            PlannedTaskNode(
                id = "deliver-telegram-test",
                type = GoalNodeType.Deliver,
                label = "Send a test message",
                status = telegramRequirement.state,
            ),
        ),
        edges = listOf(
            TaskDependencyEdge("store-telegram-secret", "bind-telegram-chat"),
            TaskDependencyEdge("bind-telegram-chat", "deliver-telegram-test"),
        ),
        executionPolicy = ExecutionPolicy.RequiresConsent,
        deliveryPolicy = DeliveryPolicy.ChatSummaryOnly,
        recipe = VerticalSkillRecipe(
            goalType = AgentGoalType.TelegramConnect,
            summary = "Telegram connect",
            capabilities = listOf("delivery.telegram.connect", "delivery.telegram.test"),
        ),
    )
}

private fun isMarketNewsGoal(normalizedPrompt: String): Boolean {
    return containsAny(
        normalizedPrompt,
        "kospi",
        "kosdaq",
        "주가",
        "코스피",
        "코스닥",
    ) && containsAny(
        normalizedPrompt,
        "news",
        "headline",
        "뉴스",
        "이슈",
        "브리핑",
        "watch",
        "monitor",
        "collect",
        "수집",
        "감시",
    )
}

private fun isMorningBriefingGoal(normalizedPrompt: String): Boolean {
    return containsAny(
        normalizedPrompt,
        "morning briefing",
        "wake",
        "wake-up",
        "기상",
        "모닝 브리핑",
        "아침 브리핑",
    )
}

private fun isEmailTriageGoal(normalizedPrompt: String): Boolean {
    return containsAny(
        normalizedPrompt,
        "email",
        "mailbox",
        "gmail",
        "이메일",
        "메일",
    ) && containsAny(
        normalizedPrompt,
        "important",
        "promo",
        "광고",
        "분류",
        "보관함",
        "알림",
        "triage",
    )
}

private fun isTelegramConnectionGoal(normalizedPrompt: String): Boolean {
    return containsAny(
        normalizedPrompt,
        "telegram",
        "텔레그램",
    ) && containsAny(
        normalizedPrompt,
        "connect",
        "setup",
        "bind",
        "연결",
        "설정",
        "붙여",
    )
}

private fun containsAny(
    value: String,
    vararg needles: String,
): Boolean {
    return needles.any { needle -> value.contains(needle) }
}
