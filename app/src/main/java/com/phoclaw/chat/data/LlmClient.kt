package com.phoclaw.chat.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 多模态消息的一个内容块。
 *
 * 纯文本消息不会用到它 —— 只有带图片的当前轮才组装成 parts。
 * 这个区分是刻意的：把纯文本也塞进数组会让不支持多模态的后端直接 400。
 */
sealed interface ContentPart {
    data class Text(val text: String) : ContentPart

    /**
     * 图片块。
     *
     * [base64] 是**不含** `data:` 前缀的裸串，[mime] 单独存着，
     * 拼装成 data URI 时才合成。这样 base64 可以放心缓存复用。
     */
    data class Image(val mime: String, val base64: String) : ContentPart
}

/** 一条对话消息。 */
data class ChatMessage(
    val role: String,
    val content: String,
    val isError: Boolean = false,
    /**
     * 非空时 [content] 会被序列化成 OpenAI 的多模态数组；为空时仍是纯字符串。
     *
     * 默认空值保证了所有既有调用点（含 [LlmClient.ping]）行为完全不变。
     */
    val parts: List<ContentPart> = emptyList()
) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_SYSTEM = "system"
    }
}

sealed interface StreamEvent {
    data class Delta(val text: String) : StreamEvent
    data class Failed(val message: String) : StreamEvent
    data object Done : StreamEvent
}

/**
 * OpenAI 兼容协议的聊天客户端。
 *
 * 通过 `baseUrl + /v1/chat/completions` 调用，覆盖 DeepSeek、OpenAI、通义、
 * Moonshot、本地 Ollama（需允许明文流量）等所有兼容实现。
 */
class LlmClient {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)   // 流式响应不设读超时
        // 带附件的请求体可能有好几 MB（Base64 后还要再涨 33%），
        // 弱网下 30 秒不够用，会抛一个很难懂的超时错误
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * 发起流式对话。返回的 Flow 会持续吐出 [StreamEvent.Delta]，直到 [StreamEvent.Done]。
     * 取消 Flow 会主动 cancel 底层连接，避免请求泄漏。
     */
    fun stream(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        temperature: Double = 0.7
    ): Flow<StreamEvent> = callbackFlow {
        val url = "${baseUrl.trimEnd('/')}/v1/chat/completions"
        val payload = buildPayload(model, messages, stream = true, temperature = temperature)
        val request = buildRequest(url, apiKey, payload)

        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                trySend(StreamEvent.Failed(describe(e)))
                close()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val body = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                        trySend(StreamEvent.Failed(describeHttp(resp.code, resp.message, body)))
                        close()
                        return
                    }
                    val source = resp.body?.source()
                    if (source == null) {
                        trySend(StreamEvent.Failed("响应体为空"))
                        close()
                        return
                    }
                    try {
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            when {
                                line.isBlank() -> continue
                                line.startsWith("data:") -> {
                                    val data = line.removePrefix("data:").trim()
                                    if (data == "[DONE]") break
                                    parseDelta(data)?.let { trySend(StreamEvent.Delta(it)) }
                                }
                                line.startsWith(":") -> continue   // SSE 心跳注释
                            }
                        }
                        trySend(StreamEvent.Done)
                    } catch (e: IOException) {
                        trySend(StreamEvent.Failed(describe(e)))
                    } finally {
                        close()
                    }
                }
            }
        })

        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    /** 非流式调用，用于「测试连接」。 */
    suspend fun ping(
        baseUrl: String,
        apiKey: String,
        model: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "${baseUrl.trimEnd('/')}/v1/chat/completions"
            val payload = buildPayload(
                model,
                listOf(ChatMessage(ChatMessage.ROLE_USER, "ping")),
                stream = false,
                temperature = 0.0
            )
            client.newCall(buildRequest(url, apiKey, payload)).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} ${resp.message}${formatError(body)}")
                val text = JSONObject(body)
                    .optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content").orEmpty()
                text.ifBlank { "连接成功，但模型返回为空" }
            }
        }
    }

    // ---------------------------------------------------------------- 内部实现

    private fun buildRequest(url: String, apiKey: String, payload: String): Request =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(payload.toRequestBody(JSON))
            .build()

    private fun buildPayload(
        model: String,
        messages: List<ChatMessage>,
        stream: Boolean,
        temperature: Double
    ): String {
        val array = JSONArray()
        messages.forEach { msg ->
            array.put(JSONObject().apply {
                put("role", msg.role)
                // 关键分支：只有带 parts 才用数组格式。
                // 无条件用数组会让不支持多模态的后端（以及纯文本网关）直接报 400。
                if (msg.parts.isEmpty()) {
                    put("content", msg.content)
                } else {
                    put("content", buildContentArray(msg))
                }
            })
        }
        return JSONObject().apply {
            put("model", model)
            put("messages", array)
            put("stream", stream)
            put("temperature", temperature)
        }.toString()
    }

    /**
     * 把 content 文本 + parts 合成 OpenAI 视觉协议的数组。
     *
     * 文本块放前面、图片块放后面：多数后端对顺序不敏感，
     * 但 Qwen-VL 一类在「文本位于首块」时表现更稳。
     *
     * 刻意不加 `detail: "high"` 之类的非标准扩展字段，保持最大兼容面。
     */
    private fun buildContentArray(msg: ChatMessage): JSONArray {
        val arr = JSONArray()
        msg.parts.forEach { part ->
            when (part) {
                is ContentPart.Text -> arr.put(JSONObject().apply {
                    put("type", "text")
                    put("text", part.text)
                })

                is ContentPart.Image -> arr.put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        // 必须是标准 data URI 格式
                        put("url", "data:${part.mime};base64,${part.base64}")
                    })
                })
            }
        }
        return arr
    }

    /** 解析一行 SSE data，取出增量文本。 */
    private fun parseDelta(data: String): String? = runCatching {
        val choices = JSONObject(data).optJSONArray("choices") ?: return@runCatching null
        val delta = choices.optJSONObject(0)?.optJSONObject("delta") ?: return@runCatching null
        delta.optString("content").takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** 把 HTTP 失败响应整理成一条可读的错误信息。 */
    private fun describeHttp(code: Int, message: String, body: String): String {
        // 413 是「请求体过大」的专用码，在多图场景下最容易撞上
        if (code == 413) {
            return "HTTP 413 请求体过大：图片太多或太大，服务端拒绝了。\n" +
                    "请减少附件数量，或改用更小的图片后重试。"
        }
        return "HTTP $code $message${formatError(body)}"
    }

    private fun formatError(body: String): String {
        if (body.isBlank()) return ""
        val detail = runCatching {
            val json = JSONObject(body)
            json.optJSONObject("error")?.optString("message")
                ?: json.optString("message")
        }.getOrNull() ?: body

        // 把「后端不接受多模态数组」这种看不懂的报错翻译成人话。
        // 这是关掉「模型支持看图」后就能解决的问题，但原始报文完全看不出来。
        val hint = multimodalHint(detail)
        return if (hint == null) "\n$detail" else "\n$detail\n\n$hint"
    }

    /** 识别多模态格式相关的报错，给出可操作的建议。 */
    private fun multimodalHint(detail: String): String? {
        val lower = detail.lowercase()
        val looksLikeFormatIssue = listOf(
            "content must be a string",
            "must be a string",
            "invalid type",
            "invalid content",
            "image_url",
            "unsupported content",
            "multimodal"
        ).any { lower.contains(it) }
        if (!looksLikeFormatIssue) return null
        return "提示：当前后端可能不接受多模态数组格式。请在「设置」里关闭「模型支持看图」后重试。"
    }

    private fun describe(e: IOException): String = when (e) {
        is java.net.UnknownHostException -> "无法解析域名，请检查 Base URL 和网络：${e.message}"
        is java.net.SocketTimeoutException -> "连接超时：${e.message}"
        is javax.net.ssl.SSLException -> "TLS 握手失败：${e.message}"
        else -> e.message ?: e.toString()
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
