package com.phoclaw.chat.data

import com.phoclaw.chat.util.CommandParser
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 技能文件的解析器。
 *
 * 支持三种格式，自动探测：
 *
 * **A. Markdown + YAML frontmatter**（从社区下载的技能多半长这样）
 * ```
 * ---
 * name: 代码审查
 * description: 按团队规范审查代码
 * actions: read, list, tree, search
 * ---
 * 提示词正文……
 * ```
 *
 * **B. 纯 JSON**
 * ```json
 * { "name": "...", "description": "...", "allowedActions": ["read"], "prompt": "..." }
 * ```
 *
 * **C. 纯 Markdown**（无 frontmatter）—— 文件名作名称，全文作提示词，不限制指令。
 *
 * 没引 YAML 库：frontmatter 只需要「键值对 + 逗号列表」两种结构，
 * 手写 30 行就能覆盖，比拉进来一个 snakeyaml 划算得多。
 */
object SkillParser {

    private const val MAX_PROMPT_CHARS = 20000

    /**
     * 从文件内容解析一个技能。
     *
     * @param fileName 用于推断名称与格式
     */
    fun parse(fileName: String, raw: String): Result<Skill> {
        val text = raw.trim()
        if (text.isEmpty()) return Result.failure(IllegalArgumentException("文件为空"))

        return if (text.startsWith("{") || text.startsWith("[")) {
            parseJson(fileName, text)
        } else {
            parseMarkdown(fileName, text)
        }
    }

    // ------------------------------------------------------------------ JSON

    private fun parseJson(fileName: String, text: String): Result<Skill> {
        return runCatching {
            // 顶层是数组时取第一个对象 —— 有些技能包把多个技能打在一个文件里，
            // 我们只取第一个，其余由用户再拆一次
            val trimmed = text.trim()
            val obj = if (trimmed.startsWith("[")) {
                val array = JSONArray(trimmed)
                if (array.length() == 0) throw IllegalArgumentException("JSON 数组为空")
                array.optJSONObject(0) ?: throw IllegalArgumentException("数组里没有对象")
            } else {
                JSONObject(trimmed)
            }

            val name = obj.optString("name").trim()
                .ifBlank { fallbackName(fileName) }
                .ifBlank { throw IllegalArgumentException("缺少 name 字段") }

            val description = obj.optString("description").ifBlank {
                obj.optString("desc")
            }.trim()

            // 两种键名都认：allowedActions（我们的规范名）/ actions（社区习惯）
            val actionsRaw = obj.optJSONArray("allowedActions")
                ?: obj.optJSONArray("actions")

            val actions = if (actionsRaw != null) {
                (0 until actionsRaw.length()).map { actionsRaw.optString(it) }
            } else {
                // 也接受逗号分隔的字符串写法，宽容一点
                obj.optString("allowedActions").ifBlank { obj.optString("actions") }
                    .split(',').map { it.trim() }.filter { it.isNotEmpty() }
            }

            val prompt = obj.optString("prompt").ifBlank { obj.optString("instructions") }
            if (prompt.isBlank()) {
                throw IllegalArgumentException("缺少 prompt 字段（提示词正文）")
            }

            build(fileName, name, description, prompt, actions)
        }
    }

    // -------------------------------------------------------------- Markdown

    private fun parseMarkdown(fileName: String, text: String): Result<Skill> = runCatching {
        val (header, body) = splitFrontmatter(text)

        if (header == null) {
            // 格式 C：没有 frontmatter，整篇当提示词
            val name = fallbackName(fileName)
            if (name.isBlank()) throw IllegalArgumentException("无法从文件名推断技能名称")
            if (body.isBlank()) throw IllegalArgumentException("文件内容为空")
            return@runCatching build(fileName, name, "", body, emptyList())
        }

        val fields = parseSimpleYaml(header)

        val name = fields["name"].orEmpty().trim()
            .ifBlank { fallbackName(fileName) }
            .ifBlank { throw IllegalArgumentException("frontmatter 缺少 name") }

        val description = fields["description"].orEmpty().trim()
            .ifBlank { fields["desc"].orEmpty().trim() }

        // actions 支持三种写法：逗号分隔、方括号数组、空格分隔
        val actionsRaw = fields["actions"] ?: fields["allowedactions"]
        ?: fields["allowed_actions"]
        val actions = actionsRaw.orEmpty()
            .removeSurrounding("[", "]")
            .split(',', ' ')
            .map { it.trim().trim('"', '\'') }
            .filter { it.isNotEmpty() }

        if (body.isBlank()) throw IllegalArgumentException("frontmatter 之后的提示词正文为空")

        build(fileName, name, description, body, actions)
    }

    /**
     * 切出 frontmatter 与正文。
     *
     * 只在文件**开头**是 `---` 时才算 frontmatter —— Markdown 里的水平分割线
     * 也是 `---`，不限制位置会把正文切成两半。
     */
    private fun splitFrontmatter(text: String): Pair<String?, String> {
        val lines = text.lines()
        if (lines.isEmpty() || lines[0].trim() != "---") return null to text

        // 找闭合的 ---
        val endIndex = (1 until lines.size).firstOrNull { lines[it].trim() == "---" }
            ?: return null to text   // 没有闭合，当作普通正文

        val header = lines.subList(1, endIndex).joinToString("\n")
        val body = lines.subList(endIndex + 1, lines.size).joinToString("\n").trim()
        return header to body
    }

    /**
     * 极简 YAML 解析：只处理顶层 `key: value`。
     *
     * 不支持嵌套、多行字符串、锚点引用 —— frontmatter 里用不到，
     * 而且真需要这些的复杂技能用 JSON 格式更合适。
     */
    private fun parseSimpleYaml(header: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        header.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val colon = trimmed.indexOf(':')
            if (colon <= 0) return@forEach
            val key = trimmed.substring(0, colon).trim().lowercase()
            val value = trimmed.substring(colon + 1).trim()
            map[key] = value.trim('"', '\'')
        }
        return map
    }

    // ------------------------------------------------------------------ 组装

    /**
     * 校验动作名并组装技能。
     *
     * **未知动作名一律报错**，不静默忽略。理由是安全：
     * 用户写 `actions: read, writex`，如果静默丢掉 writex，
     * 用户会以为限制生效了，实际白名单只剩 read —— 结果比预期更严（相对安全），
     * 但反过来若是 `actions: readx` 被打包器写错，静默后变成空集 = 不限制，
     * 就完全没有防护了。所以宁可报错让用户改。
     */
    private fun build(
        fileName: String,
        name: String,
        description: String,
        prompt: String,
        rawActions: List<String>
    ): Skill {
        val actions = mutableSetOf<String>()
        val unknown = mutableListOf<String>()

        rawActions.forEach { raw ->
            if (raw.isBlank()) return@forEach
            val action = CommandParser.actionFromAlias(raw)
            if (action == null) unknown += raw
            else actions += CommandParser.actionName(action)
        }

        if (unknown.isNotEmpty()) {
            throw IllegalArgumentException(
                "含未知指令 ${unknown.joinToString("、")}；" +
                        "合法值：${CommandParser.ALL_ACTION_NAMES.joinToString("、")}"
            )
        }

        val cleanPrompt = if (prompt.length > MAX_PROMPT_CHARS) {
            prompt.take(MAX_PROMPT_CHARS) + "\n\n（提示词过长，已截断）"
        } else {
            prompt
        }

        return Skill(
            id = newId(),
            name = name.take(60),
            description = description.take(200),
            prompt = cleanPrompt,
            allowedActions = actions,
            enabled = true,
            source = fileName,
            importedAt = System.currentTimeMillis()
        )
    }

    /** 从文件名推断技能名（去掉扩展名）。 */
    private fun fallbackName(fileName: String): String =
        fileName.substringBeforeLast('.').trim()

    private fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)
}
