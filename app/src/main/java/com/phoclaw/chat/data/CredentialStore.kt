package com.phoclaw.chat.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.phoclaw.chat.util.BaseUrlHint
import com.phoclaw.chat.util.SystemPromptBuilder
import org.json.JSONArray
import org.json.JSONObject

/**
 * API 凭据与运行参数的加密存储。
 *
 * 使用 AndroidX Security 的 EncryptedSharedPreferences，密钥由 Keystore 托管，
 * 避免 API Key 以明文形式落在 SharedPreferences 里被 root 设备或备份提取。
 *
 * ## 多档案
 *
 * 模型相关的参数（地址、Key、模型名、视觉、思考显示、上下文预算、温度）
 * 都存在 [ModelProfile] 里，可以有多份、随时切换。
 *
 * 但**对调用方保持旧接口**：`apiKey` / `baseUrl` / `model` 这些属性依然能直接读写，
 * 内部转发的都是「当前档案」。这样 `MainViewModel`、`CommandDispatcher` 里
 * 二十几处调用一行都不用改 —— 换模型的复杂度只留在存储层和界面上。
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

    // ------------------------------------------------------------ 模型档案

    /**
     * 档案列表的内存缓存。
     *
     * 一次对话最多迭代 8 轮，每轮要读七八次 `baseUrl` / `model`，
     * 每次都解一遍 JSON 纯属浪费。写操作会把它置空。
     */
    @Volatile
    private var cachedProfiles: List<ModelProfile>? = null

    /**
     * 全部档案。**至少一条** —— 首次启动（或旧版本升级上来）会就地迁移出
     * 一份「默认」档案，界面因此永远不需要处理空列表。
     */
    val profiles: List<ModelProfile>
        @Synchronized get() = cachedProfiles ?: readOrMigrate().also { cachedProfiles = it }

    /** 当前选中的档案 id。 */
    var activeProfileId: String
        get() {
            val saved = prefs.getString(KEY_ACTIVE_PROFILE, "").orEmpty()
            // 档案被删掉后 id 可能指向不存在的一条，这里兜底回第一条，
            // 否则 activeProfile 会抛异常让整个界面挂掉
            return if (profiles.any { it.id == saved }) saved else profiles.first().id
        }
        set(value) = prefs.edit().putString(KEY_ACTIVE_PROFILE, value).apply()

    /** 当前生效的档案。 */
    val activeProfile: ModelProfile
        get() {
            val id = activeProfileId
            return profiles.firstOrNull { it.id == id } ?: profiles.first()
        }

    /**
     * 保存一条档案：id 已存在则更新，否则追加。
     *
     * 返回落盘后的档案（`baseUrl` 可能被 [BaseUrlHint.normalize] 清洗过，
     * 调用方应该拿返回值回显，而不是用自己传进去的原始值）。
     */
    @Synchronized
    fun upsertProfile(profile: ModelProfile): ModelProfile {
        val cleaned = profile.copy(
            baseUrl = BaseUrlHint.normalize(profile.baseUrl).ifBlank { DEFAULT_BASE_URL },
            model = profile.model.trim().ifBlank { DEFAULT_MODEL },
            apiKey = profile.apiKey.trim(),
            contextBudget = profile.contextBudget.coerceIn(MIN_CONTEXT_BUDGET, MAX_CONTEXT_BUDGET),
            temperature = profile.temperature.coerceIn(
                ModelProfile.MIN_TEMPERATURE, ModelProfile.MAX_TEMPERATURE
            )
        )
        val list = profiles.toMutableList()
        val index = list.indexOfFirst { it.id == cleaned.id }
        if (index >= 0) list[index] = cleaned else list += cleaned
        writeProfiles(list)
        return cleaned
    }

    /**
     * 删除档案。
     *
     * **不允许删掉最后一条**：空列表会让 `activeProfile` 无处可退，
     * 所有依赖它的地方都得加空判断。与其到处兜底，不如在这里挡住 ——
     * 界面上那个删除按钮也会同步禁用。
     */
    @Synchronized
    fun deleteProfile(id: String): Boolean {
        val list = profiles
        if (list.size <= 1) return false
        val next = list.filterNot { it.id == id }
        if (next.size == list.size) return false
        writeProfiles(next)
        if (activeProfileId == id) activeProfileId = next.first().id
        return true
    }

    /** 切到另一份档案。id 不存在时不做任何事。 */
    fun selectProfile(id: String) {
        if (profiles.any { it.id == id }) activeProfileId = id
    }

    /** 新建一份空白档案并立刻切换过去，返回它。 */
    fun createProfile(): ModelProfile {
        val created = ModelProfile(
            name = "新档案",
            contextBudget = DEFAULT_CONTEXT_BUDGET
        )
        upsertProfile(created)
        activeProfileId = created.id
        return created
    }

    private fun readOrMigrate(): List<ModelProfile> {
        val raw = prefs.getString(KEY_PROFILES, null)
        if (!raw.isNullOrBlank()) {
            val parsed = runCatching {
                val array = JSONArray(raw)
                (0 until array.length()).mapNotNull { i ->
                    array.optJSONObject(i)?.let { ModelProfile.fromJson(it) }
                }
            }.getOrDefault(emptyList())
            // 解析失败或全被过滤掉时不能返回空列表 —— 会连带把用户填过的
            // Key 一起丢掉。走迁移路径至少能保住旧的那一份
            if (parsed.isNotEmpty()) return parsed
        }
        return migrateLegacy().also { list ->
            // 首次迁移要写回，否则每次启动都重新造一条、id 还会变
            writeProfiles(list)
            if (prefs.getString(KEY_ACTIVE_PROFILE, "").isNullOrBlank()) {
                prefs.edit().putString(KEY_ACTIVE_PROFILE, list.first().id).apply()
            }
        }
    }

    /**
     * 把旧版本的单份配置搬成第一条档案。
     *
     * 旧 key 不删除：万一迁移逻辑本身有 bug，用户回滚到旧版本时
     * 填过的 Key 还在。代价只是几个字符串占着空间。
     */
    private fun migrateLegacy(): List<ModelProfile> {
        val legacy = ModelProfile(
            name = "默认",
            baseUrl = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL).orEmpty()
                .ifBlank { DEFAULT_BASE_URL },
            apiKey = prefs.getString(KEY_API, "").orEmpty(),
            model = prefs.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty()
                .ifBlank { DEFAULT_MODEL },
            supportsVision = prefs.getBoolean(KEY_VISION, false),
            contextBudget = prefs.getInt(KEY_CONTEXT_BUDGET, DEFAULT_CONTEXT_BUDGET)
                .coerceIn(MIN_CONTEXT_BUDGET, MAX_CONTEXT_BUDGET)
        )
        return listOf(legacy)
    }

    private fun writeProfiles(list: List<ModelProfile>) {
        val array = JSONArray()
        list.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_PROFILES, array.toString()).apply()
        cachedProfiles = list
    }

    // ---------------------------------------------------- 转发到当前档案的属性

    var apiKey: String
        get() = activeProfile.apiKey
        set(value) = updateActive { it.copy(apiKey = value.trim()) }

    var baseUrl: String
        get() = activeProfile.baseUrl.ifBlank { DEFAULT_BASE_URL }
        set(value) = updateActive {
            it.copy(baseUrl = BaseUrlHint.normalize(value).ifBlank { DEFAULT_BASE_URL })
        }

    var model: String
        get() = activeProfile.model.ifBlank { DEFAULT_MODEL }
        set(value) = updateActive { it.copy(model = value.trim()) }

    /**
     * 当前模型是否支持视觉输入。
     *
     * 开启后，用户发送的图片会转成 Base64 走多模态 `image_url` 通道；
     * 关闭时只发送图片的文字说明（文件名 + 大小），模型看不到画面。
     *
     * **默认 false 是刻意的保守选择**：多模态要求把 content 写成数组，
     * 而不支持视觉的后端（老版 Ollama、部分国产网关）收到数组会直接报 400。
     */
    var modelSupportsVision: Boolean
        get() = activeProfile.supportsVision
        set(value) = updateActive { it.copy(supportsVision = value) }

    /** 是否把模型吐出的思考过程（`reasoning_content`）展示出来。 */
    var showReasoning: Boolean
        get() = activeProfile.showReasoning
        set(value) = updateActive { it.copy(showReasoning = value) }

    /** 采样温度。推理模型多数要求 temperature = 1，交给用户自己按模型设置。 */
    var temperature: Double
        get() = activeProfile.temperature
        set(value) = updateActive {
            it.copy(
                temperature = value.coerceIn(
                    ModelProfile.MIN_TEMPERATURE, ModelProfile.MAX_TEMPERATURE
                )
            )
        }

    /**
     * 上下文预算（token）。
     *
     * 默认 32000：主流模型窗口多在 64k~128k，取一半是给回复和工具结果
     * 留余量。估算本身有 ±30% 误差，压得太紧反而会把有用的近况压掉。
     *
     * 上下限与 [MIN_CONTEXT_BUDGET] / [MAX_CONTEXT_BUDGET] 一致，
     * 旧数据里超出范围的值读出来会被夹到边界。
     */
    var contextBudget: Int
        get() = activeProfile.contextBudget
            .coerceIn(MIN_CONTEXT_BUDGET, MAX_CONTEXT_BUDGET)
        set(value) = updateActive {
            it.copy(contextBudget = value.coerceIn(MIN_CONTEXT_BUDGET, MAX_CONTEXT_BUDGET))
        }

    /** 就地修改当前档案。档案列表常量极小，直接整体重写比精细合并安全。 */
    @Synchronized
    private fun updateActive(transform: (ModelProfile) -> ModelProfile) {
        val current = activeProfile
        val list = profiles.toMutableList()
        val index = list.indexOfFirst { it.id == current.id }
        val updated = transform(current)
        if (index >= 0) list[index] = updated else list += updated
        writeProfiles(list)
    }

    // ---------------------------------------------------------- 全局运行参数

    /**
     * 用户自己写的那部分提示词 —— 角色、语气、项目约定这类。
     *
     * 工具说明**不存这里**：它在每次读 [systemPrompt] 时现拼，永远追加在后面。
     * 这样工具表加了新指令，用户的「回答用中文」不用跟着重抄一遍。
     */
    var customSystemPrompt: String
        get() = prefs.getString(KEY_CUSTOM_PROMPT, null) ?: migrateLegacyPrompt()
        set(value) {
            val trimmed = value.trim()
            val editor = prefs.edit()
            if (trimmed.isEmpty()) editor.remove(KEY_CUSTOM_PROMPT)
            else editor.putString(KEY_CUSTOM_PROMPT, trimmed)
            editor.apply()
        }

    /**
     * 完整系统提示词 = 自定义部分 + 工具段（见 [SystemPromptBuilder.compose]）。
     *
     * 两个部分分开维护的原因见 `SystemPromptBuilder` 的类注释。这里只负责
     * 把工具段缓存起来：这个方法在每一轮工具循环里都会被调用，而工具段要拼
     * 十几段围栏 + 一张表 + 近三十条规则，每次都重算纯属浪费。
     *
     * 缓存的是**工具段**而不是最终结果 —— 最终结果还取决于用户自定义的内容，
     * 那部分随时会变，不该被缓存。
     */
    val systemPrompt: String
        get() {
            val custom = customSystemPrompt
            val flag = Triple(codeExecEnabled, shellEnabled, DeviceEnv.shellAvailable)
            val section = promptSectionCache?.takeIf { promptSectionFlag == flag }
                ?: SystemPromptBuilder.toolSection(
                    codeExecEnabled = flag.first,
                    shellEnabled = flag.second,
                    shellSupported = flag.third
                ).also {
                    promptSectionCache = it
                    promptSectionFlag = flag
                }
            return SystemPromptBuilder.compose(custom, section)
        }

    /** 工具段的缓存。见 [systemPrompt] 的说明。 */
    @Volatile
    private var promptSectionCache: String? = null

    @Volatile
    private var promptSectionFlag: Triple<Boolean, Boolean, Boolean>? = null

    /**
     * 1.11.0 及之前 `KEY_SYSTEM_PROMPT` 存的是「整份提示词」，里面已经内嵌了
     * 工具段。直接拿来当自定义部分会让工具说明出现两遍，所以读到时先切一刀。
     *
     * 旧 key 保留不删：万一要回滚到旧版本，那时读的还是它。
     */
    private fun migrateLegacyPrompt(): String {
        val legacy = prefs.getString(KEY_SYSTEM_PROMPT, null) ?: return ""
        val custom = SystemPromptBuilder.extractCustomPart(legacy)
        // 写成空串而不是 remove：下次读到 ""（非 null）就不会反复走迁移
        prefs.edit().putString(KEY_CUSTOM_PROMPT, custom).apply()
        return custom
    }

    /** 用户是否写了自定义部分。设置页据此显示「清空」是否可点。 */
    val hasCustomSystemPrompt: Boolean
        get() = customSystemPrompt.isNotBlank()

    /** 清空自定义部分。清空后只剩工具段（含默认开场白）。 */
    fun resetSystemPrompt() {
        // 两个 key 都要删：只删新 key 的话，下次读取会从旧 key 又迁移回来
        prefs.edit().remove(KEY_CUSTOM_PROMPT).remove(KEY_SYSTEM_PROMPT).apply()
    }

    /** 已授权的工作区目录 URI，进程重启后据此恢复。 */
    var workspaceUri: String
        get() = prefs.getString(KEY_WORKSPACE_URI, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_WORKSPACE_URI, value).apply()

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
     * 是否允许 AI 在本机执行 shell 命令。
     *
     * **默认关闭，且比 `run` 更需要谨慎**：`run` 是在远程容器里跑，最坏结果
     * 是代码泄露；本地 shell 拿的是应用自己的 UID，能读写应用私有目录、
     * 能杀进程、能发网络请求。这是明确的「交出执行权」，只能由用户主动打开。
     *
     * 在鸿蒙设备上这个开关永远不会生效 —— 见 `DeviceEnv`。
     */
    var shellEnabled: Boolean
        get() = prefs.getBoolean(KEY_SHELL_EXEC, false)
        set(value) = prefs.edit().putBoolean(KEY_SHELL_EXEC, value).apply()

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

        /** 档案列表（整份 JSON 数组）。 */
        private const val KEY_PROFILES = "model_profiles"
        private const val KEY_ACTIVE_PROFILE = "active_profile_id"

        // 旧版单份配置的 key。只读不写 —— 迁移用，留着以防回滚
        private const val KEY_API = "api_key"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL = "model"
        private const val KEY_VISION = "model_supports_vision"

        /** 旧版的全局上下文预算。现在预算随档案走，这个 key 只在迁移时读一次。 */
        private const val KEY_CONTEXT_BUDGET = "context_budget"

        /** 1.11.0 及之前：整份提示词。现在只读，用于迁移。 */
        private const val KEY_SYSTEM_PROMPT = "system_prompt"

        /** 1.11.1 起：只存用户写的那部分，工具段在运行时拼。 */
        private const val KEY_CUSTOM_PROMPT = "custom_system_prompt"
        private const val KEY_WORKSPACE_URI = "workspace_uri"
        private const val KEY_TAVILY = "tavily_api_key"
        private const val KEY_CODE_EXEC = "code_exec_enabled"
        private const val KEY_SHELL_EXEC = "shell_exec_enabled"
        private const val KEY_MEMORY_ENABLED = "memory_enabled"
        private const val KEY_AUTO_COMPRESS = "auto_compress"
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
