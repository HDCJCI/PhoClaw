package com.phoclaw.chat.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 自动化任务的本地持久化。
 *
 * 同样是 `files/automations/<id>.json` + `index.json` 的结构，
 * 全程 `opt*` 宽松读 —— 以后加字段不会让旧任务读不出来。
 */
class AutomationStore(context: Context) {

    private val root = File(context.applicationContext.filesDir, DIR_NAME)
    private val indexFile = File(root, INDEX_FILE)

    // ------------------------------------------------------------------ 读

    suspend fun list(): List<AutomationTask> = withContext(Dispatchers.IO) {
        runCatching { readAll() }.getOrDefault(emptyList())
    }

    suspend fun load(id: String): AutomationTask? = withContext(Dispatchers.IO) {
        val file = File(root, "$id.json")
        if (!file.exists()) return@withContext null
        runCatching { parse(file.readText()) }.getOrNull()
    }

    /** 所有启用的任务，开机恢复时用。 */
    suspend fun enabled(): List<AutomationTask> = list().filter { it.enabled }

    // ------------------------------------------------------------------ 写

    suspend fun save(task: AutomationTask) = withContext(Dispatchers.IO) {
        ensureDir()
        val target = File(root, "${task.id}.json")
        val tmp = File(root, "${task.id}.json.tmp")
        tmp.writeText(serialize(task))
        if (!tmp.renameTo(target)) {
            target.writeText(tmp.readText())
            tmp.delete()
        }
        updateIndex(task.id)
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(root, "$id.json").delete()
        File(root, "$id.json.tmp").delete()
        updateIndex(id, remove = true)
    }

    /**
     * 只更新执行状态字段，不碰任务的其它配置。
     *
     * 单独开这个方法是因为执行发生在后台服务里：如果直接 save 整个对象，
     * 而用户恰好在执行期间改了任务的 cron 或描述，就会产生「后写覆盖先写」，
     * 把用户的修改冲掉。只写这三个字段能把这个窗口缩到最小。
     */
    suspend fun updateRunState(
        id: String,
        lastRunAt: Long,
        result: String,
        success: Boolean
    ) = withContext(Dispatchers.IO) {
        val current = load(id) ?: return@withContext
        save(
            current.copy(
                lastRunAt = lastRunAt,
                lastResult = result.take(200),
                lastSuccess = success
            )
        )
    }

    // -------------------------------------------------------------- 索引维护

    private fun readIndexIds(): List<String> {
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(indexFile.readText())
            (0 until array.length()).mapNotNull { i ->
                array.optString(i).takeIf { it.isNotBlank() }
            }
        }.getOrDefault(emptyList())
    }

    private fun updateIndex(id: String, remove: Boolean = false) {
        val ids = readIndexIds().toMutableList()
        if (remove) ids.remove(id) else if (id !in ids) ids.add(id)
        indexFile.writeText(JSONArray().apply { ids.forEach { put(it) } }.toString())
    }

    // -------------------------------------------------------------- 序列化

    private fun serialize(task: AutomationTask): String = JSONObject().apply {
        put("id", task.id)
        put("title", task.title)
        put("summary", task.summary)
        put("prompt", task.prompt)
        put("cron", task.cron)
        put("allowDangerous", task.allowDangerous)
        put("enabled", task.enabled)
        put("createdAt", task.createdAt)
        put("lastRunAt", task.lastRunAt)
        put("lastResult", task.lastResult)
        put("lastSuccess", task.lastSuccess)
        put("conversationId", task.conversationId)
    }.toString()

    private fun parse(raw: String): AutomationTask? = runCatching {
        val obj = JSONObject(raw)
        val id = obj.optString("id")
        if (id.isBlank()) return@runCatching null
        AutomationTask(
            id = id,
            title = obj.optString("title").ifBlank { "未命名任务" },
            summary = obj.optString("summary"),
            prompt = obj.optString("prompt"),
            cron = obj.optString("cron"),
            // 默认 false：旧数据没有这个字段时按最保守的方式处理
            allowDangerous = obj.optBoolean("allowDangerous", false),
            enabled = obj.optBoolean("enabled", true),
            createdAt = obj.optLong("createdAt"),
            lastRunAt = obj.optLong("lastRunAt"),
            lastResult = obj.optString("lastResult"),
            lastSuccess = obj.optBoolean("lastSuccess", true),
            conversationId = obj.optString("conversationId")
        )
    }.getOrNull()

    private fun readAll(): List<AutomationTask> {
        if (!root.exists()) return emptyList()

        val ids = readIndexIds()
        val files = if (ids.isNotEmpty()) {
            ids.mapNotNull { id -> File(root, "$id.json").takeIf { it.exists() } }
        } else {
            root.listFiles { f -> f.isFile && f.name.endsWith(".json") && f.name != INDEX_FILE }
                ?.toList().orEmpty()
        }

        return files.mapNotNull { runCatching { parse(it.readText()) }.getOrNull() }
    }

    private fun ensureDir() {
        if (!root.exists()) root.mkdirs()
    }

    private companion object {
        const val DIR_NAME = "automations"
        const val INDEX_FILE = "index.json"
    }
}
