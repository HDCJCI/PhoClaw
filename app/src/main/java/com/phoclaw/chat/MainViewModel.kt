package com.phoclaw.chat

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.phoclaw.chat.auto.AlarmScheduler
import com.phoclaw.chat.auto.AutomationService
import com.phoclaw.chat.auto.ExactAlarmPermission
import com.phoclaw.chat.data.Attachment
import com.phoclaw.chat.data.AttachmentRepository
import com.phoclaw.chat.data.AutomationStore
import com.phoclaw.chat.data.AutomationTask
import com.phoclaw.chat.data.ChatMessage
import com.phoclaw.chat.data.CommandDispatcher
import com.phoclaw.chat.data.ContentPart
import com.phoclaw.chat.data.ConversationMeta
import com.phoclaw.chat.data.ConversationStore
import com.phoclaw.chat.data.CredentialStore
import com.phoclaw.chat.data.LlmClient
import com.phoclaw.chat.data.PendingAttachment
import com.phoclaw.chat.data.PromptComposer
import com.phoclaw.chat.data.Skill
import com.phoclaw.chat.data.SkillParser
import com.phoclaw.chat.data.SkillStore
import com.phoclaw.chat.data.StreamEvent
import com.phoclaw.chat.data.StoredConversation
import com.phoclaw.chat.data.StoredMessage
import com.phoclaw.chat.data.TavilyClient
import com.phoclaw.chat.data.WorkspaceRepository
import com.phoclaw.chat.util.CommandParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import android.util.Base64

/** 界面上一条可见的消息（含工具执行日志）。 */
data class UiMessage(
    val role: String,
    val text: String,
    val isError: Boolean = false,
    val toolLog: List<String> = emptyList(),
    /** 仅用于回灌给模型的中间消息，不在界面上渲染。 */
    val isInternal: Boolean = false,
    /** 该消息正在流式生成中。此时按纯文本渲染，避免未闭合的 Markdown 语法抖动。 */
    val isStreaming: Boolean = false,
    /** 本条消息携带的附件。历史消息里也有值，用于渲染缩略图与生成占位文本。 */
    val attachments: List<Attachment> = emptyList()
) {
    /**
     * 是否有任何可渲染内容。
     *
     * 气泡渲染、自动滚底、落盘过滤统一用它 —— 光看 [text] 会让
     * 「只发了图片没打字」的消息整个消失。
     */
    val hasContent: Boolean get() = text.isNotBlank() || attachments.isNotEmpty()
}

/** 待用户确认的危险操作（目前仅删除）。 */
data class PendingApproval(
    val action: String,
    val path: String,
    val description: String
)

/** 新建会话的默认标题，也是「标题还没根据首条消息生成」的标记。 */
const val DEFAULT_TITLE = "新对话"

data class ChatUiState(
    val messages: List<UiMessage> = emptyList(),
    val input: String = "",
    val isStreaming: Boolean = false,
    val workspaceName: String? = null,
    val workspaceReady: Boolean = false,
    val banner: String? = null,
    /** 非空时界面弹出危险操作确认框。 */
    val pendingApproval: PendingApproval? = null,
    /** 当前会话的标题，显示在顶栏。 */
    val conversationTitle: String = DEFAULT_TITLE,
    /** 输入框上方待发送的附件暂存区。 */
    val pendingAttachments: List<PendingAttachment> = emptyList()
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val credentials = CredentialStore(app)
    private val workspace = WorkspaceRepository(app)
    private val llm = LlmClient()
    private val conversations = ConversationStore(app)
    private val attachments = AttachmentRepository(app)
    private val skills = SkillStore(app)
    private val automations = AutomationStore(app)

    /** 指令执行器，与后台定时任务共用同一份实现。 */
    private val tavily = TavilyClient()

    /**
     * 联网搜索客户端与 Key。
     *
     * 每次调度时**实时**构造，而不是在字段初始化时固定 ——
     * 用户可能在设置里改了 Key，缓存住的话要重启应用才生效。
     */
    private val dispatcher: CommandDispatcher
        get() = CommandDispatcher(workspace, tavily, credentials.tavilyKey)

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val _history = MutableStateFlow<List<ConversationMeta>>(emptyList())

    /** 历史会话列表，供历史页收集。 */
    val history: StateFlow<List<ConversationMeta>> = _history.asStateFlow()

    private val _skills = MutableStateFlow<List<Skill>>(emptyList())

    /** 技能列表，供技能页收集。 */
    val skillList: StateFlow<List<Skill>> = _skills.asStateFlow()

    private val _automationTasks = MutableStateFlow<List<AutomationTask>>(emptyList())

    /** 自动化任务列表。 */
    val automationTasks: StateFlow<List<AutomationTask>> = _automationTasks.asStateFlow()

    /**
     * 当前生效的技能白名单。`null` 表示不限制。
     *
     * 缓存起来是因为 [buildApiMessages] 与 [executeCommands] 每轮都要用，
     * 而技能列表本身很少变（只在导入 / 开关时刷新）。
     */
    @Volatile
    private var activeWhitelist: Set<String>? = null

    /**
     * 精确闹钟是否已授权。
     *
     * 界面据此显示「精确 / 非精确」角标。任务本身不受影响 ——
     * 没授权时 [AlarmScheduler] 会自动降级，而不是让功能不可用。
     */
    private val _exactAlarmGranted = MutableStateFlow(true)
    val exactAlarmGranted: StateFlow<Boolean> = _exactAlarmGranted.asStateFlow()

    /** 供设置页读写。 */
    val credentialStore: CredentialStore get() = credentials
    val workspaceRepo: WorkspaceRepository get() = workspace

    private var streamJob: Job? = null

    /** 当前会话 id。新建会话时换一个，切换历史时沿用旧的那个。 */
    private var conversationId: String = newId()

    /** 当前会话是否已有内容落盘（用来区分「空会话」和「已存在的会话」）。 */
    private var conversationExists = false

    /** 等待落盘的防抖任务，避免每收到一个流式增量就写一次磁盘。 */
    private var persistJob: Job? = null

    /**
     * 本轮图片的 Base64 缓存：`localName -> 裸 base64`。
     *
     * [runTurn] 最多迭代 8 轮，每轮都会重新构造完整上下文。如果不缓存，
     * 一张 4MB 的图会被读 8 次盘、编码 8 次。在循环外算一次即可跨轮复用。
     *
     * 注意：这只省掉本地 CPU 开销。网络上传仍然会传 8 次 ——
     * 协议要求每轮都带完整上下文，绕不开。
     */
    private var turnImageCache: Map<String, String> = emptyMap()

    /** 当前等待用户确认的危险操作。用户点「允许 / 拒绝」时完成它。 */
    private var approvalDeferred: CompletableDeferred<Boolean>? = null

    /** 界面点「允许」后调用。 */
    fun approvePendingAction() {
        approvalDeferred?.complete(true)
        approvalDeferred = null
        _state.update { it.copy(pendingApproval = null) }
    }

    /** 界面点「拒绝」后调用。 */
    fun rejectPendingAction() {
        approvalDeferred?.complete(false)
        approvalDeferred = null
        _state.update { it.copy(pendingApproval = null) }
    }

    /**
     * 请求用户确认危险操作；挂起直到用户做出选择。
     * 返回 true 表示允许执行。
     */
    private suspend fun requestApproval(action: String, path: String, description: String): Boolean {
        approvalDeferred?.complete(false)   // 清理可能残留的上一次请求
        val deferred = CompletableDeferred<Boolean>()
        approvalDeferred = deferred
        _state.update {
            it.copy(pendingApproval = PendingApproval(action, path, description))
        }
        return try {
            deferred.await()
        } finally {
            approvalDeferred = null
            _state.update { it.copy(pendingApproval = null) }
        }
    }

    init {
        restoreWorkspace()
        restoreLastConversation()
        // 冷启动兜底：清掉崩溃残留的孤儿附件。
        // 只在启动时跑一次 —— 这是 O(会话数) 的磁盘扫描，不适合高频调用。
        collectAttachmentGarbage()
        // 技能与自动化任务在启动时载入一次，供沙盒外的后台逻辑参考
        viewModelScope.launch {
            refreshSkills()
            refreshAutomations()
            refreshExactAlarmState()
        }
    }

    /** 进程重启后恢复上次授权的工作区。 */
    private fun restoreWorkspace() {
        val saved = credentials.workspaceUri
        if (saved.isBlank()) return
        runCatching {
            val uri = Uri.parse(saved)
            // 校验权限是否仍然有效
            val hasPermission = getApplication<Application>().contentResolver
                .persistedUriPermissions
                .any { it.uri == uri && it.isReadPermission && it.isWritePermission }
            if (hasPermission) {
                workspace.setTreeUri(uri)
                refreshWorkspaceState()
            }
        }
    }

    // ---------------------------------------------------------------- 工作区

    /**
     * 处理目录选择结果。必须立刻申请持久化权限，否则进程重启后 URI 失效。
     */
    fun onWorkspacePicked(uri: Uri, alreadyPersisted: Boolean = false) {
        viewModelScope.launch {
            // 持久化授权：部分 ROM 会重复授权而抛异常，失败不阻断本次使用
            val persisted = runCatching {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                getApplication<Application>().contentResolver
                    .takePersistableUriPermission(uri, flags)
                true
            }.getOrElse { false }

            workspace.setTreeUri(uri)
            if (persisted) credentials.workspaceUri = uri.toString()
            refreshWorkspaceState()

            val tip = if (persisted) "工作区已就绪：${workspace.displayName}"
            else "工作区已就绪：${workspace.displayName}（本次有效，重启后需重新选择）"
            _state.update { it.copy(banner = tip) }
        }
    }

    fun refreshWorkspaceState() {
        _state.update {
            it.copy(
                workspaceReady = workspace.isReady,
                workspaceName = workspace.displayName
            )
        }
    }

    /** 供工作区浏览器调用。 */
    suspend fun listDirectory(path: String) = workspace.list(path)

    suspend fun readFile(path: String) = workspace.readText(path)

    /** 设置页的「测试连接」。 */
    suspend fun testConnection(): Result<String> =
        llm.ping(credentials.baseUrl, credentials.apiKey, credentials.model)

    /** 设置页的「测试搜索」。 */
    suspend fun testSearch(): Result<String> = tavily.ping(credentials.tavilyKey)

    // ---------------------------------------------------------------- 聊天

    fun onInputChange(value: String) = _state.update { it.copy(input = value) }

    fun clearBanner() = _state.update { it.copy(banner = null) }

    fun stopStreaming() {
        streamJob?.cancel()
        streamJob = null
        clearStreamingFlags()
        _state.update { it.copy(isStreaming = false) }
        persistCurrentConversation()
    }

    /** 开一个新会话。当前会话若已有内容，会在开新会话前先落盘。 */
    fun newSession() {
        streamJob?.cancel()
        persistCurrentConversation(immediate = true)
        conversationId = newId()
        conversationExists = false
        _state.update {
            it.copy(
                messages = emptyList(),
                isStreaming = false,
                conversationTitle = DEFAULT_TITLE,
                // 待发送附件属于「上一个会话的输入」，切会话时一并清掉
                pendingAttachments = emptyList()
            )
        }
    }

    // ---------------------------------------------------------------- 会话存储

    /**
     * 冷启动时恢复上次的对话。
     *
     * 只恢复最近一个会话，不把历史全读进内存——历史页需要时再按需加载。
     */
    private fun restoreLastConversation() {
        viewModelScope.launch {
            refreshHistory()
            val last = _history.value.firstOrNull() ?: return@launch
            val stored = conversations.load(last.id) ?: return@launch
            conversationId = stored.id
            conversationExists = true
            _state.update {
                it.copy(
                    messages = stored.messages.map { m -> m.toUiMessage() },
                    conversationTitle = stored.title
                )
            }
        }
    }

    /** 重新读取历史索引。 */
    suspend fun refreshHistory() {
        _history.value = runCatching { conversations.listConversations() }.getOrDefault(emptyList())
    }

    /** 打开一个历史会话。 */
    fun openConversation(id: String) {
        if (id == conversationId && _state.value.messages.isNotEmpty()) return
        streamJob?.cancel()
        viewModelScope.launch {
            val stored = conversations.load(id)
            if (stored == null) {
                _state.update { it.copy(banner = "会话已不存在") }
                refreshHistory()
                return@launch
            }
            conversationId = stored.id
            conversationExists = true
            _state.update {
                it.copy(
                    messages = stored.messages.map { m -> m.toUiMessage() },
                    isStreaming = false,
                    conversationTitle = stored.title,
                    // 换会话时清掉上一个会话残留的待发送附件
                    pendingAttachments = emptyList()
                )
            }
        }
    }

    /** 删除一个历史会话。若删的是当前会话，界面顺带清空。 */
    fun deleteConversation(id: String) {
        viewModelScope.launch {
            // 先取出该会话引用的附件清单，删完 JSON 后再删文件
            conversations.load(id)?.let { attachments.deleteForConversation(it.messages) }
            conversations.delete(id)
            if (id == conversationId) {
                streamJob?.cancel()
                conversationId = newId()
                conversationExists = false
                _state.update {
                    it.copy(
                        messages = emptyList(),
                        isStreaming = false,
                        conversationTitle = DEFAULT_TITLE,
                        pendingAttachments = emptyList()
                    )
                }
            }
            refreshHistory()
            _state.update { it.copy(banner = "已删除该会话") }
        }
    }

    /** 清空全部历史。 */
    fun clearAllConversations() {
        viewModelScope.launch {
            streamJob?.cancel()
            conversations.clearAll()
            attachments.clearAll()
            conversationId = newId()
            conversationExists = false
            _state.update {
                it.copy(
                    messages = emptyList(),
                    isStreaming = false,
                    conversationTitle = DEFAULT_TITLE,
                    pendingAttachments = emptyList(),
                    banner = "历史记录已清空"
                )
            }
            refreshHistory()
        }
    }

    /**
     * 把当前对话写盘。
     *
     * 流式期间调用会做 [PERSIST_DEBOUNCE_MS] 防抖：逐字增量每几十毫秒就来一次，
     * 每次都序列化整个会话会造成大量无谓 IO，等安静下来再写。
     */
    private fun persistCurrentConversation(immediate: Boolean = false) {
        val messages = _state.value.messages
            .filterNot { it.isStreaming }   // 半截内容不落盘
            // 纯附件消息也要存 —— 只看 text 会把「只发了张图」的消息整个丢掉
            .filter { it.hasContent || it.toolLog.isNotEmpty() }
            .map { it.toStoredMessage() }
        if (messages.isEmpty()) return

        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            if (!immediate) delay(PERSIST_DEBOUNCE_MS)
            val title = _state.value.conversationTitle
                .takeIf { it != DEFAULT_TITLE }
                ?: ConversationStore.buildTitle(titleSource(messages))
            runCatching {
                conversations.save(
                    StoredConversation(
                        id = conversationId,
                        title = title,
                        updatedAt = System.currentTimeMillis(),
                        messages = messages
                    )
                )
            }.onSuccess {
                conversationExists = true
                if (title != _state.value.conversationTitle) {
                    _state.update { it.copy(conversationTitle = title) }
                }
                refreshHistory()
            }
        }
    }

    /** 界面主动触发的保存（例如 Activity 进入后台）。 */
    fun flushConversation() = persistCurrentConversation(immediate = true)

    /**
     * 生成标题的素材：首条用户消息的正文。
     *
     * 纯图片消息没有正文，这时退回附件名 —— 否则标题会一直是「新对话」，
     * 在历史列表里几条这样的会话完全分不清。
     */
    private fun titleSource(messages: List<StoredMessage>): String {
        val first = messages.firstOrNull {
            it.role == ChatMessage.ROLE_USER && !it.isInternal
        } ?: return ""
        return first.text.takeIf { it.isNotBlank() }
            ?: first.attachments.firstOrNull()?.displayName.orEmpty()
    }

    // ---------------------------------------------------------------- 附件

    /**
     * 接收选择器返回的 URI，读取并放入待发送暂存区。
     *
     * 失败（超大、二进制、读不出来）只弹提示，不影响其它附件。
     */
    fun addPendingAttachment(uri: Uri) {
        viewModelScope.launch {
            attachments.loadFromUri(uri)
                .onSuccess { item ->
                    _state.update { s ->
                        val list = s.pendingAttachments + item
                        if (list.size > AttachmentRepository.MAX_PENDING) {
                            s.copy(banner = "一次最多发送 ${AttachmentRepository.MAX_PENDING} 个附件")
                        } else {
                            // note 是压缩提示，没有就静默添加
                            s.copy(pendingAttachments = list, banner = item.note)
                        }
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(banner = "添加附件失败：${e.message}") }
                }
        }
    }

    fun removePendingAttachment(key: String) = _state.update {
        it.copy(pendingAttachments = it.pendingAttachments.filterNot { p -> p.key == key })
    }

    /** 附件的实际文件位置，供界面解码缩略图。 */
    fun attachmentFile(localName: String): File = attachments.fileFor(localName)

    /** 清理无人引用的附件文件（冷启动与退到后台时调用）。 */
    fun collectAttachmentGarbage() {
        viewModelScope.launch {
            attachments.collectGarbage(conversationsDir())
        }
    }

    private fun conversationsDir(): File =
        File(getApplication<Application>().filesDir, "conversations")

    // ---------------------------------------------------------------- 聊天

    fun send() {
        val text = _state.value.input.trim()
        val pending = _state.value.pendingAttachments
        // 放宽：只有图片、没打字也应该能发出去
        if ((text.isEmpty() && pending.isEmpty()) || _state.value.isStreaming) return
        if (!credentials.isConfigured) {
            _state.update { it.copy(banner = "请先在设置里填入 API Key") }
            return
        }

        streamJob = viewModelScope.launch {
            // 先落盘再入队消息，保证消息引用的文件一定存在
            val saved = pending.mapNotNull { attachments.persist(it).getOrNull() }
            if (pending.isNotEmpty() && saved.isEmpty()) {
                _state.update { it.copy(banner = "附件保存失败，请重试") }
                return@launch
            }
            if (saved.size < pending.size) {
                _state.update {
                    it.copy(banner = "有 ${pending.size - saved.size} 个附件保存失败，已跳过")
                }
            }

            _state.update {
                it.copy(
                    messages = it.messages + UiMessage(
                        ChatMessage.ROLE_USER, text, attachments = saved
                    ),
                    input = "",
                    pendingAttachments = emptyList(),
                    isStreaming = true
                )
            }

            attachments.trimIfNeeded()
            runTurn()
        }
    }

    /**
     * 跑一轮对话：流式接收 → 解析指令 → 执行 → 把结果回灌给模型，最多迭代 [MAX_ROUNDS] 次。
     */
    private suspend fun runTurn() {
        try {
            // 附件准备必须在循环外做且只做一次：
            // 图片 Base64 跨 8 轮复用，文本内容作为常驻消息注入
            val anchor = currentTurnAnchorIndex()
            prepareTurnImages()
            if (anchor >= 0) injectTextAttachments(anchor)

            var round = 0
            while (round < MAX_ROUNDS) {
                round++
                // 流式过程中已经就地创建并更新了助手气泡，这里拿到它的下标，避免重复追加
                val result = streamingCompletion() ?: return finishStreaming()
                val parsed = CommandParser.parse(result.text)

                // 用去掉指令块后的正文覆盖流式原文（界面上只显示摘要 + 工具日志）
                val assistantIndex = finalizeAssistant(result.messageIndex, parsed.displayText)

                if (parsed.commands.isEmpty()) return finishStreaming()

                val logs = executeCommands(parsed.commands)
                attachToolLog(assistantIndex, logs)

                // 把执行结果回灌，让模型继续下一步
                _state.update {
                    it.copy(
                        messages = it.messages + UiMessage(
                            ChatMessage.ROLE_USER,
                            "【工具执行结果】\n" + logs.joinToString("\n"),
                            isInternal = true
                        )
                    )
                }
            }
            finishStreaming()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 用户主动停止，保留已生成内容
            finishStreaming()
            throw e
        } catch (e: Exception) {
            clearStreamingFlags()
            _state.update {
                it.copy(
                    messages = it.messages + UiMessage("", "发生异常：${e.message}", isError = true),
                    isStreaming = false
                )
            }
            streamJob = null
            // 报错也要存：用户可能已经问了一半，重开应用还得看得见
            persistCurrentConversation(immediate = true)
        }
    }

    /** 流式结果：完整文本 + 界面上对应助手气泡的下标（-1 表示未创建）。 */
    private data class StreamResult(val text: String, val messageIndex: Int)

    /**
     * 执行一次流式调用。
     *
     * 首次收到增量时就地创建助手气泡，后续增量都更新**同一条**消息，
     * 因此返回的下标可直接复用，不需要再追加新消息（否则回复会重复显示）。
     */
    private suspend fun streamingCompletion(): StreamResult? {
        val buffer = StringBuilder()
        var messageIndex = -1
        var failed = false

        llm.stream(
            baseUrl = credentials.baseUrl,
            apiKey = credentials.apiKey,
            model = credentials.model,
            messages = buildApiMessages()
        ).collect { event ->
            when (event) {
                is StreamEvent.Delta -> {
                    buffer.append(event.text)
                    if (messageIndex < 0) {
                        _state.update {
                            it.copy(messages = it.messages +
                                    UiMessage(ChatMessage.ROLE_ASSISTANT, "", isStreaming = true))
                        }
                        messageIndex = _state.value.messages.lastIndex
                    }
                    val idx = messageIndex
                    val text = buffer.toString()
                    _state.update { s ->
                        if (idx > s.messages.lastIndex) s
                        else s.copy(
                            messages = s.messages.toMutableList().also {
                                it[idx] = it[idx].copy(text = text, isStreaming = true)
                            }
                        )
                    }
                }
                is StreamEvent.Failed -> {
                    failed = true
                    _state.update {
                        it.copy(messages = it.messages + UiMessage("", event.message, isError = true))
                    }
                }
                StreamEvent.Done -> Unit
            }
        }
        if (failed) return null
        val text = buffer.toString()
        if (text.isBlank()) return null
        return StreamResult(text, messageIndex)
    }

    /**
     * 把流式原文替换成展示文本（已剥离指令块）。
     * 复用流式期间创建的助手气泡；若模型只输出了指令块、没有正文，则把该气泡标记为内部消息。
     */
    private fun finalizeAssistant(index: Int, displayText: String): Int {
        if (index < 0 || index > _state.value.messages.lastIndex) {
            // 兜底：流式期间未创建气泡（理论上不会发生）
            _state.update {
                it.copy(messages = it.messages + UiMessage(
                    ChatMessage.ROLE_ASSISTANT, displayText, isInternal = displayText.isBlank()
                ))
            }
            return _state.value.messages.lastIndex
        }
        _state.update { s ->
            s.copy(messages = s.messages.toMutableList().also {
                it[index] = it[index].copy(
                    text = displayText,
                    // 没有正文时隐藏气泡，但工具日志通过 toolLog 挂载后仍会展示
                    isInternal = displayText.isBlank(),
                    // 清除流式标记，交给 Markdown 渲染
                    isStreaming = false
                )
            })
        }
        return index
    }

    /** 出错或中断时，清除所有残留的流式标记。 */
    private fun clearStreamingFlags() {
        _state.update { s ->
            if (s.messages.none { it.isStreaming }) s
            else s.copy(
                messages = s.messages.map { if (it.isStreaming) it.copy(isStreaming = false) else it }
            )
        }
    }

    // ---------------------------------------------------------------- 附件上下文

    /**
     * 当前轮的锚点消息下标：最后一条「用户真实发的」消息。
     *
     * 必须排除 `isInternal` —— [runTurn] 每轮都会追加一条 internal 的 user 消息
     * （工具执行结果回灌），它们不是用户发言。
     *
     * 用**下标**而不是消息对象：8 轮循环里只做 append、不做 insert，
     * 已有元素的下标不会变，所以首轮算出的下标全程有效。
     */
    private fun currentTurnAnchorIndex(): Int = _state.value.messages.indexOfLast {
        it.role == ChatMessage.ROLE_USER && !it.isInternal
    }

    /**
     * 把当前轮的图片转成 Base64 缓存起来。
     *
     * 只处理锚点那条消息的图片 —— 历史消息里的图**不重发**。
     * 否则每多聊一轮，上下文里就多叠一整套 Base64，很快就会撑爆请求体。
     */
    private suspend fun prepareTurnImages() {
        val anchor = currentTurnAnchorIndex()
        val images = _state.value.messages.getOrNull(anchor)
            ?.attachments?.filter { it.isImage }.orEmpty()

        // 开关关闭或没有图片时清空缓存，让发送逻辑走纯文本通道
        if (images.isEmpty() || !credentials.modelSupportsVision) {
            turnImageCache = emptyMap()
            return
        }

        turnImageCache = withContext(Dispatchers.IO) {
            images.mapNotNull { a ->
                attachments.readBytes(a.localName)?.let {
                    a.localName to Base64.encodeToString(it, Base64.NO_WRAP)
                }
            }.toMap()
        }
    }

    /**
     * 把文本附件的内容作为一条常驻的内部消息注入上下文。
     *
     * 与图片不同，文本内容**留在 messages 里**，后续每一轮都会被带上 ——
     * 用户希望 AI 在多轮里反复引用这份代码，而不是只看一眼就忘。
     *
     * 注入的位置是锚点消息之后，这样对话顺序读起来是
     * 「用户发言（带附件） → 附件内容」。
     */
    private suspend fun injectTextAttachments(anchor: Int) {
        val files = _state.value.messages.getOrNull(anchor)
            ?.attachments?.filter { !it.isImage }.orEmpty()
        if (files.isEmpty()) return

        // 先把字节都读出来。readBytes 是挂起函数，不能放在 joinToString 的
        // lambda 里调用（那里不是协程上下文）。
        val bodies = withContext(Dispatchers.IO) {
            files.map { it to attachments.readBytes(it.localName) }
        }

        var budget = MAX_INLINE_TEXT_TOTAL
        val body = bodies.joinToString("\n\n") { (a, raw) ->
            if (raw == null) {
                return@joinToString "===== 附件 ${a.displayName} =====\n（文件已不可读）"
            }

            var text = raw.toString(Charsets.UTF_8)
            var truncated = false
            if (raw.size > budget) {
                // 按预算截断。优先在行末断开，读起来更自然
                val cut = text.take(budget.toInt().coerceAtLeast(0))
                text = cut.substringBeforeLast('\n', cut)
                truncated = true
            }
            budget -= raw.size

            buildString {
                append("===== 附件 ${a.displayName} =====\n")
                append(text)
                if (truncated) {
                    // 必须明确告知被截断，否则模型会以为自己看到了全文
                    append("\n\n（已截断，仅包含前 ${humanBytes(text.toByteArray().size.toLong())}，")
                    append("完整文件共 ${humanBytes(raw.size.toLong())}）")
                }
                append("\n===== 附件结束 =====")
            }
        }

        _state.update { s ->
            val list = s.messages.toMutableList()
            if (anchor in list.indices) {
                list.add(anchor + 1, UiMessage(
                    ChatMessage.ROLE_USER,
                    "【附件内容】\n$body",
                    isInternal = true
                ))
            }
            s.copy(messages = list)
        }
    }

    /** 历史消息的文本：附件降级成占位说明，不重发任何字节。 */
    private fun UiMessage.toHistoryText(): String {
        if (attachments.isEmpty()) return text
        val notes = attachments.joinToString("\n") { a ->
            val label = if (a.isImage) "图片" else "文件"
            "[$label ${a.displayName}]"
        }
        return if (text.isBlank()) notes else "$text\n\n$notes"
    }

    /** 开关关闭时，用文字把图片信息告诉模型，让它至少知道有这回事。 */
    private fun imageNoteText(): String =
        "[附件] 用户发了一张图片，但当前模型不支持看图，无法查看内容。" +
                "如果这影响回答，请提示用户切换支持视觉的模型。"

    /**
     * 把界面消息转换成发给 API 的上下文。
     *
     * 三种形态：
     * - 当前轮 + 图片 + 开关开 → `parts` 非空，序列化成多模态数组
     * - 当前轮 + 图片 + 开关关 → 纯字符串，附一句文字说明
     * - 其余（含历史消息）→ 纯字符串，图片变成 `[图片 xxx.png]` 占位
     */
    private fun buildApiMessages(): List<ChatMessage> {
        val list = mutableListOf<ChatMessage>()

        // 基础提示词与已激活技能合成。没有激活技能时原样返回基础提示词，
        // 保证既有对话行为一字不变
        val system = PromptComposer.compose(credentials.systemPrompt, _skills.value.filter { it.enabled })
        if (system.isNotBlank()) {
            list += ChatMessage(ChatMessage.ROLE_SYSTEM, system)
        }

        val anchor = currentTurnAnchorIndex()

        _state.value.messages.forEachIndexed { index, msg ->
            // 与原先一致：只要 user 和 assistant。
            // 注意 internal 消息的 role 也是 user，它们是刻意发给模型的
            //（工具结果回灌、附件内容注入），不在这里过滤。
            if (msg.role != ChatMessage.ROLE_USER && msg.role != ChatMessage.ROLE_ASSISTANT) {
                return@forEachIndexed
            }
            if (msg.text.isBlank() && msg.attachments.isEmpty()) return@forEachIndexed

            val isCurrentTurn = index == anchor
            if (!isCurrentTurn) {
                // 历史消息：图片永远只留占位
                list += ChatMessage(msg.role, msg.toHistoryText())
                return@forEachIndexed
            }

            // 当前轮：尝试走多模态
            val images = msg.attachments.filter { it.isImage }
                .mapNotNull { a -> turnImageCache[a.localName]?.let { a to it } }

            if (images.isEmpty()) {
                // 没有可发图片（开关关闭、或图片读取失败）→ 纯文本
                val note = if (msg.attachments.any { it.isImage }) {
                    if (msg.text.isBlank()) imageNoteText() else "${msg.text}\n\n${imageNoteText()}"
                } else msg.text
                list += ChatMessage(msg.role, note)
                return@forEachIndexed
            }

            val parts = mutableListOf<ContentPart>()
            parts += ContentPart.Text(msg.text.ifBlank { "（见下图）" })
            images.forEach { (_, b64) -> parts += ContentPart.Image(mime = "image/jpeg", base64 = b64) }
            list += ChatMessage(msg.role, msg.text, parts = parts)
        }
        return list
    }

    // ---------------------------------------------------------------- 指令执行

    private suspend fun executeCommands(commands: List<CommandParser.Command>): List<String> {
        val logs = mutableListOf<String>()
        val whitelist = activeWhitelist
        commands.forEach { cmd ->
            val result = try {
                // 技能白名单优先于危险操作确认。
                // 顺序反过来的话，一条被白名单禁掉的 delete 会先弹确认框、
                // 用户点了「允许」再被告知「被技能拒绝」，体验很荒谬
                val actionName = CommandParser.actionName(cmd.action)
                if (whitelist != null && actionName !in whitelist) {
                    logs += "指令 `$actionName` 被技能白名单拒绝" +
                            "（当前仅允许：${whitelist.joinToString("、")}），已跳过"
                    return@forEach
                }

                // 危险操作先征得用户同意
                if (needsApproval(cmd)) {
                    val desc = describeDanger(cmd)
                    val allowed = requestApproval(cmd.action.name.lowercase(), cmd.path, desc)
                    if (!allowed) {
                        logs += "用户拒绝了该操作，已跳过（${desc}）"
                        return@forEach
                    }
                }
                runCatching { dispatch(cmd) }
                    .getOrElse { e -> "执行失败（${cmd.path}）：${e.message}" }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                "执行出错（${cmd.path}）：${e.message}"
            }
            logs += result
        }
        return logs
    }

    /**
     * 删除与目录移动属于不可逆操作，需要用户确认。
     *
     * 判定逻辑放在 [CommandDispatcher] 里而不是这里 ——
     * 后台定时任务用同一份判断。两处各写一份的话，迟早会出现
     * 「前台要确认、后台直接执行」这种危险的不一致。
     */
    private fun needsApproval(cmd: CommandParser.Command) = CommandDispatcher.needsApproval(cmd)

    private fun describeDanger(cmd: CommandParser.Command) = CommandDispatcher.describeDanger(cmd)

    /** 真正执行单条指令。实现在 [CommandDispatcher] 里，与后台任务共用。 */
    private suspend fun dispatch(cmd: CommandParser.Command): String = dispatcher.dispatch(cmd)

    private fun attachToolLog(index: Int, logs: List<String>) {
        if (index < 0) return
        _state.update { s ->
            s.copy(messages = s.messages.toMutableList().also {
                if (index <= it.lastIndex) it[index] = it[index].copy(toolLog = logs)
            })
        }
    }

    private fun finishStreaming() {
        // 所有结束路径（正常、出错、用户停止）都要清掉流式标记，
        // 否则那一条气泡会一直以纯文本形式显示，不会渲染 Markdown
        clearStreamingFlags()
        _state.update { it.copy(isStreaming = false) }
        streamJob = null
        // 一轮结束是天然的落盘时机：此时消息完整，不会存下半截回复
        persistCurrentConversation(immediate = true)
    }

    // ---------------------------------------------------------------- 技能

    /** 重新读取技能列表，并刷新生效的白名单。 */
    suspend fun refreshSkills() {
        val list = runCatching { skills.list(forceReload = true) }.getOrDefault(emptyList())
        _skills.value = list
        activeWhitelist = PromptComposer.effectiveWhitelist(list.filter { it.enabled })
    }

    /**
     * 导入技能文件。
     *
     * [files] 是 `文件名 to 内容` 的列表 —— 读取由界面层完成（那边才有 ContentResolver），
     * 解析与落盘在这里做。逐个文件独立处理：一个坏文件不该让整批导入失败，
     * 失败的会逐条列出原因，用户能照着改。
     */
    fun importSkills(files: List<Triple<String, String, Long>>) {
        viewModelScope.launch {
            val imported = mutableListOf<Skill>()
            val errors = mutableListOf<String>()

            files.forEach { (name, content, _) ->
                SkillParser.parse(name, content)
                    .onSuccess { imported += it }
                    .onFailure { e ->
                        errors += "$name：${e.message ?: "解析失败"}"
                    }
            }

            if (imported.isNotEmpty()) {
                runCatching { skills.saveAll(imported) }
                    .onFailure { e ->
                        _state.update { it.copy(banner = "技能保存失败：${e.message}") }
                        return@launch
                    }
            }

            refreshSkills()

            // 提示语要同时反映成功与失败，不能让用户以为「全成功了」
            val msg = buildString {
                if (imported.isNotEmpty()) append("已导入 ${imported.size} 个技能")
                if (errors.isNotEmpty()) {
                    if (isNotEmpty()) append("；")
                    append("${errors.size} 个失败 → ")
                    append(errors.joinToString("；"))
                }
            }
            _state.update { it.copy(banner = msg.ifBlank { "没有可导入的技能" }) }
        }
    }

    /** 开关技能。会影响白名单与提示词，所以必须同步刷新缓存。 */
    fun toggleSkill(id: String, enabled: Boolean) {
        viewModelScope.launch {
            skills.setEnabled(id, enabled)
            refreshSkills()
            val name = _skills.value.firstOrNull { it.id == id }?.name ?: "技能"
            _state.update {
                it.copy(banner = if (enabled) "已启用「$name」" else "已停用「$name」")
            }
        }
    }

    fun deleteSkill(id: String) {
        viewModelScope.launch {
            skills.delete(id)
            refreshSkills()
            _state.update { it.copy(banner = "技能已删除") }
        }
    }

    fun clearSkills() {
        viewModelScope.launch {
            skills.clearAll()
            refreshSkills()
            _state.update { it.copy(banner = "已清空全部技能") }
        }
    }

    // ---------------------------------------------------------------- 自动化

    suspend fun refreshAutomations() {
        _automationTasks.value = runCatching { automations.list() }.getOrDefault(emptyList())
    }

    /** 刷新精确闹钟授权状态，供界面显示角标。 */
    fun refreshExactAlarmState() {
        _exactAlarmGranted.value = ExactAlarmPermission.canScheduleExact(getApplication())
    }

    /**
     * 新建或更新任务。
     *
     * 保存后立刻重排闹钟：改了 cron 就必须让新表达式生效，
     * 否则用户会看到任务列表上写着新时间、实际却按旧时间触发。
     */
    fun saveAutomation(task: AutomationTask) {
        viewModelScope.launch {
            val isNew = _automationTasks.value.none { it.id == task.id }
            runCatching { automations.save(task) }
                .onFailure { e ->
                    _state.update { it.copy(banner = "保存失败：${e.message}") }
                    return@launch
                }

            // 先取消旧闹钟再按新配置排 —— 直接 schedule 在时间改早的情况下
            // 会留下一个旧的 PendingIntent 仍在生效
            AlarmScheduler.cancel(getApplication(), task.id)
            if (task.enabled) {
                AlarmScheduler.schedule(getApplication(), task)
            }

            refreshAutomations()
            refreshExactAlarmState()

            val tip = buildString {
                append(if (isNew) "任务已创建" else "任务已更新")
                if (task.enabled && !_exactAlarmGranted.value) {
                    append("（未授权精确闹钟，触发时间可能有偏差）")
                }
            }
            _state.update { it.copy(banner = tip) }
        }
    }

    fun deleteAutomation(id: String) {
        viewModelScope.launch {
            AlarmScheduler.cancel(getApplication(), id)
            automations.delete(id)
            refreshAutomations()
            _state.update { it.copy(banner = "任务已删除") }
        }
    }

    fun toggleAutomation(id: String, enabled: Boolean) {
        viewModelScope.launch {
            val task = _automationTasks.value.firstOrNull { it.id == id } ?: return@launch
            val updated = task.copy(enabled = enabled)
            automations.save(updated)

            if (enabled) {
                AlarmScheduler.schedule(getApplication(), updated)
            } else {
                AlarmScheduler.cancel(getApplication(), id)
            }

            refreshAutomations()
            _state.update {
                it.copy(banner = if (enabled) "已启用「${task.title}」" else "已停用「${task.title}」")
            }
        }
    }

    /**
     * 立即执行一次。
     *
     * 刻意走与定时触发**完全相同**的服务路径，而不是在 ViewModel 里直接跑。
     * 两条路径分叉的话，「手动能跑、定时跑不了」这类 bug 会非常难查。
     */
    fun runAutomationNow(id: String) {
        val task = _automationTasks.value.firstOrNull { it.id == id }
        if (task == null) {
            _state.update { it.copy(banner = "任务不存在") }
            return
        }
        AutomationService.start(getApplication(), id)
        _state.update { it.copy(banner = "已在后台开始执行「${task.title}」") }
    }

    /** 任务所属会话 id，供界面「查看执行记录」跳转。 */
    fun conversationIdOfTask(taskId: String): String? =
        _automationTasks.value.firstOrNull { it.id == taskId }?.conversationId

    /**
     * 请求切到「对话」标签页。
     *
     * ViewModel 不直接持有导航状态（那是界面的事），这里只发一个一次性信号，
     * 由 MainActivity 消费后切换标签。
     */
    private val _openChatSignal = MutableStateFlow(0)
    val openChatSignal: StateFlow<Int> = _openChatSignal.asStateFlow()

    fun openChatTab() {
        _openChatSignal.update { it + 1 }
    }

    /**
     * 从通知进入时打开任务会话。
     *
     * 通知里带的是 **taskId** 而不是会话 id：任务首次执行前还没有会话 id，
     * 而通知可能在执行完成前就被点击。这里先查任务拿会话 id，再打开。
     */
    fun openConversationByTask(taskId: String) {
        viewModelScope.launch {
            // 任务列表可能还没加载完（冷启动直接从通知进入），先刷一次
            if (_automationTasks.value.isEmpty()) refreshAutomations()
            val cid = conversationIdOfTask(taskId)
            if (cid.isNullOrBlank()) {
                _state.update { it.copy(banner = "该任务还没有执行记录") }
                return@launch
            }
            openConversation(cid)
        }
    }

    // ---------------------------------------------------------------- 类型转换

    private fun UiMessage.toStoredMessage() = StoredMessage(
        role = role,
        text = text,
        isError = isError,
        toolLog = toolLog,
        isInternal = isInternal,
        attachments = attachments
    )

    private fun StoredMessage.toUiMessage() = UiMessage(
        role = role,
        text = text,
        isError = isError,
        toolLog = toolLog,
        isInternal = isInternal,
        isStreaming = false,
        attachments = attachments
    )

    private fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    private fun humanBytes(bytes: Long): String = AttachmentRepository.humanSize(bytes)

    private companion object {
        /** 单次请求内最多迭代几轮工具调用。能力变多后放宽到 8 轮，足以完成常规重构。 */
        const val MAX_ROUNDS = 8

        /** 流式期间的落盘防抖窗口，避免逐字增量触发高频写盘。 */
        const val PERSIST_DEBOUNCE_MS = 1200L

        /**
         * 单轮内联文本附件的总预算。
         *
         * 不设预算的话，3 个 256KB 的日志文件就接近 20 万 token，
         * 直接超出多数模型的上下文窗口，而且报错信息通常难以理解。
         */
        const val MAX_INLINE_TEXT_TOTAL = 200 * 1024
    }
}
