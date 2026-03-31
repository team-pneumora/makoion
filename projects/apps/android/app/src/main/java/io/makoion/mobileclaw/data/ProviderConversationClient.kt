package io.makoion.mobileclaw.data

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

sealed interface ProviderConversationResult {
    data class Reply(
        val text: String,
        val providerId: String,
        val providerLabel: String,
        val model: String,
    ) : ProviderConversationResult

    data class Failure(
        val providerLabel: String? = null,
        val model: String? = null,
        val detail: String,
    ) : ProviderConversationResult

    data object Unavailable : ProviderConversationResult
}

interface ProviderConversationClient {
    suspend fun generateReply(
        prompt: String,
        recentMessages: List<ChatMessage>,
        context: AgentTurnContext,
    ): ProviderConversationResult
}

class HttpProviderConversationClient(
    private val settingsRepository: ModelProviderSettingsRepository,
    private val credentialVault: ModelProviderCredentialVault,
) : ProviderConversationClient {
    override suspend fun generateReply(
        prompt: String,
        recentMessages: List<ChatMessage>,
        context: AgentTurnContext,
    ): ProviderConversationResult {
        return withContext(Dispatchers.IO) {
            val profile = resolveConversationProviderProfile(
                preference = context.modelPreference,
                profiles = settingsRepository.profiles.value,
            ) ?: return@withContext ProviderConversationResult.Unavailable
            val credential = credentialVault.read(profile.providerId)
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: return@withContext ProviderConversationResult.Failure(
                    providerLabel = profile.displayName,
                    model = profile.selectedModel,
                    detail = "No stored credential is available for the selected provider.",
                )

            runCatching {
                when (profile.providerId) {
                    openAiProviderId -> requestOpenAiConversation(
                        profile = profile,
                        credential = credential,
                        prompt = prompt,
                        recentMessages = recentMessages,
                        context = context,
                    )
                    anthropicProviderId -> requestAnthropicConversation(
                        profile = profile,
                        credential = credential,
                        prompt = prompt,
                        recentMessages = recentMessages,
                        context = context,
                    )
                    else -> ProviderConversationResult.Unavailable
                }
            }.getOrElse { error ->
                ProviderConversationResult.Failure(
                    providerLabel = profile.displayName,
                    model = profile.selectedModel,
                    detail = error.message ?: error::class.java.simpleName,
                )
            }
        }
    }

    private fun requestOpenAiConversation(
        profile: ModelProviderProfileState,
        credential: String,
        prompt: String,
        recentMessages: List<ChatMessage>,
        context: AgentTurnContext,
    ): ProviderConversationResult {
        val historyText = buildConversationTranscript(
            recentMessages = recentMessages,
            currentPrompt = prompt,
        )
        val payload = JSONObject().apply {
            put("model", profile.selectedModel)
            put("max_output_tokens", maxConversationOutputTokens)
            put("instructions", buildConversationSystemPrompt(context))
            put("input", historyText)
        }
        val response = postJson(
            url = providerEndpoint(profile, "https://api.openai.com/v1/responses"),
            headers = mapOf(
                "Authorization" to "Bearer $credential",
            ),
            body = payload,
        )
        if (response.statusCode !in 200..299) {
            return ProviderConversationResult.Failure(
                providerLabel = profile.displayName,
                model = profile.selectedModel,
                detail = providerErrorDetail(response.body, response.statusCode),
            )
        }
        val text = extractOpenAiResponseText(response.body)
            ?: return ProviderConversationResult.Failure(
                providerLabel = profile.displayName,
                model = profile.selectedModel,
                detail = "The provider returned an empty response.",
            )
        return ProviderConversationResult.Reply(
            text = text,
            providerId = profile.providerId,
            providerLabel = profile.displayName,
            model = profile.selectedModel,
        )
    }

    private fun requestAnthropicConversation(
        profile: ModelProviderProfileState,
        credential: String,
        prompt: String,
        recentMessages: List<ChatMessage>,
        context: AgentTurnContext,
    ): ProviderConversationResult {
        val payload = JSONObject().apply {
            put("model", profile.selectedModel)
            put("max_tokens", maxConversationOutputTokens)
            put("system", buildConversationSystemPrompt(context))
            put(
                "messages",
                JSONArray().apply {
                    sanitizedConversationHistory(
                        recentMessages = recentMessages,
                        currentPrompt = prompt,
                    ).forEach { message ->
                        put(
                            JSONObject().apply {
                                put(
                                    "role",
                                    when (message.role) {
                                        ChatMessageRole.User -> "user"
                                        ChatMessageRole.Assistant -> "assistant"
                                    },
                                )
                                put("content", message.text)
                            },
                        )
                    }
                    put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
                        },
                    )
                },
            )
        }
        val response = postJson(
            url = providerEndpoint(profile, "https://api.anthropic.com/v1/messages"),
            headers = mapOf(
                "x-api-key" to credential,
                "anthropic-version" to anthropicApiVersion,
            ),
            body = payload,
        )
        if (response.statusCode !in 200..299) {
            return ProviderConversationResult.Failure(
                providerLabel = profile.displayName,
                model = profile.selectedModel,
                detail = providerErrorDetail(response.body, response.statusCode),
            )
        }
        val text = extractAnthropicResponseText(response.body)
            ?: return ProviderConversationResult.Failure(
                providerLabel = profile.displayName,
                model = profile.selectedModel,
                detail = "The provider returned an empty response.",
            )
        return ProviderConversationResult.Reply(
            text = text,
            providerId = profile.providerId,
            providerLabel = profile.displayName,
            model = profile.selectedModel,
        )
    }

    private fun postJson(
        url: String,
        headers: Map<String, String>,
        body: JSONObject,
    ): ProviderHttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = networkTimeoutMs
            readTimeout = networkTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            headers.forEach { (name, value) ->
                setRequestProperty(name, value)
            }
        }
        return connection.useConnection {
            outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(body.toString())
            }
            val statusCode = responseCode
            val stream = if (statusCode in 200..299) inputStream else errorStream
            val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            ProviderHttpResponse(
                statusCode = statusCode,
                body = responseBody,
            )
        }
    }

    private fun providerEndpoint(
        profile: ModelProviderProfileState,
        defaultUrl: String,
    ): String {
        val baseUrl = profile.baseUrl?.trim().orEmpty().trimEnd('/')
        return if (baseUrl.isBlank()) {
            defaultUrl
        } else {
            when (profile.providerId) {
                openAiProviderId -> "$baseUrl/responses"
                anthropicProviderId -> "$baseUrl/messages"
                else -> baseUrl
            }
        }
    }

    companion object {
        private const val openAiProviderId = "openai"
        private const val anthropicProviderId = "anthropic"
        private const val anthropicApiVersion = "2023-06-01"
        private const val maxConversationOutputTokens = 320
        private const val networkTimeoutMs = 30000
    }
}

private data class ProviderHttpResponse(
    val statusCode: Int,
    val body: String,
)

internal fun resolveConversationProviderProfile(
    preference: AgentModelPreference,
    profiles: List<ModelProviderProfileState>,
): ModelProviderProfileState? {
    val supportedProviders = setOf("openai", "anthropic")
    val eligibleProfiles = profiles.filter {
        it.providerId in supportedProviders &&
            it.enabled &&
            it.credentialStatus == ModelProviderCredentialStatus.Stored
    }
    if (eligibleProfiles.isEmpty()) {
        return null
    }
    return eligibleProfiles.firstOrNull { it.providerId == preference.preferredProviderId }
        ?: eligibleProfiles.firstOrNull(ModelProviderProfileState::isDefault)
        ?: eligibleProfiles.first()
}

internal fun buildConversationSystemPrompt(context: AgentTurnContext): String {
    val hasLocalFilesReady =
        context.fileIndexState.permissionGranted ||
            context.fileIndexState.documentTreeCount > 0 ||
            context.fileIndexState.indexedCount > 0
    return buildString {
        append("You are Makoion, a chat-first phone-local AI agent shell. ")
        append("Reply naturally, briefly, and truthfully. ")
        append("Do not claim you executed a task unless the runtime already did it. ")
        append("If the user asks for an unsupported or unavailable action, explain the nearest supported path or one missing setup item. ")
        append("Ask at most one concise follow-up question when required. ")
        append("Available capabilities include indexed file summary, organize planning with approval, transfer planning with approval, dashboard/history/settings routing, companion health/notify/open/workflow actions, browser research planning, automation planning, and code scaffold planning. ")
        append("Current state: indexed files ${context.fileIndexState.indexedCount}, ")
        append("local files ready $hasLocalFilesReady, ")
        append("document roots ${context.fileIndexState.documentTreeCount}, ")
        append("paired companions ${context.pairedDevices.size}, ")
        append("pending approvals ${context.approvals.count { it.status == ApprovalInboxStatus.Pending }}, ")
        append("configured providers ${context.modelPreference.configuredProviderIds.size}. ")
        append(chatAttachmentPromptSummary(context.attachments))
    }
}

internal fun extractOpenAiResponseText(raw: String): String? {
    val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
    val directOutputText = json.optString("output_text").trim()
    if (directOutputText.isNotBlank()) {
        return directOutputText
    }
    val outputs = json.optJSONArray("output") ?: return fallbackChoiceText(json)
    val parts = buildList {
        for (index in 0 until outputs.length()) {
            val item = outputs.optJSONObject(index) ?: continue
            val content = item.optJSONArray("content") ?: continue
            for (contentIndex in 0 until content.length()) {
                val entry = content.optJSONObject(contentIndex) ?: continue
                val text = entry.optString("text").trim()
                if (text.isNotBlank()) {
                    add(text)
                }
            }
        }
    }
    return parts.joinToString("\n").trim().ifBlank { fallbackChoiceText(json) }
}

internal fun extractAnthropicResponseText(raw: String): String? {
    val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
    val content = json.optJSONArray("content") ?: return null
    val parts = buildList {
        for (index in 0 until content.length()) {
            val entry = content.optJSONObject(index) ?: continue
            if (entry.optString("type") != "text") {
                continue
            }
            val text = entry.optString("text").trim()
            if (text.isNotBlank()) {
                add(text)
            }
        }
    }
    return parts.joinToString("\n").trim().ifBlank { null }
}

private fun fallbackChoiceText(json: JSONObject): String? {
    val choices = json.optJSONArray("choices") ?: return null
    val firstChoice = choices.optJSONObject(0) ?: return null
    val message = firstChoice.optJSONObject("message") ?: return null
    return message.optString("content").trim().ifBlank { null }
}

private fun sanitizedConversationHistory(
    recentMessages: List<ChatMessage>,
    currentPrompt: String,
): List<ChatMessage> {
    val trimmedPrompt = currentPrompt.trim()
    val trimmedHistory = recentMessages.takeLast(maxConversationHistoryMessages)
    return if (
        trimmedHistory.lastOrNull()?.role == ChatMessageRole.User &&
        trimmedHistory.lastOrNull()?.text?.trim() == trimmedPrompt
    ) {
        trimmedHistory.dropLast(1)
    } else {
        trimmedHistory
    }
}

internal fun buildConversationTranscript(
    recentMessages: List<ChatMessage>,
    currentPrompt: String,
): String {
    return buildString {
        sanitizedConversationHistory(
            recentMessages = recentMessages,
            currentPrompt = currentPrompt,
        ).forEach { message ->
            append(
                when (message.role) {
                    ChatMessageRole.User -> "User"
                    ChatMessageRole.Assistant -> "Assistant"
                },
            )
            append(": ")
            val body = message.text.takeIf(String::isNotBlank)
            if (body != null) {
                append(body)
            }
            if (message.attachments.isNotEmpty()) {
                if (body != null) {
                    append(" ")
                }
                append("[Attachments: ")
                append(chatAttachmentSummaryLine(message.attachments))
                append("]")
            }
            append("\n")
        }
        append("User: ")
        if (currentPrompt.isNotBlank()) {
            append(currentPrompt)
        } else {
            append("(no text)")
        }
    }
}

private fun providerErrorDetail(
    raw: String,
    statusCode: Int,
): String {
    val json = runCatching { JSONObject(raw) }.getOrNull()
    val directError = json?.optJSONObject("error")?.optString("message")?.trim().orEmpty()
    if (directError.isNotBlank()) {
        return "HTTP $statusCode: $directError"
    }
    val anthropicError = json?.optString("error")?.trim().orEmpty()
    if (anthropicError.isNotBlank()) {
        return "HTTP $statusCode: $anthropicError"
    }
    return "HTTP $statusCode"
}

private inline fun <T> HttpURLConnection.useConnection(block: HttpURLConnection.() -> T): T {
    return try {
        block()
    } finally {
        disconnect()
    }
}

private const val maxConversationHistoryMessages = 8
