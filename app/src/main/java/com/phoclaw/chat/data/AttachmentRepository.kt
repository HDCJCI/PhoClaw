package com.phoclaw.chat.data

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * 附件的读取、压缩、落盘与清理。
 *
 * 存储位置：应用私有目录 `files/attachments/`。会话 JSON 里只存文件名引用，
 * 图片字节**不进** JSON —— 否则一个几 MB 的图会让每次读写聊天记录都卡顿。
 *
 * 全部读写操作都在 [Dispatchers.IO] 上，与工程既有风格一致。
 */
class AttachmentRepository(context: Context) {

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, DIR_NAME)

    // ------------------------------------------------------------------ 读取

    /**
     * 读取 SAF 返回的 URI，产出可直接预览的 [PendingAttachment]。
     *
     * 顺序上先查大小再开流，避免把几百兆的东西读进内存；
     * 但查询结果不可信（有些 provider 返回 null 或撒谎），所以实际读取时
     * 还会用 [readLimited] 做二次硬截断。
     */
    suspend fun loadFromUri(uri: Uri): Result<PendingAttachment> = withContext(Dispatchers.IO) {
        runCatching {
            val displayName = queryName(uri)?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: "attachment"

            val mime = appContext.contentResolver.getType(uri)
                ?.takeIf { it.isNotBlank() && it != "*/*" }
                ?: guessMime(displayName)

            val kind = if (mime.startsWith("image/")) AttachmentKind.IMAGE
            else AttachmentKind.TEXT

            val limit = if (kind == AttachmentKind.IMAGE) MAX_IMAGE_BYTES else MAX_TEXT_BYTES
            val declared = querySize(uri)
            if (declared > limit) {
                throw IllegalArgumentException(
                    "文件过大（${humanSize(declared)}），上限 ${humanSize(limit)}"
                )
            }

            val raw = appContext.contentResolver.openInputStream(uri)?.use { readLimited(it, limit) }
                ?: throw IllegalArgumentException("无法打开文件：$displayName")
            if (raw.isEmpty()) throw IllegalArgumentException("文件内容为空：$displayName")

            when (kind) {
                AttachmentKind.IMAGE -> {
                    val (bytes, thumb, note) = processImage(raw)
                    PendingAttachment(
                        key = newKey(),
                        displayName = displayName,
                        // 压缩后统一是 JPEG，MIME 必须跟着改，
                        // 否则 data URI 声明与实际内容不符，部分后端会报错
                        mime = "image/jpeg",
                        kind = kind,
                        bytes = bytes,
                        thumbnail = thumb,
                        note = note
                    )
                }

                AttachmentKind.TEXT -> {
                    // 含 NUL 字节基本可判定为二进制（图片/压缩包/可执行文件都含）
                    if (raw.any { it == 0.toByte() }) {
                        throw IllegalArgumentException("这不像文本文件，暂不支持：$displayName")
                    }
                    PendingAttachment(
                        key = newKey(),
                        displayName = displayName,
                        mime = mime,
                        kind = kind,
                        bytes = raw
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ 落盘

    /**
     * 把待发送附件写入私有目录，返回落盘后的元信息。
     *
     * 调用时机是**用户点发送的瞬间**，不是选文件的时候 ——
     * 用户选完又删掉不该留下垃圾文件；而且发送时复制能保证
     * 「JSON 里引用的文件一定存在」。
     */
    suspend fun persist(pending: PendingAttachment): Result<Attachment> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureDir()
                // 文件名不掺原文件名：避免中文、表情、超长名、路径分隔符带来的各种麻烦。
                // 原文件名只存在 displayName 里，用于展示。
                val localName = "${newKey()}${extensionFor(pending.mime, pending.displayName)}"
                File(dir, localName).writeBytes(pending.bytes)
                Attachment(
                    localName = localName,
                    displayName = pending.displayName,
                    mime = pending.mime,
                    size = pending.bytes.size.toLong(),
                    kind = pending.kind
                )
            }
        }

    /** 读回落盘的附件字节（发请求时用）。文件不存在或读取失败返回 null。 */
    suspend fun readBytes(localName: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { fileFor(localName).takeIf { it.exists() }?.readBytes() }.getOrNull()
    }

    /** 附件的实际文件位置。UI 层通过它拿，避免路径拼接散落各处。 */
    fun fileFor(localName: String): File = File(dir, localName)

    // ------------------------------------------------------------------ 清理

    /** 删除指定会话引用过的全部附件。应在删除会话 JSON 之前调用。 */
    suspend fun deleteForConversation(messages: List<StoredMessage>) =
        withContext(Dispatchers.IO) {
            messages.flatMap { it.attachments }.forEach { a ->
                runCatching { fileFor(a.localName).delete() }
            }
        }

    /** 清空全部附件（配合「清空全部历史」使用）。 */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        Unit
    }

    /**
     * 孤儿清理：删掉不被任何会话 JSON 引用的附件文件。
     *
     * 用在冷启动时兜底崩溃残留 —— 比如「附件已写入但会话 JSON 还没落盘」
     * 时进程被杀，就会留下没人引用的文件。
     *
     * 两个要点：
     * - **跳过最近 [GC_GRACE_MS] 内的文件**。否则会删掉「正在发送、文件刚写完、
     *   JSON 还没落盘」那种临时状态下的正常附件。
     * - 只在冷启动跑一次。这是 O(会话数) 的磁盘扫描，不适合高频调用。
     */
    suspend fun collectGarbage(conversationsDir: File) = withContext(Dispatchers.IO) {
        val files = dir.listFiles() ?: return@withContext
        if (files.isEmpty()) return@withContext

        val alive = mutableSetOf<String>()
        conversationsDir.listFiles { f -> f.name.endsWith(".json") }?.forEach { f ->
            runCatching {
                val messages = JSONObject(f.readText()).optJSONArray("messages") ?: return@runCatching
                for (i in 0 until messages.length()) {
                    val atts = messages.optJSONObject(i)?.optJSONArray("attachments") ?: continue
                    for (j in 0 until atts.length()) {
                        atts.optJSONObject(j)?.optString("localName")
                            ?.takeIf { it.isNotBlank() }?.let { alive += it }
                    }
                }
            }
        }

        val now = System.currentTimeMillis()
        files.forEach { f ->
            if (f.name in alive) return@forEach
            if (now - f.lastModified() < GC_GRACE_MS) return@forEach
            runCatching { f.delete() }
        }
        Unit
    }

    /**
     * 目录总大小软上限。
     *
     * 超过 [MAX_DIR_BYTES] 时按最后修改时间从旧到新删，直到降到 [TARGET_DIR_BYTES]。
     * 用「软上限 + 顺手清理」而不是硬拒绝发送：用户不会因为历史图片占满空间
     * 就突然发不出新消息。被清掉的图在历史里会显示「图片已不可用」占位。
     */
    suspend fun trimIfNeeded() = withContext(Dispatchers.IO) {
        if (totalSize() <= MAX_DIR_BYTES) return@withContext
        dir.listFiles()?.sortedBy { it.lastModified() }?.forEach { f ->
            if (totalSize() <= TARGET_DIR_BYTES) return@withContext
            runCatching { f.delete() }
        }
        Unit
    }

    private fun totalSize(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    // -------------------------------------------------------------- 内部实现

    private fun queryName(uri: Uri): String? = queryColumn(uri) { c ->
        c.getString(c.getColumnIndex(OpenableColumns.DISPLAY_NAME))
    }

    private fun querySize(uri: Uri): Long = queryColumn(uri) { c ->
        val idx = c.getColumnIndex(OpenableColumns.SIZE)
        if (idx < 0 || c.isNull(idx)) -1L else c.getLong(idx)
    } ?: -1L

    /**
     * 统一的 Cursor 查询。三个坑都要防：
     * 1. `query()` 可能返回 null（provider 不支持该 URI）
     * 2. 必须先 `moveToFirst()` 再读，否则 `getString` 会抛异常
     * 3. `getColumnIndex()` 返回 -1 表示该列不存在
     *
     * Cursor 用 `use` 保证关闭，绝不跨函数持有。
     */
    private fun <T> queryColumn(uri: Uri, read: (Cursor) -> T): T? = runCatching {
        appContext.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) read(c) else null
        }
    }.getOrNull()

    /**
     * 限量读：最多读 [limit] 字节，超一个字节就抛。
     *
     * 之所以不信任前面查到的 SIZE：部分 provider 返回 null 或不准确，
     * 直接 `readBytes()` 有可能把超大文件一次性读进内存导致 OOM。
     */
    private fun readLimited(input: InputStream, limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            total += n
            if (total > limit) {
                throw IllegalArgumentException("文件超过 ${humanSize(limit)} 上限，未读取")
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * 图片处理：按需压缩 + 生成缩略图。
     *
     * 返回 (最终字节, 缩略图, 提示文案)。
     *
     * 之所以要压缩：主流视觉模型会把长边缩放到 1568px 左右再切 patch，
     * 原图传 4000px 是纯粹的带宽和 token 浪费。
     *
     * 之所以能压：全程走「先读边界 → 采样解码 → 精确缩放」，
     * 绝不对原图做整图解码（一张 8000×6000 的图整解要 190MB，直接 OOM）。
     */
    private fun processImage(raw: ByteArray): Triple<ByteArray, Bitmap?, String?> {
        // ① 只读边界，不分配像素内存
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        val longSide = maxOf(bounds.outWidth, bounds.outHeight)
        // 尺寸读不出来（损坏或非图片）就原样返回，不阻断发送
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return Triple(raw, null, null)

        // 已经够小就不重编码：重编码小图往往反而变大
        if (longSide <= MAX_LONG_SIDE && raw.size <= COMPRESS_THRESHOLD) {
            return Triple(raw, decodeThumbnail(raw), null)
        }

        // ② inSampleSize 按 2 的幂粗降一档。用 highestOneBit 一次算出，
        //    不用循环 —— 采样率必须是 2 的幂，BitmapFactory 只认这个。
        val sample = Integer.highestOneBit((longSide / MAX_LONG_SIDE).coerceAtLeast(1))
        val decoded = BitmapFactory.decodeByteArray(
            raw, 0, raw.size,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return Triple(raw, decodeThumbnail(raw), null)

        // ③ 精确缩放到长边上限，再按质量 85 压成 JPEG
        val scaled = scaleToLongSide(decoded, MAX_LONG_SIDE)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        val bytes = out.toByteArray()

        if (scaled !== decoded) decoded.recycle()

        // 极端情况下重编码可能更大（比如本来就是高质量小图），那就用原图
        if (bytes.size >= raw.size) {
            return Triple(raw, decodeThumbnail(raw), null)
        }

        val note = "已压缩 ${humanSize(raw.size.toLong())} → ${humanSize(bytes.size.toLong())}"
        return Triple(bytes, decodeThumbnail(bytes), note)
    }

    private fun scaleToLongSide(src: Bitmap, maxSide: Int): Bitmap {
        val longSide = maxOf(src.width, src.height)
        if (longSide <= maxSide) return src
        val ratio = maxSide.toFloat() / longSide
        val w = (src.width * ratio).toInt().coerceAtLeast(1)
        val h = (src.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    /**
     * 生成预览缩略图：固定解到不超过 [target] 像素。
     *
     * 同样先读边界再算采样率 —— 缩略图绝不能整图解码。
     */
    private fun decodeThumbnail(bytes: ByteArray, target: Int = THUMB_TARGET): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= target) sample *= 2
        return runCatching {
            BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        }.getOrNull()
    }

    private fun extensionFor(mime: String, displayName: String): String = when (mime) {
        "image/jpeg" -> ".jpg"
        "image/png" -> ".png"
        "image/webp" -> ".webp"
        "image/gif" -> ".gif"
        else -> {
            val ext = displayName.substringAfterLast('.', "")
            if (ext.isBlank() || ext.length > 8 || !ext.all { it.isLetterOrDigit() }) ".txt"
            else ".${ext.lowercase()}"
        }
    }

    /** 按扩展名猜 MIME，用于 provider 返回通配或空的情况。 */
    private fun guessMime(fileName: String): String = when (
        fileName.substringAfterLast('.', "").lowercase()
    ) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "html", "htm" -> "text/html"
        "js", "kt", "kts", "java", "py", "c", "cpp", "h", "cs", "go", "rs",
        "rb", "php", "swift", "ts", "tsx", "jsx", "sh", "gradle", "md", "txt",
        "log", "yml", "yaml", "toml", "ini", "cfg", "properties", "sql" -> "text/plain"
        else -> "application/octet-stream"
    }

    private fun ensureDir() {
        if (!dir.exists() && !dir.mkdirs()) {
            throw java.io.IOException("无法创建附件目录：${dir.absolutePath}")
        }
    }

    companion object {
        const val DIR_NAME = "attachments"

        /** 图片长边上限。主流视觉模型的实际处理分辨率在这一档。 */
        private const val MAX_LONG_SIDE = 1568

        /** 小于这个体积就不重编码 —— 重编码小图往往反而变大。 */
        private const val COMPRESS_THRESHOLD = 1024L * 1024L

        private const val JPEG_QUALITY = 85

        /** 原始图片文件硬上限。 */
        private const val MAX_IMAGE_BYTES = 12L * 1024 * 1024

        /** 文本附件上限。比工作区读取的 512KB 更保守，因为要内联进 prompt。 */
        private const val MAX_TEXT_BYTES = 256L * 1024

        private const val THUMB_TARGET = 480

        /** 单次请求最多带几个附件，防止把请求体撑到几十 MB。 */
        const val MAX_PENDING = 6

        private const val MAX_DIR_BYTES = 200L * 1024 * 1024
        private const val TARGET_DIR_BYTES = 150L * 1024 * 1024

        /** GC 宽限期：跳过最近这段时间内修改过的文件，避开「已落盘但 JSON 未写」的窗口。 */
        private const val GC_GRACE_MS = 5L * 60 * 1000

        fun newKey(): String = UUID.randomUUID().toString().replace("-", "").take(16)

        /** 人类可读的体积，用于提示文案。 */
        fun humanSize(bytes: Long): String = when {
            bytes < 0 -> "未知大小"
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        }
    }
}
