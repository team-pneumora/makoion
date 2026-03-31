package io.makoion.mobileclaw.data

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

sealed interface TelegramDeliveryResult {
    data class Delivered(
        val detail: String,
    ) : TelegramDeliveryResult

    data class Failed(
        val detail: String,
    ) : TelegramDeliveryResult
}

interface TelegramDeliveryGateway {
    suspend fun sendMessage(
        botToken: String,
        chatId: String,
        text: String,
    ): TelegramDeliveryResult
}

class HttpTelegramDeliveryGateway : TelegramDeliveryGateway {
    override suspend fun sendMessage(
        botToken: String,
        chatId: String,
        text: String,
    ): TelegramDeliveryResult {
        return withContext(Dispatchers.IO) {
            runCatching {
                val payload = JSONObject().apply {
                    put("chat_id", chatId)
                    put("text", text)
                    put("disable_web_page_preview", true)
                }
                val connection = (URL("https://api.telegram.org/bot${botToken.trim()}/sendMessage").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = networkTimeoutMs
                    readTimeout = networkTimeoutMs
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                }
                connection.useConnection {
                    outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                        writer.write(payload.toString())
                    }
                    val statusCode = responseCode
                    val response = (if (statusCode in 200..299) inputStream else errorStream)
                        ?.bufferedReader(Charsets.UTF_8)
                        ?.use { it.readText() }
                        .orEmpty()
                    if (statusCode !in 200..299) {
                        return@useConnection TelegramDeliveryResult.Failed("HTTP $statusCode")
                    }
                    val ok = runCatching { JSONObject(response).optBoolean("ok") }.getOrDefault(false)
                    if (ok) {
                        TelegramDeliveryResult.Delivered("Delivered to chat $chatId.")
                    } else {
                        TelegramDeliveryResult.Failed("Telegram API rejected the request.")
                    }
                }
            }.getOrElse { error ->
                TelegramDeliveryResult.Failed(error.message ?: error::class.java.simpleName)
            }
        }
    }

    companion object {
        private const val networkTimeoutMs = 10_000
    }
}

private inline fun <T> HttpURLConnection.useConnection(block: HttpURLConnection.() -> T): T {
    return try {
        block()
    } finally {
        disconnect()
    }
}
