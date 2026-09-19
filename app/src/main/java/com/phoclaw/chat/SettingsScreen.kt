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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    var apiKey by remember { mutableStateOf(store.apiKey) }
    var baseUrl by remember { mutableStateOf(store.baseUrl) }
    var model by remember { mutableStateOf(store.model) }
    var modelSupportsVision by remember { mutableStateOf(store.modelSupportsVision) }
    var systemPrompt by remember { mutableStateOf(store.systemPrompt) }
    var showKey by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }

    // 联网搜索
    var tavilyKey by remember { mutableStateOf(store.tavilyKey) }
    var showTavily by remember { mutableStateOf(false) }
    var testingSearch by remember { mutableStateOf(false) }

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

            Text("API 配置", style = MaterialTheme.typography.titleMedium)

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

            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("模型名") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = {
                    Text("如 deepseek-chat / gpt-4o-mini / qwen-plus", fontSize = 11.sp)
                }
            )

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

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val hadRedundantPath = BaseUrlHint.hasRedundantPath(baseUrl)
                        store.apiKey = apiKey
                        store.baseUrl = baseUrl
                        store.model = model
                        store.modelSupportsVision = modelSupportsVision
                        // 回显清洗后的地址，让用户看到实际生效的值
                        baseUrl = store.baseUrl
                        scope.launch {
                            snackbarHost.showSnackbar(
                                if (hadRedundantPath) "已保存，地址已自动清理为 ${store.baseUrl}"
                                else "已保存"
                            )
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("保存") }

                OutlinedButton(
                    onClick = {
                        store.apiKey = apiKey
                        store.baseUrl = baseUrl
                        store.model = model
                        store.modelSupportsVision = modelSupportsVision
                        baseUrl = store.baseUrl
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

            // ---------------- 联网搜索 ----------------

            Text("联网搜索", style = MaterialTheme.typography.titleMedium)

            Text(
                "填入 Tavily API Key 后，AI 就能在需要时调用 `websearch` 指令\n" +
                        "查询实时信息（新闻、股价、最新版本号等），并在回答里附上来源链接。",
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
                ) { Text("保存搜索设置") }

                OutlinedButton(
                    onClick = {
                        store.tavilyKey = tavilyKey
                        tavilyKey = store.tavilyKey
                        testingSearch = true
                        scope.launch {
                            val result = vm.testSearch()
                            testingSearch = false
                            snackbarHost.showSnackbar(
                                result.fold({ "搜索可用：$it" }, { "失败：${it.message}" })
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

            Text("系统提示词", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                supportingText = { Text("模型据此理解 phoclaw 指令格式，非必要不要改动", fontSize = 11.sp) }
            )

            OutlinedButton(
                onClick = {
                    store.systemPrompt = systemPrompt
                    scope.launch { snackbarHost.showSnackbar("提示词已保存") }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("保存提示词") }

            TextButton(
                onClick = {
                    systemPrompt = com.phoclaw.chat.data.CredentialStore.DEFAULT_SYSTEM_PROMPT
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
}
