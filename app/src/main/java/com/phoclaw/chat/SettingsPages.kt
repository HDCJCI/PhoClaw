package com.phoclaw.chat

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/*
 * 设置页的各个二级页面。
 *
 * 每个页面自持输入状态、自己负责落盘。切页时 Compose 会销毁整个子树，
 * 所以「编辑了一半的输入」必须靠 onDispose 兜住 —— 只有模型页和记忆页
 * 有这个问题（其它页面的改动都是即时生效的开关）。
 */

// ------------------------------------------------------------------ 模型与连接

/** 离开模型页时自动落盘的快照。 */
private data class ModelDraft(
    val name: String,
    val key: String,
    val url: String,
    val model: String,
    val vision: Boolean,
    val reasoning: Boolean,
    val temp: String
)

@Composable
internal fun ModelSettingsPage(
    vm: MainViewModel,
    snackbarHost: SnackbarHostState,
    scope: CoroutineScope,
    onBack: () -> Unit
) {
    val store = vm.credentialStore
    val profileList by vm.profiles.collectAsState()
    val activeId by vm.activeProfileId.collectAsState()
    val current = profileList.firstOrNull { it.id == activeId } ?: profileList.firstOrNull()

    /*
     * 所有输入框都以「当前档案 id」为 remember 的 key。
     * 切换档案时它们自动重新载入，省掉一套「切换后重置全部字段」的手写逻辑 ——
     * 那种代码漏一个字段就会把 A 档案的 Key 带进 B 档案。
     */
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
    var showKey by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }

    /**
     * 把编辑框里的内容写回当前档案。
     *
     * 地址会被 [BaseUrlHint.normalize] 清洗，所以要用返回值回显 —— 否则用户
     * 看到的是自己填的 `/v1/chat/completions`，实际生效的却是清洗后的域名，
     * 下次进来又变一个样。
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

    /*
     * 离开页面时自动保存。
     *
     * 二级页一拆，切页就意味着整个子树的 state 被销毁 —— 用户填好 Key
     * 顺手点返回，回来发现 Key 没了，这会被当成「保存失败」。
     * 用 rememberUpdatedState 读取最新值，避免闭包捕获第一次组合时的快照。
     */
    val liveProfile = rememberUpdatedState(current)
    val liveDraft = rememberUpdatedState(
        ModelDraft(profileName, apiKey, baseUrl, model, modelSupportsVision, showReasoning, temperatureText)
    )
    DisposableEffect(Unit) {
        onDispose {
            val base = liveProfile.value ?: return@onDispose
            val d = liveDraft.value
            vm.saveProfile(
                base.copy(
                    name = d.name.trim(),
                    baseUrl = d.url,
                    apiKey = d.key,
                    model = d.model,
                    supportsVision = d.vision,
                    showReasoning = d.reasoning,
                    temperature = d.temp.toDoubleOrNull() ?: base.temperature
                )
            )
        }
    }

    SettingsPageScaffold(
        title = "模型与连接",
        onBack = onBack,
        snackbarHost = snackbarHost,
        bottomBar = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val hadRedundantPath = BaseUrlHint.hasRedundantPath(baseUrl)
                        val saved = saveDraft()
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
        }
    ) {
        HintText(
            "每份档案是一套独立的后端地址、Key 与参数。切到哪份，接下来的对话就用哪个模型 —— " +
                    "对话页点标题也能直接切。"
        )

        SectionLabel("档案")

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
                // 所有依赖它的地方都得加空判断
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

        SectionLabel("连接")

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
                    // 只收数字和小数点，最多两位小数。手动挡比滑杆好：
                    // 推理模型常要求精确填 1.0
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

        SectionLabel("模型能力")

        // 默认关闭是刻意的保守选择：多模态要求把 content 写成数组，
        // 不支持视觉的后端收到数组会直接报 400
        ToggleRow(
            title = "模型支持看图",
            subtitle = "开启后图片以 Base64 传给模型。若模型不支持视觉请保持关闭 —— " +
                    "此时只发图片的文字说明，否则部分后端会直接报错。",
            checked = modelSupportsVision,
            onChange = { modelSupportsVision = it }
        )

        ToggleRow(
            title = "显示思考过程",
            subtitle = "推理模型（deepseek-reasoner、Kimi、QwQ 等）会先输出一段内心独白。" +
                    "开启后在回答上方显示为可折叠的小块；普通模型没有这个字段，开了也不会有内容。",
            checked = showReasoning,
            onChange = { showReasoning = it }
        )
    }

    /*
     * 删档案要确认。
     *
     * 一次误触就是「Key、地址、参数全没了」，而且那串 Key 在输入框里显示为
     * 圆点，用户抄不下来。
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
                    val id = current?.id ?: return@TextButton
                    val ok = vm.deleteProfile(id)
                    scope.launch {
                        snackbarHost.showSnackbar(if (ok) "档案已删除" else "至少要保留一份档案")
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteProfile = false }) { Text("取消") }
            }
        )
    }
}

// ------------------------------------------------------------------ 上下文压缩

@Composable
internal fun ContextSettingsPage(
    vm: MainViewModel,
    snackbarHost: SnackbarHostState,
    onBack: () -> Unit
) {
    val store = vm.credentialStore
    val profileList by vm.profiles.collectAsState()
    val activeId by vm.activeProfileId.collectAsState()
    val current = profileList.firstOrNull { it.id == activeId } ?: profileList.firstOrNull()

    var autoCompress by remember { mutableStateOf(store.autoCompress) }
    // 预算随档案走，切换档案时跟着换
    var budgetText by remember(current?.id) {
        mutableStateOf((current?.contextBudget ?: CredentialStore.DEFAULT_CONTEXT_BUDGET).toString())
    }
    var keepRecentText by remember { mutableStateOf(store.keepRecent.toString()) }

    SettingsPageScaffold(
        title = "上下文压缩",
        onBack = onBack,
        snackbarHost = snackbarHost
    ) {
        HintText(
            "对话变长之后，早期消息会被压成摘要再发给模型，而不是原样全发。" +
                    "界面里的记录一条都不会少，只是模型看到的是浓缩版 —— " +
                    "不这样做的话，聊到一定长度请求会被后端直接拒绝。"
        )

        ToggleRow(
            title = "超预算时自动压缩",
            subtitle = if (autoCompress) "已开启 · 压缩会额外调用一次模型"
            else "已关闭 · 长对话最终会因超长报错",
            checked = autoCompress,
            onChange = {
                autoCompress = it
                store.autoCompress = it
            }
        )

        SectionLabel("预算")

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

        HintText(
            "预算按模型的上下文窗口估：64k 的模型填 32000 比较稳，128k 的可以填到 60000。" +
                    "估算是按字符数量级推的，留足余量比精确更重要。\n" +
                    "「保留最近」是每次压缩时不动的那几条。压缩会在完整问答之间切分，" +
                    "不会把一问一答拆散。"
        )

        HintText(
            "预算属于「${current?.displayName ?: "当前档案"}」这一份档案 —— " +
                    "不同模型的窗口从 8k 到 1M 不等，用一个全局值必然要么浪费要么溢出。",
            color = MaterialTheme.colorScheme.primary
        )
    }
}

// ------------------------------------------------------------------ 系统提示词

@Composable
internal fun PromptSettingsPage(
    vm: MainViewModel,
    snackbarHost: SnackbarHostState,
    onBack: () -> Unit
) {
    val store = vm.credentialStore
    val scope = rememberCoroutineScope()

    var systemPrompt by remember { mutableStateOf(store.systemPrompt) }
    var promptCustomized by remember { mutableStateOf(store.hasCustomSystemPrompt) }

    SettingsPageScaffold(
        title = "系统提示词",
        onBack = onBack,
        snackbarHost = snackbarHost,
        bottomBar = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        store.systemPrompt = systemPrompt
                        promptCustomized = true
                        scope.launch { snackbarHost.showSnackbar("提示词已保存，工具说明不再自动更新") }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("保存") }

                OutlinedButton(
                    onClick = {
                        store.resetSystemPrompt()
                        promptCustomized = false
                        systemPrompt = store.systemPrompt
                        scope.launch { snackbarHost.showSnackbar("已恢复自动生成") }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("恢复默认") }
            }
        }
    ) {
        HintText(
            "这段内容在每轮对话前发给模型。默认是自动生成的：工具清单、用法、规则、" +
                    "以及各功能的开关状态都从工具表里拼出来，加了新工具不用手改。\n" +
                    "一旦你点了「保存」，它就固定成你现在看到的样子，不再跟随工具表更新。"
        )

        Surface(
            color = if (promptCustomized) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (promptCustomized) "已自定义 · 新工具不会自动出现在这里"
                else "自动生成 · 工具增删后自动更新，无需手动改",
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }

        OutlinedTextField(
            value = systemPrompt,
            onValueChange = { systemPrompt = it },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 320.dp),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            supportingText = { Text("${systemPrompt.length} 字符", fontSize = 11.sp) }
        )
    }
}

// ------------------------------------------------------------------ 工具与权限

@Composable
internal fun ToolSettingsPage(
    vm: MainViewModel,
    snackbarHost: SnackbarHostState,
    scope: CoroutineScope,
    onBack: () -> Unit
) {
    val store = vm.credentialStore
    val context = LocalContext.current

    var tavilyKey by remember { mutableStateOf(store.tavilyKey) }
    var showTavily by remember { mutableStateOf(false) }
    var testingSearch by remember { mutableStateOf(false) }
    var codeExecEnabled by remember { mutableStateOf(store.codeExecEnabled) }
    var shellEnabled by remember { mutableStateOf(store.shellEnabled) }

    SettingsPageScaffold(
        title = "工具与权限",
        onBack = onBack,
        snackbarHost = snackbarHost
    ) {
        /*
         * 这一页的三个开关都是「扩大 AI 的能力边界」。
         * 和普通设置分开摆，是因为它们各自都涉及数据外发或设备权限 ——
         * 混在模型参数里，用户很容易一路划过去顺手全开。
         */
        HintText(
            "下面每一项都会扩大 AI 能做的事。默认全部关闭：" +
                    "先知道它要拿什么，再决定给不给。",
            color = MaterialTheme.colorScheme.primary
        )

        // ---------------- 联网搜索 ----------------
        SectionLabel("联网搜索")

        HintText(
            "填入 Tavily API Key 后 AI 就能在需要时联网：\n" +
                    "· websearch —— 搜索实时信息（新闻、股价、最新版本号等）\n" +
                    "· webfetch —— 抓取指定网址的正文（你贴链接让它看时用）\n" +
                    "两者都会在回答里附来源链接。另外 http 指令（发任意 HTTP 请求）" +
                    "不需要 Key，始终可用。"
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
            horizontalArrangement = Arrangement.spacedBy(8.dp)
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
            ) { Text("保存") }

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
            ) { Text(if (testingSearch) "测试中…" else "测试") }
        }

        TextButton(
            onClick = {
                // 用系统浏览器打开控制台，方便申请 Key。不内嵌 WebView：
                // 那会让用户在应用内输入第三方账号密码，反而更不安全
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://app.tavily.com"))
                    )
                }.onFailure {
                    scope.launch { snackbarHost.showSnackbar("没有可用的浏览器，请手动访问 app.tavily.com") }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("去 Tavily 申请免费 Key", fontSize = 12.sp) }

        HorizontalDivider()

        // ---------------- 代码执行 ----------------
        SectionLabel("代码执行")

        HintText(
            "开启后 AI 可以用 run 指令运行代码，支持 Python、JavaScript、C++、C、Java、" +
                    "Go、Rust、Shell 等 30+ 种语言。"
        )

        WarnText(
            "代码会发送到 wandbox.org 执行 —— 这是第三方服务，代码会离开你的设备。" +
                    "不要运行含密钥、密码或个人数据的代码。"
        )

        ToggleRow(
            title = "允许远程执行",
            subtitle = if (codeExecEnabled) "已开启 · 代码会上传到 wandbox.org"
            else "已关闭 · 代码不会离开设备",
            checked = codeExecEnabled,
            onChange = {
                codeExecEnabled = it
                store.codeExecEnabled = it
            }
        )

        HintText(
            "远程环境是隔离的，看不到工作区的其它文件；二进制文件无法运行" +
                    "（Android 10+ 禁止执行可写目录里的 ELF 文件）。"
        )

        /*
         * 鸿蒙上整块不渲染。
         *
         * 开关摆在那里却永远用不了，比没有这个功能更让人困惑 ——
         * 用户会反复点、反复失败，然后以为是 bug。
         */
        if (DeviceEnv.shellAvailable) {
            HorizontalDivider()

            SectionLabel("本机命令")

            HintText(
                "开启后 AI 可以用 shell 指令在你手机上执行命令 —— 系统自带的 " +
                        "ls、grep、find、sed、du、stat 这些，支持管道和重定向。"
            )

            WarnText(
                "命令在你自己的设备上执行，拥有与这个应用相同的权限。" +
                        "和「代码执行」相反，数据不会离开手机；但删除类命令会真的删掉东西。"
            )

            ToggleRow(
                title = "允许执行本机命令",
                subtitle = if (shellEnabled) "已开启 · AI 可以运行 shell 命令"
                else "已关闭 · AI 只能用文件读写指令",
                checked = shellEnabled,
                onChange = {
                    shellEnabled = it
                    store.shellEnabled = it
                }
            )

            HintText(
                "· 命令在应用沙箱里跑，工作区文件会镜像进去，改过的自动同步回来\n" +
                        "· 没有 python / node / gcc —— 要跑真正的代码请用「代码执行」\n" +
                        "· 单条命令 30 秒超时，输出超过 8000 字符会截断\n" +
                        "· 删除类命令（rm -rf、mkfs、pm uninstall 等）会先弹确认框\n" +
                        "· 碰不到 /sdcard 和其它应用的数据，这是系统限制，不是配置问题"
            )
        }
    }
}

// ------------------------------------------------------------------ 长期记忆

@Composable
internal fun MemorySettingsPage(
    vm: MainViewModel,
    snackbarHost: SnackbarHostState,
    scope: CoroutineScope,
    onBack: () -> Unit
) {
    val store = vm.credentialStore

    var memoryEnabled by remember { mutableStateOf(store.memoryEnabled) }
    var memoryText by remember { mutableStateOf("") }
    var memoryStats by remember { mutableStateOf(MemoryStore.Stats(0, 0)) }
    var confirmClearMemory by remember { mutableStateOf(false) }
    // 载入时的原文，用来判断「离开时到底改没改」
    var loadedText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val text = vm.memory.load()
        memoryText = text
        loadedText = text
        memoryStats = vm.memory.stats()
    }

    // 和模型页同理：编到一半点返回，改动不该凭空消失。
    // 但空文本不自动落盘 —— 清空必须走「清空」那个带确认的按钮
    val liveText = rememberUpdatedState(memoryText)
    val liveLoaded = rememberUpdatedState(loadedText)
    DisposableEffect(Unit) {
        onDispose {
            val t = liveText.value
            val base = liveLoaded.value ?: return@onDispose
            if (t.isNotBlank() && t != base) {
                scope.launch {
                    vm.memory.save(t)
                    vm.reloadMemory()
                }
            }
        }
    }

    SettingsPageScaffold(
        title = "长期记忆",
        onBack = onBack,
        snackbarHost = snackbarHost,
        bottomBar = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            vm.memory.save(memoryText)
                                .onSuccess {
                                    // 必须重新载入缓存，否则用户改完记忆、
                                    // 下一句话却没生效，会以为是功能坏了
                                    vm.reloadMemory()
                                    memoryStats = vm.memory.stats()
                                    loadedText = memoryText
                                    snackbarHost.showSnackbar("记忆已保存")
                                }
                                .onFailure {
                                    snackbarHost.showSnackbar("保存失败：${it.message}")
                                }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("保存记忆") }

                OutlinedButton(
                    onClick = { confirmClearMemory = true },
                    modifier = Modifier.weight(1f)
                ) { Text("清空", color = MaterialTheme.colorScheme.error) }
            }
        }
    ) {
        HintText(
            "AI 会把这些内容带进每一轮对话，不用每次重复交代。存在 App 私有目录里，" +
                    "换工作区也不会丢。你也可以直接对它说「记住…」，它会自己写进来。"
        )

        ToggleRow(
            title = "注入到每轮对话",
            subtitle = if (memoryEnabled) "已开启 · 记忆会影响之后所有对话"
            else "已关闭 · 内容保留但不发给模型",
            checked = memoryEnabled,
            onChange = {
                memoryEnabled = it
                store.memoryEnabled = it
            }
        )

        OutlinedTextField(
            value = memoryText,
            onValueChange = { memoryText = it },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 220.dp),
            textStyle = MaterialTheme.typography.bodySmall
                .copy(fontFamily = FontFamily.Monospace),
            placeholder = {
                Text(
                    "空的时候可以让 AI 自己写，也可以在这里手写，比如：\n" +
                            "- 用户偏好深色主题\n" +
                            "- 项目用 Kotlin 2.0，不要建议降到 1.x",
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

        HintText(
            "记忆写多了会挤占上下文。AI 自己也会往里写，定期清掉不再成立的内容 —— " +
                    "比如「这个项目已完结」之类过期的东西。"
        )
    }

    /*
     * 清空要二次确认。
     *
     * 这不是多此一举：记忆是唯一「AI 会自己往里写」的数据，用户可能已经
     * 攒了几个月的偏好，误触清掉就全没了，也没有回收站。
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
                                loadedText = ""
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
}

// ------------------------------------------------------------------ 关于

@Composable
internal fun AboutSettingsPage(onBack: () -> Unit) {
    SettingsPageScaffold(
        title = "关于",
        onBack = onBack,
        snackbarHost = remember { SnackbarHostState() }
    ) {
        SectionLabel("版本")
        Text("PhoClaw v${appVersion()}", fontSize = 15.sp)
        HintText("一个只有六个文件也能干活的手机 AI 助手。")

        HorizontalDivider()

        /*
         * 数据去向汇总。
         *
         * 这个应用同时连着四五个外部服务，每个开关都意味着「哪些数据会离开设备」。
         * 散在各页的说明里用户拼不起来，所以在这里一次性列清楚。
         */
        SectionLabel("数据去哪了")

        DataFlow(
            title = "对话内容",
            target = "你填的模型后端",
            detail = "每轮请求都带完整上下文（含系统提示词与记忆）。用的是哪家，取决于当前档案。"
        )
        DataFlow(
            title = "图片",
            target = "你填的模型后端",
            detail = "仅在「模型支持看图」打开时以 Base64 上送；关闭时只发文字说明。"
        )
        DataFlow(
            title = "联网搜索 · 网页抓取",
            target = "Tavily / 目标网站",
            detail = "搜索词会发给 Tavily；抓取的网址会直接请求。两者只有填了 Key 才可用。"
        )
        DataFlow(
            title = "运行的代码",
            target = "wandbox.org",
            detail = "仅在「代码执行」打开时。远程环境隔离，但代码确实离开了设备。"
        )
        DataFlow(
            title = "本机命令",
            target = "只在设备上",
            detail = "不联网。工作区文件会镜像到应用沙箱，改动的同步回来。"
        )
        DataFlow(
            title = "长期记忆",
            target = "App 私有目录",
            detail = "存在 files/MEMORY.md，不随工作区走，也不会上传。"
        )
        DataFlow(
            title = "工作区文件",
            target = "只在你选的目录内",
            detail = "应用没有其它目录的访问权，这是 SAF 的系统限制。"
        )

        HorizontalDivider()

        SectionLabel("凭据")
        HintText(
            "API Key 与 Tavily Key 使用 Android Keystore 加密后存放在私有 SharedPreferences 里，" +
                    "不会明文保存，也不会随备份导出（备份规则已排除）。"
        )

        SectionLabel("已知限制")
        HintText(
            "· 二进制文件无法在本机运行（Android 10+ 的 W^X 策略）\n" +
                    "· 鸿蒙设备上不提供本机命令\n" +
                    "· 上下文预算是按字符估算的，误差约 ±30%"
        )

        Spacer(Modifier.height(8.dp))
    }
}

/** 「数据流向」的一行。 */
@Composable
private fun DataFlow(title: String, target: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(
                "→ $target",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            detail,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
