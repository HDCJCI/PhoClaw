package com.phoclaw.chat.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoclaw.chat.data.FileChange
import com.phoclaw.chat.data.TurnChanges
import com.phoclaw.chat.util.LineDiff

/**
 * 一轮对话改了哪些文件 —— 汇总 + 红绿 diff + 撤销。
 *
 * ## 为什么放在气泡外面单独一张卡
 *
 * 工具的原始日志（`已写入 a.txt（123 字节）`）已经在气泡下方那块等宽文字里了。
 * 但那段文字解决不了「改得对不对」：用户真正想问的是「它把我的代码改成什么样了」。
 * 那需要 before/after 的逐行对照，不是一句「已写入」。
 *
 * ## 为什么默认展开
 *
 * 折叠起来只显示「修改 3 个文件 +42 −7」的话，用户得每轮点开一次 ——
 * 而 AI 改文件恰恰是最该被看见的动作。默认展开、每个文件只看改动前后各两行，
 * 长度可控，扫一眼就知道动的是哪里。真要细看再往上翻。
 */
@Composable
fun ChangesCard(
    changes: List<FileChange>,
    undoEnabled: Boolean,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (changes.isEmpty()) return
    val turn = remember(changes) { TurnChanges(changes) }
    var expanded by remember(changes) { mutableStateOf(true) }

    // diff 是 O(n·m)，必须缓存。每次重组都重算的话，滚动时几十个卡片
    // 同时重组会把主线程拖住
    val diffs = remember(changes) {
        changes.map { change -> change to buildDiff(change) }
    }

    val dark = isSystemInDarkTheme()

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = modifier.widthIn(max = 360.dp)
    ) {
        Column(
            Modifier
                .padding(10.dp)
                .animateContentSize()
        ) {
            // ---------------- 头部汇总 ----------------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.EditNote,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(6.dp))
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "修改 ${turn.fileCount} 个文件",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (turn.added > 0) {
                        Spacer(Modifier.width(6.dp))
                        Text("+${turn.added}", fontSize = 12.sp, color = diffAddText(dark),
                            fontWeight = FontWeight.Medium)
                    }
                    if (turn.removed > 0) {
                        Spacer(Modifier.width(4.dp))
                        Text("−${turn.removed}", fontSize = 12.sp, color = diffDelText(dark),
                            fontWeight = FontWeight.Medium)
                    }
                }

                if (turn.canUndo) {
                    TextButton(
                        onClick = onUndo,
                        enabled = undoEnabled,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(
                            Icons.Default.Undo,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(3.dp))
                        Text("撤销", fontSize = 11.sp)
                    }
                }

                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { expanded = !expanded },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!expanded) return@Column

            Spacer(Modifier.height(8.dp))

            // ---------------- 每个文件的 diff ----------------
            diffs.forEachIndexed { index, (change, lines) ->
                if (index > 0) Spacer(Modifier.height(10.dp))
                FileDiffBlock(change, lines, dark)
            }

            if (!turn.canUndo) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "有文件超过 512 KB 或不是文本，没有备份，无法撤销。",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun FileDiffBlock(change: FileChange, lines: List<LineDiff.Line>, dark: Boolean) {
    Column {
        // 路径行：A / M / D 角标 + 相对路径
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (badge, badgeColor) = when (change.kind) {
                FileChange.Kind.CREATE -> "A" to diffAddText(dark)
                FileChange.Kind.MODIFY -> "M" to MaterialTheme.colorScheme.primary
                FileChange.Kind.DELETE -> "D" to diffDelText(dark)
            }
            Surface(
                color = badgeColor.copy(alpha = 0.16f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    badge,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = badgeColor,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                change.path,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(Modifier.height(4.dp))

        if (change.truncated) {
            Text(
                "文件过大，只记录了路径，没有保存内容。",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }

        if (lines.isEmpty()) {
            Text("内容没有变化。", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 4.dp)
            ) {
                lines.forEach { line -> DiffRow(line, dark) }
            }
        }
    }
}

/**
 * 一行 diff。
 *
 * 行号列用固定宽度、低对比度 —— 它的作用是「定位」，不是「阅读」，
 * 抢了正文的注意力就本末倒置了。
 */
@Composable
private fun DiffRow(line: LineDiff.Line, dark: Boolean) {
    val bg = when (line.kind) {
        LineDiff.Kind.ADD -> diffAddBg(dark)
        LineDiff.Kind.DEL -> diffDelBg(dark)
        LineDiff.Kind.SAME -> Color.Transparent
    }
    val prefix = when (line.kind) {
        LineDiff.Kind.ADD -> "+"
        LineDiff.Kind.DEL -> "−"
        LineDiff.Kind.SAME -> " "
    }
    val textColor = when (line.kind) {
        LineDiff.Kind.ADD -> diffAddText(dark)
        LineDiff.Kind.DEL -> diffDelText(dark)
        LineDiff.Kind.SAME -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .background(bg)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = prefix,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            lineHeight = 15.sp,
            color = textColor,
            modifier = Modifier.width(10.dp)
        )
        Text(
            text = (line.text.ifEmpty { " " }),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            lineHeight = 15.sp,
            color = textColor,
            maxLines = 1
        )
    }
}

/** 单个文件的 diff 行。超过上限就截断，避免一个新文件把整页撑开。 */
private fun buildDiff(change: FileChange): List<LineDiff.Line> {
    if (change.truncated) return emptyList()
    val raw = LineDiff.compute(change.before.orEmpty(), change.after.orEmpty())
    val withCtx = LineDiff.withContext(raw, context = 2)
    return if (withCtx.size <= MAX_DIFF_ROWS) withCtx
    else withCtx.take(MAX_DIFF_ROWS) + LineDiff.Line(
        LineDiff.Kind.SAME,
        "… 还有 ${withCtx.size - MAX_DIFF_ROWS} 行未显示"
    )
}

private const val MAX_DIFF_ROWS = 120

/*
 * 红绿配色。
 *
 * 不用 MaterialTheme 的 errorContainer：那个红色是「出错了」的语义，
 * 而删除一行代码并不总是错误。这里用的是独立的告警色（偏暖的红、偏冷的绿），
 * 底色极淡、只在文字上给足对比度 —— 底色一重，代码就完全看不清了。
 */
private fun diffAddBg(dark: Boolean): Color =
    if (dark) Color(0xFF2EA043).copy(alpha = 0.18f) else Color(0xFF2EA043).copy(alpha = 0.12f)

private fun diffDelBg(dark: Boolean): Color =
    if (dark) Color(0xFFF85149).copy(alpha = 0.16f) else Color(0xFFF85149).copy(alpha = 0.10f)

private fun diffAddText(dark: Boolean): Color =
    if (dark) Color(0xFF6FDD8B) else Color(0xFF0F7B33)

private fun diffDelText(dark: Boolean): Color =
    if (dark) Color(0xFFFF9490) else Color(0xFFB3261E)
