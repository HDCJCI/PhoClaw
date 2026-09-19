package com.phoclaw.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoclaw.chat.data.Skill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 技能管理页。
 *
 * 技能 = 提示词 + 指令白名单。导入本地 `.md` 或 `.json` 文件即可。
 * 列表上直接给开关，因为「临时禁用某个技能」是很高频的操作 ——
 * 每次都要进详情页再回来太啰嗦。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(vm: MainViewModel) {
    val skills by vm.skillList.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    val state by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var expandedId by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<Skill?>(null) }
    var confirmClearAll by remember { mutableStateOf(false) }

    // 导入选择器。
    // 最后那个 octet-stream 是刻意加的：很多技能文件用了 .skill / .txt 之类的后缀，
    // 系统识别不出 MIME，在选择器里会是灰的、根本点不动。放开后能选到任何文件，
    // 真正的格式校验由 SkillParser 负责（解析不出来会明确报错）
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    val name = queryDisplayName(context, uri) ?: "skill.md"
                    val text = readText(context, uri) ?: return@mapNotNull null
                    Triple(name, text, 0L)
                }
            }
            if (loaded.isEmpty()) {
                snackbarHost.showSnackbar("无法读取所选文件（可能为空或过大）")
            } else {
                vm.importSkills(loaded)
            }
        }
    }

    LaunchedEffect(state.banner) {
        state.banner?.let {
            snackbarHost.showSnackbar(it)
            vm.clearBanner()
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            icon = { Icon(Icons.Default.DeleteOutline, contentDescription = null) },
            title = { Text("删除技能") },
            text = { Text("将永久删除「${target.name}」，此操作不可撤销。", fontSize = 14.sp) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteSkill(target.id)
                        pendingDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }

    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
            title = { Text("清空全部技能") },
            text = { Text("将删除所有已导入的技能，此操作不可撤销。", fontSize = 14.sp) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.clearSkills()
                        confirmClearAll = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearAll = false }) { Text("取消") }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("技能", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        Text(
                            "${skills.count { it.enabled }} / ${skills.size} 已启用",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    if (skills.isNotEmpty()) {
                        IconButton(onClick = { confirmClearAll = true }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "清空全部")
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
            // 导入按钮做成横幅而不是浮动按钮：技能导入不是高频操作，
            // 但需要在空列表时足够显眼，横幅两个场景都合适
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clickable {
                        picker.launch(arrayOf(
                            "text/markdown",
                            "text/plain",
                            "text/x-markdown",
                            "application/json",
                            // 放开任意文件，理由见上面 picker 处的注释
                            "application/octet-stream"
                        ))
                    },
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.FileUpload, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("导入技能", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text(
                            "支持 .md（含 frontmatter）或 .json 文件",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null)
                }
            }

            if (skills.isEmpty()) {
                EmptySkillsHint()
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(skills, key = { it.id }) { skill ->
                        SkillCard(
                            skill = skill,
                            expanded = expandedId == skill.id,
                            onToggleExpand = {
                                expandedId = if (expandedId == skill.id) null else skill.id
                            },
                            onToggleEnabled = { vm.toggleSkill(skill.id, it) },
                            onDelete = { pendingDelete = skill }
                        )
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }
}

@Composable
private fun SkillCard(
    skill: Skill,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    val scope = rememberCoroutineScope()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (skill.enabled) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (skill.enabled) Icons.Default.Extension else Icons.Default.ExtensionOff,
                    contentDescription = null,
                    tint = if (skill.enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        skill.name,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (skill.description.isNotBlank()) {
                        Text(
                            skill.description,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Switch(
                    checked = skill.enabled,
                    onCheckedChange = onToggleEnabled
                )
            }

            Spacer(Modifier.height(8.dp))

            // 白名单徽章：这是技能最需要用户看清的信息
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (skill.hasRestriction) {
                    AssistChip(
                        onClick = onToggleExpand,
                        label = {
                            Text(
                                "限定 ${skill.allowedActions.size} 条指令",
                                fontSize = 11.sp
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    )
                } else {
                    AssistChip(
                        onClick = onToggleExpand,
                        label = { Text("不限制指令", fontSize = 11.sp) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.LockOpen,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    )
                }

                Spacer(Modifier.weight(1f))

                IconButton(onClick = onToggleExpand, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "收起" else "展开",
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "删除",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            if (expanded) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(10.dp))

                if (skill.hasRestriction) {
                    Text(
                        "允许的指令",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        skill.allowedActions.sorted().joinToString("、"),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(10.dp))
                }

                Text(
                    "提示词",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                // 提示词可能很长，限高 + 内部滚动，避免一张卡片占满整屏
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .background(
                            MaterialTheme.colorScheme.surface,
                            RoundedCornerShape(8.dp)
                        )
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp)
                ) {
                    Text(
                        skill.prompt,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 17.sp
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "来源：${skill.source.ifBlank { "（未知）" }}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EmptySkillsHint() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 60.dp, start = 32.dp, end = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Extension,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(14.dp))
        Text("还没有技能", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "技能可以为 AI 补充领域知识，并限制它能使用的文件操作指令。\n" +
                    "导入一个 .md 或 .json 文件即可开始。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                "---\nname: 代码审查\nactions: read, list, tree\n---\n你是一名严格的代码审查员……",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(12.dp),
                lineHeight = 16.sp
            )
        }
    }
}

// ---------------------------------------------------------------- 文件读取

/** 查询 SAF 文档的显示名。 */
private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String? =
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            // 必须先 moveToFirst：直接读列会抛 CursorIndexOutOfBoundsException
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index) else null
        }
    }.getOrNull()

/** 读取 SAF 文档内容。超过上限直接放弃，避免把整个大文件读进内存。 */
private suspend fun readText(
    context: android.content.Context,
    uri: android.net.Uri
): String? = withContext(Dispatchers.IO) {
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val bytes = stream.readBytes()
            if (bytes.size > MAX_SKILL_FILE_BYTES) return@use null
            String(bytes, Charsets.UTF_8)
        }
    }.getOrNull()
}

private const val MAX_SKILL_FILE_BYTES = 512 * 1024
