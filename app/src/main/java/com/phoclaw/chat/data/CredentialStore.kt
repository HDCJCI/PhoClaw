package com.phoclaw.chat.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.phoclaw.chat.util.BaseUrlHint

/**
 * API 凭据的加密存储。
 *
 * 使用 AndroidX Security 的 EncryptedSharedPreferences，密钥由 Keystore 托管，
 * 避免 API Key 以明文形式落在 SharedPreferences 里被 root 设备或备份提取。
 */
class CredentialStore(context: Context) {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var apiKey: String
        get() = prefs.getString(KEY_API, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_API, value.trim()).apply()

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL).orEmpty().ifBlank { DEFAULT_BASE_URL }
        set(value) = prefs.edit()
            .putString(KEY_BASE_URL, BaseUrlHint.normalize(value).ifBlank { DEFAULT_BASE_URL })
            .apply()

    var model: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL }
        set(value) = prefs.edit().putString(KEY_MODEL, value.trim()).apply()

    /** 系统提示词：让模型知道它可以操作工作区，并按约定格式输出文件操作指令。 */
    var systemPrompt: String
        get() = prefs.getString(KEY_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT).orEmpty()
        set(value) = prefs.edit().putString(KEY_SYSTEM_PROMPT, value).apply()

    /** 已授权的工作区目录 URI，进程重启后据此恢复。 */
    var workspaceUri: String
        get() = prefs.getString(KEY_WORKSPACE_URI, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_WORKSPACE_URI, value).apply()

    /**
     * 当前模型是否支持视觉输入。
     *
     * 开启后，用户发送的图片会转成 Base64 走多模态 `image_url` 通道；
     * 关闭时只发送图片的文字说明（文件名 + 大小），模型看不到画面。
     *
     * **默认 false 是刻意的保守选择**：多模态要求把 content 写成数组，
     * 而不支持视觉的后端（老版 Ollama、部分国产网关）收到数组会直接报 400。
     * 用户不主动打开，就绝不会踩到这个坑。
     */
    var modelSupportsVision: Boolean
        get() = prefs.getBoolean(KEY_VISION, false)
        set(value) = prefs.edit().putBoolean(KEY_VISION, value).apply()

    /**
     * Tavily API Key，用于联网搜索。
     *
     * 留空表示未启用联网搜索 —— 此时 `websearch` 指令会被拒绝，
     * 并明确告诉模型「用户没有配置」，这样它会改用其它方式回答，
     * 而不是反复重试一条注定失败的指令。
     */
    var tavilyKey: String
        get() = prefs.getString(KEY_TAVILY, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_TAVILY, value.trim()).apply()

    /** 是否已配置联网搜索。 */
    val searchEnabled: Boolean get() = tavilyKey.isNotBlank()

    val isConfigured: Boolean get() = apiKey.isNotBlank()

    companion object {
        private const val PREFS_NAME = "phoclaw_secure_credentials"
        private const val KEY_API = "api_key"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL = "model"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"
        private const val KEY_WORKSPACE_URI = "workspace_uri"
        private const val KEY_VISION = "model_supports_vision"
        private const val KEY_TAVILY = "tavily_api_key"

        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_MODEL = "deepseek-chat"

        val DEFAULT_SYSTEM_PROMPT = """
            你是一个可以直接操作用户手机工作区文件的编程助手。你可以读取、创建、修改、删除、复制和移动文件。

            ## 指令格式

            用代码块输出指令，语言标记为 phoclaw:动作名：

            ```phoclaw:list .
            ```

            ```phoclaw:tree .
            ```

            ```phoclaw:read src/main.js
            ```

            ```phoclaw:write src/main.js
            文件完整内容
            ```

            ```phoclaw:append src/main.js
            追加到末尾的内容
            ```

            ```phoclaw:search TODO src
            ```

            ```phoclaw:websearch 2026年最新的 Kotlin 版本
            ```

            ```phoclaw:info src/main.js
            ```

            ```phoclaw:mkdir src/components
            ```

            ```phoclaw:copy src/a.js -> src/b.js
            ```

            ```phoclaw:move src/old.js -> src/new.js
            ```

            ```phoclaw:delete src/tmp.js
            ```

            ## 各指令说明

            | 指令 | 用途 |
            |---|---|
            | `list <目录>` | 列出目录下的文件和子目录 |
            | `tree <目录>` | 递归展示目录树（默认 3 层） |
            | `read <文件>` | 读取文件全文 |
            | `write <文件>` | 写入文件（覆盖原有内容，父目录自动创建） |
            | `append <文件>` | 在文件末尾追加内容 |
            | `search <关键词> [目录]` | 搜索**工作区文件内容**，返回「文件:行号: 内容」 |
            | `websearch <关键词>` | **联网搜索**互联网，返回摘要与来源链接 |
            | `info <路径>` | 查看大小、类型、修改时间 |
            | `mkdir <目录>` | 创建目录 |
            | `copy <源> -> <目标>` | 复制文件或目录（目录递归复制） |
            | `move <源> -> <目标>` | 移动或重命名 |
            | `delete <路径>` | 删除文件或目录 |

            ## 规则

            1. **先看再改**：修改现有代码前，先用 `list` / `tree` / `read` 了解结构，不要凭猜测直接覆盖。
            2. **改代码优先用 `write` 重写整个文件**，确保内容完整。不要输出「此处省略」「...」之类的占位符——那会让文件损坏。少量追加才用 `append`。
            3. `write` 代码块内必须是文件**完整**内容，不要带行号，不要加额外说明文字。
            4. 路径一律用相对路径（相对于工作区根目录），不要以 `/` 开头，**不要使用 `..`**（会被拒绝）。
            5. `copy` 和 `move` 用 `->` 分隔源和目标，例如 `copy src/a.js -> src/b.js`。
            6. 一次回复可以包含多个指令块，会**按书写顺序**执行，结果一次性反馈给你。
            7. **删除操作要谨慎**：删除前先确认路径正确，不要删除用户可能还需要的东西。
            8. 如果用户只是聊天或问问题，正常回答即可，不需要输出任何指令。
            9. **联网搜索的时机**：当问题涉及实时信息（新闻、股价、天气、最新版本号、近期事件）或你不确定的事实，先用 `websearch 关键词` 查证再回答。不要凭记忆猜测时效性内容。
            10. 不要为了「显得严谨」而滥用搜索：常识、代码写法、数学计算这类稳定知识直接回答即可。
            11. 引用搜索结果时，把来源链接一并给出，方便用户核对。
            12. 不要在回复里解释指令格式本身，直接使用即可。
            13. 回复保持简洁，用中文。
        """.trimIndent()
    }
}
