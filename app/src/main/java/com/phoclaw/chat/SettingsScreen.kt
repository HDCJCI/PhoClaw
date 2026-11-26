package com.phoclaw.chat

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoclaw.chat.data.DeviceEnv

/**
 * 设置页的一级 / 二级页面。
 *
 * 顺序（ordinal）同时决定切换动画的方向：进入 ordinal 更大的页面从右滑入，
 * 返回时从左滑入。所以枚举的声明顺序就是导航的层级顺序，不要随意调整。
 */
internal enum class SettingsPage(val title: String) {
    HOME("设置"),
    MODEL("模型与连接"),
    CONTEXT("上下文压缩"),
    PROMPT("系统提示词"),
    TOOLS("工具与权限"),
    MEMORY("长期记忆"),
    ABOUT("关于")
}

/**
 * 设置页。
 *
 * ## 为什么要分二级
 *
 * 1.9.0 之前所有设置都堆在一个 [Column] 里纵向滚动，到 1.10.0 已经有 8 个区块、
 * 近千行组合代码。问题不是「长」（手机屏幕本来就只能一屏），而是**找不到**：
 * 用户想关掉代码执行，得先划过工作区、一整屏模型参数、联网搜索，才看到那个开关。
 * 而且每加一个功能，所有东西都往下沉一截。
 *
 * 现在首页只留「目录」：一项一行，标题 + 当前状态摘要。摘要是有信息量的
 * （当前用哪个模型、开了几个工具、记忆多少条），不进二级页也能知道系统状态。
 *
 * ## 状态为什么在子页里，而不是全提到这一层
 *
 * 提到顶层意味着 30 多个参数往下传，改一个字段要动三处。子页自持状态后
 * 每个页面是封闭的，代价是**切页会丢草稿** —— 这个用 onDispose 自动落盘补上了
 * （见 ModelPage / MemoryPage）。
 *
 * 反过来，凡是真正的持久化设置都走 [com.phoclaw.chat.data.CredentialStore]，
 * 所以切页丢掉的是「还没保存的输入」，不是「已保存的配置」。
 */
@Composable
fun SettingsScreen(vm: MainViewModel) {
    // 用 name 而不是枚举本身存：Bundle 对 Serializable 的支持依赖 ROM，
    // 字符串是绝对安全的
    var pageId by rememberSaveable { mutableStateOf(SettingsPage.HOME.name) }
    val page = SettingsPage.entries.firstOrNull { it.name == pageId } ?: SettingsPage.HOME

    val snackbarHost = remember { SnackbarHostState() }
    // 这个 scope 的生命周期是整个设置页，不是单个子页 —— 子页 onDispose
    // 里发起的写盘要活过子页本身，否则协程刚启动就被取消
    val scope = rememberCoroutineScope()

    BackHandler(enabled = page != SettingsPage.HOME) { pageId = SettingsPage.HOME.name }

    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val forward = targetState.ordinal > initialState.ordinal
            val sign = if (forward) 1 else -1
            (slideInHorizontally { sign * it / 6 } + fadeIn()) togetherWith
                    (slideOutHorizontally { -sign * it / 6 } + fadeOut())
        },
        label = "settings-page"
    ) { current ->
        val back: () -> Unit = { pageId = SettingsPage.HOME.name }
        when (current) {
            SettingsPage.HOME -> SettingsHome(
                vm = vm,
                snackbarHost = snackbarHost,
                onOpen = { pageId = it.name }
            )

            SettingsPage.MODEL -> ModelSettingsPage(vm, snackbarHost, scope, back)
            SettingsPage.CONTEXT -> ContextSettingsPage(vm, snackbarHost, back)
            SettingsPage.PROMPT -> PromptSettingsPage(vm, snackbarHost, back)
            SettingsPage.TOOLS -> ToolSettingsPage(vm, snackbarHost, scope, back)
            SettingsPage.MEMORY -> MemorySettingsPage(vm, snackbarHost, scope, back)
            SettingsPage.ABOUT -> AboutSettingsPage(back)
        }
    }
}

// ------------------------------------------------------------------ 首页目录

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHome(
    vm: MainViewModel,
    snackbarHost: SnackbarHostState,
    onOpen: (SettingsPage) -> Unit
) {
    val store = vm.credentialStore
    val state by vm.state.collectAsState()
    val profileList by vm.profiles.collectAsState()
    val activeId by vm.activeProfileId.collectAsState()
    val current = profileList.firstOrNull { it.id == activeId } ?: profileList.firstOrNull()

    // 目录选择器和子页里的那套是同一个 SAF 契约，但必须各自持有一个 launcher
    val workspacePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { vm.onWorkspacePicked(it) } }

    // 记忆条数要读文件，异步取。−1 表示还没读到，避免闪一下「0 条」
    var memoryCount by remember { mutableIntStateOf(-1) }
    LaunchedEffect(Unit) {
        memoryCount = runCatching { vm.memory.stats().entries }.getOrDefault(0)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = { TopAppBar(title = { Text("设置") }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                WorkspaceCard(
                    name = state.workspaceName,
                    onPick = { workspacePicker.launch(null) }
                )
            }

            item {
                GroupLabel("对话与模型")
                SettingsGroup {
                    SettingsEntry(
                        icon = Icons.Default.SmartToy,
                        title = "模型与连接",
                        // 没填 Key 是个真问题：点进去就能填，所以在首页就点出来
                        subtitle = current?.subtitle?.takeIf { it.isNotBlank() }
                            ?: "还没有可用的档案",
                        warning = current?.isConfigured != true,
                        onClick = { onOpen(SettingsPage.MODEL) }
                    )
                    EntryDivider()
                    SettingsEntry(
                        icon = Icons.Default.Compress,
                        title = "上下文压缩",
                        subtitle = buildString {
                            append(if (store.autoCompress) "自动压缩" else "不压缩")
                            append(" · 预算 ")
                            append(current?.contextBudget?.toString() ?: "—")
                            append(" token")
                        },
                        onClick = { onOpen(SettingsPage.CONTEXT) }
                    )
                    EntryDivider()
                    SettingsEntry(
                        icon = Icons.Default.Description,
                        title = "系统提示词",
                        subtitle = if (store.hasCustomSystemPrompt) "已自定义 · 不再自动更新"
                        else "自动生成 · 跟随工具表更新",
                        onClick = { onOpen(SettingsPage.PROMPT) }
                    )
                }
            }

            item {
                GroupLabel("能力与数据")
                SettingsGroup {
                    SettingsEntry(
                        icon = Icons.Default.Build,
                        title = "工具与权限",
                        subtitle = enabledTools(store),
                        onClick = { onOpen(SettingsPage.TOOLS) }
                    )
                    EntryDivider()
                    SettingsEntry(
                        icon = Icons.Default.Psychology,
                        title = "长期记忆",
                        subtitle = buildString {
                            append(if (memoryCount < 0) "读取中…" else "$memoryCount 条")
                            append(" · ")
                            append(if (store.memoryEnabled) "注入每轮对话" else "未启用")
                        },
                        onClick = { onOpen(SettingsPage.MEMORY) }
                    )
                }
            }

            item {
                SettingsGroup {
                    SettingsEntry(
                        icon = Icons.Default.Info,
                        title = "关于",
                        subtitle = "v${appVersion()} · 数据去向一览",
                        onClick = { onOpen(SettingsPage.ABOUT) }
                    )
                }
            }

            item {
                Text(
                    "凭据使用 Android Keystore 加密后存储，不会明文保存。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )
            }
        }
    }
}

/** 首页那一行摘要：把「AI 现在能用什么」压成一句话。 */
private fun enabledTools(store: com.phoclaw.chat.data.CredentialStore): String {
    val on = buildList {
        if (store.searchEnabled) add("联网搜索")
        if (store.codeExecEnabled) add("代码执行")
        if (store.shellEnabled && DeviceEnv.shellAvailable) add("本机命令")
    }
    return if (on.isEmpty()) "全部关闭 · AI 只能用文件读写" else on.joinToString(" · ")
}

@Composable
private fun WorkspaceCard(name: String?, onPick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "工作区",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = name ?: "未选择目录",
                fontSize = 15.sp,
                fontFamily = if (name != null) FontFamily.Monospace else null,
                color = if (name != null) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (name != null) "AI 只能读写这个目录，无法访问目录之外的文件"
                else "还没有选择目录，AI 目前不能读写任何文件",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onPick) {
                Text(if (name == null) "选择目录" else "更换目录", fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
    )
}

/** 一组入口包成一张卡片。用 Surface 而不是 Card：Card 的默认高度和内边距
 *  会把「一组三行」撑得很松。 */
@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
        content = { Column(content = content) }
    )
}

@Composable
private fun EntryDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 52.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )
}

@Composable
private fun SettingsEntry(
    icon: ImageVector,
    title: String,
    subtitle: String,
    warning: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                fontSize = 12.sp,
                color = if (warning) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            Icons.Default.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
internal fun appVersion(): String {
    val context = LocalContext.current
    return remember {
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }
}

// ------------------------------------------------------------------ 共用外壳

/**
 * 二级页的统一外壳：返回箭头 + 标题 + 可选的底部操作栏。
 *
 * 底部操作栏是这次改版顺带解决的一个真问题：原来「保存」按钮夹在页面中间，
 * 用户在下面的输入框里改完，得往上翻才能保存。固定在底部之后，滚到哪都看得见。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsPageScaffold(
    title: String,
    onBack: () -> Unit,
    snackbarHost: SnackbarHostState,
    bottomBar: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        bottomBar = {
            if (bottomBar != null) {
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            bottomBar()
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content
        )
    }
}

/** 二级页里的小标题。比 titleMedium 小一号，和正文拉开但不喧宾夺主。 */
@Composable
internal fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.primary
    )
}

/** 说明文字。11sp / 16sp 行高是这一版统一后的规格 —— 原来 11 和 12 混着用。 */
@Composable
internal fun HintText(
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    fontSize: TextUnit = 11.sp
) {
    Text(text, fontSize = fontSize, lineHeight = fontSize * 1.45f, color = color)
}

/** 开关行。整块可点，不是只有开关可点 —— 手指落在文字上也算。 */
@Composable
internal fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { onChange(!checked) }
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp)
                Spacer(Modifier.height(3.dp))
                Text(
                    subtitle,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = checked,
                onCheckedChange = if (enabled) onChange else null,
                enabled = enabled
            )
        }
    }
}

/** 隐私警告。红字只留给「数据会离开设备」这类不能忽略的事。 */
@Composable
internal fun WarnText(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        color = MaterialTheme.colorScheme.error
    )
}
