package com.phoclaw.chat.data

import android.content.Context
import android.util.Log
import com.phoclaw.chat.util.CommandParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import java.util.UUID

/**
 * 无头对话执行引擎：在没有界面、没有 Activity 的情况下把一整轮对话跑完。
 *
 * ## 为什么不复用 MainViewModel
 *
 * 三个原因，一个比一个致命：
 * 1. 它绑定 Activity 的 `ViewModelStore`，后台执行时 Activity 可能已经销毁
 * 2. 强行持有 Activity 引用会内存泄漏
 * 3. **最要命的**：`requestApproval()` 里用 `CompletableDeferred.await()` 等用户点确认框。
 *    后台执行时没有人能点，那个协程会**永久挂起**，整个前台服务卡死直到被系统杀掉。
 *
 * 所以这里完全绕开 `StateFlow` 和界面依赖，用纯数据列表承载上下文，
 * 并且**危险操作不弹框** —— 由任务的 `allowDangerous` 开关预先决定允许还是拒绝。
 *
 * ## 关于危险操作
 *
 * 用户在建任务时勾选「允许危险操作」就是一次**预先授权**。这实质上等价于
 * 「把这个任务在这个范围内永久批准」，所以那个开关的 UI 上带了红色警告。
 * 没勾选时 Runner 会拦下 delete / move 并把「已拒绝」写回日志 ——
 * 模型因此知道发生了什么，可以换个方式继续，而不是傻等一个永远不会有人点的对话框。
 */
class HeadlessTurnRunner(
    private val app: Context,
    private val credentials: CredentialStore,
    private val workspace: WorkspaceRepository,
    private val skillStore: SkillStore,
    private val conversations: ConversationStore,
    private val llm: LlmClient = LlmClient()
) {

    /** 执行结果。 */
    data class Outcome(
        val success: Boolean,
        /** 一句话摘要，进通知和任务卡片。 */
        val summary: String,
        val conversationId: String,
        /** 执行的完整消息列表，已落盘。 */
        val messages: List<StoredMessage>
    )

    /**
     * 执行一个任务。
     *
     * @param onProgress 进度回调，用于更新前台服务的常驻通知
     */
    suspend fun run(
        task: AutomationTask,
        conversationId: String,
        onProgress: (String) -> Unit = {}
    ): Outcome {
        // 每次覆盖写同一个会话，所以直接沿用任务绑定的 id
        val cid = conversationId

        if (!credentials.isConfigured) {
            return Outcome(
                success = false,
                summary = "未配置 API Key，请先在设置里填写",
                conversationId = cid,
                messages = emptyList()
            )
        }

        if (!workspace.isReady) {
            return Outcome(
                success = false,
                summary = "工作区未授权或授权已失效，请重新选择工作区",
                conversationId = cid,
                messages = emptyList()
            )
        }

        // 载入既有上下文（首次执行时为空）
        val existing = conversations.load(cid)?.messages.orEmpty().toMutableList()

        // 只保留最近若干条，避免长跑任务把上下文撑爆。
        // 定时任务每次都是独立的一次执行，太久远的历史参考价值很低
        val history = if (existing.size > MAX_HISTORY) {
            existing.subList(existing.size - MAX_HISTORY, existing.size).toMutableList()
        } else {
            existing
        }

        val messages = history
        messages += StoredMessage(
            role = ChatMessage.ROLE_USER,
            text = task.prompt
        )

        val activeSkills = runCatching { skillStore.enabled() }.getOrDefault(emptyList())
        val systemPrompt = PromptComposer.compose(credentials.systemPrompt, activeSkills)
        val whitelist = PromptComposer.effectiveWhitelist(activeSkills)

        // 联网搜索在后台任务里同样可用：用户选的「两者都能用」。
        // 这里的 dispatcher 每次 run 都新建，保证拿到的是当前最新的 Tavily Key
        val dispatcher = CommandDispatcher(
            workspace = workspace,
            tavily = TavilyClient(),
            tavilyKey = credentials.tavilyKey
        )
        var lastAssistantText = ""
        var round = 0

        try {
            while (round < MAX_ROUNDS) {
                round++
                onProgress("第 $round 轮：正在思考")

                val apiMessages = buildApiMessages(systemPrompt, messages)
                val text = streamOnce(apiMessages)

                if (text.isNullOrBlank()) {
                    // 空回复：可能是模型抽风，也可能是网络中断。结束这一轮
                    messages += StoredMessage(
                        role = ChatMessage.ROLE_ASSISTANT,
                        text = ""
                    )
                    break
                }

                val parsed = CommandParser.parse(text)
                lastAssistantText = parsed.displayText

                messages += StoredMessage(
                    role = ChatMessage.ROLE_ASSISTANT,
                    text = parsed.displayText,
                    toolLog = emptyList()
                )

                if (parsed.commands.isEmpty()) break

                onProgress("第 $round 轮：执行 ${parsed.commands.size} 条指令")
                val logs = executeCommands(parsed.commands, dispatcher, whitelist, task.allowDangerous)

                // 把工具日志挂到最后一条助手消息上，界面里能看见
                val lastIndex = messages.lastIndex
                messages[lastIndex] = messages[lastIndex].copy(toolLog = logs)

                messages += StoredMessage(
                    role = ChatMessage.ROLE_USER,
                    text = "【工具执行结果】\n" + logs.joinToString("\n"),
                    isInternal = true
                )
            }

            persist(cid, task, messages)

            val summary = lastAssistantText.ifBlank { "执行完成，但没有产生文本回复" }
                .replace(Regex("\\s+"), " ")
                .take(SUMMARY_CHARS)

            return Outcome(true, summary, cid, messages)

        } catch (e: CancellationException) {
            // 被取消（用户停用任务 / 系统回收）也要留存已产生的记录
            runCatching { persist(cid, task, messages) }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "任务 ${task.id} 执行失败", e)
            runCatching { persist(cid, task, messages) }
            return Outcome(
                success = false,
                summary = "执行出错：${e.message ?: e.javaClass.simpleName}",
                conversationId = cid,
                messages = messages
            )
        }
    }

    // ------------------------------------------------------------ 指令执行

    /**
     * 逐条执行指令，返回给模型看的日志。
     *
     * 拦截顺序很重要：**白名单在前，危险操作在后**。
     * 反过来的话，一个只允许读的技能声明了 8 条指令，其中一条是 delete，
     * 就会先弹「是否需要批准」再被白名单拒绝 —— 逻辑绕且日志混乱。
     */
    private suspend fun executeCommands(
        commands: List<CommandParser.Command>,
        dispatcher: CommandDispatcher,
        whitelist: Set<String>?,
        allowDangerous: Boolean
    ): List<String> {
        val logs = mutableListOf<String>()

        commands.forEach { cmd ->
            val actionName = CommandParser.actionName(cmd.action)

            // 1) 技能白名单
            if (whitelist != null && actionName !in whitelist) {
                logs += "指令 `$actionName` 被技能白名单拒绝（当前仅允许：${whitelist.joinToString("、")}），已跳过"
                return@forEach
            }

            // 2) 危险操作
            if (CommandDispatcher.needsApproval(cmd) && !allowDangerous) {
                val desc = CommandDispatcher.describeDanger(cmd)
                logs += "危险操作（$desc）未在本任务中开启，已拒绝执行。" +
                        "如需允许，请编辑任务并打开「允许危险操作」"
                return@forEach
            }

            // 3) 真正执行
            val result = try {
                runCatching { dispatcher.dispatch(cmd) }
                    .getOrElse { e -> "执行失败（${cmd.path}）：${e.message}" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "执行出错（${cmd.path}）：${e.message}"
            }
            logs += result
        }

        return logs
    }

    // -------------------------------------------------------------- API 调用

    /**
     * 把存储消息转成 API 上下文。
     *
     * 与前台对话不同，这里**不发图片**：定时任务的提示词是纯文本，
     * 历史里的附件降级成占位说明即可，没必要把 Base64 一直带着上传。
     */
    private fun buildApiMessages(systemPrompt: String, messages: List<StoredMessage>): List<ChatMessage> {
        val list = mutableListOf<ChatMessage>()
        if (systemPrompt.isNotBlank()) {
            list += ChatMessage(ChatMessage.ROLE_SYSTEM, systemPrompt)
        }
        messages.forEach { msg ->
            if (msg.role != ChatMessage.ROLE_USER && msg.role != ChatMessage.ROLE_ASSISTANT) {
                return@forEach
            }
            if (msg.text.isBlank() && msg.attachments.isEmpty()) return@forEach
            list += ChatMessage(msg.role, msg.toPlainText())
        }
        return list
    }

    /** 附件降级成文字占位，不重发字节。 */
    private fun StoredMessage.toPlainText(): String {
        if (attachments.isEmpty()) return text
        val notes = attachments.joinToString("\n") { a ->
            val label = if (a.isImage) "图片" else "文件"
            "[$label ${a.displayName}]"
        }
        return if (text.isBlank()) notes else "$text\n\n$notes"
    }

    /** 跑一次流式调用并收集完整文本。失败返回 null。 */
    private suspend fun streamOnce(messages: List<ChatMessage>): String? {
        val buffer = StringBuilder()
        var failed = false

        llm.stream(
            baseUrl = credentials.baseUrl,
            apiKey = credentials.apiKey,
            model = credentials.model,
            messages = messages
        ).collect { event ->
            when (event) {
                is StreamEvent.Delta -> buffer.append(event.text)
                is StreamEvent.Failed -> {
                    failed = true
                    Log.w(TAG, "流式调用失败：${event.message}")
                }
                StreamEvent.Done -> Unit
            }
        }

        if (failed && buffer.isEmpty()) return null
        return buffer.toString()
    }

    // ------------------------------------------------------------------ 落盘

    private suspend fun persist(cid: String, task: AutomationTask, messages: List<StoredMessage>) {
        conversations.save(
            StoredConversation(
                id = cid,
                title = task.title,
                updatedAt = System.currentTimeMillis(),
                messages = messages
            )
        )
    }

    private companion object {
        const val TAG = "HeadlessTurnRunner"

        /**
         * 单次执行最多迭代几轮。与前台一致取 8。
         *
         * 上限同时也是「失控保护」：模型如果陷入「执行→失败→重试」的循环，
         * 8 轮就会停下来，不会无限跑下去烧掉用户的 token。
         */
        const val MAX_ROUNDS = 8

        /** 带进上下文的历史消息上限。 */
        const val MAX_HISTORY = 30

        const val SUMMARY_CHARS = 120
    }
}

/** 供调用方生成会话 id。 */
internal fun newConversationId(): String =
    UUID.randomUUID().toString().replace("-", "").take(16)
