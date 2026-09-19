package com.phoclaw.chat.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** 一个会话的元信息（不含消息正文），用于历史列表展示。 */
data class ConversationMeta(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int
)

/** 落盘的完整会话。 */
data class StoredConversation(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messages: List<StoredMessage>
)

/**
 * 一条落盘的消息。
 *
 * [role] 用字符串保存而不是枚举，方便以后换协议时不怕旧数据读不出来。
 * [isInternal] 的消息（工具结果回灌、附件内容注入）也会存下来——它们不进界面，
 * 但重开会话后要让模型看到之前的操作结果与附件内容，否则上下文会断。
 */
data class StoredMessage(
    val role: String,
    val text: String,
    val isError: Boolean = false,
    val toolLog: List<String> = emptyList(),
    val isInternal: Boolean = false,
    /** 本条消息携带的附件。旧数据没有这个字段，默认空列表即可兼容。 */
    val attachments: List<Attachment> = emptyList()
)

/**
 * 对话的本地持久化。
 *
 * 每个会话存成 `files/conversations/<id>.json`，另有一份 `index.json` 作为索引
 * （只放元信息，列表页读它即可，不必把每个会话的正文全解析一遍）。
 *
 * 不用数据库是刻意的：会话数量级在几百以内，JSON 读写够快；
 * 少了 Room 的注解处理器和版本迁移负担，也方便用户直接导出查看。
 */
class ConversationStore(context: Context) {

    private val root = File(context.applicationContext.filesDir, DIR_NAME)
    private val indexFile = File(root, INDEX_FILE)

    // ------------------------------------------------------------------ 读

    /** 读取会话索引，按更新时间倒序（最近的在前）。 */
    suspend fun listConversations(): List<ConversationMeta> = withContext(Dispatchers.IO) {
        readIndex().sortedByDescending { it.updatedAt }
    }

    /** 读取单个会话的完整内容；文件损坏或不存在时返回 null。 */
    suspend fun load(id: String): StoredConversation? = withContext(Dispatchers.IO) {
        val file = File(root, "$id.json")
        if (!file.exists()) return@withContext null
        runCatching { parseConversation(file.readText()) }.getOrNull()
    }

    // ------------------------------------------------------------------ 写

    /**
     * 保存（或覆盖）一个会话。
     *
     * 先写临时文件再原子替换，避免写到一半进程被杀导致会话文件损坏——
     * 对话记录丢了比多一次 rename 的代价大得多。
     */
    suspend fun save(conversation: StoredConversation) = withContext(Dispatchers.IO) {
        ensureDir()
        val target = File(root, "${conversation.id}.json")
        val tmp = File(root, "${conversation.id}.json.tmp")
        tmp.writeText(serializeConversation(conversation))
        if (!tmp.renameTo(target)) {
            // 某些文件系统上 rename 会失败，退回直接覆盖
            target.writeText(tmp.readText())
            tmp.delete()
        }
        updateIndex(conversation)
    }

    /** 删除一个会话及其索引项。 */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(root, "$id.json").delete()
        File(root, "$id.json.tmp").delete()
        val rest = readIndex().filterNot { it.id == id }
        writeIndex(rest)
    }

    /** 清空全部历史。 */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        root.listFiles()?.forEach { it.delete() }
    }

    /** 某一会话是否已落盘（用来区分「新建但还没说话」的空会话）。 */
    suspend fun exists(id: String): Boolean = withContext(Dispatchers.IO) {
        File(root, "$id.json").exists()
    }

    // ------------------------------------------------------------ 索引维护

    private fun updateIndex(conversation: StoredConversation) {
        val meta = ConversationMeta(
            id = conversation.id,
            title = conversation.title,
            updatedAt = conversation.updatedAt,
            messageCount = conversation.messages.size
        )
        val rest = readIndex().filterNot { it.id == conversation.id }
        writeIndex(rest + meta)
    }

    private fun readIndex(): List<ConversationMeta> {
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(indexFile.readText())
            (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let { obj ->
                    val id = obj.optString("id")
                    if (id.isBlank()) null
                    else ConversationMeta(
                        id = id,
                        title = obj.optString("title"),
                        updatedAt = obj.optLong("updatedAt"),
                        messageCount = obj.optInt("messageCount")
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun writeIndex(list: List<ConversationMeta>) {
        ensureDir()
        val array = JSONArray()
        list.forEach { meta ->
            array.put(JSONObject().apply {
                put("id", meta.id)
                put("title", meta.title)
                put("updatedAt", meta.updatedAt)
                put("messageCount", meta.messageCount)
            })
        }
        indexFile.writeText(array.toString())
    }

    // -------------------------------------------------------------- 序列化

    private fun serializeConversation(conversation: StoredConversation): String {
        val messages = JSONArray()
        conversation.messages.forEach { msg ->
            messages.put(JSONObject().apply {
                put("role", msg.role)
                put("text", msg.text)
                put("isError", msg.isError)
                put("isInternal", msg.isInternal)
                if (msg.toolLog.isNotEmpty()) {
                    put("toolLog", JSONArray().apply { msg.toolLog.forEach { put(it) } })
                }
                // 仅在非空时才写，避免给每个旧格式消息塞一个空数组把 JSON 撑大
                if (msg.attachments.isNotEmpty()) {
                    put("attachments", JSONArray().apply {
                        msg.attachments.forEach { a ->
                            put(JSONObject().apply {
                                put("localName", a.localName)
                                put("displayName", a.displayName)
                                put("mime", a.mime)
                                put("size", a.size)
                                put("kind", a.kind.name)
                            })
                        }
                    })
                }
            })
        }
        return JSONObject().apply {
            put("id", conversation.id)
            put("title", conversation.title)
            put("updatedAt", conversation.updatedAt)
            put("messages", messages)
        }.toString()
    }

    private fun parseConversation(raw: String): StoredConversation {
        val json = JSONObject(raw)
        val array = json.optJSONArray("messages") ?: JSONArray()
        val messages = (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let { obj ->
                val logArray = obj.optJSONArray("toolLog")
                val attArray = obj.optJSONArray("attachments")
                StoredMessage(
                    role = obj.optString("role"),
                    text = obj.optString("text"),
                    isError = obj.optBoolean("isError"),
                    toolLog = if (logArray == null) emptyList()
                    else (0 until logArray.length()).map { logArray.optString(it) },
                    isInternal = obj.optBoolean("isInternal"),
                    attachments = parseAttachments(attArray)
                )
            }
        }
        return StoredConversation(
            id = json.optString("id"),
            title = json.optString("title"),
            updatedAt = json.optLong("updatedAt"),
            messages = messages
        )
    }

    /**
     * 解析附件数组。
     *
     * 全程用 opt* 系列宽松读取：旧版本写的会话没有这个字段，
     * 或者某条附件缺字段，都不应该让整个会话读不出来。
     */
    private fun parseAttachments(array: JSONArray?): List<Attachment> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let { obj ->
                val localName = obj.optString("localName")
                if (localName.isBlank()) return@let null
                val kindName = obj.optString("kind")
                Attachment(
                    localName = localName,
                    displayName = obj.optString("displayName").ifBlank { localName },
                    mime = obj.optString("mime").ifBlank { "application/octet-stream" },
                    size = obj.optLong("size"),
                    // 未知 kind 一律当文本处理：当文本最多是内容读不出来，
                    // 当图片则会往多模态通道塞垃圾，后果更严重
                    kind = if (kindName == AttachmentKind.IMAGE.name) AttachmentKind.IMAGE
                    else AttachmentKind.TEXT
                )
            }
        }
    }

    private fun ensureDir() {
        if (!root.exists() && !root.mkdirs()) {
            throw IOException("无法创建对话存储目录：${root.absolutePath}")
        }
    }

    companion object {
        private const val DIR_NAME = "conversations"
        private const val INDEX_FILE = "index.json"

        /**
         * 从首条用户消息生成会话标题。
         *
         * 取首行的前 [MAX_TITLE] 个字符。之所以不调用模型来总结标题，
         * 是因为那会给每句话多加一次 API 请求和等待——本地截断够用了。
         */
        fun buildTitle(firstUserMessage: String): String {
            val line = firstUserMessage.trim().lineSequence()
                .firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            if (line.isEmpty()) return "新对话"
            val cleaned = line.replace(Regex("\\s+"), " ")
            return if (cleaned.length <= MAX_TITLE) cleaned
            else cleaned.take(MAX_TITLE) + "…"
        }

        private const val MAX_TITLE = 24
    }
}
