package com.phoclaw.chat.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 通用 HTTP 请求客户端。
 *
 * 与 [WebFetchClient] 的分工：
 * - [WebFetchClient] 解决「**读懂**一个网页」——抓回来的是清洗过的 Markdown 正文
 * - 这个解决「**调用**一个接口」——关心状态码、响应头、原始响应体
 *
 * 两者不能合并：一个只发 GET 且目标是网页，另一个要发任意方法、要看到
 * 原始响应头（比如调试 API 时得看 `Content-Type` / `X-RateLimit-*`）。
 *
 * ## 安全边界
 *
 * 任意 HTTP 请求意味着**能把数据发到任意服务器**，这是本项目里权限最高
 * 的一个能力。做了这些限制：
 * - 只允许 `http` / `https`，挡掉 `file://` `ftp://` 这类协议
 * - 响应体有大小上限，避免被超大响应拖垮内存
 * - 有超时，避免挂死
 * - 写操作（POST/PUT/PATCH/DELETE）在执行前需要用户确认，
 *   见 `CommandDispatcher.needsApproval`
 *
 * 没有做「禁止访问内网地址」的限制 —— 这是个纯客户端 App，用户自己就是
 * 手机的主人，访问局域网服务（比如家里的 NAS、开发机）是**合理需求**。
 * 加这层限制只会挡住正常用法，挡不住真正想作恶的人（他直接装个 curl 就行）。
 */
class HttpToolClient {

    /** 一次请求的结果。 */
    data class HttpResponse(
        val code: Int,
        val message: String,
        val headers: List<Pair<String, String>>,
        val body: String,
        /** 响应体是否被截断。 */
        val truncated: Boolean,
        /** 重定向后的最终地址（与请求地址不同时才值得关注）。 */
        val finalUrl: String
    )

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        // 手动处理重定向，这样能拿到中间状态，也避免 POST 被自动改成 GET
        .followRedirects(false)
        .build()

    /** 默认 UA。有些站点（GitHub API 等）要求带 UA，否则直接 403。 */
    private val defaultUserAgent = "PhoClaw/1.0 (Android)"

    /**
     * 发起一次请求。
     *
     * @param method HTTP 方法，大小写不敏感
     * @param headers 自定义请求头，格式 `名字: 值`，每行一个
     * @param body 请求体，GET / HEAD 时忽略
     */
    suspend fun request(
        method: String,
        url: String,
        headers: List<Pair<String, String>> = emptyList(),
        body: String = ""
    ): Result<HttpResponse> = withContext(Dispatchers.IO) {
        val m = method.trim().uppercase().ifBlank { "GET" }
        val u = url.trim()

        if (u.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("请提供请求地址"))
        }
        if (!u.startsWith("http://", ignoreCase = true) &&
            !u.startsWith("https://", ignoreCase = true)
        ) {
            return@withContext Result.failure(
                IllegalArgumentException("只支持 http / https 地址，收到的是：${u.take(60)}")
            )
        }
        if (m !in ALLOWED_METHODS) {
            return@withContext Result.failure(
                IllegalArgumentException(
                    "不支持的请求方法 $m，可用：${ALLOWED_METHODS.joinToString(", ")}"
                )
            )
        }

        runCatching {
            val hasBody = m in METHODS_WITH_BODY && body.isNotEmpty()
            val requestBody = if (hasBody) {
                // 没指定 Content-Type 时按 JSON 处理 —— 这是调用 API 时最常见的场景。
                // 用户在 headers 里显式指定会覆盖这个默认值（见下方 header 循环）
                body.toRequestBody(mediaTypeOf(headers))
            } else null

            val builder = Request.Builder()
                .url(u)
                .method(m, requestBody)

            // 先放默认 UA，用户自定义的会覆盖它（后设置的优先）
            builder.header("User-Agent", defaultUserAgent)
            headers.forEach { (k, v) ->
                if (k.isNotBlank()) {
                    // header() 是覆盖，addHeader() 是追加。这里要覆盖，
                    // 否则用户指定 UA 时会变成两个 UA 头，很多站点会直接拒绝
                    builder.header(k.trim(), v.trim())
                }
            }

            client.newCall(builder.build()).execute().use { resp ->
                // 跟随同协议重定向（最多 5 跳）。跨协议跳转（https -> http）
                // 不跟 —— 那属于降级，应该让调用方知道
                val followed = followRedirects(resp, m, headers, requestBody)
                val r = followed ?: resp
                readResponse(r)
            }
        }
    }

    /**
     * 手动跟随重定向。
     *
     * 为什么不用 OkHttp 内置的 `followRedirects(true)`：内置行为会把
     * 307/308 之外的 POST 自动降级成 GET 并丢掉请求体，这在调 API 时
     * 会得到莫名其妙的结果。这里显式处理，并且保留原始方法语义。
     */
    private fun followRedirects(
        resp: Response,
        method: String,
        headers: List<Pair<String, String>>,
        body: okhttp3.RequestBody?
    ): Response? {
        var current = resp
        var hops = 0
        while (current.code in REDIRECT_CODES && hops < MAX_REDIRECTS) {
            val location = current.header("Location") ?: break
            val next = current.request.url.resolve(location) ?: break
            // 跨协议降级不跟（https -> http 属于安全性下降）
            if (current.request.url.isHttps && !next.isHttps) break

            // 先把判断方法要用的状态码存下来再关
            val code = current.code
            current.close()

            val b = Request.Builder().url(next)
            // 303 明确要求改成 GET；301/302 对 POST 也按 GET 处理（浏览器行为）
            val nextMethod = when (code) {
                303 -> "GET"
                301, 302 -> if (method == "POST") "GET" else method
                else -> method
            }
            b.method(nextMethod, if (nextMethod == "GET" || nextMethod == "HEAD") null else body)
            headers.forEach { (k, v) -> if (k.isNotBlank()) b.header(k.trim(), v.trim()) }
            if (!headers.any { it.first.equals("User-Agent", true) }) {
                b.header("User-Agent", defaultUserAgent)
            }
            current = client.newCall(b.build()).execute()
            hops++
        }
        return current
    }

    private fun readResponse(resp: Response): HttpResponse {
        // 先用 Content-Length 分流，再用「能读多少读多少」的方式取字节。
        //
        // 这里踩过一个坑：Okio 的 `readByteArray(n)` 语义是**恰好读 n 字节**，
        // 流不够长时抛 EOFException，而不是返回实际读到的部分。所以绝不能
        // 用它来「读前 N 字节」—— 小于上限的响应（绝大多数）会全部报错。
        // 正确做法是 `readByteArray()` 读全部（此时已知它不会超限），
        // 或者在超限场景下用 `readByteArray(limit)` 配合已知长度。
        val declared = resp.body?.contentLength() ?: -1L
        val source = resp.body?.source()

        val bytes: ByteArray
        val truncated: Boolean
        if (source == null) {
            bytes = ByteArray(0)
            truncated = false
        } else if (declared in 0..MAX_BODY_BYTES.toLong()) {
            // 服务端明说了大小且不超限：直接全读
            bytes = source.readByteArray()
            truncated = false
        } else if (declared > MAX_BODY_BYTES) {
            // 明说超限：只取前 MAX_BODY_BYTES。此时流里至少有这么多字节，
            // readByteArray(n) 才不会抛 EOF
            bytes = source.readByteArray(MAX_BODY_BYTES.toLong())
            truncated = true
        } else {
            // Content-Length 未知（分块传输 / gzip）：用 Buffer 一点点读，
            // 读满上限就停，避免把超大响应吃进内存
            val buf = okio.Buffer()
            val limit = MAX_BODY_BYTES.toLong()
            while (buf.size < limit) {
                if (source.read(buf, limit - buf.size) == -1L) break
            }
            // 试着再读 1 字节判断是否还有剩余。这一字节是探测用的，
            // 必须丢掉 —— 否则会返回比上限多 1 字节的内容，
            // 下游按 MAX_BODY_BYTES 做的任何假设都会错位
            val over = if (source.read(buf, 1L) != -1L) {
                buf.readByte()
                true
            } else false
            truncated = over
            bytes = buf.readByteArray()
        }
        val text = decode(bytes, resp.header("Content-Type"))

        return HttpResponse(
            code = resp.code,
            message = resp.message,
            headers = resp.headers.names().flatMap { name ->
                resp.headers.values(name).map { name to it }
            },
            body = text,
            truncated = truncated,
            finalUrl = resp.request.url.toString()
        )
    }

    /**
     * 按 Content-Type 里的字符集解码。
     *
     * 不能一律用 UTF-8：国内的不少中文站点还在用 GBK，直接按 UTF-8 解
     * 会得到一屏乱码。也不做全量猜编码（那要引 charset detector 依赖），
     * 只认 Content-Type 里显式声明的，认不出就退回 UTF-8。
     */
    private fun decode(bytes: ByteArray, contentType: String?): String {
        val charset = runCatching {
            contentType?.substringAfter("charset=", "")
                ?.substringBefore(';')?.substringBefore('"')?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { java.nio.charset.Charset.forName(it) }
        }.getOrNull() ?: Charsets.UTF_8

        return try {
            String(bytes, charset)
        } catch (_: Exception) {
            // 解码失败（比如二进制内容），退回 UTF-8 并标注
            String(bytes, Charsets.UTF_8)
        }
    }

    /** 从用户给的头里找 Content-Type，没有就按 JSON 处理。 */
    private fun mediaTypeOf(headers: List<Pair<String, String>>) =
        headers.firstOrNull { it.first.equals("Content-Type", ignoreCase = true) }
            ?.second?.trim()
            ?.let { runCatching { it.toMediaTypeOrNull() }.getOrNull() }
            ?: JSON

    /**
     * 整理成给模型看的文本。
     *
     * 响应头只挑有用的给全量会太吵（有的站点几十个头，一半是缓存和安全策略）。
     * 但也不能过滤太狠 —— 调 API 时排查问题往往就靠那几个头。
     * 这里取「全量但不展开超长的」，并把状态码放在最显眼的位置。
     */
    fun formatForModel(res: HttpResponse, method: String, url: String): String {
        val sb = StringBuilder()
        sb.append("===== HTTP 请求结果 =====\n")
        sb.append("$method $url\n")
        sb.append("状态：${res.code} ${res.message}\n")
        if (res.finalUrl != url) {
            sb.append("最终地址：${res.finalUrl}\n")
        }

        if (res.headers.isNotEmpty()) {
            sb.append("\n【响应头】\n")
            res.headers.forEach { (k, v) ->
                // 单个头过长时收一下，避免 Set-Cookie 之类的把上下文吃光
                val value = if (v.length > HEADER_VALUE_LIMIT) v.take(HEADER_VALUE_LIMIT) + "…" else v
                sb.append("$k: $value\n")
            }
        }

        sb.append("\n【响应体】\n")
        if (res.body.isBlank()) {
            sb.append("（空）\n")
        } else {
            sb.append(res.body)
            if (!res.body.endsWith("\n")) sb.append('\n')
        }
        if (res.truncated) {
            sb.append("\n…（响应体过大，已截断至 ${MAX_BODY_BYTES / 1024} KB）\n")
        }

        sb.append("\n===== 请求结束 =====")
        return sb.toString()
    }

    companion object {
        /**
         * 允许的方法。
         *
         * 不含 CONNECT / TRACE —— 前者是代理隧道、后者会回显请求，
         * 对模型完成任何实际任务都没用，只会成为攻击面。
         */
        val ALLOWED_METHODS = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

        private val METHODS_WITH_BODY = setOf("POST", "PUT", "PATCH", "DELETE")

        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        private const val MAX_REDIRECTS = 5

        /** 响应体上限 256 KB。再大对模型也没意义，只会撑爆上下文。 */
        const val MAX_BODY_BYTES = 256 * 1024

        /** 单个响应头的展示上限。 */
        private const val HEADER_VALUE_LIMIT = 500

        private val JSON = "application/json; charset=utf-8".toMediaTypeOrNull()

        /**
         * 写操作会改变服务端状态，需要用户确认。
         *
         * GET / HEAD / OPTIONS 是「只读」的，不拦。
         */
        fun isMutating(method: String): Boolean =
            method.trim().uppercase() in setOf("POST", "PUT", "PATCH", "DELETE")
    }
}
