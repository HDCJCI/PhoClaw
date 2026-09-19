package com.phoclaw.chat.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 技能的本地持久化。
 *
 * 结构与 [ConversationStore] 一致：`files/skills/<id>.json` 每个技能一个文件，
 * 外加 `index.json` 存轻量元信息。技能数量很少（几十个以内），
 * 内存里直接缓存全量列表，激活状态变了才重新读盘。
 */
class SkillStore(context: Context) {

    private val root = File(context.applicationContext.filesDir, DIR_NAME)
    private val indexFile = File(root, INDEX_FILE)

    /**
     * 全量技能缓存。
     *
     * [MainViewModel.buildApiMessages] 每轮对话都要取一次激活技能，
     * 走磁盘的话 8 轮循环就是 8 次 IO。用缓存挡住。
     * [@Volatile] 是因为后台的 [HeadlessTurnRunner] 也会读它（在另一个线程上）。
     */
    @Volatile
    private var cache: List<Skill>? = null

    // ------------------------------------------------------------------ 读

    /** 读取全部技能（含禁用）。 */
    suspend fun list(forceReload: Boolean = false): List<Skill> = withContext(Dispatchers.IO) {
        cache?.takeIf { !forceReload }?.let { return@withContext it }
        val loaded = runCatching { readAll() }.getOrDefault(emptyList())
        cache = loaded
        loaded
    }

    /** 已激活的技能，供提示词合成与白名单使用。 */
    suspend fun enabled(): List<Skill> = list().filter { it.enabled }

    // ------------------------------------------------------------------ 写

    suspend fun save(skill: Skill) = withContext(Dispatchers.IO) {
        ensureDir()
        val target = File(root, "${skill.id}.json")
        val tmp = File(root, "${skill.id}.json.tmp")
        tmp.writeText(serialize(skill))
        // 与对话存储一致：先写临时文件再原子替换，避免写一半被杀导致技能损坏
        if (!tmp.renameTo(target)) {
            target.writeText(tmp.readText())
            tmp.delete()
        }
        cache = null
    }

    /** 批量保存，用于一次导入多个文件。 */
    suspend fun saveAll(skills: List<Skill>) {
        skills.forEach { save(it) }
        cache = null
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(root, "$id.json").delete()
        File(root, "$id.json.tmp").delete()
        cache = null
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        root.listFiles()?.forEach { it.delete() }
        cache = null
    }

    /**
     * 开关某个技能。
     *
     * 直接改文件而不是只改缓存 —— 进程被杀后重开，勾选状态必须还在。
     */
    suspend fun setEnabled(id: String, enabled: Boolean) {
        val target = list(forceReload = true).firstOrNull { it.id == id } ?: return
        save(target.copy(enabled = enabled))
    }

    /** 供界面读取文件位置（不需要，技能没有附件那样的外部资源，留作扩展）。 */
    fun dir(): File = root

    // -------------------------------------------------------------- 序列化

    private fun serialize(skill: Skill): String = JSONObject().apply {
        put("id", skill.id)
        put("name", skill.name)
        put("description", skill.description)
        put("prompt", skill.prompt)
        put("allowedActions", JSONArray().apply {
            // 排序后写盘，文件内容稳定，方便用户自己看和 diff
            skill.allowedActions.sorted().forEach { put(it) }
        })
        put("enabled", skill.enabled)
        put("source", skill.source)
        put("importedAt", skill.importedAt)
    }.toString()

    private fun parse(raw: String): Skill? = runCatching {
        val obj = JSONObject(raw)
        val id = obj.optString("id")
        if (id.isBlank()) return@runCatching null

        val actionsArray = obj.optJSONArray("allowedActions")
        val actions = if (actionsArray == null) emptySet()
        else (0 until actionsArray.length()).map { actionsArray.optString(it) }
            .filter { it.isNotBlank() }.toSet()

        Skill(
            id = id,
            name = obj.optString("name").ifBlank { "未命名技能" },
            description = obj.optString("description"),
            prompt = obj.optString("prompt"),
            allowedActions = actions,
            enabled = obj.optBoolean("enabled", true),
            source = obj.optString("source"),
            importedAt = obj.optLong("importedAt")
        )
    }.getOrNull()

    private fun readAll(): List<Skill> {
        if (!root.exists()) return emptyList()

        // 先读索引（快），索引缺失或为空时退回扫描目录
        val ids = readIndexIds()
        val files = if (ids.isNotEmpty()) {
            ids.mapNotNull { id ->
                File(root, "$id.json").takeIf { it.exists() }
            }
        } else {
            root.listFiles { f -> f.isFile && f.name.endsWith(".json") && f.name != INDEX_FILE }
                ?.toList().orEmpty()
        }

        // 单个文件坏掉不影响其余技能：parse 里已经吞掉异常返回 null
        return files.mapNotNull { runCatching { parse(it.readText()) }.getOrNull() }
            .sortedBy { it.importedAt }
    }

    private fun readIndexIds(): List<String> {
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(indexFile.readText())
            (0 until array.length()).mapNotNull { i ->
                array.optString(i).takeIf { it.isNotBlank() }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeIndex() {
        ensureDir()
        val ids = root.listFiles { f ->
            f.isFile && f.name.endsWith(".json") && f.name != INDEX_FILE
        }?.map { it.nameWithoutExtension }?.sorted().orEmpty()
        indexFile.writeText(JSONArray().apply { ids.forEach { put(it) } }.toString())
    }

    private fun ensureDir() {
        if (!root.exists()) root.mkdirs()
        writeIndex()
    }

    private companion object {
        const val DIR_NAME = "skills"
        const val INDEX_FILE = "index.json"
    }
}
