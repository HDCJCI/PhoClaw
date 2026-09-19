package com.phoclaw.chat.util

/**
 * 从模型回复中提取工作区操作指令。
 *
 * 约定格式（见 CredentialStore.DEFAULT_SYSTEM_PROMPT）：
 * ```phoclaw:write src/main.js
 * ...文件内容...
 * ```
 * ```phoclaw:read src/main.js
 * ```
 * ```phoclaw:list .
 * ```
 *
 * 同时兼容旧前缀 `espclaw:`，避免历史对话中的格式失效。
 */
object CommandParser {

    /** 一条解析出的指令。 */
    data class Command(
        val action: Action,
        val path: String,
        val content: String,
        /** copy / move 的目标路径；其余指令为空。 */
        val target: String = ""
    ) {
        enum class Action {
            WRITE, READ, LIST, DELETE, MKDIR, APPEND, COPY, MOVE, SEARCH, INFO, TREE,

            /**
             * 联网搜索。
             *
             * 与 [SEARCH] 是两回事：[SEARCH] 搜的是工作区里的文件内容，
             * 这个搜的是互联网。动作名不能复用 —— 复用会让模型分不清，
             * 也会让「技能白名单里放开 search」这个意图变得含糊
             *（用户想放开的是本地文件搜索，不是让 AI 上网）。
             */
            WEBSEARCH
        }
    }

    /** 解析结果：去掉指令块后的展示文本 + 待执行指令列表。 */
    data class ParseResult(
        val displayText: String,
        val commands: List<Command>
    )

    private const val PREFIX = "(?:phoclaw|espclaw)"
    private const val ACTIONS =
        "write|read|list|ls|delete|del|rm|mkdir|md|append|copy|cp|move|mv|" +
                "search|grep|find|info|stat|tree|" +
                // 联网搜索。web_search / webs / netsearch 都收，
                // 因为模型未必每次都写对那个词
                "websearch|web_search|wsearch|webs|netsearch"

    private val BLOCK_REGEX = Regex(
        """```$PREFIX:($ACTIONS)\s*([^\n`]*)\n?([\s\S]*?)```""",
        RegexOption.IGNORE_CASE
    )

    /** 把各种写法归一到内部动作。技能白名单解析也用它，所以是公开的。 */
    fun actionFromAlias(raw: String): Command.Action? = when (raw.trim().lowercase()) {
        "write" -> Command.Action.WRITE
        "read" -> Command.Action.READ
        "list", "ls" -> Command.Action.LIST
        "delete", "del", "rm" -> Command.Action.DELETE
        "mkdir", "md" -> Command.Action.MKDIR
        "append" -> Command.Action.APPEND
        "copy", "cp" -> Command.Action.COPY
        "move", "mv" -> Command.Action.MOVE
        "search", "grep", "find" -> Command.Action.SEARCH
        "websearch", "web_search", "wsearch", "webs", "netsearch" -> Command.Action.WEBSEARCH
        "info", "stat" -> Command.Action.INFO
        "tree" -> Command.Action.TREE
        else -> null
    }

    /**
     * 动作的规范名，用于技能白名单比对。
     *
     * 必须与 [actionFromAlias] 的主名一致 —— 白名单里存的是规范名，
     * 用户用 `rm` 还是 `delete` 写指令都归一到同一个值。
     */
    fun actionName(action: Command.Action): String = when (action) {
        Command.Action.WRITE -> "write"
        Command.Action.READ -> "read"
        Command.Action.LIST -> "list"
        Command.Action.DELETE -> "delete"
        Command.Action.MKDIR -> "mkdir"
        Command.Action.APPEND -> "append"
        Command.Action.COPY -> "copy"
        Command.Action.MOVE -> "move"
        Command.Action.SEARCH -> "search"
        Command.Action.WEBSEARCH -> "websearch"
        Command.Action.INFO -> "info"
        Command.Action.TREE -> "tree"
    }

    /** 全部合法动作名，用于给用户提示。 */
    val ALL_ACTION_NAMES: List<String> = Command.Action.entries.map { actionName(it) }

    fun parse(raw: String): ParseResult {
        val commands = mutableListOf<Command>()

        BLOCK_REGEX.findAll(raw).forEach { match ->
            val action = actionFromAlias(match.groupValues[1]) ?: return@forEach
            val rawArg = match.groupValues[2].trim()
            val body = match.groupValues[3].trim('\n')

            // copy / move 的参数是「源 目标」，用空格或 -> 分隔
            var path = rawArg
            var target = ""
            if (action == Command.Action.COPY || action == Command.Action.MOVE) {
                val parts = splitTwoArgs(rawArg)
                path = parts.first
                target = parts.second
            }

            commands += Command(action, path.ifBlank { "." }, body, target)
        }

        // 展示文本里把指令块替换成人类可读的一行摘要
        val display = BLOCK_REGEX.replace(raw) { match ->
            val action = actionFromAlias(match.groupValues[1])
            val arg = match.groupValues[2].trim()
            val label = when (action) {
                Command.Action.WRITE -> "写入"
                Command.Action.READ -> "读取"
                Command.Action.LIST -> "列出"
                Command.Action.DELETE -> "删除"
                Command.Action.MKDIR -> "新建目录"
                Command.Action.APPEND -> "追加"
                Command.Action.COPY -> "复制"
                Command.Action.MOVE -> "移动"
                Command.Action.SEARCH -> "搜索"
                Command.Action.WEBSEARCH -> "联网搜索"
                Command.Action.INFO -> "详情"
                Command.Action.TREE -> "目录树"
                null -> match.groupValues[1]
            }
            "\n\ud83d\udee0\ufe0f [$label] $arg\n"
        }.trim()

        return ParseResult(display, commands)
    }

    /** 拆分双参数指令，支持 "源 目标" 和 "源 -> 目标" 两种写法。 */
    private fun splitTwoArgs(raw: String): Pair<String, String> {
        val arrow = raw.split("->", "=>")
        if (arrow.size == 2) {
            return arrow[0].trim() to arrow[1].trim()
        }
        // 按空白拆成两段（路径含空格的情况极少，优先取前两段）
        val parts = raw.split(Regex("\\s+")).filter { it.isNotBlank() }
        return when {
            parts.size >= 2 -> parts[0] to parts[1]
            parts.size == 1 -> parts[0] to ""
            else -> "" to ""
        }
    }
}
