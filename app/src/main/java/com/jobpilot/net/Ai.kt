package com.jobpilot.net

import android.util.Base64
import com.jobpilot.data.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Calls a language model with the user's own API key, straight from the phone.
 * Two wire formats are supported: Anthropic's Messages API, and the OpenAI chat-completions
 * format that most other providers (and local servers) also accept.
 */
object Ai {
    private val json = Json { ignoreUnknownKeys = true }
    private val client by lazy {
        Http.client.newBuilder().readTimeout(180, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).build()
    }

    fun defaultModel(provider: String) = if (provider == "anthropic") "claude-sonnet-5-5" else ""

    private fun str(o: JsonObject?, key: String): String = (o?.get(key) as? JsonPrimitive)?.content.orEmpty()

    suspend fun complete(p: Profile, system: String, user: String, pdf: File? = null, maxTokens: Int = 3000): String =
        withContext(Dispatchers.IO) {
            if (p.aiKey.isBlank()) throw IOException("Add an AI API key in Settings first.")
            val anthropic = p.aiProvider == "anthropic"
            val model = p.aiModel.trim().ifBlank { defaultModel(p.aiProvider) }
            if (model.isBlank()) throw IOException("Enter the model name in Settings.")
            val pdfB64 = pdf?.let { Base64.encodeToString(it.readBytes(), Base64.NO_WRAP) }

            val request = if (anthropic) {
                val base = p.aiBaseUrl.trim().trimEnd('/').ifBlank { "https://api.anthropic.com" }
                val body = buildJsonObject {
                    put("model", model)
                    put("max_tokens", maxTokens)
                    put("system", system)
                    putJsonArray("messages") {
                        addJsonObject {
                            put("role", "user")
                            putJsonArray("content") {
                                if (pdfB64 != null) addJsonObject {
                                    put("type", "document")
                                    putJsonObject("source") {
                                        put("type", "base64"); put("media_type", "application/pdf"); put("data", pdfB64)
                                    }
                                }
                                addJsonObject { put("type", "text"); put("text", user) }
                            }
                        }
                    }
                }
                Request.Builder().url(if (base.endsWith("/v1")) "$base/messages" else "$base/v1/messages")
                    .header("x-api-key", p.aiKey.trim())
                    .header("anthropic-version", "2023-06-01")
                    .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            } else {
                val base = p.aiBaseUrl.trim().trimEnd('/').ifBlank { "https://api.openai.com/v1" }
                val body = buildJsonObject {
                    put("model", model)
                    putJsonArray("messages") {
                        addJsonObject { put("role", "system"); put("content", system) }
                        addJsonObject {
                            put("role", "user")
                            if (pdfB64 == null) put("content", user) else putJsonArray("content") {
                                addJsonObject {
                                    put("type", "file")
                                    putJsonObject("file") {
                                        put("filename", "resume.pdf"); put("file_data", "data:application/pdf;base64,$pdfB64")
                                    }
                                }
                                addJsonObject { put("type", "text"); put("text", user) }
                            }
                        }
                    }
                }
                Request.Builder().url("$base/chat/completions")
                    .header("Authorization", "Bearer ${p.aiKey.trim()}")
                    .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            }

            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                if (!resp.isSuccessful) {
                    val detail = str(root?.get("error") as? JsonObject, "message").ifBlank { text.take(200) }
                    throw IOException(
                        when (resp.code) {
                            401, 403 -> "The AI provider rejected the API key."
                            404 -> "The AI provider doesn't know that model or address. Check them in Settings. $detail"
                            429 -> "The AI provider says you are over your rate or credit limit."
                            else -> "AI request failed (${resp.code}). $detail"
                        }.trim()
                    )
                }
                val out = if (anthropic) {
                    (root?.get("content") as? JsonArray).orEmpty()
                        .mapNotNull { it as? JsonObject }.filter { str(it, "type") == "text" }.joinToString("") { str(it, "text") }
                } else {
                    val first = (root?.get("choices") as? JsonArray)?.firstOrNull() as? JsonObject
                    str(first?.get("message") as? JsonObject, "content")
                }
                if (out.isBlank()) throw IOException("The AI returned an empty answer. Try again.")
                out.trim()
            }
        }
}
