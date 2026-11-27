package com.phoclaw.chat.util

/**
 * 精确替换的**纯逻辑**部分：解析、定位、替换。
 *
 * 从 `WorkspaceRepository.editText` 里拆出来是为了可测 —— 这段判断
 * （唯一匹配？缩进对不上怎么办？）是整套编辑能力里最容易出错的地方，
 * 而它原本和 SAF 的 IO 缠在一起，想跑个用例都得有台 Android 设备。
 * 现在它不碰文件系统，输入两个字符串、输出一个结果。
 */
object TextPatch {

    /** 一次块匹配的位置。 */
    data class Hit(val start: Int, val size: Int, val indent: String)

    /**
     * 替换结果。
     *
     * @param updated 成功时的新全文；失败时为 null
     * @param line 改动起始行号（1 起）；用于回执里告诉模型改在哪
     * @param fuzzy 是否走了「忽略缩进」的容错匹配
     */
    data class Result(
        val updated: String?,
        val line: Int = 0,
        val fuzzy: Boolean = false,
        val error: String? = null,
        val hits: Int = 0
    ) {
        val ok: Boolean get() = updated != null
    }

    /**
     * 从指令正文里解析出「查找」「替换」两段。
     *
     * 分隔行写 `--- 查找` / `--- 替换`。宽容度：横线数量不限、可以用 `#`，
     * 或者干脆只写「查找」「替换」两个字加冒号。但**两段必须都在** ——
     * 少一段就报错，不去猜它想改什么。
     */
    fun parseBody(body: String): Pair<String, String>? {
        val lines = body.trim('\n').lines()
        var findAt = -1
        var replaceAt = -1
        lines.forEachIndexed { i, line ->
            val t = line.trim()
            if (findAt < 0) {
                if (isMarker(t, "查找") || isMarker(t, "原内容") || isMarker(t, "old")) findAt = i
            } else if (replaceAt < 0) {
                if (isMarker(t, "替换") || isMarker(t, "新内容") || isMarker(t, "new")) replaceAt = i
            }
        }
        if (findAt < 0 || replaceAt < 0 || replaceAt < findAt) return null

        val find = lines.subList(findAt + 1, replaceAt).joinToString("\n").trim('\n')
        val replace = lines.subList(replaceAt + 1, lines.size).joinToString("\n").trim('\n')
        if (find.isBlank()) return null
        return find to replace
    }

    fun isMarker(line: String, keyword: String): Boolean {
        val t = line.trim().trimEnd('：', ':')
        if (t.equals(keyword, ignoreCase = true)) return true
        val stripped = t.trimStart('-', '#', '*', ' ').trim()
        return stripped.equals(keyword, ignoreCase = true)
    }

    /**
     * 执行替换。
     *
     * 三段式：先精确匹配；唯一就换、多处就拒；一处都没有时再试「忽略缩进」
     * 的整块匹配。最后这条是为模型准备的 —— 它抄代码经常丢缩进。
     */
    fun apply(original: String, find: String, replace: String): Result {
        if (find.isEmpty()) return Result(null, error = "查找内容为空，无法定位")

        // ---- 精确匹配 ----
        val hits = occurrences(original, find)
        if (hits.size == 1) {
            val at = hits[0]
            return Result(
                updated = original.substring(0, at) + replace + original.substring(at + find.length),
                line = lineNumberAt(original, at)
            )
        }
        if (hits.size > 1) {
            return Result(
                null,
                hits = hits.size,
                error = "查找内容出现了 ${hits.size} 处" +
                        "（第 ${hits.take(5).joinToString("、") { lineNumberAt(original, it).toString() }} 行附近），" +
                        "无法确定改哪一处。请在「查找」里多带几行上下文，让它唯一。"
            )
        }

        // ---- 忽略缩进的整块匹配 ----
        val fuzzy = fuzzyLocate(original, find)
        if (fuzzy.isEmpty()) {
            return Result(
                null,
                error = "在文件里找不到这段内容。可能：① 文件已被改过，先 read 确认；" +
                        "② 只写了一行片段（如 `}`）；③ 路径不对。"
            )
        }
        if (fuzzy.size > 1) {
            return Result(
                null,
                hits = fuzzy.size,
                error = "忽略缩进后仍有 ${fuzzy.size} 处匹配（第 " +
                        fuzzy.take(5).joinToString("、") { it.start.toString() } +
                        " 行）。请在「查找」里多带几行上下文。"
            )
        }
        val hit = fuzzy[0]
        return Result(updated = applyHit(original, replace, hit), line = hit.start, fuzzy = true)
    }

    /** [needle] 在 [haystack] 里所有出现位置。最多记 50 个，够用来报错。 */
    fun occurrences(haystack: String, needle: String): List<Int> {
        if (needle.isEmpty()) return emptyList()
        val out = mutableListOf<Int>()
        var idx = haystack.indexOf(needle)
        while (idx >= 0) {
            out += idx
            if (out.size > 50) break
            idx = haystack.indexOf(needle, idx + 1)
        }
        return out
    }

    /** 字符下标 → 行号（从 1 开始）。 */
    fun lineNumberAt(text: String, index: Int): Int {
        var n = 1
        for (i in 0 until minOf(index, text.length)) if (text[i] == '\n') n++
        return n
    }

    /**
     * 忽略每行首尾空白的整块定位。
     *
     * 只认「首行与文件里某行的非空白部分完全相同」的位置。允许从行中间开始
     * 匹配的话，替换后会在行首留下半截，比匹配不上更难排查。
     */
    fun fuzzyLocate(original: String, needle: String): List<Hit> {
        val fileLines = original.split('\n')
        val needleLines = needle.trim('\n').split('\n')
            .dropWhile { it.isBlank() }
            .dropLastWhile { it.isBlank() }
        if (needleLines.isEmpty()) return emptyList()

        val out = mutableListOf<Hit>()
        val last = fileLines.size - needleLines.size
        if (last < 0) return emptyList()
        for (s in 0..last) {
            if (fileLines[s].trim() != needleLines[0].trim()) continue
            var ok = true
            for (k in needleLines.indices) {
                if (fileLines[s + k].trim() != needleLines[k].trim()) { ok = false; break }
            }
            if (ok) {
                val indent = fileLines[s].takeWhile { it == ' ' || it == '\t' }
                out += Hit(start = s + 1, size = needleLines.size, indent = indent)
                if (out.size > 10) break
            }
        }
        return out
    }

    /** 把 [replace] 套到 [hit] 指定的行区间，并按文件里的实际缩进重排。 */
    fun applyHit(original: String, replace: String, hit: Hit): String {
        val lines = original.split('\n')
        val before = lines.take(hit.start - 1)
        val after = lines.drop(hit.start - 1 + hit.size)
        return (before + reindent(replace, hit.indent) + after).joinToString("\n")
    }

    /**
     * 去掉 [replace] 的公共缩进，再统一补上 [indent]。
     *
     * 两步都要做：模型给的替换内容常常是顶格的（要补缩进），但也可能它
     * 已经按原文缩进过了（此时再补一遍就越缩越深）。先减后加，两种情况都对。
     */
    fun reindent(replace: String, indent: String): List<String> {
        val lines = replace.split('\n')
        val minIndent = lines.filter { it.isNotBlank() }
            .minOfOrNull { line -> line.takeWhile { it == ' ' || it == '\t' }.length } ?: 0
        return lines.map { line ->
            if (line.isBlank()) "" else indent + line.drop(minIndent)
        }
    }
}
