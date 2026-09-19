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
 * Tavily 联网搜索客户端。
 *
 * 为什么选 Tavily 而不是直接抓搜索引擎：Tavily 是专为 LLM 设计的搜索接口，
 * 返回的是**已经抽取好的正文片段**，不需要我们再解析 HTML，
 * 而且可以一次带回一段整合好的答案（`include_answer`）。
 * 对于「把结果喂给模型」这个用途，比拿原始网页省事得多。
 *
 * 接口契约（见 https://docs.tavily.com/documentation/api-reference/introduction）：
 * ```
 * POST https://api.tavily.com/search
 * Authorization: Bearer tvly-xxxxx
 * {"query": "...", "max_results": 5, "include_answer": true}
 * ```
 */
class TavilyClient {

    /** 单条搜索结果。 */
    data class SearchResult(
        val title: String,
        val url: String,
        val content: String
    )

    /** 一次搜索的完整结果。 */
    data class SearchResponse(
        /** Tavily 整合出的一段答案，可能为空（取决于 query 与套餐）。 */
        val answer: String,
        val results: List<SearchResult>
    )

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // 搜索是同步的一次性请求，不是流式，所以给读超时。
        // 但不能太短：advanced 深度 + 慢站点时 10 秒会误杀
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * 执行一次搜索。
     *
     * @param maxResults 返回条数。默认 5 —— 再多对模型帮助有限，
     *   却会显著挤占上下文预算（每条 content 可达上千字符）
     */
    suspend fun search(
        apiKey: String,
        query: String,
        maxResults: Int = DEFAULT_MAX_RESULTS
    ): Result<SearchResponse> = withContext(Dispatchers.IO) {
        val key = apiKey.trim()
        if (key.isBlank()) {
            return@withContext Result.failure(IllegalStateException("未配置 Tavily API Key"))
        }
        val q = query.trim()
        if (q.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("搜索关键词不能为空"))
        }

        runCatching {
            val payload = JSONObject().apply {
                put("query", q)
                put("max_results", maxResults.coerceIn(1, MAX_RESULTS_LIMIT))
                // 让 Tavily 顺带整合一段答案。对「今天天气怎么样」这类问题，
                // 这段答案往往比零散的网页片段更好用
                put("include_answer", true)
            }

            val request = Request.Builder()
                .url(ENDPOINT)
                .header("Authorization", "Bearer $key")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON))
                .build()

            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()

                if (!resp.isSuccessful) {
                    throw IOException(describeHttp(resp.code, resp.message, body))
                }

                parse(body)
            }
        }
    }

    /** 「测试连接」用：搜一个固定的简单词，只关心能不能通。 */
    suspend fun ping(apiKey: String): Result<String> =
        search(apiKey, "hello world", maxResults = 1).map { r ->
            if (r.results.isEmpty() && r.answer.isBlank()) "连接成功，但没有返回结果"
            else "连接成功，返回 ${r.results.size} 条结果"
        }

    // ------------------------------------------------------------------ 解析

    private fun parse(body: String): SearchResponse {
        val json = runCatching { JSONObject(body) }.getOrElse {
            throw IOException("返回内容不是合法 JSON：${body.take(200)}")
        }

        val answer = json.optString("answer").trim()

        val array = json.optJSONArray("results") ?: JSONArray()
        val results = (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val url = obj.optString("url")
            val content = obj.optString("content")
            if (url.isBlank() && content.isBlank()) return@mapNotNull null
            SearchResult(
                title = obj.optString("title").ifBlank { "(无标题)" },
                url = url,
                content = content
            )
        }

        return SearchResponse(answer, results)
    }

    /**
     * 把搜索结果整理成给模型看的文本。
     *
     * 刻意带上 URL：模型可以在回答里引用来源，用户也能自己去核对 ——
     * 这在联网搜索场景里很重要，因为模型整合出来的答案未必可靠。
     */
    fun formatForModel(query: String, response: SearchResponse): String {
        val sb = StringBuilder()
        sb.append("===== 联网搜索结果：").append(query).append(" =====\n")

        if (response.answer.isNotBlank()) {
            sb.append("\n【摘要】\n").append(response.answer).append('\n')
        }

        if (response.results.isEmpty()) {
            sb.append("\n（没有找到相关结果）\n")
        } else {
            response.results.forEachIndexed { i, r ->
                sb.append("\n[").append(i + 1).append("] ").append(r.title).append('\n')
                sb.append("来源：").append(r.url).append('\n')
                sb.append(snippet(r.content)).append('\n')
            }
        }

        sb.append("\n===== 搜索结果结束 =====")
        return sb.toString()
    }

    /**
     * 截断过长的正文。
     *
     * Tavily 的 content 字段有时是整篇文章。5 条各 5000 字就是 2 万多字，
     * 一下吃掉大半上下文预算，后面几轮对话就没空间了。
     */
    private fun snippet(text: String): String {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        return if (clean.length <= MAX_SNIPPET) clean
        else clean.take(MAX_SNIPPET) + "…"
    }

    /** HTTP 错误的可读说明。 */
    private fun describeHttp(code: Int, message: String, body: String): String {
        val detail = extractMessage(body)
        return when (code) {
            401, 403 -> "Tavily 拒绝了这个 Key（HTTP $code）。请检查设置里的 Tavily Key 是否正确、是否已过期"
            429 -> "Tavily 请求过于频繁或配额用尽（HTTP 429）。请稍后再试，或去 Tavily 控制台查看额度"
            432, 433 -> "Tavily 配额或用量限制（HTTP $code）：$detail"
            in 500..599 -> "Tavily 服务端错误（HTTP $code），稍后重试"
            else -> "搜索失败：HTTP $code $message${if (detail.isBlank()) "" else " —— $detail"}"
        }
    }

    /** 从错误响应里挖出可读信息。Tavily 用 detail / error 字段。 */
    private fun extractMessage(body: String): String {
        if (body.isBlank()) return ""
        return runCatching {
            val json = JSONObject(body)
            json.optString("detail").ifBlank { json.optString("error") }
                .ifBlank { json.optString("message") }
        }.getOrElse { body.take(160) }
    }

    companion object {
        const val ENDPOINT = "https://api.tavily.com/search"

        /** 默认返回条数。 */
        const val DEFAULT_MAX_RESULTS = 5

        /** 接口允许的上限（Tavily 最多 20）。 */
        const val MAX_RESULTS_LIMIT = 20

        /** 单条正文的截断长度，控制上下文占用。 */
        const val MAX_SNIPPET = 1200

        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
