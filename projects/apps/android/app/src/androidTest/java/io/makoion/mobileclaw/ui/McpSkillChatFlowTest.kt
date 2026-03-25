package io.makoion.mobileclaw.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.makoion.mobileclaw.MainActivity
import io.makoion.mobileclaw.MobileClawApplication
import io.makoion.mobileclaw.data.AgentTurnContext
import io.makoion.mobileclaw.data.ExternalEndpointStatus
import io.makoion.mobileclaw.data.resolveAgentModelPreference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class McpSkillChatFlowTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun connectAndSyncMcpSkillsFromChat() {
        MockWebServer().use { server ->
            repeat(2) {
                server.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .setBody(
                            """
                            {
                              "status": "ok",
                              "server_label": "Mock desktop MCP relay",
                              "transport_label": "Direct HTTP bridge",
                              "auth_label": "Trusted secret + local policy gate",
                              "capabilities": [
                                "mcp.connect",
                                "mcp.tools.list",
                                "mcp.skills.sync",
                                "session.notify",
                                "app.open",
                                "workflow.run",
                                "files.transfer"
                              ],
                              "tool_names": [
                                "desktop.session.notify",
                                "desktop.app.open",
                                "desktop.workflow.run",
                                "files.transfer.receive",
                                "browser.research.plan",
                                "api.request.ingest"
                              ],
                              "tool_schemas": [
                                {
                                  "name": "desktop.session.notify",
                                  "title": "Desktop Session Notify",
                                  "summary": "Show a guarded desktop notification on the paired companion.",
                                  "input_schema_summary": "title:string, body:string",
                                  "requires_confirmation": false
                                },
                                {
                                  "name": "desktop.app.open",
                                  "title": "Desktop App Open",
                                  "summary": "Open an approved desktop surface such as inbox or latest transfer.",
                                  "input_schema_summary": "target_kind:string, target_label?:string, open_mode:string",
                                  "requires_confirmation": true
                                },
                                {
                                  "name": "browser.research.plan",
                                  "title": "Browser Research Plan",
                                  "summary": "Stage a browser research brief for later guarded execution.",
                                  "input_schema_summary": "topic:string, objective:string",
                                  "requires_confirmation": false
                                }
                              ],
                              "skill_bundles": [
                                {
                                  "bundle_id": "desktop_action_bridge",
                                  "title": "Desktop action bridge",
                                  "summary": "Routes guarded notify, open, and workflow actions through the paired companion.",
                                  "tool_names": ["desktop.session.notify", "desktop.app.open", "desktop.workflow.run"],
                                  "version_label": "2026.03"
                                },
                                {
                                  "bundle_id": "browser_research_handoff",
                                  "title": "Browser research handoff",
                                  "summary": "Stages browser research briefs for later guarded execution.",
                                  "tool_names": ["browser.research.plan"],
                                  "version_label": "2026.03"
                                },
                                {
                                  "bundle_id": "external_api_ingest",
                                  "title": "External API ingest",
                                  "summary": "Registers API ingest work for later guarded execution and parsing.",
                                  "tool_names": ["api.request.ingest"],
                                  "version_label": "2026.03"
                                }
                              ],
                              "workflow_ids": [
                                "open_latest_transfer",
                                "open_actions_folder"
                              ],
                              "status_detail": "Mock MCP discovery endpoint is ready."
                            }
                            """.trimIndent(),
                        ),
                )
            }

            val deviceId = runBlocking {
                ensureDirectHttpCompanion(server.url("/api/v1/transfers").toString())
            }

            runBlocking {
                application.appContainer.agentTaskEngine.submitTurn(
                    threadId = "thread-instrumentation-mcp",
                    prompt = "Connect the MCP bridge",
                    context = buildTurnContext(),
                )
            }

            composeRule.waitUntil(timeoutMillis = 15_000) {
                application.appContainer.externalEndpointRepository.profiles.value.any {
                    it.endpointId == "companion-mcp-bridge" &&
                        it.status == ExternalEndpointStatus.Connected &&
                        it.toolNames.size >= 3
                }
            }

            val statusExecution = runBlocking {
                application.appContainer.agentTaskEngine.submitTurn(
                    threadId = "thread-instrumentation-mcp",
                    prompt = "Show MCP status",
                    context = buildTurnContext(),
                )
            }

            val syncExecution = runBlocking {
                application.appContainer.agentTaskEngine.submitTurn(
                    threadId = "thread-instrumentation-mcp",
                    prompt = "Update MCP skills from the MCP bridge",
                    context = buildTurnContext(),
                )
            }

            composeRule.waitUntil(timeoutMillis = 15_000) {
                application.appContainer.mcpSkillRepository.skills.value.size >= 3
            }

            val toolsExecution = runBlocking {
                application.appContainer.agentTaskEngine.submitTurn(
                    threadId = "thread-instrumentation-mcp",
                    prompt = "List MCP tools",
                    context = buildTurnContext(),
                )
            }

            val endpoint = application.appContainer.externalEndpointRepository.profiles.value.first {
                it.endpointId == "companion-mcp-bridge"
            }
            val pairedDevice = application.appContainer.devicePairingRepository.pairedDevices.value.first {
                it.id == deviceId
            }
            val recordedRequest = server.takeRequest(5, TimeUnit.SECONDS)

            assertEquals(
                "Expected the MCP bridge to be recorded as connected.",
                ExternalEndpointStatus.Connected,
                endpoint.status,
            )
            assertNotNull(
                "Expected the initial MCP discovery request to reach the mock server.",
                recordedRequest,
            )
            assertTrue(
                "Expected MCP skill sync to install at least the seeded skill catalog.",
                application.appContainer.mcpSkillRepository.skills.value.size >= 3,
            )
            assertEquals(
                "Expected MCP discovery to target the discovery route.",
                "/api/v1/mcp/discovery",
                recordedRequest?.path,
            )
            assertNotNull(
                "Expected the discovery request to carry the trusted secret header.",
                recordedRequest?.getHeader("X-MobileClaw-Trusted-Secret"),
            )
            assertFalse(
                "Expected the paired device to advertise capabilities after discovery.",
                pairedDevice.capabilities.isEmpty(),
            )
            assertTrue(
                "Expected the endpoint profile to persist MCP tool schemas from discovery.",
                endpoint.toolSchemas.any { it.name == "desktop.app.open" && it.requiresConfirmation },
            )
            assertTrue(
                "Expected the endpoint profile to persist advertised skill bundles.",
                endpoint.skillBundles.any { it.bundleId == "desktop_action_bridge" },
            )
            assertTrue(
                "Expected the endpoint profile to persist advertised workflow ids.",
                endpoint.workflowIds.contains("open_latest_transfer"),
            )
            assertTrue(
                "Expected the MCP status reply to include the Direct HTTP bridge transport.",
                statusExecution.turnResult.reply.contains("Direct HTTP bridge", ignoreCase = true),
            )
            assertTrue(
                "Expected the MCP status reply to include skill bundle inventory.",
                statusExecution.turnResult.reply.contains("skill bundle", ignoreCase = true),
            )
            assertTrue(
                "Expected the skill sync reply to mention the connector tool inventory.",
                syncExecution.turnResult.reply.contains("tool", ignoreCase = true),
            )
            assertTrue(
                "Expected the MCP tools reply to list desktop.app.open.",
                toolsExecution.turnResult.reply.contains("desktop.app.open", ignoreCase = true),
            )
            assertTrue(
                "Expected the MCP tools reply to surface tool schema detail.",
                toolsExecution.turnResult.reply.contains("approved desktop surface", ignoreCase = true),
            )
            assertTrue(
                "Expected the endpoint profile to persist the synced skill count and last sync time.",
                endpoint.syncedSkillCount >= 3 && endpoint.lastSyncAtEpochMillis != null,
            )
        }
    }

    private suspend fun ensureDirectHttpCompanion(endpointUrl: String): String {
        val pairingRepository = application.appContainer.devicePairingRepository
        pairingRepository.refresh()
        var device = pairingRepository.pairedDevices.value.firstOrNull()
        if (device == null) {
            pairingRepository.startPairing()
            pairingRepository.refresh()
            val session = pairingRepository.pairingSessions.value.first()
            pairingRepository.approvePairing(session.id)
            pairingRepository.refresh()
            device = pairingRepository.pairedDevices.value.first()
        }
        pairingRepository.armDirectHttpBridge(device.id)
        pairingRepository.setDirectHttpEndpoint(
            device.id,
            endpointUrl,
        )
        pairingRepository.refresh()
        return device.id
    }

    private val application: MobileClawApplication
        get() = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as MobileClawApplication

    private suspend fun buildTurnContext(): AgentTurnContext {
        val appContainer = application.appContainer
        val fileIndexState = appContainer.fileIndexRepository.refreshIndex()
        appContainer.approvalInboxRepository.refresh()
        appContainer.agentTaskRepository.refresh()
        appContainer.auditTrailRepository.refresh()
        appContainer.devicePairingRepository.refresh()
        appContainer.cloudDriveConnectionRepository.refresh()
        appContainer.externalEndpointRepository.refresh()
        appContainer.deliveryChannelRepository.refresh()
        appContainer.scheduledAutomationRepository.refresh()
        return AgentTurnContext(
            fileIndexState = fileIndexState,
            approvals = appContainer.approvalInboxRepository.items.value,
            tasks = appContainer.agentTaskRepository.tasks.value,
            auditEvents = appContainer.auditTrailRepository.events.value,
            pairedDevices = appContainer.devicePairingRepository.pairedDevices.value,
            selectedTargetDeviceId = null,
            cloudDriveConnections = appContainer.cloudDriveConnectionRepository.connections.value,
            modelPreference = resolveAgentModelPreference(appContainer.modelProviderSettingsRepository.profiles.value),
            externalEndpoints = appContainer.externalEndpointRepository.profiles.value,
            deliveryChannels = appContainer.deliveryChannelRepository.profiles.value,
            scheduledAutomations = appContainer.scheduledAutomationRepository.automations.value,
            selectedFileId = null,
        )
    }
}
