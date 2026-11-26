package com.phoclaw.chat

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoclaw.chat.data.CredentialStore
import com.phoclaw.chat.data.DeviceEnv
import com.phoclaw.chat.data.MemoryStore
import com.phoclaw.chat.data.ModelProfile
import com.phoclaw.chat.util.BaseUrlHint
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: MainViewModel
) {
    val store = vm.credentialStore
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }

    /*
     * 模型档案。
     *
     * 编辑的永远是「当前选中的那一份」，所以所有输入框的 state 都用当前
     * 档案的 id 当 remember 的 key —— 切换档案时它们会自动重新载入，
     * 不需要手写一遍「切换后重置全部字段」，那种代码漏一个就会串数据。
     */
    val profileList by vm.profiles.collectAsState()
    val activeId by vm.activeProfileId.collectAsState()
    val current = profileList.firstOrNull { it.id == activeId } ?: profileList.firstOrNull()

    var profileName by remember(current?.id) { mutableStateOf(current?.name.orEmpty()) }
    var apiKey by remember(current?.id) { mutableStateOf(current?.apiKey.orEmpty()) }
    var baseUrl by remember(current?.id) { mutableStateOf(current?.baseUrl.orEmpty()) }
    var model by remember(current?.id) { mutableStateOf(current?.model.orEmpty()) }
    var modelSupportsVision by remember(current?.id) {
        mutableStateOf(current?.supportsVision ?: false)
    }
    var showReasoning by remember(current?.id) { mutableStateOf(current?.showReasoning ?: true) }
    var temperatureText by remember(current?.id) {
        mutableStateOf((current?.temperature ?: ModelProfile.DEFAULT_TEMPERATURE).toString())
    }

    var profileMenuOpen by remember { mutableStateOf(false) }
    var confirmDeleteProfile by remember { mutableStateOf(false) }

    var systemPrompt by remember { mutableStateOf(store.systemPrompt) }
    var showKey by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }

    /**
     * 把编辑框里的内容写回当前档案。
     *
     * 地址会被 [BaseUrlHint.normalize] 清洗，所以必须用返回值回显 ——
     * 否则用户看到的是自己填的 `/v1/chat/completions`，实际生效的却是
     * 清洗后的域名，下次进来又变一个样。
     */
    fun saveDraft(): ModelProfile? {
        val base = current ?: return null
        return vm.saveProfile(
            base.copy(
                name = profileName.trim(),
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = model,
                supportsVision = modelSupportsVision,
                showReasoning = showReasoning,
                temperature = temperatureText.toDoubleOrNull() ?: base.temperature
            )
        )
    }

    // 联网搜索
    var tavilyKey by remember { mutableStateOf(store.tavilyKey) }
    var showTavily by remember { mutableStateOf(false) }
    var testingSearch by remember { mutableStateOf(false) }

    // 代码执行（默认关闭）
    var codeExecEnabled by remember { mutableStateOf(store.codeExecEnabled) }

    // 长期记忆
    var memoryEnabled by remember { mutableStateOf(store.memoryEnabled) }
    var memoryText by remember { mutableStateOf("") }
    var memoryStats by remember { mutableStateOf(MemoryStore.Stats(0, 0)) }
    var confirmClearMemory by remember { mutableStateOf(false) }

    // 上下文压缩
    var autoCompress by remember { mutableStateOf(store.autoCompress) }
    // 预算随档案走，切换档案时跟着换（见上面 current 的说明）
    var budgetText by remember(current?.id) {
        mutableStateOf((current?.contextBudget ?: CredentialStore.DEFAULT_CONTEXT_BUDGET).toString())
    }
    var keepRecentText by remember { mutableStateOf(store.keepRecent.toString()) }

    // 本机 shell（默认关闭；鸿蒙上整块隐藏）
    var shellEnabled by remember { mutableStateOf(store.shellEnabled) }

    // 记忆正文在 IO 线程读，进来时加载一次即可
    LaunchedEffect(Unit) {
        memoryText = vm.memory.load()
        memoryStats = vm.memory.stats()
    }

    // 提示词是否已被用户固定。未固定时它随工具表自动生成
    var promptCustomized by remember { mutableStateOf(store.hasCustomSystemPrompt) }

    // 工作区：选目录后要立刻刷新界面上的名字，所以用状态而不是直接读 vm
    val state by vm.state.collectAsState()
    var workspaceName by remember(state.workspaceName) {
        mutableStateOf(state.workspaceName)
    }

    // 目录选择器。和 ChatScreen 里那个是同一套 SAF 契约，
    // 但必须各自持有一个 launcher —— ActivityResult 的注册是绑在组合位置上的
    val workspacePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            vm.onWorkspacePicked(it)
            // onWorkspacePicked 是异步的（要 await 持久化授权），
            // 状态变化由上面 remember(state.workspaceName) 自动同步
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text("设置") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            /*
             * 工作区放在最上面：它是整个应用的前提 —— 没有它 AI 什么都读写不了，
             * 而 API Key 只影响「能不能对话」。原来这个入口只挂在聊天页、
             * 且仅在「还没选工作区」时显示，导致选过之后再也换不掉。
             */
            Text("工作区", style = MaterialTheme.typography.titleMedium)

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        workspaceName ?: "未选择",
                        fontSize = 14.sp,
                        fontFamily = if (workspaceName != null) FontFamily.Monospace else null,
                        color = if (workspaceName != null) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (workspaceName != null) "AI 只能读写这个目录，无法访问目录之外的文件"
                        else "还没有选择目录，AI 目前不能读写任何文件",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            OutlinedButton(
                onClick = { workspacePicker.launch(null) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (workspaceName == null) "选择工作区目录" else "更换工作区目录")
            }

            HorizontalDivider()

            Text("模型档案", style = MaterialTheme.typography.titleMedium)

            Text(
                "每份档案是一套独立的后端地址、Key 与参数。切换到哪个档案，" +
                        "接下来的对话就用哪个模型 —— 对话页点标题也能直接切。",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { profileMenuOpen = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = current?.displayName ?: "未配置",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                            textAlign = TextAlign.Start
                        )
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = profileMenuOpen,
                        onDismissRequest = { profileMenuOpen = false }
                    ) {
                        profileList.forEach { p ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(p.displayName, fontSize = 14.sp)
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            p.subtitle,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                trailingIcon = {
                                    if (p.id == activeId) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = "当前",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                },
                                onClick = {
                                    profileMenuOpen = false
                                    vm.selectProfile(p.id)
                                }
                            )
                        }
                    }
                }

                IconButton(onClick = { vm.createProfile() }) {
                    Icon(Icons.Default.Add, contentDescription = "新建档案")
                }
                IconButton(
                    onClick = { confirmDeleteProfile = true },
                    // 只剩一份时不给删：空列表会让「当前档案」无处可退，
                    // 那些依赖它的地方全得加空判断
                    enabled = profileList.size > 1
                ) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "删除档案",
                        tint = if (profileList.size > 1) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }

            OutlinedTextField(
                value = profileName,
                onValueChange = { profileName = it },
                label = { Text("档案名称（可选）") },
                placeholder = { Text("比如「写代码」「看图」「本地 Ollama」") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = { Text("留空时显示模型名", fontSize = 11.sp) }
            )

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None
                else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(
                            if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "显示/隐藏"
                        )
                    }
                }
            )

            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = BaseUrlHint.isFullEndpoint(baseUrl),
                supportingText = {
                    val warn = BaseUrlHint.warning(baseUrl)
                    if (warn != null) {
                        Text(warn, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                    } else {
                        Column {
                            Text("只填到域名，不要带 /v1/chat/completions", fontSize = 11.sp)
                            Text(
                                "正确：https://api.deepseek.com\n" +
                                        "错误：https://api.deepseek.com/v1/chat/completions",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("模型名") },
                    modifier = Modifier.weight(1.6f),
                    singleLine = true,
                    supportingText = { Text("如 deepseek-chat", fontSize = 11.sp) }
                )
                OutlinedTextField(
                    value = temperatureText,
                    onValueChange = { v ->
                        // 只收数字和小数点，且最多两位小数 —— 手动挡在这里
                        // 比弹一个滑杆好：用户可以精确输入 1.0（推理模型常要求）
                        temperatureText = v.filter { it.isDigit() || it == '.' }
                            .let { if (it.count { c -> c == '.' } > 1) it.dropLast(1) else it }
                            .take(4)
                    },
                    label = { Text("温度", fontSize = 11.sp) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = { Text("0 ~ 2", fontSize = 11.sp) }
                )
            }

            // 视觉开关。默认关闭是刻意的保守选择：多模态要求把 content 写成数组，
            // 而不支持视觉的后端收到数组会直接报 400。
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("模型支持看图", fontSize = 14.sp)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "开启后，你发的图片会以 Base64 传给模型。\n" +
                                    "若模型不支持视觉，请保持关闭 —— 此时只发图片的文字说明，" +
                                    "否则部分后端会直接报错。",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Switch(
                        checked = modelSupportsVision,
                        onCheckedChange = { modelSupportsVision = it }
                    )
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("显示思考过程", fontSize = 14.sp)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "推理模型（deepseek-reasoner、Kimi、QwQ 等）会先输出一段内心独白。" +
                                    "开启后在回答上方显示为可折叠的小块；普通模型没有这个字段，" +
                                    "开了也不会有内容。",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Switch(
                        checked = showReasoning,
                        onCheckedChange = { showReasoning = it }
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val hadRedundantPath = BaseUrlHint.hasRedundantPath(baseUrl)
                        val saved = saveDraft()
                        // 回显清洗后的值，让用户看到实际生效的地址
                        saved?.let { baseUrl = it.baseUrl }
                        scope.launch {
                            snackbarHost.showSnackbar(
                                when {
                                    saved == null -> "没有可保存的档案"
                                    hadRedundantPath -> "已保存，地址已自动清理为 ${saved.baseUrl}"
                                    else -> "已保存"
                                }
                            )
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("保存") }

                OutlinedButton(
                    onClick = {
                        saveDraft()
                        testing = true
                        scope.launch {
                            val result = vm.testConnection()
                            testing = false
                            snackbarHost.showSnackbar(
                                result.fold({ "连接成功：$it" }, { "失败：${it.message}" })
                            )
                        }
                    },
                    enabled = !testing && apiKey.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) { Text(if (testing) "测试中…" else "测试连接") }
            }

            HorizontalDivider()

            // ---------------- 联网搜索与网页抓取 ----------------

            Text("联网搜索与网页抓取", style = MaterialTheme.typography.titleMedium)

            Text(
                "填入 Tavily API Key 后，AI 就能在需要时联网：\n" +
                        "· `websearch` —— 搜索实时信息（新闻、股价、最新版本号等）\n" +
                        "· `webfetch` —— 抓取指定网址的正文（用户贴链接让它看时用这个）\n" +
                        "两者都会在回答里附上来源链接。另外 `http` 指令（发任意 HTTP 请求）" +
                        "不需要 Key，始终可用。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = tavilyKey,
                onValueChange = { tavilyKey = it },
                label = { Text("Tavily API Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("tvly-xxxxxxxxxxxx") },
                visualTransformation = if (showTavily) VisualTransformation.None
                else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = {
                    Text(
                        if (tavilyKey.isBlank()) "留空 = 关闭联网搜索，AI 只能靠已有知识回答"
                        else "已启用联网搜索",
                        fontSize = 11.sp,
                        color = if (tavilyKey.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary
                    )
                },
                trailingIcon = {
                    IconButton(onClick = { showTavily = !showTavily }) {
                        Icon(
                            if (showTavily) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "显示/隐藏"
                        )
                    }
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = {
                        store.tavilyKey = tavilyKey
                        tavilyKey = store.tavilyKey   // 回显 trim 后的值
                        scope.launch {
                            snackbarHost.showSnackbar(
                                if (store.tavilyKey.isBlank()) "已关闭联网搜索"
                                else "Tavily Key 已保存"
                            )
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("保存设置") }

                OutlinedButton(
                    onClick = {
                        store.tavilyKey = tavilyKey
                        tavilyKey = store.tavilyKey
                        testingSearch = true
                        scope.launch {
                            val result = vm.testSearch()
                            testingSearch = false
                            snackbarHost.showSnackbar(
                                result.fold({ "可用：$it" }, { "失败：${it.message}" })
                            )
                        }
                    },
                    enabled = !testingSearch && tavilyKey.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) { Text(if (testingSearch) "测试中…" else "测试搜索") }
            }

            TextButton(
                onClick = {
                    scope.launch {
                        // 用系统浏览器打开 Tavily 控制台，方便申请 Key。
                        // 不内嵌 WebView：那需要额外维护一套页面，也会让用户
                        // 在应用内输入第三方账号密码，反而更不安全
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://app.tavily.com"))
                            )
                        }.onFailure {
                            snackbarHost.showSnackbar("没有可用的浏览器，请手动访问 app.tavily.com")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("去 Tavily 申请免费 Key") }

            HorizontalDivider()

            // ---------------- 代码执行 ----------------
            Text("代码执行", style = MaterialTheme.typography.titleMedium)

            Text(
                "开启后 AI 可以用 `run` 指令运行代码，支持 Python、JavaScript、C++、C、Java、Go、Rust、Shell 等 30+ 种语言。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                "⚠️ 代码会发送到 wandbox.org 执行 —— 这是第三方服务，代码会离开你的设备。" +
                        "不要运行含密钥、密码或个人数据的代码。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.error
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("允许远程执行", fontSize = 14.sp)
                    Text(
                        if (codeExecEnabled) "已开启 · 代码会上传到 wandbox.org"
                        else "已关闭 · 代码不会离开设备",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = codeExecEnabled,
                    onCheckedChange = { checked ->
                        codeExecEnabled = checked
                        store.codeExecEnabled = checked
                        // 未自定义提示词时，让它重新生成 —— `run` 的可用状态
                        // 会写进提示词，开关变了模型也该知道
                        if (!store.hasCustomSystemPrompt) {
                            systemPrompt = store.systemPrompt
                        }
                    }
                )
            }

            Text(
                "远程环境是隔离的，看不到工作区的其它文件；二进制文件无法运行" +
                        "（Android 10+ 禁止执行可写目录里的 ELF 文件）。",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            /*
             * ---------------- 本机命令 ----------------
             *
             * 鸿蒙上**整块不渲染**。开关摆在那里却永远用不了，比没有这个功能
             * 更让人困惑 —— 用户会反复点、反复失败，然后以为是 bug。
             * 检测逻辑见 DeviceEnv，误判方向是「宁可当成鸿蒙」。
             */
            if (DeviceEnv.shellAvailable) {
                Text("本机命令", style = MaterialTheme.typography.titleMedium)

                Text(
                    "开启后 AI 可以用 `shell` 指令在你手机上执行命令 —— 系统自带的 " +
                            "`ls`、`grep`、`find`、`sed`、`du`、`stat` 这些，支持管道和重定向。",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    "⚠️ 命令在你自己的设备上执行，拥有与这个应用相同的权限。" +
                            "和「代码执行」相反，数据不会离开手机；但删除类命令会真的删掉东西。",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.error
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("允许执行本机命令", fontSize = 14.sp)
                        Text(
                            if (shellEnabled) "已开启 · AI 可以运行 shell 命令"
                            else "已关闭 · AI 只能用文件读写指令",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = shellEnabled,
                        onCheckedChange = { checked ->
                            shellEnabled = checked
                            store.shellEnabled = checked
                            // 开关状态会写进系统提示词，变了就得重新生成，
                            // 否则模型还以为没开
                            if (!store.hasCustomSystemPrompt) {
                                systemPrompt = store.systemPrompt
                            }
                        }
                    )
                }

                Text(
                    "· 命令在应用沙箱里跑，工作区文件会镜像到沙箱，改过的文件自动同步回去\n" +
                            "· 没有 python / node / gcc —— 要跑真正的代码请用「代码执行」\n" +
                            "· 单条命令 30 秒超时，输出超过 8000 字符会截断\n" +
                            "· 删除类命令（rm -rf、mkfs、pm uninstall 等）会先弹确认框\n" +
                            "· 碰不到 /sdcard 和其它应用的数据，这是系统限制，不是配置问题",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider()
            }

            // ---------------- 长期记忆 ----------------
            Text("长期记忆", style = MaterialTheme.typography.titleMedium)

            Text(
                "AI 会把这些内容带进**每一轮**对话，不用每次重复交代。存在 App 私有目录里，" +
                        "换工作区也不会丢。你也可以直接对它说「记住…」，它会自己写进来。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("注入到每轮对话", fontSize = 14.sp)
                    Text(
                        if (memoryEnabled) "已开启 · 记忆会影响之后所有对话"
                        else "已关闭 · 内容保留但不发给模型",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = memoryEnabled,
                    onCheckedChange = { checked ->
                        memoryEnabled = checked
                        store.memoryEnabled = checked
                    }
                )
            }

            OutlinedTextField(
                value = memoryText,
                onValueChange = { memoryText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp),
                textStyle = MaterialTheme.typography.bodySmall
                    .copy(fontFamily = FontFamily.Monospace),
                placeholder = {
                    Text(
                        "空的时候可以让 AI 自己写，也可以在这里手写，比如：\n- 用户偏好深色主题\n- 项目用 Kotlin 2.0，不要建议降到 1.x",
                        fontSize = 12.sp
                    )
                },
                supportingText = {
                    Text(
                        "${memoryStats.entries} 条 · ${memoryStats.chars} 字符" +
                                "（上限 ${MemoryStore.MAX_FILE_CHARS} 字符 / ${MemoryStore.MAX_ENTRIES} 条）",
                        fontSize = 11.sp
                    )
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            vm.memory.save(memoryText)
                                .onSuccess {
                                    // 必须重新载入缓存，否则用户改完记忆、
                                    // 下一句话却没生效，会以为是功能坏了
                                    vm.reloadMemory()
                                    memoryStats = vm.memory.stats()
                                    snackbarHost.showSnackbar("记忆已保存")
                                }
                                .onFailure {
                                    snackbarHost.showSnackbar("保存失败：${it.message}")
                                }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("保存记忆") }

                TextButton(
                    onClick = { confirmClearMemory = true },
                    modifier = Modifier.weight(1f)
                ) { Text("清空", color = MaterialTheme.colorScheme.error) }
            }

            Text(
                "记忆写多了会挤占上下文。AI 自己也会往里写，定期清掉不再成立的内容 ——" +
                        "比如「这个项目已完结」之类过期的东西。",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            // ---------------- 上下文压缩 ----------------
            Text("上下文压缩", style = MaterialTheme.typography.titleMedium)

            Text(
                "对话变长之后，早期消息会被**压成摘要**再发给模型，而不是原样全发。" +
                        "界面里的记录一条都不会少，只是模型看到的是浓缩版 —— " +
                        "不这样做的话，聊到一定长度请求会被后端直接拒绝。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("超预算时自动压缩", fontSize = 14.sp)
                    Text(
                        if (autoCompress) "已开启 · 压缩会额外调用一次模型"
                        else "已关闭 · 长对话最终会因超长报错",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = autoCompress,
                    onCheckedChange = { checked ->
                        autoCompress = checked
                        store.autoCompress = checked
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = budgetText,
                    onValueChange = { v ->
                        val digits = v.filter { it.isDigit() }.take(7)
                        budgetText = digits
                        // 删光时保留旧值，不要因为一次退格就跳到最小值。
                        // 预算随档案存，所以直接写进当前档案
                        digits.toIntOrNull()?.let { value ->
                            current?.let { vm.saveProfile(it.copy(contextBudget = value)) }
                        }
                    },
                    label = { Text("上下文预算", fontSize = 11.sp) },
                    suffix = { Text("token", fontSize = 11.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = keepRecentText,
                    onValueChange = { v ->
                        val digits = v.filter { it.isDigit() }.take(2)
                        keepRecentText = digits
                        digits.toIntOrNull()?.let { store.keepRecent = it }
                    },
                    label = { Text("保留最近", fontSize = 11.sp) },
                    suffix = { Text("条", fontSize = 11.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }

            Text(
                "预算按模型的上下文窗口估：64k 的模型填 32000 比较稳，" +
                        "128k 的可以填到 60000。估算是按字符数量级推的，留足余量比精确更重要。\n" +
                        "「保留最近」是每次压缩时不动的那几条，压缩会在完整问答之间切分，" +
                        "不会把一问一答拆散。",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            Text("系统提示词", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                supportingText = {
                    Text(
                        if (promptCustomized) {
                            "已自定义 · 工具说明不再自动更新，点下方「恢复默认」可重新跟随工具表生成"
                        } else {
                            "自动生成 · 工具增删后会自动更新，无需手动改"
                        },
                        fontSize = 11.sp
                    )
                }
            )

            OutlinedButton(
                onClick = {
                    store.systemPrompt = systemPrompt
                    promptCustomized = true
                    scope.launch { snackbarHost.showSnackbar("提示词已保存") }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("保存提示词") }

            TextButton(
                onClick = {
                    store.resetSystemPrompt()
                    promptCustomized = false
                    systemPrompt = store.systemPrompt
                    scope.launch { snackbarHost.showSnackbar("已恢复自动生成") }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("恢复默认提示词") }

            Spacer(Modifier.height(20.dp))
            Text(
                "凭据使用 Android Keystore 加密后存储，不会明文保存。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    /*
     * 清空记忆要二次确认。
     *
     * 这不是多此一举：记忆是唯一「AI 会自己往里写」的数据，用户可能已经
     * 攒了几个月的偏好，误触清掉就全没了，而且没有回收站。
     */
    if (confirmClearMemory) {
        AlertDialog(
            onDismissRequest = { confirmClearMemory = false },
            title = { Text("清空长期记忆？") },
            text = {
                Text(
                    "共 ${memoryStats.entries} 条、${memoryStats.chars} 字符，" +
                            "清空后无法恢复。如果只想删掉其中几条，可以自己编辑上面的内容再保存。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearMemory = false
                    scope.launch {
                        vm.memory.clear()
                            .onSuccess {
                                memoryText = ""
                                memoryStats = MemoryStore.Stats(0, 0)
                                vm.reloadMemory()
                                snackbarHost.showSnackbar("长期记忆已清空")
                            }
                            .onFailure {
                                snackbarHost.showSnackbar("清空失败：${it.message}")
                            }
                    }
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearMemory = false }) { Text("取消") }
            }
        )
    }

    /*
     * 删档案也要确认。
     *
     * 一次误触就是「Key、地址、参数全没了」，而且用户往往记不住那串
     * API Key 的完整内容 —— 键盘输入框里显示的是圆点，抄不下来。
     */
    if (confirmDeleteProfile) {
        AlertDialog(
            onDismissRequest = { confirmDeleteProfile = false },
            title = { Text("删除这份档案？") },
            text = {
                Text(
                    "「${current?.displayName.orEmpty()}」的地址、Key 与参数都会删掉，无法恢复。" +
                            "已有的对话记录不受影响。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteProfile = false
                    val id = current?.id
                    scope.launch {
                        val ok = id != null && vm.deleteProfile(id)
                        snackbarHost.showSnackbar(
                            if (ok) "档案已删除" else "至少要保留一份档案"
                        )
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteProfile = false }) { Text("取消") }
            }
        )
    }
}
