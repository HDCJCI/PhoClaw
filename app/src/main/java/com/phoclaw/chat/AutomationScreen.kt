package com.phoclaw.chat

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.phoclaw.chat.auto.AlarmScheduler
import com.phoclaw.chat.data.AutomationTask
import com.phoclaw.chat.data.CronParser
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
private val TIME_FORMAT_SHORT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/**
 * 自动化任务的列表与编辑页。
 *
 * 「下次触发时间」是这一页最重要的信息 —— cron 表达式本身很抽象，
 * 用户看到 `0 9 * * 1-5` 未必能立刻反应过来是「工作日早上 9 点」。
 * 所以在编辑页**实时**算出未来三次触发时刻显示出来，写错了一眼就能发现。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationScreen(vm: MainViewModel) {
    val tasks by vm.automationTasks.collectAsState()
    val exactAlarm by vm.exactAlarmGranted.collectAsState()
    val state by vm.state.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var editing by remember { mutableStateOf<AutomationTask?>(null) }
    var creating by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<AutomationTask?>(null) }
    var showPermissionDialog by remember { mutableStateOf(false) }

    // 通知权限只在 Android 13+ 需要运行时申请。
    // 在「新建任务」时申请是最合适的时机 —— 此刻用户刚表达出「我要用定时任务」的意图，
    // 能理解为什么需要通知权限（不然结果没法通知他）
    val notificationPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            scope.launch {
                snackbarHost.showSnackbar("未授予通知权限，任务仍会执行，但结果无法通知你")
            }
        }
    }

    fun ensureNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(Unit) {
        vm.refreshAutomations()
        vm.refreshExactAlarmState()
    }

    LaunchedEffect(state.banner) {
        state.banner?.let {
            snackbarHost.showSnackbar(it)
            vm.clearBanner()
        }
    }

    // 编辑 / 新建页
    if (creating || editing != null) {
        TaskEditor(
            initial = editing,
            exactAlarmGranted = exactAlarm,
            onBack = { creating = false; editing = null },
            onSave = { task ->
                vm.saveAutomation(task)
                creating = false
                editing = null
            },
            onRequestExactAlarm = { showPermissionDialog = true }
        )
        if (showPermissionDialog) {
            ExactAlarmDialog(
                onDismiss = { showPermissionDialog = false },
                onOpenSettings = {
                    showPermissionDialog = false
                    openExactAlarmSettings(context)
                }
            )
        }
        return
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            icon = { Icon(Icons.Default.DeleteOutline, contentDescription = null) },
            title = { Text("删除任务") },
            text = { Text("将永久删除「${target.title}」，此操作不可撤销。", fontSize = 14.sp) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteAutomation(target.id)
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("自动化", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        Text(
                            if (exactAlarm) "${tasks.count { it.enabled }} 个任务运行中"
                            else "${tasks.count { it.enabled }} 个任务 · 非精确模式",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    ensureNotificationPermission()
                    creating = true
                },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("新建任务") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!exactAlarm) {
                item {
                    InexactBanner(onFix = { showPermissionDialog = true })
                }
            }

            if (tasks.isEmpty()) {
                item { EmptyAutomationHint() }
            }

            items(tasks, key = { it.id }) { task ->
                AutomationCard(
                    task = task,
                    onToggle = { vm.toggleAutomation(task.id, it) },
                    onEdit = { editing = task },
                    onDelete = { pendingDelete = task },
                    onRunNow = { vm.runAutomationNow(task.id) },
                    onOpenConversation = {
                        vm.conversationIdOfTask(task.id)?.let { cid ->
                            vm.openConversation(cid)
                            vm.openChatTab()
                        }
                    }
                )
            }

            item { Spacer(Modifier.height(72.dp)) }
        }
    }
}

@Composable
private fun InexactBanner(onFix: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Schedule, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("当前为非精确模式", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                Text(
                    "系统未授予精确闹钟权限，任务可能延迟十几分钟触发。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onFix) { Text("授权") }
        }
    }
}

@Composable
private fun AutomationCard(
    task: AutomationTask,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRunNow: () -> Unit,
    onOpenConversation: () -> Unit
) {
    val nextTriggers = remember(task.cron, task.enabled) {
        if (task.enabled) AlarmScheduler.nextTrigger(task.cron, 1) else emptyList()
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (task.enabled) Icons.Default.AlarmOn else Icons.Default.AlarmOff,
                    contentDescription = null,
                    tint = if (task.enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        task.title,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (task.summary.isNotBlank()) {
                        Text(
                            task.summary,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Switch(checked = task.enabled, onCheckedChange = onToggle)
            }

            Spacer(Modifier.height(10.dp))

            // cron 原文 + 人话描述，两个都给
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        task.cron,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                CronParser.parse(task.cron).expression?.let { expr ->
                    Text(
                        expr.describe(),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            if (task.enabled) {
                if (nextTriggers.isEmpty()) {
                    Text(
                        "表达式永不触发，请检查",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Text(
                        "下次触发：${nextTriggers.first().format(TIME_FORMAT)}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            } else {
                Text(
                    "已停用",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (task.allowDangerous) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "允许危险操作",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            if (task.lastRunAt > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "上次执行：${LocalDateTime.ofEpochSecond(task.lastRunAt / 1000, 0, java.time.ZoneOffset.systemDefault().rules.getOffset(java.time.Instant.now()))}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (task.lastResult.isNotBlank()) {
                    Text(
                        (if (task.lastSuccess) "✓ " else "✗ ") + task.lastResult,
                        fontSize = 11.sp,
                        color = if (task.lastSuccess) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onRunNow, enabled = task.enabled) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("立即执行", fontSize = 12.sp)
                }
                TextButton(onClick = onOpenConversation, enabled = task.conversationId.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("执行记录", fontSize = 12.sp)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "编辑", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "删除",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyAutomationHint() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 60.dp, start = 32.dp, end = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Schedule,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(14.dp))
        Text("还没有自动化任务", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "设定一个 cron 表达式与任务描述，到时间后 PhoClaw 会在后台\n" +
                    "自动把描述发给 AI 并执行，完成后通知你。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "例如：每天 9 点整理工作区的待办事项",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------------------------------------------------------------- 编辑页

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskEditor(
    initial: AutomationTask?,
    exactAlarmGranted: Boolean,
    onBack: () -> Unit,
    onSave: (AutomationTask) -> Unit,
    onRequestExactAlarm: () -> Unit
) {
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var summary by remember { mutableStateOf(initial?.summary.orEmpty()) }
    var prompt by remember { mutableStateOf(initial?.prompt.orEmpty()) }
    var cron by remember { mutableStateOf(initial?.cron ?: "0 9 * * *") }
    var allowDangerous by remember { mutableStateOf(initial?.allowDangerous ?: false) }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }

    // 实时校验 + 预览。每次 cron 变化都重算，
    // 用户改一个字符就能立刻看到「下次触发」有没有变合理
    val parseResult = remember(cron) { CronParser.parse(cron) }
    val previews = remember(cron, parseResult.isValid) {
        if (parseResult.isValid) AlarmScheduler.nextTrigger(cron, 3) else emptyList()
    }

    val cronError = when {
        cropped(cron).isEmpty() -> null   // 还没开始填，不报错
        !parseResult.isValid -> parseResult.error
        previews.isEmpty() -> "该表达式不会触发（例如 2 月 30 日）"
        else -> null
    }

    val canSave = title.isNotBlank() && prompt.isNotBlank() && cronError == null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initial == null) "新建任务" else "编辑任务") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            val task = (initial ?: newTask()).copy(
                                title = title.trim(),
                                summary = summary.trim(),
                                prompt = prompt.trim(),
                                cron = cropped(cron),
                                allowDangerous = allowDangerous,
                                enabled = enabled
                            )
                            onSave(task)
                        },
                        enabled = canSave
                    ) { Text("保存") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("标题") },
                placeholder = { Text("每天早上整理待办") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = summary,
                onValueChange = { summary = it },
                label = { Text("简介（可选）") },
                placeholder = { Text("读一遍工作区里的清单，生成今日要点") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("任务描述") },
                placeholder = {
                    Text("用自然语言描述你想让 AI 做什么。\n会原样作为消息发给 AI，所以写成「你」对 AI 说话的语气最自然。")
                },
                minLines = 4,
                maxLines = 10,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(20.dp))

            Text("触发时间", fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "5 段：分 时 日 月 周　·　6 段：秒 分 时 日 月 周",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = cron,
                onValueChange = { cron = it },
                label = { Text("cron 表达式") },
                isError = cronError != null,
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth()
            )

            if (cronError != null) {
                Spacer(Modifier.height(4.dp))
                Text(cronError, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }

            // 常用表达式快捷填充 —— cron 对不熟的人门槛很高，
            // 给几个典型例子比让人去查文档友好得多
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CRON_PRESETS.forEach { (label, value) ->
                    AssistChip(
                        onClick = { cron = value },
                        label = { Text(label, fontSize = 11.sp) }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            if (previews.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "接下来会在这几个时间触发",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(6.dp))
                        previews.forEach { t ->
                            Text(
                                "· ${t.format(TIME_FORMAT)}",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        parseResult.expression?.let { expr ->
                            Spacer(Modifier.height(6.dp))
                            Text(
                                expr.describe(),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (parseResult.warnings.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                parseResult.warnings.forEach {
                    Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary)
                }
            }

            if (!exactAlarmGranted) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onRequestExactAlarm() }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "未授予精确闹钟权限，触发时间可能延迟。点此授权（不授权也能用）",
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Text("权限", fontWeight = FontWeight.Medium, fontSize = 14.sp)

            Spacer(Modifier.height(8.dp))

            // 危险操作开关
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (allowDangerous) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (allowDangerous) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("允许危险操作", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Text(
                                "删除、移动文件",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = allowDangerous,
                            onCheckedChange = { allowDangerous = it }
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    Text(
                        if (allowDangerous) {
                            "⚠️ 已开启。任务在后台无人值守运行时，AI 可以直接删除或移动工作区文件，" +
                                    "过程中不会有任何确认。请确认工作区内的数据可以承受被删除。"
                        } else {
                            "关闭时，AI 遇到删除或移动操作会被拒绝，并收到「已拒绝」的说明，" +
                                    "因此可以改用其它方式继续完成任务。建议保持关闭。"
                        },
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = if (allowDangerous) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("启用任务", fontSize = 14.sp)
                    Text(
                        "关闭后保留配置，但不触发",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ExactAlarmDialog(onDismiss: () -> Unit, onOpenSettings: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Schedule, contentDescription = null) },
        title = { Text("开启精确提醒") },
        text = {
            Column {
                Text(
                    "系统默认不允许应用在精确时间唤醒（为省电）。授权后任务会准时触发。",
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "不授权也能用：任务照常执行，只是可能延迟十几分钟。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) { Text("去设置") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("以后再说") }
        }
    )
}

// ---------------------------------------------------------------- 工具

private fun cropped(s: String) = s.trim().replace(Regex("\\s+"), " ")

private fun newTask(): AutomationTask = AutomationTask(
    id = UUID.randomUUID().toString().replace("-", "").take(16),
    title = "",
    summary = "",
    prompt = "",
    cron = "",
    allowDangerous = false,
    enabled = true,
    createdAt = System.currentTimeMillis()
)

/** 打开系统的精确闹钟授权页。 */
private fun openExactAlarmSettings(context: android.content.Context) {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return
    runCatching {
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:${context.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }.onFailure {
        // 个别 ROM 没有这个页面，退回应用详情页让用户手动找
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )
        }
    }
}

private val CRON_PRESETS = listOf(
    "每天 9 点" to "0 9 * * *",
    "每小时" to "0 * * * *",
    "工作日 8 点" to "0 8 * * 1-5",
    "每 30 分钟" to "*/30 * * * *",
    "每周一 10 点" to "0 10 * * 1",
    "每月 1 号" to "0 0 1 * *"
)
