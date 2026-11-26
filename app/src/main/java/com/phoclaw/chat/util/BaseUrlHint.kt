package com.phoclaw.chat.util

/**
 * Base URL 的校验与归一化。
 *
 * 用户最常见的错误是直接把文档里的完整接口地址粘进来，例如
 * `https://api.deepseek.com/v1/chat/completions`。
 * 本类负责识别这种情况并给出提示，同时在保存时自动剥离多余的路径段。
 */
object BaseUrlHint {

    private const val CHAT_PATH = "/v1/chat/completions"

    /** 判断是否粘成了完整接口地址（含 /v1/chat/completions）。 */
    fun isFullEndpoint(url: String): Boolean {
        val u = url.trim().trimEnd('/').lowercase()
        return u.contains("/chat/completions")
    }

    /** 除了完整接口地址，还粘了 /v1 也算提示对象。 */
    fun hasRedundantPath(url: String): Boolean {
        val u = url.trim().trimEnd('/').lowercase()
        return u.contains("/chat/completions") || u.endsWith("/v1")
    }

    /** 返回需要展示给用户的纠错提示，无需提示时返回 null。 */
    fun warning(url: String): String? = when {
        isFullEndpoint(url) ->
            "这里不用填完整接口地址，去掉 /v1/chat/completions。" +
                    "保存时会自动帮你删掉。"
        url.trim().trimEnd('/').lowercase().endsWith("/v1") ->
            "末尾的 /v1 是多余的，程序会自动补。保存时会自动删掉。"
        else -> null
    }

    /**
     * 归一化：补全协议头、去掉末尾斜杠、剥离多余的接口路径。
     *
     * `https://api.deepseek.com/v1/chat/completions` → `https://api.deepseek.com`
     * `api.openai.com/v1` → `https://api.openai.com`
     */
    fun normalize(raw: String): String {
        var url = raw.trim()
        if (url.isEmpty()) return url
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            // 私网地址补 http 而不是 https：局域网里自建的服务几乎都是明文，
            // 补成 https 只会让人对着「TLS 握手失败」发呆。公网仍然补 https。
            val guess = hostOf(url)
            url = if (guess.isNotEmpty() && isPrivateHost(guess)) "http://$url" else "https://$url"
        }
        url = url.trimEnd('/')

        // 剥离常见后缀，从长到短依次匹配
        val suffixes = listOf(
            "/v1/chat/completions",
            "/chat/completions",
            "/v1/completions",
            "/v1"
        )
        val lower = url.lowercase()
        suffixes.firstOrNull { lower.endsWith(it) }?.let { suffix ->
            url = url.dropLast(suffix.length).trimEnd('/')
        }
        return url
    }

    // ------------------------------------------------------------ 地址性质判断

    /**
     * 从地址里抠出主机名（不带协议、端口、路径）。
     *
     * 手写解析而不是用 [java.net.URI]：用户正在输入的过程中 URI 会抛异常，
     * 而这里每一帧都要在输入框下方算提示文字。
     */
    fun hostOf(raw: String): String {
        var u = raw.trim()
        if (u.isEmpty()) return ""
        u = u.substringAfter("://", u)
        u = u.substringBefore('/')
        u = u.substringAfterLast('@')
        // IPv6 字面量：[::1]:8080
        if (u.startsWith("[")) return u.substringAfter('[').substringBefore(']')
        return u.substringBefore(':')
    }

    fun isHttps(raw: String): Boolean = raw.trim().startsWith("https://", ignoreCase = true)

    /**
     * 是否**明确写了** `http://`。
     *
     * 不能用 `!isHttps()` 反推：用户输入 `api.deepseek.com` 时既不是 https
     * 也不是 http，而 [normalize] 最终会补成 https —— 这时候弹「你用了明文」
     * 纯属冤枉人。
     */
    fun isPlainHttp(raw: String): Boolean = raw.trim().startsWith("http://", ignoreCase = true)

    /** 抠出端口号，没写就返回空串（让调用方决定默认值）。 */
    fun portOf(raw: String): String {
        var u = raw.trim()
        u = u.substringAfter("://", u)
        u = u.substringBefore('/')
        u = u.substringAfterLast('@')
        if (u.startsWith("[")) return u.substringAfter("]:").takeIf { ':' in u } ?: ""
        if (u.count { it == ':' } != 1) return ""
        return u.substringAfter(':').takeWhile { it.isDigit() }
    }

    /** 本机回环地址。这类地址在手机上指向手机自己，不是电脑。 */
    fun isLoopback(host: String): Boolean {
        val h = host.trim().lowercase()
        return h == "localhost" || h == "::1" || h == "0.0.0.0" || h.startsWith("127.")
    }

    /**
     * 私有网段 / 局域网主机名。
     *
     * 覆盖 10/8、192.168/16、172.16/12、169.254/16（链路本地，部分热点用）、
     * 100.64/10（CGNAT，Tailscale 与部分运营商大内网），以及 `.local`、`.lan`、
     * `.home` 这类只在局域网内解析的域名。
     */
    fun isPrivateHost(host: String): Boolean {
        val h = host.trim().lowercase()
        if (h.isEmpty()) return false
        if (isLoopback(h)) return true
        if (h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home")) return true

        val parts = h.split('.')
        if (parts.size != 4) return false
        val nums = parts.map { it.toIntOrNull() ?: return false }
        if (nums.any { it !in 0..255 }) return false
        val (a, b) = nums
        return when {
            a == 10 -> true
            a == 192 && b == 168 -> true
            a == 172 && b in 16..31 -> true
            a == 169 && b == 254 -> true
            a == 100 && b in 64..127 -> true
            else -> false
        }
    }

    /**
     * 输入框下方的补充提示。分三类，严重程度不同 ——
     *
     * - 回环地址：**必然连不上**（除非手机上真跑了那个服务），必须说清楚；
     * - http + 公网：能用，但 Key 会明文过网，属于用户该知道的代价；
     * - http + 局域网：正常做法，只是提一句「确认电脑服务监听 0.0.0.0」。
     *
     * @param severe 为 true 时用错误色显示
     */
    data class Note(val text: String, val severe: Boolean)

    fun note(raw: String): Note? {
        val url = raw.trim()
        if (url.isEmpty()) return null
        val host = hostOf(url)
        if (host.isEmpty()) return null

        if (isLoopback(host)) {
            return Note(
                "这个地址指向手机自己。服务跑在电脑上要填电脑的局域网 IP" +
                        "（如 192.168.1.5），不是 127.0.0.1。",
                severe = true
            )
        }
        // 按归一化之后的结果判断：用户填 `192.168.1.5:8080` 时虽然没写
        // scheme，但保存时会被补成 http://，提示要跟最终存下来的值一致
        if (!isPlainHttp(normalize(url))) return null
        if (!isPrivateHost(host)) {
            return Note(
                "公网地址用了 http：API Key 和对话内容会明文经过网络。" +
                        "能用，但建议改成 https://。",
                severe = true
            )
        }
        return Note(
            "局域网明文地址，已允许。记得让电脑上的服务监听 0.0.0.0" +
                    "（而不是 127.0.0.1），并放行防火墙端口。",
            severe = false
        )
    }
}
