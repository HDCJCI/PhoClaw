package com.phoclaw.chat.util

/**
 * 极简 Markdown 解析器。
 *
 * 只覆盖 AI 回复中实际会出现的语法，不追求 CommonMark 完全合规：
 * - 代码块 ```lang ... ```
 * - 标题 # ## ###
 * - 有序 / 无序列表、任务列表 - [ ] / - [x]
 * - 引用 >
 * - 分隔线 ---
 * - 表格 | a | b |
 * - 图片 ![alt](url)
 * - 段落内的 **粗体**、*斜体*、***粗斜体***、~~删除线~~、<u>下划线</u>、
 *   `行内代码`、[链接](url)
 *
 * 设计成「先切块、再切行内」两级结构，渲染时逐块处理。
 */
object MarkdownParser {

    /** 块级元素。 */
    sealed interface Block {
        /** 普通段落，内部还可能含行内标记。 */
        data class Paragraph(val text: String) : Block

        /** 标题，[level] 为 1~3。 */
        data class Heading(val level: Int, val text: String) : Block

        /** 代码块，[language] 可为空。 */
        data class CodeBlock(val language: String, val code: String) : Block

        /** 列表项，[ordered] 区分有序无序，[index] 是有序列表的序号。 */
        data class ListItem(val text: String, val ordered: Boolean, val index: Int) : Block

        /**
         * 任务列表项。
         * [checked] 为 true 表示 `- [x]`，false 表示 `- [ ]`。
         */
        data class TaskItem(val text: String, val checked: Boolean) : Block

        /** 引用块。 */
        data class Quote(val text: String) : Block

        /** 表格。 */
        data class Table(val header: List<String>, val rows: List<List<String>>) : Block

        /** 分隔线。 */
        data object Divider : Block
    }

    /** 行内标记片段。 */
    sealed interface Span {
        data class Plain(val text: String) : Span
        data class Bold(val text: String) : Span
        data class Italic(val text: String) : Span

        /** 粗斜体（***text***）。 */
        data class BoldItalic(val text: String) : Span

        /** 删除线（~~text~~）。 */
        data class Strikethrough(val text: String) : Span

        /** 下划线（<u>text</u>）。 */
        data class Underline(val text: String) : Span

        data class Code(val text: String) : Span
        data class Link(val text: String, val url: String) : Span

        /** 图片（![alt](url)）。 */
        data class Image(val alt: String, val url: String) : Span
    }

    // ------------------------------------------------------------------ 块级解析

    fun parse(markdown: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val lines = markdown.replace("\r\n", "\n").split('\n')
        var i = 0

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            when {
                // 代码块
                trimmed.startsWith("```") -> {
                    val lang = trimmed.removePrefix("```").trim()
                    val body = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) {
                        if (body.isNotEmpty()) body.append('\n')
                        body.append(lines[i])
                        i++
                    }
                    i++ // 跳过结束的 ```
                    blocks += Block.CodeBlock(lang, body.toString())
                }

                // 标题
                trimmed.matches(Regex("^#{1,3}\\s+.*")) -> {
                    val level = trimmed.takeWhile { it == '#' }.length
                    blocks += Block.Heading(level, trimmed.drop(level).trim())
                    i++
                }

                // 分隔线
                trimmed.matches(Regex("^(-{3,}|\\*{3,}|_{3,})$")) -> {
                    blocks += Block.Divider
                    i++
                }

                // 表格：当前行含 | 且下一行是分隔行
                trimmed.contains('|') && i + 1 < lines.size &&
                        lines[i + 1].trim().matches(Regex("^\\|?[\\s:-]*-[\\s:|-]*\\|?$")) -> {
                    val header = splitRow(trimmed)
                    i += 2
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && lines[i].trim().contains('|')) {
                        rows += splitRow(lines[i].trim())
                        i++
                    }
                    blocks += Block.Table(header, rows)
                }

                // 引用
                trimmed.startsWith(">") -> {
                    val buf = mutableListOf<String>()
                    while (i < lines.size && lines[i].trim().startsWith(">")) {
                        buf += lines[i].trim().removePrefix(">").trim()
                        i++
                    }
                    blocks += Block.Quote(buf.joinToString("\n"))
                }

                // 任务列表：- [ ] / - [x] / * [X] / + [ ]
                // 必须排在普通列表判断**之前**，否则会被当成普通列表项，
                // 文字里残留 "[ ]" 字样。
                trimmed.matches(Regex("^[-*+]\\s+\\[[ xX]]\\s+.*")) -> {
                    val buf = mutableListOf<Block.TaskItem>()
                    while (i < lines.size) {
                        val t = lines[i].trim()
                        val m = Regex("^[-*+]\\s+\\[([ xX])]\\s+(.*)$").find(t) ?: break
                        buf += Block.TaskItem(
                            text = m.groupValues[2],
                            checked = m.groupValues[1].equals("x", ignoreCase = true)
                        )
                        i++
                    }
                    blocks += buf
                }

                // 列表
                trimmed.matches(Regex("^([-*+]|\\d+\\.)\\s+.*")) -> {
                    val buf = mutableListOf<Block.ListItem>()
                    while (i < lines.size && lines[i].trim().matches(Regex("^([-*+]|\\d+\\.)\\s+.*"))) {
                        val t = lines[i].trim()
                        val orderedMatch = Regex("^(\\d+)\\.\\s+(.*)$").find(t)
                        if (orderedMatch != null) {
                            buf += Block.ListItem(
                                orderedMatch.groupValues[2], ordered = true,
                                index = orderedMatch.groupValues[1].toIntOrNull() ?: 1
                            )
                        } else {
                            buf += Block.ListItem(
                                t.replaceFirst(Regex("^[-*+]\\s+"), ""),
                                ordered = false, index = 0
                            )
                        }
                        i++
                    }
                    blocks += buf
                }

                // 空行
                trimmed.isEmpty() -> i++

                // 段落：连续非空行合并
                else -> {
                    val buf = mutableListOf<String>()
                    while (i < lines.size && lines[i].trim().isNotEmpty() &&
                        !isBlockStart(lines[i].trim(), lines, i)
                    ) {
                        buf += lines[i].trim()
                        i++
                    }
                    if (buf.isNotEmpty()) blocks += Block.Paragraph(buf.joinToString("\n"))
                    else i++   // 兜底，防止死循环
                }
            }
        }
        return blocks
    }

    /** 判断某行是否是新块的开始，用于界定段落边界。 */
    private fun isBlockStart(trimmed: String, lines: List<String>, index: Int): Boolean {
        if (trimmed.startsWith("```")) return true
        if (trimmed.matches(Regex("^#{1,3}\\s+.*"))) return true
        if (trimmed.matches(Regex("^(-{3,}|\\*{3,}|_{3,})$"))) return true
        if (trimmed.startsWith(">")) return true
        if (trimmed.matches(Regex("^[-*+]\\s+\\[[ xX]]\\s+.*"))) return true
        if (trimmed.matches(Regex("^([-*+]|\\d+\\.)\\s+.*"))) return true
        if (trimmed.contains('|') && index + 1 < lines.size &&
            lines[index + 1].trim().matches(Regex("^\\|?[\\s:-]*-[\\s:|-]*\\|?$"))
        ) return true
        return false
    }

    /** 拆分表格行，去掉首尾多余的竖线。 */
    private fun splitRow(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|")
            .split('|')
            .map { it.trim() }

    // ------------------------------------------------------------------ 行内解析

    /**
     * 解析行内标记，返回片段序列。
     *
     * 用一次线性扫描按优先级匹配，避免正则回溯导致的性能问题。
     * 匹配顺序很关键：*** 要在 ** 之前、** 要在 * 之前，
     * 否则 ***x*** 会被切成「粗体 + *x*」这种错误结构。
     */
    fun parseInline(text: String): List<Span> {
        val spans = mutableListOf<Span>()
        val plain = StringBuilder()
        var i = 0

        fun flush() {
            if (plain.isNotEmpty()) {
                spans += Span.Plain(plain.toString())
                plain.clear()
            }
        }

        while (i < text.length) {
            when {
                // 转义：\* 这类字符原样输出，不参与标记解析
                text[i] == '\\' && i + 1 < text.length && text[i + 1] in ESCAPABLE -> {
                    plain.append(text[i + 1]); i += 2
                }

                // 行内代码：优先级最高，内部不再解析其他标记
                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        flush()
                        spans += Span.Code(text.substring(i + 1, end))
                        i = end + 1
                    } else {
                        plain.append(text[i]); i++
                    }
                }

                // 图片 ![alt](url) —— 必须排在链接之前，否则会先被 [ 匹配掉
                text.startsWith("![", i) -> {
                    val parsed = readLinkOrImage(text, i + 1)
                    if (parsed != null) {
                        flush()
                        spans += Span.Image(alt = parsed.first, url = parsed.second)
                        i = parsed.third
                    } else {
                        plain.append(text[i]); i++
                    }
                }

                // 粗斜体 ***text***
                text.startsWith("***", i) -> {
                    val end = text.indexOf("***", i + 3)
                    if (end > i + 2) {
                        flush()
                        spans += Span.BoldItalic(text.substring(i + 3, end))
                        i = end + 3
                    } else {
                        plain.append(text[i]); i++
                    }
                }

                // 粗体 **text**
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i + 1) {
                        flush()
                        spans += Span.Bold(text.substring(i + 2, end))
                        i = end + 2
                    } else {
                        plain.append(text[i]); i++
                    }
                }

                // 删除线 ~~text~~
                text.startsWith("~~", i) -> {
                    val end = text.indexOf("~~", i + 2)
                    if (end > i + 1) {
                        flush()
                        spans += Span.Strikethrough(text.substring(i + 2, end))
                        i = end + 2
                    } else {
                        plain.append(text[i]); i++
                    }
                }

                // 下划线 <u>text</u>
                text.startsWith("<u>", i, ignoreCase = true) -> {
                    val end = text.indexOf("</u>", i + 3, ignoreCase = true)
                    if (end > i) {
                        flush()
                        spans += Span.Underline(text.substring(i + 3, end))
                        i = end + 4
                    } else {
                        plain.append(text[i]); i++
                    }
                }

                // 斜体 *text*（排除 ** 的情况）
                text[i] == '*' && (i + 1 >= text.length || text[i + 1] != '*') -> {
                    val end = text.indexOf('*', i + 1)
                    if (end > i + 1 && text.getOrNull(end + 1) != '*') {
                        flush()
                        spans += Span.Italic(text.substring(i + 1, end))
                        i = end + 1
                    } else {
                        plain.append(text[i]); i++
                    }
                }

                // 链接 [text](url)
                text[i] == '[' -> {
                    val parsed = readLinkOrImage(text, i)
                    if (parsed != null) {
                        flush()
                        spans += Span.Link(text = parsed.first, url = parsed.second)
                        i = parsed.third
                    } else {
                        plain.append(text[i]); i++
                    }
                }

                else -> {
                    plain.append(text[i]); i++
                }
            }
        }
        flush()
        return spans
    }

    /**
     * 从 [start]（指向 `[`）开始读取 `[文本](地址)`。
     *
     * @return 三元组 (显示文本, 地址, 下一个待处理下标)；语法不完整时返回 null。
     */
    private fun readLinkOrImage(text: String, start: Int): Triple<String, String, Int>? {
        if (text.getOrNull(start) != '[') return null
        val closeBracket = text.indexOf(']', start + 1)
        if (closeBracket <= start) return null
        if (text.getOrNull(closeBracket + 1) != '(') return null
        val closeParen = text.indexOf(')', closeBracket + 2)
        if (closeParen <= closeBracket) return null

        val label = text.substring(start + 1, closeBracket)
        val url = text.substring(closeBracket + 2, closeParen).trim()
        if (url.isEmpty()) return null

        // 可选标题：![alt](url "title") —— 去掉尾部引号部分，避免当成 URL
        val cleanUrl = url.split(Regex("\\s+")).firstOrNull().orEmpty()
        if (cleanUrl.isEmpty()) return null

        return Triple(label, cleanUrl, closeParen + 1)
    }

    /** 反斜杠可以转义的字符集合。 */
    private val ESCAPABLE = setOf('\\', '`', '*', '_', '{', '}', '[', ']', '(', ')', '#', '+', '-', '.', '!', '~', '>', '|', '<')
}
