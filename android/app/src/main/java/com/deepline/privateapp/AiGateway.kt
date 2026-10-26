package com.deepline.privateapp

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** One API client for DeepSeek, OpenAI-compatible vision APIs, Claude Messages, and Doubao Ark. */
object AiGateway {
    private val client = OkHttpClient.Builder().connectTimeout(25, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()

    suspend fun reply(context: Context, provider: String, apiKey: String, model: String, system: String, history: JSONArray): String = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "请先填写所选服务商的 API Key" }
        val p = provider.ifBlank { "DeepSeek" }
        val m = model.ifBlank { defaultModel(p) }
        val url = when (p) {
            "Claude" -> "https://api.anthropic.com/v1/messages"
            "豆包" -> "https://ark.cn-beijing.volces.com/api/v3/chat/completions"
            "ChatGPT / OpenAI" -> "https://api.openai.com/v1/chat/completions"
            else -> "https://api.deepseek.com/chat/completions"
        }
        val body = if (p == "Claude") buildClaudeBody(context, m, system, history) else buildOpenAiBody(context, p, m, system, history)
        val builder = Request.Builder().url(url).post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Content-Type", "application/json")
        when (p) {
            "Claude" -> builder.header("x-api-key", apiKey).header("anthropic-version", "2023-06-01")
            else -> builder.header("Authorization", "Bearer $apiKey")
        }
        client.newCall(builder.build()).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val json = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
            if (!response.isSuccessful) {
                val detail = json.optJSONObject("error")?.optString("message")
                    ?: json.optString("message").takeIf { it.isNotBlank() }
                    ?: raw.take(400).ifBlank { "接口未返回错误详情" }
                throw IllegalStateException("$p 请求失败（HTTP ${response.code}）：$detail")
            }
            val answer = if (p == "Claude") {
                json.optJSONArray("content")?.let { blocks -> (0 until blocks.length()).mapNotNull { blocks.optJSONObject(it)?.optString("text") }.joinToString("\n") }
            } else {
                val content = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.opt("content")
                when (content) {
                    is String -> content
                    is JSONArray -> (0 until content.length()).mapNotNull { content.optJSONObject(it)?.optString("text") }.joinToString("\n")
                    else -> null
                }
            }
            answer?.trim()?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("$p 接口返回成功，但没有可显示的回复。")
        }
    }

    fun defaultModel(provider: String): String = when (provider) {
        "Claude" -> "claude-sonnet-5"
        "豆包" -> "doubao-seed-2-1-pro-260915"
        "ChatGPT / OpenAI" -> "gpt-4.1-mini"
        else -> "deepseek-chat"
    }

    private fun buildOpenAiBody(context: Context, provider: String, model: String, system: String, history: JSONArray): JSONObject {
        if (provider == "DeepSeek" && (0 until history.length()).any { imageMarker(history.optJSONObject(it)?.optString("content").orEmpty()) != null }) {
            throw IllegalStateException("当前 DeepSeek 文本模型不能保证直接识图。请将该 AI 切换到支持视觉输入的 Claude、ChatGPT 或豆包视觉模型；应用不会把图片伪装成文字标签。")
        }
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        for (i in 0 until history.length()) {
            val m = history.optJSONObject(i) ?: continue
            val role = m.optString("role").let { if (it == "assistant") "assistant" else "user" }
            val raw = m.optString("content")
            val image = imageMarker(raw)
            if (image != null) {
                val file = resolveImageFile(image)
                    ?: throw IllegalStateException("图片文件找不到，无法把真实图片发送给 AI。请重新选择图片。")
                val mime = when (file.extension.lowercase()) { "png" -> "image/png"; "webp" -> "image/webp"; "gif" -> "image/gif"; else -> "image/jpeg" }
                val b64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                val promptText = attachmentCaption(raw).ifBlank { "请认真查看这张实际上传的图片，描述你能确认的内容，并回答用户的问题。" }
                val parts = JSONArray()
                    .put(JSONObject().put("type", "text").put("text", promptText))
                    .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:$mime;base64,$b64").put("detail", "high")))
                messages.put(JSONObject().put("role", role).put("content", parts))
            } else {
                messages.put(JSONObject().put("role", role).put("content", raw))
            }
        }
        return JSONObject().put("model", model).put("messages", messages).put("max_tokens", 2048)
    }

    private fun buildClaudeBody(context: Context, model: String, system: String, history: JSONArray): JSONObject {
        val messages = JSONArray()
        for (i in 0 until history.length()) {
            val m = history.optJSONObject(i) ?: continue
            val role = if (m.optString("role") == "assistant") "assistant" else "user"
            val raw = m.optString("content")
            val image = imageMarker(raw)
            if (image != null) {
                val file = resolveImageFile(image) ?: throw IllegalStateException("图片文件找不到，无法把真实图片发送给 Claude。请重新选择图片。")
                val mime = when (file.extension.lowercase()) { "png" -> "image/png"; "webp" -> "image/webp"; "gif" -> "image/gif"; else -> "image/jpeg" }
                val b64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                val text = attachmentCaption(raw).ifBlank { "请查看这张真实图片并分析可见内容。" }
                val content = JSONArray()
                    .put(JSONObject().put("type", "text").put("text", text))
                    .put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", mime).put("data", b64)))
                messages.put(JSONObject().put("role", role).put("content", content))
            } else {
                messages.put(JSONObject().put("role", role).put("content", raw))
            }
        }
        return JSONObject().put("model", model).put("max_tokens", 2048).put("system", system).put("messages", messages)
    }

    private fun imageMarker(raw: String): String? = Regex("\\[\\[(?:IMAGE|GIF):(.*?)]]", RegexOption.DOT_MATCHES_ALL).find(raw)?.groupValues?.getOrNull(1)

    private fun attachmentCaption(raw: String): String {
        val starts = listOf(raw.indexOf("[[IMAGE:"), raw.indexOf("[[GIF:")).filter { it >= 0 }
        return if (starts.isEmpty()) raw.trim() else raw.substring(0, starts.minOrNull()!!).trim()
    }

    private fun resolveImageFile(value: String): File? = try {
        val uri = Uri.parse(value)
        when (uri.scheme) {
            "file" -> uri.path?.let(::File)?.takeIf { it.exists() && it.isFile }
            else -> if (value.startsWith("/")) File(value).takeIf { it.exists() } else null
        }
    } catch (_: Exception) { null }
}
