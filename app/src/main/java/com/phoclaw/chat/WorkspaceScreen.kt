package com.phoclaw.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** 简易工作区浏览器：浏览目录树、查看文件内容。写入由 AI 完成，也可在此手动新建。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    vm: MainViewModel
) {
    val scope = rememberCoroutineScope()
    var currentPath by remember { mutableStateOf(".") }
    var entries by remember { mutableStateOf<List<Triple<String, Boolean, Long>>>(emptyList()) }
    var preview by remember { mutableStateOf<Pair<String, String>?>(null) }
    var loading by remember { mutableStateOf(false) }
    val snackbarHost = remember { SnackbarHostState() }

    val state by vm.state.collectAsState()

    // 工作区切换后回到根目录：否则会停在上一个工作区的路径上，
    // 那个路径在新工作区里可能根本不存在，列表就空了
    LaunchedEffect(state.workspaceName) {
        currentPath = "."
    }

    val workspacePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            vm.onWorkspacePicked(it)
            preview = null
        }
    }

    fun reload(path: String) {
        scope.launch {
            loading = true
            entries = runCatching { vm.listDirectory(path) }
                .getOrElse {
                    snackbarHost.showSnackbar("读取失败：${it.message}")
                    emptyList()
                }
            loading = false
        }
    }

    LaunchedEffect(currentPath) { reload(currentPath) }

    preview?.let { (name, content) ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(name, fontSize = 15.sp) },
            text = {
                Box(Modifier.heightIn(max = 420.dp)) {
                    LazyColumn {
                        item {
                            Text(
                                content.take(8000),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { preview = null }) { Text("关闭") } }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text("工作区 · ${vm.state.value.workspaceName ?: ""}", fontSize = 16.sp) },
                navigationIcon = {
                    // 目录内部仍要能返回上一级；已在根目录时按钮变灰，
                    // 因为「退出本页」现在交给底部导航栏了
                    IconButton(
                        onClick = {
                            currentPath = currentPath.substringBeforeLast('/', "")
                                .ifBlank { "." }
                        },
                        enabled = currentPath != "." && currentPath.isNotEmpty()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上一级")
                    }
                },
                actions = {
                    /*
                     * 就地更换工作区。放在这里是因为用户想到「换个目录」时
                     * 第一反应就是进「文件」页 —— 设置页那个入口虽然更"正确"，
                     * 但要多跳一层。两个入口都留，成本很低。
                     */
                    IconButton(onClick = { workspacePicker.launch(null) }) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = "更换工作区")
                    }
                    IconButton(onClick = { reload(currentPath) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
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
            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(
                    "当前目录：$currentPath",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }

            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            if (entries.isEmpty() && !loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("空目录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(entries) { (name, isDir, size) ->
                        ListItem(
                            headlineContent = { Text(name, fontSize = 14.sp) },
                            supportingContent = if (!isDir) {
                                { Text(formatSize(size), fontSize = 11.sp) }
                            } else null,
                            leadingContent = {
                                Icon(
                                    if (isDir) Icons.Default.Folder else Icons.Default.Description,
                                    contentDescription = null,
                                    tint = if (isDir) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            modifier = Modifier.clickable {
                                val child = if (currentPath == "." || currentPath.isEmpty()) name
                                else "$currentPath/$name"
                                if (isDir) {
                                    currentPath = child
                                } else {
                                    scope.launch {
                                        runCatching { vm.readFile(child) }
                                            .onSuccess { preview = name to it }
                                            .onFailure {
                                                snackbarHost.showSnackbar("读取失败：${it.message}")
                                            }
                                    }
                                }
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
