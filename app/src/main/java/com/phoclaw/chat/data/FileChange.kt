package com.phoclaw.chat.data

/**
 * 一次文件变更的记录。
 *
 * 这份数据同时支撑三件事，所以刻意放在一处：
 * - **变更汇总**（「修改 3 个文件 +42 −7」）
 * - **diff 展示**（红绿行）
 * - **撤销**（把 [before] 写回去）
 *
 * 三者如果各自去读磁盘，撤销就会变成「撤销的是当前状态」而不是
 * 「撤销的是那一轮改的东西」—— 用户在一轮里连改三次同一个文件时，
 * 只有这里记下的中间状态能还原对。
 *
 * ## 为什么不存全文
 *
 * 大文件（二进制、日志、几 MB 的 JSON）存进会话 JSON 会让落盘慢到肉眼可见。
 * 超过 [MAX_CONTENT] 就只记路径与大小，[truncated] 置 true ——
 * 界面上老老实实说「太大，没有备份，无法撤销」，比假装能撤销好。
 */
data class FileChange(
    val path: String,
    val kind: Kind,
    /** 改动前的内容。null 表示文件原本不存在（新建）。 */
    val before: String? = null,
    /** 改动后的内容。null 表示文件已被删除。 */
    val after: String? = null,
    /** 内容超过上限，只记了路径没记正文。此时 [canUndo] 为 false。 */
    val truncated: Boolean = false,
    val at: Long = 0L
) {
    enum class Kind { CREATE, MODIFY, DELETE }

    /** 能否撤销。新建与修改需要 [before]（新建时可为 null，撤销=删除）；删除需要 [after]。 */
    val canUndo: Boolean
        get() = !truncated && when (kind) {
            Kind.CREATE -> true          // 撤销 = 删掉这个文件
            Kind.MODIFY -> before != null
            Kind.DELETE -> after != null
        }

    val symbol: String
        get() = when (kind) {
            Kind.CREATE -> "A"
            Kind.MODIFY -> "M"
            Kind.DELETE -> "D"
        }

    /** 简短的增删统计，用于角标。内容缺失时返回 null。 */
    val stats: com.phoclaw.chat.util.LineDiff.Stats?
        get() {
            if (truncated) return null
            val lines = com.phoclaw.chat.util.LineDiff.compute(before.orEmpty(), after.orEmpty())
            return com.phoclaw.chat.util.LineDiff.stats(lines)
        }

    companion object {
        /** 单个文件保存正文的上限（字符）。与 `read` 的上限保持一致。 */
        const val MAX_CONTENT = 512 * 1024
    }
}

/** 一轮对话里的全部文件变更，按执行顺序排列。 */
data class TurnChanges(val items: List<FileChange> = emptyList()) {

    val isEmpty: Boolean get() = items.isEmpty()

    /** 有几个不同的文件被改过（同一文件改多次只算一个）。 */
    val fileCount: Int get() = items.map { it.path }.distinct().size

    val added: Int get() = items.sumOf { it.stats?.added ?: 0 }
    val removed: Int get() = items.sumOf { it.stats?.removed ?: 0 }

    val canUndo: Boolean get() = items.any { it.canUndo }
}
