package com.phoclaw.chat.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.phoclaw.chat.util.BaseUrlHint
import com.phoclaw.chat.util.SystemPromptBuilder

/**
 * API 凭据与运行参数的加密存储。
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

    /**
     * 系统提示词。
     *
     * **未自定义时返回动态生成的内容**（[SystemPromptBuilder]），而不是一份
     * 写死在代码里的字符串。这样新增工具、改工具说明之后，提示词自动跟着变，
     * 不需要有人记得去同步一份多行文本 —— 之前正是因为忘了同步，
     * 出现过程序里能执行、模型却不知道有这个指令的情况。
     *
     * 用户手动编辑过之后（[hasCustomSystemPrompt] 为 true），就以用户的版本为准，
     * 不再自动生成 —— 那是用户明确表达的意图，不该被覆盖。
     *
     * 生成结果会缓存：这个方法在每一轮工具循环里都会被调用，
     * 而 `SystemPromptBuilder.build()` 要拼十几段围栏 + 一张表 + 近三十条规则。
     * 缓存键是 [codeExecEnabled] —— 它是唯一会改变生成内容的运行时开关。
     */
    var systemPrompt: String
        get() {
            prefs.getString(KEY_SYSTEM_PROMPT, null)?.let { return it }

            val flag = codeExecEnabled
            val cached = defaultPromptCache
            if (cached != null && defaultPromptFlag == flag) return cached

            return SystemPromptBuilder.build(flag).also {
                defaultPromptCache = it
                defaultPromptFlag = flag
            }
        }
        set(value) = prefs.edit().putString(KEY_SYSTEM_PROMPT, value).apply()

    /** 默认提示词的缓存。见 [systemPrompt] 的说明。 */
    @Volatile
    private var defaultPromptCache: String? = null

    @Volatile
    private var defaultPromptFlag: Boolean? = null

    /** 用户是否手动改过提示词。设置页据此显示「恢复默认」。 */
    val hasCustomSystemPrompt: Boolean
        get() = prefs.contains(KEY_SYSTEM_PROMPT)

    /** 丢弃自定义提示词，回到随工具表自动生成的内容。 */
    fun resetSystemPrompt() {
        prefs.edit().remove(KEY_SYSTEM_PROMPT).apply()
    }

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
     * Tavily API Key，用于联网搜索与网页抓取。
     *
     * 留空表示未启用 —— 此时 `websearch` / `webfetch` 会被拒绝，
     * 并明确告诉模型「用户没有配置」，这样它会改用其它方式回答，
     * 而不是反复重试一条注定失败的指令。
     */
    var tavilyKey: String
        get() = prefs.getString(KEY_TAVILY, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_TAVILY, value.trim()).apply()

    /** 是否已配置联网搜索。 */
    val searchEnabled: Boolean get() = tavilyKey.isNotBlank()

    /**
     * 是否允许把代码发送到远程执行服务。
     *
     * **默认关闭**：`run` 会把代码原文上传到 wandbox.org，这是明确的
     * 数据离开设备的行为，必须由用户主动打开，不能默认替用户做主。
     */
    var codeExecEnabled: Boolean
        get() = prefs.getBoolean(KEY_CODE_EXEC, false)
        set(value) = prefs.edit().putBoolean(KEY_CODE_EXEC, value).apply()

    /**
     * 是否把 `MEMORY.md` 注入每一轮的 system 消息。
     *
     * 默认开启：长期记忆的价值就在于「不用用户重复交代」。关掉只是不再
     * **读取**，文件内容原样保留，随时能再打开。
     */
    var memoryEnabled: Boolean
        get() = prefs.getBoolean(KEY_MEMORY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_MEMORY_ENABLED, value).apply()

    /**
     * 是否在超预算时自动压缩早期对话。
     *
     * 默认开启。关掉之后长对话最终会撞上后端的长度上限并报错 ——
     * 那是用户自己的选择，不做二次确认。
     */
    var autoCompress: Boolean
        get() = prefs.getBoolean(KEY_AUTO_COMPRESS, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_COMPRESS, value).apply()

    /**
     * 上下文预算（token）。
     *
     * 默认 32000：主流模型窗口多在 64k~128k，取一半是给回复和工具结果
     * 留余量。估算本身有 ±30% 误差，压得太紧反而会把有用的近况压掉。
     */
    var contextBudget: Int
        get() = prefs.getInt(KEY_CONTEXT_BUDGET, DEFAULT_CONTEXT_BUDGET)
            .coerceIn(MIN_CONTEXT_BUDGET, MAX_CONTEXT_BUDGET)
        set(value) = prefs.edit()
            .putInt(KEY_CONTEXT_BUDGET, value.coerceIn(MIN_CONTEXT_BUDGET, MAX_CONTEXT_BUDGET))
            .apply()

    /** 压缩时末尾保留多少条原文。必须是偶数才凑得成问答对，设置页会取整。 */
    var keepRecent: Int
        get() = prefs.getInt(KEY_KEEP_RECENT, DEFAULT_KEEP_RECENT)
            .coerceIn(MIN_KEEP_RECENT, MAX_KEEP_RECENT)
        set(value) = prefs.edit()
            .putInt(KEY_KEEP_RECENT, value.coerceIn(MIN_KEEP_RECENT, MAX_KEEP_RECENT))
            .apply()

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
        private const val KEY_CODE_EXEC = "code_exec_enabled"
        private const val KEY_MEMORY_ENABLED = "memory_enabled"
        private const val KEY_AUTO_COMPRESS = "auto_compress"
        private const val KEY_CONTEXT_BUDGET = "context_budget"
        private const val KEY_KEEP_RECENT = "keep_recent"

        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_MODEL = "deepseek-chat"

        const val DEFAULT_CONTEXT_BUDGET = 32_000
        const val MIN_CONTEXT_BUDGET = 4_000
        const val MAX_CONTEXT_BUDGET = 500_000

        const val DEFAULT_KEEP_RECENT = 6
        const val MIN_KEEP_RECENT = 2
        const val MAX_KEEP_RECENT = 40
    }
}
