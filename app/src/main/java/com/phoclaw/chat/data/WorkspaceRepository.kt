package com.phoclaw.chat.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

/**
 * 基于 SAF（Storage Access Framework）的工作区。
 *
 * 用户通过系统的目录选择器授权一个目录，拿到 tree Uri 后即可在**无需任何存储权限**的
 * 前提下完整读写该目录及其子目录。持久化权限由 [Context.takePersistableUriPermission] 保证，
 * 重启后依然可用。
 *
 * 所有 IO 都切到 [Dispatchers.IO]，避免阻塞 Compose 主线程。
 */
class WorkspaceRepository(private val context: Context) {

    private var treeUri: Uri? = null

    /** 当前工作区是否已选定。 */
    val isReady: Boolean get() = treeUri != null

    val rootUri: Uri? get() = treeUri

    /** 已授权工作区的展示名，未选定时返回 null。 */
    val displayName: String?
        get() = treeUri?.let { uri ->
            DocumentFile.fromTreeUri(context, uri)?.name
        }

    fun setTreeUri(uri: Uri?) {
        treeUri = uri
    }

    private fun root(): DocumentFile =
        treeUri?.let { DocumentFile.fromTreeUri(context, it) }
            ?: throw FileNotFoundException("尚未选择工作区目录")

    // ---------------------------------------------------------------- 路径解析

    /**
     * 把相对路径解析为 [DocumentFile]。
     * @param path 形如 "src/main.js" 或 "."（根目录），不支持 ".." 向上越权。
     */
    private fun resolve(path: String, createDirectories: Boolean = false): DocumentFile? {
        val cleaned = sanitize(path)
        var current = root()
        if (cleaned.isEmpty()) return current

        val segments = cleaned.split('/').filter { it.isNotBlank() }
        segments.forEachIndexed { index, seg ->
            val isLast = index == segments.lastIndex
            var child = findChild(current, seg)
            if (child == null) {
                child = when {
                    isLast -> null
                    createDirectories -> current.createDirectory(seg)
                    else -> null
                }
            }
            if (child == null) return null
            current = child
        }
        return current
    }

    private fun findChild(parent: DocumentFile, name: String): DocumentFile? {
        if (!parent.isDirectory) return null
        return parent.listFiles().firstOrNull { it.name == name }
    }

    /** 归一化路径，拦截路径穿越。 */
    private fun sanitize(path: String): String {
        val trimmed = path.trim().trim('/')
        if (trimmed.isEmpty() || trimmed == ".") return ""
        val parts = trimmed.split('/').filter { it.isNotBlank() && it != "." }
        if (parts.any { it == ".." }) {
            throw SecurityException("路径不允许包含 ..：$path")
        }
        return parts.joinToString("/")
    }

    // ---------------------------------------------------------------- 读操作

    /** 列出目录，返回 (名称, 是否目录, 字节数) 三元组。 */
    suspend fun list(path: String = "."): List<Triple<String, Boolean, Long>> = withContext(Dispatchers.IO) {
        val dir = resolve(path) ?: return@withContext emptyList()
        if (!dir.isDirectory) return@withContext emptyList()
        dir.listFiles()
            .sortedWith(compareByDescending<DocumentFile> { it.isDirectory }.thenBy { it.name })
            .map { Triple(it.name.orEmpty(), it.isDirectory, it.length()) }
    }

    suspend fun readText(path: String): String = withContext(Dispatchers.IO) {
        val file = resolve(path) ?: throw FileNotFoundException("文件不存在：$path")
        if (file.isDirectory) throw IllegalArgumentException("这是一个目录，不是文件：$path")
        if (file.length() > MAX_TEXT_BYTES) {
            throw IllegalArgumentException(
                "文件过大（${humanSize(file.length())}），超过 ${humanSize(MAX_TEXT_BYTES)} 上限，未读取"
            )
        }
        context.contentResolver.openInputStream(file.uri).use { input ->
            requireNotNull(input) { "无法打开文件：$path" }
            input.bufferedReader().readText()
        }
    }

    /** 文件详情：大小、类型、修改时间。 */
    suspend fun info(path: String): String = withContext(Dispatchers.IO) {
        val cleaned = sanitize(path)
        val target = resolve(cleaned) ?: throw FileNotFoundException("目标不存在：${cleaned.ifEmpty { "." }}")
        val name = target.name ?: cleaned.ifEmpty { "工作区根目录" }
        val type = if (target.isDirectory) "目录" else "文件"
        val size = if (target.isDirectory) "-" else humanSize(target.length())
        val modified = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(target.lastModified()))
        buildString {
            appendLine("路径：${cleaned.ifEmpty { "." }}")
            appendLine("名称：$name")
            appendLine("类型：$type")
            appendLine("大小：$size")
            append("修改时间：$modified")
        }
    }

    /**
     * 递归列出目录树，最多 [maxDepth] 层、[maxEntries] 个条目，避免超大仓库撑爆上下文。
     */
    suspend fun tree(path: String = ".", maxDepth: Int = 3, maxEntries: Int = 300): String =
        withContext(Dispatchers.IO) {
            val cleaned = sanitize(path)
            val start = resolve(cleaned) ?: throw FileNotFoundException("目录不存在：${cleaned.ifEmpty { "." }}")
            if (!start.isDirectory) return@withContext "这不是目录：$cleaned"

            val lines = mutableListOf<String>()
            var count = 0
            var truncated = false

            fun walk(dir: DocumentFile, prefix: String, depth: Int) {
                if (depth > maxDepth || truncated) return
                val children = dir.listFiles()
                    .sortedWith(compareByDescending<DocumentFile> { it.isDirectory }.thenBy { it.name })
                for ((i, child) in children.withIndex()) {
                    if (count >= maxEntries) { truncated = true; return }
                    count++
                    val isLast = i == children.lastIndex
                    val branch = if (isLast) "└── " else "├── "
                    val name = child.name.orEmpty()
                    lines += if (child.isDirectory) "$prefix$branch$name/"
                    else "$prefix$branch$name (${humanSize(child.length())})"
                    if (child.isDirectory) {
                        val nextPrefix = prefix + if (isLast) "    " else "│   "
                        walk(child, nextPrefix, depth + 1)
                    }
                }
            }

            walk(start, "", 1)
            if (lines.isEmpty()) return@withContext "目录为空：${cleaned.ifEmpty { "." }}"
            buildString {
                appendLine("工作区/${cleaned}".trimEnd('/'))
                append(lines.joinToString("\n"))
                if (truncated) append("\n… 条目过多已截断（上限 $maxEntries 条）")
            }
        }

    /**
     * 在工作区里按关键词搜索文件内容。
     * 只搜文本类文件，跳过超过 [MAX_SEARCH_FILE_BYTES] 的大文件和二进制文件。
     */
    suspend fun search(
        keyword: String,
        path: String = ".",
        maxResults: Int = 60
    ): String = withContext(Dispatchers.IO) {
        require(keyword.isNotBlank()) { "请提供搜索关键词" }
        val cleaned = sanitize(path)
        val start = resolve(cleaned) ?: throw FileNotFoundException("目录不存在：${cleaned.ifEmpty { "." }}")

        val hits = mutableListOf<String>()
        var scanned = 0
        var skippedBinary = 0
        var truncated = false

        fun scan(file: DocumentFile, relPath: String) {
            if (truncated) return
            if (file.isDirectory) {
                file.listFiles().forEach { scan(it, "$relPath/${it.name}") }
                return
            }
            val name = file.name.orEmpty()
            if (file.length() > MAX_SEARCH_FILE_BYTES || !isTextLike(name)) {
                skippedBinary++
                return
            }
            scanned++
            val text = runCatching {
                context.contentResolver.openInputStream(file.uri)?.use {
                    it.bufferedReader().readText()
                }.orEmpty()
            }.getOrDefault("")
            if (text.isEmpty()) return

            text.lineSequence().forEachIndexed { idx, line ->
                if (truncated) return@forEachIndexed
                if (line.contains(keyword, ignoreCase = true)) {
                    if (hits.size >= maxResults) { truncated = true; return@forEachIndexed }
                    val trimmed = line.trim().take(200)
                    hits += "${relPath.trimStart('/')}:${idx + 1}: $trimmed"
                }
            }
        }

        scan(start, cleaned)

        when {
            hits.isEmpty() -> "未找到包含「$keyword」的内容（已扫描 $scanned 个文本文件）"
            else -> buildString {
                appendLine("找到 ${hits.size} 处匹配（扫描 $scanned 个文本文件）")
                appendLine()
                append(hits.joinToString("\n"))
                if (truncated) append("\n… 结果过多已截断")
            }
        }
    }

    // ---------------------------------------------------------------- 写操作

    /**
     * 写入文件（覆盖写）。父目录不存在时自动创建。
     */
    suspend fun writeText(path: String, content: String): String = withContext(Dispatchers.IO) {
        val cleaned = sanitize(path)
        require(cleaned.isNotEmpty()) { "不能写入工作区根目录，请指定文件名" }
        val segments = cleaned.split('/')
        val fileName = segments.last()
        val parentPath = segments.dropLast(1).joinToString("/")

        val parent = if (parentPath.isEmpty()) root()
        else resolve(parentPath, createDirectories = true)
            ?: throw FileNotFoundException("无法创建目录：$parentPath")

        val existing = findChild(parent, fileName)
        val target = when {
            existing == null -> parent.createFile(guessMime(fileName), fileName)
                ?: throw FileNotFoundException("无法创建文件：$cleaned")
            existing.isDirectory -> throw IllegalArgumentException("同名目录已存在：$cleaned")
            else -> existing
        }

        context.contentResolver.openOutputStream(target.uri, "wt").use { output ->
            requireNotNull(output) { "无法以写入模式打开：$cleaned" }
            output.bufferedWriter().use { it.write(content) }
        }
        "已写入 ${cleaned}（${content.toByteArray().size} 字节）"
    }

    /** 追加内容到文件末尾，文件不存在则创建。 */
    suspend fun appendText(path: String, content: String): String = withContext(Dispatchers.IO) {
        val cleaned = sanitize(path)
        require(cleaned.isNotEmpty()) { "请指定文件路径" }
        val segments = cleaned.split('/')
        val fileName = segments.last()
        val parentPath = segments.dropLast(1).joinToString("/")

        val parent = if (parentPath.isEmpty()) root()
        else resolve(parentPath, createDirectories = true)
            ?: throw FileNotFoundException("无法创建目录：$parentPath")

        val existing = findChild(parent, fileName)
        val target = when {
            existing == null -> parent.createFile(guessMime(fileName), fileName)
                ?: throw FileNotFoundException("无法创建文件：$cleaned")
            existing.isDirectory -> throw IllegalArgumentException("同名目录已存在：$cleaned")
            else -> existing
        }

        val before = target.length()
        context.contentResolver.openOutputStream(target.uri, "wa").use { output ->
            requireNotNull(output) { "无法以追加模式打开：$cleaned" }
            output.bufferedWriter().use { it.write(content) }
        }
        val after = DocumentFile.fromSingleUri(context, target.uri)?.length() ?: target.length()
        "已追加内容到 $cleaned（${humanSize(before)} → ${humanSize(after)}）"
    }

    suspend fun delete(path: String): String = withContext(Dispatchers.IO) {
        val cleaned = sanitize(path)
        require(cleaned.isNotEmpty()) { "不能删除工作区根目录" }
        val target = resolve(cleaned) ?: throw FileNotFoundException("目标不存在：$cleaned")
        val wasDir = target.isDirectory
        val subCount = if (wasDir) target.listFiles().size else 0
        val ok = target.delete()
        if (ok) {
            if (wasDir && subCount > 0) "已删除目录 $cleaned（含 $subCount 个条目）"
            else "已删除 $cleaned"
        } else "删除失败：$cleaned"
    }

    suspend fun createDirectory(path: String): String = withContext(Dispatchers.IO) {
        val cleaned = sanitize(path)
        require(cleaned.isNotEmpty()) { "请指定目录名" }
        if (resolve(cleaned) != null) return@withContext "目录已存在：$cleaned"
        val segments = cleaned.split('/')
        val parentPath = segments.dropLast(1).joinToString("/")
        val parent = if (parentPath.isEmpty()) root()
        else resolve(parentPath, createDirectories = true) ?: root()
        parent.createDirectory(segments.last())
            ?: throw FileNotFoundException("无法创建目录：$cleaned")
        "已创建目录 $cleaned"
    }

    /**
     * 复制文件或目录到目标路径。
     * 目录会被递归复制；已存在的目标会被覆盖（文件）或合并（目录）。
     */
    suspend fun copy(source: String, target: String): String = withContext(Dispatchers.IO) {
        val src = sanitize(source)
        val dst = sanitize(target)
        require(src.isNotEmpty()) { "请指定源路径" }
        require(dst.isNotEmpty()) { "请指定目标路径" }
        require(src != dst) { "源路径与目标路径相同" }
        // 禁止把目录复制进自己的子目录，否则会无限递归
        require(!dst.startsWith("$src/")) { "不能把目录复制到它自己的子目录里：$src -> $dst" }

        val sourceFile = resolve(src) ?: throw FileNotFoundException("源不存在：$src")
        var copied = 0

        fun copyRecursive(from: DocumentFile, toParent: DocumentFile) {
            val name = from.name.orEmpty()
            if (from.isDirectory) {
                val newDir = findChild(toParent, name)?.takeIf { it.isDirectory }
                    ?: toParent.createDirectory(name)
                    ?: throw FileNotFoundException("无法创建目录：$name")
                copied++
                from.listFiles().forEach { copyRecursive(it, newDir) }
            } else {
                val existing = findChild(toParent, name)
                val outFile = when {
                    existing == null -> toParent.createFile(guessMime(name), name)
                    existing.isDirectory -> throw IllegalArgumentException("同名目录已存在：$name")
                    else -> existing
                } ?: throw FileNotFoundException("无法创建文件：$name")

                context.contentResolver.openInputStream(from.uri).use { input ->
                    requireNotNull(input) { "无法读取源文件：$name" }
                    context.contentResolver.openOutputStream(outFile.uri, "wt").use { output ->
                        requireNotNull(output) { "无法写入目标文件：$name" }
                        input.copyTo(output)
                    }
                }
                copied++
            }
        }

        val dstSegments = dst.split('/')
        val dstName = dstSegments.last()
        val dstParentPath = dstSegments.dropLast(1).joinToString("/")
        val dstParent = if (dstParentPath.isEmpty()) root()
        else resolve(dstParentPath, createDirectories = true)
            ?: throw FileNotFoundException("无法创建目录：$dstParentPath")

        // 目标最后一段若与源同名，直接复制进父目录；否则按新名字复制
        val dstExisting = findChild(dstParent, dstName)
        if (dstExisting != null && dstExisting.isDirectory && sourceFile.isFile) {
            // 目标是个已存在的目录，按原名放进去
            copyRecursive(sourceFile, dstExisting)
        } else if (dstExisting == null && sourceFile.isFile) {
            // 目标名不存在，按目标名创建新文件
            val outFile = dstParent.createFile(guessMime(dstName), dstName)
                ?: throw FileNotFoundException("无法创建文件：$dstName")
            context.contentResolver.openInputStream(sourceFile.uri).use { input ->
                requireNotNull(input) { "无法读取源文件：$src" }
                context.contentResolver.openOutputStream(outFile.uri, "wt").use { output ->
                    requireNotNull(output) { "无法写入目标文件：$dst" }
                    input.copyTo(output)
                }
            }
            copied++
        } else {
            copyRecursive(sourceFile, dstParent)
        }

        "已复制 $src -> $dst（$copied 个条目）"
    }

    /** 移动或重命名。跨目录移动通过「复制 + 删除源」实现。 */
    suspend fun move(source: String, target: String): String = withContext(Dispatchers.IO) {
        val src = sanitize(source)
        val dst = sanitize(target)
        require(src.isNotEmpty()) { "请指定源路径" }
        require(dst.isNotEmpty()) { "请指定目标路径" }
        require(src != dst) { "源路径与目标路径相同" }
        require(!dst.startsWith("$src/")) { "不能把目录移动到它自己的子目录里：$src -> $dst" }

        val sourceFile = resolve(src) ?: throw FileNotFoundException("源不存在：$src")
        val isDir = sourceFile.isDirectory

        if (isDir) {
            // 目录无法一次原子移动，走复制后删除
            val copyResult = copy(src, dst)
            val ok = sourceFile.delete()
            buildString {
                appendLine(copyResult.replace("已复制", "已移动"))
                append(if (ok) "已移除原目录 $src" else "原目录 $src 移除失败，请手动清理")
            }
        } else {
            val renamed = sourceFile.renameTo(dst)
            if (renamed) {
                "已移动 $src -> $dst"
            } else {
                // renameTo 跨卷可能失败，退回复制 + 删除
                copy(src, dst)
                val ok = sourceFile.delete()
                "已移动 $src -> $dst" + if (ok) "" else "（原文件移除失败，请手动清理）"
            }
        }
    }

    private fun humanSize(bytes: Long): String = when {
        bytes < 0 -> "未知"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    }

    /** 粗略判断是否文本文件，用于搜索时跳过二进制。 */
    private fun isTextLike(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return true
        return ext !in BINARY_EXTENSIONS
    }

    private fun guessMime(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "txt", "md", "log" -> "text/plain"
        "json" -> "application/json"
        "xml" -> "text/xml"
        "html", "htm" -> "text/html"
        "js" -> "application/javascript"
        "css" -> "text/css"
        "kt", "java", "py", "c", "cpp", "h", "go", "rs", "ts", "sh" -> "text/plain"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        else -> "application/octet-stream"
    }

    companion object {
        /** 把目录 URI 拆成「可持久化的树 URI」。 */
        fun toTreeUri(uri: Uri): Uri = uri

        /** 单文件读取上限，避免把超大文件塞进上下文。 */
        private const val MAX_TEXT_BYTES = 512 * 1024L

        /** 搜索时单个文件的体积上限。 */
        private const val MAX_SEARCH_FILE_BYTES = 1024 * 1024L

        private val BINARY_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "svgz",
            "mp3", "wav", "ogg", "flac", "m4a", "aac",
            "mp4", "mkv", "avi", "mov", "webm",
            "zip", "rar", "7z", "gz", "tar", "jar", "apk", "dex",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "so", "dll", "exe", "bin", "class", "o", "a"
        )
    }
}
