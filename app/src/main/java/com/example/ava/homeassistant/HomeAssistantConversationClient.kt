package com.example.ava.homeassistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL

/**
 * The response of a conversation agent to a text command.
 */
data class ConversationResult(
    val speech: String,
    val conversationId: String?,
    val continueConversation: Boolean
)

/**
 * Sends a text command to a conversation agent.
 */
interface ConversationClient {
    /**
     * Returns the agent's response, or null if the agent couldn't be reached.
     */
    suspend fun process(text: String, conversationId: String?): ConversationResult?
}

private const val TIMEOUT_MS = 30000

/**
 * Sends text commands to Home Assistant's conversation API on behalf of this satellite,
 * so that the agent knows which device the command came from.
 *
 * @param url The base URL of Home Assistant, e.g. http://127.0.0.1:8123
 * @param token A long-lived access token.
 * @param agentId The conversation agent, empty for Home Assistant's default agent.
 * @param macAddress The MAC address the satellite reports to Home Assistant,
 * used to look up its device.
 */
class HomeAssistantConversationClient(
    private val url: suspend () -> String,
    private val token: suspend () -> String,
    private val agentId: suspend () -> String,
    private val macAddress: suspend () -> String
) : ConversationClient {
    private var deviceId: String? = null

    override suspend fun process(text: String, conversationId: String?): ConversationResult? =
        withContext(Dispatchers.IO) {
            try {
                val agent = agentId()
                val device = deviceId()
                val request = buildJsonObject {
                    put("text", text)
                    if (conversationId != null) put("conversation_id", conversationId)
                    if (agent.isNotBlank()) put("agent_id", agent)
                    if (device != null) put("device_id", device)
                }
                val response = Json.parseToJsonElement(post("/api/conversation/process", request))
                    .jsonObject
                val speech = response["response"]?.jsonObject?.get("speech")?.jsonObject
                    ?.get("plain")?.jsonObject?.get("speech")?.jsonPrimitive?.contentOrNull ?: ""
                ConversationResult(
                    speech = speech,
                    conversationId = response["conversation_id"]?.jsonPrimitive?.contentOrNull,
                    continueConversation = response["continue_conversation"]?.jsonPrimitive?.booleanOrNull
                        ?: false
                )
            } catch (e: Exception) {
                Timber.w(e, "Failed to send the command to Home Assistant")
                null
            }
        }

    /**
     * Looks up this satellite's device in Home Assistant by its MAC address.
     * Only a found device is cached, so a lookup before the device is added is retried.
     */
    private suspend fun deviceId(): String? {
        deviceId?.let { return it }
        val mac = macAddress().lowercase()
        val template = "{% set ns = namespace(id='') %}" +
                "{% for d in integration_entities('esphome') | map('device_id') | unique %}" +
                "{% if ('mac', '$mac') in device_attr(d, 'connections') %}{% set ns.id = d %}{% endif %}" +
                "{% endfor %}{{ ns.id }}"
        val id = post("/api/template", buildJsonObject { put("template", template) }).trim()
        if (id.isEmpty()) {
            Timber.w("No Home Assistant device found for $mac")
            return null
        }
        deviceId = id
        return id
    }

    private suspend fun post(path: String, body: JsonObject): String {
        val connection = URL(url().trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer ${token()}")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code from $path")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
