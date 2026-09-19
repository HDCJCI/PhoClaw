package com.phoclaw.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoclaw.chat.data.ConversationMeta

/**
 * 历史会话列表。
 *
 * 条目点击进入该会话，右侧图标删除（删除前二次确认）。
 * 「清空全部」放在顶栏溢出菜单里——这是不可逆操作，不该太容易点到。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    vm: MainViewModel,
    onBack: () -> Unit
) {
    val history by vm.history.collectAsState()
    var pendingDelete by remember { mutableStateOf<ConversationMeta?>(null) }
    var confirmClearAll by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refreshHistory() }

    // 删除单个会话的确认
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            icon = { Icon(Icons.Default.DeleteOutline, contentDescription = null) },
            title = { Text("删除会话") },
            text = {
                Column {
                    Text("将永久删除这个会话的全部消息：", fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        target.title,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteConversation(target.id)
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

    // 清空全部的确认
    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
            title = { Text("清空全部历史") },
            text = {
                Text(
                    "将删除 ${history.size} 个会话的全部记录，此操作不可撤销。工作区文件不受影响。",
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.clearAllConversations()
                        confirmClearAll = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("全部清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearAll = false }) { Text("取消") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("历史对话", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        Text(
                            "${history.size} 个会话",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("清空全部历史") },
                                onClick = {
                                    menuOpen = false
                                    confirmClearAll = true
                                },
                                enabled = history.isNotEmpty(),
                                leadingIcon = {
                                    Icon(Icons.Default.DeleteSweep, contentDescription = null)
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (history.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.History,
                        contentDescription = null,
                        modifier = Modifier.size(52.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("还没有历史对话", fontSize = 14.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "聊过之后会自动保存在这里",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = 6.dp)
        ) {
            items(history, key = { it.id }) { item ->
                ConversationRow(
                    meta = item,
                    onClick = { vm.openConversation(item.id); onBack() },
                    onDelete = { pendingDelete = item }
                )
                HorizontalDivider(modifier = Modifier.padding(start = 68.dp))
            }
        }
    }
}

@Composable
private fun ConversationRow(
    meta: ConversationMeta,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.ChatBubbleOutline,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = meta.title.ifBlank { "新对话" },
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "${formatTimestamp(meta.updatedAt)} · ${meta.messageCount} 条消息",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.DeleteOutline,
                contentDescription = "删除",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 把时间戳渲染成「今天 14:30 / 昨天 09:12 / 3月5日」这种人话。 */
private fun formatTimestamp(millis: Long): String {
    if (millis <= 0L) return "未知时间"
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = millis }

    val sameYear = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR)
    val hhmm = "%02d:%02d".format(
        then.get(java.util.Calendar.HOUR_OF_DAY),
        then.get(java.util.Calendar.MINUTE)
    )

    // 用「日期序数」比较，避免手写跨月/跨年判断
    val dayDiff = dayIndex(now) - dayIndex(then)
    return when {
        dayDiff == 0L -> "今天 $hhmm"
        dayDiff == 1L -> "昨天 $hhmm"
        dayDiff in 2L..6L -> "${dayDiff}天前"
        sameYear -> "%d月%d日".format(
            then.get(java.util.Calendar.MONTH) + 1,
            then.get(java.util.Calendar.DAY_OF_MONTH)
        )
        else -> "%d年%d月%d日".format(
            then.get(java.util.Calendar.YEAR),
            then.get(java.util.Calendar.MONTH) + 1,
            then.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }
}

/** 把日期折算成「距离公元元年的天数」，只用于做差。 */
private fun dayIndex(calendar: java.util.Calendar): Long {
    val copy = calendar.clone() as java.util.Calendar
    copy.set(java.util.Calendar.HOUR_OF_DAY, 0)
    copy.set(java.util.Calendar.MINUTE, 0)
    copy.set(java.util.Calendar.SECOND, 0)
    copy.set(java.util.Calendar.MILLISECOND, 0)
    return copy.timeInMillis / 86_400_000L
}
