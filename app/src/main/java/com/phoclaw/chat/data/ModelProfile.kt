package com.phoclaw.chat.data

import org.json.JSONObject
import java.util.UUID

/**
 * 一份模型配置档案。
 *
 * ## 为什么要拆成档案
 *
 * 之前全局只有一份 `baseUrl + apiKey + model`。这个设计在「只有一个后端」时
 * 没问题，但实际使用中几乎必然会撞上三种情况：
 *
 * - 用 DeepSeek 写代码、用 Qwen-VL 看图，两边 Base URL 和 Key 都不一样；
 * - 同一家服务商的推理模型（如 `deepseek-reasoner`）和普通模型，
 *   「是否显示思考过程」「上下文预算」这些参数是**模型级**的，不是全局级；
 * - 临时切一个本地 Ollama 试一下，切回来时得把前面填的全 recall 一遍。
 *
 * 所以把一个模型的**全部参数**收进档案：切档案 = 切模型 = 切后端，
 * 不需要记任何东西。
 *
 * ## 为什么不存进 `CredentialStore` 的独立 key
 *
 * 每个档案一组 `profile_<id>_xxx` 的 key 会让「列出所有档案」变成
 * 遍历 pref 全部键名的脏活，删除档案还要逐个 key 移除，漏一个就残留。
 * 整份列表序列化成一个 JSON 字符串最省事 —— 反正都在加密 prefs 里，
 * API Key 依然不会明文落盘。
 *
 * @param supportsVision 是否支持视觉输入。属于模型能力，必须随档案走
 * @param showReasoning 是否显示思考过程（`reasoning_content`）。同样属于模型能力：
 *   推理模型有、普通模型没有
 * @param contextBudget 上下文预算（token）。不同模型窗口从 8k 到 1M 不等，
 *   用一个全局值必然要么浪费要么溢出
 * @param temperature 采样温度
 */
data class ModelProfile(
    val id: String = UUID.randomUUID().toString(),
    /** 用户起的名字，界面上显示这个。留空时退回显示模型名。 */
    val name: String = "",
    val baseUrl: String = CredentialStore.DEFAULT_BASE_URL,
    val apiKey: String = "",
    val model: String = CredentialStore.DEFAULT_MODEL,
    val supportsVision: Boolean = false,
    val showReasoning: Boolean = true,
    val contextBudget: Int = CredentialStore.DEFAULT_CONTEXT_BUDGET,
    val temperature: Double = DEFAULT_TEMPERATURE
) {

    /** 列表里显示的名字：优先用户起的，其次模型名，最后兜底。 */
    val displayName: String
        get() = name.ifBlank { model.ifBlank { "未命名档案" } }

    /** 副标题：让用户一眼看出这条档案连的是哪个后端。 */
    val subtitle: String
        get() = buildString {
            if (name.isNotBlank() && model.isNotBlank()) append(model)
            val host = baseUrl.substringAfter("://").substringBefore('/')
            if (host.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(host)
            }
            if (apiKey.isBlank()) {
                if (isNotEmpty()) append(" · ")
                append("未填 Key")
            }
        }

    /** 是否填齐了能用的信息。 */
    val isConfigured: Boolean get() = apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("baseUrl", baseUrl)
        put("apiKey", apiKey)
        put("model", model)
        put("supportsVision", supportsVision)
        put("showReasoning", showReasoning)
        put("contextBudget", contextBudget)
        put("temperature", temperature)
    }

    companion object {
        const val DEFAULT_TEMPERATURE = 0.7
        const val MIN_TEMPERATURE = 0.0
        const val MAX_TEMPERATURE = 2.0

        /**
         * 反序列化。字段全部带默认值 —— 旧版本存的档案没有 `showReasoning`
         * 之类的字段时，不该让整条档案读不出来。
         */
        fun fromJson(obj: JSONObject): ModelProfile = ModelProfile(
            id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = obj.optString("name"),
            baseUrl = obj.optString("baseUrl").ifBlank { CredentialStore.DEFAULT_BASE_URL },
            apiKey = obj.optString("apiKey"),
            model = obj.optString("model").ifBlank { CredentialStore.DEFAULT_MODEL },
            supportsVision = obj.optBoolean("supportsVision", false),
            showReasoning = obj.optBoolean("showReasoning", true),
            contextBudget = obj.optInt("contextBudget", CredentialStore.DEFAULT_CONTEXT_BUDGET)
                .coerceIn(CredentialStore.MIN_CONTEXT_BUDGET, CredentialStore.MAX_CONTEXT_BUDGET),
            temperature = obj.optDouble("temperature", DEFAULT_TEMPERATURE)
                .takeIf { !it.isNaN() }
                ?.coerceIn(MIN_TEMPERATURE, MAX_TEMPERATURE)
                ?: DEFAULT_TEMPERATURE
        )
    }
}
