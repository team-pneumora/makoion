package io.makoion.mobileclaw.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimePlanningTest {
    @Test
    fun `environment snapshot surfaces connected telegram and mcp capabilities`() {
        val snapshot = buildAgentEnvironmentSnapshot(
            testContext(
                externalEndpoints = listOf(
                    ExternalEndpointProfileState(
                        endpointId = "companion-mcp-bridge",
                        displayName = "Companion MCP",
                        category = ExternalEndpointCategory.McpServer,
                        status = ExternalEndpointStatus.Connected,
                        summary = "Connected",
                        supportedCapabilities = listOf("mcp.tools", "browser.automation"),
                        toolNames = listOf("browser.open"),
                        syncedSkillCount = 2,
                        updatedAtEpochMillis = 0L,
                        updatedAtLabel = "now",
                    ),
                ),
                deliveryChannels = listOf(
                    DeliveryChannelProfileState(
                        channelId = "phone-local-notification",
                        displayName = "Phone local notification",
                        type = DeliveryChannelType.LocalNotification,
                        status = DeliveryChannelStatus.Connected,
                        summary = "Connected",
                        supportedDeliveries = listOf("notifications.local"),
                        updatedAtEpochMillis = 0L,
                        updatedAtLabel = "now",
                    ),
                    DeliveryChannelProfileState(
                        channelId = "telegram-bot-delivery",
                        displayName = "Telegram bot relay",
                        type = DeliveryChannelType.TelegramBot,
                        status = DeliveryChannelStatus.Connected,
                        summary = "Connected",
                        supportedDeliveries = listOf("notifications.telegram"),
                        destinationLabel = "Chat -100123",
                        address = "-100123",
                        updatedAtEpochMillis = 0L,
                        updatedAtLabel = "now",
                    ),
                ),
            ),
        )

        assertTrue(snapshot.hasCapability("mcp.bridge"))
        assertTrue(snapshot.hasCapability("delivery.telegram"))
        assertEquals(DeliveryCapabilityState.Ready, snapshot.deliveryCapabilityState)
    }

    @Test
    fun `goal planner maps market news prompt to market watch recipe`() {
        val plan = planAgentGoal(
            prompt = "코스피 코스닥 뉴스 이슈를 모아서 중요한 것만 알려줘",
            context = testContext(),
        )

        assertNotNull(plan)
        assertEquals(AgentGoalType.MarketNewsWatch, plan?.type)
        assertTrue(plan!!.nodes.any { it.type == GoalNodeType.Deliver })
    }

    @Test
    fun `goal planner marks email triage as blocked without mailbox connector`() {
        val plan = planAgentGoal(
            prompt = "광고 메일은 보관함으로 옮기고 중요한 메일은 알림 줘",
            context = testContext(),
        )

        assertNotNull(plan)
        assertEquals(AgentGoalType.EmailTriage, plan?.type)
        assertTrue(plan!!.blockedReason!!.contains("mailbox", ignoreCase = true))
    }

    @Test
    fun `environment snapshot surfaces connected mailbox capability`() {
        val snapshot = buildAgentEnvironmentSnapshot(
            testContext(
                mailboxConnections = listOf(
                    MailboxConnectionProfileState(
                        mailboxId = primaryMailboxConnectionId,
                        displayName = "Primary mailbox",
                        status = MailboxConnectionStatus.Connected,
                        summary = "Validated",
                        host = "imap.gmail.com",
                        port = 993,
                        username = "me@example.com",
                        inboxFolder = "INBOX",
                        promotionsFolder = "Promotions",
                        updatedAtEpochMillis = 0L,
                        updatedAtLabel = "now",
                    ),
                ),
            ),
        )

        assertTrue(snapshot.hasCapability("mailbox.connector"))
    }

    @Test
    fun `goal planner unblocks email triage when mailbox is connected`() {
        val plan = planAgentGoal(
            prompt = "광고 메일은 보관함으로 옮기고 중요한 메일은 알림 줘",
            context = testContext(
                mailboxConnections = listOf(
                    MailboxConnectionProfileState(
                        mailboxId = primaryMailboxConnectionId,
                        displayName = "Primary mailbox",
                        status = MailboxConnectionStatus.Connected,
                        summary = "Validated",
                        host = "imap.gmail.com",
                        port = 993,
                        username = "me@example.com",
                        inboxFolder = "INBOX",
                        promotionsFolder = "Promotions",
                        updatedAtEpochMillis = 0L,
                        updatedAtLabel = "now",
                    ),
                ),
            ),
        )

        assertNotNull(plan)
        assertEquals(AgentGoalType.EmailTriage, plan?.type)
        assertNull(plan?.blockedReason)
        assertTrue(plan!!.nodes.any { it.id == "classify-mail" && it.status == ResourceConnectionState.Connected })
    }

    @Test
    fun `scheduled run spec detects morning briefing and telegram delivery`() {
        val spec = buildScheduledAgentRunSpec("매일 아침 모닝 브리핑을 텔레그램으로 보내줘")

        assertEquals(ScheduledAgentGoalKind.MorningBriefing, spec.goalKind)
        assertEquals(DeliveryTargetPolicy.PreferTelegramThenLocalPush, spec.deliveryPolicy)
    }

    @Test
    fun `email setup question does not get misclassified as triage goal`() {
        val plan = planAgentGoal(
            prompt = "이메일 자동화 가능해? 어떻게 연결해?",
            context = testContext(),
        )

        assertNull(plan)
    }

    @Test
    fun `browser capability question is detected separately from browser research`() {
        assertTrue(looksLikeBrowserCapabilityQuestion("웹 페이지 접근도 가능해?"))
        assertTrue(looksLikeBrowserCapabilityQuestion("can you access web pages directly?"))
    }

    @Test
    fun `browser research request is not treated as a capability question`() {
        assertEquals(false, looksLikeBrowserCapabilityQuestion("웹에서 오늘 뉴스 조사해줘"))
        assertEquals(false, looksLikeBrowserCapabilityQuestion("search the web and summarize the latest news"))
    }

    @Test
    fun `extract first web url from prompt`() {
        assertEquals(
            "https://example.com/docs?a=1",
            extractFirstWebUrl("이 페이지 열어줘 https://example.com/docs?a=1 그리고 요약해줘"),
        )
        assertEquals(
            "example.com/docs",
            extractFirstWebUrl("example.com/docs 열어줘"),
        )
    }

    private fun testContext(
        externalEndpoints: List<ExternalEndpointProfileState> = emptyList(),
        mailboxConnections: List<MailboxConnectionProfileState> = emptyList(),
        deliveryChannels: List<DeliveryChannelProfileState> = listOf(
            DeliveryChannelProfileState(
                channelId = "phone-local-notification",
                displayName = "Phone local notification",
                type = DeliveryChannelType.LocalNotification,
                status = DeliveryChannelStatus.Connected,
                summary = "Connected",
                supportedDeliveries = listOf("notifications.local"),
                updatedAtEpochMillis = 0L,
                updatedAtLabel = "now",
            ),
        ),
    ): AgentTurnContext {
        return AgentTurnContext(
            fileIndexState = FileIndexState(permissionGranted = true, indexedCount = 12, documentTreeCount = 1),
            approvals = emptyList(),
            tasks = emptyList(),
            auditEvents = emptyList(),
            pairedDevices = emptyList(),
            selectedTargetDeviceId = null,
            cloudDriveConnections = emptyList(),
            modelPreference = AgentModelPreference(
                preferredProviderId = "openai",
                preferredProviderLabel = "OpenAI",
                preferredModel = "gpt-5.4",
                enabledProviderIds = listOf("openai"),
                configuredProviderIds = listOf("openai"),
            ),
            externalEndpoints = externalEndpoints,
            deliveryChannels = deliveryChannels,
            mailboxConnections = mailboxConnections,
            scheduledAutomations = emptyList(),
        )
    }
}
