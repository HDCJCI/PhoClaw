package com.phoclaw.chat

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.phoclaw.chat.data.ChatMessage
import com.phoclaw.chat.data.ModelProfile
import com.phoclaw.chat.data.PendingAttachment
import com.phoclaw.chat.ui.MarkdownText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    vm: MainViewModel = viewModel(),
    onOpenHistory: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    val state by vm.state.collectAsState()
    val listState = rememberLazyListState()
    val snackbarHost = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var profileSheetOpen by remember { mutableStateOf(false) }

    // 模型档案。切换档案 = 切换后端 + 模型 + 全部相关参数
    val profiles by vm.profiles.collectAsState()
    val activeProfileId by vm.activeProfileId.collectAsState()
    val activeProfile = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.firstOrNull()

    // 目录选择器：打开系统 SAF，让用户挑一个工作区目录
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { vm.onWorkspacePicked(it) }
    }

    // 附件选择器。用一个 launcher 通吃图片和文件 ——
    // GetMultipleContents 只能传单个 mime 类型，PickVisualMedia 又只能选图片，
    // 都不满足「图片 + 代码文件混选」的需求。
    val attachmentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { vm.addPendingAttachment(it) }
    }

    LaunchedEffect(Unit) { vm.refreshWorkspaceState() }

    /*
     * 跟随最新消息。
     *
     * 三处限制，缺一个体验都会崩：
     *
     * 1. autoFollow —— 用户往上翻时不跟随。否则流式期间每 120ms 把视图拽回
     *    底部，想回看上一句都做不到。
     * 2. sample(120) —— 流式文本每几十毫秒变一次，逐次滚动会把主线程的
     *    Layout 阶段排满，而且旧实现每个 token 都 animateScrollToItem，
     *    新动画不断打断旧动画，看起来像抽搐。
     * 3. 滚到末尾的哨兵 item，而不是最后一条消息。scrollToItem(最后一条) 是把
     *    这条消息的**顶部**对齐视口顶部，而流式中的回答往往比屏幕还高 ——
     *    结果每追加一段就把视图拽回这条消息的开头，用户永远看不到正在写的那一行。
     */
    var autoFollow by remember { mutableStateOf(true) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.lastScrolledBackward }
            .collect { (scrolling, backward) ->
                // 只认用户自己的滚动方向。程序调用 scrollToItem 也会让
                // isScrollInProgress 为 true，但方向是向下的，正好保持跟随
                if (scrolling) autoFollow = !backward
            }
    }

    // 自己发消息时恢复跟随：发出去就是为了看回复，没有理由停在上文
    LaunchedEffect(state.messages.size) {
        if (state.messages.lastOrNull()?.role == ChatMessage.ROLE_USER) autoFollow = true
    }

    fun bottomItemIndex(): Int =
        (if (state.messages.isEmpty()) 1 else 0) +
                (if (state.summarizedCount > 0) 1 else 0) +
                state.messages.size

    LaunchedEffect(Unit) {
        snapshotFlow {
            val last = state.messages.lastOrNull()
            // 长度做信号就够：内容只会追加，长度不变则布局不会变
            state.messages.size to (last?.text?.length ?: 0)
        }
            .sample(120)
            .collect {
                if (state.messages.isEmpty() || !autoFollow) return@collect
                if (state.isStreaming) {
                    // 流式：无动画，120ms 一次动画会互相打断
                    listState.scrollToItem(bottomItemIndex())
                } else {
                    listState.animateScrollToItem(bottomItemIndex())
                }
            }
    }

    LaunchedEffect(state.banner) {
        state.banner?.let {
            snackbarHost.showSnackbar(it)
            vm.clearBanner()
        }
    }

    /*
     * 键盘弹出时把视图滚到最新一条。
     *
     * 键盘占了半个屏，原本可见的那几条会被顶到视野外。用户点输入框的瞬间
     * 期望看到的是「最近聊到哪了」，而不是自己刚看过的那几条。
     * 这里监听 ime 可见性而不是键盘高度：高度是连续变化的，
     * 每变一次都滚一下会在键盘动画期间疯狂抖动。
     */
    val imeVisible = WindowInsets.Companion.isImeVisible
    LaunchedEffect(imeVisible) {
        if (imeVisible && state.messages.isNotEmpty()) {
            // 指到末尾哨兵，而不是最后一条消息的顶部：键盘弹起时用户要看的是
            // 「最近聊到哪了」，长回答的最后一行才是这句话
            listState.animateScrollToItem(bottomItemIndex())
        }
    }

    // 危险操作确认框
    state.pendingApproval?.let { pending ->
        AlertDialog(
            onDismissRequest = { vm.rejectPendingAction() },
            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
            title = { Text("确认操作") },
            text = {
                Column {
                    Text("AI 想要执行以下操作：", fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        pending.description,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "此操作不可撤销，确认要继续吗？",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.approvePendingAction() },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("允许") }
            },
            dismissButton = {
                TextButton(onClick = { vm.rejectPendingAction() }) { Text("拒绝") }
            }
        )
    }

    // 模型切换弹层。放在 Scaffold 之前：ModalBottomSheet 自己是一个窗口，
    // 组合位置不影响它盖在谁上面
    if (profileSheetOpen) {
        ModelProfileSheet(
            profiles = profiles,
            activeId = activeProfileId,
            onSelect = { id ->
                vm.selectProfile(id)
                profileSheetOpen = false
            },
            onManage = {
                profileSheetOpen = false
                onOpenSettings()
            },
            onDismiss = { profileSheetOpen = false }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    /*
                     * 标题整块可点 = 切换模型。
                     *
                     * 没有另加一个图标：顶栏已经有历史、新会话、更多三个按钮，
                     * 再塞第四个会让每个都变小、都更难点。而「点标题换模型」
                     * 是个很自然的动作 —— 副标题上那个小箭头就是提示。
                     */
                    Column(
                        modifier = Modifier.clickable(enabled = !state.isStreaming) {
                            profileSheetOpen = true
                        }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("PhoClaw", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "切换模型",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            // 副标题给三样东西：哪个模型、哪个工作区。
                            // 模型名放前面 —— 「我现在在跟谁说话」比
                            // 「文件写到哪里」更常需要确认
                            text = listOfNotNull(
                                activeProfile?.model?.takeIf { it.isNotBlank() } ?: "未配置模型",
                                if (state.workspaceReady) state.workspaceName ?: "工作区"
                                else "未选择工作区"
                            ).joinToString(" · "),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Default.History, contentDescription = "历史对话")
                    }
                    IconButton(onClick = { vm.newSession() }) {
                        Icon(Icons.Default.AddComment, contentDescription = "新会话")
                    }
                    /*
                     * 更多菜单。
                     *
                     * 压缩放在这里而不是单独一个图标：它不是高频操作，
                     * 挤在顶栏里会和「历史」「新会话」抢注意力。
                     * 压缩期间把图标换成进度圈 —— 这个操作要调一次模型，
                     * 可能持续好几秒，没有反馈的话用户会以为点了没反应。
                     */
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            if (state.isCompressing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.MoreVert, contentDescription = "更多")
                            }
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text("压缩上下文")
                                        Text(
                                            text = contextLabel(state),
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                enabled = !state.isCompressing && state.messages.isNotEmpty(),
                                onClick = {
                                    menuOpen = false
                                    vm.compressContextNow()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("长期记忆") },
                                onClick = {
                                    menuOpen = false
                                    onOpenSettings()
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (!state.workspaceReady) {
                WorkspacePrompt(onPick = { picker.launch(null) })
            }

            Box(Modifier.weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = 12.dp,
                        // 底部多留一块：悬浮按钮压在右下角，
                        // 不留空间的话最后一条消息的右下角会被盖住
                        bottom = 12.dp + 48.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (state.messages.isEmpty()) {
                        item { EmptyHint(hasWorkspace = state.workspaceReady) }
                    }
                    // 压缩提示条。放在最顶部而不是底部：用户往上翻的时候
                    // 第一个看到的就是「这里之后的记录不在模型脑子里了」，
                    // 正好对应他要确认的事
                    if (state.summarizedCount > 0) {
                        item { CompressionNotice(state.summarizedCount) }
                    }
                    // 内部消息（工具结果回灌、附件内容注入）不渲染气泡，
                    // 但其挂载的工具日志要展示；思考过程同理 —— 有思考就要让人看见
                    items(state.messages) { msg ->
                        if (msg.isInternal && msg.toolLog.isEmpty() && msg.reasoning.isBlank()) {
                            return@items
                        }
                        MessageBubble(msg, attachmentFile = vm::attachmentFile)
                    }

                    /*
                     * 末尾哨兵。
                     *
                     * 「滚到底」需要一个能落在内容末尾的目标。直接滚到最后一条
                     * 消息只会把它的顶部对齐视口顶部 —— 对超出一屏的流式回答来说，
                     * 等于每次都把视图拉回开头。
                     *
                     * 滚到这条 1dp 的哨兵时，LazyColumn 不能滚过内容末尾，会自然
                     * clamp 到底部，短消息和长消息都能正确落位。
                     */
                    item(key = "chat-bottom-anchor") {
                        Spacer(Modifier.height(1.dp))
                    }
                }

                /*
                 * 「回到最新」悬浮按钮。
                 *
                 * 常驻显示（用户明确要求）——好处是不会在滚动时突然出现/消失
                 * 导致视觉抖动，位置固定也就形成了肌肉记忆。
                 *
                 * 没有消息时隐藏：空对话页上飘一个「回到最新」很怪，
                 * 而且那时也没什么可滚的。
                 */
                if (state.messages.isNotEmpty()) {
                    val scope = rememberCoroutineScope()
                    SmallFloatingActionButton(
                        onClick = {
                            // 点了就说明想看最新内容，恢复跟随
                            autoFollow = true
                            scope.launch {
                                // 用 animate 而不是 snap：消息多时瞬间跳转会让人
                                // 失去方位感，平滑滚动能看清中间掠过了什么
                                listState.animateScrollToItem(bottomItemIndex())
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 14.dp, bottom = 14.dp),
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ) {
                        Icon(
                            Icons.Default.ArrowDownward,
                            contentDescription = "回到最新消息"
                        )
                    }
                }
            }

            InputBar(
                value = state.input,
                isStreaming = state.isStreaming,
                pending = state.pendingAttachments,
                onValueChange = vm::onInputChange,
                onPickClick = {
                    attachmentPicker.launch(arrayOf(
                        "image/*",
                        "text/*",
                        "application/json",
                        "application/xml",
                        "application/javascript",
                        "application/x-yaml",
                        // 放开任意文件：否则 .kt / .py / .gradle 这类系统识别不出
                        // MIME 的代码文件在选择器里会是灰的，选不中。
                        // 真正的内容过滤由 AttachmentRepository 负责
                        //（二进制检测 + 大小上限），不会因为放开就出问题。
                        "application/octet-stream"
                    ))
                },
                onRemovePending = vm::removePendingAttachment,
                onSend = vm::send,
                onStop = vm::stopStreaming
            )
        }
    }
}

@Composable
private fun WorkspacePrompt(onPick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Folder, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text(
                "AI 需要你指定一个目录作为工作区才能读写文件",
                modifier = Modifier.weight(1f),
                fontSize = 13.sp
            )
            TextButton(onClick = onPick) { Text("选择") }
        }
    }
}

/**
 * 顶栏菜单里那行副标题。
 *
 * 压缩按钮本身不带数字，用户看不到「还剩多少」。把当前估算占用放在标题下面，
 * 点开菜单就知道该不该压 —— token 数比任何进度条都直观。
 */
private fun contextLabel(state: ChatUiState): String = when {
    state.isCompressing -> "正在压缩…"
    state.contextTokens <= 0 -> "把早期对话压成摘要"
    state.contextTokens >= 10_000 -> "当前约 ${state.contextTokens / 1000}k token"
    else -> "当前约 ${state.contextTokens} token"
}

/**
 * 「更早的 N 条已压缩」提示条。
 *
 * 必须显示：不显示的话，用户会以为是模型突然变笨了 —— 明明上面刚说过的事，
 * 模型却答不上来。这一行字解释了原因，也告诉用户原文还在。
 */
@Composable
private fun CompressionNotice(count: Int) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "更早的 $count 条已压缩成摘要，发给模型的只有摘要；往下翻仍能看到全文",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
private fun EmptyHint(hasWorkspace: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Terminal,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(14.dp))
        Text("开始对话", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (hasWorkspace) "试试：「把工作区的文件列出来」"
            else "先在设置里填 API Key，再选择工作区目录",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 底部弹层：选模型。
 *
 * 为什么是弹层而不是下拉菜单：档案有三行信息（名字、模型名、后端），
 * 下拉菜单一行放不下，而且手指在屏幕下半部分时够不到顶部菜单。
 *
 * 这里只做「选」，「改」交给设置页 —— 同一个地方既选又编辑，
 * 用户很容易在只是想切一下的时候误改到配置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelProfileSheet(
    profiles: List<ModelProfile>,
    activeId: String,
    onSelect: (String) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp)
        ) {
            Text("选择模型", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(3.dp))
            Text(
                "每份档案对应一套独立的后端地址、Key 与参数",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))

            profiles.forEach { profile ->
                val selected = profile.id == activeId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelect(profile.id) }
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = profile.displayName,
                            fontSize = 14.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = profile.subtitle,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (selected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "当前使用",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            HorizontalDivider()
            OutlinedButton(
                onClick = onManage,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("管理档案")
            }
        }
    }
}

/**
 * 思考过程折叠块。
 *
 * 推理模型（DeepSeek-R1、Kimi、QwQ 等）在正式回答前会先吐一大段「内心独白」。
 * 这些内容对调试提示词、判断模型有没有跑偏很有用，但对只想看答案的人来说
 * 是噪音 —— 所以默认**流式时展开、结束后由用户自己收**。
 *
 * 展开后限高 + 内部滚动：一次思考两三千字很常见，不限高的话一条消息
 * 就能把整个列表撑到需要翻好几屏，正文反而被挤没了。
 */
@Composable
private fun ReasoningBlock(text: String, streaming: Boolean) {
    // 无 key 的 remember：只在首次组合时取一次初值。
    // 这样流式过程中用户手动收起后，后续增量不会把它又弹开
    var expanded by remember { mutableStateOf(streaming) }
    val scroll = rememberScrollState()

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.widthIn(max = 360.dp)
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowDown
                    else Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (streaming) "正在思考…" else "思考过程",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "· ${text.length} 字",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            if (expanded) {
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = text.trim(),
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .heightIn(max = 260.dp)
                        .verticalScroll(scroll)
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: UiMessage, attachmentFile: (String) -> File) {
    val isUser = msg.role == "user"
    val isError = msg.isError

    val bg = when {
        isError -> MaterialTheme.colorScheme.errorContainer
        isUser -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val align = if (isUser) Alignment.End else Alignment.Start

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = align) {
        // 思考过程放在气泡**上方**而不是里面：它是「模型怎么想的」，
        // 和「模型答了什么」是两件事。混在同一个气泡里会让正文退到
        // 一大段灰字的后面，反而更难读
        if (!isUser && msg.reasoning.isNotBlank()) {
            ReasoningBlock(text = msg.reasoning, streaming = msg.isStreaming)
            Spacer(Modifier.height(6.dp))
        }

        // 用 hasContent 而不是 text.isNotBlank()：
        // 只发了图片没打字的消息，text 是空串但必须渲染出来
        if (msg.hasContent) {
            // Markdown 渲染的代码块/表格需要横向空间，AI 气泡放宽到 360dp
            val bubbleWidth = if (isUser || isError) 320.dp else 360.dp
            Surface(
                color = bg,
                shape = RoundedCornerShape(
                    topStart = 14.dp, topEnd = 14.dp,
                    bottomStart = if (isUser) 14.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 14.dp
                ),
                modifier = Modifier.widthIn(max = bubbleWidth)
            ) {
                // 内边距挂在 Column 上，内部的 Text / MarkdownText 不再各自加 padding，
                // 否则会出现双层内边距
                Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                    val images = msg.attachments.filter { it.isImage }
                    val docs = msg.attachments.filterNot { it.isImage }

                    images.forEachIndexed { i, a ->
                        if (i > 0) Spacer(Modifier.height(6.dp))
                        AttachmentThumb(
                            file = attachmentFile(a.localName),
                            name = a.displayName,
                            size = a.size
                        )
                    }

                    docs.forEach { a ->
                        if (images.isNotEmpty()) Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Description,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                "${a.displayName}（${formatBytes(a.size)}）",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (msg.text.isNotBlank()) {
                        if (msg.attachments.isNotEmpty()) Spacer(Modifier.height(7.dp))
                        if (isUser || isError) {
                            // 用户输入与错误信息按纯文本显示：前者是原始输入，
                            // 后者是程序生成的多行说明，都不该被当成 Markdown
                            Text(
                                text = msg.text,
                                fontSize = 14.sp,
                                fontFamily = if (isError) FontFamily.Monospace
                                else FontFamily.Default
                            )
                        } else {
                            // 流式期间也走 Markdown，只是内部按 90ms 采样。
                            // 之前这里整段用纯文本，代价是几千字的回答写完后
                            // 才「啪」地变成排版好的样子 —— 前面读的全是带 ** 和 | 的原文
                            MarkdownText(
                                markdown = msg.text,
                                fontSize = 14.sp,
                                streaming = msg.isStreaming
                            )
                        }
                    }
                }
            }
        }

        if (msg.toolLog.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.widthIn(max = 320.dp)
            ) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Build,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("工作区操作", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(6.dp))
                    msg.toolLog.forEach { line ->
                        Text(
                            text = line.take(400),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * 从私有目录解码并显示附件缩略图。
 *
 * 三个要点：
 * - `inJustDecodeBounds` 先读尺寸再算 `inSampleSize`，**绝不整图解码**（大图会 OOM）
 * - `produceState` 把解码放到 IO 线程，重组时不阻塞主线程
 * - 走 `decodeFile` 而不是读成 ByteArray，省一次内存拷贝
 */
@Composable
private fun AttachmentThumb(file: File, name: String, size: Long) {
    val bitmap by produceState<Bitmap?>(initialValue = null, file.absolutePath) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                if (bounds.outWidth <= 0) return@runCatching null

                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= THUMB_TARGET) {
                    sample *= 2
                }
                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                )
            }.getOrNull()
        }
    }

    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .widthIn(max = 240.dp)
                .heightIn(max = 240.dp)
                .clip(RoundedCornerShape(10.dp))
        )
    } else {
        // 文件被清理或损坏时的兜底 —— 不崩，明确告诉用户图没了
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.BrokenImage,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "图片已不可用（${formatBytes(size)}）",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 待发送附件的预览小卡片：图片显示缩略图，文本显示图标 + 文件名。 */
@Composable
private fun PendingChip(item: PendingAttachment, onRemove: () -> Unit) {
    Box(modifier = Modifier.size(76.dp)) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 6.dp, top = 6.dp)
        ) {
            val thumb = item.thumbnail
            if (thumb != null) {
                Image(
                    bitmap = thumb.asImageBitmap(),
                    contentDescription = item.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(4.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.Description,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        item.displayName,
                        fontSize = 9.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 11.sp
                    )
                    Text(
                        formatBytes(item.size),
                        fontSize = 8.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 删除按钮叠在右上角
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(20.dp)
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "移除",
                modifier = Modifier
                    .padding(3.dp)
                    .clickable(onClick = onRemove)
            )
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    isStreaming: Boolean,
    pending: List<PendingAttachment>,
    onValueChange: (String) -> Unit,
    onPickClick: () -> Unit,
    onRemovePending: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    Surface(tonalElevation = 3.dp) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 待发送附件预览条：横向滚动，可逐个删除
            if (pending.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(pending, key = { it.key }) { item ->
                        PendingChip(item = item, onRemove = { onRemovePending(item.key) })
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                // 流式生成期间禁用，避免中途改变上下文
                FilledTonalIconButton(
                    onClick = onPickClick,
                    enabled = !isStreaming,
                    modifier = Modifier.size(46.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "添加附件")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入消息…", fontSize = 14.sp) },
                    maxLines = 5,
                    shape = RoundedCornerShape(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = { if (isStreaming) onStop() else onSend() },
                    // 关键：只带附件、不打字也能发
                    enabled = isStreaming || value.isNotBlank() || pending.isNotEmpty(),
                    modifier = Modifier.size(46.dp)
                ) {
                    if (isStreaming) {
                        Icon(Icons.Default.Stop, contentDescription = "停止")
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

private const val THUMB_TARGET = 480
