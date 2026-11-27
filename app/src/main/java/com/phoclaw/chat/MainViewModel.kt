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
import com.phoclaw.chat.data.CodeRunnerClient
import com.phoclaw.chat.data.CommandDispatcher
import com.phoclaw.chat.data.ContentPart
import com.phoclaw.chat.data.ContextCompressor
import com.phoclaw.chat.data.ConversationMeta
import com.phoclaw.chat.data.ConversationStore
import com.phoclaw.chat.data.CredentialStore
import com.phoclaw.chat.data.DeviceEnv
import com.phoclaw.chat.data.FileChange
import com.phoclaw.chat.data.LlmClient
import com.phoclaw.chat.data.MemoryStore
import com.phoclaw.chat.data.ModelProfile
import com.phoclaw.chat.data.PendingAttachment
import com.phoclaw.chat.data.PromptComposer
import com.phoclaw.chat.data.ShellRunner
import com.phoclaw.chat.data.Skill
import com.phoclaw.chat.data.SkillParser
import com.phoclaw.chat.data.SkillStore
import com.phoclaw.chat.data.StreamEvent
import com.phoclaw.chat.data.StoredConversation
import com.phoclaw.chat.data.StoredMessage
import com.phoclaw.chat.data.HttpToolClient
import com.phoclaw.chat.data.TavilyClient
import com.phoclaw.chat.data.TokenEstimator
import com.phoclaw.chat.data.WebFetchClient
import com.phoclaw.chat.data.WorkspaceRepository
import com.phoclaw.chat.util.CommandParser
import com.phoclaw.chat.util.SystemPromptBuilder
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
    val attachments: List<Attachment> = emptyList(),
    /**
     * 模型的思考过程（推理模型的 `reasoning_content`）。
     *
     * 与 [text] 分开存：思考是旁注，界面上要能折叠，也要能按档案关掉；
     * 而且**绝不能**回传给 API —— 多数后端收到带 `reasoning_content` 的
     * assistant 消息会直接 400。
     */
    val reasoning: String = "",
    /**
     * 这一轮里 AI 改过的文件。
     *
     * 与 [toolLog] 并列而不是合并进去：日志是「它做了什么」的文字流，
     * 这里是可结构化展示（红绿 diff）与可撤销的数据。混在一起的话，
     * 想给某个文件加个「撤销」按钮就得回头去解析字符串。
     */
    val changes: List<FileChange> = emptyList()
) {
    /**
     * 是否有任何可渲染内容。
     *
     * 气泡渲染、自动滚底、落盘过滤统一用它 —— 光看 [text] 会让
     * 「只发了图片没打字」的消息整个消失。
     */
    val hasContent: Boolean get() = text.isNotBlank() || attachments.isNotEmpty()

    /**
     * 有任何值得渲染的东西。
     *
     * 比 [hasContent] 多算了 [reasoning] 和 [toolLog] —— 模型可能先思考再调工具，
     * 一句话没说，那条消息依然要让用户看到「它在想什么」「它动了什么」。
     */
    val isRenderable: Boolean
        get() = hasContent || reasoning.isNotBlank() || toolLog.isNotEmpty()
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
    val pendingAttachments: List<PendingAttachment> = emptyList(),
    /**
     * 已被摘要覆盖的消息条数，0 表示没压缩过。
     *
     * 界面据此显示「更早的 N 条已压缩成摘要」—— 用户不会莫名其妙地
     * 发现模型忘了前面聊过什么。
     */
    val summarizedCount: Int = 0,
    /** 当前上下文的估算占用（token）。让用户在撞墙之前先看到快满了。 */
    val contextTokens: Int = 0,
    /** 正在压缩上下文。压缩要额外调一次模型，可能持续几秒。 */
    val isCompressing: Boolean = false
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

    /** 网页抓取与通用 HTTP 请求。前者复用 Tavily 的 Key，后者不需要凭据。 */
    private val webFetch = WebFetchClient()
    private val httpTool = HttpToolClient()

    /** 远程代码执行。是否真的能用由 `credentials.codeExecEnabled` 决定。 */
    private val codeRunner = CodeRunnerClient()

    /** 本机 shell 执行。是否真的能用由「开关 + 设备支持」两个条件共同决定。 */
    private val shellRunner = ShellRunner(app, workspace)

    /** 跨会话长期记忆（`files/MEMORY.md`）。 */
    private val memoryStore = MemoryStore(app)

    /** 上下文压缩器。调模型做摘要，所以复用同一个 [llm]。 */
    private val compressor = ContextCompressor(llm)

    /**
     * 联网搜索客户端与 Key。
     *
     * 每次调度时**实时**构造，而不是在字段初始化时固定 ——
     * 用户可能在设置里改了 Key，缓存住的话要重启应用才生效。
     */
    private val dispatcher: CommandDispatcher
        get() = CommandDispatcher(
            workspace = workspace,
            tavily = tavily,
            tavilyKey = credentials.tavilyKey,
            webFetch = webFetch,
            httpClient = httpTool,
            codeRunner = codeRunner,
            codeExecEnabled = credentials.codeExecEnabled,
            shellRunner = shellRunner,
            shellEnabled = credentials.shellEnabled,
            shellSupported = DeviceEnv.shellAvailable,
            memory = memoryStore,
            onChange = { turnChanges += it }
        )

    /**
     * 本轮已发生的文件变更。
     *
     * 在 [runTurn] 开始时清空、结束时整体挂到助手气泡上 —— 按「轮」而不是
     * 按「条指令」聚合，是因为用户看到的是「这条回复改了哪些东西」，
     * 他点撤销也是冲着这条回复去的。
     */
    private val turnChanges = mutableListOf<FileChange>()

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

    /**
     * 模型档案列表，供聊天页与设置页收集。
     *
     * `CredentialStore` 本身是同步的（prefs 读写），不是 Flow。
     * 但 Compose 需要能感知变化，所以在 ViewModel 里镜像一份 StateFlow，
     * 每次增删改之后调 [refreshProfiles] 推一次。
     */
    private val _profiles = MutableStateFlow(credentials.profiles)

    /** 全部档案。 */
    val profiles: StateFlow<List<ModelProfile>> = _profiles.asStateFlow()

    private val _activeProfileId = MutableStateFlow(credentials.activeProfileId)

    /** 当前选中的档案 id。 */
    val activeProfileId: StateFlow<String> = _activeProfileId.asStateFlow()

    /** 把存储层的真值同步到界面。任何档案写操作之后都要调用。 */
    fun refreshProfiles() {
        _profiles.value = credentials.profiles
        _activeProfileId.value = credentials.activeProfileId
    }

    /** 切换档案。流式生成过程中禁止 —— 半个回答用 A 模型、半个用 B 模型毫无意义。 */
    fun selectProfile(id: String) {
        if (_state.value.isStreaming) return
        credentials.selectProfile(id)
        refreshProfiles()
    }

    /** 新建一份空白档案并切过去，返回它的 id（设置页据此滚到新档案）。 */
    fun createProfile(): String {
        val created = credentials.createProfile()
        refreshProfiles()
        return created.id
    }

    /** 保存档案。返回落盘后的版本（地址可能被清洗过）。 */
    fun saveProfile(profile: ModelProfile): ModelProfile {
        val saved = credentials.upsertProfile(profile)
        refreshProfiles()
        return saved
    }

    /** 删除档案。返回 false 表示这是最后一份，不允许删。 */
    fun deleteProfile(id: String): Boolean {
        val ok = credentials.deleteProfile(id)
        if (ok) refreshProfiles()
        return ok
    }

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

    /**
     * 长期记忆正文的缓存。
     *
     * 每一轮对话开始时刷新一次（用户可能在设置页里刚改过），
     * 而不是每次都读盘 —— [buildApiMessages] 在 8 轮循环里会被调 8 次。
     */
    @Volatile
    private var memoryCache: String = ""

    /**
     * 当前会话的滚动摘要，以及它**覆盖了多少条 API 消息**。
     *
     * 计数用的是「过滤后要发给模型的消息」的下标，不是界面消息的下标：
     * 界面里还有 toolLog 气泡、纯图片消息等不进上下文的东西，两者对不上。
     *
     * 每次 [openConversation] / [newSession] 都要跟着切换，否则会把上一个
     * 会话的摘要带进新会话 —— 那是很难察觉的串味 bug。
     */
    @Volatile
    private var currentSummary: String = ""

    @Volatile
    private var currentSummarizedCount: Int = 0

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
            // 记忆正文也在这里预热：首轮对话时 [buildApiMessages] 已经能用上
            memoryCache = memoryStore.forPrompt()
        }
    }

    /** 供设置页读写长期记忆。 */
    val memory: MemoryStore get() = memoryStore

    /**
     * 重新读取记忆并刷新缓存。
     *
     * 设置页保存后必须调一次，否则用户改完记忆、下一句对话却没生效，
     * 会以为是功能坏了。
     */
    fun reloadMemory() {
        viewModelScope.launch {
            memoryCache = memoryStore.forPrompt()
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
    /**
     * 设置页的「测试联网」。
     *
     * 同时验证搜索与抓取 —— 两者共用同一个 Key，但走的是不同接口，
     * 只测一个可能会出现「搜索能通、抓取被套餐限制挡住」的假阳性。
     * 用 `also` 让两件事并行不了也至少都执行到，耗时叠加在可接受范围内。
     */
    suspend fun testSearch(): Result<String> {
        val key = credentials.tavilyKey
        val searchResult = tavily.ping(key)
        if (searchResult.isFailure) {
            return Result.failure(searchResult.exceptionOrNull()!!)
        }

        val fetchResult = webFetch.ping(key)
        val searchText = searchResult.getOrNull().orEmpty()
        val fetchText = fetchResult.fold(
            onSuccess = { it },
            onFailure = { "抓取接口异常：${it.message ?: it.javaClass.simpleName}" }
        )
        return Result.success("搜索 $searchText；抓取 $fetchText")
    }

    // ---------------------------------------------------------------- 聊天

    fun onInputChange(value: String) = _state.update { it.copy(input = value) }

    fun clearBanner() = _state.update { it.copy(banner = null) }

    /** 给界面一个一次性的提示（走同一条 Snackbar 通道）。 */
    fun showBanner(message: String) = _state.update { it.copy(banner = message) }

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
        // 摘要跟着会话走：不清的话，上个会话的摘要会被带进新会话，
        // 模型会一脸茫然地接着聊一个用户根本没提过的话题
        currentSummary = ""
        currentSummarizedCount = 0
        _state.update {
            it.copy(
                messages = emptyList(),
                isStreaming = false,
                conversationTitle = DEFAULT_TITLE,
                // 待发送附件属于「上一个会话的输入」，切会话时一并清掉
                pendingAttachments = emptyList(),
                summarizedCount = 0,
                contextTokens = 0
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
            // 冷启动恢复也要带摘要，否则「重启一次就失忆」——
            // 恰恰是压缩功能最让人怀疑有没有用的时刻
            currentSummary = stored.summary
            currentSummarizedCount = stored.summarizedCount
            _state.update {
                it.copy(
                    messages = stored.messages.map { m -> m.toUiMessage() },
                    conversationTitle = stored.title,
                    summarizedCount = stored.summarizedCount
                )
            }
            refreshContextUsage()
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
            // 摘要随会话恢复，否则重开之后模型会「忘掉」被压缩的那段
            currentSummary = stored.summary
            currentSummarizedCount = stored.summarizedCount
            _state.update {
                it.copy(
                    messages = stored.messages.map { m -> m.toUiMessage() },
                    isStreaming = false,
                    conversationTitle = stored.title,
                    // 换会话时清掉上一个会话残留的待发送附件
                    pendingAttachments = emptyList(),
                    summarizedCount = stored.summarizedCount,
                    contextTokens = 0
                )
            }
            // 打开旧会话时也要算一次：否则进度条停在 0，用户会以为上下文是空的，
            // 一提问才发现已经快满了
            refreshContextUsage()
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
                currentSummary = ""
                currentSummarizedCount = 0
                _state.update {
                    it.copy(
                        messages = emptyList(),
                        isStreaming = false,
                        conversationTitle = DEFAULT_TITLE,
                        pendingAttachments = emptyList(),
                        summarizedCount = 0
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
            currentSummary = ""
            currentSummarizedCount = 0
            _state.update {
                it.copy(
                    messages = emptyList(),
                    isStreaming = false,
                    conversationTitle = DEFAULT_TITLE,
                    pendingAttachments = emptyList(),
                    summarizedCount = 0,
                    contextTokens = 0,
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
            // 纯附件消息也要存 —— 只看 text 会把「只发了张图」的消息整个丢掉；
            // 只有思考过程的消息同理
            .filter { it.isRenderable }
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
                        messages = messages,
                        // 摘要在落盘这一刻快照下来。用字段当前值而不是压缩时的
                        // 局部变量 —— 期间可能又压过一次
                        summary = currentSummary,
                        summarizedCount = currentSummarizedCount
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
        turnChanges.clear()
        try {
            // 附件准备必须在循环外做且只做一次：
            // 图片 Base64 跨 8 轮复用，文本内容作为常驻消息注入
            val anchor = currentTurnAnchorIndex()
            prepareTurnImages()
            if (anchor >= 0) injectTextAttachments(anchor)

            // 压缩放在附件注入之后、第一轮请求之前：
            // 这时上下文已经是「这一轮真正要发出去的全部内容」，估算才准。
            // 只做一次，不放进下面的工具循环 —— 否则一次提问会压好几遍
            if (credentials.autoCompress) {
                val report = maybeCompressContext()
                if (report.degraded) {
                    _state.update { it.copy(banner = report.message) }
                }
            }

            var round = 0
            while (round < MAX_ROUNDS) {
                round++
                // 流式过程中已经就地创建并更新了助手气泡，这里拿到它的下标，避免重复追加
                val result = streamingCompletion() ?: return finishStreaming()
                val parsed = CommandParser.parse(result.text)

                // 用去掉指令块后的正文覆盖流式原文（界面上只显示摘要 + 工具日志）
                val assistantIndex = finalizeAssistant(result.messageIndex, parsed.displayText)

                if (parsed.commands.isEmpty()) return finishStreaming()

                // 每轮的工具调用是独立的：清掉上一轮残留，只记录这一轮改的东西。
                // 否则第二个助手气泡会重复显示第一个气泡里已经列为「已修改」的文件
                turnChanges.clear()
                val logs = executeCommands(parsed.commands)
                attachToolLog(assistantIndex, logs)
                attachChanges(assistantIndex)

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
        val thinking = StringBuilder()
        var messageIndex = -1
        var failed = false

        /** 首次收到任何增量时就地创建助手气泡，后续增量的更新都用同一个下标。 */
        fun ensureBubble() {
            if (messageIndex >= 0) return
            _state.update {
                it.copy(messages = it.messages +
                        UiMessage(ChatMessage.ROLE_ASSISTANT, "", isStreaming = true))
            }
            messageIndex = _state.value.messages.lastIndex
        }

        fun updateBubble(transform: (UiMessage) -> UiMessage) {
            val idx = messageIndex
            if (idx < 0) return
            _state.update { s ->
                if (idx > s.messages.lastIndex) s
                else s.copy(
                    messages = s.messages.toMutableList().also { it[idx] = transform(it[idx]) }
                )
            }
        }

        llm.stream(
            baseUrl = credentials.baseUrl,
            apiKey = credentials.apiKey,
            model = credentials.model,
            messages = buildApiMessages(),
            temperature = credentials.temperature
        ).collect { event ->
            when (event) {
                is StreamEvent.Delta -> {
                    buffer.append(event.text)
                    ensureBubble()
                    val text = buffer.toString()
                    updateBubble { it.copy(text = text, isStreaming = true) }
                }

                is StreamEvent.ReasoningDelta -> {
                    // 档案里关掉「显示思考」后直接丢弃：内容已经到达本地，
                    // 但用户明确表示不想看，不该在界面上留痕迹
                    if (!credentials.showReasoning) return@collect
                    thinking.append(event.text)
                    ensureBubble()
                    val full = thinking.toString()
                    updateBubble { it.copy(reasoning = full, isStreaming = true) }
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
        if (text.isBlank()) {
            // 只有思考、没有正文（推理模型想完就停，或者被用户中断）。
            // 必须清掉流式标记，否则那个转圈的骨架屏会一直挂在那里
            if (thinking.isNotEmpty() && messageIndex >= 0) finalizeAssistant(messageIndex, "")
            return null
        }
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
     * 拼出这一轮的 system 消息。
     *
     * 三块内容合成**一条** system：
     * 1. 基础提示词（含已激活技能）
     * 2. 长期记忆 `MEMORY.md`
     * 3. 早期对话的滚动摘要
     *
     * 不拆成多条：部分自建网关只认第一条 system，后面的会被静默丢弃 ——
     * 拆开等于白写，而且很难排查。
     */
    private fun systemText(): String {
        val base = PromptComposer.compose(
            credentials.systemPrompt,
            _skills.value.filter { it.enabled }
        )
        val withMemory = if (credentials.memoryEnabled) {
            SystemPromptBuilder.withMemory(base, memoryCache)
        } else {
            base
        }
        if (currentSummary.isBlank() || currentSummarizedCount <= 0) return withMemory

        return buildString {
            append(withMemory)
            appendLine()
            appendLine()
            appendLine("## 早期对话摘要")
            appendLine()
            appendLine("下面是更早的对话压缩而成的摘要，**不是用户的原话**。此后出现的消息才是近期原文。")
            appendLine("摘要里提到的结论、路径、待办依然有效，可以直接引用；如果它与近期原文冲突，以近期原文为准。")
            appendLine()
            append(currentSummary)
        }
    }

    /**
     * 把界面消息转换成发给 API 的上下文。
     *
     * 三种形态：
     * - 当前轮 + 图片 + 开关开 → `parts` 非空，序列化成多模态数组
     * - 当前轮 + 图片 + 开关关 → 纯字符串，附一句文字说明
     * - 其余（含历史消息）→ 纯字符串，图片变成 `[图片 xxx.png]` 占位
     *
     * 已被摘要覆盖的前 [currentSummarizedCount] 条**不再发送**，正文只留在
     * 界面上给用户看。下标口径与 [ContextCompressor] 一致：都按「过滤后
     * 要发送的消息」计数，不是界面消息的下标。
     *
     * @param skipSummarized 是否跳过已被摘要覆盖的部分。压缩器需要看**完整**
     *   列表才能算出切分点，传 false；正常发送传 true。
     */
    private fun buildApiMessages(skipSummarized: Boolean = true): List<ChatMessage> {
        val list = mutableListOf<ChatMessage>()

        val system = systemText()
        if (system.isNotBlank()) {
            list += ChatMessage(ChatMessage.ROLE_SYSTEM, system)
        }

        val anchor = currentTurnAnchorIndex()
        var apiIndex = 0

        _state.value.messages.forEachIndexed { index, msg ->
            // 与原先一致：只要 user 和 assistant。
            // 注意 internal 消息的 role 也是 user，它们是刻意发给模型的
            //（工具结果回灌、附件内容注入），不在这里过滤。
            if (msg.role != ChatMessage.ROLE_USER && msg.role != ChatMessage.ROLE_ASSISTANT) {
                return@forEachIndexed
            }
            if (msg.text.isBlank() && msg.attachments.isEmpty()) return@forEachIndexed

            // 先记下这条在「发送序列」里的位置，再判断是否已被摘要覆盖。
            // 顺序不能反：先自增再比较的话，被覆盖的区间会整体错位一条
            val covered = skipSummarized && apiIndex < currentSummarizedCount
            apiIndex++
            if (covered) return@forEachIndexed

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
        // 兜底：单条超长就地削。必须放在最后，前面所有分支都可能产出一条巨长的消息
        return shrinkOversized(list, credentials.contextBudget)
    }

    // ---------------------------------------------------------------- 上下文压缩

    /** 压缩结果，用于给界面一句人话。 */
    private data class CompressReport(
        val compressed: Boolean,
        val message: String,
        /** 压缩失败并已降级为「直接丢弃最早若干条」。 */
        val degraded: Boolean = false
    )

    /**
     * **完整**的上下文消息（不含 system），供压缩器分析。
     *
     * 这里必须传 `skipSummarized = false`：压缩器要自己按 [currentSummarizedCount]
     * 跳过已覆盖的部分，再算切分点。如果这里先跳一遍，压缩器再 drop 一次，
     * 就会双重跳过 —— 表现为压缩点一路往后窜、越压越狠，而且切分下标与
     * 发送时的下标彻底对不上。
     */
    private fun contextMessages(): List<ChatMessage> =
        buildApiMessages(skipSummarized = false)
            .filter { it.role != ChatMessage.ROLE_SYSTEM }

    /**
     * 超预算就把早期对话压成摘要。
     *
     * 调用点只有两个：[runTurn] 开头（自动）和聊天页的手动入口。
     * **不要**放进 8 轮工具循环里 —— 那样一次用户提问会触发多次压缩请求，
     * 白白烧钱，而且会把刚刚注入的工具结果也压掉。
     *
     * @param force 手动触发时忽略预算，压到「只剩最近几条」为止
     */
    private suspend fun maybeCompressContext(force: Boolean = false): CompressReport {
        if (!credentials.isConfigured) {
            return CompressReport(false, "还没有配置 API Key，无法压缩")
        }
        val messages = contextMessages()
        val baseTokens = TokenEstimator.estimate(systemText())
        val budget = credentials.contextBudget
        // 只算「真正要发的部分」：被摘要覆盖的那些已经不在请求里了。
        // 这里若按全量算，压过一次之后每轮都会判超预算，白跑一趟压缩逻辑
        val used = baseTokens +
                TokenEstimator.estimate(currentSummary) +
                TokenEstimator.estimate(messages.drop(currentSummarizedCount))

        if (!force && used <= budget) {
            _state.update { it.copy(contextTokens = used) }
            return CompressReport(false, "上下文还有余量（约 $used / $budget）")
        }
        // 还没被压缩的条数都不到保留上限，压了也没有意义
        if (messages.size - currentSummarizedCount <= credentials.keepRecent) {
            return CompressReport(false, "消息太少，没有可压缩的内容")
        }

        _state.update { it.copy(isCompressing = true) }
        val target = if (force) (budget * 0.6).toInt() else budget

        val outcome = compressor.compressIfNeeded(
            baseUrl = credentials.baseUrl,
            apiKey = credentials.apiKey,
            model = credentials.model,
            summary = currentSummary,
            summarizedCount = currentSummarizedCount,
            messages = messages,
            baseTokens = baseTokens,
            budgetTokens = target,
            keepRecent = credentials.keepRecent
        )

        return when (outcome) {
            is ContextCompressor.Outcome.NotNeeded -> {
                _state.update { it.copy(isCompressing = false, contextTokens = outcome.usedTokens) }
                CompressReport(false, "当前上下文约 ${outcome.usedTokens} token，未超过预算")
            }

            is ContextCompressor.Outcome.Compressed -> {
                currentSummary = outcome.summary
                currentSummarizedCount = outcome.summarizedCount
                val after = baseTokens +
                        TokenEstimator.estimate(currentSummary) +
                        TokenEstimator.estimate(messages.drop(currentSummarizedCount))
                _state.update {
                    it.copy(
                        isCompressing = false,
                        summarizedCount = outcome.summarizedCount,
                        contextTokens = after
                    )
                }
                // 摘要要落盘，否则重开会话后模型又「失忆」了
                persistCurrentConversation(immediate = true)
                CompressReport(
                    true,
                    "已把前 ${outcome.summarizedCount} 条压成摘要，" +
                            "上下文从约 $used 降到约 $after token"
                )
            }

            is ContextCompressor.Outcome.Failed -> {
                // 压缩失败不等于对话要挂：退回滑动窗口，直接把最老的丢掉。
                // 信息有损，但总比请求被后端拒绝、整个对话卡死强。
                val fallbackCut = (messages.size - credentials.keepRecent)
                    .coerceAtLeast(1)
                    .coerceAtMost(messages.size)
                if (fallbackCut <= currentSummarizedCount) {
                    _state.update { it.copy(isCompressing = false) }
                    return CompressReport(false, outcome.reason)
                }
                currentSummarizedCount = fallbackCut
                if (currentSummary.isBlank()) {
                    currentSummary = "（更早的对话因压缩失败已省略，只保留最近的原文。）"
                }
                _state.update {
                    it.copy(
                        isCompressing = false,
                        summarizedCount = fallbackCut,
                        contextTokens = baseTokens + TokenEstimator.estimate(messages.drop(fallbackCut))
                    )
                }
                persistCurrentConversation(immediate = true)
                CompressReport(
                    true,
                    "${outcome.reason}\n已临时丢弃最早的 $fallbackCut 条以腾出空间（界面记录不受影响）。",
                    degraded = true
                )
            }
        }
    }

    /**
     * 手动压缩入口（聊天页顶栏）。
     *
     * 强制模式：不看预算，直接压到「大约六成预算」，适合用户主动想省 token
     * 或者模型开始明显记不住前面内容的时候。
     */
    fun compressContextNow() {
        if (_state.value.isStreaming) {
            _state.update { it.copy(banner = "正在生成回复，稍后再压缩") }
            return
        }
        if (_state.value.isCompressing) return
        viewModelScope.launch {
            val report = maybeCompressContext(force = true)
            _state.update { it.copy(banner = report.message) }
        }
    }

    /**
     * 单条消息过长时的兜底截断。
     *
     * 压缩解决「条数太多」，解决不了「一条就是 20 万 token」—— 比如 `read`
     * 一个几 MB 的日志、或者 `http` 拉回来一大坨 JSON。这种消息无论怎么压
     * 早期对话都还在，只能就地削。
     *
     * 只削发给模型的那份，界面上的原文不动 —— 用户想看的还是全的。
     */
    private fun shrinkOversized(messages: List<ChatMessage>, budget: Int): List<ChatMessage> {
        // 单条上限取预算的 35%：给对话本身留出空间，也避免一条把摘要挤没
        val cap = (budget * 0.35).toInt().coerceAtLeast(MIN_SINGLE_MESSAGE_TOKENS)
        return messages.map { msg ->
            if (msg.role == ChatMessage.ROLE_SYSTEM) return@map msg
            val est = TokenEstimator.estimate(msg)
            if (est <= cap || msg.content.isEmpty()) return@map msg

            // 按比例反推该留多少字符，再在行末断开，别把一行的中间切掉
            val keepChars = (msg.content.length.toLong() * cap / est)
                .toInt().coerceIn(400, msg.content.length)
            val head = msg.content.substring(0, keepChars)
            val cut = head.substringBeforeLast('\n', head)
            msg.copy(
                content = "$cut\n\n…（本条内容过长，已截断。原文 ${msg.content.length} 字符，" +
                        "约 $est token；界面里仍是完整内容）"
            )
        }
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

    /**
     * 把本轮累积的文件变更挂到助手气泡上。
     *
     * 同一个文件在一轮里被改两次时保留两条记录 —— 撤销要靠它们逐条倒推，
     * 合并成一条就只能撤到「第一次改之前」或者「最后一次改之后」，都不对。
     */
    private fun attachChanges(index: Int) {
        if (index < 0 || turnChanges.isEmpty()) return
        val snapshot = turnChanges.toList()
        _state.update { s ->
            s.copy(messages = s.messages.toMutableList().also {
                if (index <= it.lastIndex) it[index] = it[index].copy(changes = snapshot)
            })
        }
    }

    /**
     * 撤销某条回复造成的文件改动。
     *
     * 倒序执行：同一个文件在一轮里被改了三次，只有从后往前回放才能
     * 逐步退回最初的状态。正序的话第二次「恢复」会把第三次的结果又覆盖回去。
     *
     * 撤销**不走** [CommandDispatcher]，因此不会弹确认框 —— 这是用户自己
     * 主动点的按钮，不是模型发起的操作。但也正因如此，界面那边要有一次确认。
     */
    fun undoChanges(messageIndex: Int) {
        val msg = _state.value.messages.getOrNull(messageIndex) ?: return
        if (msg.changes.isEmpty()) return
        if (_state.value.isStreaming) {
            _state.update { it.copy(banner = "正在生成，稍后再撤销") }
            return
        }

        viewModelScope.launch {
            val undoable = msg.changes.filter { it.canUndo }
            if (undoable.isEmpty()) {
                _state.update { it.copy(banner = "这些改动没有备份（文件过大），无法撤销") }
                return@launch
            }

            val failures = mutableListOf<String>()
            undoable.asReversed().forEach { change ->
                runCatching {
                    when (change.kind) {
                        FileChange.Kind.CREATE -> workspace.delete(change.path)
                        FileChange.Kind.MODIFY -> workspace.overwrite(change.path, change.before.orEmpty())
                        FileChange.Kind.DELETE -> workspace.overwrite(change.path, change.after.orEmpty())
                    }
                }.onFailure { e -> failures += "${change.path}：${e.message}" }
            }

            // 撤完就把记录清掉：留着的话按钮还在，再点一次会对着已经变过的
            // 文件重放一遍，把用户手动改的东西也冲掉
            _state.update { s ->
                if (messageIndex > s.messages.lastIndex) s
                else s.copy(messages = s.messages.toMutableList().also {
                    it[messageIndex] = it[messageIndex].copy(changes = emptyList())
                })
            }

            val ok = undoable.size - failures.size
            val message = buildString {
                append("已撤销 $ok 个文件的改动")
                if (failures.isNotEmpty()) {
                    append("，${failures.size} 个失败：")
                    append(failures.joinToString("；"))
                }
            }
            _state.update { it.copy(banner = message) }
            persistCurrentConversation(immediate = true)
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
        refreshContextUsage()
    }

    /**
     * 刷新「当前上下文占用」。
     *
     * 之前这个数字只在压缩流程里更新 —— 关掉自动压缩的用户永远看到 0，
     * 进度条也就永远是空的。每轮结束算一次，成本是一次字符串遍历，
     * 比起让用户对着一个不动的数字猜还有多少余量，这点开销值得。
     */
    private fun refreshContextUsage() {
        runCatching {
            val used = TokenEstimator.estimate(buildApiMessages())
            _state.update { it.copy(contextTokens = used) }
        }
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

    /**
     * 把当前会话导出成 Markdown。
     *
     * 只导出**界面上看得见的东西**：内部回灌消息（工具结果、附件正文）不写 ——
     * 那些是给模型看的中间产物，几十 KB 的附件原文掺进去，导出的文件就没法读了。
     * 工具日志保留，但收在 `<details>` 里：想追查时能展开，默认不干扰阅读。
     */
    fun exportMarkdown(): String {
        val s = _state.value
        val title = s.conversationTitle.ifBlank { "PhoClaw 对话" }
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date())

        return buildString {
            appendLine("# $title")
            appendLine()
            appendLine("> 导出自 PhoClaw · $stamp")
            if (s.workspaceReady) appendLine("> 工作区：${s.workspaceName ?: "未命名"}")
            appendLine()
            appendLine("---")
            appendLine()

            s.messages.forEach { msg ->
                if (msg.isInternal) return@forEach
                if (!msg.isRenderable) return@forEach

                when (msg.role) {
                    ChatMessage.ROLE_USER -> appendLine("## 我")
                    ChatMessage.ROLE_ASSISTANT -> appendLine("## PhoClaw")
                    else -> appendLine("## ${msg.role}")
                }
                appendLine()

                if (msg.attachments.isNotEmpty()) {
                    msg.attachments.forEach { a ->
                        appendLine("_[${if (a.isImage) "图片" else "文件"}] ${a.displayName}_")
                    }
                    appendLine()
                }

                if (msg.reasoning.isNotBlank()) {
                    appendLine("<details><summary>思考过程</summary>")
                    appendLine()
                    appendLine(msg.reasoning)
                    appendLine()
                    appendLine("</details>")
                    appendLine()
                }

                if (msg.text.isNotBlank()) {
                    appendLine(msg.text)
                    appendLine()
                }

                if (msg.changes.isNotEmpty()) {
                    appendLine("**改动文件**")
                    appendLine()
                    msg.changes.forEach { c ->
                        val stats = c.stats
                        val delta = if (stats == null) "" else " (+${stats.added} −${stats.removed})"
                        appendLine("- `${c.symbol}` `${c.path}`$delta")
                    }
                    appendLine()
                }

                if (msg.toolLog.isNotEmpty()) {
                    appendLine("<details><summary>工作区操作（${msg.toolLog.size} 条）</summary>")
                    appendLine()
                    appendLine("```")
                    msg.toolLog.forEach { appendLine(it) }
                    appendLine("```")
                    appendLine()
                    appendLine("</details>")
                    appendLine()
                }
            }
        }.trim() + "\n"
    }

    private fun UiMessage.toStoredMessage() = StoredMessage(
        role = role,
        text = text,
        isError = isError,
        toolLog = toolLog,
        isInternal = isInternal,
        attachments = attachments,
        reasoning = reasoning,
        changes = changes
    )

    private fun StoredMessage.toUiMessage() = UiMessage(
        role = role,
        text = text,
        isError = isError,
        toolLog = toolLog,
        isInternal = isInternal,
        isStreaming = false,
        attachments = attachments,
        reasoning = reasoning,
        changes = changes
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

        /**
         * 单条消息截断的下限。
         *
         * 预算调得很小时（比如 4000 token），35% 只有 1400，截得太狠会让
         * 工具结果完全没法用。保底 2000 反而比「严格按预算」更实用 ——
         * 压缩器会把总预算再拉回来。
         */
        const val MIN_SINGLE_MESSAGE_TOKENS = 2_000
    }
}
