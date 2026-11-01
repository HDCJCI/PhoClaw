package com.phoclaw.chat.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/**
 * 跨会话的长期记忆，存在 `files/MEMORY.md`。
 *
 * 为什么放 App 私有目录而不是工作区：
 * - 工作区是 SAF（`content://`）目录，用户随时可能换掉，记忆会跟着一起「丢」
 * - 没授权工作区时（首启动、用户取消）就没有记忆，跨会话能力等于不存在
 * - 私有目录下的文件跟着 App 走，卸载才消失，语义才对得上「长期」
 *
 * 文件格式刻意做得**宽松**：只有 `- ` 开头的行被当成一条记忆，其余内容
 *（标题、空行、用户手写的备注）原样保留但不解析。这样用户直接编辑时
 * 不必研究什么格式，随手写也不会把整个记忆读崩。
 *
 * @see MemoryStore.MAX_INJECT_CHARS 注入提示词时的截断上限
 */
class MemoryStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)

    /** 一条被解析出来的记忆。 */
    data class Entry(val date: String, val text: String) {
        /** 去掉日期前缀的正文，去重和匹配都用它。 */
        val body: String get() = text
    }

    data class Stats(val chars: Int, val entries: Int)

    // ------------------------------------------------------------------ 读

    /** 读取完整 Markdown。文件不存在时返回空串。 */
    suspend fun load(): String = withContext(Dispatchers.IO) {
        if (!file.exists()) "" else runCatching { file.readText() }.getOrDefault("")
    }

    /**
     * 读出注入提示词用的版本。
     *
     * 超过 [MAX_INJECT_CHARS] 会截断并注明 —— 记忆本身不能成为
     * 撑爆上下文的原因，那是它本该缓解的问题。
     */
    suspend fun forPrompt(): String = withContext(Dispatchers.IO) {
        val raw = if (file.exists()) runCatching { file.readText() }.getOrDefault("") else ""
        val trimmed = raw.trim()
        if (trimmed.length <= MAX_INJECT_CHARS) return@withContext trimmed

        // 按行截断，避免把一个条目切一半
        val cut = trimmed.take(MAX_INJECT_CHARS)
            .substringBeforeLast('\n', trimmed.take(MAX_INJECT_CHARS))
        "$cut\n\n…（记忆过长已截断，完整内容 ${trimmed.length} 字符，" +
                "共 ${parseEntries(trimmed).size} 条，建议去设置里清理）"
    }

    suspend fun stats(): Stats = withContext(Dispatchers.IO) {
        val raw = if (file.exists()) runCatching { file.readText() }.getOrDefault("") else ""
        Stats(chars = raw.trim().length, entries = parseEntries(raw).size)
    }

    // ------------------------------------------------------------------ 写

    /** 整份覆盖。设置页的手工编辑走这条。 */
    suspend fun save(text: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val backup = if (file.exists()) file.readText() else null
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
            // 写坏了能退回去，总比记忆变空白强
            if (backup != null && file.length() == 0L) file.writeText(backup)
        }
    }

    /**
     * 追加一条记忆。
     *
     * 重复内容直接跳过（返回 [RememberResult.Duplicate]）—— 模型很容易在
     * 多轮里反复「记住」同一件事，不去重的话记忆区很快就被同一句话填满。
     */
    suspend fun remember(text: String): RememberResult = withContext(Dispatchers.IO) {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        if (clean.isEmpty()) return@withContext RememberResult.Empty

        val existing = if (file.exists()) runCatching { file.readText() }.getOrDefault("") else ""
        val entries = parseEntries(existing)

        if (entries.any { it.body.equals(clean, ignoreCase = true) }) {
            return@withContext RememberResult.Duplicate
        }
        if (existing.trim().length + clean.length > MAX_FILE_CHARS) {
            return@withContext RememberResult.Full(existing.trim().length)
        }
        if (entries.size >= MAX_ENTRIES) {
            return@withContext RememberResult.Full(existing.trim().length)
        }

        val line = "- [${LocalDate.now()}] $clean"
        val body = if (existing.isBlank()) {
            "$FILE_HEADER\n\n$line\n"
        } else {
            // 保证前一条和这一条之间恰好一个换行，不叠加空行
            existing.trimEnd() + "\n" + line + "\n"
        }
        runCatching { file.writeText(body) }
            .fold(
                onSuccess = { RememberResult.Added(entries.size + 1) },
                onFailure = { RememberResult.Failed(it.message ?: it.javaClass.simpleName) }
            )
    }

    /**
     * 删除正文里包含 [keyword] 的条目，返回删掉的条数。
     *
     * 保留标题和其它非条目行，这样用户手写的备注不会被连带清掉。
     */
    suspend fun forget(keyword: String): Int = withContext(Dispatchers.IO) {
        val key = keyword.trim()
        if (key.isEmpty()) return@withContext 0
        val existing = if (file.exists()) runCatching { file.readText() }.getOrDefault("") else ""
        if (existing.isBlank()) return@withContext 0

        var removed = 0
        val kept = existing.lines().filterNot { line ->
            val isEntry = ENTRY_LINE.matches(line)
            if (isEntry && line.contains(key, ignoreCase = true)) {
                removed++
                true
            } else {
                false
            }
        }
        if (removed > 0) {
            file.writeText(kept.joinToString("\n").trimEnd() + "\n")
        }
        removed
    }

    suspend fun clear(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (file.exists()) file.delete()
            Unit
        }
    }

    // ---------------------------------------------------------------- 解析

    /**
     * 抽出所有记忆条目。
     *
     * 只认 `- ` 或 `* ` 开头的行，日期前缀可有可无 —— 用户手写
     * `- 记住我喜欢深色主题` 也算一条，不该因为它没写日期就忽略。
     */
    fun parseEntries(raw: String): List<Entry> {
        if (raw.isBlank()) return emptyList()
        return raw.lines().mapNotNull { line ->
            val trimmed = line.trim()
            if (!ENTRY_LINE.matches(trimmed)) return@mapNotNull null
            val withoutBullet = trimmed.removePrefix("- ").removePrefix("* ").trim()
            val match = DATE_PREFIX.find(withoutBullet)
            if (match != null) {
                Entry(match.groupValues[1], withoutBullet.substring(match.value.length).trim())
            } else {
                Entry("", withoutBullet)
            }
        }.filter { it.body.isNotEmpty() }
    }

    companion object {
        private const val FILE_NAME = "MEMORY.md"
        private const val FILE_HEADER = "# 长期记忆"

        /** 注入提示词时的截断长度。约 2000 汉字，够存几十条偏好。 */
        const val MAX_INJECT_CHARS = 8_000

        /** 文件总长度上限。超过后拒绝新增，提示用户清理。 */
        const val MAX_FILE_CHARS = 20_000

        /** 最多存多少条。条数太多时提示词会被淹没，模型反而记不住重点。 */
        const val MAX_ENTRIES = 200

        private val ENTRY_LINE = Regex("""^[-*]\s+\S""")

        /** `[2026-11-01]` 或 `(2026-11-01)` 形式的日期前缀。 */
        private val DATE_PREFIX = Regex("""^[\[(](\d{4}-\d{2}-\d{2})[\])\s]*""")
    }

    /** [remember] 的结果。 */
    sealed interface RememberResult {
        data class Added(val total: Int) : RememberResult
        data object Duplicate : RememberResult
        data object Empty : RememberResult
        data class Full(val currentChars: Int) : RememberResult
        data class Failed(val message: String) : RememberResult
    }
}
