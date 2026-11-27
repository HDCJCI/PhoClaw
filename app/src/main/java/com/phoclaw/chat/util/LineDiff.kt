package com.phoclaw.chat.util

/**
 * 行级文本差异。
 *
 * 为什么自己写而不是引一个库：需要的就是「两个字符串 → 一串带标记的行」，
 * 标准 LCS 二百行足够；引库要么带一堆用不上的字符级 diff，要么在 Android 上
 * 还要多一个依赖项。
 *
 * ## 复杂度与上限
 *
 * LCS 是 O(n·m)。两个 2000 行的文件就是 400 万次比较 —— 在手机上约几十毫秒，
 * 可以接受；但两个 5 万行的文件会直接卡死主线程。所以超过 [MAX_LINES] 就
 * 退化成「整体替换」：不逐行比对了，直接 -全部 +全部。
 *
 * 这不是偷懒。真要给 5 万行的文件做逐行 diff，用户在手机上也没法一条条看完，
 * 明确说「这个文件太大，只显示整体替换」比卡住十秒强得多。
 */
object LineDiff {

    /** 单侧行数上限。超过就走退化路径。 */
    const val MAX_LINES = 2000

    enum class Kind { SAME, ADD, DEL }

    /**
     * 一行差异。
     *
     * @param oldNo 在原文里的行号（从 1 开始）。新增行为 null
     * @param newNo 在新文里的行号（从 1 开始）。删除行为 null
     */
    data class Line(
        val kind: Kind,
        val text: String,
        val oldNo: Int? = null,
        val newNo: Int? = null
    )

    /** 统计结果，给「+42 −7」这种角标用。 */
    data class Stats(val added: Int, val removed: Int, val changed: Int) {
        val isEmpty: Boolean get() = added == 0 && removed == 0
    }

    /**
     * 计算差异。
     *
     * 末尾的空行会被忽略 —— 文件末尾到底有没有换行行，几乎从来不是
     * 用户关心的东西，但会让每个改动都多出一行 `-` `+`。
     */
    fun compute(before: String, after: String): List<Line> {
        val a = splitLines(before)
        val b = splitLines(after)

        if (a.size > MAX_LINES || b.size > MAX_LINES) {
            return degraded(a, b)
        }

        // LCS 表：dp[i][j] = a[i..] 与 b[j..] 的最长公共子序列长度。
        // 从后往前填，回溯时就能从前往后走，输出顺序天然正确
        val n = a.size
        val m = b.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1
                else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }

        val out = ArrayList<Line>(maxOf(n, m))
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> {
                    out += Line(Kind.SAME, a[i], i + 1, j + 1)
                    i++; j++
                }
                // 相等时优先走「删除」，让成块的删除排在新增之前：
                // 读起来是「原来这几行 → 换成这几行」，比 +-+- 交错清楚
                dp[i + 1][j] >= dp[i][j + 1] -> {
                    out += Line(Kind.DEL, a[i], oldNo = i + 1)
                    i++
                }
                else -> {
                    out += Line(Kind.ADD, b[j], newNo = j + 1)
                    j++
                }
            }
        }
        while (i < n) { out += Line(Kind.DEL, a[i], oldNo = i + 1); i++ }
        while (j < m) { out += Line(Kind.ADD, b[j], newNo = j + 1); j++ }
        return out
    }

    fun stats(lines: List<Line>): Stats = Stats(
        added = lines.count { it.kind == Kind.ADD },
        removed = lines.count { it.kind == Kind.DEL },
        changed = lines.count { it.kind != Kind.SAME }
    )

    /**
     * 在差异里给每处改动配 [context] 行上下文，中间隔得远的地方折成一行省略。
     *
     * 直接铺全部内容的话，改一个文件末尾的字符也要显示整个文件 ——
     * 手机上翻三次屏才找得到那处 `+`。
     */
    fun withContext(lines: List<Line>, context: Int = 2): List<Line> {
        if (lines.size <= context * 2 + 4) return lines

        val keep = BooleanArray(lines.size)
        lines.forEachIndexed { idx, line ->
            if (line.kind != Kind.SAME) {
                for (k in maxOf(0, idx - context)..minOf(lines.lastIndex, idx + context)) keep[k] = true
            }
        }
        // 没有任何改动时全保留（纯新增文件之类）
        if (keep.none { it }) return lines

        val out = ArrayList<Line>(keep.size)
        var skipped = 0
        lines.forEachIndexed { idx, line ->
            if (keep[idx]) {
                if (skipped > 0) {
                    out += Line(Kind.SAME, "… 省略 $skipped 行 …")
                    skipped = 0
                }
                out += line
            } else {
                skipped++
            }
        }
        return out
    }

    private fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        return text.trimEnd('\n').split('\n')
    }

    private fun degraded(a: List<String>, b: List<String>): List<Line> {
        val out = ArrayList<Line>(a.size + b.size + 1)
        out += Line(Kind.SAME, "（文件超过 $MAX_LINES 行，不逐行比对，下面是整体替换）")
        a.forEachIndexed { i, s -> out += Line(Kind.DEL, s, oldNo = i + 1) }
        b.forEachIndexed { i, s -> out += Line(Kind.ADD, s, newNo = i + 1) }
        return out
    }
}
