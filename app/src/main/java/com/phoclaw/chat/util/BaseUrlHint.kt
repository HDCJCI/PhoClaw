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
            url = "https://$url"
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
}
