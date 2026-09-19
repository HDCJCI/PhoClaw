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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.phoclaw.chat.data.PendingAttachment
import com.phoclaw.chat.ui.MarkdownText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    vm: MainViewModel = viewModel(),
    onOpenHistory: () -> Unit = {}
) {
    val state by vm.state.collectAsState()
    val listState = rememberLazyListState()
    val snackbarHost = remember { SnackbarHostState() }

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

    LaunchedEffect(
        state.messages.size,
        state.messages.lastOrNull()?.text,
        // 纯图片消息的 text 是空的，不加这个依赖就不会触发滚动
        state.messages.lastOrNull()?.attachments?.size
    ) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
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
            listState.animateScrollToItem(state.messages.lastIndex)
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("PhoClaw", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        Text(
                            text = when {
                                !state.workspaceReady -> "未选择工作区"
                                else -> state.workspaceName ?: "工作区"
                            },
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

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (state.messages.isEmpty()) {
                    item { EmptyHint(hasWorkspace = state.workspaceReady) }
                }
                // 内部消息（工具结果回灌、附件内容注入）不渲染气泡，
                // 但其挂载的工具日志要展示
                items(state.messages) { msg ->
                    if (msg.isInternal && msg.toolLog.isEmpty()) return@items
                    MessageBubble(msg, attachmentFile = vm::attachmentFile)
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
                        if (isUser || isError || msg.isStreaming) {
                            // 用户输入、错误信息、以及流式生成中的内容都按纯文本显示。
                            // 流式期间 Markdown 语法尚未闭合（如代码块缺少结尾的 ```），
                            // 提前渲染会导致排版反复跳动，等生成完毕再渲染。
                            Text(
                                text = msg.text,
                                fontSize = 14.sp,
                                fontFamily = if (isError) FontFamily.Monospace
                                else FontFamily.Default
                            )
                        } else {
                            MarkdownText(markdown = msg.text, fontSize = 14.sp)
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
