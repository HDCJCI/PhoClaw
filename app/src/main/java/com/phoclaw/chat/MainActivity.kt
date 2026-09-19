package com.phoclaw.chat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.phoclaw.chat.auto.Notifications
import com.phoclaw.chat.ui.theme.PhoClawTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 从任务执行结果通知点进来时，带上要打开的会话
        val openConversationId = intent?.getStringExtra(Notifications.EXTRA_OPEN_TASK_ID)

        setContent {
            PhoClawTheme {
                AppRoot(pendingConversationId = openConversationId)
            }
        }
    }
}

/**
 * 底部导航项。
 *
 * 用枚举而不是字符串，是因为 `when` 分支能靠编译器穷尽检查兜住 ——
 * 以后加页面时忘了处理其中一个，编译期就会报错，不会留到运行时白屏。
 */
private enum class Tab(val label: String, val icon: ImageVector) {
    CHAT("对话", Icons.AutoMirrored.Filled.Chat),
    FILES("文件", Icons.Default.Folder),
    SKILLS("技能", Icons.Default.Extension),
    AUTOMATION("自动化", Icons.Default.Schedule),
    SETTINGS("设置", Icons.Default.Settings)
}

/** 对话页内部的二级状态：正常聊天 / 历史列表。 */
private enum class ChatOverlay { NONE, HISTORY }

@Composable
private fun AppRoot(pendingConversationId: String? = null) {
    // 用同一个 ViewModel 实例贯通所有页面，避免跨页面状态丢失
    val activity = LocalContext.current as ComponentActivity
    val vm: MainViewModel = viewModel(viewModelStoreOwner = activity)

    var tab by remember { mutableStateOf(Tab.CHAT) }
    var chatOverlay by remember { mutableStateOf(ChatOverlay.NONE) }

    // 「查看执行记录」这类跨页跳转：界面层只发信号，切标签由这里做
    val openChatSignal by vm.openChatSignal.collectAsState()
    LaunchedEffect(openChatSignal) {
        if (openChatSignal > 0) {
            chatOverlay = ChatOverlay.NONE
            tab = Tab.CHAT
        }
    }

    // 从通知进入时切到对话页并打开对应会话
    remember(pendingConversationId) {
        if (!pendingConversationId.isNullOrBlank()) {
            vm.openConversationByTask(pendingConversationId)
        }
        true
    }

    // 应用退到后台前把当前会话落盘，避免进程被系统回收时丢掉最后几轮对话；
    // 顺带清一次无人引用的附件文件
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                vm.flushConversation()
                vm.collectAttachmentGarbage()
            }
            if (event == Lifecycle.Event.ON_RESUME) {
                // 用户可能刚从系统设置里授权完精确闹钟回来，回到前台时校准一次
                vm.refreshExactAlarmState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        /*
         * 内层各页自己的 Scaffold 已经会处理 inset（它们都有 TopAppBar），
         * 外层这里把 inset 全部置零，避免「外层让一次、内层再让一次」。
         *
         * 历史教训：
         *   v1.6.1 让底栏吃 safeDrawing → 底栏与内容各让一次，中间空一条
         *   v1.6.2 手工 max(底栏高, 键盘高) → 数值算错，内容被挤出屏幕
         * 结论是别自己算。inset 只在一处消费 —— 这里选内层（因为它离输入框更近，
         * 且各页的 topBar 也需要自己那份状态栏高度）。
         */
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = {
                            // 从别的页切回对话时，顺手退出历史列表 ——
                            // 否则用户点「对话」看到的还是历史列表，会以为没反应
                            if (item == Tab.CHAT) chatOverlay = ChatOverlay.NONE
                            tab = item
                        },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label, fontSize = 11.sp) }
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = padding.calculateBottomPadding())
                /*
                 * 外层让开「底栏 + 键盘」，内层各页不再各自处理键盘。
                 *
                 * consumeWindowInsets(padding) 是必须的：它把底栏已经占掉的那部分
                 * inset 标记为「已消费」，这样紧接着的 imePadding() 只会补上
                 * **差值**（键盘比底栏高的那部分），而不是把两者相加。
                 * 这正是前面两版翻车的地方 —— 少这一步就变成了求和。
                 */
                .consumeWindowInsets(padding)
                .imePadding()
        ) {
            when (tab) {
                Tab.CHAT -> if (chatOverlay == ChatOverlay.HISTORY) {
                    HistoryScreen(vm = vm, onBack = { chatOverlay = ChatOverlay.NONE })
                } else {
                    ChatScreen(
                        vm = vm,
                        onOpenHistory = { chatOverlay = ChatOverlay.HISTORY }
                    )
                }

                Tab.FILES -> WorkspaceScreen(vm = vm)
                Tab.SKILLS -> SkillsScreen(vm = vm)
                Tab.AUTOMATION -> AutomationScreen(vm = vm)
                Tab.SETTINGS -> SettingsScreen(vm = vm)
            }
        }
    }
}
