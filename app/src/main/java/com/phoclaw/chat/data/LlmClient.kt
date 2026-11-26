package com.phoclaw.chat.data

import com.phoclaw.chat.util.BaseUrlHint
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
    /** 正文增量。 */
    data class Delta(val text: String) : StreamEvent

    /**
     * 思考过程增量（推理模型的 `reasoning_content`）。
     *
     * 与 [Delta] 分开而不是拼进正文，原因是两者在界面上的归属完全不同：
     * 正文是用户要读的答案，思考是「模型怎么想的」的可选旁注，
     * 混在一起就没法折叠，也没法在切换模型时按档案单独关掉。
     *
     * 还需要**回传时不带上**：绝大多数 OpenAI 兼容后端不接受 assistant
     * 消息里出现 `reasoning_content` 字段，历史消息里带上会直接 400。
     */
    data class ReasoningDelta(val text: String) : StreamEvent

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
                trySend(StreamEvent.Failed(describe(e, url)))
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
                                    parseDelta(data)?.let { delta ->
                                        // 先吐思考再吐正文：推理模型总是先想后答，
                                        // 界面上的时间顺序才和真实过程一致
                                        delta.reasoning?.let { trySend(StreamEvent.ReasoningDelta(it)) }
                                        delta.content?.let { trySend(StreamEvent.Delta(it)) }
                                    }
                                }
                                line.startsWith(":") -> continue   // SSE 心跳注释
                            }
                        }
                        trySend(StreamEvent.Done)
                    } catch (e: IOException) {
                        trySend(StreamEvent.Failed(describe(e, url)))
                    } finally {
                        close()
                    }
                }
            }
        })

        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    /**
     * 非流式补全，用于「测试连接」与**上下文压缩**。
     *
     * 压缩不需要一边生成一边显示，反而是非流式更合适：拿到完整摘要才能
     * 决定要不要落盘。
     *
     * @param maxTokens 不传则不下发这个字段 —— 部分国产网关对未知字段敏感，
     *   没必要的话就别多塞一个
     */
    suspend fun complete(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        temperature: Double = 0.3,
        maxTokens: Int? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val url = "${baseUrl.trimEnd('/')}/v1/chat/completions"
        runCatching {
            val payload = buildPayload(
                model, messages, stream = false,
                temperature = temperature, maxTokens = maxTokens
            )
            client.newCall(buildRequest(url, apiKey, payload)).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IOException("HTTP ${resp.code} ${resp.message}${formatError(body)}")
                }
                JSONObject(body)
                    .optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content").orEmpty()
            }
        }.recoverCatching { throw translate(it, url) }
    }

    /** 非流式调用，用于「测试连接」。 */
    suspend fun ping(
        baseUrl: String,
        apiKey: String,
        model: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val url = "${baseUrl.trimEnd('/')}/v1/chat/completions"
        runCatching {
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
        }.recoverCatching { throw translate(it, url) }
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
        temperature: Double,
        maxTokens: Int? = null
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
            // 仅在有明确需求时下发。有些网关遇到不认识的字段会直接 400，
            // 能少一个是一个
            if (maxTokens != null) put("max_tokens", maxTokens)
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

    /** 一行 SSE data 里拆出来的两部分内容。 */
    private data class DeltaParts(val content: String?, val reasoning: String?)

    /**
     * 解析一行 SSE data。
     *
     * 思考过程的字段名各家不一致，所以三种都认：
     * - `reasoning_content` —— DeepSeek-R1、Kimi、Qwen 系
     * - `reasoning` —— OpenRouter 归一化后的名字（也可能是对象，见下）
     * - `thinking` —— 部分自建网关的写法
     *
     * `reasoning` 在 OpenRouter 上有时是 `{ "content": "..." }` 对象而不是字符串，
     * 对对象直接 `optString` 会拿到 `"{}"` 这种垃圾，所以按类型分支取。
     */
    private fun parseDelta(data: String): DeltaParts? = runCatching {
        val choices = JSONObject(data).optJSONArray("choices") ?: return@runCatching null
        val choice = choices.optJSONObject(0) ?: return@runCatching null
        // 流式看 delta，非流式兜底看 message（个别网关对 stream=true 也回 message）
        val node = choice.optJSONObject("delta") ?: choice.optJSONObject("message")
        ?: return@runCatching null

        val content = node.optString("content").takeIf { it.isNotEmpty() }
        val reasoning = reasoningOf(node)

        if (content == null && reasoning == null) null else DeltaParts(content, reasoning)
    }.getOrNull()

    /** 从 delta 节点里按三种可能的字段名取思考内容。 */
    private fun reasoningOf(node: JSONObject): String? {
        val candidates = listOf("reasoning_content", "reasoning", "thinking")
        for (key in candidates) {
            when (val raw = node.opt(key)) {
                is String -> if (raw.isNotEmpty()) return raw
                is JSONObject -> {
                    // OpenRouter 一类会把 reasoning 包成对象
                    val inner = raw.optString("content")
                    if (inner.isNotEmpty()) return inner
                }
                // null / Boolean / Number 都不是思考文本，跳过
                else -> Unit
            }
        }
        return null
    }

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

    /**
     * 把异常换成一条人话 message，原始异常挂到 cause 上。
     *
     * HTTP 状态码类的异常（message 以 `HTTP ` 开头）已经由 [describeHttp]
     * 整理过，不能再套一层，否则会变成「连不上 xxx。HTTP 401 …」。
     */
    private fun translate(e: Throwable, url: String): Throwable {
        if (e !is IOException) return e
        if (e.message?.startsWith("HTTP ") == true) return e
        return IOException(describe(e, url), e)
    }

    /**
     * 把网络异常翻译成人能读懂的话。
     *
     * 直接抛 `e.message` 是最省事也最没用的做法 —— 用户截图里那句
     * `Failed to connect to /127.0.0.1:8443` 就是原文，除了让人确定
     * 「确实连不上」之外没有任何信息：不知道是地址问题、服务没开、
     * 还是自己填错了协议。这里按最常见的三种原因分别给出下一步。
     *
     * @param url 完整请求地址，用来从里面抠出主机和端口
     */
    private fun describe(e: IOException, url: String = ""): String {
        val host = BaseUrlHint.hostOf(url)
        val port = BaseUrlHint.portOf(url)
        val target = when {
            host.isBlank() -> "服务器"
            port.isBlank() -> host
            else -> "$host:$port"
        }

        return when (e) {
            is java.net.UnknownHostException ->
                "找不到 $target 这个地址。\n" +
                        "检查域名拼写，或者手机当前网络能不能解析它（公司网络、校园网经常拦）。"

            is java.net.SocketTimeoutException ->
                "连接 $target 超时。\n" +
                        "如果地址是内网 IP，确认手机和电脑在同一个 Wi-Fi。"

            is java.net.ConnectException -> buildString {
                append("连不上 $target。\n")
                when {
                    BaseUrlHint.isLoopback(host) ->
                        append(
                            "127.0.0.1 / localhost 在手机上指的是「手机自己」，" +
                                    "不是你的电脑。要连电脑上的服务，请填电脑的局域网 IP" +
                                    "（在电脑上执行 ipconfig / ifconfig 查，形如 192.168.1.5）。"
                        )
                    BaseUrlHint.isPrivateHost(host) ->
                        append(
                            "三件事逐一确认：\n" +
                                    "1. 电脑上的服务已启动，端口没写错\n" +
                                    "2. 服务监听的是 0.0.0.0 而不是 127.0.0.1（只监听本机时外部连不进来）\n" +
                                    "3. 电脑防火墙放行了这个端口，手机和电脑在同一个 Wi-Fi"
                        )
                    else ->
                        append("确认地址、端口没写错，以及手机能正常上网。")
                }
            }

            is javax.net.ssl.SSLException -> buildString {
                // 最常见的两种：往明文端口发 https 请求，或证书不被信任
                append("TLS 握手失败（$target）。\n")
                if (e.message?.contains("Unrecognized SSL message", true) == true ||
                    e.message?.contains("plaintext", true) == true
                ) {
                    append("这个端口像是明文 HTTP 服务，把地址改成 http:// 开头试试。")
                } else {
                    append(
                        "如果后端是自建的、用了自签名证书，系统不认 —— " +
                                "换成正式证书，或先用 http://（仅限局域网）。"
                    )
                }
            }

            else -> e.message ?: e.toString()
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
