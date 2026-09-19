package com.phoclaw.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoclaw.chat.util.MarkdownParser
import com.phoclaw.chat.util.MarkdownParser.Block
import com.phoclaw.chat.util.MarkdownParser.Span

/**
 * 把 Markdown 文本渲染成 Compose 组件。
 *
 * 针对聊天气泡做了尺寸适配：字号偏小、间距紧凑、代码块横向可滚动。
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 14.sp
) {
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block -> BlockView(block, fontSize) }
    }
}

@Composable
private fun BlockView(block: Block, fontSize: androidx.compose.ui.unit.TextUnit) {
    when (block) {
        is Block.Paragraph -> Text(
            text = inline(block.text),
            fontSize = fontSize,
            lineHeight = fontSize * 1.45f
        )

        is Block.Heading -> {
            val size = when (block.level) {
                1 -> fontSize * 1.35f
                2 -> fontSize * 1.2f
                else -> fontSize * 1.08f
            }
            Text(
                text = inline(block.text),
                fontSize = size,
                fontWeight = FontWeight.Bold,
                lineHeight = size * 1.4f
            )
        }

        is Block.CodeBlock -> {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp, MaterialTheme.colorScheme.outlineVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(9.dp)) {
                    if (block.language.isNotBlank()) {
                        Text(
                            block.language.uppercase(),
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(
                        text = block.code,
                        fontFamily = FontFamily.Monospace,
                        fontSize = fontSize * 0.85f,
                        lineHeight = fontSize * 1.25f,
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .fillMaxWidth()
                    )
                }
            }
        }

        is Block.ListItem -> Row(Modifier.fillMaxWidth()) {
            Text(
                text = if (block.ordered) "${block.index}." else "•",
                fontSize = fontSize,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.width(22.dp)
            )
            Text(
                text = inline(block.text),
                fontSize = fontSize,
                lineHeight = fontSize * 1.45f,
                modifier = Modifier.weight(1f)
            )
        }

        is Block.Quote -> Row(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .width(3.dp)
                    .heightIn(min = 18.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        RoundedCornerShape(2.dp)
                    )
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = inline(block.text),
                fontSize = fontSize * 0.95f,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontStyle = FontStyle.Italic,
                lineHeight = fontSize * 1.4f,
                modifier = Modifier.weight(1f)
            )
        }

        is Block.Table -> MarkdownTable(block, fontSize)

        Block.Divider -> Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
    }
}

/** 表格：列宽按内容自适应，整体可横向滚动。 */
@Composable
private fun MarkdownTable(table: Block.Table, fontSize: androidx.compose.ui.unit.TextUnit) {
    val colCount = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
    if (colCount == 0) return

    // 估算每列宽度：取该列所有单元格的字符数上限
    val widths = (0 until colCount).map { c ->
        val headerLen = table.header.getOrNull(c)?.length ?: 0
        val bodyMax = table.rows.maxOfOrNull { it.getOrNull(c)?.length ?: 0 } ?: 0
        maxOf(headerLen, bodyMax).coerceIn(6, 24)
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp)
        ) {
            // 表头
            Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
                table.header.forEachIndexed { i, cell ->
                    TableCell(
                        text = cell,
                        widthChars = widths.getOrElse(i) { 8 },
                        fontSize = fontSize,
                        bold = true
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            // 数据行
            table.rows.forEach { row ->
                Row {
                    (0 until colCount).forEach { i ->
                        TableCell(
                            text = row.getOrNull(i).orEmpty(),
                            widthChars = widths.getOrElse(i) { 8 },
                            fontSize = fontSize,
                            bold = false
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TableCell(
    text: String,
    widthChars: Int,
    fontSize: androidx.compose.ui.unit.TextUnit,
    bold: Boolean
) {
    Text(
        text = inline(text),
        fontSize = fontSize * 0.9f,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        lineHeight = fontSize * 1.3f,
        modifier = Modifier
            .width((widthChars * 8).dp)
            .padding(horizontal = 7.dp, vertical = 5.dp)
    )
}

/** 把行内片段组合成 AnnotatedString。 */
@Composable
private fun inline(text: String): AnnotatedString {
    val spans = remember(text) { MarkdownParser.parseInline(text) }
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary

    return remember(spans, codeBg, linkColor) {
        buildAnnotatedString {
            spans.forEach { span ->
                when (span) {
                    is Span.Plain -> append(span.text)
                    is Span.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(span.text)
                    }
                    is Span.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(span.text)
                    }
                    is Span.Code -> withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = codeBg,
                            fontSize = 13.sp
                        )
                    ) { append(span.text) }
                    is Span.Link -> withStyle(
                        SpanStyle(color = linkColor, fontWeight = FontWeight.Medium)
                    ) { append(span.text) }
                }
            }
        }
    }
}
