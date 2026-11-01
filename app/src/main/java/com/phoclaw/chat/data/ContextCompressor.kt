package com.phoclaw.chat.data

/**
 * 上下文预算估算与压缩。
 *
 * 解决的问题很具体：在此之前 `buildApiMessages()` 是**无条件全量发送**，
 * 聊到几十轮、或者几轮里读过几个大文件之后，请求必然因为超长被后端拒掉。
 * 而工具结果回灌（`isInternal`）那部分尤其能撑爆上下文 —— 一次 `read`
 * 就是几千行常驻。
 *
 * 为什么不用精确 tokenizer：Android 上没有轻量的多模型 tokenizer 实现，
 * 塞一个 BPE 词表进来要几 MB。字符估算的误差在 ±30% 以内，只要预算留够
 * 余量（默认按模型窗口的一半算）就完全够用。压缩是「避免炸掉」的手段，
 * 不是「榨干窗口」的优化，宁可多压一点。
 */
object TokenEstimator {

    /**
     * 估算一段文本的 token 数。
     *
     * 中日韩字符按 **1 字符 ≈ 1 token** 估（实际多在 0.6~1.0 之间，
     * 取上限是刻意的保守），其余字符按 4 字符 ≈ 1 token。
     */
    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var other = 0
        for (ch in text) {
            // 0x2E80 之后基本是 CJK 部首、汉字、假名、韩文、全角标点
            if (ch.code >= 0x2E80) cjk++ else other++
        }
        return cjk + other / 4 + 1
    }

    /** 单条消息的估算，含消息结构本身的开销。 */
    fun estimate(message: ChatMessage): Int {
        var tokens = estimate(message.content) + MESSAGE_OVERHEAD
        // 图片按固定值估：真实开销随分辨率浮动，这里只求量级正确
        tokens += message.parts.count { it is ContentPart.Image } * IMAGE_TOKENS
        return tokens
    }

    fun estimate(messages: List<ChatMessage>): Int = messages.sumOf { estimate(it) }

    private const val MESSAGE_OVERHEAD = 4
    private const val IMAGE_TOKENS = 1_000
}

/**
 * 把早期对话压成摘要，腾出上下文空间。
 *
 * 策略是**滚动摘要**：每次只压缩「最老的一批、且不碰最近几条」，
 * 把结果和上一份摘要合并成一份新的。这样：
 * - 界面上的消息一条不丢，用户往上翻还是能看全文
 * - 模型拿到的是「摘要 + 最近的原文」，老信息不会直接蒸发
 * - 每次只总结增量，不会因为对话变长而让压缩请求本身越来越大
 *
 * 压缩失败（网络问题、模型抽风）时由调用方降级为滑动窗口 —— 宁可丢掉
 * 最早的几条原文，也不能让整个对话因为超长彻底不能用。
 */
class ContextCompressor(private val llm: LlmClient) {

    sealed interface Outcome {
        /** 压缩成功。 */
        data class Compressed(
            val summary: String,
            val summarizedCount: Int,
            val freedTokens: Int
        ) : Outcome

        /** 不需要压缩。 */
        data class NotNeeded(val usedTokens: Int) : Outcome

        /** 想压但压不了，附带原因（交给用户看）。 */
        data class Failed(val reason: String) : Outcome
    }

    /**
     * 判断是否需要压缩，需要就执行。
     *
     * @param summary 已有摘要（首次为空）
     * @param summarizedCount 已有摘要已覆盖的消息条数
     * @param messages 完整上下文（不含 system，但含内部注入的工具结果）
     * @param baseTokens system + 长期记忆的固定开销，算预算时必须计入
     * @param budgetTokens 上下文预算
     * @param keepRecent 末尾保留多少条原文不参与压缩
     */
    suspend fun compressIfNeeded(
        baseUrl: String,
        apiKey: String,
        model: String,
        summary: String,
        summarizedCount: Int,
        messages: List<ChatMessage>,
        baseTokens: Int,
        budgetTokens: Int,
        keepRecent: Int
    ): Outcome {
        val pending = messages.drop(summarizedCount.coerceIn(0, messages.size))
        val used = baseTokens + TokenEstimator.estimate(summary) + TokenEstimator.estimate(pending)
        if (used <= budgetTokens) return Outcome.NotNeeded(used)

        // 切分点：末尾 keepRecent 条不动，且尽量落在 user 消息之前。
        // 从 assistant 处切开会留下一条「没有提问的回答」，模型读起来莫名其妙。
        val maxCut = (messages.size - keepRecent).coerceAtLeast(summarizedCount)
        val cut = alignToUserBoundary(messages, summarizedCount, maxCut)
        if (cut <= summarizedCount) {
            return Outcome.Failed(
                "需要压缩，但最近 $keepRecent 条消息已经占满了预算。" +
                        "可以在设置里调小「保留最近消息」，或者开一个新会话。"
            )
        }

        val batch = messages.subList(summarizedCount, cut)
        val prompt = buildPrompt(summary, batch)

        val result = llm.complete(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            messages = listOf(
                ChatMessage(ChatMessage.ROLE_SYSTEM, SUMMARIZER_SYSTEM),
                ChatMessage(ChatMessage.ROLE_USER, prompt)
            ),
            temperature = 0.2,
            maxTokens = MAX_SUMMARY_TOKENS
        )

        val text = result.getOrNull()?.trim().orEmpty()
        if (result.isFailure) {
            return Outcome.Failed("压缩失败：${result.exceptionOrNull()?.message ?: "未知错误"}")
        }
        if (text.isBlank()) {
            return Outcome.Failed("压缩失败：模型返回了空摘要")
        }

        val freed = TokenEstimator.estimate(pending) -
                (TokenEstimator.estimate(text) + TokenEstimator.estimate(
                    messages.subList(cut, messages.size)
                ))
        return Outcome.Compressed(
            summary = text.take(MAX_SUMMARY_CHARS),
            summarizedCount = cut,
            freedTokens = freed.coerceAtLeast(0)
        )
    }

    /**
     * 把切分点往回挪到最近的 user 消息处。
     *
     * 目标是让 `[summarizedCount, cut)` 这一段**以 assistant 结尾** ——
     * 切出去的是完整的一问一答，剩下的也以提问开头。
     *
     * 关键：只能**往回**找，绝不能往前推。往前推意味着压得更多，可能压进
     * 「保留区」，把用户刚说的话也吞掉 —— 症状是模型对最新的提问毫无反应。
     *
     * 这里有个容易写错的地方：切分点必须从 `maxCut` **往回**找，
     * 而不是从 `maxCut` 往后找。往后即压得更多，会吃进保留区。
     */
    private fun alignToUserBoundary(
        messages: List<ChatMessage>,
        from: Int,
        maxCut: Int
    ): Int {
        val limit = maxCut.coerceIn(from, messages.size)
        if (limit <= from) return limit

        for (i in limit downTo from) {
            if (i < messages.size && messages[i].role == ChatMessage.ROLE_USER) return i
        }
        return limit
    }

    /**
     * 组装压缩请求。
     *
     * 单条消息过长时先按 [MAX_ITEM_CHARS] 截断 —— 否则「为了省上下文而发的
     * 请求」自己就先超了预算，变成一个笑话。
     */
    private fun buildPrompt(existing: String, batch: List<ChatMessage>): String = buildString {
        if (existing.isNotBlank()) {
            appendLine("<已有摘要>")
            appendLine(existing.trim())
            appendLine("</已有摘要>")
            appendLine()
        }
        appendLine("<新增对话>")
        batch.forEach { msg ->
            val label = when (msg.role) {
                ChatMessage.ROLE_USER -> "用户"
                ChatMessage.ROLE_ASSISTANT -> "助手"
                else -> msg.role
            }
            val body = if (msg.content.length > MAX_ITEM_CHARS) {
                msg.content.take(MAX_ITEM_CHARS) + "\n…（本条过长已截断）"
            } else {
                msg.content
            }
            appendLine("$label：$body")
            appendLine()
        }
        appendLine("</新增对话>")
        appendLine()
        if (existing.isNotBlank()) {
            appendLine("把已有摘要和新增对话**整合成一份**新的摘要。")
        } else {
            appendLine("把上面的对话写成一份摘要。")
        }
        append("只输出摘要正文，不要加标题、不要加解释。")
    }

    private companion object {
        /** 摘要本身的长度上限，约 500 汉字。 */
        const val MAX_SUMMARY_CHARS = 1_500

        /** 生成摘要的 token 上限，防止模型把摘要写成小作文。 */
        const val MAX_SUMMARY_TOKENS = 1_200

        /** 压缩请求里单条消息的截断长度。 */
        const val MAX_ITEM_CHARS = 3_000

        val SUMMARIZER_SYSTEM = """
            你是对话摘要器。把给定对话压缩成一段事实性摘要，供后续对话继续参考。

            必须保留：
            - 用户的目标、约束与明确要求
            - 已确认的技术决策与结论（比如「用 A 方案而不是 B」）
            - 已执行的操作及其结果（改了哪个文件、命令输出说明了什么）
            - 尚未完成的待办、已知问题
            - 关键的文件路径、函数名、变量名、配置值

            必须丢弃：
            - 客套话、重复的解释、对同一件事的反复确认
            - 已被否决或作废的尝试过程（只保留「试过 X 不行」的结论）
            - 完整代码清单（保留文件名和改动要点即可）

            不要编造原文没有的内容。不要写「用户和助手讨论了……」这类空话，
            直接陈述事实。控制在 600 字以内。
        """.trimIndent()
    }
}
