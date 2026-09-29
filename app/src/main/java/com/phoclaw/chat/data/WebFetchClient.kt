package com.phoclaw.chat.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 网页正文抓取客户端。
 *
 * 复用 Tavily 的 `/extract` 接口，而不是自己下载 HTML 再解析。理由：
 * 1. 自己解析 HTML 要处理结构、广告、导航栏、编码、JS 渲染 —— 这是一整个
 *    工程量，而且解析质量远不如专业服务；塞给模型的会是一堆噪声。
 * 2. 项目已经在用 Tavily 做搜索（见 [TavilyClient]），Key 是现成的，
 *    不引入新的账号体系。
 * 3. `/extract` 默认返回 **Markdown**，而 App 的渲染层本来就支持 Markdown
 *    （见 `MarkdownParser`），拿回来直接能显示，不用二次转换。
 *
 * 接口契约（见 https://docs.tavily.com/documentation/api-reference/endpoint/extract）：
 * ```
 * POST https://api.tavily.com/extract
 * Authorization: Bearer tvly-xxxxx
 * {"urls": ["https://..."], "format": "markdown", "extract_depth": "basic"}
 * ```
 *
 * **注意**：接口即使返回 HTTP 200，也可能所有 URL 都抓取失败（走
 * `failed_results`）。所以不能只看状态码，必须检查两个数组。
 */
class WebFetchClient {

    /** 单个网页的抓取结果。 */
    data class Page(
        val url: String,
        /** Markdown 正文。 */
        val content: String
    )

    /** 一次抓取的完整结果。 */
    data class FetchResult(
        val pages: List<Page>,
        /** url -> 失败原因。 */
        val failures: Map<String, String>
    )

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // 网页抓取比搜索慢：有些站点响应本身就得好几秒，
        // advanced 深度下服务端最长可能等 30 秒，客户端要留出余量
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * 抓取若干网页的正文。
     *
     * @param query 可选的「用户意图」。传了它 Tavily 会按相关度对内容分块重排，
     *   只返回最相关的若干片段（见 [chunksPerSource]）—— 抓长文档时能显著
     *   节省上下文。不传则返回整页正文。
     */
    suspend fun fetch(
        apiKey: String,
        urls: List<String>,
        query: String = ""
    ): Result<FetchResult> = withContext(Dispatchers.IO) {
        val key = apiKey.trim()
        if (key.isBlank()) {
            return@withContext Result.failure(IllegalStateException("未配置 Tavily API Key"))
        }

        val clean = urls.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (clean.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("请提供要抓取的网址"))
        }

        runCatching {
            val payload = JSONObject().apply {
                put("urls", JSONArray(clean.take(MAX_URLS)))
                // 默认就是 markdown，但显式写出来，避免接口默认值变更后静默降级
                put("format", "markdown")
                put("extract_depth", "basic")
                if (query.isNotBlank()) {
                    put("query", query)
                    put("chunks_per_source", DEFAULT_CHUNKS)
                }
            }

            val request = Request.Builder()
                .url(ENDPOINT)
                .header("Authorization", "Bearer $key")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON))
                .build()

            client.newCall(request).execute().use { resp ->
                // 先卡一道体积：Tavily 的 extract 响应里含正文，正常也就
                // 几百 KB。如果远超这个量级，多半是出错了（比如网关返回了
                // 一个巨大的 HTML 错误页），没必要读进内存
                val len = resp.body?.contentLength() ?: -1L
                if (len > MAX_RESPONSE_BYTES) {
                    throw IOException("响应过大（${len / 1024} KB），已放弃")
                }
                val body = resp.body?.string().orEmpty()

                if (!resp.isSuccessful) {
                    throw IOException(describeHttp(resp.code, resp.message, body))
                }

                parse(body)
            }
        }
    }

    /** 「测试连接」用：抓一个稳定的轻量页面，只关心能不能通。 */
    suspend fun ping(apiKey: String): Result<String> =
        fetch(apiKey, listOf(PING_URL)).map { r ->
            when {
                r.pages.isNotEmpty() -> "连接成功，成功抓取 ${r.pages.first().content.length} 字符"
                r.failures.isNotEmpty() -> "能连上，但目标页面抓取失败：${r.failures.values.first()}"
                else -> "连接成功，但没有返回内容"
            }
        }

    // ------------------------------------------------------------------ 解析

    private fun parse(body: String): FetchResult {
        val json = runCatching { JSONObject(body) }.getOrElse {
            throw IOException("返回内容不是合法 JSON：${body.take(200)}")
        }

        val resultsArray = json.optJSONArray("results") ?: JSONArray()
        val pages = (0 until resultsArray.length()).mapNotNull { i ->
            val obj = resultsArray.optJSONObject(i) ?: return@mapNotNull null
            val content = obj.optString("raw_content")
            if (content.isBlank()) return@mapNotNull null
            Page(url = obj.optString("url"), content = content)
        }

        val failedArray = json.optJSONArray("failed_results") ?: JSONArray()
        val failures = buildMap {
            for (i in 0 until failedArray.length()) {
                val obj = failedArray.optJSONObject(i) ?: continue
                val url = obj.optString("url")
                if (url.isBlank()) continue
                put(url, obj.optString("error").ifBlank { "抓取失败" })
            }
        }

        return FetchResult(pages, failures)
    }

    /**
     * 整理成给模型看的文本。
     *
     * 保留来源 URL —— 模型引用内容时要能说出出处，用户也要能自己核对。
     * 这与 [TavilyClient.formatForModel] 的做法一致。
     */
    fun formatForModel(result: FetchResult, requested: List<String>): String {
        val sb = StringBuilder()
        sb.append("===== 网页内容抓取 =====\n")
        sb.append("请求网址：").append(requested.joinToString(", ")).append('\n')

        if (result.pages.isEmpty() && result.failures.isEmpty()) {
            sb.append("\n（没有返回任何内容）\n")
        }

        result.pages.forEachIndexed { i, p ->
            sb.append("\n--- [").append(i + 1).append("] ").append(p.url).append(" ---\n")
            sb.append(clip(p.content)).append('\n')
        }

        if (result.failures.isNotEmpty()) {
            sb.append("\n【抓取失败的网址】\n")
            result.failures.forEach { (url, err) ->
                sb.append("- ").append(url).append("：").append(err).append('\n')
            }
        }

        sb.append("\n===== 抓取结束 =====")
        return sb.toString()
    }

    /**
     * 截断过长正文。
     *
     * 一篇长文可能十几万字，一次就能把上下文撑爆，导致后续对话没空间。
     * 这里给一个上限，超出的部分明确标注被截断 —— 不能静默丢弃，
     * 否则模型会以为这就是全文。
     */
    private fun clip(text: String): String {
        val clean = text.replace(Regex("\n{3,}"), "\n\n").trim()
        return if (clean.length <= MAX_CONTENT) clean
        else clean.take(MAX_CONTENT) + "\n…（内容过长，已截断，共 ${clean.length} 字符）"
    }

    /** HTTP 错误的可读说明。 */
    private fun describeHttp(code: Int, message: String, body: String): String {
        val detail = extractMessage(body)
        return when (code) {
            401, 403 -> "Tavily 拒绝了这个 Key（HTTP $code）。请检查设置里的 Tavily Key 是否正确、是否已过期"
            429 -> "Tavily 请求过于频繁或配额用尽（HTTP 429）。请稍后再试，或去 Tavily 控制台查看额度"
            432, 433 -> "Tavily 配额或用量限制（HTTP $code）：$detail"
            in 500..599 -> "Tavily 服务端错误（HTTP $code），稍后重试"
            else -> "抓取失败：HTTP $code $message${if (detail.isBlank()) "" else " —— $detail"}"
        }
    }

    /**
     * 从错误响应里挖出可读信息。
     *
     * Tavily 的错误形状有两种（都实测过）：
     * - `{"detail": {"error": "Unauthorized: ..."}}` —— `detail` 是**对象**
     * - `{"detail": "..."}` / `{"error": "..."}` —— 直接是字符串
     *
     * 只用 optString 取 `detail` 的话，第一种会返回空串，错误信息就丢了
     * （用户只看到「HTTP 401」而不知道为什么）。所以两种都试。
     */
    private fun extractMessage(body: String): String {
        if (body.isBlank()) return ""
        return runCatching {
            val json = JSONObject(body)

            // detail 是对象时，往下挖一层看 error / message
            json.optJSONObject("detail")?.let { d ->
                d.optString("error").ifBlank { d.optString("message") }
                    .ifBlank { d.optString("detail") }
            }?.takeIf { it.isNotBlank() }?.let { return@runCatching it }

            json.optString("detail").ifBlank { json.optString("error") }
                .ifBlank { json.optString("message") }
        }.getOrElse { body.take(160) }
    }

    companion object {
        const val ENDPOINT = "https://api.tavily.com/extract"

        /** 接口允许单次最多 20 个 URL（文档明确说明）。 */
        const val MAX_URLS = 20

        /** 带 query 时每个来源返回的相关片段数。 */
        const val DEFAULT_CHUNKS = 3

        /** 单个页面的正文上限，控制上下文占用。 */
        const val MAX_CONTENT = 12_000

        /** 整个响应体的上限。extract 正常也就几百 KB，超过说明有问题。 */
        private const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024

        /** ping 用的目标：example.com 极其稳定，且正文很短。 */
        private const val PING_URL = "https://example.com"

        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
