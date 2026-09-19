package com.phoclaw.chat.data

/**
 * 附件种类。
 *
 * 两类走完全不同的通道：
 * - [IMAGE] 转 Base64 后走多模态 `image_url`，且**只发当前轮**（历史图片不重发，否则上下文暴涨）
 * - [TEXT]  内容全文内联进上下文，且**常驻**（用户希望 AI 在多轮里反复引用代码）
 */
enum class AttachmentKind { IMAGE, TEXT }

/**
 * 一条附件的元信息 —— 这是**唯一被持久化**的形态。
 *
 * [localName] 是 `files/attachments/` 下的文件名，会话 JSON 里只存它，
 * 不存 Base64、也不存原始 `content://` URI。
 *
 * 为什么不直接存原始 URI：SAF 单文件 URI 的读权限只活到进程结束
 * （逐条申请持久化权限在混选图片/文本时很脆弱）。复制到私有目录是一次性的确定行为，
 * 重启后读自己的文件永不失效。
 */
data class Attachment(
    /** `files/attachments/` 下的文件名，同时充当稳定 id。 */
    val localName: String,
    /** 展示给用户看的原始文件名，如 `screenshot.png`。 */
    val displayName: String,
    /** 归一化后的 MIME，如 `image/jpeg`、`text/plain`。 */
    val mime: String,
    /** 落盘后的实际字节数（图片为压缩后的大小）。 */
    val size: Long,
    val kind: AttachmentKind
) {
    /** 兜底判断，避免把非图片当成图片往多模态通道里塞。 */
    val isImage: Boolean get() = kind == AttachmentKind.IMAGE
}

/**
 * 待发送附件：还没落盘的暂存态（输入框上方预览的那一条）。
 *
 * 只在内存里存在，不进 JSON。用户点发送时才由
 * [AttachmentRepository.persist] 写入 `files/attachments/`。
 */
data class PendingAttachment(
    /** 稳定 key，用于 LazyRow 的 key 和删除定位。 */
    val key: String,
    val displayName: String,
    val mime: String,
    val kind: AttachmentKind,
    /** 已读到的字节。图片是**压缩后**的内容。 */
    val bytes: ByteArray,
    /** 预览用缩略图，仅图片有值。 */
    val thumbnail: android.graphics.Bitmap? = null,
    /** 处理提示，如「已压缩 4.2MB → 380KB」，用于回显给用户。 */
    val note: String? = null
) {
    val size: Long get() = bytes.size.toLong()

    /**
     * 必须手写 equals / hashCode。
     *
     * 这个类含 [ByteArray] 和 [Bitmap]，如果沿用 data class 自动生成的实现，
     * 每次 Compose 重组都会因为数组引用不同而判定「列表变了」，触发全量刷新；
     * 而且逐字节比较大的数组本身也很贵。只用 [key] 判断身份即可 ——
     * 同一个 key 的附件内容不会变。
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PendingAttachment) return false
        return key == other.key
    }

    override fun hashCode(): Int = key.hashCode()
}
