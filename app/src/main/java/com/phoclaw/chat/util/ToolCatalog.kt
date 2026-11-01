package com.phoclaw.chat.util

import com.phoclaw.chat.util.CommandParser.Command.Action

/**
 * 工具元数据的**单一数据源**。
 *
 * 在此之前，同一个工具的信息散落在四处：
 * - [CommandParser] 的正则、别名映射、展示标签
 * - `CommandDispatcher` 的执行分支
 * - `CredentialStore` 里手写的「指令格式」「各指令说明」「规则」
 * - 各处的 `needsApproval` / `describeDanger`
 *
 * 新增一个指令要同步改四个地方，漏一处就会出现「模型不知道有这个工具」
 * 或「提示词里写了但解析不出来」的错位。现在统一收在这里：
 * 解析器从 [tools] 生成正则与别名，提示词生成器从同一份数据拼出示例与表格。
 *
 * 新增工具的步骤只剩两步：① 这里加一条 [Tool]；② 在 `CommandDispatcher`
 * 补对应的执行分支。提示词会自动带上它。
 */
object ToolCatalog {

    /**
     * 一个工具的完整描述。
     *
     * @param action 内部枚举值
     * @param name 规范名，同时也是提示词里写的名字
     * @param aliases 兼容别名。模型未必每次都写对，多收几个词比报错便宜
     * @param label 界面展示的中文标签（指令块会被替换成「🔧 [标签] 参数」）
     * @param signature 参数签名，用于说明表格
     * @param summary 一句话用途
     * @param example 示例块正文（**不含围栏**）。多行内容直接换行即可
     * @param extraExamples 额外示例。少数工具需要展示两种用法（如 http 的 GET/POST）
     * @param rules 该工具专属的规则，会归到提示词的规则区
     */
    data class Tool(
        val action: Action,
        val name: String,
        val aliases: List<String> = emptyList(),
        val label: String,
        val signature: String,
        val summary: String,
        val example: String? = null,
        val extraExamples: List<String> = emptyList(),
        val rules: List<String> = emptyList()
    )

    // ------------------------------------------------------------------ 工具表

    val tools: List<Tool> = listOf(
        Tool(
            action = Action.LIST,
            name = "list",
            aliases = listOf("ls"),
            label = "列出",
            signature = "list <目录>",
            summary = "列出目录下的文件和子目录",
            example = "."
        ),
        Tool(
            action = Action.TREE,
            name = "tree",
            aliases = emptyList(),
            label = "目录树",
            signature = "tree <目录>",
            summary = "递归展示目录树（默认 3 层）",
            example = "."
        ),
        Tool(
            action = Action.READ,
            name = "read",
            aliases = emptyList(),
            label = "读取",
            signature = "read <文件>",
            summary = "读取文件全文",
            example = "src/main.js"
        ),
        Tool(
            action = Action.WRITE,
            name = "write",
            aliases = emptyList(),
            label = "写入",
            signature = "write <文件>",
            summary = "写入文件（覆盖原有内容，父目录自动创建）",
            example = "src/main.js\n文件完整内容",
            rules = listOf(
                "**改代码优先用 `write` 重写整个文件**，确保内容完整。不要输出「此处省略」「...」之类的占位符——那会让文件损坏。少量追加才用 `append`。",
                "`write` 代码块内必须是文件**完整**内容，不要带行号，不要加额外说明文字。",
                "**文件后缀要写全**：创建源码文件时把扩展名带上（`test.py` 而不是 `test`），系统不会替你猜。"
            )
        ),
        Tool(
            action = Action.APPEND,
            name = "append",
            aliases = emptyList(),
            label = "追加",
            signature = "append <文件>",
            summary = "在文件末尾追加内容",
            example = "src/main.js\n追加到末尾的内容"
        ),
        Tool(
            action = Action.SEARCH,
            name = "search",
            aliases = listOf("grep", "find"),
            label = "搜索",
            signature = "search <关键词> [目录]",
            summary = "搜索**工作区文件内容**，返回「文件:行号: 内容」",
            example = "TODO src"
        ),
        Tool(
            action = Action.INFO,
            name = "info",
            aliases = listOf("stat"),
            label = "详情",
            signature = "info <路径>",
            summary = "查看大小、类型、修改时间",
            example = "src/main.js"
        ),
        Tool(
            action = Action.MKDIR,
            name = "mkdir",
            aliases = listOf("md"),
            label = "新建目录",
            signature = "mkdir <目录>",
            summary = "创建目录",
            example = "src/components"
        ),
        Tool(
            action = Action.COPY,
            name = "copy",
            aliases = listOf("cp"),
            label = "复制",
            signature = "copy <源> -> <目标>",
            summary = "复制文件或目录（目录递归复制）",
            example = "src/a.js -> src/b.js"
        ),
        Tool(
            action = Action.MOVE,
            name = "move",
            aliases = listOf("mv"),
            label = "移动",
            signature = "move <源> -> <目标>",
            summary = "移动或重命名",
            example = "src/old.js -> src/new.js"
        ),
        Tool(
            action = Action.DELETE,
            name = "delete",
            aliases = listOf("del", "rm"),
            label = "删除",
            signature = "delete <路径>",
            summary = "删除文件或目录",
            example = "src/tmp.js",
            rules = listOf(
                "**删除操作要谨慎**：删除前先确认路径正确，不要删除用户可能还需要的东西。"
            )
        ),
        Tool(
            action = Action.WEBSEARCH,
            name = "websearch",
            aliases = listOf("web_search", "wsearch", "webs", "netsearch"),
            label = "联网搜索",
            signature = "websearch <关键词>",
            summary = "**联网搜索**互联网，返回摘要与来源链接",
            example = "2026年最新的 Kotlin 版本",
            rules = listOf(
                "**联网搜索的时机**：当问题涉及实时信息（新闻、股价、天气、最新版本号、近期事件）或你不确定的事实，先用 `websearch 关键词` 查证再回答。不要凭记忆猜测时效性内容。",
                "不要为了「显得严谨」而滥用搜索：常识、代码写法、数学计算这类稳定知识直接回答即可。",
                "引用搜索结果时，把来源链接一并给出，方便用户核对。"
            )
        ),
        Tool(
            action = Action.WEBFETCH,
            name = "webfetch",
            aliases = listOf("web_fetch", "fetch", "urlfetch", "geturl"),
            label = "抓取网页",
            signature = "webfetch <网址...>",
            summary = "**抓取指定网页正文**，可一次给多个网址（空白分隔），返回清洗后的 Markdown",
            example = "https://example.com/article"
        ),
        Tool(
            action = Action.HTTP,
            name = "http",
            aliases = listOf("httprequest", "http_request", "curl", "request"),
            label = "HTTP 请求",
            signature = "http <方法> <网址>",
            summary = "**发起 HTTP 请求**，可选在下方写请求头与请求体",
            example = "GET https://api.github.com/users/octocat\nAccept: application/vnd.github+json",
            extraExamples = listOf(
                "POST https://httpbin.org/post\nContent-Type: application/json\n\n{\"name\": \"PhoClaw\", \"version\": \"1.0\"}"
            ),
            rules = listOf(
                "**`http` 用之前想清楚**：POST / PUT / PATCH / DELETE 会改变服务端状态，执行前会弹确认框让用户批准；GET 之类的只读请求不会。不要把用户的隐私数据往不明服务器发。"
            )
        ),
        Tool(
            action = Action.RUN,
            name = "run",
            aliases = listOf("exec", "execute", "runfile"),
            label = "运行",
            signature = "run <文件或语言> [参数...]",
            summary = "**运行工作区里的代码**，支持 Python / JS / C++ / C / Java / Go / Rust / Shell 等 30+ 种语言",
            example = "main.py",
            extraExamples = listOf("python\nprint(1 + 1)"),
            rules = listOf(
                "**`run` 的两种用法**：① `run 文件名` 直接运行工作区里的文件；② `run 语言名` + 代码块正文，运行临时代码（适合先验证一小段逻辑再写进文件）。",
                "**`run` 受「代码执行」开关控制**（当前开关状态见下一条）。",
                "**远程执行，代码会发送到 wandbox.org**。不要把 API Key、密码、私人数据写进要运行的代码里。",
                "**运行环境是隔离的**：代码只能读写本次提交的代码本身，**看不到工作区的其它文件**。需要数据时把内容直接写进代码或通过标准输入传入。",
                "**二进制文件跑不了**：Android 10+ 禁止应用执行可写目录里的 ELF 文件，这是系统限制。遇到 `.so` / 无扩展名的可执行文件，直接告诉用户不支持，不要尝试 `chmod`。",
                "**编译型语言会有编译阶段**：C++ / Rust / Go 等如果编译失败，把编译器的错误原样呈现给用户，不要自己臆测修改。"
            )
        )
    )

    /** 规范名 → 工具，供解析器与执行器查表。 */
    val byName: Map<String, Tool> = tools.associateBy { it.name }

    /** 所有可接受的写法（规范名 + 别名）→ Action。 */
    val aliasToAction: Map<String, Action> =
        tools.flatMap { tool -> (listOf(tool.name) + tool.aliases).map { it.lowercase() to tool.action } }
            .toMap()

    /** 正则里用的动作名分支，按长度倒序，避免 `search` 抢先匹配 `websearch`。 */
    val actionPattern: String =
        aliasToAction.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }

    fun toolOf(action: Action): Tool = tools.first { it.action == action }

    // ------------------------------------------------------------------ 规则区

    /**
     * 通用规则 —— 与具体工具无关的行为约束。
     *
     * 编号在生成时统一加，这里不写死数字，避免插一条就全篇错位。
     */
    val generalRules: List<String> = listOf(
        "**先看再改**：修改现有代码前，先用 `list` / `tree` / `read` 了解结构，不要凭猜测直接覆盖。",
        "路径一律用相对路径（相对于工作区根目录），不要以 `/` 开头，**不要使用 `..`**（会被拒绝）。",
        "`copy` 和 `move` 用 `->` 分隔源和目标，例如 `copy src/a.js -> src/b.js`。",
        "一次回复可以包含多个指令块，会**按书写顺序**执行，结果一次性反馈给你。",
        "如果用户只是聊天或问问题，正常回答即可，不需要输出任何指令。",
        "不要在回复里解释指令格式本身，直接使用即可。",
        "**善用 Markdown 排版**：界面支持下列语法，适当使用能让回答更易读——不要把所有内容堆成一大段纯文本。\n" +
                "    - 标题 `#` `##` `###`\n" +
                "    - 粗体 `**文字**`、斜体 `*文字*`、粗斜体 `***文字***`\n" +
                "    - 删除线 `~~文字~~`、下划线 `<u>文字</u>`\n" +
                "    - 行内代码 `` `代码` ``、代码块 ``` ```lang ```\n" +
                "    - 有序 / 无序列表、任务列表 `- [ ] 待办` / `- [x] 已完成`\n" +
                "    - 引用 `>`、分隔线 `---`、表格\n" +
                "    - 链接 `[文字](网址)`（可点击打开）；裸网址如 `https://example.com` 会自动变成可点链接\n" +
                "    - 引用式链接 `[文字][id]` + 文末 `[id]: 网址` —— 长文档里反复引用同一网址时用它，正文更干净\n" +
                "    - 图片 `![说明](图片网址)`（会直接显示出来，仅支持 http/https 网络图片）",
        "回复保持简洁，用中文。"
    )

    /**
     * 工具之间的**分工说明** —— 单看任何一个工具的说明都写不出来的那句话。
     *
     * 比如「找内容用 websearch，读网页用 webfetch，调接口用 http」，
     * 它约束的是三者之间的选择，放在任一工具下都不合适，因此单列。
     */
    val groupRules: List<String> = listOf(
        "**三个联网工具的分工**，不要用错：\n" +
                "    - `websearch` —— **你不知道在哪里**，让搜索引擎去找。用关键词，不是网址。\n" +
                "    - `webfetch` —— **你已经知道网址**，要读它的内容。用户贴了个链接让你看，或者是搜索结果的第 3 条你想细读，都用它。它比 websearch 更省钱也更准，因为不必再搜一遍。\n" +
                "    - `http` —— 要**调用接口**而不是读网页时用。比如查 GitHub API 拿 JSON、给某个服务发一条 POST。参数行写 `方法 网址`，需要请求头就写在下一行（`名字: 值`），空行之后的内容作为请求体。\n" +
                "    - 判断口诀：**找内容用 websearch，读网页用 webfetch，调接口用 http。**"
    )
}
