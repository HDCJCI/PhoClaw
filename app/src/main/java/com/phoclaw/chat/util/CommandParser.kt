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
            WEBSEARCH,

            /**
             * 抓取指定网址的网页正文。
             *
             * 与 [WEBSEARCH] 的分工：SEARCH 是「我不知道在哪，帮我找」，
             * FETCH 是「我知道网址，把内容读给我」。用户贴一个链接过来说
             * 「看看这个」时，用 FETCH 而不是把链接丢给搜索。
             */
            WEBFETCH,

            /**
             * 发任意 HTTP 请求。
             *
             * 这是权限最高的指令：能把数据发到任意服务器。写操作
             *（POST/PUT/PATCH/DELETE）执行前需要用户确认。
             */
            HTTP,

            /**
             * 运行工作区里的代码。
             *
             * 走远程执行（Wandbox），因为 Android 系统里没有任何语言运行时：
             * 没有 python / node / clang，系统只带了 `/system/bin/sh`。
             * 而且 Android 10+ 的 W^X 策略禁止执行可写目录里的 ELF，
             * 所以连用户自己放进去的二进制也跑不了。
             */
            RUN,

            /**
             * 在**本机**执行 shell 命令。
             *
             * 与 [RUN] 是两条完全不同的路：
             * - [RUN] 远程容器，能编译能跑 30 多种语言，但看不到工作区、代码要出设备；
             * - [SHELL] 就在这台手机上，能直接翻工作区文件、数据不出设备，
             *   但只有系统自带的 toybox（没有 python / node / clang）。
             *
             * 之所以不复用 [RUN] 加个参数：两者的能力、风险、开关都不一样
             *（远程执行 vs 本地执行权），混在一个动作里会让「技能白名单只放开
             * 本地命令」这种诉求没法表达。
             */
            SHELL,

            /**
             * 读写跨会话的长期记忆（`MEMORY.md`）。
             *
             * 这是唯一一个**不受工作区影响的**写入型指令：记忆存在 App 私有
             * 目录里，换工作区、甚至没授权工作区，都照样能记。
             *
             * 与 [WRITE] 的区别要说清：`write` 写的是工作区文件（用户能看见、
             * 内容随项目走），`memory` 写的是一段会**永久注入每轮提示词**的文字
             * （用户不一定看得见，但会影响之后所有对话）。
             */
            MEMORY
        }
    }

    /** 解析结果：去掉指令块后的展示文本 + 待执行指令列表。 */
    data class ParseResult(
        val displayText: String,
        val commands: List<Command>
    )

    private const val PREFIX = "(?:phoclaw|espclaw)"

    /**
     * 动作名分支从 [ToolCatalog] 生成。
     *
     * 别名只在那里维护一份，这里不再手写长字符串 —— 以前加一个别名
     * 要同时改正则和 `actionFromAlias`，漏一处就会出现「正则匹配得上、
     * 映射查不到」的静默失败。
     *
     * 用 `by lazy` 避免 object 初始化顺序问题（ToolCatalog 反过来引用了
     * [Command.Action]）。
     */
    private val actionPattern: String by lazy { ToolCatalog.actionPattern }

    private val BLOCK_REGEX: Regex by lazy {
        Regex(
            """```$PREFIX:($actionPattern)\s*([^\n`]*)\n?([\s\S]*?)```""",
            RegexOption.IGNORE_CASE
        )
    }

    /** 把各种写法归一到内部动作。技能白名单解析也用它，所以是公开的。 */
    fun actionFromAlias(raw: String): Command.Action? =
        ToolCatalog.aliasToAction[raw.trim().lowercase()]

    /**
     * 动作的规范名，用于技能白名单比对。
     *
     * 直接取 [ToolCatalog] 里的名字 —— 白名单里存的是规范名，
     * 用户用 `rm` 还是 `delete` 写指令都归一到同一个值。
     */
    fun actionName(action: Command.Action): String = ToolCatalog.toolOf(action).name

    /** 全部合法动作名，用于给用户提示。 */
    val ALL_ACTION_NAMES: List<String> by lazy {
        Command.Action.entries.map { actionName(it) }
    }

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
            val label = action?.let { ToolCatalog.toolOf(it).label } ?: match.groupValues[1]
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
